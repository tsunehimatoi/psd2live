package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.RigEditOverlay
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Reduces a simulation to parameters, keyforms and pendulums (see [SimBakeResult]).
 *
 * 1. Static response: for each static input's keys the rig is posed, the body settles under
 *    gravity, and what is left against the rig becomes corrections on that parameter's own axis, so the
 *    exported model hangs exactly as the pose asks.
 * 2. Training: the dynamic inputs play [SimMotionLibrary]'s motion - the model dragged, shaken, nodded
 *    and swaying over the inputs' full ranges - each piece from rest and all of them at once on separate
 *    copies of the body; each frame records how far the body is from the rig (minus the static
 *    correction) in each mesh's local keyform space.
 * 3. Subspace: the few principal directions of that residual, measured in px at the default pose.
 * 4. Pendulum: [SimPendulumFit] finds the Cubism pendulum whose driven vertices' angles play the motion
 *    best - following it, settling like it and no jerkier - each vertex driving one -1..1 parameter that
 *    spans the vertex's whole swing over that motion, so it does not stall at ±1. The first mode carries
 *    the swing; each later one is one more segment fitted over what the modes above leave - the body
 *    bending and bunching up as it lags.
 * 5. Keys: evenly spread over -1..1, their shapes solved mode by mode, each by least squares over every
 *    frame as the pendulum plays it - arcs and one-sided pushes included - over what the modes before it
 *    leave, and kept on a smooth curve so the body's speed does not jump at a key.
 *
 * Steps 2 to 5 run twice when the inputs move the body both ways: the vertical inputs ([VERTICAL_INPUTS])
 * apart, as translations into a one-mode pendulum of their own (see [SimGenerator.verticalParameterId]),
 * since a pendulum answers only sideways.
 *
 * [model] must not carry this simulation's own bake.
 */
object SimBaker {
    class Options(
        val fps: Int = 60,
        /** The rate the exported pendulum steps at (physics3.json `Fps`); [RigEditOverlay.UNLIMITED_FPS] steps once per frame. */
        val physicsFps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS,
        /** Scales every phase of the training run; tests shorten it. */
        val duration: Float = 1f,
        /** Runs the pieces of the training on several cores. */
        val parallel: Boolean = true,
        /** Pendulum segments above the driven ones: they add lag without adding parameters. */
        val extraSegments: Int = 0,
        /** The pendulum of the bake being replaced: the fit starts from it and searches close by, which is quicker. */
        val previous: io.github.psd2live.core.RigPhysicsEdit? = null,
        /** That bake's pendulums of their own, likewise. */
        val previousExtra: List<io.github.psd2live.core.RigPhysicsEdit> = emptyList(),
        val progress: (Float) -> Unit = {},
        val cancelled: () -> Boolean = { false },
        /** How the motion the pendulum is fitted on is made. */
        val method: Method = Method.DOF,
        /** The model's own motions to train on as well ([RigSimEdit.trainingClips]), sampled at [fps]; [Method.DOF] only. */
        val trainingMotions: List<TrainingMotion> = emptyList(),
        /** What the stack before the simulation does to its meshes, for the fingerprint ([SimBake.preceding]). */
        val preceding: String = "",
    )

    /** A motion of the model's to train on: per parameter its value each frame, and how much it counts. */
    class TrainingMotion(val name: String, val values: Map<String, FloatArray>, val weight: Float)

    enum class Method {
        /** Every input played through [SimMotionLibrary]'s motion, on its own and all together. */
        LEGACY,
        /** Each input that moves the body driven on its own through short designed motion ([SimDofTraining]). */
        DOF,
    }

    /** Below this much motion (px) at the default pose a mode or static axis is not worth keys. */
    private const val MIN_MOTION_PX = 0.5f
    /** Below this much motion (px) the vertical inputs are not worth a parameter and a pendulum of their own. */
    private const val MIN_BOUNCE_PX = 2f
    /** Below this share of the motion's energy a principal direction is left out of the fit. */
    private const val MIN_ENERGY = 0.01f
    /** Principal directions the pendulum fit reads the motion in. */
    private const val SUBSPACE = 6
    /** How strongly key shapes with few frames near them lean toward a straight line through the mode. */
    private const val KEY_RIDGE = 1e-3
    /** How strongly the key shapes of a mode are kept on a smooth curve rather than turning corners at keys. */
    private const val KEY_BEND = 0.3
    /** How much more a corner at the rest key costs than one at another key. */
    private const val CENTER_BEND = 10.0
    /** The gains a later mode's shapes are tried at on the held-out motion, besides its full fit. */
    private val LATER_GAINS = floatArrayOf(0.25f, 0.5f, 0.75f)
    /** As in the pendulum fit: how much jerkier than the simulation the baked motion may be before it costs. */
    private const val JERK_ALLOWANCE = 1.2
    private const val JERK_COST = 0.5

