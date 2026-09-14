package io.github.psd2live.agent

import io.github.psd2live.core.DeformPathTools
import io.github.psd2live.core.RigGeometryTools
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import java.awt.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.max

/** Agent helper functions for Deform Path inspection, point binding, preview, and command construction. */
internal object AgentPathTools {

    fun parsePoints(rawPoints: JsonArray, vertices: FloatArray, indices: IntArray): List<DeformPathPoint> {
        require(rawPoints.size in 2..128) { "Paths require 2..128 points" }
        return rawPoints.map { elem ->
            when (elem) {
                is JsonObject -> {
                    if ("wa" in elem) {
                        DeformPathPoint(
                            a = elem.getValue("a").jsonPrimitive.int,
                            b = elem.getValue("b").jsonPrimitive.int,
                            c = elem.getValue("c").jsonPrimitive.int,
                            wa = elem.getValue("wa").jsonPrimitive.float,
                            wb = elem.getValue("wb").jsonPrimitive.float,
                            wc = elem.getValue("wc").jsonPrimitive.float,
                            corner = elem["corner"]?.jsonPrimitive?.boolean ?: false,
                        )
                    } else {
                        val x = elem.getValue("x").jsonPrimitive.float
                        val y = elem.getValue("y").jsonPrimitive.float
                        val corner = elem["corner"]?.jsonPrimitive?.boolean ?: false
                        DeformPathTools.bind(vertices, indices, x, y, corner)
                    }
                }
                is JsonArray -> {
                    require(elem.size >= 2) { "Point coordinate must have at least [x, y]" }
                    val x = elem[0].jsonPrimitive.float
                    val y = elem[1].jsonPrimitive.float
                    val corner = elem.getOrNull(2)?.jsonPrimitive?.boolean ?: false
                    DeformPathTools.bind(vertices, indices, x, y, corner)
                }
                else -> error("Point must be [x, y] or {x, y}")
            }
        }
    }

    fun parseMovedPoints(rawPoints: JsonArray): List<Pair<Float, Float>> {
        return rawPoints.map { elem ->
            when (elem) {
                is JsonArray -> {
                    require(elem.size >= 2) { "Moved point must have [x, y]" }
                    elem[0].jsonPrimitive.float to elem[1].jsonPrimitive.float
                }
                is JsonObject -> {
                    elem.getValue("x").jsonPrimitive.float to elem.getValue("y").jsonPrimitive.float
                }
                else -> error("Moved point must be [x, y] or {x, y}")
            }
        }
    }

