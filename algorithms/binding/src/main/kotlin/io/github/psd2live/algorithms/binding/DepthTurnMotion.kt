package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.fit.FaceLandmarks
import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.fit.FeatureTurn
import io.github.psd2live.core.fit.HairTurn
import org.umamo.runtime.model.*
import kotlin.math.PI
import kotlin.math.sin

/** The styled face field solved back through the head, face and nested feature lattices. */
object DepthTurnMotion {
    private val ANGLE_X = ParameterId("ParamAngleX")
    private val ANGLE_Y = ParameterId("ParamAngleY")
    private val FACE = DeformerId("DeformFaceNinePose")
    private val CONTOUR = DeformerId("DeformFaceContour")
    private val FRONT_FOLLOW = DeformerId("DeformHairFrontFollow")
    private val BACK_FOLLOW = DeformerId("DeformHairBackFollow")

    data class Container(val pivotX: Float, val pivotY: Float, val strength: Float,
                         val shellTurn: Float, val shellCrown: Float, val shellTilt: Float) {
        fun point(head: Bounds, u: Float, v: Float, yawAngle: Float, pitchAngle: Float): Pair<Float, Float> {
            val boundedStrength = strength.coerceIn(0f, 2f)
            val canvasX = head.left + u * head.width
            val canvasY = head.top + v * head.height
            val yaw = yawAngle / 45f * boundedStrength
            val pitch = pitchAngle / 30f * boundedStrength
            val crownArch = sin(PI * u).toFloat().coerceAtLeast(0f)
            val shellX = yaw * head.width * (shellTurn + crownArch * shellCrown) / 100f
            val shellY = -pitch * head.height * shellTilt / 100f
            return (canvasX - pivotX + shellX) to (canvasY - pivotY + shellY)
        }
    }

