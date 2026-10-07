package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterRange
import org.umamo.format.art.LayerBounds
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.*

/**
 * Journal records written under a generator-owned deformer that a later generation setting drops - the arm hang
 * warp an enabled skeleton replaces with a bone. A hand-built rig: an arm split into two parts under the warp, one
 * of them split again; the same records then replay on a rig where the arm hangs under a rotation deformer.
 */
class VanishedParentReplayTest {
	private val source = ArtSourceId("art-0")
	private val layers = listOf("arm", "p1", "p2", "q1", "q2")
	private val param = ParameterId("ParamX")
	private val hang = DeformerId("Hang")
	private val bone = DeformerId("Bone")
	private val arm = DrawableId("Arm")
	private val evaluator = CpuDeformationEvaluator()

	/** Canvas quads of each part (two triangles each). */
	private val quads = mapOf(
		"P1" to floatArrayOf(20f, 20f, 40f, 20f, 20f, 60f, 40f, 60f),
		"P2" to floatArrayOf(40f, 20f, 60f, 20f, 40f, 60f, 60f, 60f),
		"Q1" to floatArrayOf(40f, 20f, 60f, 20f, 40f, 40f, 60f, 40f),
		"Q2" to floatArrayOf(40f, 40f, 60f, 40f, 40f, 60f, 60f, 60f),
	)
	private val triangles = intArrayOf(0, 1, 2, 1, 3, 2)

