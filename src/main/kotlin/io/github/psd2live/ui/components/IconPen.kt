package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The one pen every icon in the app draws with. An icon is drawn on an 18-unit grid with a round
 * 1.4-unit stroke (1 unit for secondary detail) and scaled to the box it is given, so icons of any
 * size share one line weight relative to their size. Below about 14dp the line would thin past a
 * device pixel, so lines keep at least 1dp at their main weight, finer lines in proportion.
 *
 * Conventions, so new icons sit with the old ones:
 * - the drawing keeps about 2 units clear of the edge (inline glyphs - close, add, check, chevron -
 *   reach to about 1.5, since they are sized as text-sized marks);
 * - a closed shape that stands for a thing gets a [IconPen.soft] fill under its outline;
 * - points are dots of radius 1.4 to 1.9, handles hollow rings;
 * - colour is semantic and sparing: [IconPen.tone] lays a theme colour over the tint's alpha, for the
 *   parts of an icon whose colour is its meaning (a record dot, a colour well, a weight ramp).
 */

internal const val ICON_GRID = 18f
internal const val ICON_LINE = 1.4f
internal const val ICON_FINE = 1f
private const val MIN_LINE_DP = 1f
/** Pixels drawn around a [GridIcon]'s box. */
private const val ICON_BLEED_PX = 2

/** Grid-unit drawing helpers bound to one icon's scope and tint. */
internal class IconPen(val scope: DrawScope, val color: Color) {
    val s = scope.size.minDimension / ICON_GRID

    /** Grid offset that centres the square grid in a non-square box. */
    private val ox = (scope.size.width - scope.size.minDimension) / 2f
    private val oy = (scope.size.height - scope.size.minDimension) / 2f

    /** The tint at the fill strength a shape's body takes under its outline. */
    val soft = color.copy(alpha = color.alpha * 0.28f)

    /** [tone] at the tint's alpha, for an icon part whose colour carries meaning. */
    fun tone(tone: Color, alpha: Float = 1f) = tone.copy(alpha = tone.alpha * color.alpha * alpha)

    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)

    /** A line [width] grid units wide, kept at least its share of 1dp on small icons. */
    fun px(width: Float) = maxOf(width * s, width / ICON_LINE * MIN_LINE_DP * scope.density)

    fun stroke(width: Float = ICON_LINE, dash: FloatArray? = null) = Stroke(
        px(width), cap = StrokeCap.Round, join = StrokeJoin.Round,
        pathEffect = dash?.let { PathEffect.dashPathEffect(FloatArray(it.size) { i -> it[i] * s }) },
    )

    fun path(block: GridPath.() -> Unit): Path = GridPath(s, ox, oy).apply(block).path

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = ICON_LINE, tint: Color = color) =
        scope.drawLine(tint, p(x1, y1), p(x2, y2), px(width), cap = StrokeCap.Round)

    fun outline(path: Path, width: Float = ICON_LINE, tint: Color = color, dash: FloatArray? = null) =
        scope.drawPath(path, tint, style = stroke(width, dash))

    fun fill(path: Path, tint: Color = color) = scope.drawPath(path, tint)

    /** [path] filled [soft] and outlined: the look of a closed shape that stands for a thing. */
    fun shape(path: Path, width: Float = ICON_LINE, body: Color = soft, tint: Color = color) {
        fill(path, body)
        outline(path, width, tint)
    }

    fun ring(x: Float, y: Float, r: Float, width: Float = ICON_LINE, tint: Color = color, dash: FloatArray? = null) =
        scope.drawCircle(tint, r * s, p(x, y), style = stroke(width, dash))

    fun dot(x: Float, y: Float, r: Float, tint: Color = color) = scope.drawCircle(tint, r * s, p(x, y))

    fun box(
        x: Float, y: Float, w: Float, h: Float, r: Float = 1f, width: Float = ICON_LINE,
        tint: Color = color, dash: FloatArray? = null,
    ) = scope.drawRoundRect(tint, p(x, y), Size(w * s, h * s), CornerRadius(r * s), style = stroke(width, dash))

    fun fillBox(x: Float, y: Float, w: Float, h: Float, r: Float = 1f, tint: Color = color) =
        scope.drawRoundRect(tint, p(x, y), Size(w * s, h * s), CornerRadius(r * s))

    /** A rounded box filled [soft] and outlined. */
    fun panel(x: Float, y: Float, w: Float, h: Float, r: Float = 1.2f, width: Float = ICON_LINE, tint: Color = color) {
        fillBox(x, y, w, h, r, if (tint == color) soft else tint.copy(alpha = tint.alpha * 0.28f))
        box(x, y, w, h, r, width, tint)
    }

    fun rectPath(x: Float, y: Float, w: Float, h: Float, r: Float = 1f) = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(p(x, y), Size(w * s, h * s)), CornerRadius(r * s)))
    }

    fun circlePath(x: Float, y: Float, r: Float) = Path().apply { addOval(Rect(p(x, y), r * s)) }

    fun arc(
        cx: Float, cy: Float, r: Float, startDegrees: Float, sweepDegrees: Float,
        width: Float = ICON_LINE, tint: Color = color,
    ) = scope.drawArc(
        tint, startDegrees, sweepDegrees, false, p(cx - r, cy - r), Size(2 * r * s, 2 * r * s), style = stroke(width),
    )

    /** An open arrowhead at ([x], [y]) pointing along ([dx], [dy]). */
    fun chevron(x: Float, y: Float, dx: Float, dy: Float, length: Float = 3f, width: Float = ICON_LINE) {
        val n = hypot(dx, dy)
        val ux = dx / n
        val uy = dy / n
        val c = cos(0.7f)
        val sn = sin(0.7f)
        outline(path {
            m(x - length * (ux * c - uy * sn), y - length * (uy * c + ux * sn))
            l(x, y)
            l(x - length * (ux * c + uy * sn), y - length * (uy * c - ux * sn))
        }, width)
    }

    /** An arc about ([cx], [cy]) ending in an arrowhead, the turn of a rotation or a cycle. */
    fun arcArrow(cx: Float, cy: Float, r: Float, startDegrees: Float, endDegrees: Float, head: Float = 2.8f) {
        arc(cx, cy, r, startDegrees, endDegrees - startDegrees)
        val a = Math.toRadians(endDegrees.toDouble())
        val sign = if (endDegrees >= startDegrees) 1f else -1f
        chevron(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), -sin(a).toFloat() * sign, cos(a).toFloat() * sign, head)
    }

    /** Draws [block] turned [degrees] about ([x], [y]). */
    fun turned(degrees: Float, x: Float = 9f, y: Float = 9f, block: () -> Unit) =
        scope.rotate(degrees, p(x, y)) { block() }

    /** Draws [block] mirrored left to right, for the second of a pair (redo after undo). */
    fun mirrored(block: () -> Unit) = scope.scale(-1f, 1f, p(9f, 9f)) { block() }

    /** Draws [block] with [hole] cut out of it, so a shape in front hides the lines behind. */
    fun behind(hole: Path, block: () -> Unit) = scope.clipPath(hole, ClipOp.Difference) { block() }
}

