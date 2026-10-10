package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterRange
import org.umamo.format.art.LayerBounds
import org.umamo.runtime.model.*
import kotlin.math.abs
import kotlin.test.*
import io.github.psd2live.core.legacy.ArtPrimitiveReplay

/**
 * Version 2 `art_primitive` records (Rule B) on a hand-built rig: a mesh of two islands with a generator axis
 * (Eye, closed at 0) and a user axis (User), split per island. The parts the base would generate are a fixture: the
 * generated original's keyforms carried onto each part ([PrimitiveSkinsFixture]), as an unchanged generator makes them.
 */
class ArtPrimitiveRecordV2Test {
	private val eye = ParameterId("ParamEye")
	private val user = ParameterId("ParamUser")
	private val ghost = DrawableId("Ghost")
	private val other = DrawableId("Other")
	private val parts = listOf(DrawableId("PartA"), DrawableId("PartB"))
	// Two triangles, no shared vertex: one island each.
	private val positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 30f, 0f, 40f, 0f, 30f, 10f)
	private val triangles = intArrayOf(0, 1, 2, 3, 4, 5)

	private fun deltas(vararg first: Float) = FloatArray(12).also { first.copyInto(it) }
	private val closed = FloatArray(12) { if (it % 2 == 1) 2f else 0f }
	private val tweak = deltas(1f, 0f, 0f, 1f)
	private val shape = deltas(0f, 3f, 3f)
	private val rest = deltas(0.5f, 0.5f)

	private fun base(): PuppetModel {
		val mesh = DrawableMesh(positions, positions.copyOf(), triangles)
		val generated = KeyformGrid(listOf(KeyformAxis(eye, floatArrayOf(0f, 1f))), listOf(
			KeyformCell(intArrayOf(0), MeshDeltaForm(closed)), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(12)))))
		val drawable = Drawable(ghost, "Ghost", null, BlendMode.Normal, emptyList(), mesh, null).copy(geometryGrid = generated)
		val second = Drawable(other, "Other", null, BlendMode.Normal, emptyList(), DrawableMesh(positions, positions.copyOf(), triangles), null)
		val path = DeformPath(RigBuilder.generatedPathId(ghost, "upper"), ghost, listOf(DeformPathPoint(0, 1, 2, 1f, 0f, 0f, false), DeformPathPoint(0, 1, 2, 0f, 0f, 1f, false)))
		return PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = emptyList(), drawables = listOf(drawable, second),
			rootChildren = listOf(OrgChild.Drawable(ghost), OrgChild.Drawable(other)), rootPartId = null,
			deformPaths = listOf(path),
			glues = listOf(Glue(ghost, other, listOf(GluePair(4, 4, 0.5f, 0.5f)), id = "GlueSkel__Ghost__Other")))
			.withParameterCreated(eye, "Eye").withParameterRange(eye, 0f, 1f, 1f)
			.withParameterCreated(user, "User").withParameterRange(user, 0f, 0f, 1f)
	}

	/** The base with the user's edits: rest, closed-eye tweak and a user shape on island A, a user path and Glue. */
	private fun authored(base: PuppetModel): PuppetModel {
		val axes = listOf(KeyformAxis(eye, floatArrayOf(0f, 1f)), KeyformAxis(user, floatArrayOf(0f, 1f)))
		fun sum(vararg values: FloatArray) = FloatArray(12) { i -> values.sumOf { it[i].toDouble() }.toFloat() }
		val grid = KeyformGrid(axes, listOf(
			KeyformCell(intArrayOf(0, 0), MeshDeltaForm(sum(closed, rest, tweak))),
			KeyformCell(intArrayOf(1, 0), MeshDeltaForm(rest)),
			KeyformCell(intArrayOf(0, 1), MeshDeltaForm(sum(closed, rest, shape))),
			KeyformCell(intArrayOf(1, 1), MeshDeltaForm(sum(rest, shape)))))
		val userPath = DeformPath("Spine", ghost, listOf(DeformPathPoint(0, 1, 2, 0f, 1f, 0f, false), DeformPathPoint(0, 1, 2, 0.5f, 0.5f, 0f, false)))
		return base.copy(drawables = base.drawables.map { if (it.id == ghost) it.copy(geometryGrid = grid, opacity = 0.5f) else it },
			deformPaths = base.deformPaths + userPath,
			glues = base.glues + Glue(ghost, other, listOf(GluePair(1, 1, 0.5f, 0.5f)), id = "Weld"))
	}

	private class Split(val authored: PuppetModel, val generated: PuppetModel, val plan: SourcePartitionGeometry.Plan,
	                    val partitionedA: SourcePartitionJournal.Partitioned, val partitionedG: SourcePartitionJournal.Partitioned)

	private fun split(): Split {
		val generated = base(); val authored = authored(generated)
		val mesh = authored.drawables.single { it.id == ghost }.mesh!!
		val plan = SourcePartitionGeometry.components(mesh, positions, intArrayOf(0, 0, 0, 1, 1, 1), 2)
		val command = SourcePartitionJournal.encode(authored, ghost, listOf("a", "b"), parts.map { it.raw }, listOf("A", "B"), plan)
		fun partition(model: PuppetModel) = SourcePartitionJournal.partition(model, command) { clone, canvas -> clone to canvas }
		return Split(authored, generated, plan, partition(authored), partition(generated))
	}

	private fun record(split: Split, skins: PrimitiveSkins, parkedModel: PuppetModel?): Pair<JsonObject, List<JsonObject>> {
		val encoded = parts.mapIndexed { index, id ->
			val parked = skins.drawables[id]
			LegacyArtPrimitiveV2.encode(split.partitionedA.model, split.partitionedA.model.drawables.single { it.id == id },
				split.authored, split.generated, ghost, split.plan.pieces[index].sources, listOf("a", "b")[index], "src",
				LayerBounds(0, 0, 40, 10), Bounds(0f, 0f, 40f, 10f), LayerClassificationOverride(), false,
				parkedModel?.let { m -> parked?.let { m.copy(drawables = m.drawables + it) } }, parked, skins)
		}
		val groups = split.partitionedA.glueGroups.map { group -> group.filterNot(ArtPrimitiveReplay::isSkeletonWeld) }.filter { it.isNotEmpty() }
		val record = ArtPrimitiveJournal.encode("split", "src", listOf(ghost), listOf("ghost"), mapOf(ghost to parts), encoded.map { it.primitive },
			groups, split.partitionedA.followers, version = ArtPrimitiveV2.VERSION_V2)
		return record to encoded.flatMap { it.overrides }
	}

	private fun skins(split: Split) = PrimitiveSkinsFixture.fromParts(split.partitionedG.model, parts, stubs = setOf(ghost),
		owner = mapOf(eye to "mesh:ghost"))

	private fun assertSameGeometry(expected: Drawable, actual: Drawable) {
		val size = expected.mesh!!.positions.size
		assertEquals(size, actual.mesh!!.positions.size)
		for (e in listOf(0f, 0.5f, 1f)) for (u in listOf(0f, 0.5f, 1f)) {
			val value = { id: ParameterId -> if (id == eye) e else u }
			val a = PrimitiveResidual.sample(expected.geometryGrid, size, value)
			val b = PrimitiveResidual.sample(actual.geometryGrid, size, value)
			for (i in a.indices) assertTrue(abs(a[i] - b[i]) < 1e-4f, "${expected.id.raw} at eye=$e user=$u vertex value $i: ${a[i]} vs ${b[i]}")
		}
	}

	@Test fun residualRoundTripGivesBackTheAuthoredParts() {
		val split = split(); val skins = skins(split)
		val (record, overrides) = record(split, skins, split.generated)
		assertTrue(ArtPrimitiveV2.isV2(record))
		ArtPrimitiveV2.resolve(RigEditOverlay(authoringJournal = listOf(record))).parts.forEach { assertTrue(it.canvasPositions) }
		val replayed = ArtPrimitiveReplay.replay(split.authored, record, skins)
		val merged = GeneratedOverrides.applyAll(replayed, overrides)
		assertTrue(merged.issues.isEmpty(), merged.conflicts.toString())
		for (id in parts) assertSameGeometry(split.partitionedA.model.drawables.single { it.id == id }, merged.model.drawables.single { it.id == id })
		assertTrue(merged.model.drawables.none { it.id == ghost })
		assertEquals(0.5f, merged.model.drawables.single { it.id == parts[0] }.opacity)
	}

	@Test fun overridesOnlyForEditedGeneratorCells() {
		val split = split(); val skins = skins(split)
		val (record, overrides) = record(split, skins, split.generated)
		// Island A has edits with the eye closed (alone and with the user axis); island B none.
		assertEquals(listOf("mesh:PartA", "mesh:PartA"), overrides.map { it.getValue("target").jsonPrimitive.content })
		assertEquals(setOf(mapOf("ParamEye" to 0f, "ParamUser" to 0f), mapOf("ParamEye" to 0f, "ParamUser" to 1f)),
			overrides.map { o -> o.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float } }.toSet())
		assertTrue(overrides.all { it.getValue("generator").jsonPrimitive.content == "mesh:ghost" })
		val primitives = ArtPrimitiveJournal.primitives(record)
		val authoredB = primitives[1].getValue(ArtPrimitiveV2.AUTHORED).jsonObject
		assertEquals(JsonNull, authoredB.getValue(ArtPrimitiveV2.GEOMETRY_RESIDUAL))
		// The plane residual of A is keyed on the user axis with the eye at its default only.
		val residual = primitives[0].getValue(ArtPrimitiveV2.AUTHORED).jsonObject.getValue(ArtPrimitiveV2.GEOMETRY_RESIDUAL).jsonObject
		val axes = residual.getValue("axes").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
		assertTrue("ParamUser" in axes)
		assertEquals(2, residual.getValue("cells").jsonArray.size)
	}

	@Test fun keepsUserGluesAndPathsAndDropsGeneratedOnes() {
		val split = split(); val skins = skins(split)
		val (record, _) = record(split, skins, split.generated)
		val glues = record.getValue("glues").jsonObject
		val ids = glues.getValue("replaced").jsonArray.flatMap { group -> group.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } }
		assertEquals(listOf("Weld"), ids)
		val paths = ArtPrimitiveJournal.primitives(record).flatMap { p -> p.getValue(ArtPrimitiveV2.AUTHORED).jsonObject.getValue(ArtPrimitiveV2.PATHS).jsonArray }
			.map { it.jsonObject.getValue("id").jsonPrimitive.content }
		assertTrue(paths.isNotEmpty() && paths.all { it.endsWith("/Spine") }, paths.toString())
		// Replayed, the user Glue lands on the part holding its vertex and the skeleton weld comes from the base.
		val weld = Glue(parts[1], other, listOf(GluePair(1, 4, 0.5f, 0.5f)), id = "GlueSkel__PartB__Other")
		val withWeld = PrimitiveSkinsFixture.fromParts(split.partitionedG.model, parts, stubs = setOf(ghost), owner = mapOf(eye to "mesh:ghost"), welds = listOf(weld))
		val replayed = ArtPrimitiveReplay.replay(split.authored, record, withWeld)
		assertEquals(setOf("Weld", "GlueSkel__PartB__Other"), replayed.glues.mapNotNull { it.id }.toSet())
		assertEquals(parts[0], replayed.glues.single { it.id == "Weld" }.meshA)
		assertTrue(replayed.deformPaths.none { it.drawableId == ghost })
		assertTrue(replayed.deformPaths.any { it.id.endsWith("/Spine") })
	}

	@Test fun replayRequiresEveryPartInTheSideChannel() {
		val split = split()
		val (record, _) = record(split, skins(split), split.generated)
		val partial = PrimitiveSkinsFixture.fromParts(split.partitionedG.model, parts.take(1), stubs = setOf(ghost))
		val failure = assertFailsWith<IllegalArgumentException> { ArtPrimitiveReplay.replay(split.authored, record, partial) }
		assertTrue(failure.message!!.contains("PartB"))
	}

	@Test fun recordedVerticesCarryOntoARegeneratedMesh() {
		val split = split(); val skins = skins(split)
		val (record, overrides) = record(split, skins, split.generated)
		// The base inserts a vertex into island A (a joint row): same pixels, one more vertex at the edge's middle.
		val primitive = ArtPrimitiveJournal.primitives(record)[0]
		val recorded = primitive.getValue(ArtPrimitiveV2.MESH).jsonObject
		val canvas = recorded.getValue(ArtPrimitiveV2.CANVAS_UVS).jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
		val recordedTriangles = recorded.getValue(ArtPrimitiveV2.TRIANGLES).jsonArray.map { it.jsonPrimitive.int }.toIntArray()
		assertNull(ArtPrimitiveReplay.vertexMap(canvas, recordedTriangles, canvas.copyOf(), recordedTriangles))
		val grown = canvas + floatArrayOf((canvas[0] + canvas[2]) / 2, (canvas[1] + canvas[3]) / 2)
		val map = assertNotNull(ArtPrimitiveReplay.vertexMap(canvas, recordedTriangles, grown, intArrayOf(0, 3, 2, 3, 1, 2)))
		val residual = RasterMeshCreation.decodeGrid(primitive.getValue(ArtPrimitiveV2.AUTHORED).jsonObject
			.getValue(ArtPrimitiveV2.GEOMETRY_RESIDUAL).jsonObject, split.authored) { value ->
			MeshDeltaForm(value.jsonArray.map { it.jsonPrimitive.float }.toFloatArray())
		}
		val carried = PrimitiveResidual.carry(residual) { PrimitiveResidual.transfer(it, map.sources) }!!
		val parameters = split.authored.parameters.associateBy { it.id }
		val composed = PrimitiveResidual.compose(parameters, null, carried, 8)
		// The residual at rest moves the recorded vertices as authored; the inserted one takes the middle of its edge.
		val atRest = PrimitiveResidual.sample(composed, 8) { id -> if (id == eye) 1f else 0f }
		assertEquals(0.5f, atRest[0], 1e-4f); assertEquals(0.5f, atRest[1], 1e-4f)
		assertEquals(0.25f, atRest[6], 1e-4f); assertEquals(0.25f, atRest[7], 1e-4f)
		// Overrides recorded on the old vertices no longer match a regenerated mesh: reported, not applied.
		val regrown = ArtPrimitiveReplay.replay(split.authored, record, skins).let { model ->
			model.copy(drawables = model.drawables.map { d ->
				if (d.id != parts[0]) d else d.copy(mesh = DrawableMesh(grown, grown, intArrayOf(0, 3, 2, 3, 1, 2)),
					geometryGrid = PrimitiveResidual.carry(d.geometryGrid) { PrimitiveResidual.transfer(it, map.sources) })
			})
		}
		assertTrue(GeneratedOverrides.applyAll(regrown, overrides).issues.any { it.reason == GeneratedOverrideOrphanReason.SHAPE_MISMATCH })
	}

	@Test fun stubToleranceSkipsOnlyEntriesAddressingStubs() {
		val split = split(); val skins = skins(split)
		val (record, _) = record(split, skins, split.generated)
		fun rebuild(id: String) = buildJsonObject {
			put("op", RasterMeshJournal.OP); put("id", id); put("before_mesh", "stale"); put("parent", JsonNull)
		}
		val tolerant = RigEditOverlay(authoringJournal = listOf(rebuild("Ghost"), record))
		val tolerated = tolerant.applyToReporting(split.generated, skins)
		val note = tolerated.notes.single()
		assertEquals(0, note.index); assertEquals(RasterMeshJournal.OP, note.op); assertEquals(listOf("Ghost"), note.targets)
		// A replay resumed from a checkpoint still reports the entries before it, and the built rig carries them.
		val previous = ReplayCheckpoints.enabled
		ReplayCheckpoints.enabled = true
		try {
			ReplayCheckpoints.clear()
			tolerant.applyToReporting(split.generated, skins)
			val again = tolerant.applyToReporting(split.generated, skins)
			assertEquals(2, ReplayCheckpoints.lastReplayed()?.from)
			assertEquals(listOf(note), again.notes)
		} finally { ReplayCheckpoints.enabled = previous }
		val rig = BuiltRig(split.generated, emptyMap(), emptyMap(), emptyMap(), 0f, 0f, 1f, 1f, emptyList(), primitiveSkins = skins)
		assertEquals(listOf(note), rig.withRigEdits(tolerant).supersededEntryNotes)
		// The overrides quality report lists it as an info finding by its code.
		val finding = io.github.psd2live.core.quality.GeneratedOverrideQuality.report(emptyList(), listOf(note))
			.getValue("findings").jsonArray.single().jsonObject
		assertEquals("SUPERSEDED_ENTRY_SKIPPED", finding.getValue("code").jsonPrimitive.content)
		assertEquals("info", finding.getValue("severity").jsonPrimitive.content)
		assertEquals("journal:0", finding.getValue("target").jsonPrimitive.content)
		assertTrue(tolerated.model.drawables.any { it.id == parts[0] })
		assertFailsWith<IllegalArgumentException> {
			RigEditOverlay(authoringJournal = listOf(rebuild("Other"), record)).applyToReporting(split.generated, skins)
		}
		// After the record, the same entry is an ordinary failure.
		assertFails { RigEditOverlay(authoringJournal = listOf(record, rebuild("Ghost"))).applyToReporting(split.generated, skins) }
	}

	@Test fun capturedOverridesUseTheSideChannelOwnership() {
		val split = split(); val skins = skins(split)
		val (record, overrides) = record(split, skins, split.generated)
		val model = GeneratedOverrides.applyAll(ArtPrimitiveReplay.replay(split.authored, record, skins), overrides).model
		val part = model.drawables.single { it.id == parts[1] }
		fun edit(key: Map<String, Float>) = JsonArray(listOf(buildJsonObject {
			put("op", "canvas_geometry"); put("kind", "mesh"); put("id", part.id.raw)
			put("key", JsonObject(key.mapValues { JsonPrimitive(it.value) }))
			put("points", JsonArray(part.mesh!!.positions.map { JsonPrimitive(it + 1f) }))
		}))
		val overlay = RigEditOverlay(authoringJournal = listOf(record))
		val generatorCell = GeneratedOverrides.capture(model, overlay, edit(mapOf("ParamEye" to 0f)), skins).single().jsonObject
		assertEquals(GeneratedOverrides.OP, generatorCell.getValue("op").jsonPrimitive.content)
		assertEquals("mesh:ghost", generatorCell.getValue("generator").jsonPrimitive.content)
		val defaultCell = GeneratedOverrides.capture(model, overlay, edit(mapOf("ParamEye" to 1f)), skins).single().jsonObject
		assertEquals("canvas_geometry", defaultCell.getValue("op").jsonPrimitive.content)
	}

	@Test fun boneBindingsMoveToTheParts() {
		val bone = SkeletonBone("thigh", "Thigh", null, BoneRole.THIGH, headX = 0f, headY = 0f, tailX = 0f, tailY = 10f,
			drawableIds = listOf("Before", "Ghost", "After"))
		val overlay = RigEditOverlay(skeleton = SkeletonSpec(bones = listOf(bone)))
		val moved = SourcePartitionJournal.migrateBones(overlay, "Ghost", listOf("PartA", "PartB"))
		assertEquals(listOf("Before", "PartA", "PartB", "After"), moved.skeleton!!.bones.single().drawableIds)
		assertSame(overlay, SourcePartitionJournal.migrateBones(overlay, "Elsewhere", listOf("X")))
	}
}
