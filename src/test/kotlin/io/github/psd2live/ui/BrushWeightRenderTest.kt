package io.github.psd2live.ui

import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushWeightRenderTest {

    @Test
    fun testSkiaDrawVerticesBlending() {
        val width = 100
        val height = 100
        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas

        // 1. Draw a background (simulate model texture, e.g. blue color 0xFF3366CC)
        val bgPaint = Paint().apply { color = 0xFF3366CC.toInt() }
        canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), bgPaint)

        // 2. We have a mesh triangle: (10, 10), (90, 10), (50, 90)
        // Vertex 0: weight = 0 (completely transparent: 0x00000000)
        // Vertex 1: weight = 1 (bright red with alpha ~0.45: premultiplied ARGB: a=115, r=108, g=13, b=13)
        // Vertex 2: weight = 0 (0x00000000)
        val positions = floatArrayOf(
            10f, 10f,
            90f, 10f,
            50f, 90f
        )
        val colors = intArrayOf(
            0x00000000,
            (115 shl 24) or (108 shl 16) or (13 shl 8) or 13, // semi-transparent red
            0x00000000
        )
        val indices = shortArrayOf(0, 1, 2)

        val weightPaint = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            isAntiAlias = true
        }

        canvas.drawVertices(
            VertexMode.TRIANGLES,
            positions,
            colors,
            null,
            indices,
            BlendMode.DST,
            weightPaint
        )

        // Read pixels
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height)
        surface.readPixels(bitmap, 0, 0)

        // Check vertex 0 (10, 10): weight was 0, so it must preserve underlying blue texture (NOT black!)
        val p0 = bitmap.getColor(10, 10)
        val p0Red = (p0 shr 16) and 0xFF
        val p0Blue = p0 and 0xFF
        assertTrue(p0Blue > 150, "Weight=0 vertex must preserve underlying blue texture, but got blue=$p0Blue")
        assertTrue(p0Red < 70, "Weight=0 vertex must not add red, got red=$p0Red")

        // Check near vertex 1 (85, 12): weight was near 1, so it should blend translucent red over the blue
        val p1 = bitmap.getColor(85, 12)
        val p1Red = (p1 shr 16) and 0xFF
        val p1Blue = p1 and 0xFF
        assertTrue(p1Red in 50..120, "Weight~1 vertex must have strong red component, got red=$p1Red")
        assertTrue(p1Blue in 100..160, "Weight~1 vertex must preserve visible blue texture underneath, got blue=$p1Blue")
    }

    @Test
    fun testSkiaDrawVerticesWithRadialGradientShader() {
        val width = 100
        val height = 100
        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas

        // Background: blue texture (0xFF3366CC)
        val bgPaint = Paint().apply { color = 0xFF3366CC.toInt() }
        canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), bgPaint)

        // Mesh triangles: two triangles covering (10, 10) to (90, 90)
        val positions = floatArrayOf(
            10f, 10f,
            90f, 10f,
            90f, 90f,
            10f, 90f
        )
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        // Radial gradient shader with hardness stops:
        // Hardness = 0.5: from 0.0 to 0.5 is solid red (weight 1.0), then smooth falloff to 1.0 (weight 0.0)
        val stops = floatArrayOf(0f, 0.5f, 1f)
        val stopColors = intArrayOf(
            (160 shl 24) or (245 shl 16) or (25 shl 8) or 25, // center (alpha ~0.63)
            (160 shl 24) or (245 shl 16) or (25 shl 8) or 25, // hardness core boundary
            0x00000000 // outer radius boundary (fully transparent)
        )
        val shader = Shader.makeRadialGradient(
            50f, 50f, 40f,
            stopColors,
            stops
        )

        val weightPaint = Paint().apply {
            this.shader = shader
            isAntiAlias = true
        }

        canvas.drawVertices(
            VertexMode.TRIANGLES,
            positions,
            null,
            null,
            indices,
            BlendMode.SRC_OVER,
            weightPaint
        )

        shader.close()
        weightPaint.close()

        val bitmap = Bitmap()
        bitmap.allocN32Pixels(width, height)
        surface.readPixels(bitmap, 0, 0)

        // Inside triangle and near center (50, 50): should have vibrant red blended over blue
        val centerPixel = bitmap.getColor(50, 50)
        val centerRed = (centerPixel shr 16) and 0xFF
        val centerBlue = centerPixel and 0xFF
        println("Center pixel (50, 50): red=$centerRed, blue=$centerBlue")
        assertTrue(centerRed > 100, "Center pixel must have strong red, got red=$centerRed")
        assertTrue(centerBlue > 0, "Center pixel must preserve blue texture underneath, got blue=$centerBlue")

        // Outside mesh (5, 5): must NOT be touched at all
        val outsideMesh = bitmap.getColor(5, 5)
        val outRed = (outsideMesh shr 16) and 0xFF
        val outBlue = outsideMesh and 0xFF
        println("Outside mesh (5, 5): red=$outRed, blue=$outBlue")
        assertEquals(0x33, outRed, "Outside mesh must remain original background red")
        assertEquals(0xCC, outBlue, "Outside mesh must remain original background blue")

        // Test Linear Gradient
        val lineShader = Shader.makeLinearGradient(
            10f, 50f, 90f, 50f,
            stopColors,
            stops
        )
        val linePaint = Paint().apply {
            this.shader = lineShader
            isAntiAlias = true
        }
        canvas.drawVertices(
            VertexMode.TRIANGLES,
            positions,
            null,
            null,
            indices,
            BlendMode.SRC_OVER,
            linePaint
        )
        lineShader.close()
        linePaint.close()
    }

    @Test
    fun testMeshDeformationVertexColorTransform() {
        val width = 100
        val height = 100

        // Frame 1: Mesh in rest pose
        // V0: (20, 20), w=0
        // V1: (50, 20), w=1.0 (color = translucent red)
        // V2: (35, 70), w=0
        val restPositions = floatArrayOf(
            20f, 20f,
            50f, 20f,
            35f, 70f
        )
        val colors = intArrayOf(
            0x00000000,
            (158 shl 24) or (153 shl 16) or (15 shl 8) or 15, // a=158, r=153, g=15, b=15
            0x00000000
        )
        val indices = shortArrayOf(0, 1, 2)

        val surface1 = Surface.makeRasterN32Premul(width, height)
        val bgPaint = Paint().apply { color = 0xFF3366CC.toInt() }
        surface1.canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), bgPaint)

        val weightPaint = Paint().apply { isAntiAlias = true }
        surface1.canvas.drawVertices(
            VertexMode.TRIANGLES,
            restPositions,
            colors,
            null,
            indices,
            BlendMode.DST,
            weightPaint
        )
        val bmp1 = Bitmap().apply { allocN32Pixels(width, height) }
        surface1.readPixels(bmp1, 0, 0)

        // At rest: (45, 25) is inside triangle near V1 (50, 20), (70, 25) is completely outside
        val pRestAtV1 = bmp1.getColor(45, 25)
        val pRestAtDest = bmp1.getColor(70, 25)
        val restV1Red = (pRestAtV1 shr 16) and 0xFF
        val restDestRed = (pRestAtDest shr 16) and 0xFF
        assertTrue(restV1Red > 100, "At rest, near V1 (45, 25) must be red-tinted, got red=$restV1Red")
        assertEquals(0x33, restDestRed, "At rest, (70, 25) is outside mesh and must be untouched background red")

        // Frame 2: User drags, V1 deforms from (50, 20) -> (80, 20)
        val deformedPositions = floatArrayOf(
            20f, 20f,
            80f, 20f, // V1 moved by +30 px in X!
            35f, 70f
        )
        val surface2 = Surface.makeRasterN32Premul(width, height)
        surface2.canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), bgPaint)
        surface2.canvas.drawVertices(
            VertexMode.TRIANGLES,
            deformedPositions,
            colors, // Same vertex colors attached to vertices!
            null,
            indices,
            BlendMode.DST,
            weightPaint
        )
        val bmp2 = Bitmap().apply { allocN32Pixels(width, height) }
        surface2.readPixels(bmp2, 0, 0)

        // When deformed: the triangle stretched right, and (70, 25) is now inside near deformed V1!
        val pDeformAtDest = bmp2.getColor(70, 25)
        val deformDestRed = (pDeformAtDest shr 16) and 0xFF
        val deformDestBlue = pDeformAtDest and 0xFF
        assertTrue(deformDestRed > 100, "When deformed, the red tint must follow V1 so (70, 25) is red-tinted, got red=$deformDestRed")
        assertTrue(deformDestBlue > 50, "Underlying blue texture must be visible under red tint, got blue=$deformDestBlue")

        weightPaint.close()
    }

    @Test
    fun testVertexWeightColorAndRadiusScaling() {
        fun calcVertexStyle(normW: Float): Triple<Float, Float, Float> {
            val r = 1.6f + normW * 0.8f
            val red = 1.0f - normW * 0.08f
            val green = 1.0f - normW * 0.90f
            return Triple(r, red, green)
        }

        // Weight = 0.0: tiny radius (1.6f), white/light (red=1.0, green=1.0)
        val (r0, red0, green0) = calcVertexStyle(0f)
        assertEquals(1.6f, r0, 0.01f)
        assertEquals(1.0f, red0, 0.01f)
        assertEquals(1.0f, green0, 0.01f)

        // Weight = 0.5: medium radius (2.0f), salmon/pink (red=0.96, green=0.55)
        val (rHalf, redHalf, greenHalf) = calcVertexStyle(0.5f)
        assertEquals(2.0f, rHalf, 0.01f)
        assertEquals(0.96f, redHalf, 0.01f)
        assertEquals(0.55f, greenHalf, 0.01f)

        // Weight = 1.0: max radius (2.4f), deep crimson red (red=0.92, green=0.10)
        val (rFull, redFull, greenFull) = calcVertexStyle(1.0f)
        assertEquals(2.4f, rFull, 0.01f)
        assertEquals(0.92f, redFull, 0.01f)
        assertEquals(0.10f, greenFull, 0.01f)

        // Strict monotonicity: green decreases as weight increases (giving deeper red)
        assertTrue(green0 > greenHalf)
        assertTrue(greenHalf > greenFull)

        // Strict monotonicity: radius grows as weight increases
        assertTrue(r0 < rHalf)
        assertTrue(rHalf < rFull)
    }
}
