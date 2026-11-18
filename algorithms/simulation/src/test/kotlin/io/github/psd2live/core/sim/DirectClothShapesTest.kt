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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectClothShapesTest {
    @Test fun separateLoadsKeepPinsAndWeldsWhileChangingTheFreeHem() {
        val scene = twoPanels()
        val original = scene.state.positions()
        val modes = DirectClothShapes.build(scene, floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)) {}
            .associateBy { it.kind }

        for (mode in modes.values) {
            assertTrue(mode.frequencyHz in 0.35f..4f)
            assertTrue(mode.dampingRatio in 0.06f..0.9f)
            assertContentEquals(FloatArray(original.size), mode.offsets[2])
            for (form in mode.offsets) {
                assertTrue(form.all(Float::isFinite))
                for (root in listOf(0, 1, 2, 9, 10, 11)) {
                    assertEquals(0f, form[2 * root])
                    assertEquals(0f, form[2 * root + 1])
                }
                for ((left, right) in listOf(5 to 12, 8 to 15)) {
                    assertEquals(form[2 * left], form[2 * right])
                    assertEquals(form[2 * left + 1], form[2 * right + 1])
                }
            }
        }
        val sway = modes.getValue(DirectMotionKind.SWAY).offsets.last()
        val turn = modes.getValue(DirectMotionKind.TURN).offsets.last()
        assertTrue(sway[2 * 6] > 1f)
        val leftHemY = 2 * 6 + 1
        val rightHemY = 2 * 17 + 1
        val turnTilt = turn[rightHemY] - turn[leftHemY]
        val swayTilt = sway[rightHemY] - sway[leftHemY]
        assertTrue(turnTilt > 2f * abs(swayTilt), "Turning should tilt the hem in the positive direction")
        val leftHemX = leftHemY - 1
        val rightHemX = rightHemY - 1
        val oppositeTurn = modes.getValue(DirectMotionKind.TURN).offsets.first()
        assertTrue(turn[leftHemX] > turn[rightHemX], "The loose hem should bunch toward the direction of travel")
        assertTrue(oppositeTurn[rightHemX] < oppositeTurn[leftHemX], "The loose side should move with the turn direction")
        val follow = modes.getValue(DirectMotionKind.FOLLOW_THROUGH).offsets.last()
        assertTrue(abs(follow[rightHemY] - follow[leftHemY]) > abs(swayTilt))
        assertTrue(abs(follow[leftHemX]) < abs(sway[leftHemX]))
        val fold = modes.getValue(DirectMotionKind.COMPRESSION).offsets
        val up = fold.last()[2 * 6 + 1]
        val down = fold.first()[2 * 6 + 1]
        assertTrue(up > 1f && down < 0f)
        assertTrue(up > abs(down) * 1.15f && abs(down) > up * 0.6f, "Compression should keep a distinct release with visible rebound")
        val nearRelease = fold[1][leftHemY]
        val nearCompression = fold[3][leftHemY]
        assertTrue(abs(nearCompression / nearRelease + 1f) < 0.16f, "The two segments touching neutral should have similar speed")
        assertContentEquals(original, scene.state.positions())
    }

    @Test fun repairsNarrowFoldTrianglesWithoutFlatteningTheHem() {
        val scene = twoPanels(thinFold = true)
        val rest = scene.state.positions()
        val turns = DirectClothShapes.build(scene, floatArrayOf(-1f, 0f, 1f)) {}
            .single { it.kind == DirectMotionKind.TURN }.offsets
        val triangles = scene.solver.triangles
        fun area(positions: FloatArray, t: Int): Float {
            val a = triangles.a[t] * 2
            val b = triangles.b[t] * 2
            val c = triangles.c[t] * 2
            return (positions[b] - positions[a]) * (positions[c + 1] - positions[a + 1]) -
                (positions[b + 1] - positions[a + 1]) * (positions[c] - positions[a])
        }
        for (key in listOf(turns.first(), turns.last())) {
            val posed = FloatArray(rest.size) { rest[it] + key[it] }
            for (t in 0 until triangles.size) {
                assertTrue(area(posed, t) / area(rest, t) >= 0.14f, "Thin triangle $t must remain oriented")
            }
            assertTrue(abs(key[2 * 6]) > 10f)
        }
    }

    private fun twoPanels(thinFold: Boolean = false): SimScene {
        fun panel(id: String, x: Float): Mesh {
            val positions = FloatArray(18) { component ->
                val v = component / 2
                when {
                    component % 2 == 0 -> x + (v % 3) * 30f
                    thinFold && v == 4 -> 0.07f
                    else -> (v / 3) * 60f
                }
            }
            val uvs = FloatArray(18) { component ->
                val v = component / 2
                if (component % 2 == 0) (v % 3) * .5f else (v / 3) * .5f
            }
            val triangles = (0 until 4).flatMap { cell ->
                val i = cell / 2 * 3 + cell % 2
                listOf(i, i + 1, i + 3, i + 1, i + 4, i + 3)
            }.toIntArray()
            return Mesh(id, id, null, geometry = MeshGeometry(
                Floats.of(positions), Floats.of(uvs), Ints.of(triangles)), offsets = null)
        }
        val left = panel("left", 0f)
        val right = panel("right", 60f)
        val glue = Glue(null, left.id, right.id, listOf(GluePair(2, 0, .5f, .5f), GluePair(5, 3, .5f, .5f), GluePair(8, 6, .5f, .5f)))
        val rig = PuppetIr.toPuppet(RigIR(Canvas(120f, 120f), emptyList(), meshes = listOf(left, right), glues = listOf(glue)))
        val model = rig.copy(vertexGroups = listOf(left, right).map {
            VertexGroup("roots", DrawableId(it.id), VertexGroupKind.PIN, FloatArray(9) { i -> if (i < 3) 1f else 0f })
        })
        val edit = RigSimEdit("test", "Test", SimKind.CLOTH, listOf(left.id, right.id),
            glueRoles = mapOf("left|right" to GlueRole.CONSTRAINT))
        return SimScene.build(model, edit).also { it.reset(model, emptyMap()) }
    }
}
