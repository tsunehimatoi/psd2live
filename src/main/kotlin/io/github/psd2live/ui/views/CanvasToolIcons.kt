package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.GlueSubTool
import io.github.psd2live.ui.PaintSelectShape
import io.github.psd2live.ui.MeshPointMode
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.SkeletonEditSubTool
import io.github.psd2live.ui.SkeletonPoseSubTool
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.components.ICON_LINE
import io.github.psd2live.ui.components.IconPen
import io.github.psd2live.ui.components.deformPath
import io.github.psd2live.ui.components.drawSingleBoneIcon
import io.github.psd2live.ui.components.glue
import io.github.psd2live.ui.components.meshPatch
import io.github.psd2live.ui.components.rotationDeformer
import io.github.psd2live.ui.components.subdivide
import io.github.psd2live.ui.components.warpLattice
import io.github.psd2live.ui.components.drawBoneIcon
import org.umamo.runtime.model.VertexGroupKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The canvas toolbar's icons, drawn with the shared [IconPen] so the tool rows, the shape rows under
 * them and the panels' icons read as one set. Hand tools (brush, pencil, knife ...) are drawn lying flat
 * with the working end on the left and turned 45 degrees, so they all point the same way.
 */

@Composable
internal fun ToolIcon(
    tool: CanvasTool,
    color: Color,
    brushShape: BrushShape? = null,
    paintShape: PaintShape? = null,
    skeletonEditSubTool: SkeletonEditSubTool? = null,
    paintSelectShape: PaintSelectShape? = null,
    meshPointMode: MeshPointMode? = null,
) {
    Canvas(Modifier.size(18.dp)) { drawToolIcon(tool, color, brushShape, paintShape, skeletonEditSubTool, paintSelectShape, meshPointMode) }
}

/** The hierarchy mode bar's icons, one per mode, on the same grid as the tools. */
@Composable
internal fun ModeIcon(mode: EditHierarchyMode, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawModeIcon(mode, color) }
}

@Composable
internal fun BrushShapeIcon(shape: BrushShape, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawBrushShapeIcon(shape, color) }
}

@Composable
internal fun PaintShapeIcon(shape: PaintShape, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawPaintShapeIcon(shape, color) }
}

@Composable
internal fun MeshPointModeIcon(mode: MeshPointMode, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { IconPen(this, color).meshPoint(mode) }
}

@Composable
internal fun PaintSelectShapeIcon(shape: PaintSelectShape, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { IconPen(this, color).paintSelect(shape) }
}

@Composable
internal fun GlueSubToolIcon(subTool: GlueSubTool, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawGlueSubToolIcon(subTool, color) }
}

@Composable
internal fun SkeletonEditSubToolIcon(subTool: SkeletonEditSubTool, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { IconPen(this, color).skeletonEditSubTool(subTool) }
}

@Composable
internal fun SkeletonPoseSubToolIcon(subTool: SkeletonPoseSubTool, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val pen = IconPen(this, color)
        if (subTool == SkeletonPoseSubTool.IK) pen.skeletonEditSubTool(SkeletonEditSubTool.EXTRUDE)
        else if (subTool == SkeletonPoseSubTool.FK) pen.skeletonPose()
        else { pen.skeletonPose(); pen.dot(14f, 14f, 2f) }
    }
}

