package org.eventt.features.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Drives the real gesture over a list of clickable rows, the way the order tables use dragPick.
@OptIn(ExperimentalTestApi::class)
class RowPickDragTest {
    @Test
    fun `dragging across rows picks them without clicking a row, a plain click still clicks`() =
        runDesktopComposeUiTest(width = 300, height = 300) {
            val pick = RowPick().apply { visible = listOf(1L, 2L, 3L, 4L) }
            var clicks = 0
            setContent {
                val state = rememberLazyListState()
                LazyColumn(state = state, modifier = Modifier.testTag("list").dragPick(pick, state)) {
                    items(pick.visible) { id ->
                        Box(
                            Modifier.fillMaxWidth().height(40.dp).clickable {
                                if (!pick.click(id, ctrl = false, shift = false)) clicks++
                            },
                        )
                    }
                }
            }
            // Rows are 40px tall at density 1: from the middle of row 2 to the middle of row 4.
            val h = density.run { 40.dp.toPx() }
            onNodeWithTag("list").performMouseInput {
                moveTo(Offset(20f, h * 1.5f))
                press()
                moveTo(Offset(20f, h * 2.5f))
                moveTo(Offset(20f, h * 3.5f))
                release()
            }
            waitForIdle()
            pick.ids shouldBe setOf(2L, 3L, 4L)
            clicks shouldBe 0

            onNodeWithTag("list").performMouseInput { click(Offset(20f, h * 0.5f)) }
            waitForIdle()
            clicks shouldBe 1
            pick.ids shouldBe emptySet()
        }
}
