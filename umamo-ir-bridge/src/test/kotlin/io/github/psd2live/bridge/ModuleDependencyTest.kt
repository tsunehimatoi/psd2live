package io.github.psd2live.bridge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*

class ModuleDependencyTest {
    private val root = Path.of("..").toAbsolutePath().normalize()
    private val dependency = Regex("""project\(\s*(?:path\s*=\s*)?"(:[^\"]+)"""")
    private fun dependencies(module: String): Set<String> {
        val build = root.resolve(module.removePrefix(":").replace(':', '/')).resolve("build.gradle.kts")
        return dependency.findAll(build.readText()).map { it.groupValues[1] }.toSet()
    }
    private fun closure(module: String, seen: MutableSet<String> = mutableSetOf()): Set<String> {
        if (seen.add(module)) dependencies(module).forEach { closure(it, seen) }
        return seen
    }

    @Test fun bridgeDependsOnlyOnTheEngineAndNeutralModel() {
        assertEquals(setOf(":umamo", ":format-model"), dependencies(":umamo-ir-bridge"))
        assertFailsWith<ClassNotFoundException> { Class.forName("io.github.psd2live.targets.cubism.Cmo3Target") }
        val sources = root.resolve("umamo-ir-bridge/src/main/kotlin")
        val forbidden = Regex("""io\.github\.psd2live\.(targets|format\.compile)|org\.umamo\.(interop|format\.(cmo3|moc3))""")
        Files.walk(sources).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { file -> assertFalse(forbidden.containsMatchIn(file.readText()), "$file") }
        }
    }

    @Test fun genericAlgorithmsCannotPullCubismBackInTransitively() {
        for (module in listOf(":algorithms:binding", ":algorithms:swing", ":algorithms:simulation")) {
            val dependencies = closure(module)
            assertContains(dependencies, ":umamo-ir-bridge")
            assertFalse(dependencies.any { it.startsWith(":targets:") }, "$module: $dependencies")
        }
    }

    @Test fun neutralMitContractsCannotReferenceGplImplementations() {
        for (module in listOf(":format-model", ":format-compile", ":algorithm-api")) {
            assertTrue(closure(module).all { it in setOf(":format-model", ":format-compile", ":algorithm-api") }, module)
            val sources = root.resolve(module.removePrefix(":")).resolve("src/main/kotlin")
            val forbidden = Regex("""org\.umamo|io\.github\.psd2live\.(bridge|targets|core|project|application|algorithms)\b""")
            Files.walk(sources).use { paths ->
                paths.filter { it.toString().endsWith(".kt") }.forEach { file ->
                    // algorithm-api retains legacy public package names; a declaration is not a dependency.
                    val references = file.readText().replace(Regex("(?m)^package .*\\r?\\n"), "")
                    assertFalse(forbidden.containsMatchIn(references), "$file")
                }
            }
        }
    }
}
