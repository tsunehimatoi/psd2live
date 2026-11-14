package io.github.psd2live.tools

import io.github.psd2live.application.run

import io.github.psd2live.core.Cmo3ModelImport
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.PipelineResult
import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.core.RigIrCompiler
import io.github.psd2live.targets.cubism.PuppetIr
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliNoDeformersTool {
    @Test fun exportsHistoricalCanvasMeshMotion() {
        requireTools()
        val sample = Sample.fromEnvironment()
        val config = PipelineConfig(meshSpacing = 64, generateDeformers = false, generatePhysics = true, exportMotions = true,
            exportCmo3 = true, exportMoc3 = true, exportJson = true)
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val baseline = pipeline.buildPreview(sample.path, config)
        val expected = RigIrCompiler.compile(baseline, tileArt = true)
        val destination = output("cli-no-deformers").toPath()
        val previous = pipeline.export(baseline, sample.path.fileName.toString(), destination.resolve("historical"))
        RigBuildProfile.reset()
        val profiling = RigBuildProfile.recording
        RigBuildProfile.recording = true
        val candidate = try {
            pipeline.run(baseline.analysis.source, sample.path.fileName.toString(), destination.resolve("source"), config)
        } finally {
            RigBuildProfile.recording = profiling
        }
        val profile = RigBuildProfile.snapshot()
        RigBuildProfile.reset()
        assertEquals(1L, profile["build: source without deformers"]?.second)
        assertFalse("build: total" in profile)
        val actual = RigIrCompiler.compile(candidate.previewModel, tileArt = true)
        val differences = actual.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { field -> field.isAccessible = true; field.get(expected) != field.get(actual) }.map { it.name }
        assertTrue(expected == actual, "IR fields differ: $differences")
        assertTrue(actual.meshes.any { it.offsets?.axes.orEmpty().isNotEmpty() })
        assertTrue(actual.deformers.all { it.id.endsWith("_Shape0") })
        assertTrue(actual.physics.groups.isNotEmpty() && actual.clips.isNotEmpty())
        val files = previous.exportedFiles.associateBy { it.path.fileName }
        assertEquals(files.keys, candidate.exportedFiles.mapTo(linkedSetOf()) { it.path.fileName })
        fun artifact(result: PipelineResult, extension: String) =
            result.exportedFiles.single { it.path.fileName.toString().endsWith(extension) }.path
        assertEquals(-1L, Files.mismatch(artifact(previous, ".moc3"), artifact(candidate, ".moc3")))
        val oldCmo = Cmo3ModelImport.read(Files.readAllBytes(artifact(previous, ".cmo3")))
        val newCmo = Cmo3ModelImport.read(Files.readAllBytes(artifact(candidate, ".cmo3")))
        val oldRig = editorReadback(oldCmo)
        val newRig = editorReadback(newCmo)
        val cmoDifferences = newRig.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { field -> field.isAccessible = true; field.get(oldRig) != field.get(newRig) }.map { it.name }
        assertTrue(oldRig == newRig, "CMO3 readback differs: $cmoDifferences")
        assertEquals(oldCmo.physics, newCmo.physics)
        assertEquals(oldCmo.fps, newCmo.fps)
    }

    private fun editorReadback(document: Cmo3ModelImport.Document): io.github.psd2live.format.model.RigIR {
        val ir = PuppetIr.toIr(document.puppet)
        // Cubism assigns fresh editor GUIDs to every exported tile and artwork layer.
        val tiles = ir.textures.tiles.mapIndexed { index, tile -> tile.id to "tile-$index" }.toMap()
        val sources = linkedMapOf<String, String>()
        val layers = linkedMapOf<Pair<String, String>, String>()
        val normalized = ir.textures.tiles.map { tile ->
            val source = tile.source?.let {
                it.copy(source = sources.getOrPut(it.source) { "source-${sources.size}" },
                    layer = layers.getOrPut(it.source to it.layer) { "layer-${layers.size}" })
            }
            tile.copy(id = tiles.getValue(tile.id), source = source, replaces = tile.replaces?.let(tiles::getValue))
        }
        return ir.copy(meshes = ir.meshes.map { mesh -> mesh.copy(tile = mesh.tile?.let(tiles::getValue)) },
            textures = ir.textures.copy(tiles = normalized,
                tileArt = ir.textures.tileArt.map { art -> art.copy(tile = tiles.getValue(art.tile)) }),
            authoring = ir.authoring.copy(
                sources = ir.authoring.sources.map { source ->
                    source.copy(id = sources.getOrPut(source.id) { "source-${sources.size}" },
                        layers = source.layers.map { layer ->
                            layer.copy(key = layers.getOrPut(source.id to layer.key) { "layer-${layers.size}" })
                        })
                },
                paths = ir.authoring.paths.mapIndexed { index, path -> path.copy(id = "path-$index") },
            ))
    }
}