    fun bake(model: PuppetModel, edit: RigSimEdit, options: Options = Options()): SimBakeResult {
        val calibrated = SimScene.build(model, edit)
        calibrated.calibrate(model)
        val space = SimSpace(model, calibrated)
        val dt = 1f / options.fps
        val parameters = model.parameters.associateBy { it.id.raw }
        fun check() { if (options.cancelled()) throw CancellationException("Bake cancelled") }
        /** A copy of the calibrated body for one piece of work. */
        fun body() = SimScene.build(model, edit).also { it.adopt(calibrated) }
        fun <T, R> each(items: List<T>, work: (T) -> R): List<R> =
            if (options.parallel && items.size > 1) items.parallelStream().map(work).toList() else items.map(work)

        // 1. Static response, every key of every static input settled on its own body.
        options.progress(0.02f)
        val staticAxes = edit.staticInputs.orEmpty().mapNotNull { raw ->
            val parameter = parameters[raw]?.takeIf { it.kind == ParameterKind.NORMAL } ?: return@mapNotNull null
            staticKeys(parameter, edit.keys).takeIf { it.size >= 2 }?.let { parameter to it }
        }
        val settled = each(staticAxes.flatMap { (parameter, keys) -> keys.map { parameter to it } }) { (parameter, key) ->
            check()
            if (key == parameter.default) FloatArray(space.size) else space.settle(body(), mapOf(parameter.id to key), dt)
        }
        var cursor = 0
        val statics = staticAxes.mapNotNull { (parameter, keys) ->
            val perKey = keys.map { settled[cursor++] }
            if (perKey.maxOf { space.motionPx(it) } < MIN_MOTION_PX) null else SimBakedAxis(parameter.id.raw, keys, space.split(perKey))
        }

        // 2. The dynamic inputs, grouped as an artist sets up a model's physics, with what moves the body up and
        // down apart: a Cubism pendulum hangs along gravity and answers only sideways - its root carried
        // straight up or down leaves every angle as it was - so the vertical inputs drive a pendulum of their
        // own, fed as translations (the one way it answers them), and a parameter of their own whose shapes are
        // how the body lags and bounces up and down.
        val inputs = edit.inputs.map { it.parameter }
            .filter { input -> parameters[input]?.let { it.kind == ParameterKind.NORMAL && it.max > it.min } == true }
        require(inputs.isNotEmpty() || statics.isNotEmpty()) { "No input parameter moves ${edit.id}; add inputs to bake it" }
        // Turned off, the vertical inputs take no part; on, they bake however little they move the body.
        val vertical = if (edit.vertical == false) emptyList() else inputs.filter { it in VERTICAL_INPUTS }
        val sideways = inputs.filter { it !in VERTICAL_INPUTS }
        require(sideways.isNotEmpty() || vertical.isNotEmpty() || statics.isNotEmpty()) {
            "Only vertical inputs move ${edit.id} and its up-and-down parameter is off; turn it on or add inputs"
        }
        val fingerprint = SimBake.fingerprint(model, edit, options.preceding)
        val bounceId = SimGenerator.verticalPhysicsId(edit)
        val split = sideways.isNotEmpty() && vertical.isNotEmpty()
        // The two groups share nothing until their results are put together, so they bake side by side; the
        // progress is their sum, the sideways one weighing more as it has more to fit.
        val done = floatArrayOf(0f, 0f)
        val shares = if (split) floatArrayOf(0.6f, 0.25f) else floatArrayOf(0.85f, 0f)
        fun progress(group: Int) = { value: Float ->
            val sum = synchronized(done) { done[group] = maxOf(done[group], value); done[0] * shares[0] + done[1] * shares[1] }
            options.progress(0.1f + sum)
        }
        val dof = options.method == Method.DOF
        // Input by input: which inputs move the body, and in which group, read off the rig.
        val analysis = if (!dof) null else SimKinematics.analyze(model, edit, ::body, inputs.map { parameters.getValue(it) }, options.parallel)
        val (side, up) = if (analysis == null) emptyList<SimKinematics.Input>() to emptyList()
            else SimDofTraining.groups(analysis, edit.vertical, edit.forcedInputs.toSet())
        val checks = analysis?.inputs.orEmpty().map { input ->
            SimInputCheck(input.id, input.motion, input.rigid, when (input) {
                in side -> SimInputCheck.SIDEWAYS
                in up -> SimInputCheck.VERTICAL
                else -> null
            }, forced = input.id in edit.forcedInputs)
        }
        if (dof) require(side.isNotEmpty() || up.isNotEmpty() || statics.isNotEmpty()) {
            "No input moves ${edit.id} by ${SimKinematics.MIN_MOTION_PX} px or more; add inputs to bake it"
        }
        val mainSideways = if (dof) side.isNotEmpty() else sideways.isNotEmpty()
        val hasMain = if (dof) side.isNotEmpty() || up.isNotEmpty() else sideways.isNotEmpty() || vertical.isNotEmpty()
        val hasBounce = if (dof) side.isNotEmpty() && up.isNotEmpty() else split
        if (dof) { shares[0] = if (hasBounce) 0.6f else 0.85f; shares[1] = if (hasBounce) 0.25f else 0f }
        // Both groups' pieces at once: an input drives the body the same whichever group reads it.
        val dofTrainings = if (!dof) null else SimDofTraining.train(model, listOf(side.ifEmpty { up }, if (side.isNotEmpty()) up else emptyList()),
            space, statics, ::body, ::check, dt, options, progress = { progress(0)(it * 0.6f); progress(1)(it * 0.6f) })
        fun training(group: Int, main: Boolean, progress: (Float) -> Unit): Training =
            if (dof) requireNotNull(dofTrainings!![group])
            else libraryTraining(model, edit, space, statics, ::body, ::check, dt, options, if (main) sideways.ifEmpty { vertical } else vertical)
        val main: () -> Dynamic? = {
            if (!hasMain) null else dynamic(model, edit, space, ::check, dt, options,
                training(0, true, progress(0)), outputs = (1..edit.modes).map { SimGenerator.parameterId(edit, it) },
                id = SimGenerator.physicsId(edit), translations = !mainSideways && !dof, segments = edit.modes + options.extraSegments,
                previous = options.previous, previousExtra = options.previousExtra.filter { it.id != bounceId },
                minimum = if (!mainSideways && edit.vertical == true) 0f else MIN_MOTION_PX, progress = progress(0))
        }
        val bounce: () -> Dynamic? = {
            if (!hasBounce) null else dynamic(model, edit, space, ::check, dt, options,
                // Each input on its own lets the fit tell a carried body (a translation) from one held in another shape (a tilt).
                training(1, false, progress(1)), outputs = listOf(SimGenerator.verticalParameterId(edit)), id = bounceId, translations = !dof,
                segments = 1 + options.extraSegments, previous = options.previousExtra.firstOrNull { it.id == bounceId }, previousExtra = emptyList(),
                minimum = if (edit.vertical == true) 0f else MIN_BOUNCE_PX, progress = progress(1))
        }
        val groups = each(listOf(main, bounce)) { it() }.filterNotNull()
        require(groups.isNotEmpty() || statics.isNotEmpty()) { "${edit.id} barely moves under its inputs; nothing to bake" }
        options.progress(1f)
        val method = options.method.name.lowercase()
        if (groups.isEmpty()) return SimBakeResult(fingerprint, space.counts, statics, emptyList(), inputs = checks, method = method)

        // Out to the parameters' own span: a keyform axis snaps a value within 0.001 of a key onto it, which
        // over -1..1 would jolt a large body every time a mode swings through rest.
        val range = SimGenerator.MODE_RANGE
        fun spanned(setting: io.github.psd2live.core.RigPhysicsEdit) = setting.copy(outputs = setting.outputs.map { it.copy(scale = it.scale * range) })
        val total = groups.sumOf { it.total }.coerceAtLeast(1e-12)
        val modes = groups.flatMap { group ->
            group.modes.map { mode ->
                SimBakedMode(SimBakedAxis(mode.axis.parameter, FloatArray(mode.axis.keys.size) { mode.axis.keys[it] * range }, mode.axis.offsets),
                    mode.amplitude, (mode.energy * group.total / total).toFloat())
            }
        }
        val first = groups.first()
        val extraPhysics = (first.extra + groups.drop(1).flatMap { listOf(it.setting) + it.extra }).map(::spanned)
        // How well the baked keys, played by the pendulums, reproduce the simulation on the motion the fit
        // never saw, each group on its own motion; how far the parameters reach, how often they sit at ±1, and
        // how smooth the baked motion is.
        val moved = groups.sumOf { it.moved }.coerceAtLeast(1e-12)
        return SimBakeResult(fingerprint, space.counts, statics, modes, spanned(first.setting),
            (1.0 - groups.sumOf { it.missed } / moved).toFloat(), percentile(groups.flatMap { it.errors }, 0.95f), groups.maxOf { it.peak },
            groups.sumOf { it.clipped }.toFloat() / groups.sumOf { it.frames }.coerceAtLeast(1),
            sqrt(groups.sumOf { it.bakedJerk } / groups.sumOf { it.simulatedJerk }.coerceAtLeast(1e-12)).toFloat(), extraPhysics,
            checks, groups.mapIndexedNotNull { g, group -> group.visual?.let { SimMotionCheck(if (g == 0 && mainSideways) HELD_OUT else HELD_OUT_VERTICAL, it) } },
            method)
    }

