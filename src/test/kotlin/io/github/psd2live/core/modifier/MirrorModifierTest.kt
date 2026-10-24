package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlin.math.hypot
import kotlin.test.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

class MirrorModifierTest {
	init { TestKinds.register() }

	/** Two layers side by side, the left one narrower than the right: a mirror must not need matching vertices. */
	private val preview: RigPreviewModel by lazy {
		fun layer(id: String, x: Int, w: Int): WorkspaceSourceLayer {
			val h = 96
			val raster = LayerRaster(w, h, ByteArray(w * h * 4) { if (it % 4 == 3) 255.toByte() else 140.toByte() })
			return WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0, LayerBounds(x, 20, w, h), 1f, false, LayerBlend.Normal,
				ChannelMask.ALL, raster, null, null, false)
		}
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(200, 140, listOf(layer("left", 10, 60), layer("right", 110, 80)), emptyList()),
			PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

	private val meshes by lazy { preview.authored.rig.puppet.drawables.filter { it.mesh != null }.sortedBy { d -> d.mesh!!.positions.filterIndexed { i, _ -> i % 2 == 0 }.average() }.map { it.id.raw } }

	private val driver = buildJsonObject { put("id", "ParamSide"); put("name", "Side"); put("min", -10); put("max", 10); put("default", 0) }

