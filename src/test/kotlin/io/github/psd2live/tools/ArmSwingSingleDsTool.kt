package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.project.*
import org.umamo.format.art.SourceArt
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class ArmSwingSingleDsTool {
    @Test fun compareWorkspaceLeftArm(): Unit = runBlocking { compareArm(Side.LEFT) }

    @Test fun compareWorkspaceRightArm(): Unit = runBlocking { compareArm(Side.RIGHT) }

    private suspend fun compareArm(side: Side) {
        requireTools()
        val config = PipelineConfig(meshSpacing = 40)
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val gloves = CharacterAnalyzer.analyze(artwork, config).layers.filter {
            it.semantic.tag == SemanticTag.HANDWEAR && it.opaquePixels > 0
        }
        assertEquals(setOf(Side.LEFT, Side.RIGHT), gloves.mapTo(hashSetOf()) { it.semantic.side })
        val removed = gloves.single { it.semantic.side != side }.source.id
        val source = object : SourceArt by artwork {
            override val layers = artwork.layers.filterNot { it.id == removed }
        }
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "mouth.spring" to 9, "body.arms" to 1,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5, "eye.squash" to 5,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))

        val historicalEvaluation = assertNotNull(historical.modifierEvaluation)
        for ((type, version) in versions) {
            val provider = historicalEvaluation.entries.single { it.instance.type == type }
            assertEquals(version, provider.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, provider.status, type)
        }
        val arm = historical.analysis.layers.single { it.semantic.tag == SemanticTag.HANDWEAR && it.opaquePixels > 0 }
        assertEquals(side, arm.semantic.side)
        val entry = stack.instances.single { it.type == "body.arms" }
        val index = stack.index(entry.id)
        for (type in versions.keys.filter { it.startsWith("mouth") }) {
            assertTrue(stack.index(stack.instances.single { it.type == type }.id) < index, type)
        }
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        assertTrue(entry.outputAliases.isEmpty())
        val meshId = RigModifierSource.of(historical).layersByMesh.entries.single { it.value == arm.source.id.raw }.key
        val mesh = after.meshes.single { it.id == meshId }
        assertTrue(mesh.visible && mesh.opacity > 0f)
        val carrier = after.deformers.single { it.id == mesh.parent } as Deformer.Warp
        val scaffold = stack.instances.single { it.type == "rig.scaffold" }
        val suffix = if (side == Side.LEFT) "L" else "R"
        assertEquals("shared/deformer:DeformArmHang_$suffix", scaffold.localOutputName(carrier.id))
        val swing = "ParamArm${suffix}Swing"
        assertTrue(carrier.shapes.single { it.parameter == swing }.shapes.filterNotNull().any { shape ->
            shape.points.shared().any { it != 0f }
        })
        val physics = after.physics.groups.filterNot { it in before.physics.groups }
        assertEquals(listOf("ParamArmLSwing", "ParamArmRSwing"), physics.flatMap { group -> group.outputs.map { it.parameter } })
        assertTrue(physics.all { group -> group.inputs.any { it.weight > 0f } && group.outputs.single().weight > 0f })
        assertEquals(before.meshes, after.meshes)
        val oldCarrier = before.deformers.single { it.id == carrier.id }
        assertEquals(oldCarrier, carrier.copy(shapes = carrier.shapes.filterNot { it.parameter == swing }))
        assertEquals(listOf(carrier), after.deformers.filter { item -> before.deformers.none { it == item } })

        fun select(version: Int): Pair<WorkspaceDocument, RigModifierInstance> {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val selected = entry.copy(version = version, plugin = artifact.reference)
            val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson())) to selected
        }

        for (version in listOf(3, 4)) {
            val (candidate, selected) = select(version)
            val model = WorkspacePreviewBuilder().build(candidate)
            val actual = assertNotNull(model.modifierEvaluation)
            assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
            for (stage in stack.instances.indices) {
                assertEquals(historicalEvaluation.outputs[stage], actual.outputs[stage], stack.instances[stage].type)
            }
            assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))

            val context = assertNotNull(actual.readAlgorithms[entry.id]).context
            assertEquals(meshId.takeIf { version == 4 }, context.values["singleArm"]?.jsonPrimitive?.content)
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val directory = Files.createTempDirectory("arm-swing-single-ds-")
            val preferences = Preferences.userRoot().node("arm-swing-single-ds/" + UUID.randomUUID())
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            try {
                BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                val worker = store.snapshot().single()
                val sourceView = RigModifierSource.of(model)
                val definition = if (version == 4) RigArmSwingModifier.singleDefinition(sourceView)
                    else RigArmSwingModifier.definition(sourceView, modern = true)
                assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                    selected, {}, store, context = context, declaredParameters = definition.parameters(selected)))
            } finally {
                ModifierPluginWorkers.stop(artifact.reference)
                preferences.removeNode()
                ModifierPluginStore.deleteTree(directory)
            }
        }
    }
}