    /** What the visual checks of a bake's held-out motion are called: the sideways group's, and the up-and-down one's. */
    const val HELD_OUT = "held_out"
    const val HELD_OUT_VERTICAL = "held_out_vertical"

    /**
     * What one group of inputs bakes to: its modes over -1..1, the pendulum driving them and those of their
     * own, and the check of them on held-out motion - the squared px missed and moved, each frame's error
     * (px), the parameters' peak, the frames they sit at ±1 of how many, and the jerk energies of the baked
     * and simulated motion. [total] is the energy of its training motion, which each mode's share is of.
     */
    private class Dynamic(
        val modes: List<SimBakedMode>,
        val setting: io.github.psd2live.core.RigPhysicsEdit,
        val extra: List<io.github.psd2live.core.RigPhysicsEdit>,
        val total: Double,
        val missed: Double,
        val moved: Double,
        val errors: List<Float>,
        val peak: Float,
        val clipped: Int,
        val frames: Int,
        val bakedJerk: Double,
        val simulatedJerk: Double,
        /** What a viewer sees of it on the held-out motion; null when that motion hardly moves the body. */
        val visual: SimVisualCheck?,
    )

    /**
     * What one group's training recorded: per piece, the body's residual each frame; per input its value each frame
     * over all the pieces; the held-out piece the same way.
     */
    internal class Training(
        val inputs: List<String>,
        val ranges: Map<String, PhysicsEngine.Range>,
        val recorded: List<List<FloatArray>>,
        val track: List<FloatArray>,
        val heldOutTrack: List<FloatArray>,
        val heldOutResiduals: List<FloatArray>,
        /** How much more or less than the others one piece may count, however much it moves the body. */
        val balance: Double = 10.0,
        /** Per piece, how much it counts on top of that (a training motion the user weighed); null counts each alike. */
        val pieceWeights: List<Double>? = null,
    )

