package org.umamo.interop.moc3.export

import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.format.moc3.model.BlendShape
import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.BlendShapeLimit
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpKeyform
import org.umamo.interop.moc3.MocCanvasMapping
import org.umamo.interop.moc3.PointSpace
import org.umamo.interop.moc3.convertDeltasToMoc
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm

/*
 * Lowers the runtime's blend-shape bindings back into MOC3 records.
 *
 * A stored MOC3 blend row is a pure DELTA; the runtime form is that delta plus the object's grid form
 * interpolated at the DEFAULT pose, which the import added so the evaluator can subtract the same
 * reference back out.  Exporting therefore means subtracting that reference again - and it has to be
 * the SAME reference, computed by the same `org.umamo.runtime.eval` samplers the evaluator calls, or
 * the loop does not close.
 *
 * The reference comes from the SESSION grid, not from a refined copy.  That is the fixed point: the
 * evaluator subtracts whatever grid the model currently holds, a re-import of this export rebuilds
 * exactly that grid, so sampling the session grid makes export → import → eval cancel.  (Compaction's
 * refinement is bit-exact, so the two sample identically anyway - but only one of them is the
 * definition.)
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */

/**
 * Builds every blend-shape record for the document.
 *
 * `recordBase` is left at 0 deliberately: `MocLowering.blendShapeSections` recomputes it as a running
 * per-kind cursor over the value tables, so a value carried here would be ignored at best and stale at
 * worst.
 *
 * @param List             drawables     The drawables in file order.
 * @param List             deformers     The deformers in file order.
 * @param List             parts         The parts in file order.
 * @param List             parameters    The parameters in file order.
 * @param Moc3IndexPlan    plan          The index plan.
 * @param MocCanvasMapping canvas        The canvas mapping, for un-converting positional deltas.
 * @param Function         spaceOfParent The space a child of the given parent deformer stores its positions in.
 * @param Function         scaleFactorOf The px→model factor for a rotation deformer.
 * @param Boolean          colorsEnabled Whether the target version carries color tables.
 * @return List<BlendShape> The records, in no particular order (the lowering orders them).
 */
