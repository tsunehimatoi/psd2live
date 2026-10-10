package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PrimitiveSkins
import io.github.psd2live.core.RigEditOverlay
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

/**
 * A baked simulation against its reference over the model's motions: each motion played through the simulation and
 * through the exported model - the bake's keys at gain 1, played by every pendulum it writes - and what a viewer sees
 * of the difference ([SimVisualCheck]). Read-only; the simulation must be baked.
 */
object SimCompare {
    const val FPS = 60

    /**
     * One motion's comparison. With recording, [frames] holds per frame the simulated world vertices of the targets
     * and [poses] the parameter values the motion and the pendulums set, for playing both back side by side.
     */
    class Result(val motion: String, val check: SimVisualCheck, val frames: List<Map<DrawableId, FloatArray>>? = null,
                 val poses: List<Map<ParameterId, Float>>? = null)

    /** The motions [motions] name in [overlay] (see [SimMotions.resolve]), compared side by side for simulation [id]. */
    fun compare(overlay: RigEditOverlay, base: PuppetModel, id: String, motions: List<String>, skins: PrimitiveSkins = PrimitiveSkins.None,
                record: Boolean = false, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): List<Result> =
        compare(overlay, SimAuthoring.AuthoredRigs.replayedOn(base, skins), id, motions, record, progress, cancelled)

    /** [compare] on the overlay's finished [authored] rig. */
    fun compare(overlay: RigEditOverlay, authored: SimAuthoring.AuthoredRigs, id: String, motions: List<String>,
                record: Boolean = false, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): List<Result> {
        val edit = requireNotNull(overlay.simEdits.firstOrNull { it.id == id }) { "Simulation not found: $id" }
        val bake = requireNotNull(edit.bake) { "$id is not baked; bake it first" }
        require(motions.isNotEmpty()) { "Name at least one motion" }
        val model = SimAuthoring.unbakedModel(overlay, authored, id)
        val defaults = model.parameters.associate { it.id.raw to it.default }
        val tracks = motions.map { name ->
            val clip = requireNotNull(SimMotions.resolve(overlay, name)) { "Motion not found: $name" }
            name to SimMotions.sample(clip, FPS) { defaults[it] ?: 0f }
        }
        // The baked rig at gain 1, and its pendulums as the export writes them.
        val plain = edit.copy(exaggeration = 1f, outputs = edit.outputs.mapValues { it.value.copy(gain = null) }.filterValues { !it.isDefault })
        val bakedEdit = plain.copy(bake = bake)
        val baked = overlay.copy(simEdits = overlay.simEdits.map { if (it.id == id) bakedEdit else it }).let { it.finish(authored.of(it)).model }
        val rules = SimGenerator.physicsRules(listOf(bakedEdit), baked.parameters.mapTo(HashSet()) { it.id.raw })
        val modes = bake.parameters.map { bakedEdit.outputId(it) to bakedEdit.outputRange(it) }
        val ranges = PhysicsEngine.ranges(baked.parameters)
        val bounds = model.parameters.associate { it.id.raw to (it.min to it.max) }

        fun check() { if (cancelled()) throw CancellationException("Simulation comparison cancelled") }
        val calibrated = SimScene.build(model, edit).also { it.calibrate(model, cancelled = cancelled) }
        val space = SimSpace(model, calibrated)
        val total = tracks.sumOf { it.second.values.firstOrNull()?.size ?: 0 }.coerceAtLeast(1)
        val done = AtomicInteger()
        return tracks.parallelStream().map { (name, values) ->
            val scene = SimScene.build(model, edit).also { it.adopt(calibrated); it.reset(model, emptyMap()) }
            val engine = PhysicsEngine(rules, ranges, overlay.physicsFps.toFloat())
            val evaluator = CpuDeformationEvaluator()
            val n = scene.state.count
            val frames = values.values.firstOrNull()?.size ?: 0
            val moved = values.filterKeys { it in bounds }.mapValues { (raw, track) ->
                val (low, high) = bounds.getValue(raw); FloatArray(track.size) { track[it].coerceIn(low, high) }
            }
            val simulated = ArrayList<FloatArray>(frames); val bakedRows = ArrayList<FloatArray>(frames)
            val simulatedWorld = ArrayList<FloatArray>(frames); val bakedWorld = ArrayList<FloatArray>(frames)
            val modeRows = ArrayList<FloatArray>(frames)
            val recorded = if (record) ArrayList<Map<DrawableId, FloatArray>>(frames) else null
            val poses = if (record) ArrayList<Map<ParameterId, Float>>(frames) else null
            for (f in 0 until frames) {
                if (f % 30 == 0) check()
                val pose = moved.entries.associate { ParameterId(it.key) to it.value[f] }
                scene.drive(model, pose, 1f / FPS)
                val driven = engine.step(pose.mapKeys { it.key.raw }, 1f / FPS)
                val full = pose + driven.mapKeys { ParameterId(it.key) }
                val world = evaluator.evaluate(baked, full).worldPositions
                val s = scene.state
                val c = cos(-s.frameAngle); val sn = sin(-s.frameAngle)
                val sw = s.positions()
                val bw = FloatArray(n * 2)
                for ((mesh, offset) in scene.offsets) world[mesh]?.let { it.copyInto(bw, offset * 2, 0, minOf(it.size, scene.vertexCounts.getValue(mesh) * 2)) }
                // Offsets from the rig in the body's frame, so a tilted head is not counted as a swing.
                fun body(w: FloatArray) = FloatArray(n * 2) { k ->
                    val i = k / 2; val dx = w[i * 2] - s.goalX[i]; val dy = w[i * 2 + 1] - s.goalY[i]
                    if (k % 2 == 0) c * dx - sn * dy else sn * dx + c * dy
                }
                simulated += body(sw); bakedRows += body(bw); simulatedWorld += sw; bakedWorld += bw
                modeRows += FloatArray(modes.size) { (driven[modes[it].first] ?: 0f) / modes[it].second }
                recorded?.add(SimAuthoring.positions(scene))
                poses?.add(full)
                progress(done.incrementAndGet().toFloat() / total)
            }
            val moving = BooleanArray(frames) { f -> f > 0 && moved.values.any { kotlin.math.abs(it[f] - it[f - 1]) > 1e-6f } }
            Result(name, SimVisualCheck.measure(SimVisualRun(simulated, bakedRows, simulatedWorld, bakedWorld, modeRows, moving), space.grain, space.grainRest, FPS),
                recorded, poses)
        }.toList()
    }
}
