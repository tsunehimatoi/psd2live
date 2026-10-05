package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import io.github.psd2live.core.quality.*
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
 * Structured findings distinguish quality risks and missing coverage from invalid data;
 * publication policy belongs to the shared quality fence.
 */
object RigIntegrityValidator {
	private enum class LatticeExtent { RowWidth, ColumnHeight }

	data class Result(
		val boundsByDrawableId: Map<String, Bounds>,
		val warnings: List<String>,
        val findings: List<QualityFinding> = emptyList(),
	)

	fun validateNeutralPose(
		label: String,
		puppet: PuppetModel,
		expectedBoundsByDrawableId: Map<String, Bounds>,
	): Result {
		val geometry = CpuDeformationEvaluator().evaluate(puppet, emptyMap())
		val warnings = mutableListOf<QualityFinding>()
		val actualBounds = linkedMapOf<String, Bounds>()
		val missing = mutableListOf<String>()

		for (drawable in puppet.drawables) {
			val positions = geometry.worldPositions[drawable.id]
			if (positions == null || positions.size < 2) {
				missing += drawable.id.raw
				continue
			}
			if (positions.size % 2 != 0) {
				warnings += QualityFinding.message(QualityRule.MODEL_INVALID_VERTEX_ARRAY, "mesh:${drawable.id.raw}", tr("validation.vertexArrayOdd", label, drawable.id.raw))
				continue
			}
			var left = Float.POSITIVE_INFINITY
			var top = Float.POSITIVE_INFINITY
			var right = Float.NEGATIVE_INFINITY
			var bottom = Float.NEGATIVE_INFINITY
			var hasNonFinite = false
			for (index in positions.indices step 2) {
				val x = positions[index]
				val y = -positions[index + 1] // evaluator world Y-up -> PSD/canvas Y-down
				if (!x.isFinite() || !y.isFinite()) {
					warnings += QualityFinding.message(QualityRule.MODEL_NON_FINITE_GEOMETRY, "mesh:${drawable.id.raw}", tr("validation.nonFiniteVertex", label, drawable.id.raw))
					hasNonFinite = true
					break
				}
				left = minOf(left, x)
				top = minOf(top, y)
				right = maxOf(right, x)
				bottom = maxOf(bottom, y)
			}
			if (hasNonFinite) continue
			val actual = Bounds(left, top, right, bottom)
			val safeWidth = actual.width.coerceAtLeast(0f)
			val safeHeight = actual.height.coerceAtLeast(0f)
			val safeActual = if (actual.width < 0f || actual.height < 0f) {
				Bounds(left, top, left + safeWidth, top + safeHeight)
			} else {
				actual
			}
			actualBounds[drawable.id.raw] = safeActual
			if (safeActual.width <= 1e-3f || safeActual.height <= 1e-3f) {
				warnings += QualityFinding.message(QualityRule.MODEL_COLLAPSED_POSE, "mesh:${drawable.id.raw}", tr("validation.neutralCollapsed", label, drawable.id.raw, safeActual.width, safeActual.height))
			}

			val expected = expectedBoundsByDrawableId[drawable.id.raw] ?: continue
			val scale = max(max(expected.width, expected.height), 1f)
			val centerError = max(abs(safeActual.centerX - expected.centerX), abs(safeActual.centerY - expected.centerY))
			val sizeError = max(abs(safeActual.width - expected.width), abs(safeActual.height - expected.height))
			val mismatchTolerance = max(5.0f, scale * 0.50f)
			val warningTolerance = max(1.5f, scale * 0.04f)
			// Preserve severe deviations (including their bounds) in the export logs/report, but
			// allow usable geometry to export even when its neutral bounds differ from the PSD.
			if (centerError > mismatchTolerance || sizeError > mismatchTolerance) {
				warnings += QualityFinding.message(QualityRule.MODEL_BOUNDS_MISMATCH, "mesh:${drawable.id.raw}", tr("validation.neutralMismatch", label, drawable.id.raw, expected, safeActual))
			} else if (centerError > warningTolerance || sizeError > warningTolerance) {
				warnings += QualityFinding.message(QualityRule.MODEL_BOUNDS_DEVIATION, "mesh:${drawable.id.raw}", tr("validation.neutralWarning", label, drawable.id.raw, expected, safeActual))
			}
		}

		if (missing.isNotEmpty()) {
			warnings += QualityFinding.message(QualityRule.MODEL_MISSING_GEOMETRY, "model", tr("validation.missingNeutralGeometry", label, missing.joinToString()))
		}
		if (actualBounds.size != puppet.drawables.count { it.mesh != null }) {
			warnings += QualityFinding.message(QualityRule.MODEL_MISSING_GEOMETRY, "model", tr("validation.incompleteNeutralGeometry", label))
		}
		return Result(actualBounds, warnings.filter { it.rule.severity != QualitySeverity.INFO }.map { it.message }, warnings)
	}

