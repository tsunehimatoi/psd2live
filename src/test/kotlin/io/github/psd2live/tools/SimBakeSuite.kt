package io.github.psd2live.tools

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimBakeResult
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimScene
import io.github.psd2live.core.sim.SimVisualCheck
import io.github.psd2live.core.sim.SimVisualRun
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * Bakes each part of a sample ([SimBenchParts]) and plays motion the bake never trained on ([SimBenchMotions])
 * through the reference simulation and through the exported model - keys at gain 1 played by every pendulum the
 * export writes - and measures what a viewer sees ([SimVisualCheck]). Writes build/tools/sim-bake/<sample>/: a
 * summary.md table, a JSON per part and setting, and a PNG per part of the tips' travel, simulated solid and baked
 * dashed.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*SimBakeSuite'
 * PSD2LIVE_SAMPLE picks the PSD (tml by default), PSD2LIVE_SIM_PARTS the parts (all by default), PSD2LIVE_BAKE_CONFIGS
 * the settings as modes:keys,... ("preset" keeps each part's own), PSD2LIVE_BAKE_METHODS the bake methods (legacy,dof).
 */
class SimBakeSuite {
	/** Below this tip travel (px) in the simulation a motion counts as not moving the body. */
	private val SEEN_PX = 10f

	private class Played(val motion: String, val metrics: SimVisualCheck, val simulated: FloatArray, val baked: FloatArray, val seconds: Float)

	@Test fun run() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val wanted = setting("PSD2LIVE_SIM_PARTS", "").split(',').map(String::trim).filter(String::isNotEmpty).toSet()
		val parts = SimBenchParts.load(sample).filter { wanted.isEmpty() || it.name in wanted }
		for (part in parts) println("sim-bench: part ${part.name}: ${part.edit.targets.size} meshes, ${part.edit.modes} modes x ${part.edit.keys} keys, material ${part.edit.material.toJson()}, inputs ${part.edit.inputs.map { it.parameter }}")
		val configs = setting("PSD2LIVE_BAKE_CONFIGS", "preset").split(',').map(String::trim)
		val methods = setting("PSD2LIVE_BAKE_METHODS", "legacy,dof").split(',').map { SimBaker.Method.valueOf(it.trim().uppercase()) }
		val out = output("sim-bake/${sample.name}")
		val report = StringBuilder("# Sim bake suite: ${sample.name}\n\n")
		for (part in parts) for (config in configs) for (method in methods) {
			val configured = if (config == "preset") part else config.split(':').let { (m, k) -> part.with(part.edit.copy(modes = m.toInt(), keys = k.toInt())) }
			// PSD2LIVE_SIM_INPUTS keeps only the inputs it names, to see what each does on its own.
			val only = setting("PSD2LIVE_SIM_INPUTS", "").split(',').map(String::trim).filter(String::isNotEmpty)
			val p = if (only.isEmpty()) configured else configured.with(configured.edit.copy(inputs = configured.edit.inputs.filter { it.parameter in only }))
			val label = "${p.name}-${p.edit.modes}x${p.edit.keys}-${method.name.lowercase()}"
			try {
				bench(p, label, method, out, report)
			} catch (failure: Exception) {
				println("sim-bench: $label failed: $failure")
				report.append("## $label\n\nfailed: ${failure.message}\n\n")
			}
			File(out, "summary.md").writeText(report.toString())
		}
		println("sim-bench: wrote ${File(out, "summary.md")}")
	}

	private fun bench(p: SimBenchPart, label: String, method: SimBaker.Method, out: File, report: StringBuilder) {
		val marks = ArrayList<Pair<Float, Long>>()
		val start = System.nanoTime()
		val bake = (if (method == SimBaker.Method.DIRECT) SimBaker::bake else SimBaker::bakeReference)(p.model, p.edit, SimBaker.Options(physicsFps = p.overlay.physicsFps, method = method,
			progress = { synchronized(marks) { marks += it to System.nanoTime() } }))
		val seconds = since(start) / 1000
		println("sim-bench: $label baked in %.2f s: fit %.3f, p95 %.1f px, peak %.2f, clipped %.3f, jerk %.2f".format(
			seconds, bake.fit, bake.maxErrorPx, bake.peak, bake.clipped, bake.jerk))

		// The baked rig at gain 1, and its pendulums as the export writes them.
		val plain = p.edit.copy(exaggeration = 1f, outputs = p.edit.outputs.mapValues { it.value.copy(gain = null) })
		val bakedOverlay = SimAuthoring.withBake(p.with(plain).overlay, plain.id, bake)
		val bakedEdit = bakedOverlay.simEdits.single { it.id == plain.id }
		val baked = bakedOverlay.applyTo(p.base, p.skins)
		val rules = SimGenerator.physicsRules(listOf(bakedEdit), baked.parameters.mapTo(HashSet()) { it.id.raw })
		val modes = bake.parameters.map { bakedEdit.outputId(it) to bakedEdit.outputRange(it) }

		val calibrated = SimScene.build(p.model, p.edit).also { it.calibrate(p.model) }
		val stretch = calibrated.solver.stretch
		val along = (0 until stretch.size).filter { calibrated.edgeAlong[it] > 0.5f }
		val edges = along.map { stretch.a[it] to stretch.b[it] }
		val rest = FloatArray(along.size) { stretch.rest[along[it]] }
		val motions = SimBenchMotions.all(p.model, p.overlay, p.edit.inputs.map { it.parameter })
		val played = motions.parallelStream().map { motion ->
			val t = System.nanoTime()
			val scene = SimScene.build(p.model, p.edit).also { it.adopt(calibrated); it.reset(p.model, emptyMap()) }
			val engine = PhysicsEngine(rules, PhysicsEngine.ranges(baked.parameters), p.overlay.physicsFps.toFloat())
			val evaluator = CpuDeformationEvaluator()
			val count = scene.state.count
			val simulated = ArrayList<FloatArray>(); val bakedRows = ArrayList<FloatArray>()
			val simulatedWorld = ArrayList<FloatArray>(); val bakedWorld = ArrayList<FloatArray>()
			val modeRows = ArrayList<FloatArray>()
			for (f in 0 until motion.frames) {
				val pose = motion.pose(f)
				scene.drive(p.model, pose, 1f / SimBenchMotions.FPS)
				val driven = engine.step(pose.mapKeys { it.key.raw }, 1f / SimBenchMotions.FPS)
				val world = evaluator.evaluate(baked, pose + driven.mapKeys { ParameterId(it.key) }).worldPositions
				val s = scene.state
				val c = cos(-s.frameAngle); val sn = sin(-s.frameAngle)
				val sw = FloatArray(count * 2) { if (it % 2 == 0) s.x[it / 2] else s.y[it / 2] }
				val bw = FloatArray(count * 2)
				for ((id, offset) in scene.offsets) world[id]?.copyInto(bw, offset * 2, 0, minOf(world.getValue(id).size, scene.vertexCounts.getValue(id) * 2))
				fun body(w: FloatArray) = FloatArray(count * 2) { k ->
					val i = k / 2; val dx = w[i * 2] - s.goalX[i]; val dy = w[i * 2 + 1] - s.goalY[i]
					if (k % 2 == 0) c * dx - sn * dy else sn * dx + c * dy
				}
				simulated += body(sw); bakedRows += body(bw); simulatedWorld += sw; bakedWorld += bw
				modeRows += FloatArray(modes.size) { (driven[modes[it].first] ?: 0f) / modes[it].second }
			}
			val metrics = SimVisualCheck.measure(SimVisualRun(simulated, bakedRows, simulatedWorld, bakedWorld, modeRows, motion.moving), edges, rest, SimBenchMotions.FPS)
			// PSD2LIVE_SIM_CSV names motions whose busiest tip, mode values and inputs go to a CSV, for a closer look.
			if (motion.name in setting("PSD2LIVE_SIM_CSV", "").split(',')) {
				val tip = (0 until count).maxBy { i -> simulated.sumOf { (it[i * 2] * it[i * 2] + it[i * 2 + 1] * it[i * 2 + 1]).toDouble() } }
				val names = motion.values.keys.toList()
				File(out, "$label-${motion.name.replace(':', '_')}.csv").writeText(buildString {
					append("frame,sim_x,sim_y,baked_x,baked_y,${modes.joinToString(",") { it.first }},${names.joinToString(",")}").append('\n')
					for (f in 0 until motion.frames) append("$f,${simulated[f][tip * 2]},${simulated[f][tip * 2 + 1]},${bakedRows[f][tip * 2]},${bakedRows[f][tip * 2 + 1]},${modeRows[f].joinToString(",")},${names.joinToString(",") { motion.values.getValue(it)[f].toString() }}").append('\n')
				})
			}
			// The tip that moves most, along the body's x then y, for the plot.
			val tip = (0 until count).maxBy { i -> simulated.sumOf { (it[i * 2] * it[i * 2] + it[i * 2 + 1] * it[i * 2 + 1]).toDouble() } }
			Played(motion.name, metrics, FloatArray(motion.frames * 2) { simulated[it / 2][tip * 2 + it % 2] },
				FloatArray(motion.frames * 2) { bakedRows[it / 2][tip * 2 + it % 2] }, (since(t) / 1000).toFloat())
		}.toList()

		// The report.
		report.append("## $label\n\n")
		report.append("bake %.2f s; stages: %s\n\n".format(seconds, stages(marks, start)))
		report.append("old readout (held out): fit %.3f, p95 %.1f px, peak %.2f, clipped %.3f, jerk %.2f\n\n".format(bake.fit, bake.maxErrorPx, bake.peak, bake.clipped, bake.jerk))
		report.append("modes: ${bake.modes.joinToString { "%s %.1f px %.0f%%".format(it.axis.parameter, it.amplitude, it.energy * 100) }}; pendulums: ${bake.pendulums.joinToString { "${it.id} (${it.segments.size} seg, inputs ${it.inputs.joinToString(" ") { i -> "${i.parameter}:${i.type.jsonName}:%.0f%s".format(i.weight, if (i.reflect) "r" else "") }})" }}\n\n")
		val keys = played.first().metrics.toMap().keys
		report.append("| motion | ${keys.joinToString(" | ")} |\n|---|${keys.joinToString("") { "---|" }}\n")
		for (run in played) report.append("| ${run.motion} | ${run.metrics.toMap().values.joinToString(" | ") { format(it) }} |\n")
		// Over the motions the simulation visibly moves the body in: the others only show it settling from rest.
		val seen = played.filter { it.metrics.motionPx >= SEEN_PX }
		if (seen.isNotEmpty()) report.append("\nover the %d motions moving it %.0f px or more: direction right in %d, mean R² %.2f, swing ×%.2f off (median), static %.1f px\n".format(
			seen.size, SEEN_PX, seen.count { it.metrics.direction >= 0.9f && it.metrics.sameSign }, seen.map { it.metrics.r2.coerceAtLeast(-1f) }.average(),
			kotlin.math.exp(-seen.map { kotlin.math.abs(kotlin.math.ln(it.metrics.amplitude.coerceAtLeast(1e-3f).toDouble())) }.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }),
			seen.mapNotNull { it.metrics.staticPx.takeIf { s -> !s.isNaN() } }.average()))
		report.append("\n")
		File(out, "$label.json").writeText(buildJsonObject {
			put("part", p.name); put("modes", p.edit.modes); put("keys", p.edit.keys); put("bake_seconds", seconds)
			put("bake", bake.summary())
			putJsonArray("motions") { for (run in played) addJsonObject { put("motion", run.motion); put("play_seconds", run.seconds); putJsonObject("metrics") { run.metrics.toMap().forEach { (k, v) -> put(k, v) } } } }
		}.toString())
		plot(played, File(out, "$label.png"))
		println("sim-bench: $label done")
	}

	/** Seconds between progress marks, as "0.02→0.25 1.2s". */
	private fun stages(marks: List<Pair<Float, Long>>, start: Long): String {
		var last = start; var from = 0f
		val distinct = synchronized(marks) { marks.toList() }.distinctBy { it.first }
		return distinct.joinToString(", ") { (p, t) -> "%.2f→%.2f %.2fs".format(from, p, (t - last) / 1e9).also { last = t; from = p } }
	}

	private fun format(value: Float) = when {
		value.isNaN() -> "-"
		kotlin.math.abs(value) >= 100f -> "%.0f".format(value)
		kotlin.math.abs(value) >= 10f -> "%.1f".format(value)
		else -> "%.3f".format(value)
	}

	/** Per motion, the busiest tip's body-frame x (red) and y (blue): simulated solid, baked dashed. */
	private fun plot(played: List<Played>, file: File) {
		val width = 1400; val row = 150
		val image = BufferedImage(width, row * played.size, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height)
		g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
		val solid = BasicStroke(1.4f); val dashed = BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(5f, 4f), 0f)
		for ((r, run) in played.withIndex()) {
			val top = r * row
			val frames = run.simulated.size / 2
			val peak = (run.simulated.maxOf { kotlin.math.abs(it) }).coerceAtLeast(run.baked.maxOf { kotlin.math.abs(it) }).coerceAtLeast(1f)
			g.color = Color(235, 235, 235); g.drawLine(0, top + row / 2, width, top + row / 2); g.drawLine(0, top + row - 1, width, top + row - 1)
			for ((axis, color) in listOf(0 to Color(200, 40, 40), 1 to Color(40, 70, 200))) for ((series, stroke) in listOf(run.simulated to solid, run.baked to dashed)) {
				g.color = color; g.stroke = stroke
				var px = 0; var py = 0
				for (f in 0 until frames) {
					val x = 60 + (width - 70) * f / frames.coerceAtLeast(2)
					val y = top + row / 2 - (series[f * 2 + axis] / peak * (row / 2 - 14)).toInt()
					if (f > 0) g.drawLine(px, py, x, y)
					px = x; py = y
				}
			}
			g.stroke = solid; g.color = Color.DARK_GRAY
			g.drawString(run.motion, 4, top + 14)
			g.drawString("±%.0f px".format(peak), 4, top + 28)
			val m = run.metrics
			g.drawString("amp %.2f  lag %.0f ms  settle %s  jitter %.2f  shorten %.3f  R² %.2f".format(m.amplitude, m.lagMs, format(m.settle), m.jitter, m.shortening, m.r2), 60, top + row - 6)
		}
		g.dispose()
		ImageIO.write(image, "png", file)
	}
}
