package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BindingProjection
import io.github.psd2live.algorithms.binding.LegacyBodyInputs
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.MouthOutlineV3Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class MouthOutlineV3ParityTest {
    @Test fun fittedOpeningAndRibbonsMatchNativeGeometry() = compare(false)

    @Test fun fittedOpeningAndRibbonsFollowActiveHead() = compare(true)

    @Test fun fittedOpeningAndRibbonsFollowStyledHead() = compare(true, styled = true)

    private fun compare(headTurn: Boolean, styled: Boolean = false) {
        val mouth = Characters.mouthed()
        val document = if (styled) {
            val eyes = Characters.eyed()
            val additions = eyes.source.layers.filter { it.id.raw !in mouth.layerOverrides }
            val overrides = eyes.layerOverrides.filterKeys { it !in mouth.layerOverrides }
            mouth.copy(source = WorkspaceSourceArt(mouth.source.widthPx, mouth.source.heightPx,
                mouth.source.layers + additions, mouth.source.groups), layerOverrides = mouth.layerOverrides + overrides)
        } else mouth
        val bases = mapOf("mouth" to BaseChoice("mouth/fitted")) +
            if (styled) mapOf("eye" to BaseChoice("eye/fitted", version = 2)) else emptyMap()
        val recipe = RigRecipe(true, bases = bases,
            modules = RigModules.modules.associate { it.id to ModuleChoice(headTurn && it == RigModules.FACE_DEPTH_TURN) })
        val config = document.rawConfig().copy(generateDeformers = true, meshOnly = false, deferRig = false,
            mouthOutlineEnabled = false, mouthThickness = 1.5f, mouthColor = null, recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val built = RigBuilder.build(analysis, atlas, config, cache)
        val layer = analysis.layers.single { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && it.semantic.role == null }
        val layerId = layer.source.id.raw
        val id = built.layerIdByDrawableId.entries.single { it.value == layerId }.key
        val target = ModifierTarget("mesh", id)
        val enabled = config.copy(mouthOutlineEnabled = true)
        val oldGeometry = RigBuilder.rigContext(analysis, config, cache)
        val newGeometry = RigBuilder.rigContext(analysis, enabled, cache)
        assertEquals(styled, oldGeometry.faceTurn(config) != null)
        assertEquals(styled, newGeometry.faceTurn(enabled) != null)
        val oldLayer = oldGeometry.analysis.layers.single { it.source.id.raw == layerId }
        val newLayer = newGeometry.analysis.layers.single { it.source.id.raw == layerId }
        val oldParent = oldGeometry.parentAndFrame(oldLayer, config)
        val newParent = newGeometry.parentAndFrame(newLayer, enabled)
        val head = if (headTurn) "head" else null
        fun mouth(geometry: RigBuilder.RigContext, settings: PipelineConfig, parent: Pair<org.umamo.runtime.model.DeformerId?, Bounds>,
                  material: ClassifiedLayer) = RigFittedMouthSource.capture(geometry, settings,
            mapOf(id to parent.first?.raw), mapOf(id to material), mapOf(id to parent.second), listOf(id), head)
        val before = mouth(oldGeometry, config, oldParent, oldLayer)
        val after = mouth(newGeometry, enabled, newParent, newLayer)
        val outline = RigMouthOutlineSource.capture(analysis, config, mapOf(id to RigMouthOutlineSource.Owner(layerId, id,
            fittedProvider = "mouth", headOnMesh = after.head != null &&
                newParent.first?.raw?.startsWith("DeformMouthShape") != true)), head = after.head, cache = cache).encode()
        val neutral = RigBuilder.fittedMouthSourceMesh(oldLayer, oldGeometry, config, atlas, cache)
        val raw = ScaffoldMeshContext.capture(listOf(ScaffoldMeshContext.Material(id, layerId,
            ScaffoldMesh.RawMesh(neutral.rigPositions, neutral.mesh.uvs, neutral.mesh.indices), ScaffoldMesh.Geometry.Zero)))
            .resources.getValue("source_meshes")
        val texture = LayerTexture.packed(layer, atlas.placementByLayerId.getValue(layerId), atlas)
        val placement = requireNotNull(texture.placement)
        fun projected(model: PuppetModel): PuppetModel = if (headTurn) model else BindingProjection.retain(model,
            model.parameters.mapTo(hashSetOf()) { it.id } - setOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y,
                StandardParameters.ANGLE_Z))
        val input = PuppetIr.toIr(projected(built.puppet))
        val headFrames = if (headTurn) buildJsonObject {
            assertNotNull(after.head)
            val headIds = ScaffoldFramesContext.build(RigBuilder.scaffoldFrameInputs(analysis, config, cache))
                .mapTo(hashSetOf()) { it.id }
            val ids = ModifierEvaluationInput(input).parentsOfMesh(id).mapTo(linkedSetOf()) {
                org.umamo.runtime.model.DeformerId(it.id)
            }.filter { it in headIds }.toSet()
            assertTrue(ids.any { it.raw.startsWith("DeformMouthShape") })
            fun phase(geometry: RigBuilder.RigContext, settings: PipelineConfig, layer: ClassifiedLayer): JsonObject {
                val structure = RigBuilder.scaffoldSourceInputs(analysis, settings, cache)
                val scaffold = ModifierAlgorithmContext(buildJsonObject {
                    put("schema", "scaffold/5")
                    put("frames", structure.frames)
                })
                val face = geometry.faceRig
                val base = LegacyFaceInputs.capture(scaffold, face.centerY, face.radiusX, face.radiusY,
                    face.eyeLineY, settings.rigTuning.legacyFaceTuning(), settings.headTurnStrength,
                    listOf("X", "Y", "Z"), "Face")
                val turn = RigDepthTurnSource.capture(geometry, settings, mapOf(id to layer),
                    mapOf(id to geometry.parentAndFrame(layer, settings).first),
                    ids.associateWith { requireNotNull(geometry.resolveFrame(it, settings)) }).copy(layers = emptyMap(), mouth = null)
                return buildJsonObject {
                    put("base", base.values)
                    put("turn", turn.encode().values)
                    val body = LegacyBodyInputs.capture(scaffold, structure.torso, settings.rigTuning.bodyMotion(),
                        emptyMap(), emptyMap()).values
                    put("body", JsonObject(body.filterKeys { it == "torso" || it == "tuning" } +
                        ("strength" to JsonPrimitive(settings.bodyStrength))))
                }
            }
            put("before", phase(oldGeometry, config, oldLayer))
            put("after", phase(newGeometry, enabled, newLayer))
            put("aliases", JsonObject(ids.associate { it.raw to JsonPrimitive(it.raw) }))
        } else null
        val capture = MouthOutlineV3Algorithm.capture(outline, before, after, raw,
            mapOf(id to MouthOutlineV3Algorithm.Texture(placement.pageIndex, placement.positionX, placement.positionY,
                placement.scaleX, placement.scaleY, placement.rotationDegrees, texture.pageWidth, texture.pageHeight,
                texture.offsetOriginX, texture.offsetOriginY, texture.space.scaleX, texture.space.scaleY)), headFrames)
            .withUpstream(listOf(ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 1, listOf(target), "mouth")) +
                listOfNotNull(head?.let { ModifierUpstreamCapability("face.depth-turn", "face.depth-turn", 1, listOf(target), it) }))
        val instance = RigModifierInstance("outline", "mouth.outline", 3, listOf(target), config = JsonObject(emptyMap()))
        val algorithm = MouthOutlineV3Algorithm()
        if (headTurn) {
            assertFailsWith<IllegalArgumentException> {
                algorithm.changes(input, instance, ModifierAlgorithmContext(
                    JsonObject(capture.values + ("head" to JsonNull)), capture.resources, capture.upstream)) {}
            }
        }
        val output = algorithm.changes(input, instance, capture) {}
        assertEquals(headTurn, output.deformers.updated.isNotEmpty())
        fun attach(rig: io.github.psd2live.format.model.RigIR, rasters: List<ModifierRasterOutput>, checkpoint: () -> Unit) =
            RigModifierRasterAssets.attach(rig, rasters, enabled, checkpoint)
        val result = output.applyWithResources(input, {}, ::attach)
        assertEquals(2, output.rasters.size)
        val prepared = MouthLipLayers.prepare(analysis, enabled, cache)
        val lips = prepared.layers.filter { it.source is MouthLipLayer }
        val packed = RigModifierRasterAssets.append(atlas, lips, enabled)
        val native = projected(RigBuilder.build(prepared, packed, enabled, cache).puppet)
        val evaluated = PuppetIr.toPuppet(result)
        fun compareMesh(old: String, new: String) {
            fun world(model: PuppetModel, id: String, pose: Map<org.umamo.runtime.model.ParameterId, Float>): FloatArray {
                val drawable = DrawableId(id)
                val local = requireNotNull(drawableLocalPosed(model, pose, drawable))
                return requireNotNull(drawableSpaceMapping(model, pose, drawable)).localToWorld(local)
            }
            val max = buildList {
                for (angle in if (headTurn) listOf(-25f, 0f, 25f) else listOf(0f))
                    for (form in listOf(-1f, 0f, 1f)) for (open in listOf(0f, .5f, 1f)) {
                        val pose = mapOf(StandardParameters.ANGLE_X to angle, StandardParameters.ANGLE_Y to angle / 2,
                            StandardParameters.MOUTH_FORM to form, StandardParameters.MOUTH_OPEN to open)
                        val expected = world(native, old, pose)
                        val actual = world(evaluated, new, pose)
                        assertEquals(expected.size, actual.size)
                        add(expected.indices.maxOf { abs(expected[it] - actual[it]) })
                    }
            }.max()
            assertTrue(max < .001f, "World coordinates differ by $max px for $old")
        }
        compareMesh(id, id)
        for ((side, ribbon) in output.rasters.withIndex()) {
            val nativeId = "${id}_lip_$side"
            compareMesh(nativeId, ribbon.mesh.id)
            val nativePath = PuppetIr.toIr(native).authoring.paths.single { it.mesh == nativeId }
            val path = result.authoring.paths.single { it.mesh == ribbon.mesh.id }
            assertEquals(nativePath.copy(id = instance.outputId("mouth-path:$id:$side:${nativePath.id}"),
                mesh = ribbon.mesh.id), path)
            assertEquals(instance.outputId("mouth-lip:$id:$side"), ribbon.mesh.id)
            assertEquals(instance.outputId("mouth-art:$id:$side"), ribbon.layer.key)
            assertContentEquals(lips.single { (it.source as MouthLipLayer).side == side }.source.raster.rgba, ribbon.pixels.read())
        }

        System.getProperty("psd2live.mouthOutlineV3Package")?.let { directory ->
            val root = Files.createTempDirectory("fitted-outline-package-")
            val preferences = Preferences.userRoot().node("psd2live-test/outline-v3/${UUID.randomUUID()}")
            val store = ModifierPluginStore(root, preferences)
            val entry = store.install(directory)
            try {
                val declared = entry.manifest.algorithms.single()
                val external = instance.copy(type = declared.type, plugin = entry.reference)
                assertEquals(result, ModifierPluginWorkers.evaluate(entry, declared, input, external, {}, store,
                    context = capture, resolveRasters = ::attach))
            } finally {
                ModifierPluginWorkers.stop(entry.reference)
                preferences.removeNode()
                ModifierPluginStore.deleteTree(root)
            }
        }
    }
}
