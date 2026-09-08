package org.umamo.edit

import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.PuppetModel

/**
 * A copy of this model with parameter [id]'s range set to [min]..[max] and its default clamped within
 * it. The range is normalized so the stored minimum never exceeds the maximum regardless of entry order
 * (typing a maximum below the current minimum still yields a valid range). The default is coerced into
 * the resulting range. Returns this same instance unchanged when nothing actually differs (so the caller
 * need not pre-check) or when [id] is not a parameter of this model.
 *
 * Pure over the model: it does not touch the live pose. Re-clamping the live value into a shrunk range
 * is the session's concern, since the pose is editor state held outside the model.
 *
 * パラメータ [id] の範囲（最小・最大）と既定値を設定したモデルのコピー。範囲は最小≤最大に正規化し、
 * 既定値は範囲内に丸める。変化が無ければ同一インスタンスを返す。ライブのポーズには触れない。
 *
 * @param ParameterId id The parameter to retarget.
 * @param Float min The requested minimum.
 * @param Float default The requested default (clamped into the resulting range).
 * @param Float max The requested maximum.
 * @return PuppetModel This model with the parameter's range and default updated, or this if unchanged.
 */
fun PuppetModel.withParameterRange(id: ParameterId, min: Float, default: Float, max: Float): PuppetModel {
	val low = minOf(min, max)
	val high = maxOf(min, max)
	val clampedDefault = default.coerceIn(low, high)
	val updated =
		parameters.map { parameter ->
			if (parameter.id == id) {
				parameter.copy(min = low, max = high, default = clampedDefault)
			} else {
				parameter
			}
		}
	if (updated == parameters) {
		return this
	}
	return copy(parameters = updated)
}

/**
 * A copy of this model with the parameter link [horizontal]->[vertical] added ([linked] = true) or
 * removed ([linked] = false). The model edit is the invariant keeper - any UI-side validity check is
 * advisory only - so an invalid request returns this same instance unchanged (and the session's
 * same-instance short-circuit then records no undo step): a link is refused when either id already
 * belongs to any existing link, when the ids are equal, or when either id is not a parameter of this
 * model; an unlink of a pair that is not present is a no-op.
 *
 * Pure over the model: values, ranges, and the live pose are untouched - a link only changes how the
 * pair presents (one 2D pad instead of two sliders).
 *
 * // CMO3: CParameterSource.combined - the first member flags the pair, the partner is the next
 * // parameter in document order (see Cmo3Import's link derivation).
 *
 * パラメータリンクを追加または削除したモデルのコピー。無効な要求（既にリンク済み・同一 ID・未知の
 * ID・存在しないペアの解除）は同一インスタンスを返し、取り消し段も記録されない。値やポーズには
 * 触れない。
 *
 * @param ParameterId horizontal The X-axis (upper) parameter.
 * @param ParameterId vertical The Y-axis parameter (the next parameter below in panel order).
 * @param Boolean linked True to create the link, false to remove it.
 * @return PuppetModel This model with the link added / removed, or this same instance when refused.
 */
fun PuppetModel.withParameterLink(horizontal: ParameterId, vertical: ParameterId, linked: Boolean): PuppetModel {
	if (linked) {
		if (horizontal == vertical) {
			return this
		}
		val knownIds = parameters.map { parameter -> parameter.id }.toSet()
		if (horizontal !in knownIds || vertical !in knownIds) {
			return this
		}
		val linkedMembers = parameterLinks.flatMap { link -> listOf(link.horizontal, link.vertical) }.toSet()
		if (horizontal in linkedMembers || vertical in linkedMembers) {
			return this
		}
		return copy(parameterLinks = parameterLinks + ParameterLink(horizontal, vertical))
	}
	val remaining = parameterLinks.filterNot { link -> link.horizontal == horizontal && link.vertical == vertical }
	if (remaining.size == parameterLinks.size) {
		return this
	}
	return copy(parameterLinks = remaining)
}