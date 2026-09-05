package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.math.max

/**
 * Geometry checks at the neutral parameter pose.
 *
 * A container round-trip is not enough: a model can retain every ID and still place a whole
 * deformer subtree outside the canvas.  This validator compares evaluated canvas-space geometry
 * with the PSD layer bounds. Bounds deviations are diagnostics and must not prevent export;
 * malformed, missing, non-finite or collapsed geometry still fails validation.
 */
object RigIntegrityValidator {
	private enum class LatticeExtent { RowWidth, ColumnHeight }

	data class Result(
		val boundsByDrawableId: Map<String, Bounds>,
		val warnings: List<String>,
	)

	fun validateNeutralPose(
		label: String,
		puppet: PuppetModel,
		expectedBoundsByDrawableId: Map<String, Bounds>,
	): Result {
		val geometry = CpuDeformationEvaluator().evaluate(puppet, emptyMap())
		val warnings = mutableListOf<String>()
		val actualBounds = linkedMapOf<String, Bounds>()
		val missing = mutableListOf<String>()

		for (drawable in puppet.drawables) {
			val positions = geometry.worldPositions[drawable.id]
			if (positions == null || positions.size < 2) {
				missing += drawable.id.raw
				continue
			}
			require(positions.size % 2 == 0) { tr("validation.vertexArrayOdd", label, drawable.id.raw) }
			var left = Float.POSITIVE_INFINITY
			var top = Float.POSITIVE_INFINITY
			var right = Float.NEGATIVE_INFINITY
			var bottom = Float.NEGATIVE_INFINITY
			for (index in positions.indices step 2) {
				val x = positions[index]
				val y = -positions[index + 1] // evaluator world Y-up -> PSD/canvas Y-down
				require(x.isFinite() && y.isFinite()) { tr("validation.nonFiniteVertex", label, drawable.id.raw) }
				left = minOf(left, x)
				top = minOf(top, y)
				right = maxOf(right, x)
				bottom = maxOf(bottom, y)
			}
			val actual = Bounds(left, top, right, bottom)
			actualBounds[drawable.id.raw] = actual
			require(actual.width > 1e-3f && actual.height > 1e-3f) {
				tr("validation.neutralCollapsed", label, drawable.id.raw, actual.width, actual.height)
			}

			val expected = expectedBoundsByDrawableId[drawable.id.raw] ?: continue
			val scale = max(max(expected.width, expected.height), 1f)
			val centerError = max(abs(actual.centerX - expected.centerX), abs(actual.centerY - expected.centerY))
			val sizeError = max(abs(actual.width - expected.width), abs(actual.height - expected.height))
			// Preserve severe deviations (including their bounds) in the export logs/report, but
			// allow usable geometry to export even when its neutral bounds differ from the PSD.
			if (centerError > scale * 0.50f || sizeError > scale * 0.50f) {
				warnings += tr("validation.neutralMismatch", label, drawable.id.raw, expected, actual)
			} else if (centerError > scale * 0.04f || sizeError > scale * 0.04f) {
				warnings += tr("validation.neutralWarning", label, drawable.id.raw, expected, actual)
			}
		}

		require(missing.isEmpty()) { tr("validation.missingNeutralGeometry", label, missing.joinToString()) }
		require(actualBounds.size == puppet.drawables.count { it.mesh != null }) {
			tr("validation.incompleteNeutralGeometry", label)
		}
		return Result(actualBounds, warnings)
	}

