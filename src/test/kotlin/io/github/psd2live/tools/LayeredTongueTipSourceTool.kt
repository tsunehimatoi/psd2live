package io.github.psd2live.tools

import io.github.psd2live.algorithms.binding.MouthTongueV9Algorithm
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.format.model.ChildRef
import io.github.psd2live.format.model.RenderGroup
import io.github.psd2live.format.model.RenderMesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class LayeredTongueTipSourceTool {
    private fun RenderGroup.withoutTip(tipId: String): RenderGroup = copy(children = children.mapNotNull { node ->
        when (node) {
            is RenderGroup -> node.withoutTip(tipId)
            is RenderMesh -> node.takeUnless { it.id == tipId }
        }
    })

    private fun RigIR.withoutTip(tipId: String): RigIR = copy(meshes = meshes.filterNot { it.id == tipId },
        parts = parts.map { it.copy(children = it.children - ChildRef.MeshRef(tipId)) },
        rootChildren = rootChildren - ChildRef.MeshRef(tipId), renderRoot = renderRoot.withoutTip(tipId),
        textures = textures.copy(bindings = textures.bindings - tipId))

    @Test fun compareLayeredCheekLidWithTongueTip(): Unit = runBlocking {
        requireTools()
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredMouthDocument(artwork, config, withTongue = true)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 8,
            "mouth.outline" to 11, "mouth.vowel" to 8, "mouth.capture" to 7, "mouth.tongue" to 7,
            "mouth.jaw" to 12, "mouth.cheek-lid" to 12)
        val (planned, _) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val lid = stack.instances.single { it.type == "mouth.cheek-lid" }
        val stage = stack.index(lid.id)
        val changedVersions = mapOf("mouth.tongue" to 9, "mouth.jaw" to 14, "mouth.cheek-lid" to 14)
        val packages = changedVersions.mapValues { (type, version) -> assertNotNull(BundledAlgorithmArtifacts.find(type, version)) }
        val legacy = stack.copy(instances = stack.instances.take(stage + 1))
        val upgraded = legacy.copy(instances = legacy.instances.map { instance ->
            packages[instance.type]?.let { instance.copy(version = changedVersions.getValue(instance.type), plugin = it.reference) } ?: instance
        })
        suspend fun preview(withStack: RigModifierStack) = WorkspacePreviewBuilder().build(
            planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = withStack.toJson())))
        val reference = preview(legacy)
        val candidate = preview(upgraded)
        val old = assertNotNull(reference.modifierEvaluation)
        val actual = assertNotNull(candidate.modifierEvaluation)
        val locked = versions + changedVersions
        val entries = actual.entries.filter { it.instance.type in locked }
        assertEquals(locked, entries.associate { it.instance.type to it.instance.version })
        for (entry in entries) assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        val before = actual.outputs[stage - 1]
        val after = actual.outputs[stage]
        val owner = before.meshes.single { it.id == "ArtMeshTongue" }
        val tongue = upgraded.instances.single { it.type == "mouth.tongue" }
        val tipId = tongue.outputId(MouthTongueV9Algorithm.tipOutput(owner.id))
        val tip = before.meshes.single { it.id == tipId }
        assertEquals(tip, after.meshes.single { it.id == tipId })
        assertEquals(owner, after.meshes.single { it.id == owner.id })
        assertTrue(owner.maskedBy.isNotEmpty() && tip.maskedBy.isEmpty())
        assertEquals(owner.id, tip.textureSource)
        assertTrue(after.parts.any { ChildRef.MeshRef(owner.id) in it.children && ChildRef.MeshRef(tipId) in it.children })
        val oldMeshes = before.meshes.associateBy { it.id }
        val oldFrames = before.deformers.associateBy { it.id }
        val frames = after.deformers.associateBy { it.id }
        val moved = after.meshes.filter { it != oldMeshes[it.id] }
        assertEquals(setOf("ArtMeshEyelashL", "ArtMeshEyelashR", "ArtMeshEyewhiteL", "ArtMeshEyewhiteR"), moved.map { it.id }.toSet())
        val form = "ParamMouthForm"
        fun <F> varies(grid: io.github.psd2live.format.model.KeyGrid<F>?): Boolean {
            val axis = grid?.axes?.indexOfFirst { it.parameter == form } ?: return false
            if (axis < 0) return false
            return grid.cells.groupBy { cell -> cell.coordinate.toArray().filterIndexed { index, _ -> index != axis } }
                .values.any { cells -> cells.map { it.form }.distinct().size > 1 }
        }
        for (mesh in moved) {
            val previous = oldMeshes.getValue(mesh.id)
            val local = (mesh.offsets != previous.offsets && varies(mesh.offsets)) ||
                (mesh.shapes - previous.shapes.toSet()).any { binding -> binding.parameter == form &&
                    binding.shapes.any { it?.deltas?.toArray()?.any { delta -> delta != 0f } == true } }
            val inherited = generateSequence(mesh.parent) { frames[it]?.parent }.any { id ->
                val frame = frames[id] as? io.github.psd2live.format.model.Deformer.Warp
                frame != oldFrames[id] && varies(frame?.lattice)
            }
            assertTrue(local || inherited, "${mesh.id} must move with $form")
        }
        for (index in old.outputs.indices) {
            assertTrue(old.outputs[index] == actual.outputs[index].withoutTip(tipId), "${legacy.instances[index].type} differs")
        }
        val compiled = RigIrCompiler.compile(candidate, tileArt = true)
        assertEquals(compiled.textures.bindings[owner.id], compiled.textures.bindings[tipId])
        assertTrue(RigIrCompiler.compile(reference, tileArt = true) == compiled.withoutTip(tipId), "Complete cheek and lid IR differs")

        val root = Files.createTempDirectory("layered-cheek-lid-tip-")
        val preferences = Preferences.userRoot().node("layered-cheek-lid-tip/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(packages.getValue(lid.type)))
            val worker = store.snapshot().single()
            val instance = upgraded.instances[stage]
            val definition = RigMouthCheekLidModifier.layeredTongueTipDefinition(RigModifierSource.of(candidate))
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            for (type in listOf("mouth.tongue", "mouth.jaw")) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(packages.getValue(type).reference, provider.plugin)
                assertEquals(changedVersions.getValue(type), provider.version)
            }
            val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before, instance, {}, store,
                context = context, declaredParameters = definition.parameters(instance))
            assertTrue(after == replay, "Installed cheek and lid v14 differs")
            println("Cheek and lid moves ${moved.map { it.id }}; retains ${tip.id} with ${tip.textureSource}")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareLayeredJawWithTongueTip(): Unit = runBlocking {
        requireTools()
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val artwork = pipeline.readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredMouthDocument(artwork, config, withTongue = true)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 8,
            "mouth.outline" to 11, "mouth.vowel" to 8, "mouth.capture" to 7, "mouth.tongue" to 7, "mouth.jaw" to 12)
        val (planned, _) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val tongue = stack.instances.single { it.type == "mouth.tongue" }
        val jaw = stack.instances.single { it.type == "mouth.jaw" }
        val tongueStage = stack.index(tongue.id)
        val jawStage = stack.index(jaw.id)
        val tongueArtifact = assertNotNull(BundledAlgorithmArtifacts.find(tongue.type, 9))
        val jawArtifact = assertNotNull(BundledAlgorithmArtifacts.find(jaw.type, 14))
        val selected = jaw.copy(version = 14, plugin = jawArtifact.reference)
        val legacy = stack.copy(instances = stack.instances.take(jawStage + 1))
        val upgraded = legacy.copy(instances = legacy.instances.map {
            when (it.id) {
                tongue.id -> it.copy(version = 9, plugin = tongueArtifact.reference)
                jaw.id -> selected
                else -> it
            }
        })
        suspend fun preview(withStack: RigModifierStack) = WorkspacePreviewBuilder().build(
            planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = withStack.toJson())))
        val reference = preview(legacy)
        val candidate = preview(upgraded)
        val old = assertNotNull(reference.modifierEvaluation)
        val actual = assertNotNull(candidate.modifierEvaluation)
        val locked = versions + (tongue.type to 9) + (jaw.type to 14)
        val entries = actual.entries.filter { it.instance.type in locked }
        assertEquals(locked, entries.associate { it.instance.type to it.instance.version })
        for (entry in entries) assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        val before = actual.outputs[jawStage - 1]
        val after = actual.outputs[jawStage]
        val owner = before.meshes.single { it.id == "ArtMeshTongue" }
        val tipId = upgraded.instances[tongueStage].outputId(MouthTongueV9Algorithm.tipOutput(owner.id))
        val tip = before.meshes.single { it.id == tipId }
        assertEquals(tip, after.meshes.single { it.id == tipId })
        assertEquals(owner, after.meshes.single { it.id == owner.id })
        assertTrue(owner.maskedBy.isNotEmpty() && tip.maskedBy.isEmpty())
        assertEquals(owner.id, tip.textureSource)
        val part = after.parts.single { ChildRef.MeshRef(tipId) in it.children }
        assertTrue(ChildRef.MeshRef(owner.id) in part.children)

        for (stage in old.outputs.indices) {
            assertTrue(old.outputs[stage] == actual.outputs[stage].withoutTip(tipId), "${legacy.instances[stage].type} differs")
        }
        val oldFull = RigIrCompiler.compile(reference, tileArt = true)
        val newFull = RigIrCompiler.compile(candidate, tileArt = true)
        assertEquals(newFull.textures.bindings[owner.id], newFull.textures.bindings[tipId])
        val stripped = newFull.withoutTip(tipId)
        val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { it.isAccessible = true; it.get(oldFull) != it.get(stripped) }.map { it.name }
        assertTrue(oldFull == stripped, "Complete jaw IR differs in $fields")
        val oldFace = before.meshes.single { it.id == "ArtMeshFace" }
        val face = after.meshes.single { it.id == oldFace.id }
        val open = "ParamMouthOpenY"
        val opening = face.offsets
        val moved = (face.shapes - oldFace.shapes.toSet()).any { binding -> binding.parameter == open &&
            binding.shapes.any { it?.deltas?.toArray()?.any { delta -> delta != 0f } == true } } ||
            (opening != null && opening != oldFace.offsets && opening.axes.any { it.parameter == open } &&
                opening.cells.map { it.form }.distinct().size > 1)
        assertTrue(moved, "Jaw must deform the face with mouth opening")

        fun nativeTip(jawEnabled: Boolean) = RigIrCompiler.compile(pipeline.buildPreview(document.source, config.copy(
            rigEdits = RigEditOverlay.Empty, layerOverrides = document.layerOverrides,
            recipe = config.recipe.copy(modules = mapOf("mouth.jaw" to ModuleChoice(on = jawEnabled),
                "mouth.cheek-lid" to ModuleChoice(on = false), "mouth.interior-depth" to ModuleChoice(on = false),
                "mouth.spring" to ModuleChoice(on = false))))), tileArt = true).meshes.single { it.id == "${owner.id}_Out" }
        val nativeBefore = nativeTip(false)
        val nativeAfter = nativeTip(true)
        assertEquals(nativeBefore, nativeAfter, "The native jaw stage must preserve the protruding tip")
        println("Native jaw retains ${nativeAfter.id}; jaw14 retains ${tip.id}")

        val root = Files.createTempDirectory("layered-jaw-tip-")
        val preferences = Preferences.userRoot().node("layered-jaw-tip/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(jawArtifact))
            val worker = store.snapshot().single()
            val definition = RigMouthJawModifier.layeredTongueTipDefinition(RigModifierSource.of(candidate))
            val context = definition.authorContext(selected).withUpstream(actual.entries.take(jawStage)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val provider = context.upstream.single { it.type == tongue.type }
            assertEquals(tongueArtifact.reference, provider.plugin)
            assertEquals(9, provider.version)
            val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before, selected, {}, store,
                context = context, declaredParameters = definition.parameters(selected))
            assertTrue(after == replay, "Installed jaw14 must preserve the identical tongue tip")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareLayeredTongueTip(): Unit = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val artwork = PSD2LivePipeline().readPsd(sample.path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredMouthDocument(artwork, config, withTongue = true)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 8,
            "mouth.outline" to 11, "mouth.vowel" to 8, "mouth.capture" to 7, "mouth.tongue" to 7)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val tongue = stack.instances.single { it.type == "mouth.tongue" }
        val stage = stack.index(tongue.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(tongue.type, MouthTongueV9Algorithm.VERSION))
        val selected = tongue.copy(version = MouthTongueV9Algorithm.VERSION, plugin = artifact.reference)
        val upgraded = stack.copy(instances = stack.instances.take(stage) + selected)
        val candidateDocument = planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = upgraded.toJson()))
        val preview = WorkspacePreviewBuilder().build(candidateDocument)
        val evaluated = assertNotNull(preview.modifierEvaluation)
        val previous = assertNotNull(baseline.modifierEvaluation)
        assertEquals(versions, previous.entries.filter { it.instance.type in versions }.associate { it.instance.type to it.instance.version })
        for (entry in evaluated.entries.filter { it.instance.type in versions }) assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        assertEquals(previous.outputs.take(stage), evaluated.outputs.take(stage))
        val before = previous.outputs[stage]
        val after = evaluated.outputs[stage]
        val owner = after.meshes.single { it.id == "ArtMeshTongue" }
        val tip = after.meshes.single { it.id == selected.outputId(MouthTongueV9Algorithm.tipOutput(owner.id)) }
        assertEquals(before.meshes, after.meshes - tip)
        assertEquals(before.parameters, after.parameters)
        assertTrue(owner.maskedBy.isNotEmpty())
        assertTrue(tip.maskedBy.isEmpty())
        assertEquals(0f, tip.opacity)
        assertEquals(owner.id, tip.textureSource)
        assertEquals(owner.tile, tip.tile)
        assertEquals(owner.page, tip.page)
        assertTrue(after.deformers.any { it.id == tip.parent })
        val part = after.parts.single { ChildRef.MeshRef(owner.id) in it.children }
        assertTrue(ChildRef.MeshRef(tip.id) in part.children)
        val driven = tip.shapes.single { it.parameter == "ParamTongueOut" }
        assertTrue(driven.shapes.any { form ->
            form != null && form.opacity > 0f && form.deltas.toArray().any { it != 0f }
        })
        val legacy = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildPreview(document.source,
            config.copy(rigEdits = RigEditOverlay.Empty, layerOverrides = document.layerOverrides))
        val nativeTip = RigIrCompiler.compile(legacy, tileArt = true).meshes.single { it.id == "${owner.id}_Out" }
        assertEquals(nativeTip.geometry?.indices, tip.geometry?.indices)
        assertEquals(nativeTip.offsets, tip.offsets)
        val nativeDriver = nativeTip.shapes.single { it.parameter == driven.parameter }
        assertEquals(nativeDriver.keys, driven.keys)
        assertEquals(nativeDriver.limits, driven.limits)
        assertEquals(nativeDriver.shapes.map { it?.let { form -> form.deltas to form.opacity } },
            driven.shapes.map { it?.let { form -> form.deltas to form.opacity } })
        assertEquals(preview.rig.layerIdByDrawableId[owner.id], preview.rig.layerIdByDrawableId[tip.id])
        val full = RigIrCompiler.compile(preview, tileArt = true)
        assertEquals(owner.id, full.meshes.single { it.id == tip.id }.textureSource)

        val root = Files.createTempDirectory("psd2live-project-")
        val preferences = Preferences.userRoot().node("layered-tongue-tip/" + UUID.randomUUID())
        val pluginStore = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(pluginStore, listOf(artifact))
            val worker = pluginStore.snapshot().single()
            val definition = RigMouthTongueModifier.sharedFrameDefinition(RigModifierSource.of(preview), layered = true, withTip = true)
            val context = definition.authorContext(selected).withUpstream(evaluated.entries.take(stage)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), evaluated.outputs[stage - 1],
                selected, {}, pluginStore, context = context, declaredParameters = definition.parameters(selected))
            assertTrue(after == replay, "Installed worker must return the identical tip")

            val store = WorkspaceStore(root.resolve("live"))
            val revision = WorkspaceRevisions.of(candidateDocument)
            val state = WorkspaceHistoryTree(candidateDocument, revision, revision).state()
            val project = "layered-tongue-tip"
            store.persistHistory(project, state)
            val file = root.resolve("tongue-tip.psd2live")
            val repository = ProjectRepository(writeHeadCache = false)
            repository.save(ProjectSaveCapture(project, state, JsonObject(emptyMap()), sample.path, store), file)
            repository.open(file).use { reopened ->
                val snapshot = reopened.history.checkout(state.headNodeId).snapshot
                val rebuilt = RigIrCompiler.compile(WorkspacePreviewBuilder().build(snapshot), tileArt = true)
                assertEquals(tip.id, rebuilt.meshes.single { it.textureSource == owner.id }.id)
                assertTrue(full == rebuilt, "Reopening must preserve the complete tongue tip IR")
            }
        } finally {
            pluginStore.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ProjectArchive.deleteTemporaryDirectory(root)
        }
    }
}