    /**
     * Step 2 as [SimMotionLibrary] plays it for one group of [inputs], the others held at their defaults: each
     * piece from rest on its own body, and the held-out piece.
     */
    private fun libraryTraining(model: PuppetModel, edit: RigSimEdit, space: SimSpace, statics: List<SimBakedAxis>, body: () -> SimScene,
                                check: () -> Unit, dt: Float, options: Options, inputs: List<String>): Training {
        val parameters = model.parameters.associateBy { it.id.raw }
        val spans = inputs.map { trainingSpan(parameters.getValue(it), edit.inputRanges[it]) }
        /** Library values (-1..1 about the default) as parameter values within each input's training span. */
        fun values(piece: List<FloatArray>) = spans.indices.map { i ->
            val (low, default, high) = spans[i]
            FloatArray(piece[i].size) { f -> val u = piece[i][f]; if (u >= 0f) default + u * (high - default) else default + u * (default - low) }
        }
        val pieces = SimMotionLibrary.training(inputs.size, options.fps, options.duration).map(::values)
        val heldOutPiece = values(SimMotionLibrary.heldOut(inputs.size, options.fps, options.duration))
        val runs = pieces + listOf(heldOutPiece)
        val all = (if (options.parallel) runs.parallelStream() else runs.stream()).map { piece ->
            val scene = body()
            scene.reset(model, emptyMap())
            val frames = piece.firstOrNull()?.size ?: 0
            List(frames) { f ->
                if (f % 30 == 0) check()
                val pose = HashMap<ParameterId, Float>()
                for ((i, input) in inputs.withIndex()) pose[ParameterId(input)] = piece[i][f]
                scene.drive(model, pose, dt)
                val r = space.residual(scene, pose) ?: FloatArray(space.size)
                for (axis in statics) pose[ParameterId(axis.parameter)]?.let { space.subtract(r, axis, it) }
                r
            }
        }.toList()
        return Training(inputs, PhysicsEngine.ranges(model.parameters), all.dropLast(1),
            inputs.indices.map { i -> pieces.flatMap { it[i].asList() }.toFloatArray() }, heldOutPiece, all.last())
    }

