package org.umamo.interop.moc3.import

import org.umamo.format.moc3.model.BlendShapeKeyform
import org.umamo.format.moc3.model.Rgb
import org.umamo.interop.moc3.PointSpace
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.flagAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.format.moc3.model.BlendShape as MocBlendShape

/*
 * Blend-shape ingest (MOC3 v4+ §5.6), one builder per target kind.
 *
 * A moc stores a blend key as a DELTA against the object's pose at the parameter defaults, while the
 * runtime stores the referenced value itself.  So every builder here does the same two things: sample
 * the owner's grid at the default pose to recover that reference, then add each stored row onto it.
 * The evaluator subtracts the same reference back out, which is why the reference has to be sampled the
 * way it samples it - `BlendShapeChannelOracleTest` and `BlendShapeOracleTest` are what hold the two
 * together, since a reference that is wrong by a constant still round-trips through our own export.
 *
 * Every builder runs on an object that is already CONSTRUCTED, because the object's own keyform grid is
 * the reference source.
 */

/**
 * Maps [records] onto [drawable] as mesh blend bindings: each stored delta row plus the
 * grid-at-default reference (positions, draw order, opacity), converted to runtime space.
 *
 * @param Moc3ImportContext   context  The import's derived state.
 * @param Drawable            drawable The constructed runtime drawable (its grid is the reference source).
 * @param PointSpace          space    The drawable's stored point space.
 * @param List<MocBlendShape> records  The drawable's records.
 * @return List<BlendShapeBinding<MeshForm>> The runtime bindings.
 */
internal fun meshBlendShapesOf(
	context: Moc3ImportContext,
	drawable: Drawable,
	space: PointSpace,
	records: List<MocBlendShape>,
): List<BlendShapeBinding<MeshForm>> {
	val defaultValue: (ParameterId) -> Float = context::defaultValueOf
	val referenceDeltas = meshGridDefaultDeltas(drawable, defaultValue) ?: FloatArray(0)
	// The scalar reference is each channel's own value at the DEFAULT pose. An untracked or
	// out-of-range channel falls back to the drawable's static, which for an imported drawable is
	// Cubism's 500 / full opacity - the same fallback meshBlendState uses, so the evaluator's
	// subtraction cancels exactly even for an ungridded drawable.
	val referenceDrawOrder =
		drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, defaultValue)
	val referenceOpacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, defaultValue)
	val referenceMultiply =
		drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, defaultValue)
	val referenceScreen =
		drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, defaultValue)
	return records.mapNotNull { record ->
		val payloads =
			record.keyforms.map { keyform ->
				(keyform as? BlendShapeKeyform.Mesh)?.form ?: return@mapNotNull null
			}
		if (payloads.size != record.keyPositions.size) {
			return@mapNotNull null
		}
		bindingOfRecord(context, record) { keyIndex ->
			MeshForm(
				positionDeltas =
					addReference(
						referenceDeltas,
						context.convertDeltas(space, payloads[keyIndex].vertexPositions),
					),
				drawOrder = referenceDrawOrder + payloads[keyIndex].drawOrder,
				opacity = referenceOpacity + payloads[keyIndex].opacity,
				// Color delta rows are ADDITIVE like the scalars, so their identity is zero rather
				// than Cubism's white multiply / black screen; a record without color tables has no
				// row at all and contributes nothing.
				multiplyColor = addColorDelta(referenceMultiply, payloads[keyIndex].multiplyColor),
				screenColor = addColorDelta(referenceScreen, payloads[keyIndex].screenColor),
			)
		}
	}
}

/**
 * Maps [records] onto [warp] as lattice blend bindings: each stored control-point delta row
 * plus the lattice's grid-at-default reference, and the same treatment for the deformer's own
 * render channels (opacity, multiply / screen color), which CASCADE onto every drawable
 * underneath.
 *
 * @param Moc3ImportContext   context The import's derived state.
 * @param Deformer.Warp       warp    The constructed runtime warp (its grid is the reference source).
 * @param PointSpace          space   The warp's stored point space.
 * @param List<MocBlendShape> records The warp's records.
 * @return List<BlendShapeBinding<WarpForm>> The runtime bindings.
 */
