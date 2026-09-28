package org.eventt.core.model

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * Localized string for code that is neither @Composable nor suspend (Swing dialogs, blocking
 * service callbacks). Prefer stringResource()/getString() where those are available.
 */
fun stringBlocking(
    res: StringResource,
    vararg args: Any,
): String = runBlocking { getString(res, *args) }

fun pluralBlocking(
    res: PluralStringResource,
    quantity: Int,
    vararg args: Any,
): String = runBlocking { getPluralString(res, quantity, *args) }
