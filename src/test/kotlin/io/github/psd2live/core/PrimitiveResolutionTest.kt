package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.islandLayer
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import kotlin.math.abs
import kotlin.test.*

/**
 * Rule A of version 2 art primitives: the base generates from the resolved layer set - parts on their pinned meshes,
 * superseded layers only as passengers in their slots - and parks the parts for their records. The records here are
 * written by hand rather than by [LegacyArtPrimitiveV2.encode], which encodes a part from an authored
 * partition of the superseded drawable; they take the flat mesh form, `positions` holding rest canvas positions.
 */
class PrimitiveResolutionTest {
	private val width = 200
	private val height = 420

	private fun layer(id: String, order: Int, vararg islands: IntArray) = islandLayer(id, order, width, height, *islands)

	private val legL = intArrayOf(100, 240, 140, 400)
	private val legR = intArrayOf(60, 240, 80, 400)
	private val whiteL = intArrayOf(108, 62, 128, 74)
	private val whiteR = intArrayOf(72, 62, 92, 74)

	private val original = listOf(
		layer("face", 4, intArrayOf(60, 30, 140, 110)),
		layer("white", 5, whiteR, whiteL),
		layer("irisL", 6, intArrayOf(113, 63, 123, 73)),
		layer("irisR", 7, intArrayOf(77, 63, 87, 73)),
		layer("top", 3, intArrayOf(55, 115, 145, 200)),
		layer("skirt", 1, intArrayOf(58, 195, 142, 245)),
		layer("legs", 2, legR, legL),
	)

	private val overrides = mapOf(
		"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
		"white" to LayerClassificationOverride(tag = SemanticTag.EYEWHITE),
		"irisL" to LayerClassificationOverride(SemanticTag.IRIDES, Side.LEFT),
		"irisR" to LayerClassificationOverride(SemanticTag.IRIDES, Side.RIGHT),
		"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
		"skirt" to LayerClassificationOverride(tag = SemanticTag.BOTTOMWEAR),
		"legs" to LayerClassificationOverride(tag = SemanticTag.LEGWEAR),
	)

	/** Split parts: id, layer, island, classification. */
	private class Split(val id: String, val layer: String, val box: IntArray, val classification: LayerClassificationOverride)

	private val legParts = listOf(
		Split("PartLegL", "legsL", legL, LayerClassificationOverride(SemanticTag.LEGWEAR, Side.LEFT)),
		Split("PartLegR", "legsR", legR, LayerClassificationOverride(SemanticTag.LEGWEAR, Side.RIGHT)),
	)
	private val whiteParts = listOf(
		Split("PartWhiteL", "whiteL", whiteL, LayerClassificationOverride(SemanticTag.EYEWHITE, Side.LEFT)),
		Split("PartWhiteR", "whiteR", whiteR, LayerClassificationOverride(SemanticTag.EYEWHITE, Side.RIGHT)),
	)

	private fun floats(vararg values: Number) = JsonArray(values.map { JsonPrimitive(it.toFloat()) })

	private fun record(superseded: String, layer: String, parts: List<Split>) = buildJsonObject {
		put("op", ArtPrimitiveV2.OP); put("v", ArtPrimitiveV2.VERSION_V2); put("origin", "split")
		put("texture_source_id", PuppetSourceAtlas.SOURCE_ID_RAW)
		putJsonArray("supersedes") { add(superseded) }; putJsonArray("supersedes_layers") { add(layer) }
		putJsonObject("replace") { put(superseded, JsonArray(parts.map { JsonPrimitive(it.id) })) }
		put("primitives", JsonArray(parts.map { part ->
			val (l, t, r, b) = part.box.toList()
			buildJsonObject {
				put("id", part.id); put("layer_id", part.layer); put("source_id", PuppetSourceAtlas.SOURCE_ID_RAW); put("name", part.layer)
				put("source_bounds", floats(l, t, r, b)); put("neutral_bounds", floats(l, t, r, b))
				put("classification", ArtPrimitiveV2.encodeClassification(part.classification)); put("parent", JsonNull)
				put("positions", floats(l, t, r, t, l, b, r, b)); put("triangles", JsonArray(listOf(0, 1, 2, 1, 3, 2).map(::JsonPrimitive)))
				put("canvas_uvs", floats(l, t, r, t, l, b, r, b))
				put("fixed_topology", false); put("frozen_axes", JsonArray(emptyList())); put("authored", JsonObject(emptyMap()))
			}
		}))
		putJsonObject("glues") { put("replaced", JsonArray(emptyList())); put("appended", JsonArray(emptyList())) }
	}

	private val baseConfig = PipelineConfig(atlasSize = 1024, generatePhysics = false, exportMoc3 = false, layerOverrides = overrides)

