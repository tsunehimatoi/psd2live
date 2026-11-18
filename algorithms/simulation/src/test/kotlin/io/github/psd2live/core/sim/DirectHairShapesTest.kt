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