internal fun warpBlendShapesOf(
	context: Moc3ImportContext,
	warp: Deformer.Warp,
	space: PointSpace,
	records: List<MocBlendShape>,
): List<BlendShapeBinding<WarpForm>> {
	val defaultValue: (ParameterId) -> Float = context::defaultValueOf
	val reference = warpControlPointsAt(warp.geometryGrid, defaultValue) ?: FloatArray(0)
	val referenceOpacity = warp.channelGrids.scalarAt(FormChannel.OPACITY, warp.opacity, defaultValue)
	val referenceMultiply =
		warp.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, warp.multiplyColor, defaultValue)
	val referenceScreen = warp.channelGrids.colorAt(FormChannel.SCREEN_COLOR, warp.screenColor, defaultValue)
	return records.mapNotNull { record ->
		val payloads =
			record.keyforms.map { keyform ->
				(keyform as? BlendShapeKeyform.Warp)?.form ?: return@mapNotNull null
			}
		if (payloads.size != record.keyPositions.size) {
			return@mapNotNull null
		}
		bindingOfRecord(context, record) { keyIndex ->
			WarpForm(
				addReference(reference, context.convertDeltas(space, payloads[keyIndex].controlPoints)),
				opacity = referenceOpacity + payloads[keyIndex].opacity,
				multiplyColor = addColorDelta(referenceMultiply, payloads[keyIndex].multiplyColor),
				screenColor = addColorDelta(referenceScreen, payloads[keyIndex].screenColor),
			)
		}
	}
}

/**
 * Maps [records] onto [rotation] as affine blend bindings: origin/angle/scale delta rows plus
 * the grid-at-default reference.  The scale delta carries the same px→model seam factor as
 * the grid keyforms; flips are not blendable, so the FLIP tracks' value at the default pose
 * fills the form.  The deformer's own opacity/color rows get the same reference treatment as
 * the geometry and CASCADE onto every drawable underneath (see `DeformerCascade`).
 *
 * @param Moc3ImportContext   context     The import's derived state.
 * @param Deformer.Rotation   rotation    The constructed runtime rotation (reference source).
 * @param PointSpace          space       The rotation's stored point space.
 * @param Float               scaleFactor The px→model seam factor (1 under a rotation ancestor, else ppu).
 * @param List<MocBlendShape> records     The rotation's records.
 * @return List<BlendShapeBinding<RotationForm>> The runtime bindings.
 */
internal fun rotationBlendShapesOf(
	context: Moc3ImportContext,
	rotation: Deformer.Rotation,
	space: PointSpace,
	scaleFactor: Float,
	records: List<MocBlendShape>,
): List<BlendShapeBinding<RotationForm>> {
	val defaultValue: (ParameterId) -> Float = context::defaultValueOf
	// The fallback mirrors rotationBlendDeltas' (identity transform, scale 1) so the
	// evaluator's subtraction cancels exactly even for an unkeyed rotation.
	val reference =
		rotationFormAt(rotation.geometryGrid, defaultValue)
			?: RotationPivotForm(0f, 0f, 0f, 1f)
	val referenceOpacity = rotation.channelGrids.scalarAt(FormChannel.OPACITY, rotation.opacity, defaultValue)
	val referenceMultiply =
		rotation.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, rotation.multiplyColor, defaultValue)
	val referenceScreen =
		rotation.channelGrids.colorAt(FormChannel.SCREEN_COLOR, rotation.screenColor, defaultValue)
	return records.mapNotNull { record ->
		val payloads =
			record.keyforms.map { keyform ->
				(keyform as? BlendShapeKeyform.Rotation)?.form ?: return@mapNotNull null
			}
		if (payloads.size != record.keyPositions.size) {
			return@mapNotNull null
		}
		bindingOfRecord(context, record) { keyIndex ->
			val originDelta =
				context.convertDeltas(space, floatArrayOf(payloads[keyIndex].originX, payloads[keyIndex].originY))
			RotationForm(
				originX = reference.originX + originDelta[0],
				originY = reference.originY + originDelta[1],
				angle = reference.angle + payloads[keyIndex].angle,
				scale = reference.scale + payloads[keyIndex].scale * scaleFactor,
				// The reference pivot carries no flips - reflections are FLAG channels on the deformer,
				// and a blend shape never varies them (MOC3 stores no flip delta rows).  Sampled from
				// the FLIP tracks at the default pose: at this point in the import the statics are still
				// their constructor defaults (compaction lifts constant flip tracks only at the end),
				// so reading rotation.flipX here would be constant false and drop the reflection.
				flipX = rotation.channelGrids.flagAt(FormChannel.FLIP_X, rotation.flipX, defaultValue),
				flipY = rotation.channelGrids.flagAt(FormChannel.FLIP_Y, rotation.flipY, defaultValue),
				opacity = referenceOpacity + payloads[keyIndex].opacity,
				multiplyColor = addColorDelta(referenceMultiply, payloads[keyIndex].multiplyColor),
				screenColor = addColorDelta(referenceScreen, payloads[keyIndex].screenColor),
			)
		}
	}
}

