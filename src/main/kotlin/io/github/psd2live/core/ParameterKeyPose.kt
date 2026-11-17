package io.github.psd2live.core

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

internal class NoCommonKeyform(val parameter: ParameterId) :
    IllegalArgumentException("No common keyform for ${parameter.raw}; select one track or add a shared key first")

/** Evaluator tolerance and first-key tie breaking are shared by gesture and agent preparation. */
internal fun nearestKeyPose(axes: List<KeyformAxis>, pose: Map<ParameterId, Float>, defaults: Map<ParameterId, Float>): Map<ParameterId, Float> =
    buildMap {
        for ((parameter, tracks) in axes.groupBy { it.parameterId }) {
            val current = pose[parameter] ?: defaults[parameter] ?: continue
            if (tracks.all { axis -> axis.keys.any { abs(it - current) < EPS_KEY } }) continue
            // Choose once across every target. Sequential snapping can bounce between disjoint grids.
            val common = tracks.flatMap { it.keys.toList() }.distinct().sorted().filter { candidate ->
                tracks.all { axis -> axis.keys.any { abs(it - candidate) < EPS_KEY } }
            }
            if (common.isEmpty()) throw NoCommonKeyform(parameter)
            put(parameter, common.minBy { abs(it - current) })
        }
    }