	private class Base(val config: PipelineConfig, val analyses: RigGenerationSource.Analyses, val rig: BuiltRig, val cache: PreviewMeshCache)

	private fun base(current: List<SourceLayer>, config: PipelineConfig): Base {
		val cache = PreviewMeshCache()
		val source = WorkspaceSourceArt(width, height, current, emptyList())
		val analyses = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
		val atlas = AtlasLayout.pack(analyses.geometry.layers, config)
		return Base(config, analyses, RigBuilder.build(analyses.geometry, atlas, config, cache), cache)
	}

	private val plain by lazy { base(original, baseConfig) }

	private fun drawableOf(layer: String) = DrawableId(plain.rig.layerIdByDrawableId.entries.single { it.value == layer }.key)

	/** The document after splitting the legs and the eye whites per side, with version 2 records. */
	private fun split(skeleton: SkeletonSpec? = null): Base {
		val parts = legParts + whiteParts
		val current = original.filterNot { it.id.raw == "legs" || it.id.raw == "white" } + parts.map { part ->
			val order = original.single { it.id.raw == if (part.layer.startsWith("legs")) "legs" else "white" }.order
			layer(part.layer, order, part.box)
		}
		val journal = listOf(record(drawableOf("legs").raw, "legs", legParts), record(drawableOf("white").raw, "white", whiteParts))
		val config = baseConfig.copy(generationSource = WorkspaceSourceArt(width, height, original, emptyList()),
			layerOverrides = overrides + parts.associate { it.layer to it.classification },
			rigEdits = RigEditOverlay.Empty.copy(authoringJournal = journal, skeleton = skeleton))
		return base(current, config)
	}

	@Test fun documentsWithoutVersion2RecordsResolveNothing() {
		assertSame(PrimitiveResolution.Inactive, PrimitiveResolution.of(RigEditOverlay.Empty))
		assertSame(PrimitiveSkins.None, plain.rig.primitiveSkins)
		assertSame(plain.rig.puppet, plain.rig.resolvedPuppet())
		// The same inputs build the same rig.
		assertEquals(ContentHash.of(io.github.psd2live.targets.cubism.PuppetIr.toIr(plain.rig.puppet)),
			ContentHash.of(io.github.psd2live.targets.cubism.PuppetIr.toIr(base(original, baseConfig).rig.puppet)))
	}

	@Test fun aggregatesReadTheResolvedSetAndPassengersNeverFeedThem() {
		val split = split()
		val context = RigBuilder.rigContext(split.analyses.geometry, split.config, split.cache)
		val ids = context.analysis.layers.map { it.source.id.raw }.toSet()
		assertTrue(ids.containsAll(listOf("legsL", "legsR", "whiteL", "whiteR")), "$ids")
		assertTrue("legs" !in ids && "white" !in ids, "$ids")
		assertEquals(setOf("whiteL", "whiteR"), context.eyeWhiteLayers.map { it.source.id.raw }.toSet())
		// The legs stand per side on the parts: the right leg on its own part, not a quarter into the joint layer.
		val legs = context.stance.legs.associateBy { it.side }
		assertEquals(setOf(Side.LEFT, Side.RIGHT), legs.keys)
		assertEquals(70.0, legs.getValue(Side.RIGHT).ankleX, 0.5)
		assertEquals(120.0, legs.getValue(Side.LEFT).ankleX, 0.5)
		val joint = RigBuilder.rigContext(plain.analyses.geometry, plain.config, plain.cache).stance.legs.associateBy { it.side }
		assertTrue(abs(joint.getValue(Side.RIGHT).ankleX - 70.0) > 5.0, "the unsplit legs stand elsewhere")
		// The visible analysis is the resolved set.
		val visible = ArtPrimitiveJournal.visibleAnalysis(split.analyses.textures, split.config.rigEdits).layers
			.filter { it.source !is MouthLipLayer }.map { it.source.id.raw }.toSet()
		assertEquals(ids, visible)
	}