    /**
     * Steps 3 to 5 for one group's [training]: the principal directions, the pendulum [id] driving [outputs] - fed
     * by translations only when [translations] - and the keys. Null when the group moves the body less than
     * [minimum] px.
     */
    private fun dynamic(
        model: PuppetModel,
        edit: RigSimEdit,
        space: SimSpace,
        check: () -> Unit,
        dt: Float,
        options: Options,
        training: Training,
        outputs: List<String>,
        id: String,
        translations: Boolean,
        segments: Int,
        previous: io.github.psd2live.core.RigPhysicsEdit?,
        previousExtra: List<io.github.psd2live.core.RigPhysicsEdit>,
        minimum: Float,
        progress: (Float) -> Unit,
    ): Dynamic? {
        val recorded = training.recorded
        val residuals = recorded.flatten()
        val heldOutResiduals = training.heldOutResiduals
        val track = training.track
        val starts = HashSet<Int>().also { set -> var at = 0; for (piece in recorded) { set += at; at += piece.size } }

        // 3. The few principal directions (px at the default pose) that span nearly all of the motion.
        progress(0.6f)
        check()
        val metric = residuals.map(space::toPx)
        val total = metric.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        // Every other frame is plenty to find them: the motion is smooth at the frame rate.
        val sampled = metric.filterIndexed { f, _ -> f % 2 == 0 }
        val sampledTotal = sampled.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        val directions = if (sampled.isEmpty()) emptyList() else principal(sampled, SUBSPACE).filter { it.second / sampledTotal >= MIN_ENERGY }.map { it.first }
        val moves = metric.isNotEmpty() && percentile(metric.map(::maxDistance), 0.98f) >= minimum
        if (!moves || directions.isEmpty()) return null
        val motion = metric.map { row -> FloatArray(directions.size) { dot(row, directions[it]) } }
        // Each piece counts alike: shaking the body through its resonance moves it far more than a drag, and
        // would otherwise decide the pendulum and the keys on its own.
        val weights = FloatArray(residuals.size)
        run {
            var at = 0
            val means = recorded.map { piece ->
                val energy = (at until at + piece.size).sumOf { f -> metric[f].sumOf { (it * it).toDouble() } } / piece.size.coerceAtLeast(1)
                at += piece.size
                energy
            }
            val overall = total / residuals.size
            at = 0
            for ((p, piece) in recorded.withIndex()) {
                val w = (if (means[p] > 1e-12) (overall / means[p]).coerceIn(1.0 / training.balance, training.balance).toFloat() else 1f) *
                    (training.pieceWeights?.get(p)?.toFloat() ?: 1f)
                for (f in at until at + piece.size) weights[f] = w
                at += piece.size
            }
        }
        val heldOutMetric = heldOutResiduals.map(space::toPx)
        val heldOutMotion = heldOutMetric.map { row -> FloatArray(directions.size) { dot(row, directions[it]) } }

        // 4. The pendulum.
        progress(0.65f)
        val fit = SimPendulumFit.fit(id, edit.name, outputs, training.inputs, training.ranges,
            track, motion, metric, dt, options.physicsFps.toFloat(), segments = segments, starts = starts,
            heldOutTrack = training.heldOutTrack, heldOutMotion = heldOutMotion, heldOutMetric = heldOutMetric, previous = previous,
            previousExtra = previousExtra, check = check, weights = weights, translations = translations)

        // 5. Key shapes for every mode at once; a mode that ends up moving nothing is dropped and the rest solved again.
        progress(0.95f)
        val keys = edit.modeKeys
        var kept = fit.played.indices.toList()
        var shapes = solveModes(residuals, kept.map { fit.played[it] }, keys, space.size, weights)
        while (kept.isNotEmpty()) {
            val moving = kept.filterIndexed { place, _ -> shapes[place].maxOf { space.motionPx(it) } >= MIN_MOTION_PX }
            if (moving.size == kept.size) break
            kept = moving
            shapes = if (kept.isEmpty()) emptyList() else solveModes(residuals, kept.map { fit.played[it] }, keys, space.size, weights)
        }
        if (kept.isEmpty()) return null
        val judged = heldOutMetric.sumOf { row -> row.sumOf { (it * it).toDouble() } } > total * 1e-3
        if (kept.size > 1 && judged) {
            // On the motion the fit never saw: each mode's keys solved once more over what the others leave
            // (closer to solving them together, without their cancelling), then each later mode's gain. Each
            // is kept only where the baked motion follows better without growing jerkier: a later mode that
            // helps in large motion may only tremble in small.
            val heldPlayed = kept.map { fit.heldOut[it] }
            val simulatedPx = heldOutResiduals.map(space::toPx)
            val simulatedJerk = jerkEnergy(simulatedPx).coerceAtLeast(1e-12)
            val heldTotal = simulatedPx.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
            fun heldOutScore(candidate: List<List<FloatArray>>): Double {
                var missed = 0.0
                val baked = heldOutResiduals.indices.map { f ->
                    val sum = FloatArray(space.size)
                    for (place in candidate.indices) { val o = at(candidate[place], keys, heldPlayed[place][f]); for (i in sum.indices) sum[i] += o[i] }
                    val px = space.toPx(sum)
                    for (i in px.indices) { val e = (simulatedPx[f][i] - px[i]).toDouble(); missed += e * e }
                    px
                }
                val excess = maxOf(0.0, sqrt(jerkEnergy(baked) / simulatedJerk) - JERK_ALLOWANCE)
                return missed / heldTotal + JERK_COST * excess * excess
            }
            var best = heldOutScore(shapes)
            val refit = backfit(residuals, kept.map { fit.played[it] }, keys, shapes, space.size, weights)
            heldOutScore(refit).let { if (it < best) { best = it; shapes = refit } }
            for (place in 1 until kept.size) {
                val full = shapes
                for (gain in LATER_GAINS) {
                    val trial = full.mapIndexed { p, mode -> if (p != place) mode else mode.map { o -> FloatArray(o.size) { o[it] * gain } } }
                    heldOutScore(trial).let { if (it < best) { best = it; shapes = trial } }
                }
            }
        }
        val axes = kept.mapIndexed { place, k -> SimBakedAxis(outputs[k], keys.copyOf(), space.split(shapes[place])) }

        // What each mode moves over the training.
        val contributions = DoubleArray(kept.size)
        val swings = kept.map { ArrayList<Float>(residuals.size) }
        for (f in residuals.indices) for ((place, k) in kept.withIndex()) {
            val px = space.toPx(space.join(axes[place], fit.played[k][f]))
            contributions[place] += px.sumOf { (it * it).toDouble() }
            swings[place] += maxDistance(px)
        }
        val modes = kept.indices.map { place -> SimBakedMode(axes[place], percentile(swings[place], 0.98f), (contributions[place] / total).toFloat()) }

        // The check, on the motion the fit never saw when it moves the body at all.
        val (checkResiduals, checkPlayed) = if (judged) heldOutResiduals to fit.heldOut else residuals to fit.played
        val checkTotal = if (judged) heldOutMetric.sumOf { row -> row.sumOf { (it * it).toDouble() } } else total
        var missed = 0.0
        val simulated = ArrayList<FloatArray>(checkResiduals.size); val baked = ArrayList<FloatArray>(checkResiduals.size)
        val errors = List(checkResiduals.size) { f ->
            val played = FloatArray(space.size)
            for ((place, k) in kept.withIndex()) {
                val offsets = space.join(axes[place], checkPlayed[k][f])
                for (i in played.indices) played[i] += offsets[i]
            }
            simulated += space.toPx(checkResiduals[f]); baked += space.toPx(played)
            val px = FloatArray(space.size) { simulated[f][it] - baked[f][it] }
            missed += px.sumOf { (it * it).toDouble() }
            maxDistance(px)
        }
        val peak = kept.maxOf { k -> checkPlayed[k].maxOf(::abs) }
        val clipped = checkPlayed.first().indices.count { f -> kept.any { k -> abs(checkPlayed[k][f]) >= 0.999f } }
        // What a viewer sees of it there: the offsets at the default pose, on the body at rest.
        val visual = if (!judged) null else {
            val held = training.heldOutTrack
            val moving = BooleanArray(simulated.size) { f -> f > 0 && held.any { abs(it[f] - it[f - 1]) > 1e-6f } }
            fun world(px: FloatArray) = FloatArray(px.size) { space.rest[it] + px[it] }
            SimVisualCheck.measure(SimVisualRun(simulated, baked, simulated.map(::world), baked.map(::world),
                simulated.indices.map { f -> FloatArray(kept.size) { checkPlayed[kept[it]][f] } }, moving), space.grain, space.grainRest, options.fps)
        }
        val keptParameters = kept.map { outputs[it] }.toSet()
        fun owned(setting: io.github.psd2live.core.RigPhysicsEdit) = setting.copy(outputs = setting.outputs.filter { it.parameter in keptParameters })
        return Dynamic(modes, owned(fit.setting), fit.extra.map(::owned).filter { it.outputs.isNotEmpty() }, total,
            missed, checkTotal, errors, peak, clipped, checkPlayed.first().size, jerkEnergy(baked), jerkEnergy(simulated), visual)
    }

