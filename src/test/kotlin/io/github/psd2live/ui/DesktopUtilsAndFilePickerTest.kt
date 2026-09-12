package io.github.psd2live.ui

import io.github.psd2live.ui.utils.DesktopUtils
import io.github.psd2live.ui.utils.NativeFilePicker
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUtilsAndFilePickerTest {

	@BeforeTest
	@AfterTest
	fun cleanState() {
		DesktopUtils.resetOpenDebounce()
		NativeFilePicker.resetPickingState()
	}

	@Test
	fun testOpenDirectoryRejectsInvalidPaths() {
		assertFalse(DesktopUtils.openDirectory(""), "Blank path should return false")
		assertFalse(DesktopUtils.openDirectory("   "), "Whitespace path should return false")
		assertFalse(DesktopUtils.openDirectory("non_existent_dir_123456789"), "Non-existent path should return false")

		val tempFile = Files.createTempFile("test-file", ".tmp")
		try {
			assertFalse(DesktopUtils.openDirectory(tempFile.toString()), "File path (non-directory) should return false")
		} finally {
			Files.deleteIfExists(tempFile)
		}
	}

	@Test
	fun testOpenDirectoryDebounceOnSameDirectory() {
		val tempDir = Files.createTempDirectory("test-dir-debounce")
		try {
			// First call may attempt to launch OS explorer or Desktop.open
			DesktopUtils.openDirectory(tempDir)

			// Immediate second call should be caught by debounce and return true
			val secondResult = DesktopUtils.openDirectory(tempDir)
			assertTrue(secondResult, "Subsequent call within debounce window must return true")
		} finally {
			Files.deleteIfExists(tempDir)
		}
	}

	@Test
	fun testNativeFilePickerResetPickingState() {
		NativeFilePicker.resetPickingState()
	}
}

