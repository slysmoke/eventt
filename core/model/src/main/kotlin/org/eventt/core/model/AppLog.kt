package org.eventt.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * App-wide error journal. Failure paths that used to swallow exceptions (or println them into
 * the void) report here instead, so "the dashboard shows zeros" has a visible explanation:
 * a topbar indicator lists the most recent entries.
 */
object AppLog {
    data class Entry(
        val time: String,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 50
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first, capped at [MAX_ENTRIES]. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun warn(
        tag: String,
        message: String,
    ) {
        println("[$tag] $message")
        val entry = Entry(LocalTime.now().format(timeFormat), tag, message)
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    fun warn(
        tag: String,
        e: Throwable,
    ) {
        if (e !is QuietFailure) warn(tag, e.message ?: (e::class.simpleName ?: "error"))
    }

    /** "[context]: [e]'s message" -- skipped for [QuietFailure]s, like the overload above. */
    fun warn(
        tag: String,
        context: String,
        e: Throwable,
    ) {
        if (e !is QuietFailure) warn(tag, "$context: ${e.message}")
    }

    fun clear() {
        _entries.value = emptyList()
    }
}

/** A failure the UI already explains elsewhere (e.g. the server-offline banner) -- not logged. */
interface QuietFailure
