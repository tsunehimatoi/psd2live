package io.github.psd2live.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A joint along the spine of a Warp Deformer.
 */
data class SkeletonWarpJoint(
    val index: Int,
    val row: Int,
    var x: Float,
    var y: Float,
    val restX: Float,
    val restY: Float,
)

/**
 * A bone segment connecting two adjacent joints.
 */
data class SkeletonWarpBone(
    val index: Int,
    val parentJointIndex: Int,
    val childJointIndex: Int,
    val restLength: Float,
    val restAngleRad: Float,
    var currentAngleRad: Float,
)

/**
 * Interactive skeleton armature state and evaluator for Live2D Warp Deformers.
 *
 * Extracts a spine of joints and bone segments along the deformer lattice rows,
 * allowing artists to pose the entire Warp Deformer like a skeletal armature with
 * Forward Kinematics (FK) and smooth joint bisector blending.
 */
class SkeletonWarpState(
    val latticeRows: Int,
    val latticeCols: Int,
) {
    init {
        require(latticeRows > 0 && latticeCols > 0) { "Lattice dimensions must be positive" }
    }

    val joints = mutableListOf<SkeletonWarpJoint>()
    val bones = mutableListOf<SkeletonWarpBone>()
    private var restPoints = FloatArray(0)
    private val restRowCenters = mutableListOf<Pair<Float, Float>>()
    private val restOffsets = mutableListOf<Pair<Float, Float>>()
    private val jointRowIndices = mutableListOf<Int>()

    /**
     * Initializes joints, bones, and rest-pose offsets from an existing Warp Deformer lattice.
     */
    fun initFromLattice(points: FloatArray, rows: Int, cols: Int) {
        require(rows == latticeRows && cols == latticeCols)
        require(points.size == (rows + 1) * (cols + 1) * 2 && points.all(Float::isFinite))

        joints.clear()
        bones.clear()
        restRowCenters.clear()
        restOffsets.clear()
        jointRowIndices.clear()
        restPoints = points.copyOf()

        val ptsPerRow = cols + 1

        fun getPt(r: Int, c: Int): Pair<Float, Float> {
            val idx = (r * ptsPerRow + c) * 2
            return points[idx] to points[idx + 1]
        }

        // Calculate center for each row
        for (r in 0..rows) {
            var sumX = 0f
            var sumY = 0f
            for (c in 0..cols) {
                val (px, py) = getPt(r, c)
                sumX += px
                sumY += py
            }
            val cx = sumX / (cols + 1).toFloat()
            val cy = sumY / (cols + 1).toFloat()
            restRowCenters += cx to cy

            for (c in 0..cols) {
                val (px, py) = getPt(r, c)
                restOffsets += (px - cx) to (py - cy)
            }
        }

        // Determine joint rows:
        // For skeleton-baked warps (even rows, e.g. 2N), joints are at 0, 2, 4, ..., 2N.
        // For generic warps, pick even rows or all rows.
        if (rows % 2 == 0 && rows >= 2) {
            for (r in 0..rows step 2) {
                jointRowIndices += r
            }
        } else {
            for (r in 0..rows) {
                jointRowIndices += r
            }
        }

        val numJoints = jointRowIndices.size
        for (i in 0 until numJoints) {
            val r = jointRowIndices[i]
            val (cx, cy) = restRowCenters[r]
            joints += SkeletonWarpJoint(
                index = i,
                row = r,
                x = cx,
                y = cy,
                restX = cx,
                restY = cy,
            )
        }

        val numBones = numJoints - 1
        for (i in 0 until numBones) {
            val j0 = joints[i]
            val j1 = joints[i + 1]
            val dx = j1.restX - j0.restX
            val dy = j1.restY - j0.restY
            val len = hypot(dx, dy).coerceAtLeast(1e-4f)
            val angle = atan2(dy, dx)
            bones += SkeletonWarpBone(
                index = i,
                parentJointIndex = i,
                childJointIndex = i + 1,
                restLength = len,
                restAngleRad = angle,
                currentAngleRad = angle,
            )
        }
    }

    /**
     * Poses a joint by dragging it to a new local coordinate.
     *
     * If [jointIndex] == 0 (root), translates the entire skeleton armature.
     * If [jointIndex] > 0, rotates bone [jointIndex - 1] around its parent joint towards [targetX], [targetY],
     * and rotates all descendant bones and joints along with it via Forward Kinematics (FK).
     */
    fun dragJoint(jointIndex: Int, targetX: Float, targetY: Float) {
        if (jointIndex !in joints.indices) return

        if (jointIndex == 0) {
            val dx = targetX - joints[0].x
            val dy = targetY - joints[0].y
            for (j in joints) {
                j.x += dx
                j.y += dy
            }
            return
        }

        val parentIdx = jointIndex - 1
        val parentJoint = joints[parentIdx]
        val vx = targetX - parentJoint.x
        val vy = targetY - parentJoint.y
        if (hypot(vx, vy) < 1e-4f) return

        val newAngle = atan2(vy, vx)
        val deltaAngle = normalizeAngle(newAngle - bones[parentIdx].currentAngleRad)
        bones[parentIdx].currentAngleRad = newAngle

        // Rotate all descendant bones
        for (b in jointIndex until bones.size) {
            bones[b].currentAngleRad = normalizeAngle(bones[b].currentAngleRad + deltaAngle)
        }

        // Recompute positions of all descendant joints
        val pivotX = parentJoint.x
        val pivotY = parentJoint.y
        val cosD = cos(deltaAngle)
        val sinD = sin(deltaAngle)

        for (j in jointIndex until joints.size) {
            val rx = joints[j].x - pivotX
            val ry = joints[j].y - pivotY
            joints[j].x = pivotX + rx * cosD - ry * sinD
            joints[j].y = pivotY + rx * sinD + ry * cosD
        }
    }

    /**
     * Resets the skeleton armature back to the rest pose.
     */
    fun resetPose() {
        for (j in joints) {
            j.x = j.restX
            j.y = j.restY
        }
        for (b in bones) {
            b.currentAngleRad = b.restAngleRad
        }
    }

    /**
     * Evaluates the deformed lattice control points from the current joint & bone poses.
     * Returns a [FloatArray] of size (latticeRows + 1) * (latticeCols + 1) * 2.
     */
    fun evaluateLattice(rows: Int = latticeRows, cols: Int = latticeCols): FloatArray {
        require(rows == latticeRows && cols == latticeCols)
        val ptsPerRow = cols + 1
        val totalPoints = (rows + 1) * ptsPerRow
        val result = FloatArray(totalPoints * 2)

        val numBones = bones.size
        if (numBones == 0 || joints.isEmpty()) {
            return restPoints.copyOf()
        }

        // 1. Calculate delta world rotation for each bone
        val boneDeltaRot = FloatArray(numBones) { i ->
            normalizeAngle(bones[i].currentAngleRad - bones[i].restAngleRad)
        }

        // 2. Calculate bisector rotation for each joint
        val jointRot = FloatArray(joints.size) { i ->
            when {
                i == 0 -> boneDeltaRot[0]
                i == joints.size - 1 -> boneDeltaRot[numBones - 1]
                else -> {
                    val prevRot = boneDeltaRot[i - 1]
                    val nextRot = boneDeltaRot[i]
                    normalizeAngle(prevRot + 0.5f * normalizeAngle(nextRot - prevRot))
                }
            }
        }

        // 3. Deform each row
        var offsetIdx = 0
        for (r in 0..rows) {
            // Find which bone segment row r belongs to
            var boneIdx = 0
            for (i in 0 until jointRowIndices.size - 1) {
                if (r >= jointRowIndices[i] && r <= jointRowIndices[i + 1]) {
                    boneIdx = i
                    break
                }
            }

            val rStart = jointRowIndices[boneIdx]
            val rEnd = jointRowIndices[boneIdx + 1]
            val t = if (rEnd > rStart) (r - rStart).toFloat() / (rEnd - rStart).toFloat() else 0f

            val j0 = joints[boneIdx]
            val j1 = joints[boneIdx + 1]

            // Interpolate row center along bone segment
            val cx = (1f - t) * j0.x + t * j1.x
            val cy = (1f - t) * j0.y + t * j1.y

            // Smoothly interpolate rotation angle:
            // At start joint: jointRot[boneIdx]
            // At midpoint (t = 0.5): boneDeltaRot[boneIdx]
            // At end joint: jointRot[boneIdx + 1]
            val rotAngle = if (t <= 0.5f) {
                val s = t * 2f
                normalizeAngle(jointRot[boneIdx] + s * normalizeAngle(boneDeltaRot[boneIdx] - jointRot[boneIdx]))
            } else {
                val s = (t - 0.5f) * 2f
                normalizeAngle(boneDeltaRot[boneIdx] + s * normalizeAngle(jointRot[boneIdx + 1] - boneDeltaRot[boneIdx]))
            }

            val cosA = cos(rotAngle)
            val sinA = sin(rotAngle)

            for (c in 0..cols) {
                val (ox, oy) = restOffsets[offsetIdx++]
                val rx = ox * cosA - oy * sinA
                val ry = ox * sinA + oy * cosA

                val ptIdx = (r * ptsPerRow + c) * 2
                result[ptIdx] = cx + rx
                result[ptIdx + 1] = cy + ry
            }
        }

        return result
    }

    companion object {
        fun normalizeAngle(angle: Float): Float {
            var a = angle % (2f * PI.toFloat())
            if (a > PI.toFloat()) a -= 2f * PI.toFloat()
            if (a < -PI.toFloat()) a += 2f * PI.toFloat()
            return a
        }
    }
}
