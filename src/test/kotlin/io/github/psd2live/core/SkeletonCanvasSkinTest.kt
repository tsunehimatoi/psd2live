package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot
import kotlin.test.*

/**
 * Bones skinning meshes the journal hangs under deformers of its own: a synthetic figure whose left arm is a sleeve
 * over the upper arm and the elbow and a hand overlapping the sleeve's end, both bound to an arm skeleton.
 */
class SkeletonCanvasSkinTest {
	private val width = 420
	private val height = 420

	private fun layer(id: String, order: Int, box: IntArray): WorkspaceSourceLayer {
		val rgba = ByteArray(width * height * 4)
		for (y in box[1] until box[3]) for (x in box[0] until box[2]) {
			val offset = (y * width + x) * 4
			rgba[offset] = 120; rgba[offset + 1] = 90; rgba[offset + 2] = 60; rgba[offset + 3] = 255.toByte()
		}
		return WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order, LayerBounds(0, 0, width, height), 1f, false,
			LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
	}

	private val layers = listOf(
		layer("face", 5, intArrayOf(170, 30, 250, 110)),
		layer("top", 3, intArrayOf(160, 115, 260, 240)),
		layer("skirt", 1, intArrayOf(165, 235, 255, 290)),
		layer("legs", 2, intArrayOf(175, 285, 245, 410)),
		layer("sleeve", 4, intArrayOf(258, 120, 340, 148)),
		layer("hand", 6, intArrayOf(330, 118, 400, 150)),
	)

