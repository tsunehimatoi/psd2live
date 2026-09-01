package io.github.autolive2d.core

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
 * with the PSD layer bounds, so a broken parent-space conversion cannot be reported as a success.
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
			require(positions.size % 2 == 0) { "$label：${drawable.id.raw} 的顶点数组长度不是偶数" }
			var left = Float.POSITIVE_INFINITY
			var top = Float.POSITIVE_INFINITY
			var right = Float.NEGATIVE_INFINITY
			var bottom = Float.NEGATIVE_INFINITY
			for (index in positions.indices step 2) {
				val x = positions[index]
				val y = -positions[index + 1] // evaluator world Y-up -> PSD/canvas Y-down
				require(x.isFinite() && y.isFinite()) { "$label：${drawable.id.raw} 含 NaN/Infinity 顶点" }
				left = minOf(left, x)
				top = minOf(top, y)
				right = maxOf(right, x)
				bottom = maxOf(bottom, y)
			}
			val actual = Bounds(left, top, right, bottom)
			actualBounds[drawable.id.raw] = actual
			require(actual.width > 1e-3f && actual.height > 1e-3f) {
				"$label：${drawable.id.raw} 在默认姿态下已塌缩（${actual.width}×${actual.height}）"
			}

			val expected = expectedBoundsByDrawableId[drawable.id.raw] ?: continue
			val scale = max(max(expected.width, expected.height), 1f)
			val centerError = max(abs(actual.centerX - expected.centerX), abs(actual.centerY - expected.centerY))
			val sizeError = max(abs(actual.width - expected.width), abs(actual.height - expected.height))
			// Neutral forms are authored as identity.  A little numerical/interpolation drift is fine,
			// but moving or resizing a layer by half its own size means the parent-space chain is broken.
			require(centerError <= scale * 0.50f && sizeError <= scale * 0.50f) {
				"$label：${drawable.id.raw} 默认姿态偏离 PSD；期望=$expected，实际=$actual"
			}
			if (centerError > scale * 0.04f || sizeError > scale * 0.04f) {
				warnings += "$label：${drawable.id.raw} 默认姿态与 PSD 有明显偏差；期望=$expected，实际=$actual"
			}
		}

		require(missing.isEmpty()) { "$label：默认姿态缺少画元几何：${missing.joinToString()}" }
		require(actualBounds.size == puppet.drawables.count { it.mesh != null }) {
			"$label：默认姿态几何数量不完整"
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
		val expectedIds = puppet.drawables.filter { it.isVisible }.mapTo(linkedSetOf()) { it.id }
		val neutralGeometry = evaluator.evaluate(puppet, emptyMap())
		for ((poseName, parameters) in poses) {
			val geometry = evaluator.evaluate(puppet, parameters)
			require(geometry.worldPositions.keys == expectedIds) {
				"$label $poseName：画元几何不完整；缺少=${(expectedIds - geometry.worldPositions.keys).joinToString { it.raw }}"
			}
			for (drawableId in expectedIds) {
				val positions = geometry.worldPositions.getValue(drawableId)
				val actual = evaluatedBounds("$label $poseName", drawableId, positions)
				val neutral = neutralBoundsByDrawableId[drawableId.raw] ?: continue
				val minimumWidth = max(1e-3f, neutral.width * 0.08f)
				val minimumHeight = max(1e-3f, neutral.height * 0.08f)
				require(actual.width >= minimumWidth && actual.height >= minimumHeight) {
					"$label $poseName：${drawableId.raw} 在非零角度下消失或塌缩；默认=$neutral，实际=$actual"
				}
				require(actual.width <= neutral.width * 4f + 4f && actual.height <= neutral.height * 4f + 4f) {
					"$label $poseName：${drawableId.raw} 在非零角度下异常放大；默认=$neutral，实际=$actual"
				}
				val neutralOpacity = neutralGeometry.opacity[drawableId] ?: 0f
				val poseOpacity = geometry.opacity[drawableId]
				require(poseOpacity != null && poseOpacity.isFinite()) { "$label $poseName：${drawableId.raw} 缺少有效透明度" }
				if (neutralOpacity > 1e-3f) {
					require(poseOpacity > 1e-3f) { "$label $poseName：${drawableId.raw} 在非零角度下透明度变为 0" }
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
		val warps = puppet.deformers.filterIsInstance<Deformer.Warp>()
		val byId = warps.associateBy { it.id.raw }

		fun requireWarp(id: String): Deformer.Warp =
			requireNotNull(byId[id]) { "$label：缺少方向尺寸校验所需变形器 $id" }

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
				?: error("$label：${warp.id.raw} 的物理关键形态不是单轴")
			auditSymmetricExtent(label, warp, parameter, LatticeExtent.RowWidth, true)
			// Swing lift is even: +/- have equal height but both are deliberately shorter than neutral.
			auditSymmetricExtent(label, warp, parameter, LatticeExtent.ColumnHeight, false)
		}

		for (rotation in puppet.deformers.filterIsInstance<Deformer.Rotation>()) {
			val grid = rotation.geometryGrid ?: continue
			for (cell in grid.cells) {
				val form: RotationPivotForm = cell.form
				require(form.scale.isFinite() && abs(form.scale - 1f) <= 1e-5f) {
					"$label：${rotation.id.raw} 在 ${cell.coordinate.contentToString()} 引入了方向相关缩放 ${form.scale}"
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
		val grid = requireNotNull(warp.geometryGrid) { "$label：${warp.id.raw} 缺少关键形态" }
		val axisIndex = grid.axes.indexOfFirst { it.parameterId == parameter }
		require(axisIndex >= 0) { "$label：${warp.id.raw} 未绑定 ${parameter.raw}" }
		val keys = grid.axes[axisIndex].keys
		val negativeIndex = keys.indices.filter { keys[it] < 0f }.minByOrNull { keys[it] }
		val positiveIndex = keys.indices.filter { keys[it] > 0f }.maxByOrNull { keys[it] }
		val neutralIndex = keys.indices.minByOrNull { abs(keys[it]) }
		require(negativeIndex != null && positiveIndex != null && neutralIndex != null) {
			"$label：${warp.id.raw}/${parameter.raw} 缺少对称的负、零、正关键点"
		}

		val coordinate = IntArray(grid.axes.size)
		fun visit(currentAxis: Int) {
			if (currentAxis == grid.axes.size) {
				fun formAt(index: Int): WarpLatticeForm {
					coordinate[axisIndex] = index
					val linear = grid.linearIndexOf(coordinate)
					return requireNotNull(grid.cellsByLinearIndex[linear]) {
						"$label：${warp.id.raw} 缺少关键形态 ${coordinate.contentToString()}"
					}.form
				}
				val negative = latticeExtents(label, warp, formAt(negativeIndex), extent)
				val positive = latticeExtents(label, warp, formAt(positiveIndex), extent)
				val neutral = if (mustEqualNeutral) latticeExtents(label, warp, formAt(neutralIndex), extent) else null
				for (index in negative.indices) {
					requireNearlyEqual(label, warp, parameter, negative[index], positive[index], "负/正", index)
					if (neutral != null) {
						requireNearlyEqual(label, warp, parameter, negative[index], neutral[index], "负/零", index)
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
		require(points.size == expected) { "$label：${warp.id.raw} 控制点数量 ${points.size} != $expected" }
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
			"$label：${warp.id.raw}/${parameter.raw} $pair 尺寸不对称（边 $index：$first vs $second）"
		}
	}

	private fun evaluatedBounds(label: String, drawableId: DrawableId, positions: FloatArray): Bounds {
		require(positions.size >= 2 && positions.size % 2 == 0) { "$label：${drawableId.raw} 的顶点数组无效" }
		var left = Float.POSITIVE_INFINITY
		var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY
		var bottom = Float.NEGATIVE_INFINITY
		for (index in positions.indices step 2) {
			val x = positions[index]
			val y = -positions[index + 1]
			require(x.isFinite() && y.isFinite()) { "$label：${drawableId.raw} 含 NaN/Infinity 顶点" }
			left = minOf(left, x)
			top = minOf(top, y)
			right = maxOf(right, x)
			bottom = maxOf(bottom, y)
		}
		return Bounds(left, top, right, bottom)
	}
}