internal fun DrawScope.drawToolIcon(
    tool: CanvasTool,
    color: Color,
    brushShape: BrushShape? = null,
    paintShape: PaintShape? = null,
    skeletonEditSubTool: SkeletonEditSubTool? = null,
    paintSelectShape: PaintSelectShape? = null,
    meshPointMode: MeshPointMode? = null,
) {
    val pen = IconPen(this, color)
    when (tool) {
        CanvasTool.SELECT -> pen.selectArrow()
        CanvasTool.TRANSFORM -> {
            pen.box(3f, 3f, 12f, 12f, r = 0f)
            for (x in listOf(3f, 15f)) for (y in listOf(3f, 15f))
                pen.fillBox(x - 1.5f, y - 1.5f, 3f, 3f, r = 0f)
        }
        CanvasTool.LASSO_SELECT -> pen.lasso()
        CanvasTool.BRUSH_SELECT -> pen.brushSelect()
        CanvasTool.BRUSH -> pen.deformBrush(brushShape ?: BrushShape.CIRCLE)
        CanvasTool.SMOOTH -> pen.smooth()
        CanvasTool.INFLATE -> pen.inflate()
        CanvasTool.SKELETON_POSE -> pen.skeletonPose()
        CanvasTool.SKELETON_EDIT -> pen.skeletonEditSubTool(skeletonEditSubTool ?: SkeletonEditSubTool.EDIT)
        CanvasTool.CREATE_WARP -> pen.warpLattice()
        CanvasTool.CREATE_ROTATION -> pen.rotationDeformer()
        CanvasTool.CREATE_DEFORM_PATH -> pen.deformPath()
        CanvasTool.GLUE -> pen.glue()
        CanvasTool.SUBDIVIDE -> pen.subdivide()
        CanvasTool.MESH_POINT -> pen.meshPoint(meshPointMode ?: MeshPointMode.POINT)
        CanvasTool.WEIGHT_PAINT -> pen.weightPaint()
        CanvasTool.WEIGHT_GRADIENT -> pen.weightGradient()
        CanvasTool.PAINT_BRUSH -> pen.paintBrush()
        CanvasTool.PAINT_PENCIL -> pen.pencil()
        CanvasTool.PAINT_ERASER -> pen.eraser()
        CanvasTool.PAINT_BUCKET -> pen.bucket()
        CanvasTool.PAINT_EYEDROPPER -> pen.eyedropper()
        // The row carries the face in hand, the way the deform brush's row carries its footprint.
        CanvasTool.PAINT_SHAPE -> pen.paintShape(paintShape ?: PaintShape.LINE)
        CanvasTool.INTERIOR -> pen.interiorHandles()
        CanvasTool.FEATURE -> pen.featureHandles()
        CanvasTool.PAINT_SELECT -> pen.paintSelect(paintSelectShape ?: PaintSelectShape.RECTANGLE)
        CanvasTool.PAINT_GRADIENT -> pen.paintGradient()
        CanvasTool.PAINT_BLUR -> pen.blurDrop()
        // A dashed box with its corner handles, a stroke inside it: the pixels in a free transform.
        CanvasTool.PAINT_TRANSFORM -> {
            pen.box(3f, 3f, 12f, 12f, r = 0f, dash = floatArrayOf(1.6f, 1.6f))
            for (x in listOf(3f, 15f)) for (y in listOf(3f, 15f)) pen.fillBox(x - 1.5f, y - 1.5f, 3f, 3f, r = 0f)
            pen.line(6.4f, 11.6f, 11.6f, 6.4f, width = 1.6f)
        }
    }
}

