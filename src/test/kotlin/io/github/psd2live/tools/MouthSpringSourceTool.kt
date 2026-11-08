package io.github.psd2live.tools

import io.github.psd2live.application.*
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

class MouthSpringSourceTool {
    @Test fun compareDsWorkspaceSpring(): Unit = runBlocking { compareSpring(sharedTongue = false) }

    @Test fun compareDsWorkspaceSharedTongueSpring(): Unit = runBlocking { compareSpring(sharedTongue = true) }

    private suspend fun compareSpring(sharedTongue: Boolean) {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = if (sharedTongue) dsMouthInteriorDocument(source, config, tongue = true)
        else WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        assertTrue(analysis.geometry.layers.none { it.semantic.tag == SemanticTag.MOUTH_CLOSE })
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 6,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 8,
            "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to (if (sharedTongue) 9 else 8),
            "mouth.cheek-lid" to (if (sharedTongue) 9 else 8), "mouth.spring" to 1) +
            if (sharedTongue) mapOf("mouth.tongue" to 6, "mouth.interior-depth" to 7) else emptyMap()
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val spring = stack.instances.single { it.type == "mouth.spring" }
        val index = stack.index(spring.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        if (sharedTongue) {
            var previous = 0
            for (type in listOf("mouth.tongue", "mouth.jaw", "mouth.cheek-lid", "mouth.interior-depth")) {
                val stage = stack.index(stack.instances.single { it.type == type }.id)
                assertTrue(stage in (previous + 1) until index, type)
                assertNotEquals(expected[stage - 1], expected[stage], "$type must contribute motion")
                previous = stage
            }
        }
        val old = expected[index - 1].meshes.associateBy { it.id }
        assertEquals(setOf("ArtMeshNose", "ArtMeshFace"),
            expected[index].meshes.filter { it != old[it.id] }.mapTo(hashSetOf()) { it.id })
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(spring.type, version))
            val next = stack.copy(instances = stack.instances.map {
                if (it.id == spring.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson()))
        }
        assertEquals(1, (expected[index].physics.groups.toSet() - expected[index - 1].physics.groups.toSet()).size)
        if (sharedTongue) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(9)) }
            assertContains(rejected.message.orEmpty(), "requires the shared open-mouth source chain")
        } else {
            assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(8)) }
        }
        val version = if (sharedTongue) 10 else 9
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            WorkspacePreviewBuilder().build(candidate(version))
        } finally {
            RigBuildProfile.recording = recording
        }
        val sourceConsumers = if (sharedTongue) {
            setOf("mouth.tongue", "mouth.jaw", "mouth.cheek-lid", "mouth.interior-depth", "mouth.spring")
        } else setOf("mouth.spring")
        assertEquals(emptySet(), legacyTemplateConsumers().intersect(sourceConsumers))
        val actual = assertNotNull(preview.modifierEvaluation)
        assertEquals(version, actual.entries[index].instance.version)
        for (entry in actual.entries.filter { it.instance.type in versions }) {
            assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        }
        fun equal(a: RigIR, b: RigIR, stage: String) {
            if (a == b) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
            val known = a.meshes.associateBy { it.id }
            val meshes = b.meshes.filter { it != known[it.id] }.map { it.id }
            fail("$stage differs in $fields, meshes=$meshes")
        }
        for (stage in stack.instances.indices) equal(expected[stage], actual.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")
        if (sharedTongue) {
            val (recommendedDocument, recommended) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
                WorkspaceInitialPreset(clothing = false))
            val selected = mapOf("mouth.tongue" to 6, "mouth.jaw" to 9, "mouth.cheek-lid" to 9, "mouth.interior-depth" to 7, "mouth.spring" to 10)
            val entries = assertNotNull(recommended.modifierEvaluation).entries.filter { it.instance.type in selected }
            assertEquals(selected, entries.associate { it.instance.type to it.instance.version })
            assertTrue(entries.all { it.status == ModifierStatus.ACTIVE })
            val recommendedStack = RigModifierStack.fromJson(assertNotNull(recommendedDocument.rigEdits.modifierStack))
            val legacyInterior = recommendedStack.copy(instances = recommendedStack.instances.map { entry ->
                if (entry.type in selected) entry.copy(version = 1, plugin = BuiltinModifierPlugins.reference(entry.type, 1)) else entry
            })
            val baseline = WorkspacePreviewBuilder().build(recommendedDocument.copy(
                rigEdits = recommendedDocument.rigEdits.copy(modifierStack = legacyInterior.toJson())))
            equal(RigIrCompiler.compile(baseline, tileArt = true), RigIrCompiler.compile(recommended, tileArt = true), "recommended IR")
        }

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(spring.type, version))
        val root = Files.createTempDirectory("mouth-spring-source-")
        val preferences = Preferences.userRoot().node("mouth-spring-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = actual.entries[index].instance
            val sourceInputs = RigModifierSource.of(preview)
            val definition = RigMouthSpringModifier.sharedFrameDefinition(sourceInputs, withSharedTongue = sharedTongue)
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val types = listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid") +
                if (sharedTongue) listOf("mouth.tongue", "mouth.interior-depth") else emptyList()
            for (type in types) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(versions.getValue(type), provider.version)
                val referencePlugin = stack.instances.single { it.type == type }.plugin
                if (sharedTongue) assertNotNull(referencePlugin, type)
                assertEquals(referencePlugin, provider.plugin)
            }
            equal(expected[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[index - 1], instance, {}, store, context = context, declaredParameters = definition.parameters(instance),
                resolveRasters = sourceInputs::resolveRasters), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
