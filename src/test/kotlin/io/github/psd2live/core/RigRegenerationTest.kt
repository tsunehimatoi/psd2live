package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterRange
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import kotlin.test.*

/**
 * The three-way merge of regeneration on hand-built rigs: a body warp holding a sway warp that holds a hair mesh, and a
 * second mesh on the body warp. The "generators" change the rig the ways settings do: drop the sway warp, drop a
 * parameter, refine a mesh.
 */
class RigRegenerationTest {
	private val sway = ParameterId("ParamSway")
	private val user = ParameterId("ParamUser")
	private val body = DeformerId("Body")
	private val swayWarp = DeformerId("Sway")
	private val hair = DrawableId("Hair")
	private val skirt = DrawableId("Skirt")
	private val evaluator = CpuDeformationEvaluator()
	private val triangles = intArrayOf(0, 1, 2, 1, 3, 2)

	private fun warp(id: DeformerId, parent: DeformerId?, points: FloatArray, axis: ParameterId? = null) = Deformer.Warp(id, id.raw, parent, null, 1, 1, false,
		if (axis == null) KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(points))))
		else KeyformGrid(listOf(KeyformAxis(axis, floatArrayOf(-1f, 1f))), listOf(
			KeyformCell(intArrayOf(0), WarpLatticeForm(FloatArray(8) { points[it] - if (it % 2 == 0) 0.1f else 0f })),
			KeyformCell(intArrayOf(1), WarpLatticeForm(FloatArray(8) { points[it] + if (it % 2 == 0) 0.1f else 0f })))))

	private fun quad(id: DrawableId, parent: DeformerId?, positions: FloatArray, uvs: FloatArray) =
		Drawable(id, id.raw, parent, BlendMode.Normal, emptyList(), DrawableMesh(positions, uvs, triangles), null)

	/** Body warp over (0,0)-(100,100) of the canvas; the sway warp fills its upper half; hair fills the sway warp. */
	private fun generated(withSway: Boolean = true, refinedSkirt: Boolean = false): PuppetModel {
		val unit = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
		val deformers = listOfNotNull(warp(body, null, floatArrayOf(0f, 0f, 100f, 0f, 0f, 100f, 100f, 100f)),
			if (withSway) warp(swayWarp, body, floatArrayOf(0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0.5f), sway) else null)
		// Without the sway warp the hair sits on the body warp directly, where the sway warp put it.
		val hairMesh = if (withSway) quad(hair, swayWarp, unit, floatArrayOf(0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0.5f))
			else quad(hair, body, floatArrayOf(0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0.5f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0.5f))
		val skirtMesh = if (!refinedSkirt) quad(skirt, body, floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, 1f, 1f, 1f), floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, 1f, 1f, 1f))
			else Drawable(skirt, "Skirt", body, BlendMode.Normal, emptyList(), DrawableMesh(
				floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, 0.75f, 1f, 0.75f, 0f, 1f, 1f, 1f), floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f, 0.75f, 1f, 0.75f, 0f, 1f, 1f, 1f),
				intArrayOf(0, 1, 2, 1, 3, 2, 2, 3, 4, 3, 5, 4)), null)
		var model = PuppetModel(parameters = emptyList(), parts = emptyList(), deformers = deformers, drawables = listOf(hairMesh, skirtMesh),
			rootChildren = listOf(OrgChild.Drawable(hair), OrgChild.Drawable(skirt)), rootPartId = null)
		if (withSway) model = model.withParameterCreated(sway, "Sway").withParameterRange(sway, -1f, 0f, 1f)
		return model.withDerivedRenderRoot()
	}

	private fun hash(model: PuppetModel) = ContentHash.of(PuppetIr.toIr(model))

	private fun canvas(model: PuppetModel, values: Map<ParameterId, Float> = emptyMap()) = evaluator.evaluate(model, values).worldPositions

	private fun assertNear(expected: FloatArray, actual: FloatArray, tolerance: Float = 1e-3f, label: String = "") {
		assertEquals(expected.size, actual.size, label)
		expected.indices.forEach { assertEquals(expected[it], actual[it], tolerance, "$label[$it]") }
	}

	@Test fun withoutChangesOnEitherSideTheMergeIsTheOtherSide() {
		val g = generated(); val g2 = generated(withSway = false)
		// The user changed nothing: the new generation.
		assertEquals(hash(g2), hash(RigRegeneration.merge(g, g2, g).model))
		// The generators changed nothing: the user's rig.
		val m = g.copy(drawables = g.drawables.map { if (it.id == skirt) it.copy(opacity = 0.5f) else it })
		assertEquals(hash(m), hash(RigRegeneration.merge(g, g, m).model))
	}

	@Test fun aUserMeshUnderAVanishedWarpMovesUpAndKeepsItsPlace() {
		val g = generated(); val g2 = generated(withSway = false)
		val bow = quad(DrawableId("Bow"), swayWarp, floatArrayOf(0.2f, 0.2f, 0.4f, 0.2f, 0.2f, 0.4f, 0.4f, 0.4f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
		val m = g.copy(drawables = g.drawables + bow, rootChildren = g.rootChildren + OrgChild.Drawable(bow.id)).withDerivedRenderRoot()
		val result = RigRegeneration.merge(g, g2, m)
		val merged = result.model
		assertNull(merged.deformers.firstOrNull { it.id == swayWarp })
		assertEquals(body, merged.drawables.single { it.id == bow.id }.parentDeformerId)
		assertNear(canvas(m).getValue(bow.id), canvas(merged).getValue(bow.id), label = "bow")
		assertTrue(result.issues.any { it.kind == RigRegeneration.IssueKind.REHOMED && it.target == "Bow" })
		// The generated hair follows the new generation; the sway parameter nothing keys on any more is gone.
		assertEquals(body, merged.drawables.single { it.id == hair }.parentDeformerId)
		assertTrue(merged.parameters.none { it.id == sway })
		assertTrue(merged.drawables.single { it.id == bow.id }.id in merged.rootChildren.filterIsInstance<OrgChild.Drawable>().map { it.id })
	}

	@Test fun aUserKeyformOnAMeshTheGeneratorsReparentMovesWithIt() {
		val g = generated(); val g2 = generated(withSway = false)
		val m = g.withParameterCreated(user, "User").withParameterRange(user, 0f, 0f, 1f).let { model ->
			// One unit of the sway warp's lattice is 50 canvas pixels down: the hair's lower edge drops 5 px at User = 1.
			model.copy(drawables = model.drawables.map { if (it.id != hair) it else it.copy(geometryGrid = KeyformGrid(listOf(KeyformAxis(user, floatArrayOf(0f, 1f))),
				listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))),
					KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8) { if (it >= 4 && it % 2 == 1) 0.1f else 0f }))))) })
		}
		val result = RigRegeneration.merge(g, g2, m)
		val merged = result.model
		assertEquals(body, merged.drawables.single { it.id == hair }.parentDeformerId)
		assertTrue(result.issues.any { it.kind == RigRegeneration.IssueKind.REPARENTED })
		for (value in listOf(0f, 1f)) {
			assertNear(canvas(m, mapOf(user to value)).getValue(hair), canvas(merged, mapOf(user to value)).getValue(hair), label = "hair at User = $value")
		}
		assertTrue(merged.parameters.any { it.id == user })
	}

	@Test fun aDroppedParameterStaysWhileTheUserKeysOnIt() {
		val g = generated(); val g2 = generated(withSway = false)
		// The user keyed the skirt on the sway parameter.
		val m = g.copy(drawables = g.drawables.map { if (it.id != skirt) it else it.copy(geometryGrid = KeyformGrid(listOf(KeyformAxis(sway, floatArrayOf(-1f, 1f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8) { -0.01f })), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8) { 0.01f }))))) })
		val result = RigRegeneration.merge(g, g2, m)
		assertTrue(result.model.parameters.any { it.id == sway })
		assertTrue(result.issues.any { it.kind == RigRegeneration.IssueKind.RETIRED_KEPT && it.target == "parameter:ParamSway" })
		assertNotNull(result.model.drawables.single { it.id == skirt }.geometryGrid)
	}

	@Test fun aRefinedMeshCarriesTheUsersVertexGroupAndKeyforms() {
		val g = generated(); val g2 = generated(refinedSkirt = true)
		val m = g.withParameterCreated(user, "User").withParameterRange(user, 0f, 0f, 1f).let { model ->
			model.copy(drawables = model.drawables.map { if (it.id != skirt) it else it.copy(geometryGrid = KeyformGrid(listOf(KeyformAxis(user, floatArrayOf(0f, 1f))),
				listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8) { if (it % 2 == 0) 0.05f else 0f }))))) },
				vertexGroups = listOf(VertexGroup("pin", skirt, VertexGroupKind.PIN, floatArrayOf(1f, 1f, 0f, 0f))))
		}
		val result = RigRegeneration.merge(g, g2, m)
		val merged = result.model
		val mesh = merged.drawables.single { it.id == skirt }
		assertEquals(6, mesh.mesh!!.vertexCount)
		assertTrue(result.issues.any { it.kind == RigRegeneration.IssueKind.TOPOLOGY_MIGRATED })
		// The middle row is halfway down: half pinned.
		assertNear(floatArrayOf(1f, 1f, 0.5f, 0.5f, 0f, 0f), merged.vertexGroups.single().weights, label = "weights")
		// Every vertex moves 5 px right at User = 1, as the user keyed it.
		val rest = canvas(merged).getValue(skirt); val moved = canvas(merged, mapOf(user to 1f)).getValue(skirt)
		for (v in 0 until 6) { assertEquals(5f, moved[v * 2] - rest[v * 2], 1e-3f, "x of $v"); assertEquals(0f, moved[v * 2 + 1] - rest[v * 2 + 1], 1e-3f, "y of $v") }
	}

	@Test fun whatTheUserDeletedStaysDeletedAndTheirOwnChangesWinConflicts() {
		val g = generated(); val g2 = generated().let { model -> model.copy(drawables = model.drawables.map { if (it.id == skirt) it.copy(opacity = 0.8f, name = "Generated") else it }) }
		val m = g.copy(drawables = g.drawables.filter { it.id != hair }.map { if (it.id == skirt) it.copy(opacity = 0.3f) else it },
			rootChildren = g.rootChildren.filterNot { it == OrgChild.Drawable(hair) }).withDerivedRenderRoot()
		val result = RigRegeneration.merge(g, g2, m)
		assertTrue(result.model.drawables.none { it.id == hair })
		val merged = result.model.drawables.single { it.id == skirt }
		assertEquals(0.3f, merged.opacity)
		assertEquals("Generated", merged.name)
		assertTrue(result.issues.any { it.kind == RigRegeneration.IssueKind.CONFLICT && it.detail == "opacity" })
	}

	/** [g] with a torso warp spliced between the body warp and everything on it, bending on ParamBend. */
	private fun spliced(g: PuppetModel): PuppetModel {
		val bend = ParameterId("ParamBend")
		val torso = warp(DeformerId("Torso"), body, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), bend)
		val deformers = g.deformers.map { if (it is Deformer.Warp && it.parent == body) it.copy(parent = torso.id) else it }
		val at = deformers.indexOfFirst { it.id == body } + 1
		return g.copy(deformers = deformers.subList(0, at) + torso + deformers.subList(at, deformers.size),
			drawables = g.drawables.map { if (it.parentDeformerId == body) it.copy(parentDeformerId = torso.id) else it })
			.withParameterCreated(bend, "Bend").withParameterRange(bend, -1f, 0f, 1f).withDerivedRenderRoot()
	}

	@Test fun aUserMeshOnAWarpTheGeneratorsSpliceANewWarpUnderFollowsWhatItHeld() {
		// The torso warp a skeleton splices under the breath warp takes over all it held; so does a mesh the user put there.
		val g = generated(); val g2 = spliced(g)
		val bow = quad(DrawableId("Bow"), body, floatArrayOf(0.2f, 0.6f, 0.4f, 0.6f, 0.2f, 0.8f, 0.4f, 0.8f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
		val m = g.copy(drawables = g.drawables + bow, rootChildren = g.rootChildren + OrgChild.Drawable(bow.id)).withDerivedRenderRoot()
		val result = RigRegeneration.merge(g, g2, m)
		val merged = result.model
		assertEquals(DeformerId("Torso"), merged.drawables.single { it.id == bow.id }.parentDeformerId)
		assertTrue(result.issues.any { it.kind == RigRegeneration.IssueKind.FOLLOWED && it.target == "mesh:Bow" && it.detail == "Torso" }, "${result.issues}")
		assertNear(canvas(m).getValue(bow.id), canvas(merged).getValue(bow.id), label = "bow at rest")
		// It bends with the skirt beside it.
		val bent = mapOf(ParameterId("ParamBend") to 1f)
		val bowShift = canvas(merged, bent).getValue(bow.id)[0] - canvas(merged).getValue(bow.id)[0]
		val skirtShift = canvas(merged, bent).getValue(skirt)[0] - canvas(merged).getValue(skirt)[0]
		assertTrue(bowShift > 1f, "the bow bends: $bowShift")
		assertEquals(skirtShift, bowShift, 1e-3f)
		// A generated mesh the user left follows the new generation as before.
		assertEquals(DeformerId("Torso"), merged.drawables.single { it.id == skirt }.parentDeformerId)
	}

	@Test fun aMeshWhoseTopologyTheUserRebuiltTakesTheGeneratorsNewParentAndKeyforms() {
		val gen = ParameterId("ParamGen")
		val g = generated()
		// The generators drop the sway warp and key the hair 10 px right on a new parameter.
		val g2 = generated(withSway = false).withParameterCreated(gen, "Gen").withParameterRange(gen, 0f, 0f, 1f).let { model ->
			model.copy(drawables = model.drawables.map { if (it.id != hair) it else it.copy(geometryGrid = KeyformGrid(listOf(KeyformAxis(gen, floatArrayOf(0f, 1f))),
				listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(8) { if (it % 2 == 0) 0.1f else 0f }))))) })
		}
		// The user rebuilt the hair as a 3 x 2 grid on the sway warp and keyed it 5 px down on their own parameter.
		val rebuilt = DrawableMesh(floatArrayOf(0f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f, 0.5f, 1f, 1f, 1f),
			floatArrayOf(0f, 0f, 0.5f, 0f, 1f, 0f, 0f, 0.5f, 0.5f, 0.5f, 1f, 0.5f), intArrayOf(0, 1, 3, 1, 4, 3, 1, 2, 4, 2, 5, 4))
		val m = g.withParameterCreated(user, "User").withParameterRange(user, 0f, 0f, 1f).let { model ->
			model.copy(drawables = model.drawables.map { if (it.id != hair) it else it.copy(mesh = rebuilt, geometryGrid = KeyformGrid(listOf(KeyformAxis(user, floatArrayOf(0f, 1f))),
				listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(12))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(12) { if (it % 2 == 1) 0.1f else 0f }))))) })
		}
		val result = RigRegeneration.merge(g, g2, m)
		val merged = result.model.drawables.single { it.id == hair }
		assertEquals(body, merged.parentDeformerId, "the generators' new parent")
		assertEquals(6, merged.mesh!!.vertexCount, "the user's vertices")
		assertTrue(result.issues.any { it.kind == RigRegeneration.IssueKind.TOPOLOGY_FOLLOWED && it.target == "mesh:Hair" }, "${result.issues}")
		val rest = canvas(result.model).getValue(hair)
		assertNear(canvas(m).getValue(hair), rest, label = "hair at rest")
		val generated = canvas(result.model, mapOf(gen to 1f)).getValue(hair)
		for (i in rest.indices step 2) { assertEquals(rest[i] + 10f, generated[i], 1e-2f); assertEquals(rest[i + 1], generated[i + 1], 1e-2f) }
		assertNear(canvas(m, mapOf(user to 1f)).getValue(hair), canvas(result.model, mapOf(user to 1f)).getValue(hair), tolerance = 1e-2f, label = "hair at User = 1")
	}

	private fun checkpoint(authored: PuppetModel, generated: PuppetModel) = RigCheckpoint.encode(
		AuthoredRig(BuiltRig(authored, emptyMap(), emptyMap(), emptyMap(), 0f, 0f, 0f, 0f, emptyList()), emptyList()), "test",
		generated = AuthoredRig(BuiltRig(generated, emptyMap(), emptyMap(), emptyMap(), 0f, 0f, 0f, 0f, emptyList()), emptyList()))

	@Test fun aRegenerationAnEarlierBuildMergedDifferentlyIsMergedAgainAndTheCorrectionCarriedOntoLaterEdits() {
		val g0 = generated(); val g1 = spliced(g0)
		val bow = quad(DrawableId("Bow"), body, floatArrayOf(0.2f, 0.6f, 0.4f, 0.6f, 0.2f, 0.8f, 0.4f, 0.8f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
		val m0 = g0.copy(drawables = g0.drawables + bow, rootChildren = g0.rootChildren + OrgChild.Drawable(bow.id)).withDerivedRenderRoot()
		// What an earlier build made of the splice: the bow left on the body warp, beside the torso warp.
		val stale = g1.copy(drawables = g1.drawables + bow, rootChildren = g1.rootChildren + OrgChild.Drawable(bow.id)).withDerivedRenderRoot()
		val overlay = RigEditOverlay(authoringJournal = listOf(checkpoint(m0, g0), checkpoint(stale, g1)))
		// A later edit: the skirt half transparent.
		val current = stale.copy(drawables = stale.drawables.map { if (it.id == skirt) it.copy(opacity = 0.5f) else it })
		val repair = assertNotNull(RigMergeRepair.repaired(overlay, current, g1.atlas, g1.sources))
		assertEquals(DeformerId("Torso"), repair.authored.drawables.single { it.id == bow.id }.parentDeformerId)
		assertEquals(0.5f, repair.authored.drawables.single { it.id == skirt }.opacity, "the later edit stays")
		assertNear(canvas(current).getValue(bow.id), canvas(repair.authored).getValue(bow.id), label = "bow at rest")
		val remerged = repair.issues.single { it.kind == RigRegeneration.IssueKind.REMERGED }
		assertEquals("journal:1", remerged.target)
		assertEquals("mesh:Bow", remerged.detail)
		// Once repaired, the stale checkpoint stays in the journal but asks for nothing more.
		val repaired = RigEditOverlay(authoringJournal = overlay.authoringJournal + checkpoint(repair.authored, g1))
		assertNull(RigMergeRepair.repaired(repaired, repair.authored, g1.atlas, g1.sources))
		// A journal this build merges alike needs nothing.
		val faithful = RigEditOverlay(authoringJournal = listOf(checkpoint(m0, g0), checkpoint(RigRegeneration.merge(g0, g1, m0).model, g1)))
		assertNull(RigMergeRepair.repaired(faithful, current, g1.atlas, g1.sources))
	}

	@Test fun aRebuiltMeshDropsTheBlendShapeItsGeneratorsMovedOntoAKeyformAxis() {
		// A skeleton change can turn a bone from a blend shape into a keyform axis. On the user's own vertices every shape
		// differs from G's by count alone; one that is G's sampled onto them is not the user's and must not stay beside the axis.
		val gen = ParameterId("ParamGen")
		val unitDeltas = FloatArray(8) { if (it % 2 == 0) 0.1f else 0f }
		fun withGen(model: PuppetModel) = model.withParameterCreated(gen, "Gen").withParameterRange(gen, 0f, 0f, 1f)
		val g = withGen(generated(withSway = false)).let { model -> model.copy(drawables = model.drawables.map { if (it.id != hair) it else
			it.copy(blendShapes = listOf(BlendShapeBinding(gen, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(unitDeltas))))) }) }
		val g2 = withGen(generated(withSway = false)).let { model -> model.copy(drawables = model.drawables.map { if (it.id != hair) it else
			it.copy(geometryGrid = KeyformGrid(listOf(KeyformAxis(gen, floatArrayOf(0f, 1f))),
				listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(8))), KeyformCell(intArrayOf(1), MeshDeltaForm(unitDeltas))))) }) }
		// The user rebuilt the hair as a 3 x 2 grid; its blend shape is G's on the new vertices.
		val rebuilt = DrawableMesh(floatArrayOf(0f, 0f, 0.5f, 0f, 1f, 0f, 0f, 0.5f, 0.5f, 0.5f, 1f, 0.5f),
			floatArrayOf(0f, 0f, 0.5f, 0f, 1f, 0f, 0f, 0.5f, 0.5f, 0.5f, 1f, 0.5f), intArrayOf(0, 1, 3, 1, 4, 3, 1, 2, 4, 2, 5, 4))
		val m = g.copy(drawables = g.drawables.map { if (it.id != hair) it else it.copy(mesh = rebuilt,
			blendShapes = listOf(BlendShapeBinding(gen, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(FloatArray(12) { if (it % 2 == 0) 0.1f else 0f }))))) })
		val result = RigRegeneration.merge(g, g2, m)
		val merged = result.model.drawables.single { it.id == hair }
		assertTrue(merged.blendShapes.none { it.parameterId == gen }, "the stale blend shape is gone: ${merged.blendShapes.map { it.parameterId }}")
		val rest = canvas(result.model).getValue(hair); val moved = canvas(result.model, mapOf(gen to 1f)).getValue(hair)
		for (i in rest.indices step 2) assertEquals(rest[i] + 10f, moved[i], 1e-2f, "moved once, not twice")
	}
}