internal fun DrawScope.drawModeIcon(mode: EditHierarchyMode, color: Color) {
    val pen = IconPen(this, color)
    when (mode) {
        // Object: a part inside the transform box it is moved by.
        EditHierarchyMode.SELECT -> {
            pen.box(3.4f, 3.4f, 11.2f, 11.2f, 0.6f, width = 1.1f)
            pen.dot(9f, 9f, 3.4f, pen.soft)
            pen.ring(9f, 9f, 3.4f)
            listOf(3.4f to 3.4f, 14.6f to 3.4f, 3.4f to 14.6f, 14.6f to 14.6f).forEach { (x, y) ->
                pen.fillBox(x - 1.7f, y - 1.7f, 3.4f, 3.4f, 0.6f)
            }
        }
        // Deform: the part's rest shape (dashed) and the shape it is bent into, in front of it.
        EditHierarchyMode.DEFORM -> {
            val bent = pen.path {
                m(6.4f, 2.2f)
                c(9.4f, 0.6f, 12.4f, 4f, 15.8f, 2.4f)
                c(14f, 5.6f, 17.4f, 9.2f, 15.6f, 12.2f)
                c(12.4f, 13.8f, 9.4f, 10.4f, 6.2f, 12f)
                c(7.8f, 8.8f, 4.6f, 5.4f, 6.4f, 2.2f)
                z()
            }
            val dash = 1.8f * pen.s
            clipPath(bent, ClipOp.Difference) {
                drawRoundRect(
                    color, pen.p(2.4f, 6f), Size(9.6f * pen.s, 9.6f * pen.s), CornerRadius(0.8f * pen.s),
                    style = Stroke(1.1f * pen.s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            }
            pen.fill(bent, pen.soft)
            pen.outline(bent)
        }
        // Edit: the mesh itself, its triangles and vertices.
        EditHierarchyMode.EDIT -> pen.meshPatch()
        // Simulate: a cloth pinned along its top edge, its hem swinging free.
        EditHierarchyMode.SIMULATE -> {
            val cloth = pen.path {
                m(3.2f, 3.4f)
                l(14.8f, 3.4f)
                c(15.4f, 7.6f, 16.6f, 11.4f, 15.6f, 14.8f)
                c(13.6f, 13.2f, 11.6f, 16.4f, 9.2f, 14.6f)
                c(7f, 16.4f, 4.6f, 13.2f, 2.4f, 14.6f)
                c(2f, 10.6f, 3.4f, 7f, 3.2f, 3.4f)
                z()
            }
            pen.fill(cloth, pen.soft)
            pen.outline(cloth)
            pen.dot(3.2f, 3.4f, 1.8f)
            pen.dot(9f, 3.4f, 1.8f)
            pen.dot(14.8f, 3.4f, 1.8f)
        }
        EditHierarchyMode.SKELETON -> drawSingleBoneIcon(color)
        // Paint: a palette with its wells.
        EditHierarchyMode.PAINT -> {
            val palette = pen.path {
                m(9f, 2f)
                c(13f, 2f, 16f, 5f, 16f, 8.6f)
                c(16f, 11f, 14.3f, 11.4f, 12.8f, 11.1f)
                c(11.2f, 10.8f, 10.3f, 12.2f, 11f, 13.6f)
                c(11.6f, 15f, 10.6f, 16f, 9f, 16f)
                c(5.1f, 16f, 2f, 12.9f, 2f, 9f)
                c(2f, 5.1f, 5.1f, 2f, 9f, 2f)
                z()
            }
            pen.fill(palette, pen.soft)
            pen.outline(palette)
            pen.dot(5.6f, 7.6f, 1.4f)
            pen.dot(8.8f, 5.2f, 1.4f)
            pen.dot(12.4f, 6.4f, 1.4f)
            pen.dot(5.8f, 11.8f, 1.4f)
        }
    }
}

/** Preview in the mode list: an eye, since the canvas only watches the model play. */
internal fun DrawScope.drawPreviewModeIcon(color: Color) {
    val pen = IconPen(this, color)
    val eye = pen.path {
        m(1.8f, 9f)
        c(4.2f, 4.4f, 13.8f, 4.4f, 16.2f, 9f)
        c(13.8f, 13.6f, 4.2f, 13.6f, 1.8f, 9f)
        z()
    }
    pen.fill(eye, pen.soft)
    pen.outline(eye)
    pen.ring(9f, 9f, 2.6f)
    pen.dot(9f, 9f, 1.1f)
}

internal fun DrawScope.drawBrushShapeIcon(shape: BrushShape, color: Color) {
    val pen = IconPen(this, color)
    when (shape) {
        BrushShape.CIRCLE -> {
            pen.ring(9f, 9f, 6.2f)
            pen.dot(9f, 9f, 1.6f)
        }
        BrushShape.LINE -> {
            pen.line(4f, 14f, 14f, 4f)
            pen.dot(4f, 14f, 1.9f)
            pen.dot(14f, 4f, 1.9f)
        }
        BrushShape.RECTANGLE -> {
            pen.fillBox(3f, 4.5f, 12f, 9f, 1.4f, pen.soft)
            pen.box(3f, 4.5f, 12f, 9f, 1.4f)
        }
    }
}

internal fun DrawScope.drawPaintShapeIcon(shape: PaintShape, color: Color) = IconPen(this, color).paintShape(shape)

internal fun DrawScope.drawGlueSubToolIcon(subTool: GlueSubTool, color: Color) {
    val pen = IconPen(this, color)
    when (subTool) {
        // A brush ring over a weld: two points pulled into one.
        GlueSubTool.BRUSH -> {
            pen.ring(9f, 9f, 6.8f)
            pen.line(6.6f, 9f, 11.4f, 9f)
            pen.dot(6.6f, 9f, 1.9f)
            pen.dot(11.4f, 9f, 1.9f)
        }
        // A balance: how the weld's weight splits between A and B.
        GlueSubTool.WEIGHT -> {
            val fulcrum = pen.path { m(9f, 7f); l(12f, 14f); l(6f, 14f); z() }
            pen.fill(fulcrum, pen.soft)
            pen.outline(fulcrum)
            pen.line(2.5f, 7f, 15.5f, 7f)
            pen.dot(3f, 7f, 1.9f)
            pen.dot(15f, 7f, 1.9f)
        }
        // Two sides pushed back together onto a seam.
        GlueSubTool.REMERGE -> {
            pen.line(9f, 3f, 9f, 15f)
            pen.line(2f, 9f, 6.6f, 9f)
            pen.chevron(6.6f, 9f, 1f, 0f, 3.2f)
            pen.line(16f, 9f, 11.4f, 9f)
            pen.chevron(11.4f, 9f, -1f, 0f, 3.2f)
        }
    }
}

// --- selection -------------------------------------------------------------------------------------------

private fun IconPen.selectArrow() {
    val arrow = path {
        m(4.5f, 2.5f); l(4.5f, 14.5f); l(7.6f, 11.8f); l(9.5f, 16f); l(11.6f, 15.1f); l(9.7f, 10.9f); l(13.8f, 10.8f); z()
    }
    fill(arrow, soft)
    outline(arrow)
}

private fun IconPen.lasso() {
    val loop = path {
        m(6.2f, 11.6f)
        c(2.2f, 10.4f, 2.4f, 3.6f, 9.6f, 3.1f)
        c(15.4f, 2.7f, 17.2f, 7.4f, 13.2f, 10f)
        c(11.2f, 11.4f, 8.4f, 12.1f, 6.2f, 11.6f)
    }
    fill(loop, soft)
    outline(loop)
    outline(path { m(6.2f, 11.6f); c(4.4f, 12.6f, 6.6f, 14.4f, 4.2f, 16f) })
    dot(6.2f, 11.6f, 1.5f)
}

private fun IconPen.brushSelect() {
    val r = 6.4f
    val period = (2 * PI * r / 10).toFloat()
    scope.drawCircle(
        color, r * s, p(9f, 9f),
        style = Stroke(ICON_LINE * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(period * 0.55f * s, period * 0.45f * s))),
    )
    dot(9f, 9f, 2.6f)
}

// --- deform brushes --------------------------------------------------------------------------------------

/** The deform brush: its footprint in hand, dragged along with the mesh under it. */
private fun IconPen.deformBrush(shape: BrushShape) {
    when (shape) {
        BrushShape.CIRCLE -> {
            scope.drawCircle(soft, 5f * s, p(11f, 7f))
            ring(11f, 7f, 5f)
        }
        BrushShape.RECTANGLE -> {
            fillBox(6.4f, 2.6f, 9.2f, 8.8f, 1.2f, soft)
            box(6.4f, 2.6f, 9.2f, 8.8f, 1.2f)
        }
        BrushShape.LINE -> {
            line(6.4f, 2.4f, 15.6f, 11.6f)
            dot(6.4f, 2.4f, 1.6f)
            dot(15.6f, 11.6f, 1.6f)
        }
    }
    line(2.6f, 15.4f, 6.4f, 11.6f)
    line(2.2f, 11.2f, 4.2f, 9.2f)
    line(6.8f, 15.8f, 8.8f, 13.8f)
}

/** Smooth: a ripple dying out into a straight line under the brush. */
private fun IconPen.smooth() {
    ring(9f, 9f, 7.2f, width = 1.1f)
    outline(path {
        m(3.2f, 9.6f)
        c(4.2f, 5.2f, 6.2f, 5.2f, 7.1f, 9f)
        c(7.9f, 11.6f, 9.6f, 11.4f, 10.6f, 9.4f)
        q(11.4f, 8.4f, 14.8f, 8.6f)
    })
}

/** Inflate: a core pushed outwards on all four sides. */
private fun IconPen.inflate() {
    scope.drawCircle(soft, 2.8f * s, p(9f, 9f))
    ring(9f, 9f, 2.8f)
    listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f).forEach { (dx, dy) ->
        line(9f + dx * 5.2f, 9f + dy * 5.2f, 9f + dx * 7.8f, 9f + dy * 7.8f)
        chevron(9f + dx * 7.8f, 9f + dy * 7.8f, dx, dy, 2.3f)
    }
}