    fun inspect(model: PuppetModel, arguments: JsonObject): JsonObject {
        val targetArg = arguments["target"]?.jsonPrimitive?.contentOrNull
        val pathIdArg = (arguments["path_id"] ?: arguments["id"])?.jsonPrimitive?.contentOrNull
        val meshId = targetArg?.removePrefix("mesh:")

        val filtered = model.deformPaths.filter { path ->
            (meshId == null || path.drawableId.raw == meshId) &&
            (pathIdArg == null || path.id == pathIdArg)
        }

        return buildJsonObject {
            putJsonArray("paths") {
                filtered.forEach { path ->
                    val drawable = model.drawables.firstOrNull { it.id == path.drawableId }
                    val mesh = drawable?.mesh
                    add(buildJsonObject {
                        put("id", path.id)
                        put("target", "mesh:${path.drawableId.raw}")
                        put("level", path.editLevel)
                        put("width", path.width)
                        put("hardness", path.hardness)
                        put("closed", path.closed)
                        put("pointCount", path.points.size)
                        if (mesh != null) {
                            val positions = DeformPathTools.positions(path, mesh.positions)
                            putJsonArray("points") {
                                path.points.forEachIndexed { i, p ->
                                    val (px, py) = positions[i]
                                    add(buildJsonObject {
                                        put("x", px)
                                        put("y", py)
                                        put("corner", p.corner)
                                        put("a", p.a)
                                        put("b", p.b)
                                        put("c", p.c)
                                        put("wa", p.wa)
                                        put("wb", p.wb)
                                        put("wc", p.wc)
                                    })
                                }
                            }
                            val bounds = RigGeometryTools.bounds(mesh.positions)
                            putJsonArray("meshBounds") {
                                bounds.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    })
                }
            }
        }
    }

    fun renderDisplacementPreview(
        model: PuppetModel,
        meshId: String,
        pathId: String,
        moved: List<Pair<Float, Float>>,
        customWidth: Float? = null,
        customHardness: Float? = null,
        showWidth: Boolean = true,
        showHardness: Boolean = true,
        targetWidth: Int = 640,
        targetHeight: Int = 640,
    ): ByteArray {
        val drawable = model.drawables.singleOrNull { it.id.raw == meshId } ?: error("Mesh not found: $meshId")
        val mesh = requireNotNull(drawable.mesh) { "Drawable has no mesh" }
        val rawActive = model.deformPaths.singleOrNull { it.id == pathId } ?: error("Deform path not found: $pathId")
        val active = rawActive.copy(
            width = customWidth?.takeIf { it > 0f } ?: rawActive.width,
            hardness = customHardness?.coerceIn(0f, 1f) ?: rawActive.hardness,
        )
        val paths = model.deformPaths.map { if (it.id == pathId) active else it }

        val base = mesh.positions
        val deformed = DeformPathTools.deform(base, paths, pathId, moved)
        val origPoints = DeformPathTools.positions(active, base)
        val origCurve = DeformPathTools.curve(origPoints, active.points.map { it.corner }, active.closed)
        val movedCurve = DeformPathTools.curve(moved, active.points.map { it.corner }, active.closed)

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        fun include(x: Float, y: Float) {
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
        for (i in base.indices step 2) {
            include(base[i], base[i + 1])
            include(deformed[i], deformed[i + 1])
        }
        for ((x, y) in origCurve) include(x, y)
        for ((x, y) in movedCurve) include(x, y)
        for ((x, y) in moved) include(x, y)

        val spanX = max(1f, maxX - minX)
        val spanY = max(1f, maxY - minY)
        val padX = spanX * 0.16f
        val padY = spanY * 0.16f
        val boxL = minX - padX
        val boxT = minY - padY
        val boxW = spanX + padX * 2f
        val boxH = spanY + padY * 2f

        val scale = minOf((targetWidth - 40f) / boxW, (targetHeight - 70f) / boxH)
        val offX = (targetWidth - boxW * scale) / 2f - boxL * scale
        val offY = (targetHeight + 20f - boxH * scale) / 2f - boxT * scale
        fun sx(x: Float) = (x * scale + offX)
        fun sy(y: Float) = (y * scale + offY)

        val img = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            // Background
            g.color = Color(0x18, 0x1A, 0x1F)
            g.fillRect(0, 0, targetWidth, targetHeight)

            // Subtle Grid
            g.color = Color(0x25, 0x28, 0x30)
            val step = 28
            for (y in step until targetHeight step step) {
                for (x in step until targetWidth step step) {
                    g.fillRect(x, y, 1, 1)
                }
            }

            // 1. Base Mesh Wireframe
            g.color = Color(110, 125, 145, 65)
            g.stroke = BasicStroke(1.0f)
            for (i in mesh.indices.indices step 3) {
                val a = mesh.indices[i]; val b = mesh.indices[i + 1]; val c = mesh.indices[i + 2]
                val ax = sx(base[a * 2]).toInt(); val ay = sy(base[a * 2 + 1]).toInt()
                val bx = sx(base[b * 2]).toInt(); val by = sy(base[b * 2 + 1]).toInt()
                val cx = sx(base[c * 2]).toInt(); val cy = sy(base[c * 2 + 1]).toInt()
                g.drawLine(ax, ay, bx, by)
                g.drawLine(bx, by, cx, cy)
                g.drawLine(cx, cy, ax, ay)
            }

            // 2. Displacement vectors & vertices
            val count = base.size / 2
            var maxDisp = 0.0
            var sumDisp = 0.0
            for (i in 0 until count) {
                val bx = base[i * 2]; val by = base[i * 2 + 1]
                val dx = deformed[i * 2]; val dy = deformed[i * 2 + 1]
                val disp = hypot((dx - bx).toDouble(), (dy - by).toDouble())
                if (disp > maxDisp) maxDisp = disp
                sumDisp += disp

                if (disp > 0.4) {
                    g.color = Color(255, 152, 0, 160)
                    g.stroke = BasicStroke(1.2f)
                    g.drawLine(sx(bx).toInt(), sy(by).toInt(), sx(dx).toInt(), sy(dy).toInt())
                }
            }

            // 3. Deformed Mesh Wireframe
            g.color = Color(0, 200, 255, 190)
            g.stroke = BasicStroke(1.4f)
            for (i in mesh.indices.indices step 3) {
                val a = mesh.indices[i]; val b = mesh.indices[i + 1]; val c = mesh.indices[i + 2]
                val ax = sx(deformed[a * 2]).toInt(); val ay = sy(deformed[a * 2 + 1]).toInt()
                val bx = sx(deformed[b * 2]).toInt(); val by = sy(deformed[b * 2 + 1]).toInt()
                val cx = sx(deformed[c * 2]).toInt(); val cy = sy(deformed[c * 2 + 1]).toInt()
                g.drawLine(ax, ay, bx, by)
                g.drawLine(bx, by, cx, cy)
                g.drawLine(cx, cy, ax, ay)
            }

            // 4. Falloff Width & Hardness
            val drawWidth = showWidth && active.width > 0f
            val drawHardness = showHardness && active.width > 0f && active.hardness > 0f
            if (drawWidth || drawHardness) {
                val outerRadiusPx = active.width * scale
                val innerRadiusPx = outerRadiusPx * active.hardness.coerceIn(0f, 1f)
                for ((px, py) in moved) {
                    val cx = sx(px); val cy = sy(py)
                    if (drawHardness && innerRadiusPx > 1f) {
                        g.color = Color(33, 150, 243, 25)
                        g.fillOval((cx - innerRadiusPx).toInt(), (cy - innerRadiusPx).toInt(),
                            (innerRadiusPx * 2).toInt(), (innerRadiusPx * 2).toInt())
                        g.color = Color(33, 150, 243, 90)
                        g.stroke = BasicStroke(1f)
                        g.drawOval((cx - innerRadiusPx).toInt(), (cy - innerRadiusPx).toInt(),
                            (innerRadiusPx * 2).toInt(), (innerRadiusPx * 2).toInt())
                    }
                    if (drawWidth && outerRadiusPx > 1f) {
                        g.color = Color(244, 67, 54, 75)
                        g.stroke = BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(4f, 4f), 0f)
                        g.drawOval((cx - outerRadiusPx).toInt(), (cy - outerRadiusPx).toInt(),
                            (outerRadiusPx * 2).toInt(), (outerRadiusPx * 2).toInt())
                    }
                }
            }

            // 5. Original Path Curve
            if (origCurve.size >= 2) {
                val origPath = Path2D.Float()
                origPath.moveTo(sx(origCurve[0].first), sy(origCurve[0].second))
                for (i in 1 until origCurve.size) {
                    origPath.lineTo(sx(origCurve[i].first), sy(origCurve[i].second))
                }
                if (active.closed) origPath.closePath()
                g.color = Color(180, 190, 205, 140)
                g.stroke = BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(5f, 5f), 0f)
                g.draw(origPath)
            }

            // 6. Moved Path Curve
            if (movedCurve.size >= 2) {
                val movedPath = Path2D.Float()
                movedPath.moveTo(sx(movedCurve[0].first), sy(movedCurve[0].second))
                for (i in 1 until movedCurve.size) {
                    movedPath.lineTo(sx(movedCurve[i].first), sy(movedCurve[i].second))
                }
                if (active.closed) movedPath.closePath()
                g.color = Color(20, 20, 24, 200)
                g.stroke = BasicStroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(movedPath)
                g.color = Color(0, 230, 118)
                g.stroke = BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(movedPath)
            }

            // 7. Control Points & Connecting Vectors
            for (i in origPoints.indices) {
                val ox = sx(origPoints[i].first); val oy = sy(origPoints[i].second)
                val mx = sx(moved[i].first); val my = sy(moved[i].second)
                val isCorner = active.points.getOrNull(i)?.corner == true

                if (hypot((mx - ox).toDouble(), (my - oy).toDouble()) > 0.5) {
                    g.color = Color(255, 235, 59, 170)
                    g.stroke = BasicStroke(1.3f)
                    g.drawLine(ox.toInt(), oy.toInt(), mx.toInt(), my.toInt())
                }

                g.color = Color(160, 175, 195, 180)
                g.stroke = BasicStroke(1.2f)
                g.drawOval((ox - 3).toInt(), (oy - 3).toInt(), 6, 6)

                if (isCorner) {
                    val d = 6f
                    val diamond = Path2D.Float().apply {
                        moveTo(mx, my - d)
                        lineTo(mx + d, my)
                        lineTo(mx, my + d)
                        lineTo(mx - d, my)
                        closePath()
                    }
                    g.color = Color(255, 202, 40)
                    g.fill(diamond)
                    g.color = Color(20, 20, 24, 220)
                    g.stroke = BasicStroke(1.2f)
                    g.draw(diamond)
                } else {
                    val r = 5
                    g.color = Color(0, 230, 118)
                    g.fillOval((mx - r).toInt(), (my - r).toInt(), r * 2, r * 2)
                    g.color = Color.WHITE
                    g.stroke = BasicStroke(1.2f)
                    g.drawOval((mx - r).toInt(), (my - r).toInt(), r * 2, r * 2)
                }

                val idx = i.toString()
                val ix = (mx + 7).toInt()
                val iy = (my - 4).toInt()
                val iw = g.fontMetrics.stringWidth(idx) + 4
                g.color = Color(20, 20, 24, 210)
                g.fillRoundRect(ix - 2, iy - 11, iw, 14, 4, 4)
                g.color = Color.WHITE
                g.drawString(idx, ix, iy)
            }

            // 8. Header & Stats
            g.color = Color(20, 22, 28, 225)
            g.fillRoundRect(12, 12, targetWidth - 24, 38, 8, 8)
            g.color = Color(50, 54, 64)
            g.stroke = BasicStroke(1f)
            g.drawRoundRect(12, 12, targetWidth - 24, 38, 8, 8)

            g.font = g.font.deriveFont(Font.BOLD, 12f)
            g.color = Color(0, 230, 118)
            val title = "PATH PREVIEW: path:${active.id} on mesh:$meshId"
            g.drawString(title, 22, 28)

            g.font = g.font.deriveFont(Font.PLAIN, 11f)
            g.color = Color(190, 200, 215)
            val avg = if (count > 0) sumDisp / count else 0.0
            val stats = "Vertices: $count | Max \u0394: ${"%.2f".format(maxDisp)}px | Avg \u0394: ${"%.2f".format(avg)}px | Width: ${"%.1f".format(active.width)} | Hardness: ${"%.2f".format(active.hardness)}"
            g.drawString(stats, 22, 43)

            // Legend at bottom
            val legY = targetHeight - 16
            g.color = Color(20, 22, 28, 210)
            g.fillRoundRect(12, targetHeight - 32, targetWidth - 24, 22, 6, 6)

            g.font = g.font.deriveFont(Font.PLAIN, 10f)
            var legX = 22
            fun item(color: Color, text: String, isCircle: Boolean = false) {
                g.color = color
                if (isCircle) g.fillOval(legX, legY - 7, 7, 7) else g.fillRect(legX, legY - 7, 7, 7)
                legX += 11
                g.color = Color(210, 215, 225)
                g.drawString(text, legX, legY)
                legX += g.fontMetrics.stringWidth(text) + 14
            }
            item(Color(110, 125, 145), "Base Wire")
            item(Color(0, 200, 255), "Deformed")
            item(Color(0, 230, 118), "Path", isCircle = true)
            item(Color(255, 152, 0), "Vector \u0394")
            if (drawWidth) item(Color(244, 67, 54), "Width")
            if (drawHardness) item(Color(33, 150, 243), "Hardness")
        } finally {
            g.dispose()
        }

        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", baos)
        return baos.toByteArray()
    }

