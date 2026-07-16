package org.eventt.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// What the app is currently "looking at": either a single character's own data, or a
// corporation's data — viewed through one locally-added member character's token, since ESI
// has no such thing as a corp-wide credential.
sealed class ViewContext {
    data class Character(
        val charId: Int,
    ) : ViewContext()

    data class Corporation(
        val corporationId: Int,
        val corporationName: String,
        val actingCharacterId: Int,
    ) : ViewContext()

    // The character whose token authorizes ESI calls for this context.
    val actingCharId: Int
        get() =
            when (this) {
                is Character -> charId
                is Corporation -> actingCharacterId
            }
}

object AppState {
    private val _selectedContext = MutableStateFlow<ViewContext?>(null)
    val selectedContext: StateFlow<ViewContext?> = _selectedContext.asStateFlow()

    // Derived from selectedContext, updated synchronously alongside it (not via a reactive
    // .map/.stateIn — that would update on a background coroutine and could read stale between
    // a selectCharacter()/selectCorporation() call and the derived flow catching up).
    // Screens that only need "which character to act as" (Dashboard, Contracts,
    // MarketAnalysisScreen) keep reading this exactly as before the corp switcher existed.
    private val _selectedCharId = MutableStateFlow<Int?>(null)
    val selectedCharId: StateFlow<Int?> = _selectedCharId.asStateFlow()

    private const val SETTING_KEY = "app.selected_context"

    fun init() {
        val chars =
            try {
                CharacterDao.getAll()
            } catch (_: Exception) {
                emptyList()
            }
        val saved = StaticDataDao.getSetting(SETTING_KEY)
        val decoded = decode(saved, chars)
        setContext(decoded ?: chars.firstOrNull()?.let { ViewContext.Character(it.id) })
    }

    fun selectCharacter(id: Int) {
        setContext(ViewContext.Character(id))
        persist()
    }

    fun selectCorporation(
        corporationId: Int,
        corporationName: String,
        actingCharacterId: Int,
    ) {
        setContext(ViewContext.Corporation(corporationId, corporationName, actingCharacterId))
        persist()
    }

    // Re-evaluate selection after characters are added or removed. Falls back to another
    // character if the current character (or the acting character behind a corp selection)
    // no longer exists locally.
    fun refreshCharacters() {
        val chars =
            try {
                CharacterDao.getAll()
            } catch (_: Exception) {
                emptyList()
            }
        val current = _selectedContext.value
        val stillValid = current != null && chars.any { it.id == current.actingCharId }
        when {
            stillValid -> {
                Unit
            }

            chars.isNotEmpty() -> {
                selectCharacter(chars.first().id)
            }

            else -> {
                setContext(null)
                StaticDataDao.setSetting(SETTING_KEY, "")
            }
        }
    }

    private fun setContext(ctx: ViewContext?) {
        _selectedContext.value = ctx
        _selectedCharId.value = ctx?.actingCharId
    }

    private fun persist() {
        val encoded =
            when (val ctx = _selectedContext.value) {
                is ViewContext.Character -> "char:${ctx.charId}"
                is ViewContext.Corporation -> "corp:${ctx.corporationId}:${ctx.actingCharacterId}"
                null -> ""
            }
        StaticDataDao.setSetting(SETTING_KEY, encoded)
    }

    private fun decode(
        saved: String?,
        chars: List<org.eventt.core.model.CharacterModel>,
    ): ViewContext? {
        if (saved.isNullOrEmpty()) return null
        val parts = saved.split(":")
        return when (parts.getOrNull(0)) {
            "char" -> {
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
                if (chars.any { it.id == id }) ViewContext.Character(id) else null
            }

            "corp" -> {
                val corpId = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val actingId = parts.getOrNull(2)?.toIntOrNull() ?: return null
                if (chars.none { it.id == actingId && it.corporationId == corpId }) return null
                val corpName =
                    try {
                        CorporationDao.getAll().firstOrNull { (it["id"] as? Int) == corpId }?.get("name") as? String
                    } catch (_: Exception) {
                        null
                    } ?: ""
                ViewContext.Corporation(corpId, corpName, actingId)
            }

            else -> {
                null
            }
        }
    }
}
