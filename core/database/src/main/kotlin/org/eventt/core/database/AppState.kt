package org.eventt.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppState {
    private val _selectedCharId = MutableStateFlow<Int?>(null)
    val selectedCharId: StateFlow<Int?> = _selectedCharId.asStateFlow()

    fun init() {
        val chars =
            try {
                CharacterDao.getAll()
            } catch (_: Exception) {
                emptyList()
            }
        val saved = StaticDataDao.getSetting("app.selected_char_id")?.toIntOrNull()
        _selectedCharId.value =
            when {
                saved != null && chars.any { it.id == saved } -> saved
                chars.isNotEmpty() -> chars.first().id
                else -> null
            }
    }

    fun selectCharacter(id: Int?) {
        _selectedCharId.value = id
        StaticDataDao.setSetting("app.selected_char_id", id?.toString() ?: "")
    }

    // Re-evaluate selection after characters are added or removed
    fun refreshCharacters() {
        val chars =
            try {
                CharacterDao.getAll()
            } catch (_: Exception) {
                emptyList()
            }
        val current = _selectedCharId.value
        when {
            current != null && chars.any { it.id == current } -> Unit
            chars.isNotEmpty() -> selectCharacter(chars.first().id)
            else -> _selectedCharId.value = null
        }
    }
}
