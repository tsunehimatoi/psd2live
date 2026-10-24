package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

class ModifierStackTest {
	init { TestKinds.register() }

	@TempDir lateinit var directory: Path

	private val preview: RigPreviewModel by lazy {
		val w = 96
		val h = 144
		val raster = LayerRaster(w, h, ByteArray(w * h * 4) { if (it % 4 == 3) 255.toByte() else 140.toByte() })
		val layer = WorkspaceSourceLayer(LayerId("cloth"), "cloth", "", SourceLayerKind.Raster, true, 0, LayerBounds(12, 8, w, h),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(128, 168, listOf(layer), emptyList()), PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

	private val target: String by lazy { preview.authored.rig.puppet.drawables.first { it.mesh != null }.id.raw }

	private val driver = buildJsonObject { put("id", "ParamModTurn"); put("name", "Turn"); put("min", -10); put("max", 10); put("default", 0) }

	private fun canvas(model: PuppetModel, pose: Map<String, Float>): FloatArray {
		val world = CpuDeformationEvaluator().evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(target))
		return FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
	}

	private fun distance(a: FloatArray, b: FloatArray) = (0 until a.size / 2).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }

	private fun turn(id: String, degrees: Float, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
		put("id", id); put("kind", "transform")
		putJsonArray("targets") { add(target) }
		putJsonArray("keys") {
			addJsonObject { put("setting", "rotation"); putJsonObject("at") { put("ParamModTurn", -10) }; put("value", -degrees) }
			addJsonObject { put("setting", "rotation"); putJsonObject("at") { put("ParamModTurn", 10) }; put("value", degrees) }
		}
		extra()
	}

	@Test
	fun `a value gains keys and axes, interpolates between them and loses them again`() {
		val constant = ModifierValue.Constant(floatArrayOf(2f))
		val one = ModifierValue.keyed(emptyList(), listOf(constant.value), mapOf("A" to 10f), floatArrayOf(6f))
		assertContentEquals(floatArrayOf(6f), one.at { 0f }, "a single key holds everywhere")
		val two = one.withKey(mapOf("A" to 0f), floatArrayOf(2f))
		assertEquals(4f, two.at { 5f }[0], 1e-5f)
		assertEquals(6f, two.at { 30f }[0], 1e-5f, "held past the last key")
		val grid = two.withKey(mapOf("A" to 10f, "B" to 1f), floatArrayOf(10f))
		assertEquals(2, grid.axes.size)
		assertEquals(2f, grid.at { if (it == "A") 0f else 1f }[0], 1e-5f, "a new axis takes what the grid showed elsewhere")
		assertEquals(10f, grid.at { if (it == "A") 10f else 0f }[0], 1e-5f, "a single key on the new axis holds along it")
		assertEquals(6f, grid.at { if (it == "A") 5f else 1f }[0], 1e-5f)
		assertEquals(grid, ModifierValue.fromJson(ModifierValue.toJson(grid)))
		val dropped = assertIs<ModifierValue.Keyed>(grid.withoutKey(mapOf("B" to 1f)))
		assertEquals(listOf("A"), dropped.parameters)
		assertEquals(ModifierValue.Constant(floatArrayOf(2f)), dropped.withoutKey(mapOf("A" to 10f))?.let { (it as ModifierValue.Keyed).withoutKey(mapOf("A" to 0f)) })
	}