	@Test fun partsAreGeneratedAndParkedWhilePassengersKeepTheirSlots() {
		val split = split()
		val rig = split.rig
		val skins = rig.primitiveSkins
		val puppet = rig.puppet
		val ghosts = setOf(drawableOf("legs"), drawableOf("white"))
		val parts = (legParts + whiteParts).map { DrawableId(it.id) }
		// The base keeps the passengers under the ids of a base without the split, and holds no part.
		assertEquals(plain.rig.puppet.drawables.map { it.id }, puppet.drawables.map { it.id })
		assertTrue(puppet.drawables.none { it.id in parts })
		assertEquals(ghosts, skins.stubs)
		assertEquals(parts.toSet(), skins.parts.map { it.id }.toSet())
		for (part in skins.parts) {
			assertEquals(skins.partLayers.getValue(part.id), part.atlasTileId!!.raw.substringAfter('/'))
			assertTrue(part.texturePage >= 0)
			assertTrue(skins.neutralBounds.containsKey(part.id))
		}
		// Eye-white parts carry the eye closure of their own side, owned by the mesh stage.
		val white = skins.drawables.getValue(DrawableId("PartWhiteL"))
		assertEquals(listOf(StandardParameters.EYE_L_OPEN), white.geometryGrid!!.axes.map { it.parameterId })
		assertEquals(mapOf(StandardParameters.EYE_L_OPEN to DocumentGenerators.meshId("whiteL")), skins.ownership.getValue(white.id))
		assertEquals(PartId("PartFace"), skins.partSlots.getValue(white.id))
		// The iris is masked by the part of its side; the base names the passenger in its place.
		val irisL = drawableOf("irisL")
		assertEquals(listOf(drawableOf("white")), puppet.drawables.single { it.id == irisL }.maskedBy)
		assertEquals(listOf(DrawableId("PartWhiteL")), skins.generatedMasks.getValue(irisL))
		// The graph names the parts' generated keyforms with their owners.
		val graph = DocumentGenerators.graph(split.config.rigEdits, primitives = skins)
		assertEquals(DocumentGenerators.RIG_MESHES,
			DocumentGenerators.owner(graph, DocumentGenerators.keyform("mesh", "PartWhiteL", StandardParameters.EYE_L_OPEN.raw))?.id)
		assertTrue(graph.order.single { it.id == DocumentGenerators.SKELETON }.reads.contains(DocumentGenerators.PRIMITIVES))
	}

	@Test fun theResolvedPuppetShowsThePartsInPlaceOfTheStubs() {
		val rig = split().rig
		val resolved = rig.resolvedPuppet()
		val ids = resolved.drawables.map { it.id }.toSet()
		assertTrue(rig.primitiveSkins.stubs.none { it in ids })
		assertTrue(ids.containsAll((legParts + whiteParts).map { DrawableId(it.id) }))
		assertEquals(listOf(DrawableId("PartWhiteL")), resolved.drawables.single { it.id == drawableOf("irisL") }.maskedBy)
		val face = resolved.parts.single { it.id == PartId("PartFace") }.children
		assertTrue(OrgChild.Drawable(DrawableId("PartWhiteL")) in face && OrgChild.Drawable(drawableOf("white")) !in face)
		// Texture coordinates bound through the atlas, like every other drawable's.
		val part = resolved.drawables.single { it.id == DrawableId("PartLegL") }
		assertTrue(part.mesh!!.uvs.all { it in -0.01f..1.01f }, part.mesh!!.uvs.toList().toString())
	}

	@Test fun theSkeletonSkinsThePartsAndNeverThePassengers() {
		val first = split()
		val analysis = first.analyses.geometry.copy(layers = PrimitiveResolution.of(first.config.rigEdits).aggregates(first.analyses.geometry.layers))
		val resolved = first.rig.resolvedPuppet()
		val layers = first.rig.layerIdByDrawableId + first.rig.primitiveSkins.partLayers.mapKeys { it.key.raw }
		val spec = SkeletonAutoBuilder.build(analysis, resolved, layers, SkeletonAutoBuilder.canvasVertices(resolved)).copy(enabled = true)
		val bound = spec.bones.flatMap { it.drawableIds }.toSet()
		assertTrue("PartLegL" in bound || "PartLegR" in bound, "$bound")
		// A bone left on a passenger binds nothing.
		val withGhost = spec.copy(bones = spec.bones.map { bone ->
			if ("PartLegL" in bone.drawableIds) bone.copy(drawableIds = bone.drawableIds + drawableOf("legs").raw) else bone
		})
		val rig = split(withGhost).rig
		val skins = rig.primitiveSkins
		val legL = skins.drawables.getValue(DrawableId("PartLegL"))
		// Skinned: it hangs under a bone, or its keyforms gained the bones' axes (owned by the skeleton).
		val bones = rig.puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Rotation>().map { it.id.raw }.toSet()
		val parent = legL.parentDeformerId?.raw
		assertTrue(parent in bones || parent?.removeSuffix("Stance") in bones || skins.ownership.getValue(legL.id).values.any { it == DocumentGenerators.SKELETON },
			"leg part is not skinned: parent $parent, axes ${skins.ownership.getValue(legL.id)}, bound $bound, bones $bones")
		val ghost = rig.puppet.drawables.single { it.id == drawableOf("legs") }
		assertNull(ghost.geometryGrid?.axes?.firstOrNull { axis -> spec.bones.any { it.parameterId == axis.parameterId.raw } })
	}
}