// --- skeleton --------------------------------------------------------------------------------------------

private fun IconPen.skeletonEditSubTool(subTool: SkeletonEditSubTool) {
    when (subTool) {
        SkeletonEditSubTool.EDIT -> skeletonEdit()
        SkeletonEditSubTool.BIND -> glue()
        SkeletonEditSubTool.WEIGHTS -> weightPaint()
        SkeletonEditSubTool.NEW_BONE -> {
            scope.drawBoneIcon(p(3.5f, 13f), p(11f, 5.5f), color, stroke = px(1.2f), headRadius = 1.6f * s)
            line(11.5f, 13.5f, 16.5f, 13.5f)
            line(14f, 11f, 14f, 16f)
        }
        SkeletonEditSubTool.EXTRUDE -> {
            scope.drawBoneIcon(p(2.5f, 15.5f), p(8.5f, 9.5f), color, stroke = px(1.2f), headRadius = 1.5f * s)
            scope.drawBoneIcon(p(8.5f, 9.5f), p(14.5f, 3.5f), color, stroke = px(1.2f), headRadius = 1.5f * s)
            line(11.5f, 13.5f, 16f, 9f)
            chevron(16f, 9f, 1f, -1f, 2.5f)
        }
    }
}

/** Pose: a bone swung about its head. */
private fun IconPen.skeletonPose() {
    scope.drawBoneIcon(p(4.2f, 13.8f), p(12.2f, 5.8f), color, stroke = px(1.2f), headRadius = 1.9f * s)
    val r = 12f
    val start = -80f
    val end = -12f
    scope.drawArc(
        color, start, end - start, false, p(4.2f - r, 13.8f - r), Size(2 * r * s, 2 * r * s),
        style = stroke(),
    )
    val a = Math.toRadians(end.toDouble())
    chevron(4.2f + r * cos(a).toFloat(), 13.8f + r * sin(a).toFloat(), -sin(a).toFloat(), cos(a).toFloat(), 2.8f)
}

/** Edit: a bone with both joints opened as handles. */
private fun IconPen.skeletonEdit() {
    scope.drawBoneIcon(p(4.8f, 13.2f), p(13.2f, 4.8f), color, stroke = px(1.2f), headRadius = 0f)
    listOf(3.6f to 14.4f, 14.4f to 3.6f).forEach { (x, y) ->
        dot(x, y, 2.4f, soft)
        ring(x, y, 2.4f)
    }
}

// --- deformers -------------------------------------------------------------------------------------------