	@Test
	fun `a keyed turn is sampled into keyforms that follow it on and between its keys`() {
		val overlay = ModifierAuthoring.put(preview, RigEditOverlay(), JsonObject(turn("Turn", 40f) + ("parameters" to JsonArray(listOf(driver)))))
		val modifier = overlay.modifiers.single()
		assertEquals(listOf("ParamModTurn"), modifier.drivers)
		val plain = RigEditOverlay().applyTo(preview.baseRig.puppet)
		val model = overlay.applyTo(preview.baseRig.puppet)
		assertTrue(model.parameters.any { it.id.raw == "ParamModTurn" })
		val axis = model.drawables.single { it.id.raw == target }.geometryGrid!!.axes.single { it.parameterId.raw == "ParamModTurn" }
		assertTrue(axis.keys.size > 3, "keys are added between -10, 0 and 10 for the arc: ${axis.keys.toList()}")
		assertTrue(distance(canvas(plain, emptyMap()), canvas(model, emptyMap())) < 1e-3f, "the default pose is untouched")
		val (stack, _) = ModifierSampler.stack(ModifierSampler.withCreated(plain, overlay.modifiers), target, overlay.modifiers)!!
		for (v in listOf(10f, 5f, -3.3f, 7.1f)) {
			val exact = stack.canvasAt(mapOf("ParamModTurn" to v))
			assertTrue(distance(exact, canvas(model, mapOf("ParamModTurn" to v))) <= modifier.sampling.tolerance + 0.05f, "at $v")
		}
		assertTrue(ModifierSampler.issues(plain, overlay.modifiers).isEmpty())
		assertTrue(DocumentGenerators.keyform("mesh", target, "ParamModTurn") in DocumentGenerators.graph(overlay).order.single { it.id == ModifierSampler.NODE }.writes)
	}