internal class GridPath(private val s: Float, private val ox: Float = 0f, private val oy: Float = 0f) {
    val path = Path()
    fun m(x: Float, y: Float) = path.moveTo(ox + x * s, oy + y * s)
    fun l(x: Float, y: Float) = path.lineTo(ox + x * s, oy + y * s)
    fun q(x1: Float, y1: Float, x: Float, y: Float) = path.quadraticTo(ox + x1 * s, oy + y1 * s, ox + x * s, oy + y * s)
    fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        path.cubicTo(ox + x1 * s, oy + y1 * s, ox + x2 * s, oy + y2 * s, ox + x * s, oy + y * s)
    fun z() = path.close()
}

/**
 * An icon drawn with [IconPen] in [tint], scaled to [modifier]'s size. It is drawn once into a bitmap of its
 * size, and again only when its size, tint or drawing changes: the window draws every frame while a preview
 * plays, and an icon's handful of antialiased strokes per frame, over every icon in the panels, is a large
 * part of that frame.
 */
@Composable
internal fun GridIcon(modifier: Modifier, tint: Color, draw: IconPen.() -> Unit) {
    Spacer(modifier.drawWithCache {
        if (size.width <= 0f || size.height <= 0f) return@drawWithCache onDrawBehind {}
        // A round cap may reach past the box, which the canvas this replaces did not clip.
        val bleed = ICON_BLEED_PX
        val bitmap = ImageBitmap(ceil(size.width).toInt() + 2 * bleed, ceil(size.height).toInt() + 2 * bleed)
        CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(bitmap), size) {
            translate(bleed.toFloat(), bleed.toFloat()) { IconPen(this, tint).draw() }
        }
        onDrawBehind { drawImage(bitmap, topLeft = Offset(-bleed.toFloat(), -bleed.toFloat())) }
    })
}

internal fun DrawScope.drawGridIcon(tint: Color, draw: IconPen.() -> Unit) = IconPen(this, tint).draw()

// --- glyphs shared across panels -------------------------------------------------------------------------
// One drawing per concept: a warp deformer is the same lattice in the toolbar, the hierarchy, the view
// options and the parameter keys.