// --- mesh editing ----------------------------------------------------------------------------------------

/** The point tool's face in hand: a lone point, or a line of points joined by edges. */
private fun IconPen.meshPoint(mode: MeshPointMode) = if (mode == MeshPointMode.LINE) meshLine() else meshPoints()

/** Mesh points: a mesh triangle on its corners, and the new point being joined to it. */
private fun IconPen.meshPoints() {
    shape(path { m(2.4f, 13f); l(7.6f, 2.8f); l(12.2f, 10.4f); z() }, width = ICON_FINE)
    outline(path { m(12.2f, 10.4f); l(15f, 15f); l(2.4f, 13f) }, width = ICON_FINE, dash = floatArrayOf(1.4f, 1.1f))
    for ((x, y) in listOf(2.4f to 13f, 7.6f to 2.8f, 12.2f to 10.4f)) dot(x, y, 1.4f)
    ring(15f, 15f, 2.2f)
}

/** A mesh line: a triangle crossed by a solid polyline whose three points it is now split along. */
private fun IconPen.meshLine() {
    shape(path { m(2.4f, 15.4f); l(9f, 2.4f); l(15.6f, 15.4f); z() }, width = ICON_FINE)
    outline(path { m(1.4f, 11.6f); l(9f, 8.4f); l(16.6f, 11.6f) })
    for ((x, y) in listOf(1.4f to 11.6f, 9f to 8.4f, 16.6f to 11.6f)) dot(x, y, 1.6f)
}

/** Weight paint: the paint brush pressed into the patch of falloff it lays its weight over. */
private fun IconPen.weightPaint() {
    dot(4.6f, 13.4f, 3.4f, soft)
    ring(4.6f, 13.4f, 3.4f, width = ICON_FINE, dash = floatArrayOf(1.4f, 1.1f))
    scope.translate(1.6f * s, -1.6f * s) {
        turned(-45f) {
            fill(path {
                m(0.6f, 9f)
                c(2f, 7.4f, 3.8f, 6.9f, 5.8f, 7.1f)
                l(5.8f, 10.9f)
                c(3.8f, 11.1f, 2f, 10.6f, 0.6f, 9f)
                z()
            })
            box(6.4f, 6.9f, 3f, 4.2f, 0.6f)
            outline(path { m(10f, 7.6f); l(16.2f, 8.1f); q(17.8f, 9f, 16.2f, 9.9f); l(10f, 10.4f) })
        }
    }
}

/**
 * Weight gradient: the gradient tool's I standing upright - full weight at the bar it starts from, none at the bar it
 * ends on, and the stem between fading from one to the other.
 */
private fun IconPen.weightGradient() {
    fillBox(3.6f, 2.4f, 10.8f, 2.6f, 1.1f)
    val steps = 5
    for (i in 0 until steps) {
        val y0 = 5f + i * 8f / steps
        line(9f, y0, 9f, y0 + 8f / steps, tint = color.copy(alpha = color.alpha * (1f - 0.75f * (i + 0.5f) / steps)))
    }
    box(3.6f, 13f, 10.8f, 2.6f, 1.1f)
}

/** Interior handles: the virtual arch over its rest line, held by the handle at its crown. */
/** An eye's outline with its four handles: the corners and the middles of both lids. */
private fun IconPen.featureHandles() {
    outline(path { m(2.6f, 9.4f); c(5.4f, 4.2f, 12.6f, 4.2f, 15.4f, 9.4f); c(12.6f, 13.2f, 5.4f, 13.2f, 2.6f, 9.4f) }, width = ICON_FINE)
    for ((x, y) in listOf(2.6f to 9.4f, 15.4f to 9.4f, 9f to 5.5f, 9f to 12.3f)) fillBox(x - 1.5f, y - 1.5f, 3f, 3f, r = 1.5f)
}

private fun IconPen.interiorHandles() {
    outline(path { m(2.4f, 14.2f); l(15.6f, 14.2f) }, width = ICON_FINE, dash = floatArrayOf(1.4f, 1.1f))
    val arch = path { m(2.4f, 14.2f); c(4.2f, 5.6f, 13.8f, 5.6f, 15.6f, 14.2f) }
    outline(arch)
    line(9f, 14.2f, 9f, 8.4f, width = ICON_FINE)
    dot(9f, 7.8f, 2.2f, soft)
    ring(9f, 7.8f, 2.2f)
}

// --- painting --------------------------------------------------------------------------------------------

private fun IconPen.paintBrush() = turned(-45f) {
    fill(path {
        m(0.6f, 9f)
        c(2f, 7.4f, 3.8f, 6.9f, 5.8f, 7.1f)
        l(5.8f, 10.9f)
        c(3.8f, 11.1f, 2f, 10.6f, 0.6f, 9f)
        z()
    })
    box(6.4f, 6.9f, 3f, 4.2f, 0.6f)
    outline(path { m(10f, 7.6f); l(16.2f, 8.1f); q(17.8f, 9f, 16.2f, 9.9f); l(10f, 10.4f) })
}