    fun deformers(
        deformers: List<Deformer>, turn: FaceTurn, head: Bounds, faceFrame: Bounds,
        container: Container, parentFrames: Map<DeformerId, Bounds>, underFace: Set<DeformerId>,
        fittedEyes: Set<String>, fittedBrows: Set<String>, marks: FaceLandmarks, checkpoint: () -> Unit = {},
    ): List<Deformer> {
        val face = deformers.firstOrNull { it.id == FACE } as? Deformer.Warp ?: return deformers
        val contour = deformers.firstOrNull { it.id == CONTOUR } as? Deformer.Warp
        val pivotX = container.pivotX; val pivotY = container.pivotY
        val xKeys = FaceTurn.YAW_KEYS
        val yKeys = FaceTurn.PITCH_KEYS
        val axes = listOf(KeyformAxis(ANGLE_X, xKeys), KeyformAxis(ANGLE_Y, yKeys))
        // The head container's lattice at a pose, as it is built.
        val headCols = 4; val headRows = 5
        fun headLattice(yaw: Float, pitch: Float) = FloatArray((headCols + 1) * (headRows + 1) * 2).also { out ->
            var i = 0
            for (r in 0..headRows) for (c in 0..headCols) {
                val (x, y) = container.point(head, c.toFloat() / headCols, r.toFloat() / headRows, yaw, pitch)
                out[i++] = x; out[i++] = y
            }
        }
        val restFace = face.geometryGrid?.let { org.umamo.runtime.eval.warpControlPointsAt(it) { 0f } } ?: return deformers
        val faceCount = (face.columns + 1) * (face.rows + 1)
        val contourCols = 16; val contourRows = 16
        val faceCells = ArrayList<KeyformCell<WarpLatticeForm>>()
        val contourCells = ArrayList<KeyformCell<WarpLatticeForm>>()
        // The parts' own warps - the eyes, their irises, the brows, the ears, the nose, the mouth - placed by the style's
        // moves for each ([FeatureTurn]): a rule per pose giving where a rest point goes.
        val byId = deformers.associateBy { it.id }
        val turns = FeatureTurn
        class Feature(val warp: Deformer.Warp, val parentFrame: Bounds, val rest: FloatArray, val inside: DeformerId?,
            val rule: (FaceTurn.Pose) -> ((Float, Float) -> Pair<Float, Float>)?)
        val features = buildList {
            fun feature(id: String, inside: DeformerId?, rule: (FaceTurn.Pose) -> ((Float, Float) -> Pair<Float, Float>)?) {
                val warp = byId[DeformerId(id)] as? Deformer.Warp ?: return
                val frame = warp.parent?.let(parentFrames::get) ?: return
                val rest = warp.geometryGrid?.let { org.umamo.runtime.eval.warpControlPointsAt(it) { 0f } } ?: return
                add(Feature(warp, frame, rest, inside, rule))
            }
            for (name in listOf("L", "R")) {
                val token = name
                val sign = if (name == "L") 1f else -1f
                if (name in fittedEyes) FeatureTurn.Eye.of(marks, name)?.let { eye ->
                    feature("DeformEyeShape$token", null) { pose -> turns.eye(eye, turn, pose).eye }
                    feature("DeformIrisPreserve$token", DeformerId("DeformEyeShape$token")) { pose -> turns.eye(eye, turn, pose).iris }
                }
                if (name in fittedBrows) feature("DeformBrowShape$token", null) { pose -> turns.brow(marks, name, sign, turn, pose) }
                if (marks["ear.$name.root"] != null) feature("DeformEarOcclusion$token", null) { pose -> turns.ear(sign, turn, pose) }
            }
            feature("DeformNoseShapeBoth", null) { pose -> pose.field }
            feature("DeformMouthShapeBoth", null) { pose -> turns.mouth(marks, turn, pose) }
        }
        val featureCells = features.associate { it.warp.id to ArrayList<KeyformCell<WarpLatticeForm>>() }
        for ((xi, yaw) in xKeys.withIndex()) for ((yi, pitch) in yKeys.withIndex()) {
            checkpoint()
            val pose = turn.pose(yaw, pitch)
            val field = if (yaw == 0f && pitch == 0f) { x: Float, y: Float -> x to y } else pose.field
            val hl = headLattice(yaw, pitch)
            // The face's lattice: each point in the head's space where the head container takes it to the field's place.
            val fl = FloatArray(faceCount * 2)
            for (k in 0 until faceCount) {
                val u = restFace[k * 2]; val v = restFace[k * 2 + 1]
                val (tx, ty) = field(head.left + u * head.width, head.top + v * head.height)
                val (iu, iv) = invertLattice(hl, headCols, headRows, tx - pivotX, ty - pivotY, u, v)
                fl[k * 2] = iu; fl[k * 2 + 1] = iv
            }
            faceCells += KeyformCell(intArrayOf(xi, yi), WarpLatticeForm(fl))
            // A rig point's place, at this pose, in a frame under the face's warp - through the head's lattice and the
            // face's - the warps between them resting as they are drawn.
            fun underFace(x: Float, y: Float, frame: Bounds): Pair<Float, Float> {
                val (hu, hv) = invertLattice(hl, headCols, headRows, x - pivotX, y - pivotY, (x - head.left) / head.width, (y - head.top) / head.height)
                val (fs, ft) = invertLattice(fl, face.columns, face.rows, hu, hv, (x - faceFrame.left) / faceFrame.width, (y - faceFrame.top) / faceFrame.height)
                return (faceFrame.left + fs * faceFrame.width - frame.left) / frame.width to (faceFrame.top + ft * faceFrame.height - frame.top) / frame.height
            }
            val placed = HashMap<DeformerId, FloatArray>()
            for (f in features.sortedBy { if (it.inside == null) 0 else 1 }) {
                val rule = (if (yaw == 0f && pitch == 0f) null else f.rule(pose)) ?: { x: Float, y: Float -> x to y }
                val count = f.rest.size / 2
                val out = FloatArray(f.rest.size)
                val outer = f.inside?.let { id -> features.firstOrNull { it.warp.id == id } }
                for (k in 0 until count) {
                    val x = f.parentFrame.left + f.rest[k * 2] * f.parentFrame.width
                    val y = f.parentFrame.top + f.rest[k * 2 + 1] * f.parentFrame.height
                    val (tx, ty) = rule(x, y)
                    if (outer == null) {
                        val (a, b) = underFace(tx, ty, f.parentFrame); out[k * 2] = a; out[k * 2 + 1] = b
                    } else {
                        // Inside another feature's warp: through that warp's lattice at this pose too.
                        val (ga, gb) = underFace(tx, ty, outer.parentFrame)
                        val lattice = placed[outer.warp.id] ?: outer.rest
                        val (a, b) = invertLattice(lattice, outer.warp.columns, outer.warp.rows, ga, gb, f.rest[k * 2], f.rest[k * 2 + 1])
                        out[k * 2] = a; out[k * 2 + 1] = b
                    }
                }
                placed[f.warp.id] = out
                featureCells.getValue(f.warp.id) += KeyformCell(intArrayOf(xi, yi), WarpLatticeForm(out))
            }
            // The skin's lattice in the face's space: through the face's lattice, then the head's, to the field's place.
            val cl = FloatArray((contourCols + 1) * (contourRows + 1) * 2)
            var j = 0
            for (r in 0..contourRows) for (c in 0..contourCols) {
                val s0 = c.toFloat() / contourCols; val t0 = r.toFloat() / contourRows
                val (tx, ty) = field(faceFrame.left + s0 * faceFrame.width, faceFrame.top + t0 * faceFrame.height)
                val hu0 = (faceFrame.left + s0 * faceFrame.width - head.left) / head.width
                val hv0 = (faceFrame.top + t0 * faceFrame.height - head.top) / head.height
                val (hu, hv) = invertLattice(hl, headCols, headRows, tx - pivotX, ty - pivotY, hu0, hv0)
                val (fs, ft) = invertLattice(fl, face.columns, face.rows, hu, hv, s0, t0)
                cl[j++] = fs; cl[j++] = ft
            }
            contourCells += KeyformCell(intArrayOf(xi, yi), WarpLatticeForm(cl))
        }
        // Everything else under the face rides the field alone: its own turn keys are held at rest, or it would turn twice.
        return deformers.map { d ->
            when {
                d.id == FACE -> face.copy(geometryGrid = KeyformGrid(axes, faceCells))
                d.id == CONTOUR -> contour?.copy(rows = contourRows, columns = contourCols, geometryGrid = KeyformGrid(axes, contourCells)) ?: d
                d.id in featureCells -> (d as Deformer.Warp).copy(geometryGrid = KeyformGrid(axes, featureCells.getValue(d.id)), channelGrids = d.channelGrids)
                d.id in underFace || d.id == FRONT_FOLLOW || d.id == BACK_FOLLOW -> when (d) {
                    is Deformer.Warp -> d.copy(geometryGrid = d.geometryGrid?.let(::heldTurn))
                    is Deformer.Rotation -> d.copy(geometryGrid = d.geometryGrid?.let(::heldTurn))
                    else -> d
                }
                else -> d
            }
        }
    }

