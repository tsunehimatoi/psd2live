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
        for (w in model.deformers.filterIsInstance<Deformer.Warp>().filter { it.id.raw in ids }) {
            val p = pointsById[w.id.raw] ?: continue
            val isSelected = selectedDeformerId != null && w.id.raw == selectedDeformerId
            val isHovered = hoveredDeformerId != null && w.id.raw == hoveredDeformerId && !isSelected
            val isDimmed = dimUnselected && selectedDeformerId != null && !isSelected && !isHovered
            val baseColor = ComponentPalette.strong(w.id.raw)
            val strokeWidth = when {
                isSelected -> 2.2f
                isHovered -> 1.8f
                isDimmed -> 0.7f
                else -> 1.3f
            }
            val wireColor = when {
                isSelected -> baseColor.brighter()
                isHovered -> Color(0, 210, 255, 230)
                isDimmed -> Color(baseColor.red, baseColor.green, baseColor.blue, 55)
                else -> baseColor
            }

            g.color = wireColor
            g.stroke = BasicStroke(strokeWidth)
            fun x(i: Int) = viewport.x(p[i * 2]).toInt()
            fun y(i: Int) = viewport.yFromWorld(p[i * 2 + 1]).toInt()
            for (r in 0..w.rows) for (c in 0..w.columns) {
                val i = r * (w.columns + 1) + c
                if (c < w.columns) g.drawLine(x(i), y(i), x(i + 1), y(i + 1))
                if (r < w.rows) g.drawLine(x(i), y(i), x(i + w.columns + 1), y(i + w.columns + 1))
                val radius = if (isSelected || isHovered) 3 else if (isDimmed) 1 else 2
                g.fillOval(x(i) - radius, y(i) - radius, radius * 2, radius * 2)
                if (pointIndices && (!isDimmed || isSelected || isHovered)) g.drawString(i.toString(), x(i) + 3, y(i) - 3)
            }
            if (labels && (!isDimmed || isSelected || isHovered)) {
                val label = "${w.name} [${w.id.raw}] ${w.columns}×${w.rows}"
                val x = x(0).coerceAtLeast(0)
                val y = y(0).coerceAtLeast(16)
                val color = g.color
                g.color = if (isDimmed) Color(20, 20, 24, 100) else Color(20, 20, 24, 220)
                g.fillRect(x, y - 14, g.fontMetrics.stringWidth(label) + 6, 17)
                g.color = color
                g.drawString(label, x + 3, y)
            }
        }
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
        hasSelection: Boolean = false,
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
            val isDimmed = dimUnselected && (hasSelection || selectedPathId != null || selectedPathIds.isNotEmpty()) && !isSelected && !isHovered

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
