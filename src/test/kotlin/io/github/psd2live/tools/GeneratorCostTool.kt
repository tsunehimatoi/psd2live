package io.github.psd2live.tools

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimAuthoring
import io.github.psd2live.core.sim.SimBaker
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.format.compile.document.ContentHash
import io.github.psd2live.bridge.PuppetIr
import kotlin.test.Test

/**
 * What each generator costs on a real rig against hashing its inputs, to decide which ones a generation cache
 * pays for: the rig builder with and without the skeleton, physics, generated motions, the IR compile, swings and a
 * baked simulation. Writes build/tools/generator-cost/report.txt.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*GeneratorCostTool'
 * PSD2LIVE_SAMPLE picks the PSD (tml by default); the swings and the simulation need its front and back hair.
 */
class GeneratorCostTool {
	private fun time(runs: Int = 10, block: () -> Unit): Double = mean(runs, warmups = 2, block)

	@Test fun measure() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = build(sample)
		val plain = built.plain
		val skeletal = built.skeletal
		val pipeline = PSD2LivePipeline()
		val lines = ArrayList<String>()
		fun report(name: String, ms: Double) { lines += "%-44s %8.1f ms".format(name, ms); println(lines.last()) }

		// The rig builder with and without the skeleton stage, meshes cached as in the editor.
		report("rig builder, no skeleton", time(5) { RigBuilder.build(plain.analysis, plain.atlas, plain.config, pipeline.meshCache) })
		report("rig builder, with skeleton, uncached", time(3) { SkeletonRig.clearCache(); RigBuilder.build(skeletal.analysis, skeletal.atlas, skeletal.config, pipeline.meshCache) })
		report("rig builder, with skeleton, cached", time(5) { RigBuilder.build(skeletal.analysis, skeletal.atlas, skeletal.config, pipeline.meshCache) })
		val beforeSkeleton = RigBuilder.build(plain.analysis, plain.atlas, plain.config, pipeline.meshCache).puppet
		report("skeleton input hash (puppet IR + spec)", time { ContentHash.of(PuppetIr.toIr(beforeSkeleton), built.spec.toJson()) })
		report("full preview build, with skeleton, cached", time(3) { pipeline.buildPreview(plain.analysis, skeletal.config) })

		// Generators that rerun on every replay: physics catalog and generated motions.
		val parameterIds = skeletal.rig.puppet.parameters.mapTo(linkedSetOf()) { it.id.raw }
		report("physics catalog", time { PhysicsCatalog.active(skeletal.analysis, skeletal.config, parameterIds) })
		report("physics input hash", time { ContentHash.of(skeletal.config.rigEdits.physicsEdits.map { it.toJson() }, parameterIds, skeletal.config) })
		val presets = listOf("Idle", "Blink", "Nod", "Shake") + SkeletonMotions.presets.map { it.name }
		report("generated motions (all presets), uncached", time {
			MotionPresets.clearCache()
			for (name in presets) MotionPresets.tracks(name, skeletal.config.rigEdits.skeleton, MotionPresetSettings(), emptySet())
		})
		report("generated motions (all presets), cached", time {
			for (name in presets) MotionPresets.tracks(name, skeletal.config.rigEdits.skeleton, MotionPresetSettings(), emptySet())
		})
		report("rig IR compile (physics + motions + IR)", time(5) { RigIrCompiler.compile(skeletal) })

		// Swings rerun on every replay: one lateral on the back hair, a vertical and a lateral motion on the front hair.
		val puppet = plain.rig.puppet
		val layers = plain.analysis.layers.associateBy { it.source.id.raw }
		fun meshOf(tag: SemanticTag) = puppet.drawables.first { d -> d.mesh != null && layers[plain.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.semantic?.tag == tag }
		var swung = plain.config.rigEdits
		swung = SwingAuthoring.put(swung, puppet, RigSwingEdit.single("back", "Back", SwingKind.LATERAL, listOf(meshOf(SemanticTag.BACK_HAIR).id.raw),
			listOf("ParamSwingBack"), shape = SwingShape(magnitude = 0.25f, parallel = 0.7f)))
		swung = SwingAuthoring.put(swung, swung.applyTo(puppet), RigSwingEdit("front", "Front", listOf(meshOf(SemanticTag.FRONT_HAIR).id.raw), listOf(
			SwingMotion(SwingKind.VERTICAL, listOf("ParamSwingFront_1", "ParamSwingFront_2"), SwingShape(magnitude = 0.1f)),
			SwingMotion(SwingKind.LATERAL, listOf("ParamSwingFrontX"), SwingShape(magnitude = 0.2f, parallel = 0.8f)))))
		val beforeSwing = swung.copy(swingEdits = emptyList()).applyTo(puppet)
		report("swing generator (${swung.swingEdits.size} swings)", time { SwingGenerator.apply(beforeSwing, swung.swingEdits) })
		report("swing input hash (puppet IR + edits)", time {
			val ir = PuppetIr.toIr(beforeSwing)
			ContentHash.of(swung.swingEdits, ir.deformers, ir.parameters, ir.parameterTree)
		})
		report("whole overlay replay with the swings", time { swung.applyTo(puppet) })

		// A baked simulation writes its keyforms on every replay.
		val back = meshOf(SemanticTag.BACK_HAIR)
		val world = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions.getValue(back.id)
		val ys = (1 until world.size step 2).map { world[it] }
		val top = ys.max(); val bottom = ys.min()
		val pin = org.umamo.runtime.model.VertexGroup("pin", back.id, org.umamo.runtime.model.VertexGroupKind.PIN,
			FloatArray(world.size / 2) { if (world[it * 2 + 1] > top - (top - bottom) * 0.1f) 1f else 0f })
		val grouped = plain.config.rigEdits.copy(authoringJournal = plain.config.rigEdits.authoringJournal + VertexGroupJournal.encode(pin))
		val overlay = SimAuthoring.put(grouped, grouped.applyTo(plain.baseRig.puppet), RigSimEdit("back", "Back hair", SimKind.HAIR, listOf(back.id.raw), modes = 1, keys = 3,
			inputs = RigSimEdit.defaultInputs(puppet.parameters.mapTo(HashSet()) { it.id.raw }, SimKind.HAIR)))
		val (bake, bakeMs) = timed { SimBaker.bake(SimAuthoring.unbakedModel(overlay, plain.baseRig.puppet, "back"), overlay.simEdits.single()) }
		report("simulation bake (explicit, not replayed)", bakeMs)
		val baked = SimAuthoring.withBake(overlay, "back", bake)
		val beforeSim = baked.copy(simEdits = emptyList()).applyTo(plain.baseRig.puppet)
		report("simulation generator", time { SimGenerator.apply(beforeSim, baked.simEdits) })
		report("simulation input hash (bake JSON only)", time { ContentHash.of(baked.simEdits.map { it.toJson() }) })
		report("whole overlay replay with the simulation", time { baked.applyTo(plain.baseRig.puppet) })

		val text = "sample ${sample.name}\n" + lines.joinToString("\n")
		output("generator-cost").resolve("report.txt").writeText(text)
		println(text)
	}
}