	@Test
	fun `modifiers on one mesh chain in stack order`() {
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), JsonObject(turn("Turn", 90f) + ("parameters" to JsonArray(listOf(driver)))))
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Shift"); put("kind", "transform"); putJsonArray("targets") { add(target) }
			putJsonArray("keys") { addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamModTurn", 10) }; putJsonArray("value") { add(30); add(0) } } }
		})
		val plain = RigEditOverlay().applyTo(preview.baseRig.puppet)
		val turnFirst = canvas(overlay.applyTo(preview.baseRig.puppet), mapOf("ParamModTurn" to 10f))
		val moved = ModifierAuthoring.move(overlay, buildJsonObject { put("id", "Shift"); put("to", "first") })
		assertEquals(listOf("Shift", "Turn"), moved.modifiers.map { it.id })
		val shiftFirst = canvas(moved.applyTo(preview.baseRig.puppet), mapOf("ParamModTurn" to 10f))
		assertTrue(distance(turnFirst, shiftFirst) > 10f, "turning then moving is not moving then turning")
		for ((o, shown) in listOf(overlay to turnFirst, moved to shiftFirst)) {
			val exact = ModifierSampler.stack(ModifierSampler.withCreated(plain, o.modifiers), target, o.modifiers)!!.first.canvasAt(mapOf("ParamModTurn" to 10f))
			assertTrue(distance(exact, shown) < 0.05f)
		}
	}

	@Test
	fun `applying writes the same rig into the journal`() {
		val overlay = ModifierAuthoring.put(preview, RigEditOverlay(), JsonObject(turn("Turn", 40f) + ("parameters" to JsonArray(listOf(driver)))))
		val shown = overlay.applyTo(preview.baseRig.puppet)
		val applied = ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Turn") })
		assertTrue(applied.modifiers.isEmpty())
		val replayed = applied.applyTo(preview.baseRig.puppet)
		for (v in listOf(-10f, -2.5f, 0f, 6f, 10f))
			assertTrue(distance(canvas(shown, mapOf("ParamModTurn" to v)), canvas(replayed, mapOf("ParamModTurn" to v))) < 1e-2f, "at $v")
		val rest = ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Turn"); put("mode", "rest"); putJsonObject("pose") { put("ParamModTurn", 10) } })
		val frozen = rest.applyTo(preview.baseRig.puppet)
		assertTrue(frozen.parameters.none { it.id.raw == "ParamModTurn" }, "the rest shape keeps no parameter nothing uses")
		assertTrue(distance(canvas(shown, mapOf("ParamModTurn" to 10f)), canvas(frozen, emptyMap())) < 1e-2f)
	}

	@Test
	fun `modifiers save, restore and replay with the project`() = runBlocking {
		val overlay = ModifierAuthoring.put(preview, RigEditOverlay(), JsonObject(turn("Turn", 40f) + ("parameters" to JsonArray(listOf(driver)))))
		assertEquals(overlay.modifiers.single(), RigModifier.fromJson(overlay.modifiers.single().toJson()))
		val base = preview.authored.rig
		val before = WorkspaceDocument(preview.analysis.source as WorkspaceSourceArt, emptyMap(), emptySet(), emptyMap(), emptyMap(),
			RigEditOverlay(authoringJournal = listOf(RigCheckpoint.encode(AuthoredRig(base, emptyList()), "modifier-test"))))
		val after = before.copy(rigEdits = before.rigEdits.copy(modifiers = overlay.modifiers))
		assertNotEquals(WorkspaceRevisions.of(before), WorkspaceRevisions.of(after))
		assertFalse(WorkspaceRevisions.reference(before).isEmpty())
		val revision = WorkspaceRevisions.of(before)
		val history = WorkspaceHistoryTree(before, revision, revision)
		val first = history.head().node.id
		val next = WorkspaceRevisions.of(after)
		history.commit(first, after, next, next, "Modifier", "user")
		val store = WorkspaceStore(directory.resolve("store"))
		store.persistHistory("modifier", history.state())
		val file = directory.resolve("modifier.psd2live")
		ProjectRepository(writeHeadCache = false).save(ProjectSaveCapture("modifier", history.state(), JsonObject(emptyMap()), null, store), file)
		ProjectRepository(writeHeadCache = false).open(file).use { opened ->
			val loaded = opened.history.head().snapshot
			assertEquals(next, WorkspaceRevisions.of(loaded))
			assertEquals(overlay.modifiers, loaded.rigEdits.modifiers)
			assertTrue(opened.history.selectionAt(first).snapshot.rigEdits.modifiers.isEmpty())
			assertTrue(loaded.rigEdits.applyTo(base.puppet).drawables.single { it.id.raw == target }.geometryGrid!!.axes.any { it.parameterId.raw == "ParamModTurn" })
		}
		assertEquals(ProjectFormatV2.NODE_SCHEMA_MODIFIERS, ProjectFormatV2.schemaOf("rig", buildJsonObject {
			putJsonObject("rigEdits") { put("modifiers", JsonArray(overlay.modifiers.map { it.toJson() })) }
		}))
		assertEquals(ProjectFormatV2.NODE_SCHEMA, ProjectFormatV2.schemaOf("rig", buildJsonObject { putJsonObject("rigEdits") {} }))
	}

	@Test
	fun `deleting a modifier drops its parameter unless something else is keyed on it`() {
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), JsonObject(turn("Turn", 40f) + ("parameters" to JsonArray(listOf(driver)))))
		assertTrue(ModifierAuthoring.delete(preview, overlay, "Turn").let { it.modifiers.isEmpty() && it.authoringJournal.isEmpty() })
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Shift"); put("kind", "transform"); putJsonArray("targets") { add(target) }
			putJsonArray("keys") { addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamModTurn", 10) }; putJsonArray("value") { add(30); add(0) } } }
		})
		val kept = ModifierAuthoring.delete(preview, overlay, "Turn")
		assertEquals(listOf("Shift"), kept.modifiers.map { it.id })
		assertTrue(kept.applyTo(preview.baseRig.puppet).parameters.any { it.id.raw == "ParamModTurn" }, "the other modifier still uses it")
		assertEquals(listOf("structure"), kept.authoringJournal.map { it.getValue("op").jsonPrimitive.content }, "kept as an ordinary parameter")
		val missing = ModifierSampler.issues(RigEditOverlay().applyTo(preview.baseRig.puppet), kept.modifiers)
		assertEquals(listOf(ModifierSampler.IssueKind.PARAMETER_MISSING), missing.map { it.kind }, "without its parameter the stack reports, not fails")
	}

	private val pair: RigPreviewModel by lazy {
		fun layer(id: String, x: Int) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0, LayerBounds(x, 8, 48, 120),
			1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(48, 120, ByteArray(48 * 120 * 4) { if (it % 4 == 3) 255.toByte() else 120.toByte() }), null, null, false)
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(160, 168, listOf(layer("left", 12), layer("right", 90)), emptyList()), PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

	@Test
	fun `a part target follows its meshes, and one mesh can be applied, split off or copied to`() {
		val authored = pair.authored.rig.puppet
		val meshes = authored.drawables.filter { it.mesh != null }.map { it.id.raw }
		assertEquals(2, meshes.size)
		val part = authored.parts.first { p -> ModifierTarget(part = p.id.raw).resolve(authored).toSet() == meshes.toSet() }.id.raw
		val shift = buildJsonObject {
			put("id", "Shift"); put("kind", "transform"); put("part", part)
			putJsonArray("parameters") { add(driver) }
			putJsonArray("keys") { addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamModTurn", 10) }; putJsonArray("value") { add(20); add(0) } } }
		}
		val overlay = ModifierAuthoring.put(pair, RigEditOverlay(), shift)
		assertEquals(meshes.toSet(), ModifierSampler.stacks(overlay.modifiers, authored).keys)
		assertTrue(DocumentGenerators.graph(overlay, model = authored).order.single { it.id == ModifierSampler.NODE }.writes
			.containsAll(meshes.map { DocumentGenerators.keyform("mesh", it, "ParamModTurn") }))
		val model = overlay.applyTo(pair.baseRig.puppet)
		for (m in meshes) assertTrue(model.drawables.single { it.id.raw == m }.geometryGrid!!.axes.any { it.parameterId.raw == "ParamModTurn" })

		val one = ModifierAuthoring.apply(pair, overlay, buildJsonObject { put("id", "Shift"); put("mesh", meshes[0]) })
		assertEquals(setOf(meshes[0]), one.modifiers.single().target.excluded)
		val replayed = one.applyTo(pair.baseRig.puppet)
		for (m in meshes) assertEquals(model.drawables.single { it.id.raw == m }.geometryGrid!!.axes.map { it.parameterId.raw }.toSet(),
			replayed.drawables.single { it.id.raw == m }.geometryGrid!!.axes.map { it.parameterId.raw }.toSet(), m)

		val split = ModifierAuthoring.split(pair, overlay, buildJsonObject { put("id", "Shift"); put("mesh", meshes[1]); put("new_id", "Own") })
		assertEquals(listOf("Shift", "Own"), split.modifiers.map { it.id })
		assertEquals(listOf(meshes[1]), split.modifiers[1].target.meshes)
		assertEquals(setOf(meshes[0]), ModifierSampler.stacks(split.modifiers.take(1), authored).keys)
		val copied = ModifierAuthoring.copy(pair, overlay.copy(modifiers = listOf(overlay.modifiers.single().copy(target = ModifierTarget(listOf(meshes[0]))))),
			buildJsonObject { put("id", "Shift"); putJsonArray("targets") { add(meshes[1]) } })
		assertEquals(2, copied.modifiers.size)
		assertTrue(copied.modifiers[1].created.isEmpty(), "the copy keys the original's parameter, it makes none")
		assertEquals(copied.modifiers[0], RigModifier.fromJson(copied.modifiers[0].toJson()))
		assertEquals(overlay.modifiers.single(), RigModifier.fromJson(overlay.modifiers.single().toJson()))
	}

	@Test
	fun `a preset puts the same stack on another mesh and reuses parameters that exist`() {
		val authored = pair.authored.rig.puppet
		val meshes = authored.drawables.filter { it.mesh != null }.map { it.id.raw }
		var overlay = ModifierAuthoring.put(pair, RigEditOverlay(), buildJsonObject {
			put("kind", "transform"); putJsonArray("targets") { add(meshes[0]) }; putJsonArray("parameters") { add(driver) }
			putJsonArray("keys") { addJsonObject { put("setting", "rotation"); putJsonObject("at") { put("ParamModTurn", 10) }; put("value", 6) } }
		})
		val preset = ModifierPreset.fromJson(ModifierPreset.of("hair", overlay.modifiers).toJson())
		val requests = preset.requests(listOf(meshes[1]), authored.parameters.map { it.id.raw }.toSet() + "ParamModTurn")
		assertFalse("parameters" in requests.single(), "a parameter that exists is not made again")
		for (r in requests) overlay = ModifierAuthoring.put(pair, overlay, r)
		val (first, second) = overlay.modifiers
		assertEquals(first.settings, second.settings)
		assertEquals(listOf(meshes[1]), second.target.meshes)
		assertTrue("parameters" in preset.requests(listOf(meshes[1]), emptySet()).single(), "where it is missing the preset makes it")
	}

	@Test
	fun `a stack reads the mesh as its keyforms pose it, so applying the first keeps the rest`() {
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), JsonObject(turn("Turn", 60f) + ("parameters" to JsonArray(listOf(driver)))))
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Shift"); put("kind", "transform"); putJsonArray("targets") { add(target) }
			putJsonArray("keys") { addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamModTurn", 10) }; putJsonArray("value") { add(25); add(5) } } }
		})
		val shown = overlay.applyTo(preview.baseRig.puppet)
		val applied = ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Turn") })
		assertEquals(listOf("Shift"), applied.modifiers.map { it.id })
		val replayed = applied.applyTo(preview.baseRig.puppet)
		for (v in listOf(-10f, -4f, 0f, 3.3f, 10f))
			assertTrue(distance(canvas(shown, mapOf("ParamModTurn" to v)), canvas(replayed, mapOf("ParamModTurn" to v))) < 0.05f, "at $v")
		assertFailsWith<IllegalArgumentException>("a modifier after another applies only after it") {
			ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Shift") })
		}
	}

	@Test
	fun `a warp target moves its control points and everything it carries, and applies to the warp's keyforms`() {
		val authored = preview.authored.rig.puppet
		val deformers = authored.deformers.associateBy { it.id }
		val warp = generateSequence(authored.drawables.single { it.id.raw == target }.parentDeformerId) { deformers[it]?.parent }
			.first { deformers[it] is Deformer.Warp }.raw
		val overlay = ModifierAuthoring.put(preview, RigEditOverlay(), buildJsonObject {
			put("id", "Slide"); put("kind", "transform"); putJsonArray("targets") { add("warp:$warp") }; putJsonArray("parameters") { add(driver) }
			putJsonArray("keys") { addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamModTurn", 10) }; putJsonArray("value") { add(20); add(0) } } }
		})
		assertTrue(DocumentGenerators.keyform("warp", warp, "ParamModTurn") in DocumentGenerators.graph(overlay).order.single { it.id == ModifierSampler.NODE }.writes)
		val plain = RigEditOverlay().applyTo(preview.baseRig.puppet)
		val shown = overlay.applyTo(preview.baseRig.puppet)
		assertTrue((shown.deformers.single { it.id.raw == warp } as Deformer.Warp).geometryGrid!!.axes.any { it.parameterId.raw == "ParamModTurn" })
		val moved = canvas(shown, mapOf("ParamModTurn" to 10f)); val rest = canvas(plain, emptyMap())
		assertTrue(distance(moved, canvas(shown, mapOf("ParamModTurn" to -10f))) < 0.05f, "the axis spans the range: below its one key it holds, nothing vanishes")
		for (v in 0 until rest.size / 2) {
			assertEquals(rest[v * 2] + 20f, moved[v * 2], 0.05f)
			assertEquals(rest[v * 2 + 1], moved[v * 2 + 1], 0.05f)
		}
		val applied = ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Slide") })
		assertTrue(applied.modifiers.isEmpty())
		val replayed = applied.applyTo(preview.baseRig.puppet)
		for (v in listOf(-10f, 0f, 4f, 10f)) assertTrue(distance(canvas(shown, mapOf("ParamModTurn" to v)), canvas(replayed, mapOf("ParamModTurn" to v))) < 0.05f, "at $v")
	}
}
