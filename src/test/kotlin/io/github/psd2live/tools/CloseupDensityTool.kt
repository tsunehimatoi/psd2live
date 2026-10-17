package io.github.psd2live.tools

import io.github.psd2live.core.IrGeometryEvaluator
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * How many pixels of art each planner close-up holds: the texel density (texels per canvas unit, as NodeDatasetTool
 * measures it) over a close-up's meshes at rest, times its side. CLOSEUP_FRAMES is plan4_data.py's frames.jsonl (one
 * line per model: {"scene", "key", "model", "closeups": [{"id", "frame": [left, top, side] in canvas units,
 * "meshes"}]}); CLOSEUP_ROOTS maps its scene dirs to model roots ("scene=D:/models/a;scene_col=D:/models/b"). Writes
 * build/tools/closeups/density.jsonl ({"scene", "key", "id", "side", "density"}). Big models need a larger test heap
 * (-Ppsd2live.testHeap=20g).
 */
class CloseupDensityTool {
	private val threads = setting("PARTS_THREADS", "4").toInt()

	@Test fun density() {
		requireTools()
		val roots = setting("CLOSEUP_ROOTS", "").split(';').filter { '=' in it }.associate { it.substringBefore('=') to File(it.substringAfter('=')) }
		val frames = File(setting("CLOSEUP_FRAMES", "build/tools/plan_v4/frames.jsonl")).readLines().filter { it.isNotBlank() }
		val pool = Executors.newFixedThreadPool(threads)
		val lines = frames.map { line ->
			pool.submit<String> {
				val job = Json.parseToJsonElement(line).jsonObject
				val scene = job["scene"]!!.jsonPrimitive.content; val key = job["key"]!!.jsonPrimitive.content
				runCatching {
					val ir = loadRig(File(roots[scene] ?: error("no root for $scene"), job["model"]!!.jsonPrimitive.content)).ir
					val session = IrGeometryEvaluator.open(ir)
					val base = session.evaluate(emptyMap())
					session.close()
					job["closeups"]!!.jsonArray.joinToString("") { c ->
						val o = c.jsonObject
						val side = o["frame"]!!.jsonArray[2].jsonPrimitive.float
						val density = texelDensity(ir, base, o["meshes"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
						"""{"scene":"$scene","key":"$key","id":"${o["id"]!!.jsonPrimitive.content}","side":$side,"density":$density}""" + "\n"
					}
				}.getOrElse { println("$scene/$key error: ${it.javaClass.simpleName}: ${it.message?.take(160)}"); "" }
			}
		}.map { it.get() }
		pool.shutdown()
		File(output("closeups"), "density.jsonl").writeText(lines.joinToString(""))
	}

	private fun texelDensity(ir: RigIR, pose: PoseGeometry, ids: Set<String>): Float {
		var uvArea = 0.0; var posedArea = 0.0
		for (mesh in ir.meshes) {
			if (mesh.id !in ids) continue
			val g = mesh.geometry ?: continue
			val page = ir.textures.pages.getOrNull(ir.textures.bindings[mesh.id] ?: mesh.page) ?: continue
			val uv = g.uvs.shared(); val p = pose.positions[mesh.id] ?: continue; val idx = g.indices.shared()
			for (t in 0 until idx.size / 3) {
				val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
				uvArea += abs(tri(uv[a * 2] * page.width, uv[a * 2 + 1] * page.height, uv[b * 2] * page.width, uv[b * 2 + 1] * page.height, uv[c * 2] * page.width, uv[c * 2 + 1] * page.height))
				posedArea += abs(tri(p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1], p[c * 2], p[c * 2 + 1]))
			}
		}
		return if (posedArea <= 0.0) 1f else kotlin.math.sqrt(uvArea / posedArea).toFloat()
	}

	private fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)) / 2.0
}
