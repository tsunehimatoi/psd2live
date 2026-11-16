package io.github.psd2live.tools

import androidx.compose.material.Text
import androidx.compose.ui.awt.ComposeWindow
import io.github.psd2live.ui.utils.NativeFilePicker
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.nio.file.Files
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.Timer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Opt-in picker checks: PSD2LIVE_TOOLS=1 ./gradlew :test --tests '*LinuxFilePickerTool'.
 * Also check the actual editor manually: browse and open a PSD/project, save a disposable copy, cancel/reopen,
 * decline overwriting an existing target, and verify that each dialog paints and remains above its owner.
 */
class LinuxFilePickerTool {
    private fun <T> edt(block: () -> T): T = NativeFilePicker.onPickerThread(block)

    private fun withWindow(block: (ComposeWindow) -> Unit) {
        requireTools()
        assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true) && !GraphicsEnvironment.isHeadless())
        val window = edt {
            ComposeWindow().apply {
                title = "PSD2Live Linux file picker regression"
                isUndecorated = true
                setContent { Text("Linux file chooser regression") }
                setBounds(180, 160, 900, 600)
                isVisible = true
            }
        }
        try { block(window) } finally { edt { window.dispose() } }
    }

    /** Drive the real modal picker through the EDT's nested loop; inspect it before approving/cancelling. */
    private fun <T> pick(owner: Window, action: (JFileChooser) -> Unit, open: () -> T): T {
        var failure: Throwable? = null
        val timer = edt {
            Timer(100, null).apply {
                var attempts = 0
                addActionListener {
                    val dialog = owner.ownedWindows.filterIsInstance<JDialog>().firstOrNull { it.isShowing }
                    val chooser = dialog?.contentPane?.components?.filterIsInstance<JFileChooser>()?.firstOrNull()
                    if (chooser != null) {
                        stop()
                        try {
                            assertSame(owner, dialog.owner)
                            assertTrue(dialog.isModal)
                            assertTrue(NativeFilePicker.isOpen)
                            action(chooser)
                        } catch (t: Throwable) { failure = t; chooser.cancelSelection() }
                    } else if (++attempts > 50) {
                        stop()
                        failure = AssertionError("Picker did not appear")
                        owner.ownedWindows.forEach { it.dispose() }
                    }
                }
                start()
            }
        }
        val result = try { open() } finally { edt { timer.stop() } }
        failure?.let { throw it }
        assertFalse(NativeFilePicker.isOpen)
        assertFalse(edt { owner.ownedWindows.any { it.isDisplayable } })
        return result
    }

    @Test @Timeout(40)
    fun openSaveCancelDirectoriesAndMultipleSelectionAfterSkiaLoads() = withWindow { window ->
        // Give the Compose window a frame, reproducing the load order in the real editor.
        Thread.sleep(500)
        val dir = Files.createTempDirectory("linux-picker").toFile()
        try {
            val psd = File(dir, "模型 with spaces.PSD").apply { writeText("selection fixture") }
            val png = File(dir, "texture.png").apply { writeText("selection fixture") }
            assertEquals(psd.absolutePath, pick(window, {
                assertEquals(dir, it.currentDirectory)
                assertNull(NativeFilePicker.choosePsdFile(window)) // A second request only raises this picker.
                it.selectedFile = psd
                it.approveSelection()
            }) { NativeFilePicker.choosePsdFile(window, dir.path) })
            assertNull(pick(window, { it.cancelSelection() }) { NativeFilePicker.chooseProjectFile(window, dir.path) })
            assertEquals(File(dir, "new model.psd2live").absolutePath, pick(window, {
                assertEquals("new model.psd2live", it.selectedFile.name)
                it.selectedFile = File(dir, "new model")
                it.approveSelection()
            }) { NativeFilePicker.chooseSaveProjectFile(window, "new model", dir.path) })
            assertNull(pick(window, { it.cancelSelection() }) { NativeFilePicker.chooseSavePsdFile(window, "export", dir.path) })
            assertEquals(dir.absolutePath, pick(window, { it.selectedFile = dir; it.approveSelection() }) {
                NativeFilePicker.chooseDirectory(window, dir.path)
            })
            assertEquals(listOf(psd, png), pick(window, { it.selectedFiles = arrayOf(psd, png); it.approveSelection() }) {
                NativeFilePicker.chooseMaterialFiles(window)
            })
            // Exercise an actual call from the EDT too, not just worker callers marshalled to it.
            assertNull(pick(window, { it.cancelSelection() }) { edt { NativeFilePicker.choosePhysicsFile(window) } })
        } finally { dir.deleteRecursively() }
    }
}
