package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.Channel
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.math.sqrt
import kotlin.test.*

class LayeredMouthOutlineSourceTool {
    private enum class Upgrade(val type: String, val version: Int) {
        OUTLINE("mouth.outline", 9), FITTED("mouth/fitted", 8), VOWEL("mouth.vowel", 8),
        CAPTURE("mouth.capture", 7), JAW("mouth.jaw", 10), CHEEK_LID("mouth.cheek-lid", 10), SPRING("mouth.spring", 11),
        TONGUE("mouth.tongue", 7), TONGUE_JAW("mouth.jaw", 12), TONGUE_CHEEK_LID("mouth.cheek-lid", 12), TONGUE_DEPTH("mouth.interior-depth", 8), TONGUE_SPRING("mouth.spring", 13),
        TEETH_DEPTH("mouth.interior-depth", 9), TEETH_SPRING("mouth.spring", 14), OPENING_DEPTH("mouth.interior-depth", 9),
    }

    @Test fun compareLayeredOutlineWithLegacyFittedMouth(): Unit = runBlocking { compareLayered(Upgrade.OUTLINE) }

    @Test fun compareLayeredFittedMouthWithLegacyOutline(): Unit = runBlocking { compareLayered(Upgrade.FITTED) }

    @Test fun compareLayeredVowelsWithSharedFittedMouth(): Unit = runBlocking { compareLayered(Upgrade.VOWEL) }

    @Test fun compareLayeredCaptureWithSharedVowels(): Unit = runBlocking { compareLayered(Upgrade.CAPTURE) }

    @Test fun compareLayeredJawWithSharedCapture(): Unit = runBlocking { compareLayered(Upgrade.JAW) }

    private enum class Interior { NONE, TONGUE, TEETH, OPENING }

    @Test fun recommendedLayeredMouthUsesSharedSources(): Unit = runBlocking { compareRecommendedLayered() }

    @Test fun recommendedLayeredTongueUsesSharedSources(): Unit = runBlocking { compareRecommendedLayered(Interior.TONGUE) }

    @Test fun recommendedLayeredTeethUseSharedSources(): Unit = runBlocking { compareRecommendedLayered(Interior.TEETH) }

    @Test fun recommendedLayeredOpeningUsesSharedSources(): Unit = runBlocking { compareRecommendedLayered(Interior.OPENING) }

    @Test fun recommendedLayeredRibbonsWithoutSkirtUseSharedSources(): Unit = runBlocking {
        compareRecommendedLayered(includeSkirt = false)
    }

