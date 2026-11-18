package io.github.psd2live.ui.views

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import io.github.psd2live.ui.theme.CompactToolTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PresetRichnessSliderTest {
    @Test
    fun previewFollowsDragAndCommitsOnlyOnRelease() = runBlocking {
        withContext(Dispatchers.Main) {
            var selected by mutableStateOf(0)
            var enabled by mutableStateOf(true)
            var preview: Int? = null
            val commits = mutableListOf<Int>()
            val scene = ImageComposeScene(200, 40, density = Density(1f), coroutineContext = currentCoroutineContext()) {
                CompactToolTheme {
                    PresetRichnessSlider(5, selected, enabled, onPreviewIndex = { preview = it }) {
                        commits += it
                        selected = it
                        // The guided import disables controls while the selected preset is rebuilt.
                        enabled = false
                    }
                }
            }
            try {
                var frame = 0L
                fun render() { scene.render((frame++) * 16_000_000).close() }
                val primary = PointerButtons(isPrimaryPressed = true)
                render()
                scene.sendPointerEvent(PointerEventType.Press, Offset(55f, 12f), buttons = primary, button = PointerButton.Primary)
                render()
                assertEquals(1, preview)
                assertTrue(commits.isEmpty())
                scene.sendPointerEvent(PointerEventType.Move, Offset(145f, 12f), buttons = primary)
                render()
                assertEquals(3, preview)
                assertTrue(commits.isEmpty())
                scene.sendPointerEvent(PointerEventType.Release, Offset(145f, 12f), button = PointerButton.Primary)
                render()
                assertEquals(listOf(3), commits)
                assertNull(preview)

                enabled = true
                render()
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 12f), buttons = primary, button = PointerButton.Primary)
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 12f), button = PointerButton.Primary)
                render()
                assertEquals(listOf(3, 2), commits)
            } finally {
                scene.close()
            }
        }
    }
}
