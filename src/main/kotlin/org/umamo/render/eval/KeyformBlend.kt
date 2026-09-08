package org.umamo.render.eval

import org.umamo.runtime.model.Keyform

/**
 * Multilinear blend for a single parameter axis: `base + Σ wᵢ·Δᵢ`.
 *
 * A 1-D isolation of the blend math also used, generalized to N dimensions, by the real keyform-grid
 * blend in [blendLocalFromCorners]. Kept as a free function so it's trivially testable on its own.
 *
 * 単一パラメータ軸の多重線形ブレンド。実 N 次元評価の 1 次元プレースホルダ。
 */
fun blend(base: FloatArray, keyforms: List<Pair<Float, Keyform>>): FloatArray {
	val result = base.copyOf()
	for ((weight, keyform) in keyforms) {
		for (i in result.indices) {
			result[i] += weight * keyform.delta[i]
		}
	}
	return result
}