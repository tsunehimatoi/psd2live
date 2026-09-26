package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushFalloffTest {
    private fun tip(radius: Float, falloff: BrushFalloff = BrushFalloff.SMOOTH, shape: BrushShape = BrushShape.CIRCLE) =
        BrushTip(radius, 0f, shape, 0f, 1f, falloff)

    @Test fun smoothKeepsTheOldHermiteProfile() {
        for (d in listOf(0f, 10f, 25f, 40f, 49f)) {
            val x = d / 50f
            assertEquals(1f - x * x * (3f - 2f * x), brushWeight(d, 50f, 0f), 1e-5f)
        }
    }

    @Test fun profilesMeetAtTheCoreAndTheRim() {
        for (falloff in BrushFalloff.entries - BrushFalloff.RANDOM) {
            assertEquals(1f, falloff.weight(1f), 1e-5f, "$falloff at the core")
            assertEquals(0f, falloff.weight(0f), 1e-5f, "$falloff at the rim")
        }
        assertEquals(.5f, brushWeight(25f, 50f, 0f, BrushFalloff.LINEAR), 1e-5f)
        assertEquals(1f, brushWeight(49f, 50f, 0f, BrushFalloff.CONSTANT), 1e-5f)
        val random = (0 until 64).map { BrushFalloff.RANDOM.weight(1f, it) }
        assertTrue(random.all { it in 0f..1f } && random.toSet().size > 32)
        assertEquals(BrushFalloff.RANDOM.weight(.7f, 5), BrushFalloff.RANDOM.weight(.7f, 5))
    }

    @Test fun connectedOnlySkipsAnIslandTheTipStillCovers() {
        val a = BrushSurface(
            listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(0f, 10f)),
            listOf(intArrayOf(1, 2), intArrayOf(0, 2), intArrayOf(0, 1)),
            intArrayOf(0, 1, 2),
            1,
        )
        val b = BrushSurface(
            listOf(Offset(20f, 0f), Offset(30f, 0f), Offset(20f, 10f)),
            listOf(intArrayOf(1, 2), intArrayOf(0, 2), intArrayOf(0, 1)),
            intArrayOf(0, 1, 2),
            2,
        )
        val pointer = Offset(2f, 2f)
        val loose = brushWeights(listOf(a, b), pointer, pointer, tip(60f), connected = false)
        assertTrue(loose[1].all { it > 0f })
        val joined = brushWeights(listOf(a, b), pointer, pointer, tip(60f), connected = true)
        assertTrue(joined[0].all { it > 0f })
        assertTrue(joined[1].all { it == 0f })
    }

    @Test fun connectedCircleMeasuresAlongTheMesh() {
        // A hairpin: the far arm's end sits 10px from the pointer but 90px away along the edges.
        val hairpin = BrushSurface(
            listOf(Offset(0f, 0f), Offset(0f, 40f), Offset(10f, 40f), Offset(10f, 0f)),
            listOf(intArrayOf(1), intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(2)),
            null,
            0,
        )
        val pointer = Offset(0f, 0f)
        assertTrue(brushWeights(listOf(hairpin), pointer, pointer, tip(20f), connected = false)[0][3] > 0f)
        val joined = brushWeights(listOf(hairpin), pointer, pointer, tip(60f), connected = true)[0]
        assertEquals(brushWeight(40f, 60f, 0f), joined[1], 1e-4f)
        assertEquals(brushWeight(50f, 60f, 0f), joined[2], 1e-4f)
        assertEquals(0f, joined[3])
    }

    @Test fun connectedLineOnlyDropsWhatItCannotReach() {
        val strip = BrushSurface(
            listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(30f, 0f)),
            listOf(intArrayOf(1), intArrayOf(0), intArrayOf()),
            null,
            0,
        )
        val pointer = Offset(0f, 0f)
        val weights = brushWeights(listOf(strip), pointer, pointer, tip(20f, shape = BrushShape.LINE, falloff = BrushFalloff.CONSTANT), connected = true)[0]
        assertEquals(listOf(1f, 1f, 0f), weights.toList())
    }
}
