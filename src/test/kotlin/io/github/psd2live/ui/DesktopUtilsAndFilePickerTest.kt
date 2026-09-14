package io.github.psd2live.ui

import io.github.psd2live.ui.utils.DesktopUtils
import io.github.psd2live.ui.utils.NativeFilePicker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
		var openCount = 0
		var openedPath: Path? = null
		DesktopUtils.directoryOpener = { path ->
			openCount++
			openedPath = path
			true
		}

		val tempDir = Files.createTempDirectory("test-dir-debounce")
		try {
			// First call should invoke directory opener
			val firstResult = DesktopUtils.openDirectory(tempDir)
			assertTrue(firstResult, "First call must succeed")
			assertEquals(1, openCount, "Opener must be called once")
			assertEquals(tempDir.toAbsolutePath().normalize(), openedPath)

			// Immediate second call should be caught by debounce and return true without calling opener again
			val secondResult = DesktopUtils.openDirectory(tempDir)
			assertTrue(secondResult, "Subsequent call within debounce window must return true")
			assertEquals(1, openCount, "Opener must not be called again within debounce window")
		} finally {
			Files.deleteIfExists(tempDir)
		}
	}

	@Test
	fun testNativeFilePickerResetPickingState() {
		NativeFilePicker.resetPickingState()
	}
}

