package io.github.psd2live.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layers a host without a window runs on never reach into the editor: the application layer and the agent,
 * project, core and history packages and the headless entry do not name the UI package or Compose.
 */
class LayerDependencyTest {
	private val sources = Path.of("src/main/kotlin/io/github/psd2live")
	private val forbidden = Regex("""\bio\.github\.psd2live\.ui\b|\bandroidx\.compose\b""")
	private val productReference = Regex("""\bio\.github\.psd2live\.([a-z][\w]*)(?:\.[\w*]+)+""")
	private fun code(text: String) = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
		.replace(Regex("//[^\\r\\n]*"), "")
	private fun files(layer: String) = Files.walk(sources.resolve(layer)).use { paths ->
		paths.filter { it.extension == "kt" }.toList()
	}
	private fun references(file: Path) = productReference.findAll(code(file.readText())).map { it.value }.toSet()
	private fun layer(reference: String) = reference.removePrefix("io.github.psd2live.").substringBefore('.')
	private fun symbol(reference: String): String {
		val parts = reference.split('.')
		val type = parts.indexOfFirst { it.firstOrNull()?.isUpperCase() == true }
		return if (type < 0) reference else parts.take(type + 1).joinToString(".")
	}
	private fun exception(file: Path, reference: String) =
		"${sources.relativize(file).toString().replace('\\', '/')}: ${symbol(reference)}"
	private fun exceptions() = Path.of("src/test/resources/architecture/product-layer-exceptions.txt").readText()
		.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toSortedSet()

	@Test fun lowerLayersNeverCallApplicationOrEntryAdapters() {
		val offenders = listOf("core", "project", "history", "document", "util").flatMap { owner ->
			files(owner).flatMap { file -> references(file).filter { reference ->
				layer(reference) in setOf("application", "agent", "ui") ||
					(owner in setOf("document", "util", "history") && layer(reference) in setOf("core", "project"))
			}.map { "${sources.relativize(file)}: $it" } }
		}
		assertEquals(emptyList(), offenders)
	}

	@Test fun cyclicExceptionsAreExactAndShrinkWithTheirCallers() {
		val actual = listOf("core", "project", "render").flatMap { owner ->
			files(owner).flatMap { file -> references(file).filter {
				layer(it) in setOf("core", "project", "render") && layer(it) != owner
			}.map { exception(file, it) } }
		}.toSortedSet()
		assertTrue(actual.none { it.endsWith(".*") }, "Cross-layer wildcard imports hide new dependencies")
		assertEquals(exceptions(), actual, "Remove resolved entries; do not broaden a file or package exemption")
	}

	@Test fun productLayersIntroduceNoNewCycles() {
		val layers = Files.list(sources).use { paths -> paths.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList().toSet() }
		val edges = layers.associateWith { owner -> files(owner).flatMap(::references).map(::layer).toSet() - owner }
		fun reachable(from: String, to: String, seen: MutableSet<String> = mutableSetOf()): Boolean =
			from == to || (seen.add(from) && edges[from].orEmpty().any { reachable(it, to, seen) })
		val cycles = layers.map { from -> layers.filter { to -> reachable(from, to) && reachable(to, from) }.toSet() }
			.filter { it.size > 1 }.toSet()
		// Keep existing edges in the graph: removing them would hide a new layer joining their cycle.
		// The companion test freezes every individual reference inside this existing component.
		assertTrue((cycles - setOf(setOf("core", "project", "render"))).isEmpty(), "New package cycle: $cycles")
	}

	@Test fun headlessLayersNameNoUi() {
		val offenders = listOf("application", "agent", "project", "core", "history", "document", "util").flatMap { layer ->
			Files.walk(sources.resolve(layer)).use { paths ->
				paths.filter { it.extension == "kt" }.toList().mapNotNull { file ->
					forbidden.find(file.readText())?.let { "${sources.relativize(file)}: ${it.value}" }
				}
			}
		} + listOf(sources.resolve("HeadlessMcp.kt")).mapNotNull { file ->
			forbidden.find(file.readText())?.let { "${sources.relativize(file)}: ${it.value}" }
		}
		assertEquals(emptyList(), offenders, "these files reach into the UI")
	}
}