	/**
	 * Evaluates all four head-angle extremes.  This specifically guards against authoring a static
	 * drawable as a single AngleX=0 keyform: that model looks correct at rest but loses geometry as
	 * soon as the viewer moves the parameter.
	 */
	fun validateHeadAnglePoses(
		label: String,
		puppet: PuppetModel,
		neutralBoundsByDrawableId: Map<String, Bounds>,
	) {
		val poses = listOf(
			"AngleX=-45" to mapOf(StandardParameters.ANGLE_X to -45f),
			"AngleX=45" to mapOf(StandardParameters.ANGLE_X to 45f),
			"AngleY=-30" to mapOf(StandardParameters.ANGLE_Y to -30f),
			"AngleY=30" to mapOf(StandardParameters.ANGLE_Y to 30f),
		)
		val evaluator = CpuDeformationEvaluator()
		val expectedIds = puppet.drawables.filter { it.mesh != null }.mapTo(linkedSetOf()) { it.id }
		val neutralGeometry = evaluator.evaluate(puppet, emptyMap())
		for ((poseName, parameters) in poses) {
			val geometry = evaluator.evaluate(puppet, parameters)
			val missing = expectedIds - geometry.worldPositions.keys
			val unexpected = geometry.worldPositions.keys - expectedIds
			require(missing.isEmpty() && unexpected.isEmpty()) {
				val details = buildList {
					if (missing.isNotEmpty()) add(missing.joinToString { it.raw })
					if (unexpected.isNotEmpty()) add("extra: " + unexpected.joinToString { it.raw })
				}.joinToString("; ")
				tr("validation.poseGeometryMissing", label, poseName, details)
			}
			for (drawableId in expectedIds) {
				val positions = geometry.worldPositions.getValue(drawableId)
				val actual = evaluatedBounds("$label $poseName", drawableId, positions)
				val neutral = neutralBoundsByDrawableId[drawableId.raw] ?: continue
				val minimumWidth = max(1e-3f, neutral.width * 0.08f)
				val minimumHeight = max(1e-3f, neutral.height * 0.08f)
				require(actual.width >= minimumWidth && actual.height >= minimumHeight) {
					tr("validation.poseCollapsed", label, poseName, drawableId.raw, neutral, actual)
				}
				require(actual.width <= neutral.width * 4f + 4f && actual.height <= neutral.height * 4f + 4f) {
					tr("validation.poseEnlarged", label, poseName, drawableId.raw, neutral, actual)
				}
				val neutralOpacity = neutralGeometry.opacity[drawableId] ?: 0f
				val poseOpacity = geometry.opacity[drawableId]
				require(poseOpacity != null && poseOpacity.isFinite()) { tr("validation.poseOpacityInvalid", label, poseName, drawableId.raw) }
				if (neutralOpacity > 1e-3f) {
					require(poseOpacity > 1e-3f) { tr("validation.poseOpacityZero", label, poseName, drawableId.raw) }
				}
			}
		}
	}

	/**
	 * Guards directional parameters against accidental signed scale in authored and round-tripped
	 * warp grids. Perspective feature warps and expression parameters are intentionally excluded:
	 * near/far face sizes and MouthForm +/- are semantic differences, not mirror directions.
	 */
	fun validateDirectionalWarpDimensions(label: String, puppet: PuppetModel) {
		if (puppet.deformers.isEmpty()) return
		val warps = puppet.deformers.filterIsInstance<Deformer.Warp>()
		val byId = warps.associateBy { it.id.raw }

		fun requireWarp(id: String): Deformer.Warp =
			requireNotNull(byId[id]) { tr("validation.missingDirectionalWarp", label, id) }

		auditSymmetricExtent(label, requireWarp("DeformBodyXY"), StandardParameters.BODY_X, LatticeExtent.RowWidth, true)
		auditSymmetricExtent(label, requireWarp("DeformBodyXY"), StandardParameters.BODY_Y, LatticeExtent.ColumnHeight, true)
		auditSymmetricExtent(label, requireWarp("DeformBodyZBreath"), StandardParameters.BODY_Z, LatticeExtent.RowWidth, true)
		auditSymmetricExtent(label, requireWarp("DeformHeadContainer"), StandardParameters.ANGLE_X, LatticeExtent.RowWidth, true)
		auditSymmetricExtent(label, requireWarp("DeformHeadContainer"), StandardParameters.ANGLE_Y, LatticeExtent.ColumnHeight, true)

		for (warp in warps.filter { it.id.raw.endsWith("Follow") && it.id.raw.startsWith("DeformHair") }) {
			auditSymmetricExtent(label, warp, StandardParameters.ANGLE_X, LatticeExtent.RowWidth, true)
			auditSymmetricExtent(label, warp, StandardParameters.ANGLE_Y, LatticeExtent.ColumnHeight, true)
		}
		for (warp in warps.filter { it.id.raw.startsWith("DeformEyeGaze") }) {
			auditSymmetricExtent(label, warp, StandardParameters.EYE_BALL_X, LatticeExtent.RowWidth, true)
			auditSymmetricExtent(label, warp, StandardParameters.EYE_BALL_Y, LatticeExtent.ColumnHeight, true)
		}
		for (warp in warps.filter { it.id.raw.endsWith("Physics") && it.id.raw.startsWith("DeformHair") }) {
			val parameter = checkNotNull(warp.geometryGrid).axes.singleOrNull()?.parameterId
				?: error(tr("validation.physicsNotSingleAxis", label, warp.id.raw))
			auditSymmetricExtent(label, warp, parameter, LatticeExtent.RowWidth, true)
			// Swing lift is even: +/- have equal height but both are deliberately shorter than neutral.
			auditSymmetricExtent(label, warp, parameter, LatticeExtent.ColumnHeight, false)
		}

		for (rotation in puppet.deformers.filterIsInstance<Deformer.Rotation>()) {
			val grid = rotation.geometryGrid ?: continue
			for (cell in grid.cells) {
				val form: RotationPivotForm = cell.form
				require(form.scale.isFinite() && abs(form.scale - 1f) <= 1e-5f) {
					tr("validation.directionalRotationScale", label, rotation.id.raw, cell.coordinate.contentToString(), form.scale)
				}
			}
		}
	}

