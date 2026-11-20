package io.github.psd2live.core

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/** Evaluator tolerance and first-key tie breaking are shared by gesture and agent preparation. */
internal fun nearestKeyPose(axes: List<KeyformAxis>, pose: Map<ParameterId, Float>, defaults: Map<ParameterId, Float>): Map<ParameterId, Float> =
    buildMap {
        for ((parameter, tracks) in axes.groupBy { it.parameterId }) {
            val current = pose[parameter] ?: defaults[parameter] ?: continue
            val keys = tracks.flatMap { it.keys.toList() }.distinct().sorted()
            if (keys.isEmpty() || keys.any { abs(it - current) < EPS_KEY }) continue
            put(parameter, keys.minBy { abs(it - current) })
        }
    }
