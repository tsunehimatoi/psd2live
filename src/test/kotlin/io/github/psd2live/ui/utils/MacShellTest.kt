package io.github.psd2live.ui.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MacShellTest {
	@TempDir lateinit var temp: Path

	@Test fun cliCommandsAreNeverFilesAndDirectoriesAreRejected() {
		assertNull(MacShell.guiFiles(listOf("targets"))) // The source tree has a targets/ directory.
		assertNull(MacShell.guiFiles(listOf("mcp")))
		assertNull(MacShell.guiFiles(listOf("export")))
		assertNull(MacShell.guiFiles(listOf(Files.createDirectory(temp.resolve("folder.psd")).toString())))
		assertNull(MacShell.guiFiles(listOf(Files.createFile(temp.resolve("notes.txt")).toString())))
		assertNull(MacShell.guiFiles(listOf(temp.resolve("missing.psd").toString())))
		assertNull(MacShell.guiFiles(listOf("--help")))
	}

	@Test fun existingDocumentsWithSpacesAndUnicodeOpenInTheGui() {
		val paths = listOf("角色 PSD.PSD", "user's project.psd2live").map { Files.createFile(temp.resolve(it)).toString() }
		assertEquals(paths, MacShell.guiFiles(paths))
		assertEquals(emptyList(), MacShell.guiFiles(emptyList()))
	}

	@Test fun filesWaitForThePreviousOpenIncludingItsSavePrompt() = runBlocking {
		withTimeout(5_000) {
			val requests = FileOpenRequests()
			val firstStarted = CompletableDeferred<Unit>()
			val finishFirst = CompletableDeferred<Unit>()
			val secondFinished = CompletableDeferred<Unit>()
			val opened = mutableListOf<String>()
			requests.enqueue("first.psd")
			val consumer = launch {
				requests.consume { path ->
					opened.add(path)
					if (path == "first.psd") {
						firstStarted.complete(Unit)
						finishFirst.await()
					} else secondFinished.complete(Unit)
				}
			}
			try {
				firstStarted.await()
				requests.enqueue("second.psd2live")
				yield()
				assertEquals(listOf("first.psd"), opened)
				assertFalse(secondFinished.isCompleted)
				finishFirst.complete(Unit)
				secondFinished.await()
				assertEquals(listOf("first.psd", "second.psd2live"), opened)
			} finally { consumer.cancelAndJoin() }
		}
	}
}
