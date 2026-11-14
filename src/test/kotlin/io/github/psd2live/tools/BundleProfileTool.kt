package io.github.psd2live.tools

import io.github.psd2live.core.*
import io.github.psd2live.targets.cubism.Cubism3Json
import io.github.psd2live.targets.cubism.Moc3Target
import io.github.psd2live.bridge.PuppetIr
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.export.Moc3Export
import org.umamo.render.canvasToParentSpaceFor
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test

/**
 * Where the preview moc3 bundle's time goes on the skeletal sample: IR compile, IR to puppet, the rest-mesh
 * rebase, the parent-space seam, sidecar JSON, the moc lowering and its serialization, then the geometry safety
 * gate. Prints means of warm calls; PSD2LIVE_SAMPLER=1 adds the hottest frames of a stack sampler.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*BundleProfileTool'
 */
class BundleProfileTool {
	private fun time(runs: Int = 10, block: () -> Unit): Double = mean(runs, warmups = 3, block)

	/** A stack sampler over [block] run for about three seconds: the hottest frames, self and inclusive. */
	private fun sample(name: String, block: () -> Unit) {
		val target = Thread.currentThread()
		val self = HashMap<String, Int>(); val inclusive = HashMap<String, Int>()
		var total = 0
		val running = java.util.concurrent.atomic.AtomicBoolean(true)
		val sampler = Thread {
			while (running.get()) {
				val stack = target.stackTrace
				if (stack.isNotEmpty()) {
					total++
					val frames = stack.filter { it.className.startsWith("org.umamo") || it.className.startsWith("io.github.psd2live") || it.className.startsWith("kotlinx") || it.className.startsWith("java.") }
					stack.firstOrNull()?.let { self.merge("${it.className}.${it.methodName}:${it.lineNumber}", 1, Int::plus) }
					frames.map { "${it.className}.${it.methodName}" }.distinct().forEach { inclusive.merge(it, 1, Int::plus) }
				}
				Thread.sleep(1)
			}
		}.apply { isDaemon = true; start() }
		val end = System.nanoTime() + 3_000_000_000L
		while (System.nanoTime() < end) block()
		running.set(false); sampler.join()
		println("bundle-profile: sampler $name ($total samples), self:")
		self.entries.sortedByDescending { it.value }.take(15).forEach { println("bundle-profile:   %5.1f%% %s".format(100.0 * it.value / total, it.key)) }
		println("bundle-profile: sampler $name inclusive:")
		inclusive.entries.sortedByDescending { it.value }.take(40).forEach { println("bundle-profile:   %5.1f%% %s".format(100.0 * it.value / total, it.key)) }
	}

	@Test fun measure() {
		requireTools()
		val model = build(Sample.fromEnvironment()).skeletal
		val pipeline = PSD2LivePipeline()
		val options = pipeline.moc3ExportOptions("psd2live-preview", model.config)
		fun report(name: String, ms: Double) = println("bundle-profile: %-40s %8.2f ms".format(name, ms))
		report("buildRuntimeBundle", time { pipeline.buildRuntimeBundle("psd2live-preview", model.analysis, model.atlas, model.rig, model.config, validate = false) })
		val ir = RigIrCompiler.compile(model.analysis, model.atlas, model.rig, model.config)
		report("RigIrCompiler.compile", time { RigIrCompiler.compile(model.analysis, model.atlas, model.rig, model.config) })
		report("  PuppetIr.toIr", time { PuppetIr.toIr(model.rig.puppet) })
		report("Moc3Target.bundle", time { Moc3Target.bundle(ir, options) })
		val puppet = PuppetIr.toPuppet(ir)
		report("  PuppetIr.toPuppet", time { PuppetIr.toPuppet(ir) })
		val pose = ir.restPose.mapKeys { ParameterId(it.key) }
		val export = restMeshesToCanvasSpace(puppet, pose)
		report("  restMeshesToCanvasSpace", time { restMeshesToCanvasSpace(puppet, pose) })
		report("  canvasToParentSpaceFor (setup)", time { canvasToParentSpaceFor(export) })
		val ids = ir.parameters.mapTo(HashSet()) { it.id }
		report("  physics3 + motion3 json", time {
			Cubism3Json.physics3(ir.physics.groups, ir.physics.fps?.toInt() ?: 0)?.let(Cubism3Json::normalize)?.let(Moc3::readPhysics3)
			ir.clips.forEach { clip -> Cubism3Json.motion3(clip, ids)?.let(Cubism3Json::normalize) }
		})
		val moc3Options = Moc3Target.options(options)
		val seam = canvasToParentSpaceFor(export)
		report("  Moc3Export.toMocDocument (seam reused)", time { Moc3Export.toMocDocument(export, canvasToParentSpace = seam, options = moc3Options) })
		val lowered = Moc3Export.toMocDocument(export, canvasToParentSpace = seam, options = moc3Options)
		report("  Moc3.write", time { Moc3.write(lowered.document) })
		report("  cdi3", time { Moc3.writeCdi3(Moc3Sidecars.displayInfo(export, lowered.writtenIds)) })
		println("bundle-profile: drawables=${puppet.drawables.size} deformers=${puppet.deformers.size} clips=${ir.clips.size} moc3 bytes=${Moc3.write(lowered.document).size}")
		val sampled = setting("PSD2LIVE_SAMPLER", "") == "1"
		if (sampled) sample("Moc3Target.bundle") { Moc3Target.bundle(ir, options) }

		// Geometry safety on the mesh with the most keyed geometry, translated: the whole gate, and the no-change diff.
		val before = model.rig.puppet
		val target = before.drawables.filter { it.mesh != null }.maxBy { it.mesh!!.positions.size * (it.geometryGrid?.cells?.size ?: 1) }
		val moved = before.copy(drawables = before.drawables.map { d ->
			val mesh = d.mesh
			if (d.id != target.id || mesh == null) d
			else d.copy(mesh = org.umamo.runtime.model.DrawableMesh(FloatArray(mesh.positions.size) { mesh.positions[it] + 0.3f }, mesh.uvs, mesh.indices))
		})
		report("safety: evaluate (translated mesh)", time { GeometrySafetyEvaluator.evaluate(before, moved, blockFoldovers = false) })
		report("safety: evaluate (no change)", time { GeometrySafetyEvaluator.evaluate(before, before, blockFoldovers = false) })
		val r = GeometrySafetyEvaluator.evaluate(before, moved, blockFoldovers = false)
		println("bundle-profile: safety affected=${r.affectedTargets} coords=${r.affectedCoordinates.values.sumOf { it.size }} vertices=${target.mesh!!.positions.size / 2}")
		if (sampled) sample("safety") { GeometrySafetyEvaluator.evaluate(before, moved, blockFoldovers = false) }
	}
}
