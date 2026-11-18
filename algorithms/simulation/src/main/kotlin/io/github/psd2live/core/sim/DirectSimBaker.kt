package io.github.psd2live.core.sim

import io.github.psd2live.core.modifier.ModifierAlgorithmStages.stage
import io.github.psd2live.core.modifier.SimulationInput
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class DirectMotionKind { SWAY, TURN, COMPRESSION, FOLLOW_THROUGH }

/** Original-resolution world-space keyforms in scene particle order. Positive keys follow the spatial axis. */
internal class DirectSpatialMode(
    val kind: DirectMotionKind,
    val offsets: List<FloatArray>,
    val frequencyHz: Float,
    val dampingRatio: Float,
)

internal class DirectPhysicsMode(
    val parameter: String,
    val kind: DirectMotionKind,
    val frequencyHz: Float,
    val dampingRatio: Float,
)

/** Compiles static geometry and discrete pendulum poles. No step, settle, replay or frame training is used here. */
internal object DirectSimBaker {
    fun bake(model: PuppetModel, edit: RigSimEdit, options: SimBaker.Options): SimBakeResult {
        require(edit.trainingClips.isEmpty() && options.trainingMotions.isEmpty()) {
            "Direct simulation baking uses rig poses; clear the motion training clips to rebake this simulation"
        }
        val parameters = model.parameters.associateBy { it.id.raw }
        val inputs = edit.inputs.mapNotNull { parameters[it.parameter] }
            .filter { it.kind == ParameterKind.NORMAL && it.max > it.min }
        val staticInputs = edit.staticInputs.orEmpty().mapNotNull(parameters::get)
            .filter { it.kind == ParameterKind.NORMAL && it.max > it.min }
        require(inputs.isNotEmpty() || staticInputs.isNotEmpty()) { "Add an input to bake ${edit.id}" }
        fun checkpoint() {
            if (Thread.currentThread().isInterrupted || options.cancelled()) throw CancellationException("Bake cancelled")
        }
        checkpoint()
        val poseModel = SimulationInput.bakeModel(model, edit)
        val scene = SimScene.build(poseModel, edit, checkpoint = ::checkpoint)
        val fingerprint = SimBake.fingerprint(model, edit)
        val space = SimSpace(poseModel, scene)
        val analysis = stage("simulation: direct input poses") {
            SimKinematics.analyze(poseModel, edit, { scene }, (inputs + staticInputs).distinctBy { it.id }, false, 1, ::checkpoint)
        }
        scene.reset(poseModel, emptyMap())
        val keys = edit.modeKeys
        val shapes = stage("simulation: direct shapes") {
            when (edit.kind) {
                SimKind.HAIR -> DirectHairShapes.build(scene, keys, ::checkpoint)
                SimKind.CLOTH -> DirectClothShapes.build(scene, keys, ::checkpoint)
            }
        }
        options.progress(0.55f)
        val active = analysis.inputs.filter { input -> inputs.any { it.id == input.parameter.id } }
        val (side, up) = SimDofTraining.groups(analysis, edit.vertical, edit.forcedInputs.toSet())
        val triggers = active.filter { it.moves || it.id in edit.forcedInputs }
        val ordered = listOf(DirectMotionKind.SWAY, DirectMotionKind.TURN, DirectMotionKind.FOLLOW_THROUGH)
        val lateral = ordered.mapNotNull { kind -> shapes.firstOrNull { it.kind == kind } }
            .filter { DirectPendulumCompiler.supported(it.kind, triggers, analysis.length) }.take(edit.modes)
        val upward = shapes.firstOrNull { it.kind == DirectMotionKind.COMPRESSION }?.takeIf {
            edit.vertical != false && DirectPendulumCompiler.supported(it.kind, triggers, analysis.length)
        }
        val selected = lateral.mapIndexed { index, mode -> SimGenerator.parameterId(edit, index + 1) to mode } +
            listOfNotNull(upward?.let { SimGenerator.verticalParameterId(edit) to it })
        val descriptions = selected.map { (id, mode) -> DirectPhysicsMode(id, mode.kind, mode.frequencyHz, mode.dampingRatio) }
        val pendulums = stage("simulation: direct pendulums") {
            DirectPendulumCompiler.compile(edit, descriptions, triggers, options.physicsFps, ::checkpoint, bodyLength = analysis.length)
        }
        val driven = pendulums.flatMapTo(HashSet()) { group -> group.outputs.map { it.parameter } }
        val used = selected.filter { it.first in driven }
        val worldKeys = used.map { (id, mode) ->
            mode.offsets.map { form -> FloatArray(form.size) { form[it] * edit.outputGain(id) } }
        }
        val gains = stage("simulation: direct geometry limits") {
            val separate = worldKeys.map { forms ->
                DirectGeometryLimit.gain(scene, space.rest, listOf(forms), ::checkpoint)
            }
            val bounded = worldKeys.mapIndexed { index, forms ->
                forms.map { row -> FloatArray(row.size) { row[it] * separate[index] } }
            }
            val mixed = if (bounded.size > 1) DirectGeometryLimit.gain(scene, space.rest, bounded, ::checkpoint, combinedOnly = true)
                else 1f
            separate.map { it * mixed }
        }
        val amplitudes = used.mapIndexed { index, (_, mode) -> gains[index] * mode.offsets.maxOf(SimBaker::maxDistance) }
        val total = amplitudes.sumOf { (it * it).toDouble() }.coerceAtLeast(1e-9)
        val scaledKeys = FloatArray(keys.size) { keys[it] * SimGenerator.MODE_RANGE }
        val modes = used.mapIndexed { index, (id, mode) ->
            val local = mode.offsets.map { shape -> space.fromPx(FloatArray(shape.size) { shape[it] * gains[index] }) }
            SimBakedMode(SimBakedAxis(id, scaledKeys, space.split(local)), amplitudes[index],
                (amplitudes[index] * amplitudes[index] / total).toFloat())
        }
        val sway = shapes.firstOrNull { it.kind == DirectMotionKind.SWAY }
        val compress = shapes.firstOrNull { it.kind == DirectMotionKind.COMPRESSION }
        val statics = staticInputs.mapNotNull { parameter ->
            val input = analysis.inputs.firstOrNull { it.parameter.id == parameter.id } ?: return@mapNotNull null
            val at = SimBaker.staticKeys(parameter, edit.keys)
            if (at.size < 2) return@mapNotNull null
            val offsets = at.map { value ->
                checkpoint()
                val turn = input.root(value, SimKinematics.TURN).coerceIn(-1f, 1f)
                val lean = sway?.let { SimBaker.at(it.offsets, keys, sin(turn)) }
                val hang = compress?.let { SimBaker.at(it.offsets, keys, 1f - cos(turn)) }
                FloatArray(space.size) { (lean?.get(it) ?: 0f) + (hang?.get(it) ?: 0f) }
            }
            if (offsets.maxOf(SimBaker::maxDistance) < 0.5f) return@mapNotNull null
            val limit = DirectGeometryLimit.gain(scene, space.rest, listOf(offsets), ::checkpoint)
            SimBakedAxis(parameter.id.raw, at, space.split(offsets.map { row -> space.fromPx(FloatArray(row.size) { row[it] * limit }) }))
        }
        val inputChecks = active.map { input ->
            SimInputCheck(input.id, input.motion, input.rigid, when (input) {
                in side -> SimInputCheck.SIDEWAYS
                in up -> SimInputCheck.VERTICAL
                else -> null
            }, forced = input.id in edit.forcedInputs)
        }
        checkpoint()
        options.progress(1f)
        return SimBakeResult(fingerprint, space.counts, statics, modes, pendulums.firstOrNull(),
            extraPhysics = pendulums.drop(1), inputs = inputChecks, method = SimBaker.Method.DIRECT.name.lowercase())
    }
}