    /** [grid] with every key on Angle X and Angle Y holding the form at their rest key. */
    private fun <T> heldTurn(grid: KeyformGrid<T>): KeyformGrid<T> {
        val turned = grid.axes.withIndex().filter { it.value.parameterId == ANGLE_X || it.value.parameterId == ANGLE_Y }
        if (turned.isEmpty()) return grid
        val rest = turned.map { (i, a) -> i to a.keys.withIndex().minBy { kotlin.math.abs(it.value) }.index }
        val byIndex = grid.cellsByLinearIndex
        return KeyformGrid(grid.axes, grid.cells.map { cell ->
            val at = cell.coordinate.copyOf(); for ((i, k) in rest) at[i] = k
            KeyformCell(cell.coordinate, (byIndex[grid.linearIndexOf(at)] ?: cell).form)
        })
    }

    /**
     * Where in a warp's own space ([cols] x [rows] cells over 0..1, its [lattice] of control points) the warp puts a point
     * at ([x], [y]): Newton's method on its bilinear map from ([u0], [v0]), outside 0..1 by its border cells.
     */
    private fun invertLattice(lattice: FloatArray, cols: Int, rows: Int, x: Float, y: Float, u0: Float, v0: Float): Pair<Float, Float> {
        fun at(u: Float, v: Float): Pair<Float, Float> {
            val c = (u * cols).coerceIn(0f, cols - 1e-4f).toInt(); val r = (v * rows).coerceIn(0f, rows - 1e-4f).toInt()
            val fu = u * cols - c; val fv = v * rows - r
            fun p(cc: Int, rr: Int, k: Int) = lattice[(rr * (cols + 1) + cc) * 2 + k]
            fun lerp(k: Int) = (p(c, r, k) * (1 - fu) + p(c + 1, r, k) * fu) * (1 - fv) + (p(c, r + 1, k) * (1 - fu) + p(c + 1, r + 1, k) * fu) * fv
            return lerp(0) to lerp(1)
        }
        var u = u0; var v = v0
        val h = 1e-3f
        repeat(12) {
            val (fx, fy) = at(u, v)
            val ex = fx - x; val ey = fy - y
            if (ex * ex + ey * ey < 1e-10f) return u to v
            val (ax, ay) = at(u + h, v); val (bx, by) = at(u, v + h)
            val j11 = (ax - fx) / h; val j21 = (ay - fy) / h; val j12 = (bx - fx) / h; val j22 = (by - fy) / h
            val det = j11 * j22 - j12 * j21
            if (kotlin.math.abs(det) < 1e-12f) return u to v
            u -= (j22 * ex - j12 * ey) / det; v -= (-j21 * ex + j11 * ey) / det
        }
        return u to v
    }

