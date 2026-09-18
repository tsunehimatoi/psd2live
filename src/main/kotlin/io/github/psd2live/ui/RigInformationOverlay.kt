package io.github.psd2live.ui

import io.github.psd2live.core.DeformPathTools
import io.github.psd2live.core.RigGeometryTools
import org.umamo.runtime.model.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D

/** Probe the actual parent cascade, rather than drawing undeformed rectangles. */
internal object RigInformationOverlay {
    fun warpPoints(model: PuppetModel, parameters: Map<ParameterId, Float>, ids: Set<String>): Map<String, FloatArray> {
        val warps = model.deformers.filterIsInstance<Deformer.Warp>().filter { it.id.raw in ids }
        require(warps.size == ids.size) { "Information layer requires existing Warp IDs" }
        val probes = warps.map { w ->
            val points = FloatArray((w.rows+1)*(w.columns+1)*2)
            for(r in 0..w.rows) for(c in 0..w.columns) { val i=(r*(w.columns+1)+c)*2; points[i]=c.toFloat()/w.columns; points[i+1]=r.toFloat()/w.rows }
            Drawable(DrawableId("__overlay_${w.id.raw}"), w.name, w.id, BlendMode.Normal, emptyList(),
                DrawableMesh(points, FloatArray(points.size), intArrayOf()), null)
        }
        val geometry = CpuDeformationEvaluator().evaluate(model.copy(drawables=probes, glues=emptyList()).withDerivedRenderRoot(), parameters)
        return warps.zip(probes).mapNotNull { (warp,probe) -> geometry.worldPositions[probe.id]?.let { warp.id.raw to it } }.toMap()
    }

    fun paint(
        g: Graphics2D,
        model: PuppetModel,
        parameters: Map<ParameterId, Float>,
        viewport: CanvasViewport,
        ids: Set<String>,
        labels: Boolean = true,
        pointIndices: Boolean = false,
        selectedDeformerId: String? = null,
        hoveredDeformerId: String? = null,
        dimUnselected: Boolean = false,
    ) {
        if (ids.isEmpty()) return
        val pointsById = warpPoints(model, parameters, ids)
        // Laid out once for the whole pass, because a mark's size depends on what else shares its corner.
        val corners = RigCanvasSupport.deformerCorners(RigCanvasSupport.deformerOutlines(model, pointsById), viewport)
        val layers = model.deformers.filterIsInstance<Deformer.Warp>().filter { it.id.raw in ids }.mapNotNull { w ->
            val p = pointsById[w.id.raw] ?: return@mapNotNull null
            val isSelected = selectedDeformerId != null && w.id.raw == selectedDeformerId
            val isHovered = hoveredDeformerId != null && w.id.raw == hoveredDeformerId && !isSelected
            // Nothing selected means every warp is "unselected", so the whole rig guide fades to a
            // background hint instead of covering the artwork.
            val isDimmed = dimUnselected && !isSelected && !isHovered
            val baseColor = ComponentPalette.strong(w.id.raw)
            WarpLayer(
                warp = w,
                points = p,
                baseColor = baseColor,
                wireColor = when {
                    isSelected -> baseColor.brighter()
                    isHovered -> Color(0, 210, 255, 230)
                    // The faded guide was faint enough to read as absent, which made an unselected rig
                    // look like it had no deformers at all. It stays a background hint, just a legible one.
                    isDimmed -> Color(baseColor.red, baseColor.green, baseColor.blue, 90)
                    else -> baseColor
                },
                strokeWidth = when {
                    isSelected -> 2.2f
                    isHovered -> 1.8f
                    isDimmed -> 0.7f
                    else -> 1.3f
                },
                pointRadius = if (isSelected || isHovered) 3 else if (isDimmed) 1 else 2,
                isActive = isSelected || isHovered,
                isDimmed = isDimmed,
            )
        }

        // Pass 1: the rig itself - every lattice, every control point, every corner mark.
        for (layer in layers) {
            val w = layer.warp
            val p = layer.points
            fun x(i: Int) = viewport.x(p[i * 2]).toInt()
            fun y(i: Int) = viewport.yFromWorld(p[i * 2 + 1]).toInt()
            g.color = layer.wireColor
            g.stroke = BasicStroke(layer.strokeWidth)
            for (r in 0..w.rows) for (c in 0..w.columns) {
                val i = r * (w.columns + 1) + c
                if (c < w.columns) g.drawLine(x(i), y(i), x(i + 1), y(i + 1))
                if (r < w.rows) g.drawLine(x(i), y(i), x(i + w.columns + 1), y(i + w.columns + 1))
                g.fillOval(x(i) - layer.pointRadius, y(i) - layer.pointRadius, layer.pointRadius * 2, layer.pointRadius * 2)
            }

            // The deformer's own corner mark, drawn here — in the same pass, off the same points, under
            // the same colour and the same dimming as the lattice above. That is the whole of what makes
            // it part of the deformer rather than a layer sitting on top of it: it has no lifecycle of its
            // own to fall out of step, so it cannot outlive the deformer it belongs to or stay bright
            // while the deformer it belongs to fades.
            corners[w.id.raw]?.let {
                RigCanvasSupport.paintDeformerCorner(g, it, layer.accent(), layer.isDimmed)
            }
        }

        // Pass 2: all of the text, after all of the rig. Drawn inside the loop above - which is where it
        // used to be - a label is covered by the NEXT deformer's lattice, so a deformer's own name could
        // sit under the very lines it names and a rig with labels switched on would still read as
        // unlabelled. Text is a tier above the geometry, not a part of it.
        for (layer in layers) {
            if (layer.isDimmed) continue
            val w = layer.warp
            val p = layer.points
            fun x(i: Int) = viewport.x(p[i * 2]).toInt()
            fun y(i: Int) = viewport.yFromWorld(p[i * 2 + 1]).toInt()

            g.color = layer.wireColor
            if (pointIndices) {
                for (r in 0..w.rows) for (c in 0..w.columns) {
                    val i = r * (w.columns + 1) + c
                    g.drawString(i.toString(), x(i) + 3, y(i) - 3)
                }
            }
            if (labels) {
                val label = "${w.name} [${w.id.raw}] ${w.columns}×${w.rows}"
                val left = x(0).coerceAtLeast(0)
                val baseline = y(0).coerceAtLeast(16)
                g.color = Color(20, 20, 24, 220)
                g.fillRect(left, baseline - 14, g.fontMetrics.stringWidth(label) + 6, 17)
                g.color = layer.wireColor
                g.drawString(label, left + 3, baseline)
            }
        }
    }

