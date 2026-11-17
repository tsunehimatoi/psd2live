package example

import io.github.psd2live.algorithms.binding.*
import io.github.psd2live.format.model.*
import io.github.psd2live.format.compile.FrameSpec
import io.github.psd2live.format.compile.PoseGeometry
import io.github.psd2live.format.compile.render.IrColors
import io.github.psd2live.format.compile.render.SoftwareRasterizer
import kotlin.math.*
import kotlin.test.*

class MouthLayeredCoordinatesTest {
    private fun mouth(): Pair<MouthRig, Pair<FloatArray, FloatArray>> {
        val n = MouthThreeLayerModel.DEFAULT.stations
        val upper = FloatArray(n * 2)
        val lower = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            upper[i * 2] = 100f * t - 50f
            upper[i * 2 + 1] = -10f * sin(PI.toFloat() * t)
            lower[i * 2] = -50f * cos(PI.toFloat() * t)
            lower[i * 2 + 1] = 25f * sin(PI.toFloat() * t)
        }
        fun at(curve: FloatArray, x: Float): Float {
            val i = (0 until n - 1).firstOrNull { x <= curve[(it + 1) * 2] } ?: n - 2
            val t = ((x - curve[i * 2]) / (curve[(i + 1) * 2] - curve[i * 2])).coerceIn(0f, 1f)
            return curve[i * 2 + 1] * (1f - t) + curve[(i + 1) * 2 + 1] * t
        }
        val xs = ((0 until n).map { upper[it * 2] } + (0 until n).map { lower[it * 2] }).distinct().sorted().toFloatArray()
        val frame = LayeredMouthFrame(true, 0f, 0f, 1f, 0f, 100f, upper, lower,
            upper.map { it / 100f }.toFloatArray(), lower.map { it / 100f }.toFloatArray(), .01f,
            xs, xs.map { at(upper, it) }.toFloatArray(), xs.map { at(lower, it) }.toFloatArray(),
            null, null, null, 0f, 0f, 0f)
        val fit = MouthFit(-50f, 0f, 1f, 0f, 100f,
            List(MouthFit.SAMPLES) { at(upper, 100f * MouthFit.u(it) - 50f) / 100f },
            List(MouthFit.SAMPLES) { at(lower, 100f * MouthFit.u(it) - 50f) / 100f }, frame)
        return MouthRig(fit, MouthOptions(exactClose = true), .01f) to (upper to lower)
    }

    @Test fun openingAndStrokesUseTheSameArcStationAtTheirBoundary() {
        val (rig, curves) = mouth()
        for (open in listOf(0f, .25f, .5f, .8f)) for (form in listOf(-1f, 0f, 1f)) {
            val pose = rig.pose(form, open)
            for ((curve, role) in listOf(curves.first to MouthRig.Follow.UPPER_LINE, curves.second to MouthRig.Follow.LOWER_LINE)) {
                for (i in listOf(10, 20, 32, 44, 54)) {
                    val (u, v) = rig.fit.toMouth(curve[i * 2], curve[i * 2 + 1])
                    val stroke = rig.moveAs(role, u, v, pose)
                    val inside = rig.moveAs(MouthRig.Follow.OPENING, u, v, pose)
                    assertEquals(stroke.first, inside.first, 1e-4f, "$role x at $i open=$open form=$form")
                    assertEquals(stroke.second, inside.second, 1e-4f, "$role y at $i open=$open form=$form")
                }
            }
        }
    }

    @Test fun bothStrokesShareTheirCornerAndKeepTheDrawnPose() {
        val (rig, curves) = mouth()
        for (open in listOf(0f, .25f, .5f, .99999f, 1f)) {
            val pose = rig.pose(0f, open)
            for (u in listOf(0f, 1f)) {
                val upper = rig.moveAs(MouthRig.Follow.UPPER_LINE, u, 0f, pose)
                val lower = rig.moveAs(MouthRig.Follow.LOWER_LINE, u, 0f, pose)
                assertEquals(upper.first, lower.first, 1e-5f)
                assertEquals(upper.second, lower.second, 1e-5f)
                assertTrue(upper.first.isFinite() && upper.second.isFinite())
            }
        }
        for ((curve, role) in listOf(curves.first to MouthRig.Follow.UPPER_LINE, curves.second to MouthRig.Follow.LOWER_LINE)) {
            for (i in curve.indices step 2) {
                val (u, v) = rig.fit.toMouth(curve[i], curve[i + 1])
                val delta = rig.moveAs(role, u, v, rig.pose(0f, 1f))
                assertEquals(0f, delta.first, 1e-5f)
                assertEquals(0f, delta.second, 1e-5f)
            }
        }
    }

    @Test fun closedOpeningColumnsCollapseAndRenderNoSolidAperture() {
        val (rig, _) = mouth()
        val columns = 33; val rows = 5
        val points = FloatArray(columns * rows * 2)
        for (x in 0 until columns) for (y in 0 until rows) {
            val u = x.toFloat() / (columns - 1)
            val top = MouthFit.sample(rig.fit.upper, u)
            val bottom = MouthFit.sample(rig.fit.lower, u)
            val p = rig.fit.toCanvas(u, top + (bottom - top) * y / (rows - 1))
            val i = (x * rows + y) * 2
            points[i] = p.first; points[i + 1] = p.second
        }
        fun posed(open: Float): FloatArray = FloatArray(points.size).also { output ->
            val pose = rig.pose(0f, open)
            for (i in points.indices step 2) {
                val (u, v) = rig.fit.toMouth(points[i], points[i + 1])
                val delta = rig.moveAs(MouthRig.Follow.OPENING, u, v, pose)
                val p = rig.fit.toCanvas(u + delta.first, v + delta.second)
                output[i] = p.first; output[i + 1] = p.second
            }
        }
        val closed = posed(0f)
        for (x in 0 until columns) for (y in 1 until rows) {
            val base = x * rows * 2
            val i = (x * rows + y) * 2
            assertEquals(closed[base], closed[i], 1e-4f, "Closed column $x must have one X")
            assertEquals(closed[base + 1], closed[i + 1], 1e-4f, "Closed column $x must have one Y")
        }
        val drawn = posed(1f)
        points.indices.forEach { assertEquals(points[it], drawn[it], 1e-5f) }
        val indices = ArrayList<Int>()
        for (x in 0 until columns - 1) for (y in 0 until rows - 1) {
            val a = x * rows + y; val b = (x + 1) * rows + y
            indices += listOf(a, b, a + 1, a + 1, b, b + 1)
        }
        for (open in listOf(.05f, .25f, .5f, .75f, 1f)) {
            val vertices = posed(open)
            for (i in indices.indices step 3) {
                val a = indices[i] * 2; val b = indices[i + 1] * 2; val c = indices[i + 2] * 2
                val area = (vertices[b] - vertices[a]) * (vertices[c + 1] - vertices[a + 1]) -
                    (vertices[b + 1] - vertices[a + 1]) * (vertices[c] - vertices[a])
                assertTrue(area >= -1e-3f, "Opening triangle $i must not invert at $open: $area")
            }
        }
        val mesh = Mesh("opening", "Opening", null, geometry = MeshGeometry(Floats.wrap(points),
            Floats.wrap(FloatArray(points.size) { .5f }), Ints.wrap(indices.toIntArray())), offsets = null, page = 0)
        val model = RigIR(Canvas(120f, 100f), emptyList(), meshes = listOf(mesh),
            rootChildren = listOf(ChildRef.MeshRef(mesh.id)), textures = Textures(pages = listOf(TexturePage(1, 1))))
        val colors = IrColors(model)
        fun pixels(vertices: FloatArray): Int = SoftwareRasterizer(model) { intArrayOf(-1) }.render(
            PoseGeometry(mapOf(mesh.id to vertices), mapOf(mesh.id to 1f), mapOf(mesh.id to 0f)),
            colors.at(emptyMap()), FrameSpec(-60f, -40f, 120f, 100f, 240, 200), parts = colors.parts(emptyMap()))
            .argb.count { it ushr 24 > 127 }
        assertEquals(0, pixels(closed), "A closed aperture must not leave triangular mask islands")
        assertTrue(pixels(posed(1f)) > 500)
    }

    @Test fun openingMarginOutsideCornersCollapsesWhenClosedAndNarrowed() {
        val (rig, _) = mouth()
        val marginPts = floatArrayOf(-56f, 0f, -54f, -4f, -54f, 4f, 56f, 0f, 54f, -4f, 54f, 4f)
        for (form in listOf(-1f, 0f, 1f)) {
            val pose = rig.pose(form, 0f)
            val leftCorner = rig.moveAs(MouthRig.Follow.UPPER_LINE, 0f, 0f, pose)
            val rightCorner = rig.moveAs(MouthRig.Follow.UPPER_LINE, 1f, 0f, pose)
            val leftP = rig.fit.toCanvas(leftCorner.first, leftCorner.second)
            val rightP = rig.fit.toCanvas(1f + rightCorner.first, rightCorner.second)
            for (i in marginPts.indices step 2) {
                val (u, v) = rig.fit.toMouth(marginPts[i], marginPts[i + 1])
                val d = rig.moveAs(MouthRig.Follow.OPENING, u, v, pose)
                val p = rig.fit.toCanvas(u + d.first, v + d.second)
                assertTrue(p.first >= leftP.first - 1e-3f && p.first <= rightP.first + 1e-3f,
                    "Closed opening margin must stay within corners at form=$form: ${p.first} vs [${leftP.first}, ${rightP.first}]")
            }
        }
    }

    @Test fun closedDrawnLipSkinStartsAtSeamAndPartsWhenOpening() {
        val n = MouthThreeLayerModel.DEFAULT.stations
        val upper = FloatArray(n * 2)
        val lower = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            upper[i * 2] = 100f * t - 50f
            upper[i * 2 + 1] = -12f * sin(PI.toFloat() * t)
            lower[i * 2] = 100f * t - 50f
            lower[i * 2 + 1] = 20f * sin(PI.toFloat() * t)
        }
        val xs = FloatArray(n) { upper[it * 2] }
        val lipBounds = Bounds(-60f, -35f, 60f, 40f)
        val frame = LayeredMouthFrame(false, 0f, 0f, 1f, 0f, 100f, upper, lower,
            upper.map { it / 100f }.toFloatArray(), lower.map { it / 100f }.toFloatArray(), 0f,
            xs, FloatArray(n) { upper[it * 2 + 1] }, FloatArray(n) { lower[it * 2 + 1] },
            lipBounds, lipBounds, null, 0f, 0f, 0f)
        val fit = MouthFit(-50f, 0f, 1f, 0f, 100f,
            List(MouthFit.SAMPLES) { -0.12f * sin(PI.toFloat() * MouthFit.u(it)) },
            List(MouthFit.SAMPLES) { 0.20f * sin(PI.toFloat() * MouthFit.u(it)) }, frame)
        val rig = MouthRig(fit, MouthOptions(exactClose = true))
        val frameBounds = Bounds(-60f, -40f, 120f, 80f)
        // Closed-drawn upper lip cover extends down to the seam (y = 0) across the middle.
        val closedUpperCover = floatArrayOf(-20f, -20f, 0f, -20f, 20f, -20f, -20f, 0f, 0f, 0f, 20f, 0f)
        val grid = rig.grid(closedUpperCover, frameBounds, org.umamo.runtime.model.ParameterId("ParamMouthForm"),
            org.umamo.runtime.model.ParameterId("ParamMouthOpenY"), MouthRig.Follow.UPPER_LIP)
        val shutCell = grid.cells.single {
            grid.axes[0].keys[it.coordinate[0]] == 0f && grid.axes[1].keys[it.coordinate[1]] == 0f
        }
        for (d in shutCell.form.positionDeltas) assertEquals(0f, d, 1e-5f)
        val openCell = grid.cells.single {
            grid.axes[0].keys[it.coordinate[0]] == 0f && grid.axes[1].keys[it.coordinate[1]] == 1f
        }
        val midBottomNewY = closedUpperCover[9] + openCell.form.positionDeltas[9] * frameBounds.height
        assertTrue(midBottomNewY < -8f, "Closed-drawn upper lip cover must lift clear of cavity at open=1: y=$midBottomNewY")
    }
}
