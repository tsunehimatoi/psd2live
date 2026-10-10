package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Split parts across regeneration checkpoints: a split merges onto the rig and checkpoints it, a split past a checkpoint
 * hangs its parts on what its own generation adds, and the skeleton's skin and welds reach the parts through the merge.
 */
class SplitPartsCheckpointTest {
	private val builder = WorkspacePreviewBuilder()

	@AfterTest fun forget() { MaterializedRigStore.clear() }

	private fun layer(id: String, order: Int, vararg boxes: IntArray) =
		islandLayer(id, order, SkeletonCharacterFixture.SIZE, SkeletonCharacterFixture.SIZE, *boxes)

	private fun document(layers: List<WorkspaceSourceLayer>, overrides: Map<String, LayerClassificationOverride>) =
		WorkspaceDocument(SkeletonCharacterFixture.source(*layers.toTypedArray()), emptyMap(), emptySet(), overrides, emptyMap(),
			RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 1024, generatePhysics = false, exportMoc3 = false)))

	private val figure = listOf(
		layer("face", 5, intArrayOf(170, 30, 250, 110)),
		layer("top", 3, intArrayOf(160, 115, 260, 240)),
	)
	private val classified = mapOf(
		"face" to LayerClassificationOverride(tag = SemanticTag.FACE),
		"top" to LayerClassificationOverride(tag = SemanticTag.TOPWEAR),
	)

	private suspend fun installed(document: WorkspaceDocument): WorkspaceRuntime<RigPreviewModel> {
		val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { next, previous -> builder.build(next, previous) })
		runtime.install(runtime.state.value.state, "project", document, builder.build(document))
		return runtime
	}

	private suspend fun split(runtime: WorkspaceRuntime<RigPreviewModel>, operation: String, request: JsonObject): WorkspaceCapture<RigPreviewModel> {
		val start = runtime.capture()
		return WorkspacePartitionCommands(runtime).execute(start.projectId, start.state,
			listOf(WorkspaceDocumentOperation(operation, request)), "Split", MutationAuthor.USER).commit.capture
	}

	private fun sides(layer: String) = buildJsonObject {
		put("layer_id", layer); putJsonArray("names") { add("$layer L"); add("$layer R") }
		putJsonArray("sides") { add("left"); add("right") }
	}

	@Test fun aSplitPastACheckpointHangsOnThePairWarpItsCommitGenerates() = runBlocking<Unit> {
		// Legs and shoes drawn on one layer each, both sides: each split per side adds a pair warp its parts hang on.
		val runtime = installed(document(figure + listOf(
			layer("legs", 2, intArrayOf(175, 235, 205, 390), intArrayOf(215, 235, 245, 390)),
			layer("shoes", 1, intArrayOf(172, 392, 206, 410), intArrayOf(214, 392, 248, 410)),
		), classified + mapOf("legs" to LayerClassificationOverride(tag = SemanticTag.LEGWEAR),
			"shoes" to LayerClassificationOverride(tag = SemanticTag.FOOTWEAR))))
		val legs = split(runtime, "source_split_components", sides("legs"))
		assertTrue(legs.document.rigEdits.authoringJournal.any(RigCheckpoint::isRecord), "the first split checkpoints the generation it changes")
		val shoes = split(runtime, "source_split_components", sides("shoes"))
		val record = ArtPrimitiveJournal.commands(shoes.document.rigEdits).last()
		assertEquals(ArtPrimitiveV2.VERSION_V2, record["v"]?.jsonPrimitive?.int)
		assertTrue(RigCheckpoint.isRecord(shoes.document.rigEdits.authoringJournal.last()), "the split checkpoints the rig it merged")
		val puppet = shoes.model.rig.puppet
		for (id in ArtPrimitiveJournal.primitives(record).map { it.getValue("id").jsonPrimitive.content })
			assertEquals(true, puppet.drawables.single { it.id.raw == id }.parentDeformerId?.raw?.startsWith("DeformPair_"), id)
	}

	@Test fun theSkeletonSkinsAndWeldsSplitPartsThroughTheCheckpointItsSwitchAdds() = runBlocking<Unit> {
		val runtime = installed(document(figure + layer("sleeve", 4, intArrayOf(258, 120, 400, 148)),
			classified + ("sleeve" to LayerClassificationOverride(SemanticTag.HANDWEAR, Side.LEFT))))
		// The sleeve cut at the elbow: its parts are generated in its place.
		val parted = split(runtime, "source_split_polygon", buildJsonObject {
			put("layer_id", "sleeve"); putJsonArray("names") { add("Upper"); add("Fore") }; putJsonArray("piece_ids") { add("upper"); add("fore") }
			putJsonArray("polygon") { listOf(250 to 100, 330 to 100, 330 to 170, 250 to 170).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
		})
		val record = ArtPrimitiveJournal.commands(parted.document.rigEdits).single()
		assertEquals(ArtPrimitiveV2.VERSION_V2, record["v"]?.jsonPrimitive?.int)
		val (upper, fore) = ArtPrimitiveJournal.primitives(record).map { it.getValue("id").jsonPrimitive.content }
		val spec = SkeletonSpec(bones = listOf(
			SkeletonBone("armL", "Upper arm L", null, BoneRole.UPPER_ARM, Side.LEFT, 262f, 134f, 330f, 134f, listOf(upper)),
			SkeletonBone("foreL", "Forearm L", "armL", BoneRole.FOREARM, Side.LEFT, 330f, 134f, 398f, 134f, listOf(fore)),
		))
		val model = builder.build(parted.document.copy(rigEdits = parted.document.rigEdits.copy(skeleton = spec)), parted.model)
		assertTrue(RigCheckpoint.isRecord(model.config.rigEdits.authoringJournal.last()), "the skeleton checkpoints the generation it changes")
		val puppet = model.rig.puppet
		assertEquals("DeformSkel_armL", puppet.drawables.single { it.id.raw == upper }.parentDeformerId?.raw)
		assertEquals("DeformSkel_foreL", puppet.drawables.single { it.id.raw == fore }.parentDeformerId?.raw)
		val weld = puppet.glues.singleOrNull { it.id == "GlueSkel__${upper}__$fore" || it.id == "GlueSkel__${fore}__$upper" }
		assertTrue(weld != null && weld.pairs.size >= 3, "the parts are welded at the cut: ${puppet.glues.map { it.id }}")

		// A bone moved past that trailing checkpoint rebakes, rather than building from the checkpoint's rig.
		val elbow = SkeletonSpec(bones = spec.bones.map { bone ->
			when (bone.id) {
				"armL" -> bone.copy(tailX = 340f)
				"foreL" -> bone.copy(headX = 340f)
				else -> bone
			}
		})
		val moved = builder.build(model.config.let { parted.document.copy(rigEdits = it.rigEdits.copy(skeleton = elbow)) }, model)
		val journal = model.config.rigEdits.authoringJournal
		assertEquals(journal, moved.config.rigEdits.authoringJournal.subList(0, journal.size))
		assertTrue(RigCheckpoint.isRecord(moved.config.rigEdits.authoringJournal.last()), "the move checkpoints the generation it changes")
		fun pivot(rig: RigPreviewModel) = FloatArray(2).also { SkeletonRig.worlds(rig.rig.puppet, emptyMap()).getValue(org.umamo.runtime.model.DeformerId("DeformSkel_foreL")).apply(0f, 0f, it, 0) }
		assertEquals(330f, pivot(model)[0], 0.01f)
		assertEquals(340f, pivot(moved)[0], 0.01f)
		assertNotNull(moved.rig.puppet.glues.singleOrNull { it.id == weld.id })
	}
}
