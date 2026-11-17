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
        val included = System.getenv("PSD2LIVE_IMPORT_INCLUDE").orEmpty().split(';').filter(String::isNotBlank).toSet()
        val paths = Files.list(directory).use { files -> files.filter {
            it.fileName.toString().endsWith(".psd", true) && it.fileName.toString() !in excluded &&
                (included.isEmpty() || it.fileName.toString() in included)
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
                val snapshot = model.modifierEvaluation!!
                val detail = runtimeFailures.joinToString("\n") { entry ->
                    val index = snapshot.entries.indexOf(entry)
                    val before = if (index == 0) snapshot.authored else snapshot.outputs[index - 1]
                    val ids = entry.instance.targets.map { it.id }.toSet()
                    entry.instance.toString() + "\n" + before.meshes.filter { it.id in ids }.joinToString("\n") { mesh ->
                        "${mesh.id}: parent=${mesh.parent}; points=${mesh.geometry?.positions?.size}; " +
                            "axes=${mesh.offsets?.axes}; shapes=${mesh.shapes.map { it.parameter }}; channels=${mesh.channels.keys}; " +
                            "masks=${mesh.maskedBy}; texture=${mesh.textureSource}"
                    }
                }
                val fitting = if (runtimeFailures.any { it.instance.type.startsWith("mouth") } || System.getenv("PSD2LIVE_IMPORT_RENDER") == "1") {
                    val mouth = RigBuilder.rigContext(model.analysis, model.config, PreviewMeshCache()).fittedMouth(model.config)
                    "mouthFit=${mouth?.result?.reason}; layered=${mouth?.rig?.fit?.layered != null}\n" +
                        model.analysis.layers.filter { it.semantic.tag in CharacterAnalyzer.MOUTH_TAGS }.joinToString("\n") {
                            "${it.source.id.raw}: ${it.semantic}; group=${it.source.groupPath}; clipped=${it.source.clipped}; " +
                                "bounds=${it.bounds}; visible=${it.source.id.raw in initialized.renderVisibleLayers(model)}; " +
                                "meshes=${model.rig.puppet.drawables.filter { mesh -> model.rig.layerIdByDrawableId[mesh.id.raw] == it.source.id.raw }.map { mesh -> mesh.id to mesh.maskedBy }}"
                        } + "\n"
                } else ""
                Files.writeString(out.resolveSibling(path.fileName.toString() + ".inputs.txt"), fitting + detail)
                val result = "${source.widthPx}x${source.heightPx}; layers=${source.layers.size}; meshes=${model.rig.puppet.drawables.size}\n" +
                    "failures=$failures; runtime=${runtimeFailures.map { it.instance.type to it.error }}\n" +
                    runtimeFailures.joinToString("\n") { "${it.instance.type}\n${it.errorDetails}" } + "\n" + model.rig.modifierReport.toString()
                Files.writeString(out, result)
                println("${path.fileName}: ${result.lineSequence().take(2).joinToString("; ")}")
                if (System.getenv("PSD2LIVE_IMPORT_RENDER") == "1") {
                    renderPoses(initialized, model, out.resolveSibling(path.fileName.toString() + ".renders"))
                }
                if (System.getenv("PSD2LIVE_IMPORT_REQUIRE_ACTIVE") == "1") {
                    assertTrue(failures.isEmpty() && runtimeFailures.isEmpty(),
                        "Plugin initialization failed: $failures; runtime=${runtimeFailures.map { it.instance.type to it.error }}")
                }
            } catch (failure: Exception) {
                Files.writeString(out, failure.stackTraceToString(), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)
                throw failure
            }
        } } }
    }

    private fun renderPoses(document: WorkspaceDocument, model: RigPreviewModel, directory: Path) {
        Files.createDirectories(directory)
        val controls = model.rig.puppet.parameters.mapTo(hashSetOf()) { it.id.raw }
        val poses = listOf(
            "Rest" to emptyMap(),
            "Closed eyes" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f),
            "Wide eyes" to mapOf("ParamEyeLOpen" to 2f, "ParamEyeROpen" to 2f),
            "Brow angle" to mapOf("ParamBrowLAngle" to 1f, "ParamBrowRAngle" to 1f),
            "Open25" to mapOf("ParamMouthOpenY" to .25f),
            "Open50" to mapOf("ParamMouthOpenY" to .5f),
            "Open100" to mapOf("ParamMouthOpenY" to 1f),
            "Smile" to mapOf("ParamMouthForm" to 1f, "ParamMouthOpenY" to .5f),
            "Capture" to mapOf("ParamMouthOpenY" to .7f, "ParamMouthX" to 1f),
            "Jaw" to mapOf("ParamJawOpen" to 1f),
            "Vowel I" to mapOf("ParamI" to 1f, "ParamMouthOpenY" to .7f),
            "Vowel O" to mapOf("ParamO" to 1f, "ParamMouthOpenY" to .7f),
            "Tongue" to mapOf("ParamMouthOpenY" to 1f, "ParamTongueOut" to 1f),
            "Turn" to mapOf("ParamAngleX" to 25f, "ParamMouthOpenY" to .5f),
        ).filter { (_, pose) -> pose.keys.all { it in controls } }
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        for ((label, pose) in poses) {
            val evaluated = evaluator.evaluate(model.rig.puppet, pose.mapKeys { org.umamo.runtime.model.ParameterId(it.key) })
            assertTrue(evaluated.worldPositions.values.all { p -> p.all(Float::isFinite) }, label)
        }
        val visible = document.renderVisibleLayers(model).intersect(model.rig.layerIdByDrawableId.values.toSet())
        fun sheet(name: String, layers: List<ClassifiedLayer>, selected: List<Pair<String, Map<String, Float>>>) {
            if (layers.isEmpty()) return
            val rect = layers.map { it.bounds }.reduce(Bounds::union).expanded(.3f)
            io.github.psd2live.tools.sheet(selected.map { (label, pose) ->
                label to javax.imageio.ImageIO.read(WorkspaceViewRenderer.modelComposite(
                    model, "import", pose, visible, emptySet(), WorkspaceViewFrame.CanvasRect(rect),
                    WorkspaceViewBackground.CHECKERBOARD, WorkspaceViewOutputSpec(400)).png.inputStream())
            }, directory.resolve(name).toFile(), columns = 4)
        }
        val layers = model.analysis.layers.filter { it.opaquePixels > 0 && it.source.id.raw in visible }
        sheet("face.png", layers.filter { it.semantic.tag in setOf(SemanticTag.EYEWHITE, SemanticTag.EYEBROW, SemanticTag.MOUTH) }, poses)
        sheet("mouth.png", layers.filter { it.semantic.tag in CharacterAnalyzer.MOUTH_TAGS },
            poses.filter { (label, _) -> label !in setOf("Closed eyes", "Wide eyes", "Brow angle") })
        if (System.getenv("PSD2LIVE_IMPORT_COMPONENTS") == "1") {
            val mouth = layers.filter { it.semantic.tag in CharacterAnalyzer.MOUTH_TAGS }
            val rect = mouth.map { it.bounds }.reduceOrNull(Bounds::union)?.expanded(.3f)
            if (rect != null) io.github.psd2live.tools.sheet(mouth.flatMap { layer ->
                listOf(0f, .5f, 1f).map { open ->
                    "${layer.semantic.tag}/${layer.semantic.role} $open" to javax.imageio.ImageIO.read(
                        WorkspaceViewRenderer.modelComposite(model, "import", mapOf("ParamMouthOpenY" to open),
                            setOf(layer.source.id.raw), emptySet(), WorkspaceViewFrame.CanvasRect(rect),
                            WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png.inputStream())
                }
            }, directory.resolve("mouth-components.png").toFile(), columns = 3)
        }
        if ("ParamBustX" in controls && "ParamBustY" in controls) {
            sheet("body.png", layers, listOf("Rest" to emptyMap(),
                "Bust X" to mapOf("ParamBustX" to 1f), "Bust Y" to mapOf("ParamBustY" to 1f)))
        }
    }
}
