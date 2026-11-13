package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsKernel
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fits a Cubism pendulum to a recorded motion. The pendulum's last [outputs] vertices each drive one mode
 * parameter; the simulated motion (as coordinates in a few principal directions) is read out as a mix of
 * their angles.
 *
 * The pendulum is judged the way the exported model is watched, not by fit alone: every candidate plays the
 * whole training motion through [PhysicsEngine] at its real amplitude, and its score adds to the motion it
 * misses how badly it misses the body settling after a move (the follow-through) and how much jerkier it
 * is than the simulation. A first guess at the input weights and types comes from small-swing
 * superposition (alternating least squares over each input's own response); from there Nelder-Mead
 * searches the segments and the weights together. A multi-segment pendulum lags each vertex behind the one
 * above, so a whip-like strand is not squeezed into one in-phase swing: the first output carries the swing,
 * and each later one is a segment searched on its own over what the outputs above leave unexplained.
 */
internal object SimPendulumFit {
    class Result(
        /** The pendulum whose vertices drive the first mode and every later mode that hangs below it. */
        val setting: RigPhysicsEdit,
        /** A pendulum of its own for each later mode that reacts apart from the swing, as `<id>_<k>`. */
        val extra: List<RigPhysicsEdit>,
        /** Per output, its parameter value frame by frame as the pendulum plays it. */
        val played: List<FloatArray>,
        /** The same over the held-out track. */
        val heldOut: List<FloatArray>,
        /** Share of the motion a straight readout of the angles explains, 0..1. */
        val r2: Float,
    )

    /** How far past the largest swing of all the motion ±1 lies. */
    private const val HEADROOM = 1.1f
    private const val RIDGE = 1e-2
    private const val ALS_ROUNDS = 6
    /**
     * The weight (0..1) each input's own response is taken at for the first guess: small enough that the
     * pendulum answers in proportion, so the responses add up to what the inputs do together.
     */
    private const val NOMINAL = 0.25f
    /** Below this weight (0..1) an input is left out of the pendulum. */
    private const val MIN_WEIGHT = 0.005f
    /** How much jerkier than the simulation the played motion may be before it costs. */
    private const val JERK_ALLOWANCE = 1.2
    private const val JERK_COST = 0.5
    /** How much the motion missed while the inputs stand still - the swing-out and settle - counts on top. */
    private const val SETTLE_COST = 0.5
    private val NORMALIZATION = PhysicsNormalization(angleMin = -30f, angleMax = 30f)
    private val TYPES = listOf(PhysicsSourceType.X, PhysicsSourceType.ANGLE)
    /** Up to this many inputs, every combination of their types is tried for the first guess. */
    private const val MAX_TYPE_SEARCH = 6
    /** Principal directions each later mode is fitted in, taken afresh over what the modes above leave. */
    private const val SUBSPACE = 6
    /** Below this share of what is left a principal direction is left out of a later mode's fit. */
    private const val MIN_ENERGY = 0.01
    /** Names the fit reads chain vertices out under; they never reach the model. */
    private val PROBES = List(RigSimEdit.MAX_MODES + 8) { "__sim_fit_$it" }

    /**
     * [inputs] name the parameters; [track] holds their values per frame, one array per input; [motion] is
     * the motion per frame as coordinates in a few principal directions, and [metric] the same motion in full
     * (px per particle coordinate), from which each later mode takes its own directions. [outputs] names
     * one parameter per mode. Frames are [dt] apart; the pendulum steps at [fps] like the exported file.
     */
    fun fit(
        id: String,
        name: String,
        outputs: List<String>,
        inputs: List<String>,
        ranges: Map<String, PhysicsEngine.Range>,
        track: List<FloatArray>,
        motion: List<FloatArray>,
        metric: List<FloatArray>,
        dt: Float,
        fps: Float,
        segments: Int = outputs.size,
        /** Runs the searches from each start on several cores. */
        parallel: Boolean = true,
        /** Frames where the track starts over from rest; the pendulum is reset there too. */
        starts: Set<Int> = emptySet(),
        /** A stretch of motion kept out of the fit, from rest: among the candidates the one that does best on it wins. */
        heldOutTrack: List<FloatArray> = emptyList(),
        heldOutMotion: List<FloatArray> = emptyList(),
        heldOutMetric: List<FloatArray> = emptyList(),
        /** The pendulum of an earlier bake of this simulation: searched from, closely, instead of from scratch. */
        previous: RigPhysicsEdit? = null,
        /** The earlier bake's pendulums of their own, likewise. */
        previousExtra: List<RigPhysicsEdit> = emptyList(),
        /** Called between candidates; throw from it to stop. */
        check: () -> Unit = {},
        /** How much each frame of [motion] counts, so each kind of motion counts alike however large; null is 1. */
        weights: FloatArray? = null,
        /** Every input a translation: inputs that move the body up and down, which an angle input would only tilt. */
        translations: Boolean = false,
    ): Result {
        val frames = motion.size
        require(heldOutMotion.isEmpty() || heldOutTrack.size == inputs.size && heldOutTrack.all { it.size == heldOutMotion.size })
        require(metric.size == frames && heldOutMetric.size == heldOutMotion.size)
        val m = motion.firstOrNull()?.size ?: 0
        val n = outputs.size
        require(n in 1..RigSimEdit.MAX_MODES && segments >= n && inputs.isNotEmpty() && track.size == inputs.size &&
            track.all { it.size == frames } && m > 0)
        // Fitting reads raw angles: unit scale, and ranges wide enough that nothing clamps.
        val open = ranges + (outputs + PROBES).associateWith { PhysicsEngine.Range(-1e6f, 1e6f, 0f) }
        // The first output's vertex; the segments above it add lag without a parameter of their own.
        val first = segments - n + 1

        /** One segment from its length, mobility, delay and acceleration as searched. */
        fun segmentOf(p: DoubleArray) = PhysicsSegment(
            exp(p[0]).toFloat().coerceIn(0.5f, 60f), (1.0 / (1.0 + exp(-p[1]))).toFloat().coerceIn(0.01f, 1f),
            exp(p[2]).toFloat().coerceIn(0.05f, 5f), exp(p[3]).toFloat().coerceIn(0.01f, 10f),
        )
        fun vectorOf(segment: PhysicsSegment): DoubleArray {
            val mobility = segment.mobility.coerceIn(0.02f, 0.98f).toDouble()
            return doubleArrayOf(ln(segment.length.toDouble()), ln(mobility / (1 - mobility)), ln(segment.delay.toDouble()), ln(segment.acceleration.toDouble()))
        }
        // The first mode's part of the search: the top segment, then each later segment down to the first
        // output's vertex as length and delay ratios to it.
        val size = 4 + 2 * (first - 1)
        fun segmentsOf(p: DoubleArray): List<PhysicsSegment> {
            val top = segmentOf(p)
            return List(first) { k ->
                if (k == 0) top
                else PhysicsSegment((top.length * exp(p[4 + 2 * (k - 1)]).toFloat()).coerceIn(0.5f, 60f), top.mobility,
                    (top.delay * exp(p[5 + 2 * (k - 1)]).toFloat()).coerceIn(0.05f, 5f), top.acceleration)
            }
        }
        fun segmentVector(chain: List<PhysicsSegment>): DoubleArray {
            val p = DoubleArray(size)
            val s0 = chain.first()
            vectorOf(s0).copyInto(p)
            for (k in 1 until first) {
                p[4 + 2 * (k - 1)] = ln(chain[k].length.toDouble() / s0.length)
                p[5 + 2 * (k - 1)] = ln(chain[k].delay.toDouble() / s0.delay)
            }
            return p
        }

        /** The angles (radians) per frame of vertices [from] to the last of [chain] as [links] play [values] through it. */
        fun play(chain: List<PhysicsSegment>, links: List<PhysicsInput>, from: Int, values: List<FloatArray>, restarts: Set<Int>): Array<FloatArray> {
            val count = chain.size - from + 1
            val probe = RigPhysicsEdit(id, name, inputs = links, outputs = List(count) { PhysicsOutput(PROBES[it], from + it, 1f) },
                segments = chain, normalization = NORMALIZATION)
            // The kernel plays as the engine does, each angle with one quick atan2: thousands of frames per candidate.
            val kernel = PhysicsKernel(listOf(probe), open, fps, exact = false)
            val length = values.first().size
            val out = Array(count) { FloatArray(length) }
            val used = links.map { link -> inputs.indexOf(link.parameter) }
            val slots = links.map { kernel.indexOf(it.parameter) }
            val read = IntArray(count) { kernel.outputs.indexOf(PROBES[it]) }
            val pose = kernel.defaults.copyOf()
            val driven = FloatArray(kernel.outputs.size)
            for (f in 0 until length) {
                if (f in restarts) kernel.reset()
                for (i in links.indices) if (slots[i] >= 0) pose[slots[i]] = values[used[i]][f]
                if (!kernel.step(pose, dt, driven)) continue
                for (k in 0 until count) if (read[k] >= 0) out[k][f] = driven[read[k]]
            }
            return out
        }
        /** The angles of the vertices [chain] reaches from the first output's down. */
        fun run(chain: List<PhysicsSegment>, links: List<PhysicsInput>, values: List<FloatArray> = track, restarts: Set<Int> = starts) =
            play(chain, links, first, values, restarts)
        fun runHeldOut(chain: List<PhysicsSegment>, links: List<PhysicsInput>) = run(chain, links, heldOutTrack, setOf(0))

        val training = Judge(track, motion, starts, m, weights)
        val judged = if (heldOutMotion.isEmpty()) null else Judge(heldOutTrack, heldOutMotion, setOf(0), m, null)

        /** [coefficients] on the nominal responses as pendulum inputs of [types]. */
        fun links(coefficients: FloatArray, types: List<PhysicsSourceType>) = inputs.indices.mapNotNull { i ->
            val w = coefficients[i] * NOMINAL
            if (abs(w) < MIN_WEIGHT) null else PhysicsInput(inputs[i], (abs(w) * 100f).coerceIn(0f, 100f), types[i], reflect = w < 0f)
        }.ifEmpty { listOf(PhysicsInput(inputs.first(), 100f * NOMINAL, types.first())) }

        /**
         * A first guess at the inputs of a pendulum [played] as given: each input's small-swing response, both
         * types, mixed by least squares onto [target].
         */
        fun guess(played: (List<PhysicsInput>) -> Array<FloatArray>, target: List<FloatArray>): List<PhysicsInput> {
            val jobs = inputs.flatMap { parameter -> TYPES.map { type -> PhysicsInput(parameter, 100f * NOMINAL, type) } }
            val channels = jobs.map { played(listOf(it)) }
            val both = Gram(channels, target)
            val weights = FloatArray(inputs.size * TYPES.size) { if (it % 2 == 0) 0.5f else 0.2f }
            both.als(weights)
            // One type per input. The one that does more in the mix is the first try; with few inputs every
            // combination is tried and the one that leaves the least wins. An input held still - a tilted
            // head - is answered for good only by an angle input, which a swing that does more may hide.
            val leaning = inputs.indices.map { i ->
                if (abs(weights[i * 2]) * sqrt(both.energy(i * 2)) >= abs(weights[i * 2 + 1]) * sqrt(both.energy(i * 2 + 1))) PhysicsSourceType.X
                else PhysicsSourceType.ANGLE
            }
            val combinations = if (translations) listOf(inputs.map { PhysicsSourceType.X })
                else if (inputs.size > MAX_TYPE_SEARCH) listOf(leaning)
                else listOf(leaning) + (0 until (1 shl inputs.size)).map { bits -> inputs.indices.map { if (bits shr it and 1 == 0) PhysicsSourceType.X else PhysicsSourceType.ANGLE } }
            var best: Triple<Double, List<PhysicsSourceType>, FloatArray>? = null
            for (types in combinations.distinct()) {
                val picked = inputs.indices.map { i -> i * 2 + TYPES.indexOf(types[i]) }
                val single = FloatArray(inputs.size) { weights[picked[it]] }
                val error = Gram(picked.map { channels[it] }, target).als(single)
                if (best == null || error < best.first) best = Triple(error, types, single)
            }
            val (_, types, single) = requireNotNull(best)
            return links(single, types)
        }

        class Candidate(val chain: List<PhysicsSegment>, val links: List<PhysicsInput>, val score: Double)

        /**
         * Nelder-Mead over [chain]'s segments and [links]' weights together (their types and directions
         * stay), each point played for real and scored; returns the best point it saw.
         */
        fun search(chain: List<PhysicsSegment>, links: List<PhysicsInput>, step: Double, iterations: Int): Candidate {
            fun linksOf(p: DoubleArray) = links.mapIndexed { i, link ->
                link.copy(weight = (100.0 / (1.0 + exp(-p[size + i]))).toFloat().coerceIn(0.1f, 100f))
            }
            val start = segmentVector(chain) + DoubleArray(links.size) { i ->
                val w = (links[i].weight / 100.0).coerceIn(0.01, 0.99); ln(w / (1 - w))
            }
            var best: Candidate? = null
            val cost = { p: DoubleArray ->
                check()
                val c = segmentsOf(p); val l = linksOf(p)
                val score = training.score(run(c, l), null).first
                if (best == null || score < best!!.score) best = Candidate(c, l, score)
                score
            }
            NelderMead.minimize(start, step, cost, iterations = iterations, tolerance = 1e-4)
            return requireNotNull(best)
        }
        fun <T> all(jobs: List<() -> T>): List<T> = if (parallel) jobs.parallelStream().map { it() }.toList() else jobs.map { it() }

        // The first mode on its own. Where to search from: the previous bake's pendulum, closely, when it fits
        // this one; otherwise a few spread-out pendulums, each later segment a copy of the first, with the
        // first guess at the inputs.
        val reuse = previous?.takeIf { p -> p.segments.size in first..segments && p.inputs.isNotEmpty() && p.inputs.all { it.parameter in inputs } }
        val jobs: List<() -> Candidate> = if (reuse != null) listOf({ search(reuse.segments.take(first), reuse.inputs, 0.25, 12 * (size + reuse.inputs.size)) })
        else listOf(
            doubleArrayOf(ln(10.0), 2.2, ln(0.9), ln(1.2)),
            doubleArrayOf(ln(20.0), 1.5, ln(1.5), ln(0.6)),
            doubleArrayOf(ln(5.0), 3.0, ln(0.5), ln(2.0)),
        ).map { start -> {
            val chain = segmentsOf(start + DoubleArray(size - 4))
            val links = guess({ run(chain, it) }, motion)
            search(chain, links, 0.5, 25 * (size + links.size))
        } }
        // The candidate that does best on the held-out motion, read out as on the training.
        fun heldOutScore(candidate: Candidate): Double {
            judged ?: return candidate.score
            val readout = training.score(run(candidate.chain, candidate.links), null).second
            return judged.score(runHeldOut(candidate.chain, candidate.links), readout).first
        }
        val chosen = all(jobs).minBy(::heldOutScore)
        var links = chosen.links
        var chain = chosen.chain

        /** A later mode with a pendulum of its own: one segment and its own inputs, reacting apart from the swing. */
        class Alone(val segment: PhysicsSegment, val links: List<PhysicsInput>)
        val alone = arrayOfNulls<Alone>(n)
        /** Per mode on the chain, its vertex. */
        val vertexOf = IntArray(n).also { it[0] = first }
        fun runAlone(a: Alone, values: List<FloatArray> = track, restarts: Set<Int> = starts): FloatArray =
            play(listOf(a.segment), a.links, 1, values, restarts).single()
        /** Mode [k]'s angles as the pendulums play [values]. */
        fun anglesOf(k: Int, values: List<FloatArray> = track, restarts: Set<Int> = starts): FloatArray =
            alone[k]?.let { runAlone(it, values, restarts) } ?: run(chain, links, values, restarts)[vertexOf[k] - first]

        // What the modes fitted so far leave of the full motion, training and held out: each later mode is
        // fitted in the principal directions of this, not of the whole motion, where most of what it has to
        // add may not lie.
        val width = metric.first().size
        var left = metric
        var leftHeld = heldOutMetric
        fun peel(k: Int) {
            val angle = anglesOf(k)
            val b = regress(listOf(angle), left, 1, width, weights).first.single()
            fun minus(rows: List<FloatArray>, a: FloatArray) = rows.mapIndexed { f, row -> FloatArray(width) { row[it] - a[f] * b[it] } }
            left = minus(left, angle)
            if (leftHeld.isNotEmpty()) leftHeld = minus(leftHeld, anglesOf(k, heldOutTrack, setOf(0)))
        }
        peel(0)
        var fitted = 1

        // Each further mode is fitted over what the modes above leave unexplained, with those modes kept as
        // they are. Fitted together, two modes of neighbouring vertices swing nearly alike and their shapes
        // grow large and cancel, which breaks on motion unlike the training; one after the other, a later
        // mode only adds the bending and bunching the ones above cannot show. It is either one more segment
        // below the chain - a vertex follows only the ones above it, so the modes above play the same - or a
        // pendulum of its own with its own inputs, length and speed: bunching up answers a jolt faster and
        // harder than the swing does. The held-out motion picks.
        for (k in 1 until n) {
            val sampled = left.filterIndexed { f, _ -> f % 2 == 0 }
            val leftTotal = sampled.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
            val directions = SimBaker.principal(sampled, SUBSPACE).filterIndexed { i, it -> i == 0 || it.second / leftTotal >= MIN_ENERGY }.map { it.first }
            if (directions.isEmpty()) break
            val d = directions.size
            fun project(rows: List<FloatArray>) = rows.map { row -> FloatArray(d) { j -> var s = 0f; val v = directions[j]; for (i in row.indices) s += row[i] * v[i]; s } }
            fun base(whole: List<FloatArray>, rest: List<FloatArray>) = whole.mapIndexed { f, row -> DoubleArray(d) { (row[it] - rest[f][it]).toDouble() } }
            val stageMotion = project(metric)
            val stageLeft = project(left)
            val stage = Judge(track, stageMotion, starts, d, weights, base(stageMotion, stageLeft))
            val stageHeldOut = if (heldOutMetric.isEmpty()) null else project(heldOutMetric).let { whole ->
                Judge(heldOutTrack, whole, setOf(0), d, null, base(whole, project(leftHeld)))
            }

            class Grown(val chain: List<PhysicsSegment>, val own: Alone?, val score: Double)
            fun anglesOf(grown: Grown, values: List<FloatArray>, restarts: Set<Int>): FloatArray =
                grown.own?.let { runAlone(it, values, restarts) } ?: run(grown.chain, links, values, restarts).last()
            val above = chain.last()
            val warm = reuse?.segments?.getOrNull(chain.size)
            val tries = if (warm != null) listOf(warm to 0.25) else listOf(
                above to 0.5,
                above.copy(length = above.length * 0.5f, delay = above.delay * 0.7f) to 0.5,
                above.copy(length = (above.length * 1.5f).coerceAtMost(60f)) to 0.5,
                above.copy(acceleration = (above.acceleration * 2f).coerceAtMost(10f), delay = above.delay * 0.5f) to 0.5,
            )
            val below: List<() -> Grown> = tries.map { (start, step) -> {
                var best: Grown? = null
                val cost = { p: DoubleArray ->
                    check()
                    val c = chain + segmentOf(p)
                    val score = stage.score(arrayOf(run(c, links).last()), null).first
                    if (best == null || score < best!!.score) best = Grown(c, null, score)
                    score
                }
                NelderMead.minimize(vectorOf(start), step, cost, iterations = (if (warm != null) 12 else 25) * 4, tolerance = 1e-4)
                requireNotNull(best)
            } }
            // A pendulum of its own: from the earlier bake's when there is one, else a shorter, quicker
            // segment with inputs guessed for what is left; its segment and input weights searched together.
            val previousAlone = previousExtra.firstOrNull { e -> e.outputs.any { it.parameter == outputs[k] } }
                ?.takeIf { e -> e.segments.size == 1 && e.inputs.isNotEmpty() && e.inputs.all { it.parameter in inputs } }
            val own: () -> Grown = {
                val segment = previousAlone?.segments?.single() ?: above.copy(length = (above.length * 0.5f).coerceAtLeast(0.5f), delay = above.delay * 0.5f)
                val start = previousAlone?.inputs ?: guess({ arrayOf(runAlone(Alone(segment, it))) }, stageLeft)
                fun linksOf(p: DoubleArray) = start.mapIndexed { i, link -> link.copy(weight = (100.0 / (1.0 + exp(-p[4 + i]))).toFloat().coerceIn(0.1f, 100f)) }
                var best: Grown? = null
                val cost = { p: DoubleArray ->
                    check()
                    val a = Alone(segmentOf(p), linksOf(p))
                    val score = stage.score(arrayOf(runAlone(a)), null).first
                    if (best == null || score < best!!.score) best = Grown(chain, a, score)
                    score
                }
                val p0 = vectorOf(segment) + DoubleArray(start.size) { i -> val w = (start[i].weight / 100.0).coerceIn(0.01, 0.99); ln(w / (1 - w)) }
                NelderMead.minimize(p0, if (previousAlone != null) 0.25 else 0.5, cost, iterations = (if (previousAlone != null) 12 else 25) * p0.size, tolerance = 1e-4)
                requireNotNull(best)
            }
            val grown = all(below + own).minBy { candidate ->
                if (stageHeldOut == null) candidate.score else {
                    val readout = stage.score(arrayOf(anglesOf(candidate, track, starts)), null).second
                    stageHeldOut.score(arrayOf(anglesOf(candidate, heldOutTrack, setOf(0))), readout).first
                }
            }
            if (grown.own != null) alone[k] = grown.own else { chain = grown.chain; vertexOf[k] = chain.size }
            peel(k)
            fitted++
        }
        // Then every segment and weight of the chain together, from there, still read out one mode after
        // another: the upper segments may swing differently knowing the ones below take up the lag. Only when
        // every mode hangs on the chain, which is all this pendulum plays.
        if (fitted > 1 && alone.all { it == null }) {
            val grown = chain.drop(first)
            fun polished(p: DoubleArray) = segmentsOf(p) + grown.indices.map { segmentOf(p.copyOfRange(size + 4 * it, size + 4 * it + 4)) }
            fun linksOf(p: DoubleArray) = links.mapIndexed { i, link ->
                link.copy(weight = (100.0 / (1.0 + exp(-p[size + 4 * grown.size + i]))).toFloat().coerceIn(0.1f, 100f))
            }
            val start = segmentVector(chain) + grown.flatMap { vectorOf(it).asList() }.toDoubleArray() + DoubleArray(links.size) { i ->
                val w = (links[i].weight / 100.0).coerceIn(0.01, 0.99); ln(w / (1 - w))
            }
            var best = Candidate(chain, links, training.score(run(chain, links), null).first)
            NelderMead.minimize(start, 0.2, { p ->
                check()
                val c = polished(p); val l = linksOf(p)
                val score = training.score(run(c, l), null).first
                if (score < best.score) best = Candidate(c, l, score)
                score
            }, iterations = 12 * start.size, tolerance = 1e-4)
            if (best.chain !== chain && heldOutScore(best) < heldOutScore(Candidate(chain, links, 0.0))) { chain = best.chain; links = best.links }
        }

        val raw = Array(fitted) { anglesOf(it) }
        val rawHeldOut = if (judged == null) null else Array(fitted) { anglesOf(it, heldOutTrack, setOf(0)) }
        // Each parameter spans its vertex's whole swing over all the motion, the hardest shaking included, with
        // room to spare: a parameter held at ±1 holds the body still while it should swing.
        val scales = FloatArray(fitted) { k ->
            var peak = raw[k].maxOf(::abs)
            rawHeldOut?.let { peak = maxOf(peak, it[k].maxOf(::abs)) }
            if (peak > 1e-6f) 1f / (peak * HEADROOM) else 1f
        }
        fun clamp(angles: Array<FloatArray>) = angles.mapIndexed { k, angle -> FloatArray(angle.size) { (angle[it] * scales[k]).coerceIn(-1f, 1f) } }
        val played = clamp(raw)
        val total = motion.sumOf { row -> row.sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        val error = regress(played, motion, fitted, m).second
        val setting = RigPhysicsEdit(id, name, inputs = links,
            outputs = (0 until fitted).filter { alone[it] == null }.map { k -> PhysicsOutput(outputs[k], vertexOf[k], scales[k].coerceAtLeast(1e-4f)) },
            segments = chain, normalization = NORMALIZATION)
        val extra = (0 until fitted).mapNotNull { k ->
            val a = alone[k] ?: return@mapNotNull null
            RigPhysicsEdit("${id}_${k + 1}", name, inputs = a.links, outputs = listOf(PhysicsOutput(outputs[k], 1, scales[k].coerceAtLeast(1e-4f))),
                segments = listOf(a.segment), normalization = NORMALIZATION)
        }
        return Result(setting, extra, played, rawHeldOut?.let(::clamp) ?: emptyList(), (1.0 - error / total).toFloat())
    }

    /**
     * Scores played angles against [motion] over [track]: the share of the motion missed, the share missed
     * while every input stands still counted again by [SETTLE_COST], and each piece's jerk past
     * [JERK_ALLOWANCE] times the simulation's. With [base] - what earlier modes already play, per frame -
     * the angles are read out over what it leaves and judged added to it.
     */
    private class Judge(track: List<FloatArray>, val motion: List<FloatArray>, val starts: Set<Int>, val m: Int, val weights: FloatArray?,
                        val base: List<DoubleArray>? = null) {
        private val frames = motion.size
        private val target = if (base == null) motion else motion.mapIndexed { f, row -> FloatArray(m) { d -> (row[d] - base[f][d]).toFloat() } }
        private fun w(f: Int) = weights?.get(f)?.toDouble() ?: 1.0
        private val total = (0 until frames).sumOf { f -> w(f) * motion[f].sumOf { (it * it).toDouble() } }.coerceAtLeast(1e-12)
        /** Frames where no input has moved for a tenth of a second or more. */
        private val still = BooleanArray(frames).also { still ->
            var run = 0
            for (f in 0 until frames) {
                val moving = f > 0 && f !in starts && track.any { abs(it[f] - it[f - 1]) > 1e-5f }
                run = if (moving || f in starts) 0 else run + 1
                still[f] = run >= 6
            }
        }
        private val stillTotal = (0 until frames).sumOf { f -> if (still[f]) w(f) * motion[f].sumOf { (it * it).toDouble() } else 0.0 }
        /** Each piece's first frame and the frame past its last. */
        private val pieces = (starts.filter { it in 0 until frames } + 0).distinct().sorted().let { at -> at.zip(at.drop(1) + frames) }
        private val targetJerk = pieces.map { (from, to) -> jerk(from, to) { f, d -> motion[f][d].toDouble() } }

        /** The energy of the third differences within [from] until [to]. */
        private fun jerk(from: Int, to: Int, value: (Int, Int) -> Double): Double {
            var sum = 0.0
            for (f in from + 3 until to) for (d in 0 until m) {
                val j = value(f, d) - 3 * value(f - 1, d) + 3 * value(f - 2, d) - value(f - 3, d)
                sum += j * j
            }
            return sum
        }

        /** The score of [angles] with [readout] (fitted by least squares when null), and the readout. */
        fun score(angles: Array<FloatArray>, readout: List<FloatArray>?): Pair<Double, List<FloatArray>> {
            val n = angles.size
            // Read out one angle after another, each over what those before it leave: read out together,
            // neighbouring vertices that swing nearly alike get large readouts that cancel.
            val b = readout ?: run {
                var left = target
                List(n) { k ->
                    val bk = regress(listOf(angles[k]), left, 1, m, weights).first.single()
                    if (k < n - 1) left = left.mapIndexed { f, row -> FloatArray(m) { d -> row[d] - angles[k][f] * bk[d] } }
                    bk
                }
            }
            val predicted = Array(frames) { f -> DoubleArray(m) { d -> var s = base?.get(f)?.get(d) ?: 0.0; for (k in 0 until n) s += angles[k][f] * b[k][d]; s } }
            var missed = 0.0; var stillMissed = 0.0
            for (f in 0 until frames) {
                val wf = w(f)
                for (d in 0 until m) {
                    val e = motion[f][d] - predicted[f][d]
                    missed += wf * e * e
                    if (still[f]) stillMissed += wf * e * e
                }
            }
            // Jerk is judged piece by piece: a pendulum that trembles through small swaying must not hide
            // behind the large swings of another piece.
            var jerky = 0.0
            for ((p, piece) in pieces.withIndex()) {
                if (targetJerk[p] <= 1e-12) continue
                val ratio = sqrt(jerk(piece.first, piece.second) { f, d -> predicted[f][d] } / targetJerk[p])
                val excess = maxOf(0.0, ratio - JERK_ALLOWANCE)
                jerky += excess * excess / pieces.size
            }
            val settle = if (stillTotal > total * 1e-3) SETTLE_COST * stillMissed / stillTotal else 0.0
            return missed / total + settle + JERK_COST * jerky to b
        }
    }

    /**
     * The sums alternating least squares needs for motion ≈ Σ_k B_k Σ_c w_c a_ck, taken once over the
     * frames: channel against channel (P) and channel against motion (Q). Each round is then independent
     * of the number of frames.
     */
    private class Gram(channels: List<Array<FloatArray>>, motion: List<FloatArray>) {
        val c = channels.size
        val n = channels.first().size
        val m = motion.first().size
        private val size = c * n
        /** P[x·size + y] = Σ_f a_x(f) a_y(f), x = j·n + k indexing channel j's vertex k. */
        private val p = DoubleArray(size * size)
        /** Q[x·m + d] = Σ_f a_x(f) y_d(f). */
        private val q = DoubleArray(size * m)
        private val total = motion.sumOf { row -> row.sumOf { (it * it).toDouble() } }

        init {
            val frames = motion.size
            val flat = Array(size) { channels[it / n][it % n] }
            for (x in 0 until size) {
                val ax = flat[x]
                for (y in x until size) {
                    val ay = flat[y]
                    var s = 0.0
                    for (f in 0 until frames) s += ax[f] * ay[f]
                    p[x * size + y] = s; p[y * size + x] = s
                }
                for (f in 0 until frames) {
                    val v = ax[f]
                    if (v == 0f) continue
                    val row = motion[f]
                    for (d in 0 until m) q[x * m + d] += v * row[d]
                }
            }
        }

        /** How much channel [j] moves on its own, summed over its vertices. */
        fun energy(j: Int): Double {
            var s = 0.0
            for (k in 0 until n) { val x = j * n + k; s += p[x * size + x] }
            return s
        }

        /** Rounds of readout-then-weights from [weights], updated in place and kept within ±1 / [NOMINAL]. Returns the error left. */
        fun als(weights: FloatArray): Double {
            var error = total
            for (round in 0..ALS_ROUNDS) {
                // The readout for the weights: G B = H.
                val g = Array(n) { DoubleArray(n) }
                val h = Array(n) { DoubleArray(m) }
                for (j in 0 until c) {
                    val wj = weights[j].toDouble()
                    if (wj == 0.0) continue
                    for (k in 0 until n) {
                        val x = j * n + k
                        for (d in 0 until m) h[k][d] += wj * q[x * m + d]
                        for (l in 0 until c) {
                            val wl = weights[l].toDouble()
                            if (wl == 0.0) continue
                            for (k2 in 0 until n) g[k][k2] += wj * wl * p[x * size + l * n + k2]
                        }
                    }
                }
                val scale = (0 until n).maxOf { g[it][it] }.coerceAtLeast(1e-12)
                val ridged = Array(n) { k -> DoubleArray(n) { g[k][it] + if (it == k) RIDGE * scale else 0.0 } }
                val b = solve(ridged, h) ?: return error
                error = total
                for (k in 0 until n) for (d in 0 until m) {
                    error -= 2 * b[k][d] * h[k][d]
                    for (k2 in 0 until n) error += b[k][d] * g[k][k2] * b[k2][d]
                }
                if (round == ALS_ROUNDS) break
                // The weights for the readout: M w = v, M_jl = Σ_kk' P(jk, lk') B_k·B_k', v_j = Σ_k Q(jk)·B_k.
                val bb = Array(n) { k -> DoubleArray(n) { k2 -> var s = 0.0; for (d in 0 until m) s += b[k][d] * b[k2][d]; s } }
                val mm = Array(c) { DoubleArray(c) }
                val v = Array(c) { DoubleArray(1) }
                for (j in 0 until c) for (k in 0 until n) {
                    val x = j * n + k
                    var s = 0.0
                    for (d in 0 until m) s += q[x * m + d] * b[k][d]
                    v[j][0] += s
                    for (l in 0 until c) for (k2 in 0 until n) mm[j][l] += p[x * size + l * n + k2] * bb[k][k2]
                }
                val diagonal = (0 until c).maxOf { mm[it][it] }.coerceAtLeast(1e-12)
                for (j in 0 until c) mm[j][j] += 1e-6 * diagonal
                val w = solve(mm, v) ?: break
                val most = (0 until c).maxOf { abs(w[it][0]) }
                if (most < 1e-9) break
                val shrink = if (most > 1.0 / NOMINAL) 1.0 / NOMINAL / most else 1.0
                for (j in 0 until c) weights[j] = (w[j][0] * shrink).toFloat()
            }
            return error.coerceAtLeast(0.0)
        }
    }

    /** The ridge least-squares readout of [signals] onto the motion and the error it leaves. */
    internal fun regress(signals: List<FloatArray>, motion: List<FloatArray>, n: Int, m: Int, weights: FloatArray? = null): Pair<List<FloatArray>, Double> {
        val frames = motion.size
        val total = (0 until frames).sumOf { f -> (weights?.get(f)?.toDouble() ?: 1.0) * motion[f].sumOf { (it * it).toDouble() } }
        // Normal equations (SᵀWS + λI) B = SᵀWY, n ≤ 3.
        val a = Array(n) { DoubleArray(n) }
        val b = Array(n) { DoubleArray(m) }
        for (f in 0 until frames) for (k in 0 until n) {
            val sk = signals[k][f].toDouble() * (weights?.get(f)?.toDouble() ?: 1.0)
            if (sk == 0.0) continue
            for (j in 0 until n) a[k][j] += sk * signals[j][f]
            val row = motion[f]
            for (d in 0 until m) b[k][d] += sk * row[d]
        }
        val scale = (0 until n).maxOf { a[it][it] }.coerceAtLeast(1e-12)
        for (k in 0 until n) a[k][k] += RIDGE * scale
        val solved = solve(a, b) ?: return List(n) { FloatArray(m) } to total
        // ‖Y - SB‖² = ‖Y‖² - 2 tr(BᵀSᵀY) + tr(BᵀSᵀSB), without another pass over the frames.
        var error = total
        for (k in 0 until n) for (d in 0 until m) {
            error -= 2 * solved[k][d] * b[k][d]
            for (j in 0 until n) error += solved[k][d] * (a[k][j] - if (j == k) RIDGE * scale else 0.0) * solved[j][d]
        }
        return solved.map { row -> FloatArray(m) { row[it].toFloat() } } to error.coerceAtLeast(0.0)
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    internal fun solve(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = a.map { it.copyOf() }.toTypedArray()
        val r = b.map { it.copyOf() }.toTypedArray()
        for (c in 0 until n) {
            val pivot = (c until n).maxBy { abs(m[it][c]) }
            if (abs(m[pivot][c]) < 1e-18) return null
            m[c] = m[pivot].also { m[pivot] = m[c] }; r[c] = r[pivot].also { r[pivot] = r[c] }
            for (i in 0 until n) if (i != c) {
                val f = m[i][c] / m[c][c]
                if (f == 0.0) continue
                for (j in c until n) m[i][j] -= f * m[c][j]
                for (j in r[i].indices) r[i][j] -= f * r[c][j]
            }
        }
        return Array(n) { i -> DoubleArray(r[i].size) { r[i][it] / m[i][i] } }
    }
}

/** Downhill simplex minimization; enough for a handful of smooth parameters. */
internal object NelderMead {
    fun minimize(start: DoubleArray, step: Double, f: (DoubleArray) -> Double, iterations: Int = 400, tolerance: Double = 1e-7): DoubleArray {
        val n = start.size
        val points = Array(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += step } }
        val values = DoubleArray(n + 1) { f(points[it]) }
        repeat(iterations) {
            val order = values.indices.sortedBy { values[it] }
            val sortedPoints = order.map { points[it] }; val sortedValues = order.map { values[it] }
            for (i in 0..n) { points[i] = sortedPoints[i]; values[i] = sortedValues[i] }
            if (abs(values[n] - values[0]) <= tolerance * (abs(values[0]) + 1e-12)) return points[0]
            val centroid = DoubleArray(n) { d -> (0 until n).sumOf { points[it][d] } / n }
            fun toward(t: Double) = DoubleArray(n) { d -> centroid[d] + t * (points[n][d] - centroid[d]) }
            val reflected = toward(-1.0); val fr = f(reflected)
            when {
                fr < values[0] -> {
                    val expanded = toward(-2.0); val fe = f(expanded)
                    if (fe < fr) { points[n] = expanded; values[n] = fe } else { points[n] = reflected; values[n] = fr }
                }
                fr < values[n - 1] -> { points[n] = reflected; values[n] = fr }
                else -> {
                    val contracted = if (fr < values[n]) toward(-0.5) else toward(0.5)
                    val fc = f(contracted)
                    if (fc < minOf(fr, values[n])) { points[n] = contracted; values[n] = fc } else {
                        for (i in 1..n) {
                            points[i] = DoubleArray(n) { d -> points[0][d] + 0.5 * (points[i][d] - points[0][d]) }
                            values[i] = f(points[i])
                        }
                    }
                }
            }
        }
        return points[values.indices.minBy { values[it] }]
    }
}