internal fun IconPen.pencil() = turned(-45f) {
    fillBox(14f, 6.8f, 3.4f, 4.4f, 1.2f, soft)
    outline(path {
        m(5f, 6.8f); l(16.2f, 6.8f)
        q(17.4f, 6.8f, 17.4f, 8f); l(17.4f, 10f)
        q(17.4f, 11.2f, 16.2f, 11.2f); l(5f, 11.2f)
        l(0.8f, 9f); z()
    })
    line(5f, 6.8f, 5f, 11.2f, width = 1f)
    line(14f, 6.8f, 14f, 11.2f, width = 1f)
    fill(path { m(0.8f, 9f); l(2.5f, 8.1f); l(2.5f, 9.9f); z() })
}

private fun IconPen.eraser() {
    turned(-45f, 9f, 8f) {
        fillBox(3.5f, 5.3f, 4.4f, 5.4f, 1.2f, soft)
        box(3.5f, 5.3f, 11f, 5.4f, 1.2f)
        line(7.9f, 5.3f, 7.9f, 10.7f)
    }
    line(9.5f, 15.8f, 16f, 15.8f)
}

private fun IconPen.bucket() {
    turned(-30f, 8f, 10f) {
        val body = path {
            m(2.6f, 7f); l(13.4f, 7f)
            l(12.2f, 14.6f); q(12f, 15.8f, 10.8f, 15.8f)
            l(5.2f, 15.8f); q(4f, 15.8f, 3.8f, 14.6f); z()
        }
        fill(body, soft)
        outline(body)
        outline(path { m(4f, 7f); c(4f, 1.8f, 12f, 1.8f, 12f, 7f) }, width = 1.1f)
    }
    fill(path {
        m(15.4f, 9.4f)
        c(16.4f, 11f, 17.2f, 12.2f, 17.2f, 13.3f)
        c(17.2f, 14.4f, 16.4f, 15.1f, 15.4f, 15.1f)
        c(14.4f, 15.1f, 13.6f, 14.4f, 13.6f, 13.3f)
        c(13.6f, 12.2f, 14.4f, 11f, 15.4f, 9.4f)
        z()
    })
}

private fun IconPen.eyedropper() = turned(-45f) {
    fill(path { m(1.6f, 9f); l(3.4f, 8f); l(6.4f, 8f); l(6.4f, 10f); l(3.4f, 10f); z() }, soft)
    outline(path { m(10.2f, 7.7f); l(3.2f, 7.7f); l(0.8f, 9f); l(3.2f, 10.3f); l(10.2f, 10.3f) })
    line(10.6f, 6.2f, 10.6f, 11.8f, width = 1.6f)
    fillBox(11.6f, 6.8f, 5.8f, 4.4f, 2.2f)
}

private fun IconPen.paintShape(shape: PaintShape) {
    when (shape) {
        PaintShape.LINE -> {
            line(3.6f, 14.4f, 14.4f, 3.6f)
            fillBox(2.2f, 13f, 2.8f, 2.8f, 0.5f)
            fillBox(13f, 2.2f, 2.8f, 2.8f, 0.5f)
        }
        PaintShape.RECTANGLE -> box(2.6f, 4.2f, 12.8f, 9.6f, 1.2f)
        PaintShape.ELLIPSE ->
            scope.drawOval(color, p(2.2f, 4f), Size(13.6f * s, 10f * s), style = stroke())
    }
}

/** The selection tool's faces: marching-ant outlines, and the wand with its sparkle. */
private fun IconPen.paintSelect(shape: PaintSelectShape) {
    val ants = floatArrayOf(1.6f, 1.6f)
    when (shape) {
        PaintSelectShape.RECTANGLE -> box(2.6f, 3.6f, 12.8f, 10.8f, 0.4f, dash = ants)
        PaintSelectShape.ELLIPSE -> ring(9f, 9f, 6.4f, dash = ants)
        PaintSelectShape.LASSO -> lasso()
        PaintSelectShape.POLYGON -> {
            outline(path { m(3f, 13.8f); l(5.2f, 3.4f); l(14.6f, 5.6f); l(12.4f, 14.4f); z() }, dash = ants)
            for ((x, y) in listOf(3f to 13.8f, 5.2f to 3.4f, 14.6f to 5.6f, 12.4f to 14.4f)) fillBox(x - 1.2f, y - 1.2f, 2.4f, 2.4f, 0.4f)
        }
        PaintSelectShape.WAND -> {
            line(3f, 15f, 11f, 7f, width = 1.8f)
            line(13f, 1.6f, 13f, 6.4f, width = 1.1f)
            line(10.6f, 4f, 15.4f, 4f, width = 1.1f)
            dot(16f, 9.6f, 0.9f)
            dot(7.6f, 2.6f, 0.9f)
        }
        // A brush tip whose outline is marching ants, with the handle trailing off it.
        PaintSelectShape.QUICK -> {
            ring(7.4f, 7.4f, 5.2f, dash = ants)
            line(11.2f, 11.2f, 15.6f, 15.6f, width = 2f)
        }
    }
}