	private val overrides = mapOf(
		"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
		"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
		"skirt" to LayerClassificationOverride(tag = SemanticTag.BOTTOMWEAR),
		"legs" to LayerClassificationOverride(tag = SemanticTag.LEGWEAR),
		"sleeve" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
		"hand" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT),
	)

	private val source = WorkspaceSourceArt(width, height, layers, emptyList())
	private val baseConfig = PipelineConfig(atlasSize = 1024, generatePhysics = false, exportMoc3 = false, layerOverrides = overrides)
	private val plain by lazy { PSD2LivePipeline().buildPreview(source, baseConfig) }
	private fun drawableOf(layer: String) = plain.rig.layerIdByDrawableId.entries.single { it.value == layer }.key
	private val sleeve by lazy { drawableOf("sleeve") }
	private val hand by lazy { drawableOf("hand") }

	/**
	 * An arm drawn by hand: the upper arm along the sleeve, the forearm through the hand. Without [withHand] the hand
	 * binds to no bone, so nothing welds the sleeve to it.
	 */
	private fun armSpec(withHand: Boolean = true): SkeletonSpec {
		val upper = SkeletonBone("armL", "Upper arm", null, BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, 335f, 134f, listOf(sleeve))
		val fore = SkeletonBone("foreL", "Forearm", "armL", BoneRole.FOREARM, Side.LEFT, 335f, 134f, 395f, 134f, if (withHand) listOf(hand) else emptyList())
		return SkeletonSpec(bones = listOf(upper, fore))
	}

	private fun build(spec: SkeletonSpec, journal: List<JsonObject> = emptyList()) =
		PSD2LivePipeline().buildPreview(source, baseConfig.copy(rigEdits = RigEditOverlay(skeleton = spec, authoringJournal = journal)))

	private fun canvas(model: PuppetModel, values: Map<String, Float>, glues: Boolean = false): Map<DrawableId, FloatArray> =
		CpuDeformationEvaluator().evaluate(if (glues) model else model.copy(glues = emptyList()), values.mapKeys { ParameterId(it.key) }).worldPositions
			.mapValues { (_, world) -> FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }

	private fun bind(id: String, parent: String) = buildJsonObject {
		put("op", "structure"); putJsonArray("edits") {
			add(buildJsonObject { put("action", "bind"); put("kind", "mesh"); put("id", id); put("parent_id", parent); put("space", "local") })
		}
	}

	private fun warp(id: String, parent: String, mesh: String, rows: Int, columns: Int) =
		buildJsonObject { put("op", "warp"); put("warp", RigWarpEdit(id, id, parent, listOf(mesh), rows, columns, fitLocal = true).toJson()) }

	/** The mesh bound to the breath warp, then wrapped in a user warp, wrapped again in a second one. */
	private fun placing(mesh: String) = listOf(bind(mesh, "DeformBodyZBreath"),
		warp("WarpOuter", "DeformBodyZBreath", mesh, 12, 8), warp("WarpInner", "WarpOuter", mesh, 4, 4))

	/**
	 * The poses at the keys [mesh] is keyed on in [model]: every key of each axis alone, and the corners of every two
	 * axes, the other parameters at rest.
	 */
	private fun keys(model: PuppetModel, mesh: String): List<Map<String, Float>> {
		val axes = model.drawables.single { it.id.raw == mesh }.geometryGrid!!.axes
		val single = axes.flatMap { axis -> axis.keys.map { mapOf(axis.parameterId.raw to it) } }
		val pairs = axes.indices.flatMap { i -> (i + 1 until axes.size).flatMap { j ->
			listOf(axes[i].keys.first(), axes[i].keys.last()).flatMap { a -> listOf(axes[j].keys.first(), axes[j].keys.last()).map { b ->
				mapOf(axes[i].parameterId.raw to a, axes[j].parameterId.raw to b) } }
		} }
		return single + pairs
	}

	private fun maxDistance(a: FloatArray, b: FloatArray): Float {
		assertEquals(a.size, b.size)
		return (0 until a.size / 2).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }
	}

	/** The widest gap between the two sides of any glue pair of [model] touching [mesh], where the rig puts them without the glue. */
	private fun seams(model: PuppetModel, mesh: String, values: Map<String, Float>): Float {
		val at = canvas(model, values)
		val glues = model.glues.filter { it.meshA.raw == mesh || it.meshB.raw == mesh }
		assertTrue(glues.isNotEmpty(), "the sleeve is glued to the hand")
		return glues.maxOf { glue ->
			val a = at.getValue(glue.meshA); val b = at.getValue(glue.meshB)
			glue.pairs.maxOf { hypot(a[it.indexA * 2] - b[it.indexB * 2], a[it.indexA * 2 + 1] - b[it.indexB * 2 + 1]) }
		}
	}

	private fun hash(model: PuppetModel) = io.github.psd2live.format.compile.document.ContentHash.of(io.github.psd2live.targets.cubism.PuppetIr.toIr(model))

	@Test fun aMeshUnderTwoUserWarpsTurnsWithItsBonesAsTheSameMeshOnTheBone() {
		val spec = armSpec(withHand = false)
		val onBone = build(spec).rig.puppet
		val placed = build(spec, placing(sleeve)).rig.puppet
		val drawable = placed.drawables.single { it.id.raw == sleeve }
		assertEquals("WarpInner", drawable.parentDeformerId?.raw, "the journal's placement stands")
		assertEquals("DeformSkel_armL", onBone.drawables.single { it.id.raw == sleeve }.parentDeformerId?.raw)
		val axes = drawable.geometryGrid!!.axes.map { it.parameterId.raw }
		assertTrue("ParamArmLA" in axes && "ParamArmLB" in axes, "$axes")
		for (values in keys(placed, sleeve)) {
			val distance = maxDistance(canvas(onBone, values).getValue(DrawableId(sleeve)), canvas(placed, values).getValue(DrawableId(sleeve)))
			assertTrue(distance < 1.5f, "$values: the placed sleeve is $distance px off the skinned one")
		}
		// At rest the journal's placement leaves the sleeve where the art has it.
		assertTrue(maxDistance(canvas(onBone, emptyMap()).getValue(DrawableId(sleeve)), canvas(placed, emptyMap()).getValue(DrawableId(sleeve))) < 0.05f)
	}

	@Test fun gluedSeamsBetweenAWarpedMeshAndABoneMeshStayClosed() {
		val placed = build(armSpec(), placing(sleeve)).rig.puppet
		for (values in keys(placed, sleeve)) {
			val gap = seams(placed, sleeve, values)
			assertTrue(gap < 0.02f, "$values: seam open by $gap px")
		}
	}

	@Test fun bothSidesOfASeamPlacedByTheJournalMeetOnEveryKey() {
		val journal = placing(sleeve) + listOf(bind(hand, "DeformBodyZBreath"), warp("WarpHand", "DeformBodyZBreath", hand, 6, 6))
		val placed = build(armSpec(), journal).rig.puppet
		assertEquals("WarpHand", placed.drawables.single { it.id.raw == hand }.parentDeformerId?.raw)
		for (values in keys(placed, sleeve)) {
			val gap = seams(placed, sleeve, values)
			assertTrue(gap < 0.05f, "$values: seam open by $gap px")
		}
	}

	@Test fun theUsersKeyformsOnThePlacedMeshStillApply() {
		val spec = armSpec()
		val parameter = RigParameterEdit("ParamSleeveTest", "Sleeve test", 0f, 1f, 0f, created = true)
		val overlay = RigEditOverlay(skeleton = spec, parameterEdits = listOf(parameter), authoringJournal = placing(sleeve))
		val config = baseConfig.copy(rigEdits = overlay)
		val before = PSD2LivePipeline().buildPreview(source, config)
		// A user keyform on a parameter of their own, written against the authored rig as the canvas writes it.
		val authored = overlay.authored(before.baseRig)
		val axes = authored.drawables.single { it.id.raw == sleeve }.geometryGrid?.axes.orEmpty()
		val key = axes.associate { it.parameterId.raw to authored.parameters.single { p -> p.id == it.parameterId }.default } + ("ParamSleeveTest" to 1f)
		val shown = RigGeometryTools.geometry(authored, "mesh", sleeve, key).points
		val moved = FloatArray(shown.size) { if (it % 2 == 1) shown[it] + 0.02f else shown[it] }
		val edit = buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "mesh"); put("id", sleeve)
			put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) })); put("points", JsonArray(moved.map(::JsonPrimitive)))
		}
		val keyed = PSD2LivePipeline().buildPreview(source, config.copy(rigEdits = overlay.copy(authoringJournal = overlay.authoringJournal + edit))).rig.puppet
		for (bone in listOf(emptyMap(), mapOf("ParamArmLA" to 60f))) {
			val off = canvas(keyed, bone).getValue(DrawableId(sleeve))
			val on = canvas(keyed, bone + ("ParamSleeveTest" to 1f)).getValue(DrawableId(sleeve))
			val shift = maxDistance(off, on)
			assertTrue(shift > 0.5f, "$bone: the user's keyform moves the sleeve ($shift px)")
		}
		// The bones still turn it with the user's key applied.
		val turned = canvas(keyed, mapOf("ParamArmLA" to 60f, "ParamSleeveTest" to 1f)).getValue(DrawableId(sleeve))
		assertTrue(maxDistance(turned, canvas(keyed, mapOf("ParamSleeveTest" to 1f)).getValue(DrawableId(sleeve))) > 10f)

		// An edit of a generated bone keyform is recorded as an override of the skin and survives the rebuild.
		val model = before.rig.puppet
		val grid = model.drawables.single { it.id.raw == sleeve }.geometryGrid!!
		val boneKey = grid.axes.associate { axis -> axis.parameterId.raw to (if (axis.parameterId.raw == "ParamArmLA") axis.keys.last() else 0f) }
		val boneShown = RigGeometryTools.geometry(model, "mesh", sleeve, boneKey).points
		val nudged = boneShown.copyOf().also { it[1] += 0.03f }
		val command = buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "mesh"); put("id", sleeve)
			put("key", JsonObject(boneKey.mapValues { JsonPrimitive(it.value) })); put("points", JsonArray(nudged.map(::JsonPrimitive)))
		}
		val captured = GeneratedOverrides.capture(model, overlay, JsonArray(listOf(command))).single().jsonObject
		assertEquals(GeneratedOverrides.OP, captured["op"]?.jsonPrimitive?.content)
		assertEquals(DocumentGenerators.SKIN, captured["generator"]?.jsonPrimitive?.content)
		val overridden = PSD2LivePipeline().buildPreview(source, config.copy(rigEdits = overlay.copy(authoringJournal = overlay.authoringJournal + captured))).rig.puppet
		val values = boneKey.filterValues { it != 0f }
		val plainVertex = canvas(model, values).getValue(DrawableId(sleeve))
		val editedVertex = canvas(overridden, values).getValue(DrawableId(sleeve))
		assertTrue(hypot(plainVertex[0] - editedVertex[0], plainVertex[1] - editedVertex[1]) > 0.5f, "the override moves its vertex")
		assertTrue((1 until plainVertex.size / 2).all { hypot(plainVertex[it * 2] - editedVertex[it * 2], plainVertex[it * 2 + 1] - editedVertex[it * 2 + 1]) < 1e-3f })
	}

	@Test fun aUserCreatedSkeletonGoesTheSameWay() {
		val custom = SkeletonSpec().withCustomBone(262f, 134f, 335f, 134f).let { it.withCustomBone(335f, 134f, 395f, 134f, it.bones.single().id) }
		val ids = custom.bones.map { it.id }
		val alone = custom.withDrawableBound(sleeve, ids[0])
		val spec = alone.withDrawableBound(hand, ids[1])
		val first = custom.bones[0].parameterId
		val second = custom.bones[1].parameterId
		assertEquals(setOf(sleeve), SkeletonCanvasSkin.placed(RigEditOverlay(skeleton = spec, authoringJournal = placing(sleeve))))
		val onBone = build(alone).rig.puppet
		val single = build(alone, placing(sleeve)).rig.puppet
		assertEquals("WarpInner", single.drawables.single { it.id.raw == sleeve }.parentDeformerId?.raw)
		assertTrue(keys(single, sleeve).flatMap { it.keys }.containsAll(listOf(first, second)))
		for (values in keys(single, sleeve)) {
			val distance = maxDistance(canvas(onBone, values).getValue(DrawableId(sleeve)), canvas(single, values).getValue(DrawableId(sleeve)))
			assertTrue(distance < 1.5f, "$values: $distance px")
		}
		val placed = build(spec, placing(sleeve)).rig.puppet
		for (values in keys(placed, sleeve)) {
			val gap = seams(placed, sleeve, values)
			assertTrue(gap < 0.02f, "$values: seam open by $gap px")
		}
	}

	@Test fun anAutomaticSkeletonGoesTheSameWay() {
		val spec = SkeletonAutoBuilder.build(plain.analysis, plain.rig).copy(enabled = true)
		val bone = SkeletonRig.jointBones(spec).firstOrNull { sleeve in it.drawableIds }
		assertNotNull(bone, "the automatic skeleton binds the sleeve")
		val placed = build(spec, placing(sleeve)).rig.puppet
		assertEquals("WarpInner", placed.drawables.single { it.id.raw == sleeve }.parentDeformerId?.raw)
		val values = keys(placed, sleeve)
		assertTrue(values.any { it.containsKey(bone.parameterId) })
		val rest = canvas(placed, emptyMap()).getValue(DrawableId(sleeve))
		assertTrue(maxDistance(rest, canvas(placed, mapOf(bone.parameterId to 60f)).getValue(DrawableId(sleeve))) > 10f)
		if (placed.glues.any { it.meshA.raw == sleeve || it.meshB.raw == sleeve }) for (at in values) {
			val gap = seams(placed, sleeve, at)
			assertTrue(gap < 0.02f, "$at: seam open by $gap px")
		}
	}

	@Test fun recordsAddressingTheUnrefinedMeshKeepItsVertices() {
		val spec = armSpec(withHand = false)
		val refined = build(spec, placing(sleeve)).rig.puppet.drawables.single { it.id.raw == sleeve }.mesh!!.vertexCount
		val unbound = SkeletonSpec(bones = spec.bones.map { it.copy(drawableIds = emptyList()) })
		val count = build(unbound).rig.puppet.drawables.single { it.id.raw == sleeve }.mesh!!.vertexCount
		assertTrue(refined > count, "the skeleton refines the sleeve across the elbow")
		// A vertex group painted before the skeleton existed addresses the mesh as it was.
		val group = VertexGroupJournal.encode(org.umamo.runtime.model.VertexGroup("stiff", DrawableId(sleeve),
			org.umamo.runtime.model.VertexGroupKind.STIFFNESS, FloatArray(count) { 0.5f }))
		val placed = build(spec, placing(sleeve) + group).rig.puppet
		assertEquals(count, placed.drawables.single { it.id.raw == sleeve }.mesh!!.vertexCount)
		assertEquals(count, placed.vertexGroups.single { it.drawableId.raw == sleeve }.weights.size)
		assertTrue(maxDistance(canvas(placed, emptyMap()).getValue(DrawableId(sleeve)),
			canvas(placed, mapOf("ParamArmLA" to 60f)).getValue(DrawableId(sleeve))) > 10f, "the bones still turn it")
	}

	@Test fun replayAndReopenGiveTheSameRig() {
		val spec = armSpec()
		val journal = placing(sleeve)
		val first = hash(build(spec, journal).rig.puppet)
		// A cold rebuild: no bake or generator reuse left over.
		SkeletonRig.clearCache(); GeneratorReuse.clear()
		assertEquals(first, hash(build(spec, journal).rig.puppet))
		// Saved and read back: the skeleton and the journal as their stored text.
		val reopenedSpec = SkeletonSpec.fromJson(Json.parseToJsonElement(spec.toJson().toString()).jsonObject)
		val reopenedJournal = journal.map { Json.parseToJsonElement(it.toString()).jsonObject }
		SkeletonRig.clearCache(); GeneratorReuse.clear()
		assertEquals(first, hash(build(reopenedSpec, reopenedJournal).rig.puppet))
	}
}
