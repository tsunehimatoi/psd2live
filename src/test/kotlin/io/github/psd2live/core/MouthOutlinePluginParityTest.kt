package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.MouthOutlineAlgorithm
import io.github.psd2live.algorithms.binding.MouthOutlineLegacyCandidate
import io.github.psd2live.algorithms.binding.MouthOutlineInputs
import io.github.psd2live.algorithms.binding.MouthOutlineOwner
import io.github.psd2live.algorithms.binding.MouthContour
import org.umamo.render.eval.drawableLocalPosed
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import kotlin.test.*

class MouthOutlinePluginParityTest {
    @Test fun candidateRecreatesLegacyRibbonPixelsTopologyKeysAndPath() = compare(fitted = false)

    @Test fun candidateRecreatesFittedRibbonFromDeclaredMouthMeasurements() = compare(fitted = true)

    private fun compare(fitted: Boolean) {
        val document = Characters.mouthed()
        val recipe = if (!fitted) RigRecipe.Legacy else RigRecipe(true,
            bases = mapOf("mouth" to BaseChoice("mouth/fitted")),
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) })
        val config = document.rawConfig().copy(generateDeformers = true, meshOnly = false, deferRig = false,
            mouthOutlineEnabled = false, mouthThickness = 1.5f, mouthColor = null, recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val source = RigBuilder.build(analysis, atlas, config, cache)
        val layer = analysis.layers.single { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) && it.semantic.role == null }
        val layerId = layer.source.id.raw
        val id = source.layerIdByDrawableId.entries.single { it.value == layerId }.key
        val target = ModifierTarget("mesh", id)
        val provider = "fitted".takeIf { fitted }
        val capture = RigMouthOutlineSource.capture(analysis, config,
            mapOf(id to RigMouthOutlineSource.Owner(layerId, id, fittedProvider = provider)), cache = cache)
        val instance = RigModifierInstance("outline", "mouth.outline", 1, listOf(target),
            config = JsonObject(emptyMap()))
        val input = PuppetIr.toIr(source.puppet)
        val candidate = MouthOutlineAlgorithm()
        val context = capture.encode()
        if (fitted) assertFailsWith<IllegalArgumentException> { candidate.changes(input, instance, context) {} }
        val upstream = provider?.let { listOf(ModifierUpstreamCapability("mouth/fitted", "mouth/fitted", 1, listOf(target), it)) }.orEmpty()
        val captured = context.withUpstream(upstream)
        val observation = ModifierEvaluationInput(input, captured)
        val changes = candidate.changes(observation, instance) {}
        assertEquals(2, changes.rasters.size)
        val encodedTarget = captured.values.getValue("targets").jsonObject.getValue(id).jsonObject
        val digest = encodedTarget.getValue("topology").jsonPrimitive.content
        assertTrue("sourcePositions" !in encodedTarget && "sourceTriangles" !in encodedTarget)
        assertFailsWith<IllegalArgumentException> {
            MouthOutlineInputs.decode(ModifierAlgorithmContext(captured.values, captured.resources - digest, upstream))
        }
        val topology = capture.topology(capture.targets.getValue(id)) {}
        val legacyTarget = JsonObject((encodedTarget - "topology") + mapOf(
            "sourcePositions" to JsonArray(topology.positions.map(::JsonPrimitive)),
            "sourceTriangles" to JsonArray(topology.triangles.map(::JsonPrimitive))))
        val legacy = ModifierAlgorithmContext(JsonObject(captured.values + mapOf("schema" to JsonPrimitive("mouth-outline/1"),
            "targets" to JsonObject(mapOf(id to legacyTarget)))), captured.resources - digest, upstream)
        val legacyRasters = candidate.changes(input, instance, legacy) {}.rasters
        assertEquals(changes.rasters.size, legacyRasters.size)
        changes.rasters.zip(legacyRasters).forEach { (current, old) ->
            assertEquals(current.owner, old.owner)
            assertEquals(current.mesh, old.mesh)
            assertEquals(current.layer, old.layer)
            assertEquals(current.paths, old.paths)
            assertEquals(current.uvSpace, old.uvSpace)
            assertContentEquals(current.pixels.read(), old.pixels.read())
        }
        val reads = observation.reads()
        assertEquals(setOf(ModifierReadKey("mesh", id)), reads.keys.filter { it.section == "mesh" }.toSet())
        assertTrue(reads.matches(input.copy(meshes = input.meshes.map { if (it.id == id) it else it.copy(opacity = .5f) }), captured))
        assertFalse(reads.matches(input.copy(meshes = input.meshes.map { if (it.id == id) it.copy(opacity = .5f) else it }), captured))

        val legacyConfig = config.copy(mouthOutlineEnabled = true)
        val prepared = MouthLipLayers.prepare(analysis, legacyConfig, cache)
        val lipLayers = prepared.layers.filter { it.source is MouthLipLayer }
        val packed = RigModifierRasterAssets.append(atlas, lipLayers, legacyConfig)
        val native = RigBuilder.build(prepared, packed, legacyConfig, cache).puppet
        val capturedTarget = capture.targets.getValue(id)
        val owner = native.drawables.single { it.id.raw == id }
        val ownerMesh = requireNotNull(owner.mesh)
        val contour = requireNotNull(MouthContour.ownerContour(topology.positions, topology.triangles))
        val capturedOwner = MouthOutlineOwner.mesh(contour, capturedTarget.frame,
            layer.source.bounds.left.toFloat(), layer.source.bounds.top.toFloat(),
            layer.source.raster.width.toFloat(), layer.source.raster.height.toFloat(), capturedTarget::toCanvas)
        assertContentEquals(if (owner.parentDeformerId == null) contour.positions else capturedOwner.positions, ownerMesh.positions)
        assertContentEquals(capturedOwner.indices, ownerMesh.indices)
        val placement = packed.placementByLayerId.getValue(layerId)
        assertContentEquals(LayerTexture.packed(layer, placement, packed).bind(capturedOwner.uvs), ownerMesh.uvs)
        val sourceId = DrawableId(layerId)
        val paths = MouthOutlineOwner.paths(sourceId, capturedOwner)
        assertEquals(2, paths.size)
        // The builder measures the owner's paths under the source layer id, where no mesh lives, and keeps only paths on
        // live meshes; the candidate attaches the same paths to the owner mesh itself.
        assertTrue(native.deformPaths.none { it.drawableId == sourceId })
        val ownerPaths = paths.map { it.copy(drawableId = DrawableId(id)) }
        val expected = PuppetIr.toIr(native)
        if (!fitted) {
            val neutralContext = RigBuilder.rigContext(analysis, config, cache)
            val framed = neutralContext.analysis.layers.single { it.source.id.raw == layerId }
            val neutral = RigBuilder.fittedMouthSourceMesh(framed, neutralContext, config, atlas, cache)
            val texture = LayerTexture.packed(layer, atlas.placementByLayerId.getValue(layerId), atlas)
            fun Bounds.observation() = ModifierSourceBounds(left, top, right, bottom)
            val (parent, frame) = neutralContext.parentAndFrame(framed, config)
            val candidateOwners = mapOf(id to MouthOutlineLegacyCandidate.NeutralOwner(
                parent, neutral.mesh, neutral.rigPositions, frame.observation(), neutralContext.rigLayer(framed).bounds.observation(),
                MouthOutlineLegacyCandidate.TextureFrame(texture.placement, texture.pageWidth, texture.pageHeight,
                    texture.offsetOriginX, texture.offsetOriginY, texture.space.scaleX, texture.space.scaleY)))
            val ownerCandidate = MouthOutlineLegacyCandidate()
            fun applyOwner(base: io.github.psd2live.format.model.RigIR, retainParent: Boolean = false): io.github.psd2live.format.model.RigIR =
                ownerCandidate.changes(ModifierEvaluationInput(base, captured), instance, candidateOwners, retainParentFrame = retainParent)
                    .applyWithResources(base, {}, { rig, outputs, checkpoint ->
                        RigModifierRasterAssets.attach(rig, outputs, legacyConfig, checkpoint)
                    })
            val complete = applyOwner(input)
            val rebasedResult = applyOwner(input, retainParent = true)
            val oldOwner = expected.meshes.single { it.id == id }
            val newOwner = complete.meshes.single { it.id == id }
            assertEquals(oldOwner.geometry, newOwner.geometry)
            assertEquals(oldOwner.offsets, newOwner.offsets)
            val nativePaths = PuppetIr.toIr(native.copy(deformPaths = ownerPaths)).authoring.paths
            assertEquals(nativePaths, complete.authoring.paths.filter { it.mesh == id })

            assertNotEquals(frame.observation(), capturedTarget.frame)
            val rebased = PuppetIr.toPuppet(rebasedResult)
            for (form in listOf(-1f, 0f, 1f)) for (open in listOf(0f, .5f, 1f)) {
                val pose = mapOf(StandardParameters.MOUTH_FORM to form, StandardParameters.MOUTH_OPEN to open)
                fun world(model: PuppetModel, mesh: String): FloatArray {
                    val drawable = DrawableId(mesh)
                    val local = requireNotNull(drawableLocalPosed(model, pose, drawable))
                    return requireNotNull(drawableSpaceMapping(model, pose, drawable)).localToWorld(local)
                }
                fun sameWorld(old: String, new: String) {
                    val a = world(native, old)
                    val b = world(rebased, new)
                    assertEquals(a.size, b.size)
                    assertTrue(a.indices.all { kotlin.math.abs(a[it] - b[it]) < .001f },
                        "Rebased world coordinates diverged for $old at $pose")
                }
                sameWorld(id, id)
                for (side in 0..1) sameWorld("${id}_lip_$side", instance.outputId("mouth-lip:$id:$side"))
            }

            val before = input.meshes.single { it.id == id }
            val geometry = requireNotNull(before.geometry)
            val positions = geometry.positions.toArray()
            positions[0] += .02f
            val edited = before.copy(geometry = geometry.copy(positions = io.github.psd2live.format.model.Floats.wrap(positions)))
            val result = applyOwner(input.copy(meshes = input.meshes.map { if (it.id == id) edited else it }))
            val kept = result.meshes.single { it.id == id }
            assertEquals(edited.geometry, kept.geometry)
            assertNotEquals(edited.offsets, kept.offsets)
            assertEquals(2, result.authoring.paths.count { it.mesh == id })
        }
        val attached = changes.applyWithResources(input, {}, { rig, outputs, checkpoint ->
            RigModifierRasterAssets.attach(rig, outputs, legacyConfig, checkpoint)
        })
        for ((side, output) in changes.rasters.withIndex()) {
            val legacyId = "${id}_lip_$side"
            val old = expected.meshes.single { it.id == legacyId }
            val new = attached.meshes.single { it.id == output.mesh.id }
            val art = lipLayers.single { (it.source as MouthLipLayer).side == side }.source
            assertContentEquals(art.raster.rgba, output.pixels.read())
            assertEquals(listOf(art.bounds.left, art.bounds.top, art.bounds.width, art.bounds.height),
                listOf(output.layer.left, output.layer.top, output.layer.width, output.layer.height))
            assertEquals(old.geometry, new.geometry)
            assertEquals(old.offsets, new.offsets)
            val oldPath = expected.authoring.paths.single { it.mesh == legacyId }
            val newPath = attached.authoring.paths.single { it.mesh == new.id }
            assertEquals(oldPath.copy(id = instance.outputId("mouth-path:$id:$side:${oldPath.id}"), mesh = new.id), newPath)
            assertEquals(instance.outputId("mouth-lip:$id:$side"), new.id)
            assertEquals(instance.outputId("mouth-art:$id:$side"), output.layer.key)
        }
    }
}