    /**
     * The low end, rest and high end [parameter] trains between: [range] within the parameter's own, which
     * always keeps the default. Without a range, the whole parameter.
     */
    internal fun trainingSpan(parameter: Parameter, range: SimInputRange?): Triple<Float, Float, Float> {
        val low = (range?.min ?: parameter.min).coerceIn(parameter.min, parameter.default)
        val high = (range?.max ?: parameter.max).coerceIn(parameter.default, parameter.max)
        return Triple(low, parameter.default, high)
    }

    /** The inputs that move the body up and down: nodding, the body rising and sinking, and leaning in. */
    internal val VERTICAL_INPUTS = listOf("ParamAngleY", "ParamBodyAngleY", "ParamBodyLean")

    /** [count] keys over [parameter]'s range, half on each side of its default (the default itself is one). */
    internal fun staticKeys(parameter: Parameter, count: Int): FloatArray {
        val side = (count - 1) / 2
        val below = if (parameter.default > parameter.min) List(side) { parameter.min + (parameter.default - parameter.min) * it / side } else emptyList()
        val above = if (parameter.max > parameter.default) List(side) { parameter.max - (parameter.max - parameter.default) * it / side }.reversed() else emptyList()
        return (below + parameter.default + above).distinct().toFloatArray()
    }

    /**
     * Key shapes for each mode in turn over what the modes before it leave, like the pendulum is fitted:
     * solved together, the shapes of two modes that swing nearly alike grow large and cancel. Returns per
     * mode, per key, the offsets.
     */
    internal fun solveModes(residuals: List<FloatArray>, played: List<FloatArray>, keys: FloatArray, size: Int,
                            weights: FloatArray? = null): List<List<FloatArray>> {
        var left = residuals
        return played.mapIndexed { k, values ->
            val shapes = solveKeys(left, listOf(values), listOf(keys), size, weights).single()
            if (k < played.lastIndex) left = left.mapIndexed { f, r -> val o = at(shapes, keys, values[f]); FloatArray(size) { r[it] - o[it] } }
            shapes
        }
    }

    /** [shapes] (per key) at [value], linearly between the keys around it. */
    internal fun at(shapes: List<FloatArray>, keys: FloatArray, value: Float): FloatArray {
        val u = value.coerceIn(keys.first(), keys.last())
        var j = 0
        while (j < keys.size - 2 && u > keys[j + 1]) j++
        val t = (u - keys[j]) / (keys[j + 1] - keys[j])
        val a = shapes[j]; val b = shapes[j + 1]
        return FloatArray(a.size) { a[it] * (1f - t) + b[it] * t }
    }