    @Test fun appendLayeredCaptureUsesStandalonePlugin(): Unit = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredMouthDocument(source, config)
        val planned = WorkspacePresetInitialization.create(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val capture = stack.instances.single { it.type == "mouth.capture" }
        val index = stack.index(capture.id)
        val prefix = stack.copy(instances = stack.instances.take(index))
        val before = planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = prefix.toJson()))
        val preview = WorkspacePreviewBuilder().build(before)
        val request = buildJsonObject {
            put("type", capture.type)
            putJsonArray("targets") { capture.targets.forEach { target -> add(buildJsonObject {
                put("kind", target.kind)
                put("id", target.id)
            }) } }
        }
        val (proposed, plan) = WorkspaceModifierPlan.plan(preview, prefix, request)
        val addition = proposed.instances.last()
        assertEquals(7, addition.version)
        assertEquals(BuiltinModifierPlugins.reference(capture.type, 7), addition.plugin)
        val additionRequest = plan.getValue("entries").jsonArray.single().jsonObject
        val appended = WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_add",
            JsonObject(additionRequest - "version" - "plugin")), before, preview)
        val direct = RigModifierStack.fromJson(assertNotNull(appended.rigEdits.modifierStack))
        assertEquals(addition, direct.instances.last())
        val evaluated = assertNotNull(WorkspacePreviewBuilder().build(appended).modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, evaluated.entries.last().status)
        val legacyRequest = JsonObject(additionRequest - "plugin" + ("version" to JsonPrimitive(1)))
        val legacy = WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_add", legacyRequest), before, preview)
        val historical = WorkspacePreviewBuilder().build(legacy)
        val expected = assertNotNull(historical.modifierEvaluation).outputs.last()
        assertTrue(expected == evaluated.outputs.last(), "Independent capture differs from v1 with the same instance ID")
    }

    private suspend fun compareRecommendedLayered(interior: Interior = Interior.NONE, includeSkirt: Boolean = true) {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val skirtOverrides = if (includeSkirt) emptyMap() else {
            val skirts = RigGenerationSource.analyze(source, config).layers.filter { it.opaquePixels > 0 && it.semantic.role == PartRole.SKIRT }
            assertTrue(skirts.isNotEmpty())
            plainSkirtOverrides(skirts)
        }
        val document = when (interior) {
            Interior.NONE -> ribbonDsDocument(source, config, layered = true, extraOverrides = skirtOverrides)
            Interior.TONGUE -> layeredMouthDocument(source, config, withTongue = true)
            Interior.TEETH -> layeredMouthDocument(source, config, withTeeth = true)
            Interior.OPENING -> layeredMouthDocument(source, config, openingOnly = true)
        }
        val extraVersions = when (interior) {
            Interior.NONE -> mapOf("body.bust" to 3, "body.arms" to 3, "outfit.ribbons" to 6, "outfit.creature" to 2)
            Interior.TONGUE -> mapOf("mouth.tongue" to 7, "mouth.jaw" to 12, "mouth.cheek-lid" to 12,
                "mouth.interior-depth" to 8, "mouth.spring" to 13)
            Interior.TEETH, Interior.OPENING -> mapOf("mouth.interior-depth" to 9, "mouth.spring" to 14)
        }
        val versions = layeredRibbonMouthVersions + extraVersions
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val (planned, recommended) = try {
            RigBuildProfile.recording = true
            WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct, WorkspaceInitialPreset(clothing = false))
        } finally {
            RigBuildProfile.recording = recording
        }
        val templateConsumers = legacyTemplateConsumers().intersect(versions.keys)
        val actual = assertNotNull(recommended.modifierEvaluation)
        val selected = actual.entries.filter { it.instance.type in versions }
        assertEquals(versions, selected.associate { it.instance.type to it.instance.version })
        assertTrue(templateConsumers.isEmpty(), "Unexpected legacy template consumers: $templateConsumers")
        assertTrue(selected.all { it.status == ModifierStatus.ACTIVE })
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        if (!includeSkirt) assertTrue(stack.instances.none { it.type == "outfit.skirt" })
        val legacy = stack.copy(instances = stack.instances.map { entry ->
            if (entry.type in versions) entry.copy(version = 1, plugin = BuiltinModifierPlugins.reference(entry.type, 1))
            else entry
        })
        val historical = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = legacy.toJson())))
        val reference = assertNotNull(historical.modifierEvaluation)
        for (index in stack.instances.indices) {
            assertEquals(reference.outputs[index], actual.outputs[index], stack.instances[index].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(recommended, tileArt = true))

        if (interior != Interior.NONE || !includeSkirt) return
        val outline = selected.single { it.instance.type == "mouth.outline" }.instance
        val index = stack.index(outline.id)
        val definition = RigFittedMouthOutlineDefinition.layeredSourceDefinition(RigModifierSource.of(recommended))
        val context = assertNotNull(actual.authorContexts[outline.id]).withUpstream(actual.entries.take(index)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        assertEquals(8, context.upstream.single { it.type == "mouth/fitted" }.version)
        assertEquals(setOf("before", "after"), context.resources.keys)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(outline.type, outline.version))
        val root = Files.createTempDirectory("layered-mouth-shared-outline-")
        val preferences = Preferences.userRoot().node("layered-mouth-outline/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            assertEquals(reference.outputs[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[index - 1], outline, {}, store, context = context,
                declaredParameters = definition.parameters(outline)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareLayeredCheekLidsWithSharedJaw(): Unit = runBlocking { compareLayered(Upgrade.CHEEK_LID) }

    @Test fun compareLayeredSpringWithSharedCheekLids(): Unit = runBlocking { compareLayered(Upgrade.SPRING) }

    @Test fun compareLayeredTongueWithSharedCapture(): Unit = runBlocking { compareLayered(Upgrade.TONGUE) }

    @Test fun compareLayeredJawWithSharedTongue(): Unit = runBlocking { compareLayered(Upgrade.TONGUE_JAW) }

    @Test fun compareLayeredCheekLidsWithSharedTongueJaw(): Unit = runBlocking { compareLayered(Upgrade.TONGUE_CHEEK_LID) }

    @Test fun compareLayeredDepthWithSharedTongueCheekLids(): Unit = runBlocking { compareLayered(Upgrade.TONGUE_DEPTH) }

    @Test fun compareLayeredSpringWithSharedTongueDepth(): Unit = runBlocking { compareLayered(Upgrade.TONGUE_SPRING) }

    @Test fun compareLayeredTeethDepthWithoutTongue(): Unit = runBlocking { compareLayered(Upgrade.TEETH_DEPTH) }

    @Test fun compareLayeredSpringWithSharedTeethDepth(): Unit = runBlocking { compareLayered(Upgrade.TEETH_SPRING) }

    @Test fun compareLayeredDepthWithOnlyOpening(): Unit = runBlocking { compareLayered(Upgrade.OPENING_DEPTH) }

    private suspend fun compareLayered(upgrade: Upgrade) {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val openingOnly = upgrade == Upgrade.OPENING_DEPTH
        val withTeeth = upgrade in setOf(Upgrade.TEETH_DEPTH, Upgrade.TEETH_SPRING)
        val withTongue = upgrade >= Upgrade.TONGUE && !withTeeth && !openingOnly
        val document = layeredMouthDocument(source, config, withTongue, withTeeth, openingOnly)
        val fittedVersion = if (upgrade in setOf(Upgrade.OUTLINE, Upgrade.FITTED)) 1 else 8
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to fittedVersion,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 1, "mouth.vowel" to (if (upgrade >= Upgrade.CAPTURE) 8 else 1),
            "mouth.capture" to (if (upgrade >= Upgrade.JAW) 7 else 1),
            "mouth.jaw" to when (upgrade) {
                Upgrade.CHEEK_LID, Upgrade.SPRING, Upgrade.TEETH_DEPTH, Upgrade.TEETH_SPRING, Upgrade.OPENING_DEPTH -> 10
                Upgrade.TONGUE_CHEEK_LID, Upgrade.TONGUE_DEPTH, Upgrade.TONGUE_SPRING -> 12
                else -> 1
            },
            "mouth.cheek-lid" to when (upgrade) {
                Upgrade.SPRING, Upgrade.TEETH_DEPTH, Upgrade.TEETH_SPRING, Upgrade.OPENING_DEPTH -> 10
                Upgrade.TONGUE_DEPTH, Upgrade.TONGUE_SPRING -> 12
                else -> 1
            }, "mouth.spring" to 1) +
            if (withTongue) mapOf("mouth.tongue" to (if (upgrade == Upgrade.TONGUE) 1 else 7),
                "mouth.interior-depth" to (if (upgrade == Upgrade.TONGUE_SPRING) 8 else 1))
            else if (withTeeth || openingOnly) mapOf("mouth.interior-depth" to (if (upgrade == Upgrade.TEETH_SPRING) 9 else 1))
            else emptyMap()
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val outline = stack.instances.single { it.type == "mouth.outline" }
        val fitted = stack.instances.single { it.type == "mouth/fitted" }
        val index = stack.index(outline.id)
        val fittedIndex = stack.index(fitted.id)
        val fit = assertNotNull(RigBuilder.rigContext(reference.analysis, config).fittedMouth(config))
        assertTrue(fit.layered && fit.rig?.fit?.layered != null, fit.result.reason)
        val referenceStages = assertNotNull(reference.modifierEvaluation).outputs
        assertEquals(setOf(PartRole.UPPER_LINE, PartRole.LOWER_LINE),
            reference.analysis.layers.filter { it.opaquePixels > 0 && it.source.id.raw.startsWith("test-lip-line-") }
                .mapNotNull { it.semantic.role }.toSet())
        val beforeOutline = referenceStages[index - 1]
        val afterOutline = referenceStages[index]
        assertEquals(beforeOutline.meshes.map { it.id }, afterOutline.meshes.map { it.id })
        assertEquals(beforeOutline.textures, afterOutline.textures)
        val beforeMeshes = beforeOutline.meshes.associateBy { it.id }
        val beforeNodes = beforeOutline.deformers.associateBy { it.id }
        if (!withTongue && !withTeeth && !openingOnly) assertEquals(19, afterOutline.meshes.count { it != beforeMeshes[it.id] })
        if (!openingOnly) assertEquals(18, afterOutline.deformers.count { it != beforeNodes[it.id] })
        val fittedBefore = referenceStages[fittedIndex - 1].meshes.associateBy { it.id }
        val authored = reference.rig.layerIdByDrawableId.filterValues { it.startsWith("test-lip-line-") }.keys
        assertEquals(2, authored.size)
        assertTrue(referenceStages[fittedIndex].meshes.filter { it.id in authored }.all { it != fittedBefore[it.id] })

        val upgraded = stack.instances.single { it.type == upgrade.type }
        val stage = stack.index(upgraded.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(upgrade.type, upgrade.version))
        fun candidate(version: Int): WorkspaceDocument {
            val reference = assertNotNull(BundledAlgorithmArtifacts.find(upgrade.type, version)).reference
            val replaced = stack.copy(instances = stack.instances.map { entry ->
                if (entry.id == upgraded.id) entry.copy(version = version, plugin = reference) else entry
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = replaced.toJson()))
        }
        if (upgrade == Upgrade.VOWEL) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(7)) }
            assertContains(rejected.message.orEmpty(), "requires its fitted mouth v6")
            val vowels = setOf("ParamA", "ParamI", "ParamU", "ParamE", "ParamO")
            for (meshId in authored) {
                assertTrue(referenceStages[stage - 1].meshes.single { it.id == meshId }.shapes.none { it.parameter in vowels })
                val shapes = referenceStages[stage].meshes.single { it.id == meshId }.shapes.filter { it.parameter in vowels }
                assertEquals(vowels, shapes.map { it.parameter }.toSet())
                assertTrue(shapes.all { binding ->
                    binding.shapes.filterNotNull().any { shape -> shape.deltas.shared().any { it != 0f } }
                })
            }
        }
        if (upgrade == Upgrade.CAPTURE) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(6)) }
            assertContains(rejected.message.orEmpty(), "requires its fitted mouth v6")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val controls = RigModules.MOUTH_CAPTURE.defines.map { it.id }.toSet()
            assertEquals(controls, desired.parameters.map { it.id }.toSet() - previous.parameters.map { it.id }.toSet())
            for (meshId in authored) {
                assertTrue(previous.meshes.single { it.id == meshId }.shapes.none { it.parameter in controls })
                val shapes = desired.meshes.single { it.id == meshId }.shapes
                for (parameter in listOf("ParamMouthX", "ParamMouthPuckerWiden", "ParamMouthFunnel")) {
                    val binding = shapes.single { it.parameter == parameter }
                    assertTrue(binding.shapes.filterNotNull().any { shape -> shape.deltas.shared().any { it != 0f } },
                        "$meshId $parameter")
                }
            }
        }
        if (upgrade == Upgrade.JAW) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(8)) }
            assertContains(rejected.message.orEmpty(), "requires a shared fitted mouth v6")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val priorById = previous.meshes.associateBy { it.id }
            val changed = desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet()
            assertTrue(changed.containsAll(setOf("ArtMeshFace", "ArtMeshNose")), changed.toString())
            assertTrue(changed.intersect(authored).isEmpty(), changed.toString())
            assertEquals(previous.deformers, desired.deformers)
            assertEquals(previous.parameters, desired.parameters)
        }
        if (upgrade == Upgrade.CHEEK_LID) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(8)) }
            assertContains(rejected.message.orEmpty(), "requires its captured mouth/fitted provider")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val priorById = previous.meshes.associateBy { it.id }
            assertEquals(setOf("ArtMeshEyelashL", "ArtMeshEyelashR", "ArtMeshEyewhiteL", "ArtMeshEyewhiteR"),
                desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet())
            assertEquals(previous.parameters, desired.parameters)
        }
        if (upgrade in setOf(Upgrade.SPRING, Upgrade.TONGUE_SPRING, Upgrade.TEETH_SPRING)) {
            val legacyVersion = if (withTongue || withTeeth) 11 else 9
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(legacyVersion)) }
            if (withTongue || withTeeth) assertContains(rejected.message.orEmpty(), "requires the shared open-mouth source chain")
            else {
                assertTrue(stack.instances.none { it.type in setOf("mouth.tongue", "mouth.interior-depth") })
                assertContains(rejected.message.orEmpty(), "requires a shared fitted mouth v6")
            }
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val priorById = previous.meshes.associateBy { it.id }
            val changed = desired.meshes.filter { it != priorById[it.id] }
            assertEquals(setOf("ArtMeshFace", "ArtMeshNose"), changed.map { it.id }.toSet())
            for (mesh in changed) {
                val spring = mesh.shapes.single { it.parameter == "ParamMouthSpring" }
                assertTrue(spring.shapes.filterNotNull().any { shape -> shape.deltas.shared().any { it != 0f } }, mesh.id)
            }
            val added = (desired.physics.groups.toSet() - previous.physics.groups.toSet()).single()
            assertEquals(listOf("ParamMouthOpenY", "ParamMouthForm"), added.inputs.map { it.parameter })
            assertEquals(listOf("ParamMouthSpring"), added.outputs.map { it.parameter })
            assertTrue(added.segments.isNotEmpty())
            if (withTongue) {
                val tongue = previous.meshes.single { reference.rig.layerIdByDrawableId[it.id] == "test-layered-tongue" }
                assertTrue(tongue.maskedBy.isNotEmpty())
                val tongueOut = tongue.shapes.single { it.parameter == "ParamTongueOut" }
                assertTrue(tongueOut.shapes.filterNotNull().any { form -> form.deltas.shared().any { it != 0f } })
                assertEquals(tongue, desired.meshes.single { it.id == tongue.id })
                val control = previous.parameters.single { it.id == "ParamTongueOut" }
                assertEquals(listOf(0f, 0.12f, 1f), control.keys?.shared()?.toList())
                assertEquals(control, desired.parameters.single { it.id == control.id })
            }
            if (withTeeth) {
                assertTrue(stack.instances.none { it.type == "mouth.tongue" })
                assertTrue(desired.parameters.none { it.id == "ParamTongueOut" })
                val tooth = previous.meshes.single { reference.rig.layerIdByDrawableId[it.id] == "test-layered-tooth" }
                assertEquals(tooth, desired.meshes.single { it.id == tooth.id })
                val opening = previous.meshes.single { it.id == tooth.maskedBy.single() }
                assertEquals(opening, desired.meshes.single { it.id == opening.id })
            }
        }
        if (upgrade == Upgrade.TONGUE) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(6)) }
            assertContains(rejected.message.orEmpty(), "requires a shared fitted mouth v6")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val sourceTongue = reference.analysis.layers.single { it.source.id.raw == "test-layered-tongue" }
            assertTrue(sourceTongue.opaquePixels > 0 && sourceTongue.source.visible && fit.covers(sourceTongue))
            val layerByMesh = reference.rig.layerIdByDrawableId
            val before = previous.meshes.single { layerByMesh[it.id] == sourceTongue.source.id.raw }
            val after = desired.meshes.single { it.id == before.id }
            assertTrue(after.visible)
            assertNotNull(after.tile)
            assertEquals(before.tile, after.tile)
            assertEquals(before.maskedBy, after.maskedBy)
            val mask = reference.analysis.layers.single { it.source.id.raw == layerByMesh[after.maskedBy.single()] }
            assertTrue(mask.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && mask.semantic.role == null)
            val control = (desired.parameters - previous.parameters.toSet()).single()
            assertEquals("ParamTongueOut", control.id)
            assertEquals(listOf(0f, 0.12f, 1f), control.keys?.shared()?.toList())
            assertTrue(before.shapes.none { it.parameter == "ParamTongueOut" })
            val shape = after.shapes.single { it.parameter == "ParamTongueOut" }
            assertTrue(shape.shapes.filterNotNull().any { form -> form.deltas.shared().any { it != 0f } })
        }
        if (upgrade == Upgrade.TONGUE_JAW) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(10)) }
            assertContains(rejected.message.orEmpty(), "requires the shared open-mouth outline, vowel and capture chain")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val priorById = previous.meshes.associateBy { it.id }
            val changed = desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet()
            assertTrue(changed.containsAll(setOf("ArtMeshFace", "ArtMeshNose")), changed.toString())
            val tongue = previous.meshes.single { reference.rig.layerIdByDrawableId[it.id] == "test-layered-tongue" }
            val shape = tongue.shapes.single { it.parameter == "ParamTongueOut" }
            assertTrue(shape.shapes.filterNotNull().any { form -> form.deltas.shared().any { it != 0f } })
            assertEquals(tongue, desired.meshes.single { it.id == tongue.id })
            val control = previous.parameters.single { it.id == "ParamTongueOut" }
            assertEquals(listOf(0f, 0.12f, 1f), control.keys?.shared()?.toList())
            assertEquals(previous.parameters, desired.parameters)
            assertEquals(previous.deformers, desired.deformers)
        }
        if (upgrade == Upgrade.TONGUE_CHEEK_LID) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(10)) }
            assertContains(rejected.message.orEmpty(), "requires its captured mouth.jaw provider")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val priorById = previous.meshes.associateBy { it.id }
            assertEquals(setOf("ArtMeshEyelashL", "ArtMeshEyelashR", "ArtMeshEyewhiteL", "ArtMeshEyewhiteR"),
                desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet())
            val tongue = previous.meshes.single { reference.rig.layerIdByDrawableId[it.id] == "test-layered-tongue" }
            assertEquals(tongue, desired.meshes.single { it.id == tongue.id })
            assertEquals(listOf(0f, 0.12f, 1f),
                previous.parameters.single { it.id == "ParamTongueOut" }.keys?.shared()?.toList())
            assertEquals(previous.parameters, desired.parameters)
        }
        if (upgrade == Upgrade.TONGUE_DEPTH) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(7)) }
            assertContains(rejected.message.orEmpty(), "requires a shared fitted mouth v6")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val priorById = previous.meshes.associateBy { it.id }
            val before = previous.meshes.single { reference.rig.layerIdByDrawableId[it.id] == "test-layered-tongue" }
            val after = desired.meshes.single { it.id == before.id }
            assertEquals(before.maskedBy, after.maskedBy)
            val opening = after.maskedBy.single()
            assertEquals(setOf(before.id, opening), desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet())
            assertEquals(before.tile, after.tile)
            assertNotEquals(before.offsets, after.offsets)
            val shading = after.channels.getValue(Channel.MULTIPLY_COLOR)
            assertTrue(shading.cells.map { it.form }.distinct().size > 1)
            val tongueOut = "ParamTongueOut"
            val beforeOut = before.shapes.single { it.parameter == tongueOut }
            val afterOut = after.shapes.single { it.parameter == tongueOut }
            assertEquals(beforeOut.keys, afterOut.keys)
            assertEquals(beforeOut.limits, afterOut.limits)
            assertTrue(afterOut.shapes.filterNotNull().any { form -> form.deltas.shared().any { it != 0f } })
            assertEquals(listOf(0f, 0.12f, 1f), desired.parameters.single { it.id == tongueOut }.keys?.shared()?.toList())
            assertEquals(previous.parameters, desired.parameters)
        }
        if (upgrade == Upgrade.TEETH_DEPTH) {
            assertTrue(stack.instances.none { it.type == "mouth.tongue" })
            assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(8)) }
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val layer = reference.analysis.layers.single { it.source.id.raw == "test-layered-tooth" }
            assertTrue(layer.source.visible && layer.opaquePixels > 0 && fit.covers(layer))
            val before = previous.meshes.single { reference.rig.layerIdByDrawableId[it.id] == layer.source.id.raw }
            val after = desired.meshes.single { it.id == before.id }
            val opening = after.maskedBy.single()
            assertEquals(before.maskedBy, after.maskedBy)
            assertNotNull(after.tile)
            assertEquals(before.tile, after.tile)
            assertNotEquals(before.offsets, after.offsets)
            assertEquals(before.channels[Channel.MULTIPLY_COLOR], after.channels[Channel.MULTIPLY_COLOR])
            val priorById = previous.meshes.associateBy { it.id }
            assertEquals(setOf(before.id, opening), desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet())
            val shading = desired.meshes.single { it.id == opening }.channels.getValue(Channel.MULTIPLY_COLOR)
            assertTrue(shading.cells.map { it.form }.distinct().size > 1)
            assertTrue(desired.parameters.none { it.id == "ParamTongueOut" })
            assertEquals(previous.parameters, desired.parameters)
        }
        if (openingOnly) {
            assertTrue(stack.instances.none { it.type == "mouth.tongue" })
            assertTrue(reference.analysis.layers.none {
                it.opaquePixels > 0 && it.semantic.tag in setOf(SemanticTag.TONGUE, SemanticTag.TOOTH_T, SemanticTag.TOOTH_B)
            })
            val layer = reference.analysis.layers.single { it.semantic.tag == SemanticTag.MOUTH_OPEN && it.opaquePixels > 0 }
            assertTrue(fit.covers(layer) && !layer.source.clipped)
            assertEquals(io.github.psd2live.algorithms.binding.MouthRig.Follow.OPENING, fit.follow(layer))
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val before = previous.meshes.single { reference.rig.layerIdByDrawableId[it.id] == layer.source.id.raw }
            val after = desired.meshes.single { it.id == before.id }
            val priorById = previous.meshes.associateBy { it.id }
            assertEquals(setOf(before.id), desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet())
            assertEquals(before.maskedBy, after.maskedBy)
            assertEquals(before.geometry, after.geometry)
            assertEquals(before.offsets, after.offsets)
            val shading = after.channels.getValue(Channel.MULTIPLY_COLOR)
            assertNotEquals(before.channels[Channel.MULTIPLY_COLOR], shading)
            assertTrue(shading.cells.map { it.form }.distinct().size > 1)
            assertTrue(desired.parameters.none { it.id == "ParamTongueOut" })
            assertEquals(previous.parameters, desired.parameters)
        }
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            WorkspacePreviewBuilder().build(candidate(upgrade.version))
        } finally {
            RigBuildProfile.recording = recording
        }
        assertTrue(upgraded.type !in legacyTemplateConsumers())
        val actual = assertNotNull(preview.modifierEvaluation)
        assertEquals(if (upgrade == Upgrade.OUTLINE) 9 else 1, actual.entries[index].instance.version)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        assertEquals(if (upgrade == Upgrade.FITTED) 8 else fittedVersion, actual.entries[fittedIndex].instance.version)
        assertEquals(upgrade.version, actual.entries[stage].instance.version)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[fittedIndex].status)
        fun equal(expected: RigIR, next: RigIR, stage: String) {
            if (expected == next) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(expected) != it.get(next) }.map { it.name }
            val knownMeshes = expected.meshes.associateBy { it.id }
            val changedMeshes = next.meshes.filter { it != knownMeshes[it.id] }.map {
                val known = knownMeshes[it.id]
                "${it.id} (unchanged=${it == beforeMeshes[it.id]}, parent=${it.parent == known?.parent}, " +
                    "geometry=${it.geometry == known?.geometry}, offsets=${it.offsets == known?.offsets}, shapes=${it.shapes == known?.shapes})"
            }
            val knownNodes = expected.deformers.associateBy { it.id }
            val changedNodes = next.deformers.filter { it != knownNodes[it.id] }.map { "${it.name} (unchanged=${it == beforeNodes[it.id]})" }
            fail("$stage differs in $fields, meshes=$changedMeshes, deformers=$changedNodes")
        }
        for (stage in stack.instances.indices) equal(referenceStages[stage], actual.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")

        val root = Files.createTempDirectory("layered-mouth-outline-")
        val preferences = Preferences.userRoot().node("layered-mouth-outline/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = actual.entries[stage].instance
            val sourceInputs = RigModifierSource.of(preview)
            val definition = when (upgrade) {
                Upgrade.OUTLINE -> RigFittedMouthOutlineDefinition.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.FITTED -> RigFittedMouthDefinition.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.VOWEL -> RigMouthVowelModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.CAPTURE -> RigMouthCaptureModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.JAW -> RigMouthJawModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.CHEEK_LID -> RigMouthCheekLidModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.SPRING -> RigMouthSpringModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.TONGUE -> RigMouthTongueModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.TONGUE_JAW -> RigMouthJawModifier.sharedFrameDefinition(sourceInputs, withTongue = true, layered = true)
                Upgrade.TONGUE_CHEEK_LID -> RigMouthCheekLidModifier.sharedFrameDefinition(sourceInputs, withTongue = true, layered = true)
                Upgrade.TONGUE_DEPTH -> RigMouthDepthModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.TEETH_DEPTH, Upgrade.OPENING_DEPTH ->
                    RigMouthDepthModifier.sharedFrameDefinition(sourceInputs, layered = true, withTeethOnly = true)
                Upgrade.TEETH_SPRING -> RigMouthSpringModifier.sharedFrameDefinition(sourceInputs, layered = true, withTeethOnly = true)
                Upgrade.TONGUE_SPRING -> RigMouthSpringModifier.sharedFrameDefinition(sourceInputs, withSharedTongue = true, layered = true)
            }
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            assertEquals(setOf("before", "after"), context.resources.keys)
            val providerTypes = when (upgrade) {
                Upgrade.OUTLINE -> listOf("mouth/fitted")
                Upgrade.FITTED -> listOf("face/fitted", "eye/fitted", "brow/fitted")
                Upgrade.VOWEL -> listOf("mouth/fitted", "mouth.outline")
                Upgrade.CAPTURE -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel")
                Upgrade.JAW -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture")
                Upgrade.CHEEK_LID -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw")
                Upgrade.SPRING -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid")
                Upgrade.TONGUE -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture")
                Upgrade.TONGUE_JAW -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.tongue")
                Upgrade.TONGUE_CHEEK_LID -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.tongue", "mouth.jaw")
                Upgrade.TONGUE_DEPTH -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.tongue", "mouth.jaw", "mouth.cheek-lid")
                Upgrade.TEETH_DEPTH, Upgrade.OPENING_DEPTH ->
                    listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid")
                Upgrade.TEETH_SPRING -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture",
                    "mouth.jaw", "mouth.cheek-lid", "mouth.interior-depth")
                Upgrade.TONGUE_SPRING -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture",
                    "mouth.tongue", "mouth.jaw", "mouth.cheek-lid", "mouth.interior-depth")
            }
            for (type in providerTypes) {
                val provider = context.upstream.single { it.type == type }
                val original = stack.instances.single { it.type == type }
                assertEquals(original.version, provider.version)
                assertEquals(original.id, provider.instanceId)
                assertEquals(original.plugin, provider.plugin)
            }
            equal(referenceStages[stage], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[stage - 1], instance, {}, store, context = context,
                declaredParameters = definition.parameters(instance)), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}

