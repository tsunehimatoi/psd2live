package io.github.psd2live.core.modifier

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspaceModifierEdits
import io.github.psd2live.application.WorkspaceModifierPlan
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.core.sim.*
import io.github.psd2live.document.WorkspaceSourceArt
import io.github.psd2live.document.WorkspaceSourceLayer
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParameterFolderOrganizationTest {
    private fun fullModernDocument(withExpressionToggle: Boolean = true): WorkspaceDocument {
        val eyed = Characters.eyed(legacyEyes = false)
        val browed = Characters.browed()
        val mouthed = Characters.mouthed()
        val extraIds = setOf("brow_r", "brow_l", "mouth")
        val baseLayers = eyed.source.layers.filterNot { it.id.raw in extraIds }
        val browLayers = browed.source.layers.filter { it.id.raw in setOf("brow_r", "brow_l") }
        val mouthLayer = mouthed.source.layers.single { it.id.raw == "mouth" }
        val layers = ArrayList<WorkspaceSourceLayer>(baseLayers.size + browLayers.size + 2)
        baseLayers.mapTo(layers) { it as WorkspaceSourceLayer }
        val nextOrder = (layers.maxOf { it.order }) + 1
        browLayers.forEachIndexed { index, layer ->
            layers += (layer as WorkspaceSourceLayer).copy(order = nextOrder + index)
        }
        layers += (mouthLayer as WorkspaceSourceLayer).copy(order = nextOrder + browLayers.size)
        val overrides = LinkedHashMap<String, LayerClassificationOverride>()
        overrides.putAll(eyed.layerOverrides)
        browed.layerOverrides.filterKeys { it in setOf("brow_r", "brow_l") }.forEach { (k, v) -> overrides[k] = v }
        overrides["mouth"] = mouthed.layerOverrides.getValue("mouth")
        if (withExpressionToggle) {
            val toggleSource = (browLayers.first() as WorkspaceSourceLayer).copy(
                id = LayerId("badge"),
                name = "badge",
                order = nextOrder + browLayers.size + 1,
            )
            layers += toggleSource
            overrides["badge"] = LayerClassificationOverride(
                type = LayerType.TOGGLE,
                tag = SemanticTag.OBJECTS,
                side = Side.NONE,
                parameter = "ParamExprBadge",
            )
        }
        val config = PipelineConfig(atlasSize = 512, generatePhysics = true, exportMoc3 = false, recipe = RigRecipe.Modern)
        return eyed.copy(
            source = WorkspaceSourceArt(eyed.source.widthPx, eyed.source.heightPx, layers, eyed.source.groups),
            layerOverrides = overrides,
            rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()),
            settings = WorkspaceSettingsCodec.encode(config),
        )
    }

    private fun collectGroups(nodes: List<ParameterNode>): List<ParameterNode.Group> = buildList {
        for (node in nodes) {
            if (node is ParameterNode.Group) {
                add(node)
                addAll(collectGroups(node.children))
            }
        }
    }

    private fun collectChildIds(group: ParameterNode.Group): List<ParameterId> = buildList {
        for (child in group.children) {
            when (child) {
                is ParameterNode.Param -> add(child.id)
                is ParameterNode.Group -> addAll(collectChildIds(child))
            }
        }
    }

    private fun assertCanonicalUniqueFolders(puppet: PuppetModel) {
        val groups = collectGroups(puppet.parameterTree)
        assertTrue(groups.isNotEmpty(), "Expected parameter folders")
        val groupIds = groups.map { it.id.raw }
        assertEquals(groupIds.distinct().size, groupIds.size, "Duplicate group IDs: $groupIds")
        assertTrue(groupIds.none { it.startsWith("modifier:") }, "Expected clean IDs, found $groupIds")

        val topGroups = puppet.parameterTree.filterIsInstance<ParameterNode.Group>()
        val topNames = topGroups.map { it.name }
        assertEquals(topNames.distinct().size, topNames.size, "Duplicate folder names: $topNames")

        val ranks = topGroups.mapNotNull { ModifierParameterGroups.canonicalRank(it.id.raw) }
        assertEquals(ranks.sorted(), ranks, "Top-level folders out of canonical order: ${topGroups.map { it.id.raw }}")
        assertTrue(ranks.distinct().size == ranks.size, "Duplicate canonical folders: ${topGroups.map { it.id.raw }}")

        val rootParams = puppet.parameterTree.filterIsInstance<ParameterNode.Param>()
        assertTrue(rootParams.isEmpty(), "Unexpected ungrouped root parameters: ${rootParams.map { it.id.raw }}")

        assertEquals(emptyList(), RigIdCheck.conflicts(puppet))
        assertEquals(emptyList(), RigIdCheck.invalid(puppet).filter { it.startsWith("param_group ") })
    }

    private fun simulationInstanceForMesh(puppet: PuppetModel, meshId: String, simId: String, simName: String): RigModifierInstance {
        val mesh = puppet.drawables.first { it.id.raw == meshId }
        val vertices = mesh.mesh!!.vertexCount
        val zero = FloatArray(vertices * 2)
        val pos = FloatArray(vertices * 2) { if (it % 2 == 0) 1.2f else -0.4f }
        val neg = FloatArray(vertices * 2) { if (it % 2 == 0) -1.2f else 0.4f }
        val paramId = "ParamSim${SwingGenerator.asciiStem(simId)}_1"
        val draft = RigSimEdit(id = simId, name = simName, kind = SimKind.CLOTH, targets = listOf(meshId), blendShapes = false)
        val axis = SimBakedAxis(paramId, floatArrayOf(-30f, 0f, 30f), mapOf(meshId to listOf(neg, zero, pos)))
        val physics = RigPhysicsEdit(
            id = "PhysicsSim_$simId",
            name = simName,
            inputs = listOf(PhysicsInput("ParamAngleX", 60f, PhysicsSourceType.X)),
            outputs = listOf(PhysicsOutput(paramId, 1, 1f, 100f, PhysicsSourceType.ANGLE)),
            segments = listOf(PhysicsSegment(15f, 0.9f, 0.8f, 1f)),
        )
        val bake = SimBakeResult(
            fingerprint = SimBake.fingerprint(puppet, draft),
            vertexCounts = mapOf(meshId to vertices),
            statics = emptyList(),
            modes = listOf(SimBakedMode(axis, 1.5f, 1f)),
            physics = physics,
        )
        return RigModifierInstance(
            id = "sim-$simId",
            type = "simulation",
            version = 1,
            targets = listOf(ModifierTarget("mesh", meshId)),
            config = draft.copy(bake = bake).toJson(),
        )
    }

    @Test fun fullModernPresetWithTogglesSwingAndSimulationHasCleanCanonicalFolders() {
        val baseDoc = fullModernDocument(withExpressionToggle = true)
        val (preparedDoc, preparedPreview) = WorkspacePresetInitialization.prepare(
            baseDoc,
            WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false),
        )
        val swingMeshId = preparedPreview.rig.puppet.drawables.first {
            preparedPreview.rig.layerIdByDrawableId[it.id.raw] == "skirt"
        }.id.raw
        val simMeshId = preparedPreview.rig.puppet.drawables.first {
            preparedPreview.rig.layerIdByDrawableId[it.id.raw] == "sleeve_l"
        }.id.raw
        var stack = RigModifierStack.fromJson(requireNotNull(preparedDoc.rigEdits.modifierStack))
        val swingEdit = RigSwingEdit.single("sleeve_swing", "Sleeve Swing", SwingKind.LATERAL, listOf(swingMeshId), listOf("ParamSwingTmp"))
        stack = stack.add("swing", 1, listOf(ModifierTarget("mesh", swingMeshId)), swingEdit.toJson())
        val swingInstance = stack.instances.last()
        val afterSwingDoc = preparedDoc.copy(rigEdits = preparedDoc.rigEdits.copy(modifierStack = stack.toJson()))
        val afterSwingPreview = PSD2LivePipeline().buildPreview(afterSwingDoc.source, afterSwingDoc.config(), ProgressListener { _, _ -> })
        val simInstance = simulationInstanceForMesh(afterSwingPreview.rig.puppet, simMeshId, "sleeve", "Sleeve Sim")
        stack = stack.add(simInstance.type, simInstance.version, simInstance.targets, simInstance.config)
        val simAddedInstance = stack.instances.last()
        val finalDoc = afterSwingDoc.copy(rigEdits = afterSwingDoc.rigEdits.copy(modifierStack = stack.toJson()))
        val finalPreview = PSD2LivePipeline().buildPreview(finalDoc.source, finalDoc.config(), ProgressListener { _, _ -> })
        val puppet = finalPreview.rig.puppet

        assertCanonicalUniqueFolders(puppet)

        val byId = puppet.parameterTree.filterIsInstance<ParameterNode.Group>().associateBy { it.id.raw }
        val customGroup = assertNotNull(byId[ModifierParameterGroups.CUSTOM], "Expected ParamGroupCustom")
        assertTrue(ParameterId("ParamExprBadge") in collectChildIds(customGroup))

        val physicsGroup = assertNotNull(byId[ModifierParameterGroups.PHYSICS], "Expected ParamGroupPhysics")
        val physicsChildren = collectChildIds(physicsGroup)
        assertTrue(ParameterId(swingInstance.outputId("swing:lateral:0")) in physicsChildren)
        assertTrue(ParameterId(simAddedInstance.outputId("simulation:ParamSimsleeve_1")) in physicsChildren)
    }

    @Test fun scrambledModifierOrderStillProducesCanonicalFoldersWithoutDuplicates() {
        val doc = fullModernDocument(withExpressionToggle = false)
        val pipeline = PSD2LivePipeline()
        val ordinary = pipeline.buildPreview(doc.source, doc.config(), ProgressListener { _, _ -> })
        val targets = ordinary.rig.puppet.drawables
            .filter { it.mesh != null && it.id.raw in ordinary.rig.ownLayerIdByDrawableId }
            .map { ModifierTarget("mesh", it.id.raw) }
        val targetsJson = JsonArray(targets.map { target ->
            buildJsonObject { put("kind", target.kind); put("id", target.id) }
        })
        var stack = RigModifierStack()
        var currentPreview = ordinary
        var currentDoc = doc
        for (type in listOf("mouth/fitted", "body/legacy", "eye/fitted", "brow/fitted", "expression.cheek", "face/fitted")) {
            val request = buildJsonObject {
                put("type", type)
                put("targets", targetsJson)
            }
            stack = WorkspaceModifierPlan.plan(currentPreview, stack, request).first
            currentDoc = doc.copy(rigEdits = doc.rigEdits.copy(modifierStack = stack.toJson()))
            currentPreview = pipeline.buildPreview(currentDoc.source, currentDoc.config(), ProgressListener { _, _ -> })
        }
        val puppet = currentPreview.rig.puppet
        assertCanonicalUniqueFolders(puppet)

        val faceGroup = puppet.parameterTree.filterIsInstance<ParameterNode.Group>()
            .single { it.id == ParameterGroupId(ModifierParameterGroups.FACE) }
        val faceChildIds = collectChildIds(faceGroup).map { it.raw }
        assertTrue(faceChildIds.indexOf("ParamAngleX") >= 0)
        assertTrue(faceChildIds.indexOf("ParamAngleY") > faceChildIds.indexOf("ParamAngleX"))
        assertTrue(faceChildIds.indexOf("ParamAngleZ") > faceChildIds.indexOf("ParamAngleY"))
        assertTrue(faceChildIds.indexOf("ParamCheek") > faceChildIds.indexOf("ParamAngleZ"),
            "Expected ParamAngleX/Y/Z before ParamCheek, got $faceChildIds")
    }

    @Test fun standaloneSwingAndSimulationCreateAndMergeSinglePhysicsFolder() {
        val doc = Characters.head(physics = false)
        val emptyDoc = doc.copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val pipeline = PSD2LivePipeline()
        val ordinary = pipeline.buildPreview(emptyDoc.source, emptyDoc.config(), ProgressListener { _, _ -> })
        val hairMeshId = ordinary.rig.puppet.drawables.first {
            ordinary.rig.layerIdByDrawableId[it.id.raw] == "hair"
        }.id.raw
        val faceMeshId = ordinary.rig.puppet.drawables.first {
            ordinary.rig.layerIdByDrawableId[it.id.raw] == "face"
        }.id.raw

        var stack = RigModifierStack().add(
            "swing",
            1,
            listOf(ModifierTarget("mesh", hairMeshId)),
            RigSwingEdit.single("hair_swing", "Hair Swing", SwingKind.LATERAL, listOf(hairMeshId), listOf("ParamHairTmp")).toJson(),
        )
        val swingInst = stack.instances.last()
        val swingDoc = emptyDoc.copy(rigEdits = emptyDoc.rigEdits.copy(modifierStack = stack.toJson()))
        val afterSwing = pipeline.buildPreview(swingDoc.source, swingDoc.config(), ProgressListener { _, _ -> })

        val simTemplate = simulationInstanceForMesh(afterSwing.rig.puppet, faceMeshId, "face_sim", "Face Sim")
        stack = stack.add(simTemplate.type, simTemplate.version, simTemplate.targets, simTemplate.config)
        val simInst = stack.instances.last()
        val finalDoc = swingDoc.copy(rigEdits = swingDoc.rigEdits.copy(modifierStack = stack.toJson()))
        val finalPreview = pipeline.buildPreview(finalDoc.source, finalDoc.config(), ProgressListener { _, _ -> })
        val puppet = finalPreview.rig.puppet

        assertCanonicalUniqueFolders(puppet)
        val physicsGroups = puppet.parameterTree.filterIsInstance<ParameterNode.Group>()
            .filter { it.id == ParameterGroupId(ModifierParameterGroups.PHYSICS) }
        assertEquals(1, physicsGroups.size)
        val physics = physicsGroups.single()
        assertEquals(tr("model.group.physics"), physics.name)
        val childIds = collectChildIds(physics)
        assertTrue(ParameterId(swingInst.outputId("swing:lateral:0")) in childIds)
        assertTrue(ParameterId(simInst.outputId("simulation:ParamSimfacesim_1")) in childIds)
    }

    @Test fun applyThroughAndApplySelectedPreserveResultWithCanonicalFolders() {
        val baseDoc = fullModernDocument(withExpressionToggle = true)
        val (preparedDoc, preparedPreview) = WorkspacePresetInitialization.prepare(
            baseDoc,
            WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false),
        )
        val stack = RigModifierStack.fromJson(requireNotNull(preparedDoc.rigEdits.modifierStack))
        val selectedTarget = stack.instances.first { it.type == "expression.toggles" }
        val selectedApplied = WorkspaceModifierEdits.apply(
            WorkspaceDocumentOperation("modifier_apply", buildJsonObject {
                put("id", selectedTarget.id)
                put("scope", "selected")
            }),
            preparedDoc,
            preparedPreview,
            WorkspaceRasterWork.Direct,
        )
        val browTarget = stack.instances.first { it.type == "brow" || it.type == "brow/fitted" }
        val directPrefix = RigModifierAlgorithms.evaluator(RigModifierSource.of(preparedPreview), stack)
            .applyThrough(RigModifierPipeline.input(preparedPreview), stack, browTarget.id)
        assertCanonicalUniqueFolders(PuppetIr.toPuppet(directPrefix.result.rig))
        assertEquals(RigIrCompiler.compile(preparedPreview).parameterTree, directPrefix.result.rig.parameterTree)

        val prefixApplied = WorkspaceModifierEdits.apply(
            WorkspaceDocumentOperation("modifier_apply", buildJsonObject {
                put("id", selectedTarget.id)
                put("scope", "prefix")
            }),
            preparedDoc,
            preparedPreview,
            WorkspaceRasterWork.Direct,
        )
        val rebuiltSelected = PSD2LivePipeline().buildPreview(selectedApplied.source, selectedApplied.config(), ProgressListener { _, _ -> })
        val rebuiltPrefix = PSD2LivePipeline().buildPreview(prefixApplied.source, prefixApplied.config(), ProgressListener { _, _ -> })
        assertCanonicalUniqueFolders(rebuiltSelected.rig.puppet)
        assertCanonicalUniqueFolders(rebuiltPrefix.rig.puppet)
        assertEquals(RigIrCompiler.compile(preparedPreview).parameterTree, RigIrCompiler.compile(rebuiltSelected).parameterTree)
        assertEquals(RigIrCompiler.compile(preparedPreview).parameterTree, RigIrCompiler.compile(rebuiltPrefix).parameterTree)
    }

    @Test fun chainParametersDropLayerNamePrefixAndUseConciseLocaleNames() {
        val base = Characters.head(physics = true, hairChains = true)
        val narrowHairLayers = base.source.layers.map { raw ->
            val layer = raw as WorkspaceSourceLayer
            if (layer.id.raw != "hair") layer else {
                val w = layer.raster.width
                val h = layer.raster.height
                val pixels = ByteArray(w * h * 4)
                for (y in 8 until 56) for (x in 28 until 36) {
                    val o = (y * w + x) * 4
                    pixels[o] = 80; pixels[o + 1] = 100; pixels[o + 2] = 120; pixels[o + 3] = -1
                }
                layer.copy(name = "back hair", raster = LayerRaster(w, h, pixels))
            }
        }
        val doc = base.copy(
            source = WorkspaceSourceArt(base.source.widthPx, base.source.heightPx, narrowHairLayers, base.source.groups),
            rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()),
        )
        val previous = I18n.currentLanguage
        try {
            val expected = listOf(
                AppLanguage.CHINESE to "后发1 第2段",
                AppLanguage.ENGLISH to "Back hair 1 Seg 2",
                AppLanguage.JAPANESE to "後ろ髪1 第2段",
                AppLanguage.KOREAN to "뒷머리1 2단",
            )
            for ((language, expectedSeg2) in expected) {
                I18n.setLanguage(language, persist = false)
                val (_, preview) = WorkspacePresetInitialization.prepare(
                    doc,
                    WorkspaceRasterWork.Direct,
                    WorkspaceInitialPreset(clothing = false),
                )
                val names = preview.rig.puppet.parameters.map { it.name }
                assertTrue(names.any { it == expectedSeg2 }, "$language expected '$expectedSeg2' in $names")
                assertTrue(names.none { it.startsWith("back hair ", ignoreCase = false) },
                    "$language must not include raw layer name 'back hair': $names")
            }
        } finally {
            I18n.setLanguage(previous, persist = false)
        }
    }
}
