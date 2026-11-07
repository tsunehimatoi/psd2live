package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class CreatureDsTool {
    @Test fun compareWorkspaceCreature(): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 40)
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "mouth.spring" to 9, "body.arms" to 3, "body.bust" to 3, "outfit.skirt" to 2, "outfit.creature" to 1,
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

        val entry = stack.instances.single { it.type == "outfit.creature" }
        val index = stack.index(entry.id)

        for (type in listOf("mouth.spring", "body.bust", "body.arms", "outfit.skirt")) {
            assertTrue(stack.index(stack.instances.single { it.type == type }.id) < index, type)
        }
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        val prior = before.meshes.associateBy { it.id }
        val tail = after.meshes.single { it.id == "ArtMeshTail" }
        val changed = after.meshes.filter { it != prior[it.id] }
        assertEquals(listOf(tail), changed)
        val old = prior.getValue(tail.id)
        assertNotEquals(old.geometry, tail.geometry)
        assertNotEquals(old.parent, tail.parent)
        val carrier = after.deformers.single { it.id == tail.parent } as Deformer.Warp
        assertTrue(before.deformers.none { it.id == carrier.id })
        assertTrue(carrier.shapes.any { binding -> binding.shapes.filterNotNull().any { it.points.shared().any { point -> point != 0f } } })
        val physics = after.physics.groups.filter { it !in before.physics.groups }
        assertEquals(2, physics.size)
        assertTrue(physics.all { group ->
            group.inputs.any { it.weight > 0f } && group.outputs.all { output ->
                output.weight > 0f && carrier.shapes.any { it.parameter == output.parameter }
            }
        })

        fun select(version: Int): Pair<WorkspaceDocument, RigModifierInstance> {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val selected = entry.copy(version = version, plugin = artifact.reference)
            val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson())) to selected
        }

        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(3).first) }
        assertContains(rejected.message.orEmpty(), "Creature v3 requires mouth/fitted@4")
        val (candidate, selected) = select(2)
        val model = WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertEquals(historicalEvaluation.outputs[stage], actual.outputs[stage], stack.instances[stage].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, 2))
        val directory = Files.createTempDirectory("creature-ds-")
        val preferences = Preferences.userRoot().node("creature-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigCreatureModifier.definition(RigModifierSource.of(model))
            val providers = RigLegacyBindings.providers(selected).map { (capability, id) ->
                val provider = actual.entries.take(index).single { it.instance.id == id }
                assertEquals(ModifierStatus.ACTIVE, provider.status)
                val instance = provider.instance
                ModifierUpstreamCapability(capability, instance.type, instance.version, instance.targets,
                    instance.id, instance.config, instance.outputAliases, instance.plugin)
            }
            val context = definition.authorContext(selected).withUpstream(providers)
            assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = context, declaredParameters = definition.parameters(selected)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
