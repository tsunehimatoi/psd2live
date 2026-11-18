package io.github.psd2live.tools

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.VertexGroupJournal
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.core.sim.SimScene
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import java.nio.file.Path
import kotlin.test.Test

/**
 * Where a bake's time goes: the tml back hair baked on one thread and on all, the time between progress marks, a
 * stack sampler over every thread of the bake, and the raw cost of a simulation step.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*SimBakeProfileTool'
 * PSD2LIVE_BAKE_METHOD picks the bake method (dof by default).
 */
class SimBakeProfileTool {
	@Test fun profile() {
		requireTools()
		val initial = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val puppet = initial.rig.puppet
		val layers = initial.analysis.layers.associateBy { it.source.id.raw }
		val back = puppet.drawables.first { d ->
			d.mesh != null && layers[initial.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == SemanticTag.BACK_HAIR
		}
		val world = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(back.id)
		val ys = (1 until world.size step 2).map { world[it] }
		val top = ys.max(); val bottom = ys.min()
		val pin = VertexGroup("pin", back.id, VertexGroupKind.PIN, FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
		val overlay = initial.config.rigEdits.copy(authoringJournal = initial.config.rigEdits.authoringJournal + VertexGroupJournal.encode(pin))
		val grouped = overlay.applyTo(initial.baseRig.puppet)
		val o = SimAuthoring.put(overlay, grouped, RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw), modes = 2, keys = 5, exaggeration = 1f,
			inputs = RigSimEdit.defaultInputs(grouped.parameters.mapTo(HashSet()) { p -> p.id.raw }, SimKind.HAIR)))
		val model = SimAuthoring.unbakedModel(o, initial.baseRig.puppet, "back")
		val edit = o.simEdits.single()
		println("sim-profile: vertices ${world.size / 2}, drawables ${model.drawables.size}, inputs ${edit.inputs.map { it.parameter }}")

		// The raw step: drive alone, and drive plus the residual the bake records each frame.
		val scene = SimScene.build(model, edit).also { it.calibrate(model); it.reset(model, emptyMap()) }
		val pose = HashMap<ParameterId, Float>()
		fun frames(n: Int, block: (Int) -> Unit): Double { val t = System.nanoTime(); repeat(n) { block(it) }; return (System.nanoTime() - t) / 1e6 / n }
		repeat(200) { pose[ParameterId("ParamAngleX")] = 20f * kotlin.math.sin(it * 0.1f); scene.drive(model, pose, 1f / 60f) }
		val drive = frames(600) { pose[ParameterId("ParamAngleX")] = 20f * kotlin.math.sin(it * 0.1f); scene.drive(model, pose, 1f / 60f) }
		println("sim-profile: drive %.3f ms/frame (%.0f fps)".format(drive, 1000 / drive))

		val method = SimBaker.Method.valueOf(setting("PSD2LIVE_BAKE_METHOD", "dof").uppercase())
		for (parallel in listOf(false, true, true)) {
			val marks = ArrayList<Pair<Float, Long>>()
			val start = System.nanoTime()
			val sampler = Sampler()
			val bake = (if (method == SimBaker.Method.DIRECT) SimBaker::bake else SimBaker::bakeReference)(model, edit, SimBaker.Options(parallel = parallel, method = method, progress = { synchronized(marks) { marks += it to System.nanoTime() } }))
			sampler.stop()
			val total = (System.nanoTime() - start) / 1e9
			println("sim-profile: parallel=$parallel total %.2f s; held-out R² %.4f, p95 %.2f px, peak %.3f, jerk ×%.3f".format(total, bake.fit, bake.maxErrorPx, bake.peak, bake.jerk))
			var last = start
			for ((p, t) in marks.distinctBy { it.first }) { println("sim-profile:   -> %.3f at +%.2f s (%.2f s since last)".format(p, (t - start) / 1e9, (t - last) / 1e9)); last = t }
			sampler.report()
		}
	}

	/** Samples every running thread in the app's packages, so work on the fork-join pool counts. */
	private class Sampler {
		val self = HashMap<String, Int>(); val inclusive = HashMap<String, Int>()
		var total = 0
		val running = java.util.concurrent.atomic.AtomicBoolean(true)
		val thread = Thread {
			while (running.get()) {
				for ((thread, stack) in Thread.getAllStackTraces()) {
					if (thread === Thread.currentThread() || thread.state != Thread.State.RUNNABLE || stack.isEmpty()) continue
					val ours = stack.filter { it.className.startsWith("org.umamo") || it.className.startsWith("io.github.psd2live") }
					if (ours.none { it.className.contains(".sim.") || it.className.endsWith("PhysicsKernel") }) continue
					total++
					self.merge(stack.first().let { "${it.className}.${it.methodName}:${it.lineNumber}" }, 1, Int::plus)
					ours.map { "${it.className}.${it.methodName}" }.distinct().forEach { inclusive.merge(it, 1, Int::plus) }
				}
				Thread.sleep(2)
			}
		}.apply { isDaemon = true; start() }

		fun stop() { running.set(false); thread.join() }

		fun report() {
			println("sim-profile: sampler ($total samples), self:")
			self.entries.sortedByDescending { it.value }.take(25).forEach { println("sim-profile:   %5.1f%% %s".format(100.0 * it.value / total, it.key)) }
			println("sim-profile: inclusive:")
			inclusive.entries.sortedByDescending { it.value }.take(50).forEach { println("sim-profile:   %5.1f%% %s".format(100.0 * it.value / total, it.key)) }
		}
	}
}