/** Warp: a lattice bowed out by the deformer, corners as handles. */
internal fun IconPen.warpLattice() {
    fun at(u: Float, v: Float): Offset {
        val bulge = 0.16f
        val x = 9f + (u - 0.5f) * 12f * (1f + bulge * sin(PI.toFloat() * v))
        val y = 9f + (v - 0.5f) * 12f * (1f + bulge * sin(PI.toFloat() * u))
        return Offset(x, y)
    }
    val steps = 12
    for (i in 0..3) {
        val t = i / 3f
        val edge = i == 0 || i == 3
        val width = if (edge) ICON_LINE else ICON_FINE
        listOf<(Float) -> Offset>({ at(t, it) }, { at(it, t) }).forEach { curve ->
            outline(path {
                val first = curve(0f)
                m(first.x, first.y)
                for (k in 1..steps) curve(k / steps.toFloat()).let { l(it.x, it.y) }
            }, width)
        }
    }
    listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (u, v) -> at(u, v).let { dot(it.x, it.y, 1.7f) } }
}

/** Rotation: a pivot with its handle and the turn it gives. */
internal fun IconPen.rotationDeformer() {
    val cx = 9f
    val cy = 12.4f
    arcArrow(cx, cy, 7f, -158f, -22f)
    line(cx, cy - 2.4f, cx, 2.6f)
    dot(cx, 2.6f, 1.4f)
    dot(cx, cy, 2.4f, soft)
    ring(cx, cy, 2.4f)
}

/** Deform path: a curve through anchors, the middle one showing its tangent handles. */
internal fun IconPen.deformPath(anchor: Color = color) {
    outline(path { m(3f, 14.6f); c(3f, 7f, 15f, 11f, 15f, 3.4f) })
    line(5.2f, 10.2f, 12.8f, 7.8f, width = ICON_FINE)
    ring(5.2f, 10.2f, 1.1f, width = ICON_FINE)
    ring(12.8f, 7.8f, 1.1f, width = ICON_FINE)
    dot(9f, 9f, 1.8f, anchor)
    fillBox(1.6f, 13.2f, 2.8f, 2.8f, 0.5f)
    fillBox(13.6f, 2f, 2.8f, 2.8f, 0.5f)
}

/** A mesh: its triangles and vertices. */
internal fun IconPen.meshPatch() {
    val a = 2.8f to 14.6f
    val b = 7.6f to 3.2f
    val c = 15.2f to 5.4f
    val d = 12.6f to 15f
    fill(path { m(a.first, a.second); l(b.first, b.second); l(c.first, c.second); l(d.first, d.second); z() }, soft)
    listOf(a to b, b to c, c to d, d to a, b to d).forEach { (p, q) ->
        line(p.first, p.second, q.first, q.second, width = if (p == b && q == d) ICON_FINE else ICON_LINE)
    }
    listOf(a, b, c, d).forEach { (x, y) -> dot(x, y, 1.9f) }
}

/** Glue: two meshes whose overlap is welded into one. */
internal fun IconPen.glue() {
    val a = circlePath(6.4f, 9f, 4.8f)
    val b = circlePath(11.6f, 9f, 4.8f)
    fill(Path().apply { op(a, b, PathOperation.Intersect) })
    outline(a)
    outline(b)
}

/** Subdivide: a triangle split at its edge midpoints. */
internal fun IconPen.subdivide() {
    outline(path { m(9f, 2.6f); l(16f, 15.2f); l(2f, 15.2f); z() })
    outline(path { m(5.5f, 8.9f); l(12.5f, 8.9f); l(9f, 15.2f); z() }, width = ICON_FINE)
    dot(5.5f, 8.9f, 1.5f)
    dot(12.5f, 8.9f, 1.5f)
    dot(9f, 15.2f, 1.5f)
}

/** A part: the folder a layer group is kept in. */
internal fun IconPen.folder() {
    shape(path {
        m(2.4f, 5f); q(2.4f, 3.6f, 3.8f, 3.6f); l(6.6f, 3.6f); l(8.2f, 5.4f)
        l(14.2f, 5.4f); q(15.6f, 5.4f, 15.6f, 6.8f); l(15.6f, 13.2f)
        q(15.6f, 14.6f, 14.2f, 14.6f); l(3.8f, 14.6f); q(2.4f, 14.6f, 2.4f, 13.2f); z()
    })
    line(2.4f, 7.6f, 15.6f, 7.6f, width = ICON_FINE)
}