/** Gradient: a box whose fill fades from full to nothing across it. */
private fun IconPen.paintGradient() {
    val steps = 6
    for (i in 0 until steps) {
        fillBox(3f + i * 12f / steps, 4f, 12f / steps + 0.05f, 10f, 0f, tint = color.copy(alpha = color.alpha * (1f - (i + 0.5f) / steps)))
    }
    box(3f, 4f, 12f, 10f, 1f)
}

/** Blur: a drop of water. */
private fun IconPen.blurDrop() = shape(path {
    m(9f, 2f)
    c(11.6f, 5.6f, 14.4f, 8.6f, 14.4f, 11.2f)
    c(14.4f, 14.2f, 12f, 16.2f, 9f, 16.2f)
    c(6f, 16.2f, 3.6f, 14.2f, 3.6f, 11.2f)
    c(3.6f, 8.6f, 6.4f, 5.6f, 9f, 2f)
    z()
})

/** What each kind of vertex group does to a simulated mesh, drawn on the tool grid. */
@Composable
internal fun VertexGroupKindIcon(kind: VertexGroupKind, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawVertexGroupKindIcon(kind, color) }
}

internal fun DrawScope.drawVertexGroupKindIcon(kind: VertexGroupKind, color: Color) {
    val pen = IconPen(this, color)
    when (kind) {
        // A pushpin: the head, and the needle into the mesh.
        VertexGroupKind.PIN -> {
            pen.dot(9f, 6f, 3.6f, pen.soft)
            pen.ring(9f, 6f, 3.6f)
            pen.line(9f, 9.6f, 9f, 15.4f)
        }
        // A spring.
        VertexGroupKind.STIFFNESS -> pen.outline(pen.path {
            m(2.4f, 9f); l(4.4f, 9f); l(6f, 4f); l(8.4f, 14f); l(10.8f, 4f); l(13.2f, 14f); l(14.4f, 9f); l(15.6f, 9f)
        })
        // A weight with its handle.
        VertexGroupKind.MASS -> {
            val body = pen.path { m(5.4f, 7.4f); l(12.6f, 7.4f); l(15f, 15.2f); l(3f, 15.2f); z() }
            pen.fill(body, pen.soft)
            pen.outline(body)
            pen.ring(9f, 4.8f, 2.1f)
        }
        // A swing that dies out.
        VertexGroupKind.DAMPING -> pen.outline(pen.path {
            m(2f, 9f); q(3.6f, 1.6f, 5.2f, 9f); q(6.6f, 15f, 8f, 9f); q(9.2f, 5.2f, 10.4f, 9f); q(11.4f, 11.6f, 12.4f, 9f); l(16f, 9f)
        })
        // Gusts.
        VertexGroupKind.WIND -> {
            pen.outline(pen.path { m(2.4f, 6f); l(11.6f, 6f); q(14.4f, 6f, 14.4f, 3.8f); q(14.4f, 2f, 12.6f, 2.2f) })
            pen.outline(pen.path { m(2.4f, 10f); l(13.8f, 10f); q(16.2f, 10f, 16.2f, 12.2f); q(16.2f, 14f, 14.4f, 13.8f) })
            pen.line(2.4f, 14f, 8.4f, 14f)
        }
        // The drawn shape, and the way back to it.
        VertexGroupKind.GOAL -> {
            pen.fillBox(9f, 9f, 6.4f, 6.4f, 1.2f, pen.soft)
            pen.box(9f, 9f, 6.4f, 6.4f, 1.2f)
            pen.outline(pen.path { m(3.4f, 13.6f); q(3.4f, 4f, 12.2f, 4f) })
            pen.chevron(12.2f, 4f, 1f, 0f, 2.6f)
        }
    }
}

/** The simulation panel's sections. */
internal enum class SimSectionIcon { BAKE, MATERIAL, INPUTS, OUTPUTS, GLUE, GROUPS }

@Composable
internal fun SimSectionIconView(icon: SimSectionIcon, color: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) { drawSimSectionIcon(icon, color) }
}