	private fun rig(deformer: Deformer, armParent: DeformerId): PuppetModel {
		val armMesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), floatArrayOf(20f, 20f, 60f, 20f, 20f, 60f, 60f, 60f), triangles)
		val drawable = Drawable(arm, "Arm", armParent, BlendMode.Normal, emptyList(), armMesh, null, atlasTileId = AtlasTileId("tile-arm"))
		return PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = listOf(deformer), drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(arm)), rootPartId = null,
			sources = listOf(ArtSource(source, "art", null, "psd", layers.map { ArtSourceLayer(it, it, "", 20, 20, 40, 40, true) })),
			atlas = PuppetAtlas(tiles = layers.map { AtlasTile(AtlasTileId("tile-$it"), it, 40, 40, null, SourceLayerRef(source, it, true)) }))
			.withParameterCreated(param, "X").withParameterRange(param, 0f, 0f, 1f).withDerivedRenderRoot()
	}

	/** The rig the records were written on: the arm under a generated warp spanning (20, 20)-(60, 60). */
	private fun recorded() = rig(Deformer.Warp(hang, "Hang", null, null, 1, 1, false,
		KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(floatArrayOf(20f, 20f, 60f, 20f, 20f, 60f, 60f, 60f)))))), hang)

	/** The same art with the warp gone and the arm on a bone. */
	private fun skeletal() = rig(Deformer.Rotation(bone, "Bone", null, null, 0f,
		KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), RotationPivotForm(30f, 25f, 15f, 1f))))), bone)

	/** A part under the warp: positions in its lattice, 4 canvas px right at X = 1. */
	private fun part(id: String): Drawable {
		val canvas = quads.getValue(id)
		val local = FloatArray(canvas.size) { (canvas[it] - 20f) / 40f }
		val grid = KeyformGrid(listOf(KeyformAxis(param, floatArrayOf(0f, 1f))), listOf(
			KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))),
			KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8) { if (it % 2 == 0) 0.1f else 0f }))))
		return Drawable(DrawableId(id), id, hang, BlendMode.Normal, emptyList(), DrawableMesh(local, canvas, triangles), grid)
	}

	private fun record(model: PuppetModel, superseded: String, layer: String, ids: List<String>, partLayers: List<String>): JsonObject {
		val primitives = ids.mapIndexed { index, id ->
			ArtPrimitiveJournal.encodePrimitive(model, part(id), partLayers[index], source.raw, LayerBounds(20, 20, 40, 40), Bounds(20f, 20f, 60f, 60f))
		}
		return ArtPrimitiveJournal.encode("split", source.raw, listOf(DrawableId(superseded)), listOf(layer),
			mapOf(DrawableId(superseded) to ids.map(::DrawableId)), primitives, emptyList(), emptyList())
	}

	private val first by lazy { record(recorded(), "Arm", "arm", listOf("P1", "P2"), listOf("p1", "p2")) }
	private val second by lazy { record(recorded(), "P2", "p2", listOf("Q1", "Q2"), listOf("q1", "q2")) }

	private fun replayed(model: PuppetModel) = ArtPrimitiveJournal.replay(ArtPrimitiveJournal.replay(model, first), second)

	private fun assertSameWorld(expected: PuppetModel, actual: PuppetModel, ids: List<String>) {
		for (x in listOf(0f, 1f)) {
			val a = evaluator.evaluate(expected, mapOf(param to x)).worldPositions
			val b = evaluator.evaluate(actual, mapOf(param to x)).worldPositions
			for (id in ids) {
				val want = a.getValue(DrawableId(id)); val got = b.getValue(DrawableId(id))
				want.indices.forEach { assertEquals(want[it], got[it], 1e-3f, "$id[$it] at X = $x") }
			}
		}
	}

	@Test fun partsUnderAVanishedGeneratedWarpGoWhereTheirOriginalLivesAndKeepTheirAppearance() {
		val reference = replayed(recorded())
		val rebuilt = replayed(skeletal())
		for (id in listOf("P1", "Q1", "Q2")) assertEquals(bone, rebuilt.drawables.single { it.id.raw == id }.parentDeformerId, id)
		assertSameWorld(reference, rebuilt, listOf("P1", "Q1", "Q2"))
		// Deterministic: the same records on the same rig give the same model.
		val again = replayed(skeletal())
		for (id in listOf("P1", "Q1", "Q2")) {
			assertContentEquals(rebuilt.drawables.single { it.id.raw == id }.mesh!!.positions, again.drawables.single { it.id.raw == id }.mesh!!.positions)
		}
	}

	@Test fun theSkeletonBakeResolvesAPartOfAnEarlierRecordThroughTheDrawableThatPartReplaced() {
		// The base the bake decodes from holds the original arm only, not the first record's parts.
		val parts = ArtPrimitiveJournal.skinnable(skeletal(), second, setOf("Q1"), listOf(first))
		assertEquals(listOf("Q1"), parts.map { it.id.raw })
		assertEquals(bone, parts.single().parentDeformerId)
		assertTrue(ArtPrimitiveJournal.skinnable(skeletal(), second, setOf("Q1")).isEmpty(), "without the earlier record nothing places it")
	}

	@Test fun aMissingParentWithoutTheReplacedDrawableStillFails() {
		val orphan = skeletal().let { it.copy(drawables = emptyList(), rootChildren = emptyList()).withDerivedRenderRoot() }
		val error = assertFailsWith<IllegalArgumentException> { ArtPrimitiveJournal.replay(orphan, first) }
		assertEquals("Art primitive parent is missing: P1", error.message)
	}

	@Test fun aRecordedBindIntoAVanishedDeformerKeepsTheMeshWhileANewOneFails() {
		val bind = buildJsonObject { put("action", "bind"); put("kind", "mesh"); put("id", "Arm"); put("parent_id", "Hang"); put("space", "local") }
		val model = skeletal()
		assertSame(model, RigStructureEdits.replay(model, listOf(bind)))
		assertFailsWith<IllegalArgumentException> { RigStructureEdits.apply(model, listOf(bind)) }
	}

	@Test fun meshesTheJournalPlacesUnderItsOwnDeformerAreNotBoundToBones() {
		val upper = SkeletonBone("upper", "Upper", null, BoneRole.UPPER_ARM, Side.RIGHT, 0f, 0f, 0f, 10f, listOf("Sleeve", "Hand", "Cuff"))
		val spec = SkeletonSpec(bones = listOf(upper))
		val journal = listOf(
			buildJsonObject { put("op", "structure"); putJsonArray("edits") {
				add(buildJsonObject { put("action", "bind"); put("kind", "mesh"); put("id", "Sleeve"); put("parent_id", "Body"); put("space", "local") })
				// A bind onto the bone itself keeps the binding.
				add(buildJsonObject { put("action", "bind"); put("kind", "mesh"); put("id", "Hand"); put("parent_id", upper.deformerId); put("space", "local") })
			} },
			RigWarpEdit("Wrap", "Wrap", "Body", listOf("Cuff")).let { buildJsonObject { put("op", "warp"); put("warp", it.toJson()) } },
		)
		assertEquals(setOf(DrawableId("Sleeve"), DrawableId("Cuff")), RigBuilder.journalPlaced(spec, journal))
	}

	@Test fun aMeshRebuildRecordedUnderAVanishedParentMovesIntoTheCurrentParent() {
		val before = ArtPrimitiveJournal.replay(recorded(), first)
		val p1 = before.drawables.single { it.id.raw == "P1" }
		// The other diagonal; the points stay in the warp's lattice, as the record holds them.
		val replacement = DrawableMesh(p1.mesh!!.positions, p1.mesh!!.uvs, intArrayOf(0, 1, 3, 0, 3, 2))
		val command = RasterMeshJournal.encode(before, p1.id, replacement)
		val reference = RasterMeshJournal.replay(before, command)
		val rebuilt = RasterMeshJournal.replay(ArtPrimitiveJournal.replay(skeletal(), first), command)
		assertEquals(bone, rebuilt.drawables.single { it.id.raw == "P1" }.parentDeformerId)
		assertContentEquals(replacement.indices, rebuilt.drawables.single { it.id.raw == "P1" }.mesh!!.indices)
		assertSameWorld(reference, rebuilt, listOf("P1"))
	}
}