/** Bounds stored keys and combined extremes in the original topology, with a fixed number of spatial tests. */
internal object DirectGeometryLimit {
    fun gain(scene: SimScene, rest: FloatArray, axes: List<List<FloatArray>>, checkpoint: () -> Unit,
             combinedOnly: Boolean = false): Float {
        if (axes.isEmpty()) return 1f
        val triangles = scene.solver.triangles
        val areas = FloatArray(triangles.size) { area(rest, triangles.a[it] * 2, triangles.b[it] * 2, triangles.c[it] * 2) }
        val colliders = scene.state.colliders
        val restClearance = colliders.map { body ->
            FloatArray(rest.size / 2) { i -> clearance(body, rest[2 * i], rest[2 * i + 1]) }
        }
        val corners = ArrayList<FloatArray>()
        fun addCorners(indices: List<Int>) {
            // Test two fully active responses, then share the same squared displacement budget
            // across the other independent channels when they all act together.
            val drive = 0.72f * sqrt(2f / indices.size)
            for (bits in 0 until (1 shl indices.size)) {
                val row = FloatArray(rest.size)
                for ((slot, index) in indices.withIndex()) {
                    val forms = axes[index]
                    val extreme = if (bits and (1 shl slot) == 0) forms.first() else forms.last()
                    for (k in row.indices) row[k] += extreme[k] * drive
                }
                corners += row
            }
        }
        for (a in axes.indices) for (b in a + 1 until axes.size) addCorners(listOf(a, b))
        if (axes.size > 2) addCorners(axes.indices.toList())
        val checks = if (combinedOnly) corners else axes.flatten() + corners
        val posed = FloatArray(rest.size)
        fun valid(gain: Float): Boolean {
            for (shape in checks) {
                checkpoint()
                for (i in rest.indices) posed[i] = rest[i] + gain * shape[i]
                for (i in areas.indices) {
                    val neutral = areas[i]
                    if (abs(neutral) < 0.01f) continue
                    val signed = area(posed, triangles.a[i] * 2, triangles.b[i] * 2, triangles.c[i] * 2)
                    if (!(signed / neutral >= 0.12f)) return false
                }
                for ((index, collider) in colliders.withIndex()) {
                    val baseline = restClearance[index]
                    for (i in baseline.indices) {
                        if (clearance(collider, posed[2 * i], posed[2 * i + 1]) < minOf(0f, baseline[i]) - 0.25f) return false
                    }
                }
            }
            return true
        }
        if (valid(1f)) return 1f
        var low = 0f
        var high = 1f
        repeat(7) {
            val middle = (low + high) * 0.5f
            if (valid(middle)) low = middle else high = middle
        }
        return low
    }

    private fun area(p: FloatArray, a: Int, b: Int, c: Int) =
        (p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])

    private fun clearance(body: PlacedCollider, x: Float, y: Float): Float {
        val vx = body.bx - body.ax
        val vy = body.by - body.ay
        val length = vx * vx + vy * vy
        val t = if (length > 1e-6f) (((x - body.ax) * vx + (y - body.ay) * vy) / length).coerceIn(0f, 1f) else 0f
        val dx = x - body.ax - t * vx
        val dy = y - body.ay - t * vy
        return kotlin.math.sqrt(dx * dx + dy * dy) - (body.radiusA + t * (body.radiusB - body.radiusA))
    }
}
