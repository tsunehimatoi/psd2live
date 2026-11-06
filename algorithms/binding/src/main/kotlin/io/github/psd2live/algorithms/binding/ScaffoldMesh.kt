package io.github.psd2live.algorithms.binding

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm

/** Binds source topology in its generated parent and computes the legacy source pose. */
object ScaffoldMesh {
    data class RawMesh(val rigPositions: FloatArray, val atlasUvs: FloatArray, val triangles: IntArray)

    sealed interface Geometry {
        data object Zero : Geometry

        data class Eye(
            val lash: Boolean,
            val bounds: Bounds,
            val whiteBounds: List<Bounds>,
            val centroidY: Float,
            val sourceLeft: Int,
            val centerline: FloatArray?,
            val blinkCorner: Float,
            val blinkDepth: Float,
            val eyelashSquash: Float,
        ) : Geometry

        data class Iris(val centroidX: Float, val centroidY: Float, val jellySquash: Float, val jellyStretch: Float) : Geometry
        data class Brow(val height: Float, val lift: Float) : Geometry
        data class Mouth(
            val aperture: Bounds,
            val curve: List<CurvePoint>,
            val narrow: Float,
            val seam: Float,
            val smileBase: Float,
            val smile: Float,
            val widen: Float,
        ) : Geometry {
            init {
                require(curve.size == 7 && curve.all { it.x.isFinite() && it.y.isFinite() }) { "Invalid scaffold mouth curve" }
            }
        }
    }

    data class CurvePoint(val x: Float, val y: Float)

    fun build(
        raw: RawMesh, parent: ScaffoldArms.Parent, geometry: Geometry, checkpoint: () -> Unit = {},
    ): Pair<DrawableMesh, KeyformGrid<MeshDeltaForm>> {
        checkpoint()
        val source = raw.rigPositions
        require(source.size % 2 == 0 && raw.atlasUvs.size == source.size && raw.triangles.size % 3 == 0) {
            "Invalid scaffold mesh topology"
        }
        require(raw.triangles.all { it in 0 until source.size / 2 }) { "Scaffold triangle references an unknown vertex" }
        val frame = parent.frame
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        val positions = FloatArray(source.size)
        val deltas = FloatArray(source.size)
        val browDy = if (geometry is Geometry.Brow) -0f * geometry.height * geometry.lift / 100f / frame.height else 0f
        for (index in source.indices step 2) {
            checkpoint()
            val x = source[index]
            val y = source[index + 1]
            positions[index] = (x - frame.left) / width
            positions[index + 1] = (y - frame.top) / height
            when (geometry) {
                Geometry.Zero -> Unit
                is Geometry.Brow -> deltas[index + 1] = browDy
                is Geometry.Eye -> {
                    val closed = eyePoint(x, y, geometry)
                    // The open key multiplies the closure offsets by zero, preserving their sign.
                    deltas[index] = (closed.first - x) / width * (1f - 1f)
                    deltas[index + 1] = (closed.second - y) / height * (1f - 1f)
                }
                is Geometry.Iris -> {
                    val target = irisPoint(x, y, geometry)
                    deltas[index] = (target.first - x) / width
                    deltas[index + 1] = (target.second - y) / height
                }
                is Geometry.Mouth -> {
                    val target = mouthPoint(x, y, geometry)
                    deltas[index] = (target.first - x) / width
                    deltas[index + 1] = (target.second - y) / height
                }
            }
        }
        checkpoint()
        val mesh = DrawableMesh(positions, raw.atlasUvs, raw.triangles)
        return mesh to KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), MeshDeltaForm(deltas))))
    }

    internal fun eyePoint(x: Float, y: Float, eye: Geometry.Eye): Pair<Float, Float> {
        val white = eye.whiteBounds.minByOrNull { abs(it.centerX - x) } ?: eye.bounds
        val halfWidth = (white.width * 0.5f).coerceAtLeast(1e-4f)
        val normalizedX = ((x - white.centerX) / halfWidth).coerceIn(-1f, 1f)
        val arch = max(0f, 1f - normalizedX * normalizedX)
        val edgeY = white.top + white.height * eye.blinkCorner / 100f
        val curveY = edgeY + max(1.5f, white.height * eye.blinkDepth / 100f) * arch
        val layerHeight = eye.bounds.height.coerceAtLeast(1f)
        val verticalScale = if (eye.lash) eye.eyelashSquash / 100f else (1.2f / layerHeight).coerceIn(0.015f, 0.55f)
        val anchorY = eye.centerline?.let { centerline ->
            val sourceX = (x - eye.sourceLeft).coerceIn(0f, (centerline.size - 1).toFloat())
            val left = sourceX.toInt()
            val right = (left + 1).coerceAtMost(centerline.lastIndex)
            centerline[left] + (centerline[right] - centerline[left]) * (sourceX - left)
        } ?: eye.centroidY
        return x to curveY + (y - anchorY) * verticalScale
    }

    internal fun irisPoint(x: Float, y: Float, iris: Geometry.Iris, amount: Float = 0f): Pair<Float, Float> {
        val scaleX = 1f - amount * iris.jellySquash / 100f
        val scaleY = 1f + amount * iris.jellyStretch / 100f
        return iris.centroidX + (x - iris.centroidX) * scaleX to iris.centroidY + (y - iris.centroidY) * scaleY
    }

    private fun mouthPoint(x: Float, y: Float, mouth: Geometry.Mouth): Pair<Float, Float> {
        val aperture = mouth.aperture
        val open = 1f
        val easedOpen = open * open * (3f - 2f * open)
        val form = 0f
        val halfWidth = (aperture.width * 0.5f).coerceAtLeast(1e-4f)
        val normalizedX = ((x - aperture.centerX) / halfWidth).coerceIn(-1.25f, 1.25f)
        val narrow = mouth.narrow / 100f
        val horizontalScale = 1f - narrow + easedOpen * narrow + form * mouth.widen / 100f
        val targetX = aperture.centerX + (x - aperture.centerX) * horizontalScale
        val seamY = aperture.top + aperture.height * mouth.seam / 100f
        val closedScale = (1.25f / aperture.height.coerceAtLeast(1f)).coerceIn(0.018f, 0.12f)
        val verticalScale = closedScale + easedOpen * (1f - closedScale)
        val cornerWeight = abs(normalizedX).toDouble().pow(1.55).toFloat().coerceAtMost(1.35f)
        val expressionY = -form * aperture.height * (mouth.smileBase + cornerWeight * mouth.smile) / 100f * (0.72f + easedOpen * 0.28f)
        val presetY = curveY(mouth.curve, (normalizedX + 1f) * 0.5f) * aperture.height * (1f - easedOpen)
        val targetY = seamY + (y - seamY) * verticalScale + expressionY + presetY
        return targetX to targetY
    }

    private fun curveY(points: List<CurvePoint>, x: Float): Float {
        val target = x.coerceIn(0f, 1f)
        val start = if (target <= points[3].x) 0 else 3
        fun component(t: Float, horizontal: Boolean): Float {
            val u = 1f - t
            fun value(i: Int) = points[start + i].let { if (horizontal) it.x else it.y }
            return u * u * u * value(0) + 3f * u * u * t * value(1) +
                3f * u * t * t * value(2) + t * t * t * value(3)
        }
        var low = 0f
        var high = 1f
        repeat(20) {
            val t = (low + high) * 0.5f
            if (component(t, true) < target) low = t else high = t
        }
        return component((low + high) * 0.5f, false)
    }
}