/**
 * Maps [records] onto a part as draw-order blend bindings.
 *
 * A part's only blendable channel is its draw order, so a record carries a single scalar delta
 * per key.  It gets the same reference treatment as every other blend payload - the stored delta
 * plus the channel's value at the DEFAULT pose - so the evaluator's subtraction cancels exactly.
 *
 * @param Moc3ImportContext   context      The import's derived state.
 * @param Float               staticOrder  The part's static draw order.
 * @param ChannelGrids        channelGrids The part's own keyform tracks (the reference source).
 * @param List<MocBlendShape> records      The part's records.
 * @return List<BlendShapeBinding<PartForm>> The runtime bindings.
 */
internal fun partBlendShapesOf(
	context: Moc3ImportContext,
	staticOrder: Float,
	channelGrids: ChannelGrids,
	records: List<MocBlendShape>,
): List<BlendShapeBinding<PartForm>> {
	val defaultValue: (ParameterId) -> Float = context::defaultValueOf
	val referenceDrawOrder = channelGrids.scalarAt(FormChannel.DRAW_ORDER, staticOrder, defaultValue)
	return records.mapNotNull { record ->
		val payloads =
			record.keyforms.map { keyform ->
				(keyform as? BlendShapeKeyform.Part)?.drawOrderDelta ?: return@mapNotNull null
			}
		if (payloads.size != record.keyPositions.size) {
			return@mapNotNull null
		}
		bindingOfRecord(context, record) { keyIndex ->
			PartForm(drawOrder = referenceDrawOrder + payloads[keyIndex])
		}
	}
}

/**
 * Assembles one runtime binding from a record: the moc keys already include the inserted
 * neutral, whose form slot imports as null (the stored neutral delta row is all-zero).
 *
 * @param Moc3ImportContext context The import's derived state, for the parameter id table.
 * @param MocBlendShape     record  The record to map.
 * @param Function          formAt  Builds the synthesized runtime form for a non-neutral key.
 * @return BlendShapeBinding<TForm> The runtime binding.
 */
private fun <TForm : Any> bindingOfRecord(
	context: Moc3ImportContext,
	record: MocBlendShape,
	formAt: (keyIndex: Int) -> TForm?,
): BlendShapeBinding<TForm> =
	BlendShapeBinding(
		parameterId = context.parameterIds.getOrElse(record.parameterIndex) { ParameterId("") },
		keys = record.keyPositions.copyOf(),
		neutralIndex = record.neutralKeyIndex,
		forms =
			record.keyPositions.indices.map { keyIndex ->
				if (keyIndex == record.neutralKeyIndex) null else formAt(keyIndex)
			},
		limits = blendLimitsOf(context, record),
	)

/**
 * Maps a record's limit curves to the runtime's min-combined [BlendWeightLimit] list.
 *
 * @param Moc3ImportContext context The import's derived state, for the parameter id table.
 * @param MocBlendShape     record  The record whose limits to map.
 * @return List<BlendWeightLimit> The runtime limit curves.
 */
private fun blendLimitsOf(
	context: Moc3ImportContext,
	record: MocBlendShape,
): List<BlendWeightLimit> =
	record.limits.map { limit ->
		BlendWeightLimit(
			parameterId = context.parameterIds.getOrElse(limit.parameterIndex) { ParameterId("") },
			points =
				limit.keyPositions.indices.map { pointIndex ->
					BlendWeightLimitPoint(limit.keyPositions[pointIndex], limit.weights[pointIndex])
				},
		)
	}

/**
 * Elementwise sum of [reference] and [deltas] (sized like [deltas]; a size-mismatched
 * reference contributes only its overlapping prefix, mirroring the evaluator's guards).
 *
 * @param FloatArray reference The grid-at-default reference components.
 * @param FloatArray deltas    The converted delta components.
 * @return FloatArray The synthesized absolute/rest-relative components.
 */
private fun addReference(
	reference: FloatArray,
	deltas: FloatArray,
): FloatArray =
	FloatArray(deltas.size) { componentIndex ->
		deltas[componentIndex] + (reference.getOrNull(componentIndex) ?: 0f)
	}

/**
 * Adds a color delta row onto its grid-at-default reference, the color analogue of
 * [addReference].
 *
 * A blend record's color rows are ADDITIVE, so a zero row is the identity and a null row (a
 * model whose color tables are absent entirely, pre-4.2) contributes nothing.  Not clamped
 * here: the evaluator subtracts this same reference back out and clamps only after summing every
 * contribution, so clamping now would bias a record whose neighbors pull the other way.
 *
 * @param ColorRgb reference The channel's value at the default pose.
 * @param Rgb?     delta     The record's stored delta row, or null when the model has no colors.
 * @return ColorRgb The referenced color this key blends toward.
 */
private fun addColorDelta(
	reference: ColorRgb,
	delta: Rgb?,
): ColorRgb =
	if (delta == null) {
		reference
	} else {
		ColorRgb(reference.red + delta.r, reference.green + delta.g, reference.blue + delta.b)
	}