    /**
     * One deformer's resolved appearance for a paint pass: its geometry, the colour every part of it is
     * drawn in, and the flags that decide whether the text above it is drawn at all.
     *
     * Derived once and reused by both passes, so that the label cannot end up under a different colour or
     * a different dimming than the lattice it belongs to - the two would then be able to disagree about
     * which deformer is selected.
     */
    private class WarpLayer(
        val warp: Deformer.Warp,
        val points: FloatArray,
        val baseColor: Color,
        val wireColor: Color,
        val strokeWidth: Float,
        val pointRadius: Int,
        /** Selected or hovered - the deformer currently being worked on. */
        val isActive: Boolean,
        val isDimmed: Boolean,
    ) {
        /** The colour of the corner mark: brighter while the deformer is the one being worked on. */
        fun accent(): Color = if (isActive) baseColor.brighter() else baseColor
    }

    fun paintDeformPaths(
        g: Graphics2D,
        model: PuppetModel,
        geometry: DeformedGeometry?,
        viewport: CanvasViewport,
        pathIds: Set<String>,
        labels: Boolean = false,
        pointIndices: Boolean = false,
        showWidth: Boolean = false,
        showHardness: Boolean = false,
        showRadius: Boolean = false,
        selectedPathId: String? = null,
        selectedPathIds: Set<String> = emptySet(),
        hoveredPathId: String? = null,
        hoveredPathIds: Set<String> = emptySet(),
        dimUnselected: Boolean = false,
    ): List<String> {
        if (pathIds.isEmpty()) return emptyList()
        val allPaths = model.deformPaths
        val targets = if (pathIds.contains("*")) {
            allPaths
        } else {
            allPaths.filter { path ->
                path.id in pathIds ||
                    (pathIds.contains("L2") && path.editLevel == 2) ||
                    (pathIds.contains("L3") && path.editLevel == 3) ||
                    (pathIds.contains("level:2") && path.editLevel == 2) ||
                    (pathIds.contains("level:3") && path.editLevel == 3)
            }
        }
        if (targets.isEmpty()) return emptyList()

        val renderedPathIds = mutableListOf<String>()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        for (path in targets) {
            val drawable = model.drawables.firstOrNull { it.id == path.drawableId } ?: continue
            val mesh = drawable.mesh ?: continue
            val positions = geometry?.worldPositions?.get(path.drawableId) ?: mesh.positions
            val points = try {
                DeformPathTools.positions(path, positions)
            } catch (e: Exception) {
                continue
            }
            if (points.size < 2) continue
            renderedPathIds.add(path.id)

            val isSelected = selectedPathIds.contains(path.id) || (selectedPathId != null && path.id == selectedPathId)
            val isHovered = hoveredPathIds.contains(path.id) || (hoveredPathId != null && path.id == hoveredPathId && !isSelected)
            val isDimmed = dimUnselected && !isSelected && !isHovered

            val baseColor = ComponentPalette.strong("path_${path.id}")
            val curveColor = when {
                isSelected -> Color(0, 230, 118)
                isHovered -> Color(0, 220, 255)
                isDimmed -> Color(baseColor.red, baseColor.green, baseColor.blue, 45)
                else -> baseColor
            }
            val strokeWidth = when {
                isSelected -> 2.8f
                isHovered -> 2.2f
                isDimmed -> 0.8f
                else -> 1.8f
            }

            val screenPoints = points.map { (wx, wy) ->
                viewport.x(wx).toFloat() to viewport.yFromWorld(wy).toFloat()
            }

            val curvePoints = DeformPathTools.curve(screenPoints, path.points.map { it.corner }, path.closed)
            if (curvePoints.size >= 2) {
                val curvePath = Path2D.Float()
                curvePath.moveTo(curvePoints[0].first, curvePoints[0].second)
                for (i in 1 until curvePoints.size) {
                    curvePath.lineTo(curvePoints[i].first, curvePoints[i].second)
                }
                if (path.closed) curvePath.closePath()

                g.color = if (isDimmed) Color(20, 20, 24, 25) else Color(20, 20, 24, 180)
                g.stroke = BasicStroke(strokeWidth + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(curvePath)

                g.color = curveColor
                g.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(curvePath)
            }

            val localBounds = RigGeometryTools.bounds(mesh.positions)
            val localExtent = maxOf(localBounds[2], localBounds[3]).coerceAtLeast(1e-6f)
            val worldBounds = RigGeometryTools.bounds(positions)
            val worldExtent = maxOf(worldBounds[2], worldBounds[3]).coerceAtLeast(1e-6f)
            val localToWorldScale = if (geometry?.worldPositions?.containsKey(path.drawableId) == true) {
                worldExtent / localExtent
            } else {
                1f
            }

            val drawWidth = (showWidth || showRadius || isSelected || isHovered) && path.width > 0f && !isDimmed
            val drawHardness = (showHardness || showRadius || isSelected || isHovered) && path.width > 0f && path.hardness > 0f && !isDimmed

            if (drawWidth || drawHardness) {
                val worldWidth = path.width * localToWorldScale
                val outerRadiusPx = (worldWidth * viewport.scale).toFloat()
                val innerRadiusPx = outerRadiusPx * path.hardness.coerceIn(0f, 1f)
                for (pt in screenPoints) {
                    if (drawHardness && innerRadiusPx > 1f) {
                        g.color = Color(33, 150, 243, 35)
                        g.fillOval((pt.first - innerRadiusPx).toInt(), (pt.second - innerRadiusPx).toInt(),
                            (innerRadiusPx * 2).toInt(), (innerRadiusPx * 2).toInt())
                        g.color = Color(33, 150, 243, 160)
                        g.stroke = BasicStroke(1.2f)
                        g.drawOval((pt.first - innerRadiusPx).toInt(), (pt.second - innerRadiusPx).toInt(),
                            (innerRadiusPx * 2).toInt(), (innerRadiusPx * 2).toInt())
                    }
                    if (drawWidth && outerRadiusPx > 1f) {
                        g.color = Color(244, 67, 54, 180)
                        g.stroke = BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(4f, 4f), 0f)
                        g.drawOval((pt.first - outerRadiusPx).toInt(), (pt.second - outerRadiusPx).toInt(),
                            (outerRadiusPx * 2).toInt(), (outerRadiusPx * 2).toInt())
                    }
                }
            }

            for (i in screenPoints.indices) {
                val pt = screenPoints[i]
                val isCorner = path.points.getOrNull(i)?.corner == true

                if (isCorner) {
                    val d = if (isSelected || isHovered) 6f else if (isDimmed) 3f else 4.5f
                    val diamond = Path2D.Float().apply {
                        moveTo(pt.first, pt.second - d)
                        lineTo(pt.first + d, pt.second)
                        lineTo(pt.first, pt.second + d)
                        lineTo(pt.first - d, pt.second)
                        closePath()
                    }
                    g.color = if (isDimmed) Color(180, 150, 50, 60) else Color(255, 202, 40)
                    g.fill(diamond)
                    g.color = if (isDimmed) Color(20, 20, 24, 40) else Color(20, 20, 24, 220)
                    g.stroke = BasicStroke(1.2f)
                    g.draw(diamond)
                } else {
                    val r = if (isSelected || isHovered) 5 else if (isDimmed) 2 else 4
                    g.color = if (isDimmed) Color(curveColor.red, curveColor.green, curveColor.blue, 50) else curveColor
                    g.fillOval((pt.first - r).toInt(), (pt.second - r).toInt(), r * 2, r * 2)
                    g.color = if (isDimmed) Color(20, 20, 24, 40) else Color.WHITE
                    g.stroke = BasicStroke(1.2f)
                    g.drawOval((pt.first - r).toInt(), (pt.second - r).toInt(), r * 2, r * 2)
                }

                if (pointIndices && (!isDimmed || isSelected || isHovered)) {
                    val idx = i.toString()
                    val ix = (pt.first + 6).toInt()
                    val iy = (pt.second - 4).toInt()
                    val w = g.fontMetrics.stringWidth(idx) + 4
                    g.color = Color(20, 20, 24, 200)
                    g.fillRoundRect(ix - 2, iy - 11, w, 14, 4, 4)
                    g.color = Color.WHITE
                    g.drawString(idx, ix, iy)
                }
            }
        }
        return renderedPathIds
    }
}
