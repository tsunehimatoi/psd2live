package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlin.test.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

/** Follow surface, corner correction and turn in depth on a fill with a smaller line layer drawn over it. */
class DepthAndFollowKindsTest {
	init { TestKinds.register() }

	private val preview: RigPreviewModel by lazy {
		fun layer(id: String, x: Int, y: Int, w: Int, h: Int, order: Int): WorkspaceSourceLayer {
			val raster = LayerRaster(w, h, ByteArray(w * h * 4) { if (it % 4 == 3) 255.toByte() else 140.toByte() })
			return WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order, LayerBounds(x, y, w, h), 1f, false, LayerBlend.Normal,
				ChannelMask.ALL, raster, null, null, false)
		}
		PSD2LivePipeline().buildPreview(WorkspaceSourceArt(200, 200, listOf(layer("fill", 20, 20, 160, 160, 0), layer("line", 80, 80, 40, 40, 1)), emptyList()),
			PipelineConfig(atlasSize = 512, meshSpacing = 16))
	}

	private val meshes by lazy { preview.authored.rig.puppet.drawables.filter { it.mesh != null }.sortedByDescending { it.mesh!!.vertexCount }.map { it.id.raw } }
	private val fill get() = meshes[0]
	private val line get() = meshes[1]

	private fun parameter(id: String) = buildJsonObject { put("id", id); put("name", id); put("min", -10); put("max", 10); put("default", 0) }

	private fun canvas(model: PuppetModel, mesh: String, pose: Map<String, Float>): FloatArray {
		val world = CpuDeformationEvaluator().evaluate(model, pose.mapKeys { ParameterId(it.key) }).worldPositions.getValue(DrawableId(mesh))
		return FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
	}

	private fun meanShift(a: FloatArray, b: FloatArray): Pair<Float, Float> {
		val n = a.size / 2
		return (0 until n).sumOf { (b[it * 2] - a[it * 2]).toDouble() }.toFloat() / n to (0 until n).sumOf { (b[it * 2 + 1] - a[it * 2 + 1]).toDouble() }.toFloat() / n
	}

	@Test
	fun `a layer drawn over another follows it without keys of its own`() {
		// The fill is keyed by hand: a push at +10, applied so it is the fill's own keyforms.
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), buildJsonObject {
			put("id", "Push"); put("kind", "transform"); putJsonArray("targets") { add(fill) }; putJsonArray("parameters") { add(parameter("ParamSide")) }
			putJsonArray("keys") {
				addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamSide", 0) }; putJsonArray("value") { add(0); add(0) } }
				addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamSide", 10) }; putJsonArray("value") { add(9); add(-3) } }
			}
		})
		overlay = ModifierAuthoring.apply(preview, overlay, buildJsonObject { put("id", "Push") })
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject { put("id", "Follow"); put("kind", "follow"); putJsonArray("targets") { add(line) } })
		assertEquals(fill, overlay.modifiers.single().options["host"], "it follows what it is drawn over")
		val model = overlay.applyTo(preview.baseRig.puppet)
		val (dx, dy) = meanShift(canvas(model, line, emptyMap()), canvas(model, line, mapOf("ParamSide" to 10f)))
		assertEquals(9f, dx, 0.2f); assertEquals(-3f, dy, 0.2f)
	}

	@Test
	fun `a corner becomes the sum of its two sides`() {
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), buildJsonObject {
			put("id", "Edges"); put("kind", "transform"); putJsonArray("targets") { add(fill) }
			putJsonArray("parameters") { add(parameter("ParamA")); add(parameter("ParamB")) }
			putJsonArray("keys") {
				addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamA", 0); put("ParamB", 0) }; putJsonArray("value") { add(0); add(0) } }
				addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamA", 10); put("ParamB", 0) }; putJsonArray("value") { add(10); add(0) } }
				addJsonObject { put("setting", "offset"); putJsonObject("at") { put("ParamA", 0); put("ParamB", 10) }; putJsonArray("value") { add(0); add(10) } }
			}
		})
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Corner"); put("kind", "corner"); putJsonArray("targets") { add(fill) }; putJsonObject("options") { put("x", "ParamA"); put("y", "ParamB") }
		})
		val model = overlay.applyTo(preview.baseRig.puppet)
		val rest = canvas(model, fill, emptyMap())
		val (dx, dy) = meanShift(rest, canvas(model, fill, mapOf("ParamA" to 10f, "ParamB" to 10f)))
		assertEquals(10f, dx, 0.2f); assertEquals(10f, dy, 0.2f)
		val (ex, ey) = meanShift(rest, canvas(model, fill, mapOf("ParamA" to 10f)))
		assertEquals(10f, ex, 0.2f, "one side alone is left as it was"); assertEquals(0f, ey, 0.2f)
	}

	@Test
	fun `a turn in depth moves what is in front further`() {
		var overlay = ModifierAuthoring.put(preview, RigEditOverlay(), buildJsonObject {
			put("id", "Hold"); put("kind", "transform"); putJsonArray("targets") { add(line) }; putJsonArray("parameters") { add(parameter("ParamTurn")) }
		})
		overlay = ModifierAuthoring.put(preview, overlay, buildJsonObject {
			put("id", "Turn"); put("kind", "turn3d"); putJsonArray("targets") { add(line) }; putJsonObject("options") { put("x", "ParamTurn") }
			putJsonObject("settings") { put("depth", 100); put("distance", 100000); putJsonArray("center") { add(100); add(100) } }
		})
		val model = overlay.applyTo(preview.baseRig.puppet)
		val (dx, _) = meanShift(canvas(model, line, emptyMap()), canvas(model, line, mapOf("ParamTurn" to 10f)))
		// Nearly orthographic: a point 100 px in front of the centre goes 100·sin 30° = 50 px across.
		assertEquals(50f, dx, 2f)
		val (back, _) = meanShift(canvas(model, line, emptyMap()), canvas(model, line, mapOf("ParamTurn" to -10f)))
		assertEquals(-50f, back, 2f)
	}
}