internal fun lowerBlendShapes(
	drawables: List<Drawable>,
	deformers: List<Deformer>,
	parts: List<Part>,
	parameters: List<Parameter>,
	plan: Moc3IndexPlan,
	canvas: MocCanvasMapping,
	spaceOfParent: (DeformerId?) -> PointSpace,
	scaleFactorOf: (Deformer.Rotation) -> Float,
	colorsEnabled: Boolean,
): List<BlendShape> {
	val defaultByParameter = parameters.associate { parameter -> parameter.id to parameter.default }
	val defaultValue: (ParameterId) -> Float = { id -> defaultByParameter[id] ?: 0f }
	val records = ArrayList<BlendShape>()

	/**
	 * The delta of a color channel against its reference, or null when colors are not written.
	 *
	 * @param ColorRgb form      The form's color (reference + delta).
	 * @param ColorRgb reference The channel's value at the default pose.
	 * @return Rgb? The stored delta row.
	 */
	fun colorDelta(form: ColorRgb, reference: ColorRgb): Rgb? =
		if (colorsEnabled) {
			Rgb(form.red - reference.red, form.green - reference.green, form.blue - reference.blue)
		} else {
			null
		}

	/**
	 * Assembles one record from a binding, given a per-key payload builder.
	 *
	 * The NEUTRAL key's form is null in the runtime binding and its stored row is all zeros, which the
	 * builder produces naturally by receiving null.
	 *
	 * @param BlendShapeTarget target      The owner kind.
	 * @param Int              localIndex  The owner's kind-local index.
	 * @param BlendShapeBinding binding    The runtime binding.
	 * @param Function         payloadOf   Builds one key's delta payload (null form = the neutral row).
	 */
	fun <TForm : Any> addRecord(
		target: BlendShapeTarget,
		localIndex: Int,
		binding: BlendShapeBinding<TForm>,
		payloadOf: (TForm?) -> BlendShapeKeyform,
	) {
		val parameterIndex = plan.parameterIndex(binding.parameterId)
		if (parameterIndex < 0) {
			return
		}
		records.add(
			BlendShape(
				target = target,
				targetIndex = localIndex,
				parameterIndex = parameterIndex,
				keyPositions = binding.keys.copyOf(),
				neutralKeyIndex = binding.neutralIndex,
				// Recomputed by the lowering; see this function's docblock.
				recordBase = 0,
				limits =
					binding.limits.mapNotNull { limit ->
						val limitParameter = plan.parameterIndex(limit.parameterId)
						if (limitParameter < 0) {
							return@mapNotNull null
						}
						BlendShapeLimit(
							parameterIndex = limitParameter,
							keyPositions = limit.points.map { point -> point.value }.toFloatArray(),
							weights = limit.points.map { point -> point.weight }.toFloatArray(),
						)
					},
				keyforms = binding.forms.map(payloadOf),
			),
		)
	}

	// ---- drawables ----
	for (drawable in drawables) {
		if (drawable.blendShapes.isEmpty()) {
			continue
		}
		val localIndex = plan.drawableIndex(drawable.id)
		val space = spaceOfParent(drawable.parentDeformerId)
		val referenceDeltas = meshGridDefaultDeltas(drawable, defaultValue) ?: FloatArray(0)
		val referenceDrawOrder =
			drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, defaultValue)
		val referenceOpacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, defaultValue)
		val referenceMultiply =
			drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, defaultValue)
		val referenceScreen =
			drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, defaultValue)
		val vertexCount = drawable.mesh?.positions?.size ?: 0
		for (binding in drawable.blendShapes) {
			addRecord(BlendShapeTarget.ART_MESH, localIndex, binding) { form: MeshForm? ->
				BlendShapeKeyform.Mesh(
					ArtMeshKeyform(
						vertexPositions =
							if (form == null) {
								FloatArray(vertexCount)
							} else {
								convertDeltasToMoc(
									space,
									FloatArray(vertexCount) { component ->
										form.positionDeltas.getOrElse(component) { 0f } -
											referenceDeltas.getOrElse(component) { 0f }
									},
									canvas,
								)
							},
						opacity = if (form == null) 0f else form.opacity - referenceOpacity,
						drawOrder = if (form == null) 0f else form.drawOrder - referenceDrawOrder,
						multiplyColor =
							if (form == null) {
								colorDelta(referenceMultiply, referenceMultiply)
							} else {
								colorDelta(form.multiplyColor, referenceMultiply)
							},
						screenColor =
							if (form == null) {
								colorDelta(referenceScreen, referenceScreen)
							} else {
								colorDelta(form.screenColor, referenceScreen)
							},
					),
				)
			}
		}
	}

	// ---- deformers ----
	for (deformer in deformers) {
		when (deformer) {
			is Deformer.Warp -> {
				if (deformer.blendShapes.isEmpty()) {
					continue
				}
				// Resolved only once the deformer is known to carry blend shapes: `spaceOfParent` is a
				// lookup the caller supplies, and asking it for every deformer in the rig would pay for a
				// result that is discarded on all of them in a model with no blend shapes.
				val space = spaceOfParent(deformer.parent)
				// The UNIFIED deformer index, not the kind-local one: `MocLowering` maps it through
				// `warpLocalByDeformer` itself, so handing it a kind-local index would re-map an already
				// -mapped value and name a different deformer.  The decoder produces the same convention.
				val localIndex = plan.deformerIndex(deformer.id)
				val reference = warpControlPointsAt(deformer.geometryGrid, defaultValue) ?: FloatArray(0)
				val referenceOpacity =
					deformer.channelGrids.scalarAt(FormChannel.OPACITY, deformer.opacity, defaultValue)
				val referenceMultiply =
					deformer.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, defaultValue)
				val referenceScreen =
					deformer.channelGrids.colorAt(FormChannel.SCREEN_COLOR, deformer.screenColor, defaultValue)
				val pointCount = (deformer.rows + 1) * (deformer.columns + 1) * 2
				for (binding in deformer.blendShapes) {
					addRecord(BlendShapeTarget.WARP, localIndex, binding) { form: WarpForm? ->
						BlendShapeKeyform.Warp(
							WarpKeyform(
								controlPoints =
									if (form == null) {
										FloatArray(pointCount)
									} else {
										convertDeltasToMoc(
											space,
											FloatArray(pointCount) { component ->
												form.controlPoints.getOrElse(component) { 0f } -
													reference.getOrElse(component) { 0f }
											},
											canvas,
										)
									},
								opacity = if (form == null) 0f else form.opacity - referenceOpacity,
								multiplyColor =
									colorDelta(form?.multiplyColor ?: referenceMultiply, referenceMultiply),
								screenColor = colorDelta(form?.screenColor ?: referenceScreen, referenceScreen),
							),
						)
					}
				}
			}
			is Deformer.Rotation -> {
				if (deformer.blendShapes.isEmpty()) {
					continue
				}
				// Resolved after the guard; see the warp branch.
				val space = spaceOfParent(deformer.parent)
				// Unified, not kind-local; see the warp branch.
				val localIndex = plan.deformerIndex(deformer.id)
				val reference =
					rotationFormAt(deformer.geometryGrid, defaultValue) ?: RotationPivotForm(0f, 0f, 0f, 1f)
				val referenceOpacity =
					deformer.channelGrids.scalarAt(FormChannel.OPACITY, deformer.opacity, defaultValue)
				val referenceMultiply =
					deformer.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, defaultValue)
				val referenceScreen =
					deformer.channelGrids.colorAt(FormChannel.SCREEN_COLOR, deformer.screenColor, defaultValue)
				val scaleFactor = scaleFactorOf(deformer)
				for (binding in deformer.blendShapes) {
					addRecord(BlendShapeTarget.ROTATION, localIndex, binding) { form: RotationForm? ->
						val originDelta =
							if (form == null) {
								floatArrayOf(0f, 0f)
							} else {
								convertDeltasToMoc(
									space,
									floatArrayOf(
										form.originX - reference.originX,
										form.originY - reference.originY,
									),
									canvas,
								)
							}
						BlendShapeKeyform.Rotation(
							RotationKeyform(
								originX = originDelta[0],
								originY = originDelta[1],
								angle = if (form == null) 0f else form.angle - reference.angle,
								scale =
									if (form == null) {
										0f
									} else {
										(form.scale - reference.scale) / (if (scaleFactor != 0f) scaleFactor else 1f)
									},
								// Reflections are not blendable: MOC3 stores no flip delta row, so the stored
								// value is the record's own neutral rather than a difference.
								reflectX = false,
								reflectY = false,
								opacity = if (form == null) 0f else form.opacity - referenceOpacity,
								multiplyColor =
									colorDelta(form?.multiplyColor ?: referenceMultiply, referenceMultiply),
								screenColor = colorDelta(form?.screenColor ?: referenceScreen, referenceScreen),
							),
						)
					}
				}
			}
		}
	}

	// ---- parts ----
	for (part in parts) {
		if (part.blendShapes.isEmpty()) {
			continue
		}
		val localIndex = plan.partIndex(part.id)
		val referenceDrawOrder =
			part.channelGrids.scalarAt(FormChannel.DRAW_ORDER, part.drawOrder.toFloat(), defaultValue)
		for (binding in part.blendShapes) {
			addRecord(BlendShapeTarget.PART, localIndex, binding) { form: PartForm? ->
				BlendShapeKeyform.Part(if (form == null) 0f else form.drawOrder - referenceDrawOrder)
			}
		}
	}
	return records
}