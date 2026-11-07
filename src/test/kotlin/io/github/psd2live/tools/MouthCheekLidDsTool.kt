package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class MouthCheekLidDsTool {
    @Test fun compareSharedFrameCheekLid(): Unit = verify(1)

    @Test fun compareSharedFrameCheekLidAfterSourceJaw(): Unit = verify(8)

    private fun verify(jawVersion: Int): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 64)
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val bindings = CliPresetBindings(analysis, config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to jawVersion, "mouth.cheek-lid" to 1,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5, "eye.squash" to 5,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = bindings)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val entry = stack.instances.single { it.type == "mouth.cheek-lid" }
        val index = stack.index(entry.id)
        val evaluation = assertNotNull(historical.modifierEvaluation)
        val expected = evaluation.outputs
        val jaw = stack.instances.single { it.type == "mouth.jaw" }
        val jawIndex = stack.index(jaw.id)
        assertEquals(jawVersion, jaw.version)
        assertTrue(jawIndex in 1 until index)
        assertEquals(ModifierStatus.ACTIVE, evaluation.entries[jawIndex].status)
        assertNotEquals(expected[jawIndex - 1], expected[jawIndex], "Jaw must change the real preceding stage")
        fun diff(before: RigIR, after: RigIR): String {
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(before) != it.get(after) }.map { it.name }
            val old = before.meshes.associateBy { it.id }
            val changes = after.meshes.mapNotNull { a -> old[a.id]?.takeIf { it != a }?.let { b ->
                "${a.id}(parent=${a.parent != b.parent},geom=${a.geometry != b.geometry},offsets=${a.offsets != b.offsets},shapes=${a.shapes != b.shapes})"
            } }
            val oldDef = before.deformers.associateBy { it.id }
            return "$fields meshes=$changes deformers=" + after.deformers.filter { it != oldDef[it.id] }.map { it.id }
        }
        val previous = expected[index - 1]
        val stage = expected[index]
        val beforeMeshes = previous.meshes.associateBy { it.id }
        assertEquals(setOf("ArtMeshEyelashL", "ArtMeshEyelashR", "ArtMeshEyewhiteL", "ArtMeshEyewhiteR"),
            stage.meshes.filter { it != beforeMeshes[it.id] }.mapTo(hashSetOf()) { it.id })
        assertEquals(previous.parameters, stage.parameters)
        assertEquals(4, stage.deformers.count { after -> previous.deformers.none { it.id == after.id } })
        fun select(version: Int): Pair<WorkspaceDocument, RigModifierInstance> {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val chosen = entry.copy(version = version, plugin = artifact.reference)
            val modified = stack.copy(instances = stack.instances.map { if (it.id == entry.id) chosen else it })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())) to chosen
        }
        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(7).first) }
        assertContains(rejected.message.orEmpty(), "requires fitted mouth v5")
        val (documentWithWorker, selected) = select(8)
        val model = WorkspacePreviewBuilder().build(documentWithWorker)
        val evaluated = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, evaluated.entries[index].status)
        for (stage in stack.instances.indices) {
            val actual = evaluated.outputs[stage]
            assertTrue(expected[stage] == actual, "${stack.instances[stage].type}: ${diff(expected[stage], actual)}")
        }
        val compiled = RigIrCompiler.compile(historical, tileArt = true)
        val candidate = RigIrCompiler.compile(model, tileArt = true)
        assertTrue(compiled == candidate, "compiled: ${diff(compiled, candidate)}")

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, 8))
        val root = Files.createTempDirectory("mouth-cheek-lid-ds-")
        val preferences = Preferences.userRoot().node("mouth-cheek-lid-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigMouthCheekLidModifier.sharedFrameDefinition(RigModifierSource.of(model))
            val providers = evaluated.entries.take(index).filter { it.status == ModifierStatus.ACTIVE &&
                it.instance.type in definition.requires + definition.optionalUpstream }.map { it.instance }
            val context = definition.authorContext(selected).withUpstream(providers.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            })
            for ((type, version) in versions.filterKeys { it != entry.type }) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(version, provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
            val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), expected[index - 1],
                selected, {}, store, context = context, declaredParameters = definition.parameters(selected))
            assertTrue(expected[index] == replay, "installed JVM: ${diff(expected[index], replay)}")
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