	private fun auditSymmetricExtent(
		label: String,
		warp: Deformer.Warp,
		parameter: ParameterId,
		extent: LatticeExtent,
		mustEqualNeutral: Boolean,
	) {
		val grid = requireNotNull(warp.geometryGrid) { tr("validation.missingKeyforms", label, warp.id.raw) }
		val axisIndex = grid.axes.indexOfFirst { it.parameterId == parameter }
		require(axisIndex >= 0) { tr("validation.parameterUnbound", label, warp.id.raw, parameter.raw) }
		val keys = grid.axes[axisIndex].keys
		val negativeIndex = keys.indices.filter { keys[it] < 0f }.minByOrNull { keys[it] }
		val positiveIndex = keys.indices.filter { keys[it] > 0f }.maxByOrNull { keys[it] }
		val neutralIndex = keys.indices.minByOrNull { abs(keys[it]) }
		require(negativeIndex != null && positiveIndex != null && neutralIndex != null) {
			tr("validation.symmetricKeysMissing", label, warp.id.raw, parameter.raw)
		}

		val coordinate = IntArray(grid.axes.size)
		fun visit(currentAxis: Int) {
			if (currentAxis == grid.axes.size) {
				fun formAt(index: Int): WarpLatticeForm {
					coordinate[axisIndex] = index
					val linear = grid.linearIndexOf(coordinate)
					return requireNotNull(grid.cellsByLinearIndex[linear]) {
						tr("validation.keyformCellMissing", label, warp.id.raw, coordinate.contentToString())
					}.form
				}
				val negative = latticeExtents(label, warp, formAt(negativeIndex), extent)
				val positive = latticeExtents(label, warp, formAt(positiveIndex), extent)
				val neutral = if (mustEqualNeutral) latticeExtents(label, warp, formAt(neutralIndex), extent) else null
				for (index in negative.indices) {
					requireNearlyEqual(label, warp, parameter, negative[index], positive[index], tr("validation.pair.negativePositive"), index)
					if (neutral != null) {
						requireNearlyEqual(label, warp, parameter, negative[index], neutral[index], tr("validation.pair.negativeNeutral"), index)
					}
				}
				return
			}
			if (currentAxis == axisIndex) {
				visit(currentAxis + 1)
			} else {
				for (keyIndex in grid.axes[currentAxis].keys.indices) {
					coordinate[currentAxis] = keyIndex
					visit(currentAxis + 1)
				}
			}
		}
		visit(0)
	}

	private fun latticeExtents(
		label: String,
		warp: Deformer.Warp,
		form: WarpLatticeForm,
		extent: LatticeExtent,
	): FloatArray {
		val points = form.controlPoints
		val expected = (warp.columns + 1) * (warp.rows + 1) * 2
		require(points.size == expected) { tr("validation.controlPointCount", label, warp.id.raw, points.size, expected) }
		return when (extent) {
			LatticeExtent.RowWidth -> FloatArray(warp.rows + 1) { row ->
				val left = row * (warp.columns + 1) * 2
				val right = (row * (warp.columns + 1) + warp.columns) * 2
				abs(points[right] - points[left])
			}
			LatticeExtent.ColumnHeight -> FloatArray(warp.columns + 1) { column ->
				val top = column * 2 + 1
				val bottom = (warp.rows * (warp.columns + 1) + column) * 2 + 1
				abs(points[bottom] - points[top])
			}
		}
	}

	private fun requireNearlyEqual(
		label: String,
		warp: Deformer.Warp,
		parameter: ParameterId,
		first: Float,
		second: Float,
		pair: String,
		index: Int,
	) {
		val tolerance = max(1f, max(abs(first), abs(second))) * 2e-4f
		require(first.isFinite() && second.isFinite() && abs(first - second) <= tolerance) {
			tr("validation.asymmetricExtent", label, warp.id.raw, parameter.raw, pair, index, first, second)
		}
	}

	private fun evaluatedBounds(label: String, drawableId: DrawableId, positions: FloatArray): Bounds {
		require(positions.size >= 2 && positions.size % 2 == 0) { tr("validation.invalidVertexArray", label, drawableId.raw) }
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		for (index in positions.indices step 2) {
			val x = positions[index]
			val y = -positions[index + 1]
			require(x.isFinite() && y.isFinite()) { tr("validation.nonFiniteVertex", label, drawableId.raw) }
			left = minOf(left, x)
			top = minOf(top, y)
			right = maxOf(right, x)
			bottom = maxOf(bottom, y)
		}
		return Bounds(left, top, right, bottom)
	}
}