    /**
     * One round of backfitting over [shapes] (per mode, per key): each mode's keys solved again, first to
     * last, over [residuals] less what every other mode plays as it stands.
     */
    internal fun backfit(residuals: List<FloatArray>, played: List<FloatArray>, keys: FloatArray, shapes: List<List<FloatArray>>, size: Int,
                         weights: FloatArray? = null): List<List<FloatArray>> {
        val out = shapes.toMutableList()
        for (k in played.indices) {
            val left = residuals.mapIndexed { f, r ->
                val rest = r.copyOf()
                for (j in played.indices) if (j != k) { val o = at(out[j], keys, played[j][f]); for (i in 0 until size) rest[i] -= o[i] }
                rest
            }
            out[k] = solveKeys(left, listOf(played[k]), listOf(keys), size, weights).single()
        }
        return out
    }

    /**
     * Key shapes for modes played as [played] (per mode, -1..1 per frame) at [keys] (per mode), all modes
     * solved together by least squares over [residuals]: each frame is the sum of every mode's keys
     * interpolated at its value. A key few frames reach leans toward the mode's straight-line shape. Returns
     * per mode, per key, the offsets.
     */
    internal fun solveKeys(residuals: List<FloatArray>, played: List<FloatArray>, keys: List<FloatArray>, size: Int,
                           weights: FloatArray? = null): List<List<FloatArray>> {
        val n = played.size
        val zeros = keys.map { k -> k.indexOfFirst { it == 0f } }
        val frees = keys.indices.map { m -> keys[m].indices.filter { it != zeros[m] } }
        val firsts = IntArray(n).also { for (m in 1 until n) it[m] = it[m - 1] + frees[m - 1].size }
        val unknowns = frees.sumOf { it.size }
        fun column(k: Int, key: Int) = firsts[k] + frees[k].indexOf(key)
        // The straight-line shapes: one shape per mode scaled by its value.
        val lineA = Array(n) { DoubleArray(n) }
        val lineB = Array(n) { DoubleArray(size) }
        val a = Array(unknowns) { DoubleArray(unknowns) }
        val b = Array(unknowns) { DoubleArray(size) }
        val columns = IntArray(2 * n); val values = DoubleArray(2 * n)
        for ((f, r) in residuals.withIndex()) {
            val w = weights?.get(f)?.toDouble() ?: 1.0
            var active = 0
            for (k in 0 until n) {
                val keys = keys[k]; val zero = zeros[k]
                val u = played[k][f].coerceIn(keys.first(), keys.last())
                var j = 0
                while (j < keys.size - 2 && u > keys[j + 1]) j++
                val t = ((u - keys[j]) / (keys[j + 1] - keys[j])).toDouble()
                if (j != zero && t < 1.0) { columns[active] = column(k, j); values[active++] = 1.0 - t }
                if (j + 1 != zero && t > 0.0) { columns[active] = column(k, j + 1); values[active++] = t }
                for (l in 0 until n) lineA[k][l] += w * u * played[l][f].coerceIn(-1f, 1f)
                val bk = lineB[k]
                for (i in 0 until size) bk[i] += w * u * r[i]
            }
            for (p in 0 until active) {
                for (q in 0 until active) a[columns[p]][columns[q]] += w * values[p] * values[q]
                val bp = b[columns[p]]; val v = w * values[p]
                for (i in 0 until size) bp[i] += v * r[i]
            }
        }
        val lineScale = (0 until n).maxOf { lineA[it][it] }.coerceAtLeast(1e-12)
        for (k in 0 until n) lineA[k][k] += 1e-2 * lineScale
        val line = SimPendulumFit.solve(lineA, lineB) ?: Array(n) { DoubleArray(size) }
        val scale = (0 until unknowns).maxOf { a[it][it] }.coerceAtLeast(1e-12)
        val ridge = KEY_RIDGE * scale
        for (k in 0 until n) for (key in frees[k]) {
            val c = column(k, key)
            a[c][c] += ridge
            for (i in 0 until size) b[c][i] += ridge * keys[k][key] * line[k][i]
        }
        // Bends between neighbouring keys cost: the body's speed jumps at a key where the shapes turn a
        // corner, which shows as a hitch when the parameter sweeps through it.
        val bend = KEY_BEND * scale
        for (k in 0 until n) {
            val ks = keys[k]
            for (j in 1 until ks.size - 1) {
                val h0 = (ks[j] - ks[j - 1]).toDouble(); val h1 = (ks[j + 1] - ks[j]).toDouble()
                val span = (h0 + h1) / 2
                val terms = listOf(j - 1 to span / h0, j to -span / h0 - span / h1, j + 1 to span / h1).filter { it.first != zeros[k] }
                // The rest key is crossed at every swing, small ones most of all: a corner there is a hitch
                // each time, so the shapes go straight through it.
                val weight = if (j == zeros[k]) bend * CENTER_BEND else bend
                for ((p, cp) in terms) for ((q, cq) in terms) a[column(k, p)][column(k, q)] += weight * cp * cq
            }
        }
        val solved = SimPendulumFit.solve(a, b) ?: Array(unknowns) { c ->
            val k = firsts.indexOfLast { it <= c }
            DoubleArray(size) { i -> keys[k][frees[k][c - firsts[k]]] * line[k][i] }
        }
        return List(n) { k -> keys[k].indices.map { key -> if (key == zeros[k]) FloatArray(size) else FloatArray(size) { solved[column(k, key)][it].toFloat() } } }
    }

