package io.github.psd2live.core

import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CWarpDeformerBezierExtension
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.type.CArrayList

/** Direction of a Bezier tangent handle relative to its anchor point. */
enum class BezierHandleDir { LEFT, RIGHT, TOP, BOTTOM }

data class BezierAnchor(val row: Int, val col: Int, var x: Float, var y: Float)

data class BezierHandle(val row: Int, val col: Int, val dir: BezierHandleDir, var x: Float, var y: Float)

/**
 * State and evaluator for Live2D Cubism-style Level 2 Bezier Deformer editing.
 *
 * Divides the deformer into [bezierRows] x [bezierCols] cubic Bezier patches.
 * Each patch is evaluated using bicubic tensor-product Bernstein polynomials to
 * determine the underlying conversion lattice points.
 */
class BezierDeformerState(
    val bezierRows: Int = 2,
    val bezierCols: Int = 2,
) {
    val anchors = mutableMapOf<Pair<Int, Int>, BezierAnchor>()
    val handles = mutableMapOf<Triple<Int, Int, BezierHandleDir>, BezierHandle>()

    /**
     * Initializes the Bezier control lattice from an existing conversion grid.
     */
    fun initFromLattice(points: FloatArray, latticeRows: Int, latticeCols: Int) {
        anchors.clear()
        handles.clear()

        fun latticePoint(r: Int, c: Int): Pair<Float, Float> {
            val clampedR = r.coerceIn(0, latticeRows)
            val clampedC = c.coerceIn(0, latticeCols)
            val idx = (clampedR * (latticeCols + 1) + clampedC) * 2
            return points[idx] to points[idx + 1]
        }

        // 1. Initialize anchors by sampling lattice points at normalized grid ratios
        for (br in 0..bezierRows) {
            val targetR = (br.toFloat() / bezierRows * latticeRows).toInt()
            for (bc in 0..bezierCols) {
                val targetC = (bc.toFloat() / bezierCols * latticeCols).toInt()
                val pt = latticePoint(targetR, targetC)
                anchors[br to bc] = BezierAnchor(br, bc, pt.first, pt.second)
            }
        }

        // 2. Initialize tangent handles along patch edges
        for (br in 0..bezierRows) {
            for (bc in 0..bezierCols) {
                val p0 = anchors[br to bc] ?: continue

                // Horizontal tangents (Left & Right)
                if (bc > 0) {
                    val pPrev = anchors[br to (bc - 1)]!!
                    val hx = p0.x - (p0.x - pPrev.x) / 3f
                    val hy = p0.y - (p0.y - pPrev.y) / 3f
                    handles[Triple(br, bc, BezierHandleDir.LEFT)] = BezierHandle(br, bc, BezierHandleDir.LEFT, hx, hy)
                }
                if (bc < bezierCols) {
                    val pNext = anchors[br to (bc + 1)]!!
                    val hx = p0.x + (pNext.x - p0.x) / 3f
                    val hy = p0.y + (pNext.y - p0.y) / 3f
                    handles[Triple(br, bc, BezierHandleDir.RIGHT)] = BezierHandle(br, bc, BezierHandleDir.RIGHT, hx, hy)
                }

                // Vertical tangents (Top & Bottom)
                if (br > 0) {
                    val pPrev = anchors[(br - 1) to bc]!!
                    val hx = p0.x - (p0.x - pPrev.x) / 3f
                    val hy = p0.y - (p0.y - pPrev.y) / 3f
                    handles[Triple(br, bc, BezierHandleDir.TOP)] = BezierHandle(br, bc, BezierHandleDir.TOP, hx, hy)
                }
                if (br < bezierRows) {
                    val pNext = anchors[(br + 1) to bc]!!
                    val hx = p0.x + (pNext.x - p0.x) / 3f
                    val hy = p0.y + (pNext.y - p0.y) / 3f
                    handles[Triple(br, bc, BezierHandleDir.BOTTOM)] = BezierHandle(br, bc, BezierHandleDir.BOTTOM, hx, hy)
                }
            }
        }
    }

    /**
     * Translates an anchor and its associated handles by (dx, dy).
     */
    fun moveAnchor(br: Int, bc: Int, dx: Float, dy: Float) {
        val a = anchors[br to bc] ?: return
        a.x += dx
        a.y += dy
        for (dir in BezierHandleDir.entries) {
            handles[Triple(br, bc, dir)]?.let {
                it.x += dx
                it.y += dy
            }
        }
    }

    /**
     * Translates a specific handle. If [smooth] is true, the opposing handle reflects direction.
     */
    fun moveHandle(br: Int, bc: Int, dir: BezierHandleDir, newX: Float, newY: Float, smooth: Boolean = true) {
        val h = handles[Triple(br, bc, dir)] ?: return
        val a = anchors[br to bc] ?: return
        h.x = newX
        h.y = newY

        if (smooth) {
            val oppositeDir = when (dir) {
                BezierHandleDir.LEFT -> BezierHandleDir.RIGHT
                BezierHandleDir.RIGHT -> BezierHandleDir.LEFT
                BezierHandleDir.TOP -> BezierHandleDir.BOTTOM
                BezierHandleDir.BOTTOM -> BezierHandleDir.TOP
            }
            val opp = handles[Triple(br, bc, oppositeDir)]
            if (opp != null) {
                val dx = newX - a.x
                val dy = newY - a.y
                val dist = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-4f)
                val oppDx = opp.x - a.x
                val oppDy = opp.y - a.y
                val oppDist = kotlin.math.hypot(oppDx, oppDy).coerceAtLeast(1e-4f)
                opp.x = a.x - (dx / dist) * oppDist
                opp.y = a.y - (dy / dist) * oppDist
            }
        }
    }

    /**
     * Evaluates a bicubic Bezier patch (patchR, patchC) at local normalized coordinates (s, t) in [0, 1].
     */
    fun evaluatePatch(patchR: Int, patchC: Int, s: Float, t: Float): Pair<Float, Float> {
        val r0 = patchR
        val r1 = patchR + 1
        val c0 = patchC
        val c1 = patchC + 1

        val a00 = anchors[r0 to c0] ?: return 0f to 0f
        val a01 = anchors[r0 to c1] ?: return 0f to 0f
        val a10 = anchors[r1 to c0] ?: return 0f to 0f
        val a11 = anchors[r1 to c1] ?: return 0f to 0f

        val h00R = handles[Triple(r0, c0, BezierHandleDir.RIGHT)]?.let { it.x to it.y } ?: (a00.x + (a01.x - a00.x) / 3f to a00.y + (a01.y - a00.y) / 3f)
        val h01L = handles[Triple(r0, c1, BezierHandleDir.LEFT)]?.let { it.x to it.y } ?: (a01.x - (a01.x - a00.x) / 3f to a01.y - (a01.y - a00.y) / 3f)
        val h10R = handles[Triple(r1, c0, BezierHandleDir.RIGHT)]?.let { it.x to it.y } ?: (a10.x + (a11.x - a10.x) / 3f to a10.y + (a11.y - a10.y) / 3f)
        val h11L = handles[Triple(r1, c1, BezierHandleDir.LEFT)]?.let { it.x to it.y } ?: (a11.x - (a11.x - a10.x) / 3f to a11.y - (a11.y - a10.y) / 3f)

        val h00B = handles[Triple(r0, c0, BezierHandleDir.BOTTOM)]?.let { it.x to it.y } ?: (a00.x + (a10.x - a00.x) / 3f to a00.y + (a10.y - a00.y) / 3f)
        val h10T = handles[Triple(r1, c0, BezierHandleDir.TOP)]?.let { it.x to it.y } ?: (a10.x - (a10.x - a00.x) / 3f to a10.y - (a10.y - a00.y) / 3f)
        val h01B = handles[Triple(r0, c1, BezierHandleDir.BOTTOM)]?.let { it.x to it.y } ?: (a01.x + (a11.x - a01.x) / 3f to a01.y + (a11.y - a01.y) / 3f)
        val h11T = handles[Triple(r1, c1, BezierHandleDir.TOP)]?.let { it.x to it.y } ?: (a11.x - (a11.x - a01.x) / 3f to a11.y - (a11.y - a01.y) / 3f)

        // 16 control points of the bicubic patch: P[row][col]
        val p = Array(4) { Array(4) { 0f to 0f } }

        p[0][0] = a00.x to a00.y
        p[0][1] = h00R
        p[0][2] = h01L
        p[0][3] = a01.x to a01.y

        p[3][0] = a10.x to a10.y
        p[3][1] = h10R
        p[3][2] = h11L
        p[3][3] = a11.x to a11.y

        p[1][0] = h00B
        p[2][0] = h10T
        p[1][3] = h01B
        p[2][3] = h11T

        // Internal 4 control points by Coons bilinear blending
        p[1][1] = (p[0][1].first + p[1][0].first - p[0][0].first) to (p[0][1].second + p[1][0].second - p[0][0].second)
        p[1][2] = (p[0][2].first + p[1][3].first - p[0][3].first) to (p[0][2].second + p[1][3].second - p[0][3].second)
        p[2][1] = (p[3][1].first + p[2][0].first - p[3][0].first) to (p[3][1].second + p[2][0].second - p[3][0].second)
        p[2][2] = (p[3][2].first + p[2][3].first - p[3][3].first) to (p[3][2].second + p[2][3].second - p[3][3].second)

        val bs = bernstein(s.coerceIn(0f, 1f))
        val bt = bernstein(t.coerceIn(0f, 1f))

        var outX = 0f
        var outY = 0f
        for (i in 0..3) {
            val wi = bt[i]
            for (j in 0..3) {
                val w = wi * bs[j]
                outX += w * p[i][j].first
                outY += w * p[i][j].second
            }
        }
        return outX to outY
    }

    /**
     * Evaluates all underlying lattice control points from the current Bezier state.
     */
    fun evaluateLattice(latticeRows: Int, latticeCols: Int): FloatArray {
        val totalPoints = (latticeRows + 1) * (latticeCols + 1)
        val result = FloatArray(totalPoints * 2)

        for (r in 0..latticeRows) {
            val v = (r.toFloat() / latticeRows).coerceIn(0f, 1f)
            val patchR = (v * bezierRows).toInt().coerceIn(0, bezierRows - 1)
            val t = (v * bezierRows - patchR).coerceIn(0f, 1f)

            for (c in 0..latticeCols) {
                val u = (c.toFloat() / latticeCols).coerceIn(0f, 1f)
                val patchC = (u * bezierCols).toInt().coerceIn(0, bezierCols - 1)
                val s = (u * bezierCols - patchC).coerceIn(0f, 1f)

                val (px, py) = evaluatePatch(patchR, patchC, s, t)
                val idx = (r * (latticeCols + 1) + c) * 2
                result[idx] = px
                result[idx + 1] = py
            }
        }
        return result
    }

    companion object {
        private fun bernstein(u: Float): FloatArray {
            val s = 1f - u
            return floatArrayOf(
                s * s * s,
                3f * s * s * u,
                3f * s * u * u,
                u * u * u
            )
        }
    }
}

/** Cubism stores the editable Bezier divisions separately from the baked deformation lattice. */
internal object BezierWarp {
    fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val s = 1f - t
        return s * s * s * p0 + 3f * s * s * t * p1 + 3f * s * t * t * p2 + t * t * t * p3
    }

    fun configureEditor(model: CModelSource) {
        val sources = (model.deformerSourceSet as? CDeformerSourceSet)?._sources as? Iterable<*> ?: return
        for (warp in sources.filterIsInstance<CWarpDeformerSource>()) {
            val extensions = CArrayList<Any?>()
            (warp._extensions as? Iterable<*>)?.filterNot { it is CWarpDeformerBezierExtension }?.forEach { extensions.add(it) }
            extensions.add(CWarpDeformerBezierExtension().apply {
                guid = org.umamo.format.cmo3.model.identity.Guid("CExtensionGuid").apply { uuid = java.util.UUID.randomUUID().toString() }
                _owner = warp
                editLevel = 2
                bezierCol = warp.col.coerceIn(1, 3)
                bezierRow = warp.row.coerceIn(1, 3)
            })
            warp._extensions = extensions
        }
    }
}
