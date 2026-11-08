package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class MouthCheekLidTongueSourceTool {
    @Test fun compareDsWorkspaceCheekLidWithTongue(): Unit = runBlocking {
        requireTools()
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = dsMouthInteriorDocument(artwork, config, tongue = true)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 6,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 8,
            "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to 9, "mouth.cheek-lid" to 1,
            "mouth.interior-depth" to 1, "mouth.spring" to 1, "mouth.tongue" to 6)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val cheekModule = stack.instances.single { it.type == "mouth.cheek-lid" }
        val index = stack.index(cheekModule.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val before = expected[index - 1].meshes.associateBy { it.id }
        assertEquals(setOf("ArtMeshEyelashL", "ArtMeshEyelashR", "ArtMeshEyewhiteL", "ArtMeshEyewhiteR"),
            expected[index].meshes.filter { it != before[it.id] }.mapTo(hashSetOf()) { it.id })
        val tongueIndex = stack.index(stack.instances.single { it.type == "mouth.tongue" }.id)
        val jawIndex = stack.index(stack.instances.single { it.type == "mouth.jaw" }.id)
        assertTrue(tongueIndex in 1 until jawIndex && jawIndex < index)
        assertNotEquals(expected[tongueIndex - 1], expected[tongueIndex], "Tongue must contribute motion")
        assertNotEquals(expected[jawIndex - 1], expected[jawIndex], "Jaw must contribute motion")
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(cheekModule.type, version))
            val modified = stack.copy(instances = stack.instances.map {
                if (it.id == cheekModule.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        }
        val old = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(8)) }
        assertContains(old.message.orEmpty(), "requires its captured mouth.jaw provider")
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            WorkspacePreviewBuilder().build(candidate(9))
        } finally {
            RigBuildProfile.recording = recording
        }
        assertTrue("mouth.cheek-lid" !in legacyTemplateConsumers())
        val actual = assertNotNull(preview.modifierEvaluation)
        assertEquals(9, actual.entries[index].instance.version)
        for (entry in actual.entries.filter { it.instance.type in versions }) {
            assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        }
        fun equal(a: RigIR, b: RigIR, stage: String) {
            if (a == b) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
            val byId = a.meshes.associateBy { it.id }
            val meshes = b.meshes.filter { it != byId[it.id] }.map { it.id }
            fail("$stage differs in $fields, meshes=$meshes")
        }
        for (stage in stack.instances.indices) equal(expected[stage], actual.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(cheekModule.type, 9))
        val root = Files.createTempDirectory("mouth-cheek-lid-tongue-source-")
        val preferences = Preferences.userRoot().node("mouth-cheek-lid-tongue-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = actual.entries[index].instance
            val definition = RigMouthCheekLidModifier.sharedFrameDefinition(RigModifierSource.of(preview), withTongue = true)
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val types = listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.tongue", "mouth.jaw")
            for (type in types) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(versions.getValue(type), provider.version)
                assertEquals(assertNotNull(stack.instances.single { it.type == type }.plugin), provider.plugin)
            }
            equal(expected[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[index - 1], instance, {}, store, context = context, declaredParameters = definition.parameters(instance)), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