    /** The energy of the third differences of [frames]: how much the motion's acceleration jumps. */
    private fun jerkEnergy(frames: List<FloatArray>): Double {
        var sum = 0.0
        for (f in 3 until frames.size) {
            val a = frames[f]; val b = frames[f - 1]; val c = frames[f - 2]; val d = frames[f - 3]
            for (i in a.indices) { val j = (a[i] - 3 * b[i] + 3 * c[i] - d[i]).toDouble(); sum += j * j }
        }
        return sum
    }

    internal fun maxDistance(px: FloatArray): Float {
        var most = 0f
        for (p in 0 until px.size / 2) most = maxOf(most, hypot(px[p * 2], px[p * 2 + 1]))
        return most
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * Up to [count] unit directions of most energy in [rows] with the energy along each, largest first:
     * a randomized subspace iteration over a slightly larger block, then its exact eigenvectors.
     */
    internal fun principal(rows: List<FloatArray>, count: Int): List<Pair<FloatArray, Double>> {
        val size = rows.first().size
        val block = minOf(count + 4, size, rows.size)
        val random = java.util.Random(31L)
        var q = List(block) { FloatArray(size) { random.nextFloat() - 0.5f } }
        orthonormalize(q)
        repeat(4) {
            // Q ← orth(RᵀR Q)
            val next = List(block) { FloatArray(size) }
            val y = FloatArray(block)
            for (row in rows) {
                for (j in 0 until block) y[j] = dot(row, q[j])
                for (j in 0 until block) { val v = y[j]; if (v != 0f) { val n = next[j]; for (i in 0 until size) n[i] += v * row[i] } }
            }
            orthonormalize(next)
            q = next
        }
        // Rayleigh-Ritz: G = (RQ)ᵀ(RQ), whose eigenvectors turn Q into the principal directions.
        val g = Array(block) { DoubleArray(block) }
        val y = DoubleArray(block)
        for (row in rows) {
            for (j in 0 until block) y[j] = dot(row, q[j]).toDouble()
            for (j in 0 until block) for (l in j until block) g[j][l] += y[j] * y[l]
        }
        for (j in 0 until block) for (l in 0 until j) g[j][l] = g[l][j]
        val (values, vectors) = jacobiEigen(g)
        return values.indices.sortedByDescending { values[it] }.take(count).filter { values[it] > 0.0 }.map { e ->
            val v = FloatArray(size)
            for (j in 0 until block) { val c = vectors[j][e].toFloat(); for (i in 0 until size) v[i] += c * q[j][i] }
            normalize(v)
            v to values[e]
        }
    }

    /** Modified Gram-Schmidt in place; a vector that vanishes is left at zero. */
    private fun orthonormalize(vectors: List<FloatArray>) {
        for ((j, v) in vectors.withIndex()) {
            repeat(2) { for (l in 0 until j) { val d = dot(v, vectors[l]); for (i in v.indices) v[i] -= d * vectors[l][i] } }
            normalize(v)
        }
    }

    /** Eigenvalues and eigenvectors (as columns) of the symmetric [m], by cyclic Jacobi rotations. */
    private fun jacobiEigen(m: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val n = m.size
        val a = Array(n) { m[it].copyOf() }
        val v = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
        repeat(60) {
            var off = 0.0
            for (p in 0 until n) for (r in p + 1 until n) off += a[p][r] * a[p][r]
            if (off < 1e-22) return@repeat
            for (p in 0 until n) for (r in p + 1 until n) {
                if (abs(a[p][r]) < 1e-300) continue
                val theta = (a[r][r] - a[p][p]) / (2 * a[p][r])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val akp = a[k][p]; val akr = a[k][r]
                    a[k][p] = c * akp - s * akr; a[k][r] = s * akp + c * akr
                }
                for (k in 0 until n) {
                    val apk = a[p][k]; val ark = a[r][k]
                    a[p][k] = c * apk - s * ark; a[r][k] = s * apk + c * ark
                }
                for (k in 0 until n) {
                    val vkp = v[k][p]; val vkr = v[k][r]
                    v[k][p] = c * vkp - s * vkr; v[k][r] = s * vkp + c * vkr
                }
            }
        }
        return DoubleArray(n) { a[it][it] } to v
    }

    private fun normalize(v: FloatArray) {
        val length = sqrt(dot(v, v))
        if (length > 0f) for (i in v.indices) v[i] /= length
    }

    private fun percentile(values: List<Float>, q: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
    }
}
