package io.github.psd2live.core

import org.umamo.runtime.model.DrawableMesh
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Exact pixel-square coverage, including faint alpha; strips bound memory on large source rasters. */
internal object RasterMeshCoverage {
    private const val ROWS = 32
    private const val AREA_EPSILON = 1e-5

    fun uncovered(width: Int, height: Int, rgba: ByteArray, meshes: List<DrawableMesh>): Float? {
        if (width <= 0 || height <= 0) return null
        require(width.toLong() * height * 4 == rgba.size.toLong())
        val areas = DoubleArray(width * min(ROWS, height))
        val fragments = HashMap<Int, Area>()
        val clip = PixelClip()
        var painted = 0L
        var missing = 0L
        for (top in 0 until height step ROWS) {
            val bottom = min(height, top + ROWS)
            areas.fill(0.0)
            fragments.clear()
            for (mesh in meshes) {
                val p = mesh.positions
                for (t in mesh.indices.indices step 3) {
                    val a = mesh.indices[t] * 2; val b = mesh.indices[t + 1] * 2; val c = mesh.indices[t + 2] * 2
                    val ax = p[a].toDouble(); val ay = p[a + 1].toDouble()
                    val bx = p[b].toDouble(); val by = p[b + 1].toDouble()
                    val cx = p[c].toDouble(); val cy = p[c + 1].toDouble()
                    val y0 = max(top, floor(min(ay, min(by, cy))).toInt())
                    val y1 = min(bottom - 1, ceil(max(ay, max(by, cy))).toInt() - 1)
                    if (y0 > y1) continue
                    val x0 = max(0, floor(min(ax, min(bx, cx))).toInt())
                    val x1 = min(width - 1, ceil(max(ax, max(bx, cx))).toInt() - 1)
                    for (y in y0..y1) for (x in x0..x1) {
                        if (rgba[(y * width + x) * 4 + 3].toInt() == 0) continue
                        val cell = (y - top) * width + x
                        if (areas[cell] >= 1.0 - AREA_EPSILON) continue
                        val area = clip.area(ax - x, ay - y, bx - x, by - y, cx - x, cy - y)
                        if (area >= 1.0 - AREA_EPSILON) {
                            areas[cell] = 1.0
                            fragments.remove(cell)
                        } else if (area > 0.0) {
                            val piece = clip.polygon()
                            val previous = fragments[cell]
                            if (previous == null) {
                                fragments[cell] = piece
                                areas[cell] = area
                            } else {
                                previous.add(piece)
                                areas[cell] = area(previous)
                            }
                        }
                    }
                }
            }
            for (y in top until bottom) for (x in 0 until width) {
                if (rgba[(y * width + x) * 4 + 3].toInt() == 0) continue
                painted++
                if (areas[(y - top) * width + x] < 1.0 - AREA_EPSILON) missing++
            }
        }
        return if (painted == 0L) null else missing.toFloat() / painted
    }

    private fun area(polygon: Area): Double {
        val path = polygon.getPathIterator(null)
        val point = DoubleArray(6)
        var firstX = 0.0; var firstY = 0.0
        var x = 0.0; var y = 0.0; var area = 0.0
        while (!path.isDone) {
            when (path.currentSegment(point)) {
                PathIterator.SEG_MOVETO -> { firstX = point[0]; firstY = point[1]; x = firstX; y = firstY }
                PathIterator.SEG_LINETO -> { area += x * point[1] - point[0] * y; x = point[0]; y = point[1] }
                PathIterator.SEG_CLOSE -> area += x * firstY - firstX * y
            }
            path.next()
        }
        return abs(area) * 0.5
    }

    /** Clip a triangle to [0,1]² without allocating a polygon per pixel. */
    private class PixelClip {
        private var input = DoubleArray(16)
        private var output = DoubleArray(16)
        private var count = 0

        fun polygon(): Area {
            val path = Path2D.Double()
            for (i in 0 until count) {
                if (i == 0) path.moveTo(input[0], input[1]) else path.lineTo(input[i * 2], input[i * 2 + 1])
            }
            path.closePath()
            return Area(path)
        }

        fun area(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
            input[0] = ax; input[1] = ay; input[2] = bx; input[3] = by; input[4] = cx; input[5] = cy
            count = 3
            edge(0, 0.0, true); edge(0, 1.0, false); edge(1, 0.0, true); edge(1, 1.0, false)
            var area = 0.0
            for (i in 0 until count) {
                val next = (i + 1) % count
                area += input[i * 2] * input[next * 2 + 1] - input[next * 2] * input[i * 2 + 1]
            }
            return abs(area) * 0.5
        }

        private fun edge(axis: Int, bound: Double, greater: Boolean) {
            if (count == 0) return
            var previous = count - 1
            var pv = input[previous * 2 + axis] - bound
            var pi = if (greater) pv >= 0 else pv <= 0
            var written = 0
            for (current in 0 until count) {
                val cv = input[current * 2 + axis] - bound
                val ci = if (greater) cv >= 0 else cv <= 0
                if (ci != pi) {
                    val t = pv / (pv - cv)
                    output[written * 2] = input[previous * 2] + t * (input[current * 2] - input[previous * 2])
                    output[written * 2 + 1] = input[previous * 2 + 1] + t * (input[current * 2 + 1] - input[previous * 2 + 1])
                    written++
                }
                if (ci) {
                    output[written * 2] = input[current * 2]; output[written * 2 + 1] = input[current * 2 + 1]
                    written++
                }
                previous = current; pv = cv; pi = ci
            }
            count = written
            val swap = input; input = output; output = swap
        }
    }
}
