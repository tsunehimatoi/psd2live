package org.umamo.interop.moc3.export

import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.RotationKeyform
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.format.moc3.model.WarpKeyform
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.moc3.convertPointsToMoc
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.format.moc3.model.Deformer as MocDeformer

/**
 * Lowers every deformer in the plan into its MOC3 record, warps and rotations alike.
 *
 * Kept as one pass over `plan.deformers` rather than two per-kind passes: the file stores deformers in
 * one unified list whose order is the addressing scheme, and the per-type columns are addressed through
 * it - so splitting the walk would mean rebuilding that correspondence twice.
 *
 * @param Moc3ExportContext context    The export's derived state.
 * @param Moc3KeyformPool   pool       Interned into: every deformer claims a binding index here.
 * @param Moc3ExportIds     ids        Claimed from: each deformer's written id.
 * @param Moc3ExportNotices noticeSink Appended to: demotions and unrepresentable grids.
 * @return List<MocDeformer> The records, in plan order.
 */
internal fun lowerDeformers(
	context: Moc3ExportContext,
	pool: Moc3KeyformPool,
	ids: Moc3ExportIds,
	noticeSink: Moc3ExportNotices,
): List<MocDeformer> {
	val plan = context.plan
	return plan.deformers.map { deformer ->
		val parentPartIndex = plan.partIndex(deformer.partId)
		val parentDeformerIndex = plan.deformerIndex(deformer.parent)
		val space = context.spaceOfParent(deformer.parent)
		when (deformer) {
			is Deformer.Warp -> {
				val keyforms =
					lowerObjectKeyforms(
						pool,
						deformer.geometryGrid,
						WarpLatticeInterpolator,
						deformer.channelGrids.onlyChannels(*renderChannels(context.colorsEnabled)),
						renderStatics(
							deformer.opacity,
							deformer.multiplyColor,
							deformer.screenColor,
							context.colorsEnabled,
						),
						requireGeometry = true,
					)
				noticeSink.reportDemotions(ExportEntityCategory.Deformer, deformer.id.raw, keyforms)
				if (keyforms == null) {
					noticeSink.unsupported(
						ExportEntityCategory.Deformer,
						deformer.id.raw,
						ExportNoticeReason.WarpDeformerHasNoLattice,
					)
				}
				val bundle = keyforms?.bundle
				// A MOC3 addresses an object's forms as `gridSize` rows from its own keyform base, and
				// the static binding 0 still declares ONE row - so an object that writes zero rows puts
				// its base on top of the next object's, and the last one sends the reader off the end of
				// the table.  Every object kind writes at least one form for that reason.
				val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
				// Unlike a drawable, which falls back to its rest mesh, a grid-less warp has no lattice
				// anywhere in the model - so the form it is obliged to write is a correctly SIZED empty
				// one.  The reader slices (rows + 1) x (columns + 1) control points back out by that
				// arithmetic, and a short array would desynchronize every warp block after it.
				val controlPointFloats = (deformer.rows + 1) * (deformer.columns + 1) * 2
				WarpDeformer(
					id = ids.deformerId(deformer.id),
					keyformBindingIndex = keyforms?.bindingIndex ?: 0,
					isVisible = deformer.isVisible,
					isEnabled = deformer.isEnabled,
					parentPartIndex = parentPartIndex,
					parentDeformerIndex = parentDeformerIndex,
					rows = deformer.rows,
					columns = deformer.columns,
					mode = if (deformer.isQuadTransform) 1 else 0,
					keyforms =
						(0 until cellCount).map { cellIndex ->
							val lattice =
								bundle?.cells?.getOrNull(cellIndex)?.geometry
									as? WarpLatticeForm
							WarpKeyform(
								convertPointsToMoc(
									space,
									lattice?.controlPoints ?: FloatArray(controlPointFloats),
									context.canvas,
								),
								bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, deformer.opacity) }
									?: deformer.opacity,
								colorOf(bundle, cellIndex, FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, context.colorsEnabled),
								colorOf(bundle, cellIndex, FormChannel.SCREEN_COLOR, deformer.screenColor, context.colorsEnabled),
							)
						},
				)
			}
			is Deformer.Rotation -> {
				val keyforms =
					lowerObjectKeyforms(
						pool,
						deformer.geometryGrid,
						RotationPivotInterpolator,
						deformer.channelGrids.onlyChannels(
							*(renderChannels(context.colorsEnabled) + arrayOf(FormChannel.FLIP_X, FormChannel.FLIP_Y)),
						),
						renderStatics(deformer.opacity, deformer.multiplyColor, deformer.screenColor, context.colorsEnabled) +
							mapOf(
								FormChannel.FLIP_X to ChannelValue.Flag(deformer.flipX),
								FormChannel.FLIP_Y to ChannelValue.Flag(deformer.flipY),
							),
						requireGeometry = true,
					)
				noticeSink.reportDemotions(ExportEntityCategory.Deformer, deformer.id.raw, keyforms)
				if (keyforms == null) {
					noticeSink.unsupported(
						ExportEntityCategory.Deformer,
						deformer.id.raw,
						ExportNoticeReason.RotationDeformerHasNoPivot,
					)
				}
				val bundle = keyforms?.bundle
				// One form minimum, for the same base-collision reason as the warp branch above.  A
				// pivot-less rotation has a well-defined fallback the warp does not: the identity
				// transform, which is what `pivot == null` already resolves to below.
				val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
				// Only the FIRST rotation on each root path carries the px->model factor.
				val scaleFactor =
					context.rotationScaleFactorFor(deformer)
				RotationDeformer(
					id = ids.deformerId(deformer.id),
					keyformBindingIndex = keyforms?.bindingIndex ?: 0,
					isVisible = deformer.isVisible,
					isEnabled = deformer.isEnabled,
					parentPartIndex = parentPartIndex,
					parentDeformerIndex = parentDeformerIndex,
					baseAngle = deformer.baseAngle,
					keyforms =
						(0 until cellCount).map { cellIndex ->
							val pivot =
								bundle?.cells?.getOrNull(cellIndex)?.geometry
									as? RotationPivotForm
							val origin =
								convertPointsToMoc(
									space,
									floatArrayOf(pivot?.originX ?: 0f, pivot?.originY ?: 0f),
									context.canvas,
								)
							RotationKeyform(
								originX = origin[0],
								originY = origin[1],
								angle = pivot?.angle ?: 0f,
								scale = (pivot?.scale ?: 1f) / (if (scaleFactor != 0f) scaleFactor else 1f),
								reflectX =
									bundle?.let { flagOf(it, cellIndex, FormChannel.FLIP_X, deformer.flipX) }
										?: deformer.flipX,
								reflectY =
									bundle?.let { flagOf(it, cellIndex, FormChannel.FLIP_Y, deformer.flipY) }
										?: deformer.flipY,
								opacity =
									bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, deformer.opacity) }
										?: deformer.opacity,
								multiplyColor =
									colorOf(bundle, cellIndex, FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, context.colorsEnabled),
								screenColor =
									colorOf(bundle, cellIndex, FormChannel.SCREEN_COLOR, deformer.screenColor, context.colorsEnabled),
							)
						},
				)
			}
		}
	}
}