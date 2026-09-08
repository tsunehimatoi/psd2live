package org.umamo.runtime.model

/**
 * One point of a blend-weight limit curve: at [value] on the constraint parameter, the blend
 * shape's weight is capped to [weight].
 *
 * ブレンドウェイト制限カーブの 1 点。制約パラメータが [value] のとき重みは [weight] に制限される。
 *
 * @property Float value  The constraint parameter's value this point sits at.
 * @property Float weight The weight cap at that value (0..1).
 */
data class BlendWeightLimitPoint(
	val value: Float,
	val weight: Float,
)

/**
 * A blend-weight limit ("blend shape limit" in the Cubism editor): a piecewise-linear, end-clamped
 * curve over ANOTHER parameter's value that caps a blend shape's weight. A binding's effective
 * multiplier is the MINIMUM over all of its limits (1 when it has none).
 *
 * CMO3: MorphTargetBlendWeightConstraint - the flat (constraintParameterGuid,
 * constraintParameterValue, blendWeight) records grouped per constraint parameter into one curve.
 *
 * ブレンドウェイト制限。別パラメータ値に対する区分線形カーブで重みを制限し、複数あれば最小値を取る。
 *
 * @property ParameterId parameterId The constraint parameter whose value drives this curve.
 * @property List        points      The curve points, sorted ascending by value.
 */
data class BlendWeightLimit(
	val parameterId: ParameterId,
	val points: List<BlendWeightLimitPoint>,
)

/**
 * A blend shape ("MorphTarget" in CMO3): an ADDITIVE deformation on one object, driven by one
 * parameter. Where a keyform grid interpolates BETWEEN absolute forms (parameters select shapes),
 * a blend shape ADDS a weighted delta on top of the grid result (parameters add shapes), and
 * multiple bindings on the same object stack by summation.
 *
 * [keys] always contains the neutral key at parameter VALUE 0, not at the parameter's default.
 * Ingest inserts the neutral when the document does not key value 0, so [forms] at [neutralIndex]
 * is null in practice (a morph authored exactly at 0 would occupy the slot but is never read - the
 * neutral key contributes zero by definition, and baked files store an all-zero delta row there).
 * A morph authored AT the parameter's default (non-zero) is an ordinary non-neutral key: the
 * Umamo C++ runtime applies it at the default pose.  Non-neutral forms are stored exactly as the
 * object's grid stores its forms ([MeshForm] position deltas are relative to the rest mesh,
 * [WarpForm]/[RotationForm] are absolute); deriving the neutral-relative delta each key contributes
 * is the evaluator's job, not this type's.
 *
 * CMO3: ACParameterControllableSource.keyformMorphTargetSet -> KeyFormMorphTargetSet - one
 * KeyFormMorphTarget per (parameterGuid, keyValue, keyformGuid); one binding groups a source's
 * morph targets sharing a driving parameter.
 *
 * ブレンドシェイプ（CMO3 の MorphTarget）。格子補間の結果に加算される差分で、同一オブジェクト上の
 * 複数バインディングは合算される。[neutralIndex] は寄与ゼロのキー（フォームは null）。
 *
 * @property ParameterId parameterId  The driving parameter.
 * @property FloatArray  keys         The parameter values the forms are keyed at, ascending,
 *                                    including the inserted neutral key.
 * @property Int         neutralIndex Index into [keys] of the zero-contribution (rest) key.
 * @property List        forms        One form per key, parallel to [keys]; null at the neutral
 *                                    key when the neutral was inserted rather than authored.
 * @property List        limits       Blend-weight limit curves; the effective weight multiplier is
 *                                    their minimum (1 when empty).
 */
data class BlendShapeBinding<TForm : Any>(
	val parameterId: ParameterId,
	val keys: FloatArray,
	val neutralIndex: Int,
	val forms: List<TForm?>,
	val limits: List<BlendWeightLimit> = emptyList(),
) {
	// data class with an array field: generated equals/hashCode use referential array equality,
	// which is wrong for value semantics (same rationale as Keyform).
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is BlendShapeBinding<*>) return false
		return parameterId == other.parameterId &&
			keys.contentEquals(other.keys) &&
			neutralIndex == other.neutralIndex &&
			forms == other.forms &&
			limits == other.limits
	}

	override fun hashCode(): Int {
		var hash = parameterId.hashCode()
		hash = 31 * hash + keys.contentHashCode()
		hash = 31 * hash + neutralIndex
		hash = 31 * hash + forms.hashCode()
		hash = 31 * hash + limits.hashCode()
		return hash
	}
}