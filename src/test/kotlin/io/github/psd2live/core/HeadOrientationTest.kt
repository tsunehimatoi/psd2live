package io.github.psd2live.core

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.runtime.model.Deformer
import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadOrientationTest {
	@Test
	fun `bilateral facial features determine authored head roll robustly`() {
		val angle = 18f
		val layers = buildList {
			addAll(featurePair(SemanticTag.EYEWHITE, angle, 54f, 78f, 146f, 78f))
			addAll(featurePair(SemanticTag.IRIDES, angle, 60f, 80f, 140f, 80f))
			addAll(featurePair(SemanticTag.EYEBROW, angle, 52f, 62f, 148f, 62f))
			// A deliberately disagreeing low-weight ear line must not drag the eye-derived result.
			addAll(featurePair(SemanticTag.EARS, -8f, 30f, 96f, 170f, 96f))
		}
		assertEquals(angle, HeadOrientationEstimator.estimate(layers, Bounds(20f, 20f, 180f, 190f)), 0.25f)
	}

	@Test
	fun `central facial axis is used when no bilateral pair exists`() {
		val angle = -14f
		val upper = rotatedPoint(100f, 66f, angle)
		val lower = rotatedPoint(100f, 146f, angle)
		val layers = listOf(
			classified("eye", SemanticTag.EYEWHITE, Side.NONE, upper.first, upper.second),
			classified("mouth", SemanticTag.MOUTH, Side.NONE, lower.first, lower.second),
		)
		assertEquals(angle, HeadOrientationEstimator.estimate(layers, Bounds(20f, 20f, 180f, 190f)), 0.25f)
	}

	@Test
	fun `head rotation and descendant warps share the detected axis`() {
		val angle = 16f
		val featureLayers = featurePair(SemanticTag.EYEWHITE, angle, 54f, 78f, 146f, 78f)
		val face = classified("face", SemanticTag.FACE, Side.NONE, 100f, 105f, Bounds(25f, 18f, 175f, 192f))
		val layers = listOf(face) + featureLayers
		val source = object : SourceArt {
			override val widthPx = 200
			override val heightPx = 210
			override val layers = layers.map { it.source }
		}
		val analysis = PipelineAnalysis(
			source,
			layers,
			RigAnchors(
				character = Bounds(0f, 0f, 200f, 210f),
				face = face.bounds,
				body = Bounds(0f, 190f, 200f, 210f),
				faceCenterX = 100f,
				faceCenterY = 105f,
				chinX = 100f,
				chinY = 192f,
				shoulderY = 190f,
				hipY = 205f,
			),
			emptyList(),
			BufferedImage(200, 210, BufferedImage.TYPE_INT_ARGB),
		)
		val atlas = PackedAtlas(
			pages = listOf(AtlasPage(BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), ByteArray(0))),
			placementByLayerId = layers.mapIndexed { index, layer ->
				layer.source.id.raw to AtlasPlacement(0, index * 32, 0, layer.source.raster.width, layer.source.raster.height)
			}.toMap(),
		)

		val rig = RigBuilder.build(analysis, atlas, PipelineConfig(atlasSize = 256, meshSpacing = 12))
		val rotation = rig.puppet.deformers.filterIsInstance<Deformer.Rotation>().single { it.id.raw == "DeformHeadRotation" }
		assertEquals(angle, rotation.baseAngle, 0.25f)
		assertEquals(angle, rig.initialHeadAngleZ, 0.25f)

		val descendantWarps = rig.puppet.deformers.filterIsInstance<Deformer.Warp>().filter {
			it.id.raw == "DeformHeadContainer" || it.id.raw == "DeformFaceNinePose" || it.id.raw.startsWith("DeformEyeShape")
		}
		assertTrue(descendantWarps.size >= 3)
		for (warp in descendantWarps) {
			val neutral = warp.geometryGrid!!.cells.single { cell ->
				cell.coordinate.all { coordinate -> coordinate == 1 }
			}.form.controlPoints
			val rowEnd = warp.columns * 2
			val localDx = neutral[rowEnd] - neutral[0]
			val localDy = neutral[rowEnd + 1] - neutral[1]
			val radians = rotation.baseAngle * PI.toFloat() / 180f
			val worldDx = cos(radians) * localDx - sin(radians) * localDy
			val worldDy = sin(radians) * localDx + cos(radians) * localDy
			val worldAngle = Math.toDegrees(atan2(worldDy.toDouble(), worldDx.toDouble())).toFloat()
			assertEquals(angle, worldAngle, 0.3f, "${warp.id.raw} did not inherit the authored head axis")
		}
	}

	private fun featurePair(
		tag: SemanticTag,
		angle: Float,
		leftX: Float,
		leftY: Float,
		rightX: Float,
		rightY: Float,
	): List<ClassifiedLayer> {
		val left = rotatedPoint(leftX, leftY, angle)
		val right = rotatedPoint(rightX, rightY, angle)
		return listOf(
			classified("${tag.name}-left", tag, Side.RIGHT, left.first, left.second),
			classified("${tag.name}-right", tag, Side.LEFT, right.first, right.second),
		)
	}

	private fun rotatedPoint(x: Float, y: Float, angle: Float): Pair<Float, Float> {
		val radians = angle * PI.toFloat() / 180f
		val dx = x - 100f
		val dy = y - 105f
		return (100f + cos(radians) * dx - sin(radians) * dy) to
			(105f + sin(radians) * dx + cos(radians) * dy)
	}

	private fun classified(
		id: String,
		tag: SemanticTag,
		side: Side,
		centerX: Float,
		centerY: Float,
		bounds: Bounds = Bounds(centerX - 6f, centerY - 4f, centerX + 6f, centerY + 4f),
	): ClassifiedLayer {
		val source = TestLayer(id, bounds)
		return ClassifiedLayer(
			source,
			LayerSemantic(tag, side, null, id, 1f),
			bounds,
			centerX,
			centerY,
			100,
		)
	}

	private class TestLayer(id: String, bounds: Bounds) : SourceLayer {
		private val rasterWidth = bounds.width.toInt().coerceAtLeast(1)
		private val rasterHeight = bounds.height.toInt().coerceAtLeast(1)
		override val id = LayerId(id)
		override val name = id
		override val groupPath = ""
		override val order = 0
		override val bounds = LayerBounds(bounds.left.toInt(), bounds.top.toInt(), rasterWidth, rasterHeight)
		override val opacity = 1f
		override val clipped = false
		override val blend = LayerBlend.Normal
		override val raster = LayerRaster(
			rasterWidth,
			rasterHeight,
			ByteArray(rasterWidth * rasterHeight * 4) { 0xff.toByte() },
		)
	}
}
