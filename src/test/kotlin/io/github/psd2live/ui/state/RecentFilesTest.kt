package io.github.psd2live.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentFilesTest {
	@Test
	fun classifiesProjectAndPsdExtensions() {
		assertEquals(RecentFileKind.PROJECT, classifyRecentPath("D:\\art\\hero.psd2live"))
		assertEquals(RecentFileKind.PSD, classifyRecentPath("/tmp/hero.psd"))
		assertEquals(RecentFileKind.PSD, classifyRecentPath("hero.PSB"))
		assertEquals(null, classifyRecentPath("notes.txt"))
	}

	@Test
	fun rememberMovesExistingPathToFrontAndCapsLength() {
		val existing = listOf("a.psd2live", "b.psd", "c.psd2live")
		assertEquals(
			listOf("B.psd", "a.psd2live", "c.psd2live"),
			rememberRecentPaths(existing, "B.psd"),
		)
		val filled = (1..RECENT_FILES_MAX).map { "$it.psd2live" }
		assertEquals(
			listOf("new.psd2live") + filled.dropLast(1),
			rememberRecentPaths(filled, "new.psd2live"),
		)
	}

	@Test
	fun rememberDropsMissingEntriesExceptTheNewPath() {
		val existing = listOf("gone.psd2live", "keep.psd", "also-gone.psd")
		assertEquals(
			listOf("fresh.psd2live", "keep.psd"),
			rememberRecentPaths(existing, "fresh.psd2live") { it == "keep.psd" || it == "fresh.psd2live" },
		)
	}

	@Test
	fun forgetRemovesMatchingPath() {
		assertEquals(
			listOf("keep.psd2live"),
			forgetRecentPath(listOf("gone.psd", "keep.psd2live"), "GONE.psd"),
		)
	}

	@Test
	fun recentFilesFromKeepsKnownKindsAndNames() {
		val files = recentFilesFrom(listOf("D:\\models\\hero.psd2live", "skip.bin", "art.psd"))
		assertEquals(listOf("hero.psd2live", "art.psd"), files.map { it.name })
		assertEquals(listOf(RecentFileKind.PROJECT, RecentFileKind.PSD), files.map { it.kind })
		assertTrue(files.first().directory.endsWith("models"))
	}
}
