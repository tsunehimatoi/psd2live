package io.github.psd2live.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Arc2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom canvas cursors for rotation-deformer handles and transform boxes.
 * Anti-aliased, dual-tone (bright white contrast outline + dark charcoal core)
 * to ensure crisp visibility against any light, dark, or textured artwork.
 */
internal object CanvasCursors {
    private val DARK_CORE = Color(0x1B, 0x1F, 0x27)
    private val WHITE_HALO = Color.WHITE

    val moveCross: Cursor by lazy {
        cursor("psd2live-move-cross", 16, 16) { g, _ ->
            // Canonical 32x32 space, center at (16, 16)
            val c = 16f

            // 4 Arrowhead polygons (North, South, West, East)
            fun makeHead(tipX: Float, tipY: Float, leftX: Float, leftY: Float, rightX: Float, rightY: Float): Path2D.Float {
                return Path2D.Float().apply {
                    moveTo(tipX.toDouble(), tipY.toDouble())
                    lineTo(leftX.toDouble(), leftY.toDouble())
                    lineTo(rightX.toDouble(), rightY.toDouble())
                    closePath()
                }
            }

            val headN = makeHead(c, 2.5f, c - 4f, 8f, c + 4f, 8f)
            val headS = makeHead(c, 29.5f, c - 4f, 24f, c + 4f, 24f)
            val headW = makeHead(2.5f, c, 8f, c - 4f, 8f, c + 4f)
            val headE = makeHead(29.5f, c, 24f, c - 4f, 24f, c + 4f)
            val heads = listOf(headN, headS, headW, headE)

            // Pass 1: White outer contour for 100% contrast on dark backgrounds
            g.color = WHITE_HALO
            g.stroke = BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(c.toInt(), 6, c.toInt(), 26)
            g.drawLine(6, c.toInt(), 26, c.toInt())
            for (h in heads) {
                g.fill(h)
                g.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(h)
            }
            g.fillOval((c - 3.5f).toInt(), (c - 3.5f).toInt(), 7, 7)

            // Pass 2: Dark slate core
            g.color = DARK_CORE
            g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(c.toInt(), 7, c.toInt(), 25)
            g.drawLine(7, c.toInt(), 25, c.toInt())
            for (h in heads) {
                g.fill(h)
            }

            // Central alignment pip
            g.color = WHITE_HALO
            g.fillOval((c - 1.5f).toInt(), (c - 1.5f).toInt(), 3, 3)
        }
    }

    val rotate: Cursor by lazy {
        cursor("psd2live-rotate", 16, 16) { g, _ ->
            // Canonical 32x32 space, center at (16, 16)
            val cx = 16f
            val cy = 16f
            val r = 8.5f

            // Smooth circular arc covering 270 degrees (leaving the bottom open)
            // In AWT Arc2D: startAngle in degrees, sweep in degrees (counter-clockwise)
            // Start at -45° (bottom-right), sweep 270° counter-clockwise to 225° (bottom-left)
            val arc = Arc2D.Float(
                cx - r, cy - r, r * 2f, r * 2f,
                -40f, 260f, Arc2D.OPEN
            )

            // Arrowheads at the two ends:
            // End 1: at angle -40° (radians = -40 * PI / 180)
            // Tangent along circulation is (-sin θ, -cos θ)
            fun arrowhead(angleDeg: Float, clockwise: Boolean): Path2D.Float {
                val rad = Math.toRadians(angleDeg.toDouble())
                val tipX = (cx + r * cos(rad)).toFloat()
                val tipY = (cy - r * sin(rad)).toFloat()
                // Tangent vector
                val sign = if (clockwise) 1f else -1f
                val tx = (-sin(rad) * sign).toFloat()
                val ty = (-cos(rad) * sign).toFloat()
                val nx = -ty
                val ny = tx
                val len = 4.8f
                val half = 3.2f

                return Path2D.Float().apply {
                    moveTo(tipX.toDouble(), tipY.toDouble())
                    lineTo((tipX - tx * len + nx * half).toDouble(), (tipY - ty * len + ny * half).toDouble())
                    lineTo((tipX - tx * len - nx * half).toDouble(), (tipY - ty * len - ny * half).toDouble())
                    closePath()
                }
            }

            // End 1 (-40°): points clockwise (downwards/leftwards)
            val head1 = arrowhead(-40f, true)
            // End 2 (220°): points counter-clockwise (downwards/rightwards)
            val head2 = arrowhead(220f, false)

            // Pass 1: White silhouette outline
            g.color = WHITE_HALO
            g.stroke = BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(arc)
            for (h in listOf(head1, head2)) {
                g.fill(h)
                g.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(h)
            }
            g.fillOval((cx - 2.5f).toInt(), (cy - 2.5f).toInt(), 5, 5)

            // Pass 2: Dark slate core
            g.color = DARK_CORE
            g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(arc)
            g.fill(head1)
            g.fill(head2)

            // Center pivot dot
            g.color = WHITE_HALO
            g.fillOval((cx - 1.2f).toInt(), (cy - 1.2f).toInt(), 2, 2)
        }
    }

    private fun cursor(name: String, hotX: Int, hotY: Int, paint: (Graphics2D, Int) -> Unit): Cursor {
        val size = Toolkit.getDefaultToolkit().getBestCursorSize(32, 32).let { dim ->
            if (dim.width <= 0 || dim.height <= 0) 32 else maxOf(dim.width, dim.height)
        }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        if (size != 32) {
            val scale = size / 32.0
            g.scale(scale, scale)
        }

        paint(g, 32)
        g.dispose()
        return Toolkit.getDefaultToolkit().createCustomCursor(image, Point(hotX * size / 32, hotY * size / 32), name)
    }
}