	private fun canvas(model: PuppetModel, mesh: String, pose: Map<String, Float>): FloatArray {
		val world = CpuDeformationEvaluator().evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(mesh))
		return FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
	}

	private fun distance(a: FloatArray, b: FloatArray) = (0 until a.size / 2).maxOf { hypot(a[it * 2] - b[it * 2], a[it * 2 + 1] - b[it * 2 + 1]) }

	/** A shift keyed on the kept side only: (12, 4) at +10, nothing at the default. */
	private fun keptSide(mesh: String) = buildJsonObject {
		put("id", "Push"); put("kind", "transform"); putJsonArray("targets") { add(mesh) }
		putJsonArray("parameters") { add(driver) }
		putJsonArray("keys") {
			addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamSide", 0) }; putJsonArray("value") { add(0); add(0) } }
			addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamSide", 10) }; putJsonArray("value") { add(12); add(4) } }
		}
	}

	@Test
	fun `the far side of a parameter is the kept side's displacement mirrored across the line`() {
		val mesh = meshes[1]
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), keptSide(mesh))
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Mirror"); put("kind", "mirror"); putJsonArray("targets") { add(mesh) }
			putJsonObject("options") { put("parameter", "ParamSide") }
		})
		assertEquals(mapOf("parameter" to "ParamSide"), overlay.modifiers.last().options)
		assertEquals(overlay.modifiers.last(), RigModifier.fromJson(overlay.modifiers.last().toJson()))
		val model = overlay.applyTo(preview.baseRig.puppet)
		val rest = canvas(model, mesh, emptyMap())
		val kept = canvas(model, mesh, mapOf("ParamSide" to 10f))
		val far = canvas(model, mesh, mapOf("ParamSide" to -10f))
		for (i in 0 until rest.size / 2) {
			assertEquals(12f, kept[i * 2] - rest[i * 2], 0.05f, "the kept side is left as it was")
			assertEquals(-12f, far[i * 2] - rest[i * 2], 0.05f, "x is mirrored")
			assertEquals(4f, far[i * 2 + 1] - rest[i * 2 + 1], 0.05f, "y is kept")
		}
		// Halfway keeps the same proportion: the keyforms sampled hold the exact mirror between keys too.
		val half = canvas(model, mesh, mapOf("ParamSide" to -5f))
		assertEquals(-6f, half[0] - rest[0], 0.1f)
		val plain = ModifierSampler.withCreated(RigEditOverlay().applyTo(preview.baseRig.puppet), overlay.modifiers)
		val (stack, _) = ModifierSampler.stack(plain, mesh, overlay.modifiers)!!
		assertTrue(distance(stack.canvasAt(mapOf("ParamSide" to -7.5f)), canvas(model, mesh, mapOf("ParamSide" to -7.5f))) < 1.05f)
	}

	@Test
	fun `a new mirror takes a two-sided parameter, and the side to keep can be turned`() {
		val mesh = meshes[1]
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), keptSide(mesh))
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject { put("id", "Mirror"); put("kind", "mirror"); putJsonArray("targets") { add(mesh) } })
		assertEquals("ParamSide", overlay.modifiers.last().options["parameter"], "the parameter its target is keyed on")
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject { put("id", "Mirror"); putJsonObject("options") { put("side", "negative") } })
		val model = overlay.applyTo(preview.baseRig.puppet)
		val rest = canvas(model, mesh, emptyMap())
		assertTrue(distance(canvas(model, mesh, mapOf("ParamSide" to -10f)), rest) < 0.05f, "keeping the negative side, nothing is there")
		assertTrue(distance(canvas(model, mesh, mapOf("ParamSide" to 10f)), rest) < 0.05f, "and the positive side, its mirror, loses the push")
		assertFailsWith<IllegalArgumentException> {
			ModifierAuthoring.put(preview, overlay, buildJsonObject { put("id", "Mirror"); putJsonObject("options") { put("side", "up") } })
		}
	}

	@Test
	fun `a stack written as a stage gets a warp of its own and leaves the mesh's keyforms alone`() {
		val mesh = meshes[1]
		val overlay = ModifierAuthoring.put(preview, RigEditOverlay(), JsonObject(keptSide(mesh) + ("output" to JsonPrimitive("stage"))))
		assertEquals(ModifierOutput.STAGE, overlay.modifiers.single().output)
		val model = overlay.applyTo(preview.baseRig.puppet)
		val drawable = model.drawables.single { it.id.raw == mesh }
		assertEquals(ModifierStage.id(mesh), drawable.parentDeformerId?.raw, "the mesh hangs under its stage")
		assertTrue(drawable.geometryGrid?.axes.orEmpty().none { it.parameterId.raw == "ParamSide" }, "the mesh is not keyed on the modifier's parameter")
		val warp = model.deformers.single { it.id.raw == ModifierStage.id(mesh) } as Deformer.Warp
		assertEquals(listOf("ParamSide"), warp.geometryGrid!!.axes.map { it.parameterId.raw })
		val plain = RigEditOverlay().applyTo(preview.baseRig.puppet)
		assertTrue(distance(canvas(plain, mesh, emptyMap()), canvas(model, mesh, emptyMap())) < 0.05f, "at rest the stage moves nothing")
		val at = canvas(model, mesh, mapOf("ParamSide" to 10f))
		val rest = canvas(model, mesh, emptyMap())
		for (i in 0 until rest.size / 2) assertEquals(12f, at[i * 2] - rest[i * 2], 0.1f)
		assertEquals(RigStages.Owner(RigStages.Owner.Kind.MODIFIERS, ""), RigStages.chain(model, overlay, mesh)[1].owner)
		assertEquals(overlay.modifiers.single(), RigModifier.fromJson(overlay.modifiers.single().toJson()))
		// Left to itself the same small stack merges into the mesh.
		val merged = ModifierAuthoring.put(preview, RigEditOverlay(), keptSide(mesh)).applyTo(preview.baseRig.puppet)
		assertNull(merged.deformers.firstOrNull { it.id.raw == ModifierStage.id(mesh) })
	}

	@Test
	fun `a mirror from another mesh takes its displacement over the whole range`() {
		val (left, right) = meshes
		// The left mesh keyed by hand: its keyforms are what the right one mirrors.
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), keptSide(left))
		overlay = ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Push") })
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Mirror"); put("kind", "mirror"); putJsonArray("targets") { add(right) }
			putJsonObject("options") { put("parameter", "ParamSide"); put("source", left) }
			putJsonObject("settings") { put("axis", 100) }
		})
		val model = overlay.applyTo(preview.baseRig.puppet)
		val rest = canvas(model, right, emptyMap())
		val at = canvas(model, right, mapOf("ParamSide" to 10f))
		for (i in 0 until rest.size / 2) {
			assertEquals(-12f, at[i * 2] - rest[i * 2], 0.1f)
			assertEquals(4f, at[i * 2 + 1] - rest[i * 2 + 1], 0.1f)
		}
	}
}