    fun styledShapes(tag: String, positions: FloatArray, frame: Bounds, head: Bounds, container: Container,
                     turn: FaceTurn, checkpoint: () -> Unit = {}): List<BlendShapeBinding<MeshForm>> {
        fun form(yaw: Float, pitch: Float): MeshForm {
            checkpoint()
            val pose = turn.pose(yaw, pitch)
            val moved = when (tag) {
                "FRONT_HAIR", "BACK_HAIR" -> HairTurn.place(positions, tag == "FRONT_HAIR", turn, pose)
                else -> FeatureTurn.whole("headwear", turn, pose).let { f ->
                    FloatArray(positions.size).also { out -> for (i in 0 until positions.size / 2) f(positions[i * 2], positions[i * 2 + 1]).let { out[i * 2] = it.first; out[i * 2 + 1] = it.second } }
                }
            }
            val out = FloatArray(positions.size)
            for (i in 0 until positions.size / 2) {
                val x = positions[i * 2]; val y = positions[i * 2 + 1]
                val u = (x - head.left) / head.width; val v = (y - head.top) / head.height
                val (hx, hy) = container.point(head, u, v, yaw, pitch)
                val (rx, ry) = container.point(head, u, v, 0f, 0f)
                out[i * 2] = (moved[i * 2] - x - (hx - rx)) / frame.width.coerceAtLeast(1e-4f)
                out[i * 2 + 1] = (moved[i * 2 + 1] - y - (hy - ry)) / frame.height.coerceAtLeast(1e-4f)
            }
            return MeshForm(out, opacity = Float.NaN)
        }
        val yaws = FaceTurn.YAW_KEYS; val pitches = FaceTurn.PITCH_KEYS
        return listOf(
            BlendShapeBinding(ANGLE_X, yaws, yaws.indexOfFirst { it == 0f }, yaws.map { if (it == 0f) null else form(it, 0f) }),
            BlendShapeBinding(ANGLE_Y, pitches, pitches.indexOfFirst { it == 0f }, pitches.map { if (it == 0f) null else form(0f, it) }),
        )
    }

    fun shell(tag: String, role: String?): HeadShell? {
        val shells = HeadShell
        if (tag == "FACE_DETAIL" && role == "SHADOW") return shells.SHADE
        if (tag == "NECK" && role == "SHADOW") return shells.RIGID.copy(scale = 1f)
        return when (tag) {
            "FACE", "FACE_DETAIL", "IRIDES", "EYEBROW", "EYEWHITE", "EYELASH",
            "EYE_CLOSE", "MOUTH", "MOUTH_OPEN", "MOUTH_CLOSE", "TOOTH_T", "TOOTH_B",
            "TONGUE" -> shells.SKIN
            "NOSE" -> shells.NOSE
            "EYEWEAR" -> shells.RIGID
            "FRONT_HAIR" -> if (role == "SIDE") shells.SIDE_HAIR else shells.FRONT_HAIR
            "BACK_HAIR" -> if (role == "SIDE") shells.SIDE_HAIR else shells.BACK_HAIR
            "HEADWEAR" -> if (role == "RIBBON" || role == "PENDANT" || role == "ANIMAL") shells.FRONT_HAIR else shells.RIGID
            "EARS", "EARWEAR" -> shells.EAR
            else -> null
        }
    }
    fun sideLock(drawable: Drawable, faceOrder: Float, farRight: Boolean): Drawable {
        if (drawable.drawOrder <= faceOrder || FormChannel.DRAW_ORDER in drawable.channelGrids.gridsByChannel) return drawable
        val far = if (farRight) 1f else -1f
        val behind = (faceOrder - 1f).coerceAtLeast(0f)
        val keys = if (farRight) floatArrayOf(-45f, 0f, 40f, 45f) else floatArrayOf(-45f, -40f, 0f, 45f)
        val grid = KeyformGrid<ChannelValue>(listOf(KeyformAxis(ANGLE_X, keys)), keys.mapIndexed { i, angle ->
            KeyformCell(intArrayOf(i), ChannelValue.Scalar(if (angle * far >= 45f) behind else drawable.drawOrder))
        })
        return drawable.copy(channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel + (FormChannel.DRAW_ORDER to grid)))
    }
    fun withFaceLean(deformers: List<Deformer>, leanDown: Float, leanUp: Float): List<Deformer> =
        LegacyBodyMotion.withFaceLean(deformers, leanDown, leanUp)
}