internal fun DrawScope.drawSimSectionIcon(icon: SimSectionIcon, color: Color) {
    val pen = IconPen(this, color)
    when (icon) {
        // Motion caught into keys: a swing over a keyframe.
        SimSectionIcon.BAKE -> {
            pen.outline(pen.path { m(2.4f, 5.4f); q(5.6f, 0.6f, 9f, 5.4f); q(12.4f, 10.2f, 15.6f, 5.4f) })
            val key = pen.path { m(9f, 9.4f); l(13f, 13.2f); l(9f, 17f); l(5f, 13.2f); z() }
            pen.fill(key, pen.soft)
            pen.outline(key)
        }
        // A hanging cloth, like the Simulate mode.
        SimSectionIcon.MATERIAL -> {
            val cloth = pen.path {
                m(3.4f, 3.4f); l(14.6f, 3.4f)
                c(15.2f, 7.6f, 16.2f, 11.4f, 15.2f, 14.8f)
                c(13.4f, 13.2f, 11.4f, 16.4f, 9f, 14.6f)
                c(6.8f, 16.4f, 4.6f, 13.2f, 2.8f, 14.8f)
                c(2f, 10.6f, 3.4f, 7f, 3.4f, 3.4f)
                z()
            }
            pen.fill(cloth, pen.soft)
            pen.outline(cloth)
        }
        // Two parameter sliders.
        SimSectionIcon.INPUTS -> {
            pen.line(2.6f, 5.6f, 15.4f, 5.6f)
            pen.dot(6.4f, 5.6f, 2.2f)
            pen.line(2.6f, 12.4f, 15.4f, 12.4f)
            pen.dot(11.8f, 12.4f, 2.2f)
        }
        // A pendulum hanging from its pivot: what the bake drives.
        SimSectionIcon.OUTPUTS -> {
            pen.line(4.4f, 3f, 13.6f, 3f)
            pen.line(9f, 3f, 12.2f, 11f)
            pen.dot(12.6f, 12.4f, 2.6f)
        }
        SimSectionIcon.GLUE -> pen.glue()
        // Three vertices, each weighted differently.
        SimSectionIcon.GROUPS -> {
            val tri = pen.path { m(9f, 3.6f); l(15f, 14.4f); l(3f, 14.4f); z() }
            pen.fill(tri, pen.soft)
            pen.outline(tri)
            pen.dot(9f, 3.6f, 2.3f)
            pen.dot(15f, 14.4f, 1.6f)
            pen.dot(3f, 14.4f, 1.1f)
        }
    }
}

/** The interior panel's sections, its solo view and its transmission direction (drawn pointing down). */
internal enum class InteriorIcon { AUDITION, SHAPE, MOTION, BINDING, FOLDS, SOLO, DIRECTION }

@Composable
internal fun InteriorIconView(icon: InteriorIcon, color: Color, size: Dp = 12.dp, degrees: Float = 0f) {
    Canvas(Modifier.size(size)) { IconPen(this, color).run { turned(degrees) { drawInteriorIcon(icon) } } }
}

private fun IconPen.drawInteriorIcon(icon: InteriorIcon) {
    when (icon) {
        // The 2D pad with its handle.
        InteriorIcon.AUDITION -> {
            panel(3f, 3f, 12f, 12f)
            line(9f, 5f, 9f, 13f, ICON_FINE)
            line(5f, 9f, 13f, 9f, ICON_FINE)
            dot(11.4f, 6.6f, 1.9f)
        }
        // A surface bulging over its base.
        InteriorIcon.SHAPE -> {
            val dome = path { m(2.6f, 13.4f); q(9f, 1.8f, 15.4f, 13.4f); z() }
            fill(dome, soft)
            outline(dome)
        }
        // A swing easing into its follower.
        InteriorIcon.MOTION -> {
            outline(path { m(2.4f, 12.4f); c(5.6f, 12.4f, 5.4f, 5.6f, 9f, 5.6f); c(12.6f, 5.6f, 12.4f, 12.4f, 15.6f, 12.4f) })
            dot(9f, 5.6f, 1.6f)
        }
        // Two parameter sliders.
        InteriorIcon.BINDING -> {
            line(2.6f, 5.6f, 15.4f, 5.6f)
            dot(6.4f, 5.6f, 2.2f)
            line(2.6f, 12.4f, 15.4f, 12.4f)
            dot(11.8f, 12.4f, 2.2f)
        }
        // Two creases.
        InteriorIcon.FOLDS -> for (x in listOf(6f, 12f)) {
            outline(path { m(x, 2.6f); q(x - 2.6f, 5.8f, x, 9f); q(x + 2.6f, 12.2f, x, 15.4f) })
        }
        // A sight on the layer.
        InteriorIcon.SOLO -> {
            ring(9f, 9f, 5.4f)
            dot(9f, 9f, 1.6f)
            line(9f, 1.8f, 9f, 4f)
            line(9f, 14f, 9f, 16.2f)
            line(1.8f, 9f, 4f, 9f)
            line(14f, 9f, 16.2f, 9f)
        }
        // The edge the motion starts from, and the wave it sends across the layer, strongest where it lands.
        InteriorIcon.DIRECTION -> {
            line(3.4f, 3f, 14.6f, 3f)
            for ((y, alpha) in listOf(9.6f to 0.45f, 15f to 1f)) {
                outline(path { m(4.6f, y - 3.2f); l(9f, y); l(13.4f, y - 3.2f) }, tint = color.copy(alpha = color.alpha * alpha))
            }
        }
    }
}