/** A page with its corner folded, and lines of text when [lines]. */
internal fun IconPen.document(lines: Boolean = true) {
    shape(path { m(4f, 2.4f); l(10.6f, 2.4f); l(14.2f, 6f); l(14.2f, 15.6f); l(4f, 15.6f); z() })
    outline(path { m(10.6f, 2.4f); l(10.6f, 6f); l(14.2f, 6f) }, width = ICON_FINE)
    if (lines) {
        line(6.6f, 9.4f, 11.6f, 9.4f, width = ICON_FINE)
        line(6.6f, 12.4f, 10.2f, 12.4f, width = ICON_FINE)
    }
}

/** Two sheets, the front one hiding the back: copy. */
internal fun IconPen.copySheets() {
    behind(rectPath(2.4f, 6.2f, 9.4f, 9.4f, 1.6f)) { box(6.2f, 2.4f, 9.4f, 9.4f, 1.6f) }
    panel(2.4f, 6.2f, 9.4f, 9.4f, 1.6f)
}

/** A clipboard holding a written sheet: paste. */
internal fun IconPen.pasteBoard() {
    shape(rectPath(3.4f, 3.6f, 11.2f, 12.2f, 1.6f))
    fillBox(6.4f, 2.2f, 5.2f, 2.8f, 1f)
    line(6.4f, 9.2f, 11.6f, 9.2f, ICON_FINE)
    line(6.4f, 12.2f, 10.2f, 12.2f, ICON_FINE)
}

/** A bin with its lid: delete, clear. */
internal fun IconPen.trashCan() {
    outline(path { m(6.8f, 4.4f); l(6.8f, 3.4f); q(6.8f, 2.4f, 7.8f, 2.4f); l(10.2f, 2.4f); q(11.2f, 2.4f, 11.2f, 3.4f); l(11.2f, 4.4f) }, ICON_FINE)
    shape(path { m(4.4f, 5f); l(5.2f, 14.6f); q(5.3f, 15.6f, 6.3f, 15.6f); l(11.7f, 15.6f); q(12.7f, 15.6f, 12.8f, 14.6f); l(13.6f, 5f) })
    line(3f, 4.8f, 15f, 4.8f)
    line(7.6f, 7.8f, 7.8f, 12.8f, ICON_FINE)
    line(10.4f, 7.8f, 10.2f, 12.8f, ICON_FINE)
}

/** Undo: a hook back to the left. [IconPen.mirrored] for redo. */
internal fun IconPen.undoArrow() {
    outline(path { m(3.6f, 6.6f); l(10.6f, 6.6f); c(15.7f, 6.6f, 15.7f, 14.2f, 10.6f, 14.2f); l(6.6f, 14.2f) })
    chevron(3.6f, 6.6f, -1f, 0f, 3.2f)
}

/**
 * A field's parameter key, as Blender's key button: hollow while the value is not keyed, its lower half filled between
 * keys, filled on one.
 */
internal fun IconPen.keyDiamond(state: Int) {
    val diamond = path { m(9f, 2.8f); l(15.2f, 9f); l(9f, 15.2f); l(2.8f, 9f); z() }
    when (state) {
        2 -> fill(diamond)
        1 -> fill(path { m(2.8f, 9f); l(15.2f, 9f); l(9f, 15.2f); z() })
    }
    outline(diamond)
}

/** A padlock: locked, fixed in place. */
internal fun IconPen.lockGlyph() {
    outline(path { m(6f, 8.4f); l(6f, 6.4f); c(6f, 2.6f, 12f, 2.6f, 12f, 6.4f); l(12f, 8.4f) })
    shape(rectPath(4.2f, 8.2f, 9.6f, 7.4f, 1.4f))
}

/** A check mark. */
internal fun IconPen.checkMark(width: Float = 1.8f) =
    outline(path { m(3.2f, 9.6f); l(7.2f, 13.6f); l(14.8f, 4.8f) }, width)

/** A cross, reaching [reach] units from the centre. */
internal fun IconPen.cross(reach: Float = 6.2f, width: Float = ICON_LINE) {
    line(9f - reach, 9f - reach, 9f + reach, 9f + reach, width)
    line(9f + reach, 9f - reach, 9f - reach, 9f + reach, width)
}

/** Corner brackets round the box from ([x0], [y0]) to ([x1], [y1]): framing, fit to view. */
internal fun IconPen.brackets(x0: Float, y0: Float, x1: Float, y1: Float, arm: Float = 4f, width: Float = ICON_LINE) {
    listOf(Triple(x0, y0, 1f to 1f), Triple(x1, y0, -1f to 1f), Triple(x0, y1, 1f to -1f), Triple(x1, y1, -1f to -1f))
        .forEach { (x, y, d) -> outline(path { m(x + d.first * arm, y); l(x, y); l(x, y + d.second * arm) }, width) }
}
