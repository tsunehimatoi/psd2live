package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in local corpus; source PSDs never enter the repository. */
class PsdBatchImportTest {
    @TestFactory fun localCorpus(): List<DynamicTest> {
        val directory = System.getenv("PSD2LIVE_IMPORT_DIRECTORY")?.let(Path::of) ?: return emptyList()
        val excluded = System.getenv("PSD2LIVE_IMPORT_EXCLUDE").orEmpty().split(';').toSet()
        val paths = Files.list(directory).use { files -> files.filter {
            it.fileName.toString().endsWith(".psd", true) && it.fileName.toString() !in excluded
        }.sorted().toList() }
        return paths.map { path -> DynamicTest.dynamicTest(path.fileName.toString()) { runBlocking {
            val out = Path.of("build/psd-corpus", path.fileName.toString() + ".txt")
            Files.createDirectories(out.parent)
            try {
                val source = PsdReader.read(Files.readAllBytes(path))
                val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
                    RigEditOverlay(modifierStack = RigModifierStack().toJson()), WorkspaceSettingsCodec.encode(PipelineConfig()))
                val (candidate, choices) = WorkspacePresetInitialize.prepare(document, WorkspaceInitialModifiers.RECOMMENDED)
                val (initialized, model) = WorkspacePresetInitialization.prepare(candidate, WorkspaceRasterWork.Direct, choices!!)
                assertEquals(source.layers.map { it.id }, initialized.source.layers.map { it.id })
                assertTrue(model.rig.puppet.drawables.isNotEmpty())
                val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
                val failures = stack.initializationFailures.map { (id, reason) ->
                    "${stack.instances.single { it.id == id }.type}: $reason"
                }
                val runtimeFailures = model.modifierEvaluation?.entries.orEmpty().filter { it.error != null }
                val result = "${source.widthPx}x${source.heightPx}; layers=${source.layers.size}; meshes=${model.rig.puppet.drawables.size}\n" +
                    "failures=$failures; runtime=${runtimeFailures.map { it.instance.type to it.error }}\n" +
                    runtimeFailures.joinToString("\n") { "${it.instance.type}\n${it.errorDetails}" } + "\n" + model.rig.modifierReport.toString()
                Files.writeString(out, result)
                println("${path.fileName}: ${result.lineSequence().take(2).joinToString("; ")}")
            } catch (failure: Exception) {
                Files.writeString(out, failure.stackTraceToString())
                throw failure
            }
        } } }
    }
}
