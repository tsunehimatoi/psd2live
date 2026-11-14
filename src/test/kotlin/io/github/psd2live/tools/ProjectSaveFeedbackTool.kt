package io.github.psd2live.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.ui.components.ProjectSaveIndicator
import io.github.psd2live.ui.state.ProjectSaveFeedback
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*ProjectSaveFeedbackTool': real Compose controls, synthetic states. */
@EnabledIfEnvironmentVariable(named = "PSD2LIVE_TOOLS", matches = "1")
@OptIn(ExperimentalComposeUiApi::class)
class ProjectSaveFeedbackTool {
    @Test fun renderThemesLanguagesAndScaleAndClickRetry() = runBlocking {
        val previous = I18n.currentLanguage
        try {
            withContext(Dispatchers.Main) {
                for (language in AppLanguage.entries) for (colors in listOf(ToolColors.Dark, ToolColors.Light)) for (scale in listOf(1f, 1.5f)) {
                    I18n.setLanguage(language, persist = false)
                    var retries = 0
                    var retryBounds = Rect.Zero
                    val scene = ImageComposeScene((640 * scale).toInt(), (260 * scale).toInt(),
                        density = Density(scale), coroutineContext = currentCoroutineContext()) {
                        CompactToolTheme(colors) {
                            Column(Modifier.fillMaxSize().background(colors.windowBackground).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (key in listOf("project.feedback.draft", "project.feedback.committed", "project.feedback.recoverySaving",
                                    "project.feedback.recoverySaved", "project.saving", "project.feedback.saved")) {
                                    ProjectSaveIndicator(ProjectSaveFeedback(key)) { retries++ }
                                }
                                Box(Modifier.onGloballyPositioned { retryBounds = it.boundsInRoot() }) {
                                    ProjectSaveIndicator(ProjectSaveFeedback("project.feedback.recoveryFailed", "Disk full", true)) { retries++ }
                                }
                            }
                        }
                    }
                    try {
                        var time = 0L
                        repeat(8) { time += 16_000_000; scene.render(time).close(); yield() }
                        val file = File("build/tools/project-save-feedback/${language.tag}-${if (colors.isDark) "dark" else "light"}-${(scale * 100).toInt()}.png")
                        file.parentFile.mkdirs()
                        scene.render(time + 16_000_000).use { file.writeBytes(requireNotNull(it.encodeToData()).bytes) }
                        val point = Offset(retryBounds.center.x, retryBounds.center.y)
                        scene.sendPointerEvent(PointerEventType.Move, point)
                        scene.sendPointerEvent(PointerEventType.Press, point, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                        scene.sendPointerEvent(PointerEventType.Release, point, button = PointerButton.Primary)
                        repeat(3) { time += 16_000_000; scene.render(time).close(); yield() }
                        assertEquals(1, retries)
                    } finally { scene.close() }
                }
            }
        } finally { I18n.setLanguage(previous, persist = false) }
    }
}