	/**
	 * Evaluates all four head-angle extremes. This specifically guards against authoring a static
	 * drawable as a single AngleX=0 keyform: that model looks correct at rest but loses geometry as
	 * soon as the viewer moves the parameter.
	 */
	fun inspectHeadAnglePoses(
		label: String,
		puppet: PuppetModel,
		neutralBoundsByDrawableId: Map<String, Bounds>,
	): List<QualityFinding> {
		val warnings = mutableListOf<QualityFinding>()
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
			if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
				val details = buildList {
					if (missing.isNotEmpty()) add(missing.joinToString { it.raw })
					if (unexpected.isNotEmpty()) add("extra: " + unexpected.joinToString { it.raw })
				}.joinToString("; ")
				warnings += QualityFinding.message(QualityRule.MODEL_MISSING_GEOMETRY, "model", tr("validation.poseGeometryMissing", label, poseName, details))
			}
			for (drawableId in expectedIds) {
				val positions = geometry.worldPositions[drawableId] ?: continue
				val actual = evaluatedBounds("$label $poseName", drawableId, positions, warnings) ?: continue
				val neutral = neutralBoundsByDrawableId[drawableId.raw] ?: continue
				val minimumWidth = max(1e-3f, neutral.width * 0.08f)
				val minimumHeight = max(1e-3f, neutral.height * 0.08f)
				if (actual.width < minimumWidth || actual.height < minimumHeight) {
					warnings += QualityFinding.message(QualityRule.MODEL_COLLAPSED_POSE, "mesh:${drawableId.raw}", tr("validation.poseCollapsed", label, poseName, drawableId.raw, neutral, actual))
				}
				if (actual.width > neutral.width * 4f + 4f || actual.height > neutral.height * 4f + 4f) {
					warnings += QualityFinding.message(QualityRule.MODEL_ENLARGED_POSE, "mesh:${drawableId.raw}", tr("validation.poseEnlarged", label, poseName, drawableId.raw, neutral, actual))
				}
				val neutralOpacity = neutralGeometry.opacity[drawableId] ?: 0f
				val poseOpacity = geometry.opacity[drawableId]
				if (poseOpacity == null || !poseOpacity.isFinite()) {
					warnings += QualityFinding.message(QualityRule.MODEL_INVALID_OPACITY, "mesh:${drawableId.raw}", tr("validation.poseOpacityInvalid", label, poseName, drawableId.raw))
				} else if (neutralOpacity > 1e-3f && poseOpacity <= 1e-3f) {
					warnings += QualityFinding.message(QualityRule.MODEL_HIDDEN_POSE, "mesh:${drawableId.raw}", tr("validation.poseOpacityZero", label, poseName, drawableId.raw))
				}
			}
		}
		return warnings
	}

	/**
	 * Guards directional parameters against accidental signed scale in authored and round-tripped
	 * warp grids. Perspective feature warps and expression parameters are intentionally excluded:
	 * near/far face sizes and MouthForm +/- are semantic differences, not mirror directions. So is the
	 * body's Body Y: leaning in and standing up straight are different poses, and both change the upper
	 * body's size in perspective, which the rotations hung from it follow on their Body Y keys.
	 */
	fun inspectDirectionalWarpDimensions(label: String, puppet: PuppetModel): List<QualityFinding> {
		if (puppet.deformers.isEmpty()) return emptyList()
		val warnings = mutableListOf<QualityFinding>()
		val warps = puppet.deformers.filterIsInstance<Deformer.Warp>()
		val byId = warps.associateBy { it.id.raw }

		fun checkWarp(id: String): Deformer.Warp? {
			val warp = byId[id]
			if (warp == null) {
				warnings += QualityFinding.message(QualityRule.MODEL_MISSING_DIRECTIONAL_WARP, "model", tr("validation.missingDirectionalWarp", label, id))
			}
			return warp
		}

		// The body turns about the torso's centre line in perspective, which the body's own parts need not
		// sit even about, so its turns mirror the figure rather than the lattice.
		checkWarp("DeformBodyXY")
		checkWarp("DeformBodyZBreath")?.let {
			auditSymmetricExtent(label, it, StandardParameters.BODY_Z, LatticeExtent.RowWidth, true, warnings)
		}
		checkWarp("DeformHeadContainer")?.let {
			auditSymmetricExtent(label, it, StandardParameters.ANGLE_X, LatticeExtent.RowWidth, true, warnings)
			auditSymmetricExtent(label, it, StandardParameters.ANGLE_Y, LatticeExtent.ColumnHeight, true, warnings)
		}

		for (warp in warps.filter { it.id.raw.endsWith("Follow") && it.id.raw.startsWith("DeformHair") }) {
			auditSymmetricExtent(label, warp, StandardParameters.ANGLE_X, LatticeExtent.RowWidth, true, warnings)
			auditSymmetricExtent(label, warp, StandardParameters.ANGLE_Y, LatticeExtent.ColumnHeight, true, warnings)
		}
		for (warp in warps.filter { it.id.raw.startsWith("DeformEyeGaze") }) {
			auditSymmetricExtent(label, warp, StandardParameters.EYE_BALL_X, LatticeExtent.RowWidth, true, warnings)
			auditSymmetricExtent(label, warp, StandardParameters.EYE_BALL_Y, LatticeExtent.ColumnHeight, true, warnings)
		}
		for (warp in warps.filter { it.id.raw.endsWith("Physics") && it.id.raw.startsWith("DeformHair") }) {
			val parameter = warp.geometryGrid?.axes?.singleOrNull()?.parameterId
			if (parameter == null) {
				warnings += QualityFinding.message(QualityRule.MODEL_DIRECTIONAL_ASSUMPTION, "warp:${warp.id.raw}", tr("validation.physicsNotSingleAxis", label, warp.id.raw))
				continue
			}
			auditSymmetricExtent(label, warp, parameter, LatticeExtent.RowWidth, true, warnings)
			// Swing lift is even: +/- have equal height but both are deliberately shorter than neutral.
			auditSymmetricExtent(label, warp, parameter, LatticeExtent.ColumnHeight, false, warnings)
		}

		for (rotation in puppet.deformers.filterIsInstance<Deformer.Rotation>()) {
			val grid = rotation.geometryGrid ?: continue
			val shaping = grid.axes.indices.filter { grid.axes[it].parameterId == StandardParameters.BODY_LEAN || grid.axes[it].parameterId == StandardParameters.PROPORTION }
			for (cell in grid.cells) {
				if (shaping.any { grid.axes[it].keys[cell.coordinate[it]] != 0f }) continue
				val form: RotationPivotForm = cell.form
				if (!form.scale.isFinite() || abs(form.scale - 1f) > 1e-5f) {
					warnings += QualityFinding.message(if (form.scale.isFinite()) QualityRule.MODEL_DIRECTIONAL_ASSUMPTION else QualityRule.MODEL_NON_FINITE_GEOMETRY,
                        "rotation:${rotation.id.raw}", tr("validation.directionalRotationScale", label, rotation.id.raw, cell.coordinate.contentToString(), form.scale))
				}
			}
		}
		return warnings
	}

    /** Compatibility presentation for callers that only display text; decisions use typed findings. */
    fun validateHeadAnglePoses(label: String, puppet: PuppetModel, neutralBoundsByDrawableId: Map<String, Bounds>): List<String> =
        inspectHeadAnglePoses(label, puppet, neutralBoundsByDrawableId).map { it.message }

    fun validateDirectionalWarpDimensions(label: String, puppet: PuppetModel): List<String> =
        inspectDirectionalWarpDimensions(label, puppet).map { it.message }

	private fun auditSymmetricExtent(
		label: String,
		warp: Deformer.Warp,
		parameter: ParameterId,
		extent: LatticeExtent,
		mustEqualNeutral: Boolean,
		warnings: MutableList<QualityFinding>,
	) {
		val grid = warp.geometryGrid
		if (grid == null) {
			warnings += QualityFinding.message(QualityRule.MODEL_DIRECTIONAL_ASSUMPTION, "warp:${warp.id.raw}", tr("validation.missingKeyforms", label, warp.id.raw))
			return
		}
		val axisIndex = grid.axes.indexOfFirst { it.parameterId == parameter }
		if (axisIndex < 0) {
			warnings += QualityFinding.message(QualityRule.MODEL_DIRECTIONAL_ASSUMPTION, "warp:${warp.id.raw}", tr("validation.parameterUnbound", label, warp.id.raw, parameter.raw))
			return
		}
		val keys = grid.axes[axisIndex].keys
		val negativeIndex = keys.indices.filter { keys[it] < 0f }.minByOrNull { keys[it] }
		val positiveIndex = keys.indices.filter { keys[it] > 0f }.maxByOrNull { keys[it] }
		val neutralIndex = keys.indices.minByOrNull { abs(keys[it]) }
		if (negativeIndex == null || positiveIndex == null || neutralIndex == null) {
			warnings += QualityFinding.message(QualityRule.MODEL_DIRECTIONAL_ASSUMPTION, "warp:${warp.id.raw}", tr("validation.symmetricKeysMissing", label, warp.id.raw, parameter.raw))
			return
		}

		val coordinate = IntArray(grid.axes.size)
		fun visit(currentAxis: Int) {
			if (currentAxis == grid.axes.size) {
				fun formAt(index: Int): WarpLatticeForm? {
					coordinate[axisIndex] = index
					val linear = grid.linearIndexOf(coordinate)
					val cell = grid.cellsByLinearIndex[linear]
					if (cell == null) {
						warnings += QualityFinding.message(QualityRule.MODEL_MISSING_KEYFORM, "warp:${warp.id.raw}", tr("validation.keyformCellMissing", label, warp.id.raw, coordinate.contentToString()))
						return null
					}
					return cell.form
				}
				val negativeForm = formAt(negativeIndex) ?: return
				val positiveForm = formAt(positiveIndex) ?: return
				val neutralForm = if (mustEqualNeutral) formAt(neutralIndex) ?: return else null
				val negative = latticeExtents(label, warp, negativeForm, extent, warnings) ?: return
				val positive = latticeExtents(label, warp, positiveForm, extent, warnings) ?: return
				val neutral = if (neutralForm != null) latticeExtents(label, warp, neutralForm, extent, warnings) ?: return else null
				for (index in negative.indices) {
					checkNearlyEqual(label, warp, parameter, negative[index], positive[index], tr("validation.pair.negativePositive"), index, warnings)
					if (neutral != null) {
						checkNearlyEqual(label, warp, parameter, negative[index], neutral[index], tr("validation.pair.negativeNeutral"), index, warnings)
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
		warnings: MutableList<QualityFinding>,
	): FloatArray? {
		val points = form.controlPoints
		val expected = (warp.columns + 1) * (warp.rows + 1) * 2
		if (points.size != expected) {
			warnings += QualityFinding.message(QualityRule.MODEL_INVALID_CONTROL_POINTS, "warp:${warp.id.raw}", tr("validation.controlPointCount", label, warp.id.raw, points.size, expected))
			return null
		}
        if (points.any { !it.isFinite() }) {
            warnings += QualityFinding(QualityRule.MODEL_NON_FINITE_GEOMETRY, "warp:${warp.id.raw}",
                QualityEvidence.metrics(emptyMap(), mapOf("invalid_field" to "control_points")))
            return null
        }
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

	private fun checkNearlyEqual(
		label: String,
		warp: Deformer.Warp,
		parameter: ParameterId,
		first: Float,
		second: Float,
		pair: String,
		index: Int,
		warnings: MutableList<QualityFinding>,
	) {
		val tolerance = max(1f, max(abs(first), abs(second))) * 2e-4f
		if (!first.isFinite() || !second.isFinite() || abs(first - second) > tolerance) {
			warnings += QualityFinding.message(QualityRule.MODEL_DIRECTIONAL_ASSUMPTION, "warp:${warp.id.raw}", tr("validation.asymmetricExtent", label, warp.id.raw, parameter.raw, pair, index, first, second))
		}
	}

	private fun evaluatedBounds(
		label: String,
		drawableId: DrawableId,
		positions: FloatArray,
		warnings: MutableList<QualityFinding>,
	): Bounds? {
		if (positions.size < 2 || positions.size % 2 != 0) {
			warnings += QualityFinding.message(QualityRule.MODEL_INVALID_VERTEX_ARRAY, "mesh:${drawableId.raw}", tr("validation.invalidVertexArray", label, drawableId.raw))
			return null
		}
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		for (index in positions.indices step 2) {
			val x = positions[index]
			val y = -positions[index + 1]
			if (!x.isFinite() || !y.isFinite()) {
				warnings += QualityFinding.message(QualityRule.MODEL_NON_FINITE_GEOMETRY, "mesh:${drawableId.raw}", tr("validation.nonFiniteVertex", label, drawableId.raw))
				return null
			}
			left = minOf(left, x)
			top = minOf(top, y)
			right = maxOf(right, x)
			bottom = maxOf(bottom, y)
		}
		return Bounds(left, top, right, bottom)
	}
}
