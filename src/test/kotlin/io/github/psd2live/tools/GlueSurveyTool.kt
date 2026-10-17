package io.github.psd2live.tools

import io.github.psd2live.format.model.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * Every glue of the listed models, to see whether glued meshes are one drawn layer cut apart (like Cubism's skinning)
 * or separate layers joined at a seam. GLUE_MODELS is a file of model paths relative to PARTS_ROOT (one per line);
 * writes build/tools/glue/<GLUE_NAME, glue>.tsv: model, glue id, mesh A and B with their names and parts, vertex pairs,
 * each mesh's vertex count and texture page, and the median and 90th percentile distance between the glued vertices'
 * UVs (0 where one picture was cut apart).
 */
class GlueSurveyTool {
	private val root = File(setting("PARTS_ROOT", "D:/模型收藏/live2d"))
	private val name = setting("GLUE_NAME", "glue")
	private val threads = setting("PARTS_THREADS", "8").toInt()

	@Test fun glue() {
		requireTools()
		val list = File(setting("GLUE_MODELS", "build/tools/glue/models.txt")).readLines().filter { it.isNotBlank() }
		val pool = Executors.newFixedThreadPool(threads)
		val rows = list.map { model ->
			pool.submit<String> {
				runCatching { survey(model) }.getOrElse { "$model\terror\t${it.javaClass.simpleName}: ${it.message?.take(120)}\n" }
			}
		}.map { it.get() }
		pool.shutdown()
		File(output("glue"), "$name.tsv").writeText(
			"model\tglue\tmeshA\tnameA\tpartA\tmeshB\tnameB\tpartB\tpairs\tvertsA\tvertsB\tpageA\tpageB\tuvGap50\tuvGap90\n" + rows.joinToString(""))
	}

	private fun survey(model: String): String {
		val ir = loadRig(File(root, model)).ir
		val meshes = ir.meshes.associateBy { it.id }
		val partOf = HashMap<String, Part>()
		for (part in ir.parts) for (child in part.children) if (child is ChildRef.MeshRef) partOf[child.id] = part
		val out = StringBuilder()
		if (ir.glues.isEmpty()) out.append("$model\tnone\n")
		for (g in ir.glues) {
			val a = meshes[g.meshA]; val b = meshes[g.meshB]
			fun verts(m: Mesh?) = (m?.geometry?.positions?.size ?: 0) / 2
			// Where the glued vertices sit on the texture: a layer cut apart keeps one picture, so its cut edge has the
			// same UVs on both sides; layers drawn apart sit in different places.
			val ua = a?.geometry?.uvs; val ub = b?.geometry?.uvs
			val gaps = if (ua == null || ub == null) emptyList() else g.pairs.mapNotNull { p ->
				if (p.a * 2 + 1 >= ua.size || p.b * 2 + 1 >= ub.size) null
				else kotlin.math.hypot((ua[p.a * 2] - ub[p.b * 2]).toDouble(), (ua[p.a * 2 + 1] - ub[p.b * 2 + 1]).toDouble())
			}.sorted()
			fun pct(q: Double) = if (gaps.isEmpty()) "" else "%.5f".format(gaps[((gaps.size - 1) * q).toInt()])
			out.append(listOf(model, g.id ?: "", g.meshA, a?.name ?: "", partOf[g.meshA]?.name ?: "", g.meshB, b?.name ?: "",
				partOf[g.meshB]?.name ?: "", g.pairs.size, verts(a), verts(b), a?.page ?: -1, b?.page ?: -1, pct(0.5), pct(0.9))
				.joinToString("\t") { it.toString().replace('\t', ' ') }).append('\n')
		}
		return out.toString()
	}
}
