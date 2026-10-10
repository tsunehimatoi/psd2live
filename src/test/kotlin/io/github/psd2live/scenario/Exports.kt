package io.github.psd2live.scenario

import org.umamo.runtime.model.PuppetModel

/**
 * The exported moc3, read back, shows what the editor shows: the same meshes, deformed alike and with the same
 * opacity at rest and with each of the first parameters at its ends.
 */
internal fun exportMatchesEditor(studio: Studio, exported: PuppetModel = studio.moc3(), what: String = "moc3") = with(studio) {
	val editor = puppet
	val ids = editor.drawables.map { it.id }.toSet()
	expect("export $what", exported.drawables.map { it.id }.toSet() == ids) {
		"exported meshes differ: missing ${ids - exported.drawables.map { it.id }.toSet()}, extra ${exported.drawables.map { it.id }.toSet() - ids}"
	}
	val poses = listOf(emptyMap<String, Float>()) + editor.parameters.filter { it.max > it.min }.take(32)
		.flatMap { p -> listOf(mapOf(p.id.raw to p.min), mapOf(p.id.raw to p.max)) }
	for (pose in poses) difference(editor, exported, pose)?.let { problem -> expect("export $what", false) { "at $pose $problem" } }
}

private fun difference(a: PuppetModel, b: PuppetModel, pose: Map<String, Float>, tolerance: Float = 0.5f): String? {
	val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
	val values = pose.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }
	val x = evaluator.evaluate(a, values); val y = evaluator.evaluate(b, values)
	for ((id, p) in x.worldPositions) {
		val q = y.worldPositions[id] ?: return "mesh ${id.raw} is not shown by the export"
		if (q.size != p.size) return "mesh ${id.raw} has ${q.size / 2} vertices in the export, ${p.size / 2} in the editor"
		val worst = p.indices.maxOfOrNull { kotlin.math.abs(p[it] - q[it]) } ?: 0f
		if (worst > tolerance) return "mesh ${id.raw} is $worst px from the editor's"
		val o = x.opacity[id] ?: 1f; val r = y.opacity[id] ?: 1f
		if (kotlin.math.abs(o - r) > 0.01f) return "mesh ${id.raw} has opacity $r in the export, $o in the editor"
	}
	return null
}