internal fun layeredMouthDocument(source: SourceArt, config: PipelineConfig, withTongue: Boolean = false,
                            withTeeth: Boolean = false, openingOnly: Boolean = false): WorkspaceDocument {
    val owner = RigGenerationSource.analyze(source, config).layers
        .filter { it.semantic.tag == SemanticTag.MOUTH && it.semantic.role == null && !it.source.clipped }
        .maxBy { it.opaquePixels }
    val bounds = owner.source.bounds
    val width = bounds.width
    val height = bounds.height
    val lines = listOf(PartRole.UPPER_LINE, PartRole.LOWER_LINE).mapIndexed { side, role ->
        val id = LayerId("test-lip-line-" + side)
        val pixels = ByteArray(width * height * 4)
        for (x in 0 until width) {
            val u = 2f * (x + 0.5f) / width - 1f
            val curve = sqrt(1f - u * u)
            val center = height * (0.5f + (if (side == 0) -0.32f else 0.32f) * curve)
            val y0 = (center.toInt() - 1).coerceAtLeast(0)
            for (y in y0..(y0 + 2).coerceAtMost(height - 1)) {
                val i = (y * width + x) * 4
                pixels[i] = 100; pixels[i + 1] = 60; pixels[i + 2] = 40; pixels[i + 3] = -1
            }
        }
        WorkspaceSourceLayer(id, role.word + (owner.semantic.variant?.let { " $it" } ?: ""), owner.source.groupPath,
            SourceLayerKind.Raster, true, owner.source.order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(width, height, pixels), null, null, false) to
            LayerClassificationOverride(tag = SemanticTag.MOUTH, side = owner.semantic.side, role = role)
    }
    val interior = if (withTongue || withTeeth) {
        val tag = if (withTongue) SemanticTag.TONGUE else SemanticTag.TOOTH_T
        val partName = if (withTongue) "tongue" else "tooth"
        val partWidth = (width / 3).coerceAtLeast(5)
        val partHeight = (height / 4).coerceAtLeast(4)
        val top = bounds.top + if (withTongue) height / 2 else height / 4
        val partBounds = LayerBounds(bounds.left + (width - partWidth) / 2, top, partWidth, partHeight)
        val raster = LayerRaster(partWidth, partHeight, ByteArray(partWidth * partHeight * 4) { -1 })
        listOf(WorkspaceSourceLayer(LayerId("test-layered-$partName"),
            partName + (owner.semantic.variant?.let { " $it" } ?: ""), owner.source.groupPath,
            SourceLayerKind.Raster, true, owner.source.order, partBounds, 1f, true, LayerBlend.Normal,
            ChannelMask.ALL, raster, null, null, false) to LayerClassificationOverride(tag = tag, side = owner.semantic.side))
    } else emptyList()
    val additions = lines + interior
    val artwork = object : SourceArt by source {
        override val layers = source.layers.flatMap { layer ->
            if (layer.id == owner.source.id) additions.map { it.first } + layer else listOf(layer)
        }.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
    }
    val overrides = additions.associate { (layer, classification) -> layer.id.raw to classification }.toMutableMap()
    if (openingOnly) {
        overrides[owner.source.id.raw] = LayerClassificationOverride(tag = SemanticTag.MOUTH_OPEN, side = owner.semantic.side)
    }
    return WorkspaceDocument(artwork, emptyMap(), emptySet(), overrides, emptyMap(), config.rigEdits,
        WorkspaceSettingsCodec.encode(config))
}
