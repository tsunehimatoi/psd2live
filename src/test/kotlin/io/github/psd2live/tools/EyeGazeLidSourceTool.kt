package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Compares the source gaze on eyelids under refitted shared head frames. */
class EyeGazeLidSourceTool {
    @Test fun compareSharedFrameGaze(): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 64)
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val wideVersion = setting("PSD2LIVE_GAZE_WIDE_VERSION", "1").toInt()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to 4, "eye.wide" to wideVersion, "eye.gaze-lid" to 1)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val gaze = stack.instances.single { it.type == "eye.gaze-lid" }
        val index = stack.index(gaze.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val oldMeshes = expected[index - 1].meshes.associateBy { it.id }
        for (id in listOf("ArtMeshEyelashL", "ArtMeshEyelashR")) {
            assertNotEquals(oldMeshes.getValue(id), expected[index].meshes.single { it.id == id })
        }
        assertNotEquals(expected[index - 1].parameterLinks, expected[index].parameterLinks)
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(gaze.type, version))
            val modified = stack.copy(instances = stack.instances.map {
                if (it.id == gaze.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        }
        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(3)) }
        assertContains(rejected.message.orEmpty(), "Lid gaze v3 cannot migrate an edited neutral lid frame: ArtMeshEyelashL")
        val preview = WorkspacePreviewBuilder().build(candidate(4))
        val result = assertNotNull(preview.modifierEvaluation)
        fun equal(reference: RigIR, observed: RigIR, stage: String) {
            if (reference == observed) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(reference) != it.get(observed) }.map { it.name }
            val meshes = reference.meshes.zip(observed.meshes).filter { (a, b) -> a != b }.map { (a, b) ->
                "${a.id}: geometry=${a.geometry != b.geometry}, offsets=${a.offsets != b.offsets}, shapes=${a.shapes != b.shapes}"
            }
            fail("$stage differs in $fields, meshes: $meshes")
        }
        for (stage in stack.instances.indices) equal(expected[stage], result.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(gaze.type, 4))
        val root = Files.createTempDirectory("eye-gaze-lid-source-")
        val preferences = Preferences.userRoot().node("eye-gaze-lid-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = result.entries[index].instance
            val definition = RigEyeGazeLidModifier.sharedFrameDefinition(RigModifierSource.of(preview))
            val context = definition.authorContext(instance).withUpstream(result.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            for (type in listOf("brow/fitted", "eye.smile", "eye.wide")) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(versions.getValue(type), provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
            equal(expected[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                result.outputs[index - 1], instance, {}, store, context = context, declaredParameters = definition.parameters(instance)), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
