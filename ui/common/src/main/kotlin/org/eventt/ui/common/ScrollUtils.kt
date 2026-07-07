package org.eventt.ui.common

import androidx.compose.foundation.lazy.LazyListState

/**
 * Scrolls only if [index] isn't already on screen — used to keep a hotkey-driven "active item"
 * highlight in view as it cycles through a list, without visibly jumping the list on every
 * keypress when the item is already visible.
 */
suspend fun LazyListState.ensureVisible(index: Int) {
    if (index < 0) return
    val alreadyVisible = layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!alreadyVisible) {
        animateScrollToItem(index)
    }
}