    fun preview(model: PuppetModel, arguments: JsonObject): JsonObject {
        val target = arguments.getValue("target").jsonPrimitive.content
        require(target.startsWith("mesh:")) { "Preview target must be mesh:<id>" }
        val meshId = target.removePrefix("mesh:")
        val pathId = (arguments["path_id"] ?: arguments["id"])?.jsonPrimitive?.content ?: error("Missing path_id")
        val moved = parseMovedPoints(arguments.getValue("moved_points").jsonArray)

        val customWidth = arguments["width"]?.jsonPrimitive?.floatOrNull
        val customHardness = arguments["hardness"]?.jsonPrimitive?.floatOrNull
        val showWidth = arguments["show_width"]?.jsonPrimitive?.booleanOrNull ?: true
        val showHardness = arguments["show_hardness"]?.jsonPrimitive?.booleanOrNull ?: true

        val drawable = model.drawables.singleOrNull { it.id.raw == meshId } ?: error("Mesh not found: $meshId")
        val mesh = requireNotNull(drawable.mesh) { "Drawable has no mesh" }
        val rawActive = model.deformPaths.singleOrNull { it.id == pathId } ?: error("Deform path not found: $pathId")
        require(moved.size == rawActive.points.size) { "moved_points count (${moved.size}) must match path points (${rawActive.points.size})" }

        val active = rawActive.copy(
            width = customWidth?.takeIf { it > 0f } ?: rawActive.width,
            hardness = customHardness?.coerceIn(0f, 1f) ?: rawActive.hardness,
        )
        val paths = model.deformPaths.map { if (it.id == pathId) active else it }

        val base = mesh.positions
        val deformed = DeformPathTools.deform(base, paths, pathId, moved)

        val count = base.size / 2
        var maxDisp = 0.0
        var sumDisp = 0.0
        for (i in 0 until count) {
            val dx = (deformed[i * 2] - base[i * 2]).toDouble()
            val dy = (deformed[i * 2 + 1] - base[i * 2 + 1]).toDouble()
            val d = hypot(dx, dy)
            if (d > maxDisp) maxDisp = d
            sumDisp += d
        }

        val baseBounds = RigGeometryTools.bounds(base)
        val deformedBounds = RigGeometryTools.bounds(deformed)

        val render = arguments["render"]?.jsonPrimitive?.booleanOrNull ?: true
        val pngBytes = if (render) {
            try {
                renderDisplacementPreview(
                    model = model,
                    meshId = meshId,
                    pathId = pathId,
                    moved = moved,
                    customWidth = customWidth,
                    customHardness = customHardness,
                    showWidth = showWidth,
                    showHardness = showHardness,
                )
            } catch (e: Exception) {
                null
            }
        } else null
        val base64 = pngBytes?.let { java.util.Base64.getEncoder().encodeToString(it) }

        return buildJsonObject {
            put("vertexCount", count)
            put("maxDisplacement", maxDisp)
            put("avgDisplacement", if (count > 0) sumDisp / count else 0.0)
            put("width", active.width)
            put("hardness", active.hardness)
            put("showWidth", showWidth)
            put("showHardness", showHardness)
            putJsonArray("baseBounds") { baseBounds.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("deformedBounds") { deformedBounds.forEach { add(JsonPrimitive(it)) } }
            base64?.let { put("previewImage", it) }
        }
    }

    fun createPutCommand(model: PuppetModel, arguments: JsonObject): Pair<String, JsonObject> {
        val target = arguments.getValue("target").jsonPrimitive.content
        require(target.startsWith("mesh:")) { "Target must be mesh:<id>" }
        val meshId = target.removePrefix("mesh:")
        val drawable = model.drawables.singleOrNull { it.id.raw == meshId } ?: error("Mesh not found: $meshId")
        val mesh = requireNotNull(drawable.mesh) { "Drawable has no mesh" }

        val id = (arguments["id"] ?: arguments["path_id"])?.jsonPrimitive?.contentOrNull
            ?: "path_${UUID.randomUUID().toString().take(8)}"
        val rawPoints = arguments.getValue("points").jsonArray
        val boundPoints = parsePoints(rawPoints, mesh.positions, mesh.indices)

        val extent = RigGeometryTools.bounds(mesh.positions).let { max(it[2], it[3]) }
        val width = arguments["width"]?.jsonPrimitive?.floatOrNull ?: (extent * 0.12f)
        val hardness = arguments["hardness"]?.jsonPrimitive?.floatOrNull ?: 0.5f
        val closed = arguments["closed"]?.jsonPrimitive?.booleanOrNull ?: false
        val level = arguments["level"]?.jsonPrimitive?.intOrNull ?: 2

        val command = buildJsonObject {
            put("op", "path_put")
            put("id", id)
            put("target", target)
            put("width", width)
            put("hardness", hardness)
            put("closed", closed)
            put("level", level)
            putJsonArray("points") {
                boundPoints.forEach { p ->
                    add(buildJsonObject {
                        put("a", p.a)
                        put("b", p.b)
                        put("c", p.c)
                        put("wa", p.wa)
                        put("wb", p.wb)
                        put("wc", p.wc)
                        put("corner", p.corner)
                    })
                }
            }
        }
        return id to command
    }

    fun createDeleteCommand(arguments: JsonObject): Pair<String, JsonObject> {
        val pathId = (arguments["path_id"] ?: arguments["id"])?.jsonPrimitive?.content ?: error("Missing path_id")
        val command = buildJsonObject {
            put("op", "path_delete")
            put("id", pathId)
        }
        return pathId to command
    }

    fun createDeformCommand(arguments: JsonObject): JsonObject {
        val target = arguments.getValue("target").jsonPrimitive.content
        require(target.startsWith("mesh:")) { "Target must be mesh:<id>" }
        val pathId = (arguments["path_id"] ?: arguments["id"])?.jsonPrimitive?.content ?: error("Missing path_id")
        val key = arguments.getValue("key").jsonObject
        val movedPoints = arguments.getValue("moved_points").jsonArray

        return buildJsonObject {
            put("op", "path_deform")
            put("target", target)
            put("path_id", pathId)
            put("key", key)
            put("moved_points", movedPoints)
        }
    }
}

