package io.github.psd2live.ui.utils

import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.plaf.metal.MetalFileChooserUI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeFilePickerTest {
    @Test fun workerAndUiCallersUseTheEventThread() {
        assertFalse(EventQueue.isDispatchThread())
        assertTrue(NativeFilePicker.onPickerThread { EventQueue.isDispatchThread() })
        assertTrue(NativeFilePicker.onPickerThread {
            NativeFilePicker.onPickerThread { EventQueue.isDispatchThread() }
        })
    }

    @Test fun linuxChooserKeepsFiltersNavigationAndDefaultsWithoutChangingGlobalLookAndFeel() {
        val directory = Files.createTempDirectory("picker-test").toFile()
        try {
            NativeFilePicker.onPickerThread {
                val lookAndFeel = UIManager.getLookAndFeel()
                val chooser = NativeFilePicker.linuxChooser("Open source", listOf("psd", "json", "zip"), "Sources",
                    File(directory, "old.PSD").path, null, JFileChooser.FILES_ONLY, false)
                assertIs<MetalFileChooserUI>(chooser.ui)
                chooser.updateUI()
                assertIs<MetalFileChooserUI>(chooser.ui)
                assertSame(lookAndFeel, UIManager.getLookAndFeel())
                assertEquals(directory, chooser.currentDirectory)
                assertTrue(chooser.fileFilter.accept(directory))
                assertTrue(chooser.fileFilter.accept(File(directory, "character.PSD")))
                assertTrue(chooser.fileFilter.accept(File(directory, "layout.json")))
                assertTrue(chooser.fileFilter.accept(File(directory, "layout.zip")))
                assertFalse(chooser.fileFilter.accept(File(directory, "wrong.txt")))
                assertFalse(chooser.isAcceptAllFileFilterUsed)
                assertEquals(null, chooser.selectedFile)

                val save = NativeFilePicker.linuxChooser("Save", listOf("psd2live"), "Project", directory.path,
                    "character.psd2live", JFileChooser.FILES_ONLY, false)
                assertEquals(File(directory, "character.psd2live"), save.selectedFile)
                val materials = NativeFilePicker.linuxChooser("Materials", listOf("psd", "png"), "Materials",
                    directory.path, null, JFileChooser.FILES_AND_DIRECTORIES, true)
                assertEquals(JFileChooser.FILES_AND_DIRECTORIES, materials.fileSelectionMode)
                assertTrue(materials.isMultiSelectionEnabled)
                val folders = NativeFilePicker.linuxChooser("Folder", emptyList(), "", directory.path, null,
                    JFileChooser.DIRECTORIES_ONLY, false)
                assertEquals(JFileChooser.DIRECTORIES_ONLY, folders.fileSelectionMode)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun saveExtensionsPreserveCaseAndPaths() {
        val file = File("folder with spaces", "模型")
        assertEquals(File(file.parentFile, "模型.psd2live"), NativeFilePicker.withExtension(file, "psd2live"))
        val upper = File("folder with spaces", "模型.PSD")
        assertEquals(upper, NativeFilePicker.withExtension(upper, "psd"))
    }
}
