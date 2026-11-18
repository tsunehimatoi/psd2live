package io.github.psd2live.core.sim

import io.github.psd2live.bridge.PuppetIr
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Glue
import io.github.psd2live.format.model.GluePair
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RigIR
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectHairShapesTest {
    private val keys = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

    @Test fun supportedHairProducesDifferentRootPinnedSwayTurnAndCompression() {
        val shapes = DirectHairShapes.build(strands(length = 100f), keys) {}.associateBy { it.kind }
        assertEquals(DirectMotionKind.entries.toSet(), shapes.keys)
        for (shape in shapes.values) {
            assertTrue(shape.frequencyHz.isFinite() && shape.frequencyHz > 0f)
            assertTrue(shape.dampingRatio.isFinite() && shape.dampingRatio in 0f..1f)
            assertTrue(shape.offsets[2].all { it == 0f })
            assertTrue(shape.offsets.all { key -> key.take(4).all { it == 0f } })
        }
        val sway = shapes.getValue(DirectMotionKind.SWAY).offsets.last()
        val turn = shapes.getValue(DirectMotionKind.TURN).offsets.last()
        val tips = shapes.getValue(DirectMotionKind.FOLLOW_THROUGH).offsets.last()
        assertTrue(sway[8] > sway[4] && sway[4] > 0f)
        assertTrue(turn[8] > turn[4] && turn[4] > 0f)
        assertTrue(abs(sway[4] / sway[8] - turn[4] / turn[8]) > 0.05f)
        assertTrue(abs(tips[4] / tips[8]) < abs(sway[4] / sway[8]))
        val compression = shapes.getValue(DirectMotionKind.COMPRESSION).offsets
        assertTrue(compression.last()[9] > 1f)
        assertTrue(compression.first()[9] < 0f)
        assertTrue(compression.last()[9] > -compression.first()[9])
    }

    @Test fun weldKeepsTheSameDisplacementAndLengthChangesNaturalPeriod() {
        val welded = DirectHairShapes.build(strands(length = 100f, weld = true), keys) {}
        for (shape in welded) for (key in shape.offsets) {
            assertEquals(key[10], key[20])
            assertEquals(key[11], key[21])
        }
        val short = DirectHairShapes.build(strands(length = 45f), keys) {}.first { it.kind == DirectMotionKind.SWAY }
        val long = DirectHairShapes.build(strands(length = 160f), keys) {}.first { it.kind == DirectMotionKind.SWAY }
        assertTrue(short.frequencyHz > long.frequencyHz)
    }

    @Test fun endSectionTurnsAcrossTheCurlWidthWithoutCollapsingIt() {
        val scene = strands(length = 100f, curve = false)
        val turn = DirectHairShapes.build(scene, keys) {}.first { it.kind == DirectMotionKind.TURN }
        for (key in listOf(turn.offsets.first(), turn.offsets.last())) {
            val dx = scene.state.goalX[5] - scene.state.goalX[4] + key[10] - key[8]
            val dy = scene.state.goalY[5] - scene.state.goalY[4] + key[11] - key[9]
            assertEquals(12f, hypot(dx, dy), 0.03f)
            assertTrue(abs(dy) > 5f)
        }
        assertTrue(turn.offsets.first()[11] - turn.offsets.first()[9] < 0f)
        assertTrue(turn.offsets.last()[11] - turn.offsets.last()[9] > 0f)
        assertTrue((turn.offsets.first()[11] + turn.offsets.first()[9]) * 0.5f > 10f)
        assertTrue((turn.offsets.last()[11] + turn.offsets.last()[9]) * 0.5f > 10f)
    }

    @Test fun swayCarriesTheStrandTurnChangesItsAngleAndFollowThroughCounterBends() {
        val scene = strands(length = 100f, curve = false)
        val finerKeys = FloatArray(9) { (it - 4) / 4f }
        val modes = DirectHairShapes.build(scene, finerKeys) {}.associateBy { it.kind }
        val sway = modes.getValue(DirectMotionKind.SWAY).offsets.last()
        val turn = modes.getValue(DirectMotionKind.TURN).offsets.last()
        val follow = modes.getValue(DirectMotionKind.FOLLOW_THROUGH).offsets.last()
        val tipCenterX: (FloatArray) -> Float = { (it[8] + it[10]) * 0.5f }
        val tipTilt: (FloatArray) -> Float = { it[11] - it[9] }
        assertTrue(tipCenterX(sway) > 0f && tipCenterX(turn) > 0f)
        assertTrue(tipTilt(turn) > 2f * (tipTilt(sway) + tipTilt(follow)))
        assertTrue(follow[4] + follow[6] < -0.5f && tipCenterX(follow) > 0f)
        val mixedX = 12f + (sway[10] - sway[8]) + (turn[10] - turn[8]) + (follow[10] - follow[8])
        val mixedY = tipTilt(sway) + tipTilt(turn) + tipTilt(follow)
        assertTrue(hypot(mixedX, mixedY) in 11.4f..13.8f)
        assertTrue(modes.values.all { it.offsets[4].all { v -> v == 0f } })
    }

    @Test fun fiveTurnKeysLimitSlopeChangesAndKeepTheirEndCurves() {
        for (curve in listOf(false, true)) {
            val scene = strands(length = 100f, curve = curve)
            val denseKeys = FloatArray(9) { (it - 4) / 4f }
            val dense = DirectHairShapes.build(scene, denseKeys) {}.single { it.kind == DirectMotionKind.TURN }
            val smooth = DirectHairShapes.build(scene, keys) {}.single { it.kind == DirectMotionKind.TURN }
            assertContentEquals(floatArrayOf(-1f, -0.20f, 0f, 0.20f, 1f), smooth.keys)
            assertContentEquals(dense.offsets.first(), smooth.offsets.first())
            assertContentEquals(dense.offsets.last(), smooth.offsets.last())
            for (i in smooth.offsets[0].indices) {
                val nearSlope = (smooth.offsets[3][i] - smooth.offsets[1][i]) / (smooth.keys[3] - smooth.keys[1])
                val totalSlope = (smooth.offsets.last()[i] - smooth.offsets.first()[i]) * 0.5f
                assertEquals(totalSlope, nearSlope, 1e-5f)
            }
            fun maxSlopeJump(at: FloatArray, forms: List<FloatArray>): Float = (1 until at.lastIndex).maxOf { key ->
                fun jump(i: Int): Float = (forms[key + 1][i] - forms[key][i]) / (at[key + 1] - at[key]) -
                    (forms[key][i] - forms[key - 1][i]) / (at[key] - at[key - 1])
                (forms[key].indices step 2).maxOf { i -> hypot(jump(i), jump(i + 1)) }
            }
            val old = maxSlopeJump(keys, dense.offsets.filterIndexed { index, _ -> index % 2 == 0 })
            val new = maxSlopeJump(smooth.keys, smooth.offsets)
            assertTrue(new < old * 0.8f, "Turn slope jump: $new px instead of $old px")
        }
    }

    @Test fun compressionKeepsOneNeutralTangentAndRetainsTheAsymmetricEnds() {
        val scene = strands(length = 100f, curve = false)
        val nine = FloatArray(9) { (it - 4) / 4f }
        val compression = DirectHairShapes.build(scene, nine) {}.first { it.kind == DirectMotionKind.COMPRESSION }
        val tip = compression.offsets.map { (it[9] + it[11]) * 0.5f }
        assertTrue(tip.zipWithNext().all { (a, b) -> b > a })
        assertEquals(tip[4] - tip[3], tip[5] - tip[4], 0.05f)
        assertTrue(tip.last() > -2f * tip.first())
        val five = DirectHairShapes.build(scene, keys) {}.first { it.kind == DirectMotionKind.COMPRESSION }
        assertTrue(five.keys.contentEquals(floatArrayOf(-1f, -0.25f, 0f, 0.25f, 1f)))
        val near = five.offsets.map { (it[9] + it[11]) * 0.5f }
        assertEquals(near[2] - near[1], near[3] - near[2], 0.05f)
        val singleAxis = DirectHairShapes.build(scene, floatArrayOf(-1f, 0f, 1f)) {}.first { it.kind == DirectMotionKind.COMPRESSION }
        assertEquals(tip.first(), (singleAxis.offsets.first()[9] + singleAxis.offsets.first()[11]) * 0.5f)
        assertEquals(tip.last(), (singleAxis.offsets.last()[9] + singleAxis.offsets.last()[11]) * 0.5f)
    }

    private fun strands(length: Float, weld: Boolean = false, curve: Boolean = true): SimScene {
        val middle = if (curve) 4f else 0f
        val tip = if (curve) 8f else 0f
        val width = if (curve) 11f else 12f
        fun strand(id: String, x: Float) = Mesh(id, id, null, geometry = MeshGeometry(
            Floats.values(x, 0f, x + 12f, 0f, x + middle, 0.5f * length, x + middle + width, 0.5f * length,
                x + tip, length, x + tip + width, length),
            Floats.values(0f, 0f, 1f, 0f, 0f, 0.5f, 1f, 0.5f, 0f, 1f, 1f, 1f),
            Ints.values(0, 1, 2, 1, 3, 2, 2, 3, 4, 3, 5, 4)),
            offsets = null)
        val meshes = if (weld) listOf(strand("a", 0f), strand("b", 11f)) else listOf(strand("a", 0f))
        val glues = if (weld) listOf(Glue(null, "a", "b", listOf(GluePair(5, 4, 0.5f, 0.5f)))) else emptyList()
        val model = PuppetIr.toPuppet(RigIR(Canvas(200f, 200f), emptyList(), meshes = meshes, glues = glues)).copy(
            vertexGroups = meshes.map {
                VertexGroup("pin", DrawableId(it.id), VertexGroupKind.PIN, floatArrayOf(1f, 1f, 0f, 0f, 0f, 0f))
            })
        val edit = RigSimEdit("hair", "Hair", SimKind.HAIR, meshes.map { it.id }, material = SimMaterial(goal = 0f),
            glueRoles = if (weld) mapOf("a|b" to GlueRole.CONSTRAINT) else emptyMap())
        return SimScene.build(model, edit).also { it.reset(model, emptyMap()) }
    }
}
