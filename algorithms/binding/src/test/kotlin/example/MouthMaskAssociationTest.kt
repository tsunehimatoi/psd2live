package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.MouthMaskAssociation
import kotlin.test.Test
import kotlin.test.assertEquals

class MouthMaskAssociationTest {
    @Test fun aNearbyLipStrokeDoesNotClipAwayTheTeethOrTongue() {
        val cavity = "cavity" to Bounds(0f, 0f, 100f, 70f)
        val stroke = "stroke" to Bounds(5f, 29f, 95f, 31f)
        val sticker = "sticker" to Bounds(35f, 35f, 65f, 45f)
        for (component in listOf(Bounds(10f, 24f, 90f, 36f), Bounds(25f, 33f, 75f, 55f), Bounds(40f, 29f, 60f, 31f))) {
            for (candidates in listOf(listOf(stroke, sticker, cavity), listOf(cavity, sticker, stroke))) {
                assertEquals("cavity", MouthMaskAssociation.select(component, candidates) { it.second }?.first)
            }
        }
    }

    @Test fun spatialCoverageKeepsSeparateMouthsIndependent() {
        val first = Bounds(0f, 0f, 100f, 70f)
        val second = Bounds(200f, 0f, 300f, 70f)
        assertEquals(second, MouthMaskAssociation.select(Bounds(220f, 20f, 280f, 40f), listOf(first, second)) { it })
        assertEquals(second, MouthMaskAssociation.select(Bounds(310f, 20f, 320f, 40f), listOf(first, second)) { it })
    }

    @Test fun equalFullCoveragePrefersTighterCloserApertureOverOversizedAlternate() {
        val cavity = "cavity" to Bounds(10f, 30f, 90f, 70f)
        val oversizedExpression = "expression" to Bounds(0f, 0f, 100f, 75f)
        val teeth = Bounds(15f, 30f, 85f, 45f)
        for (candidates in listOf(listOf(oversizedExpression, cavity), listOf(cavity, oversizedExpression))) {
            assertEquals("cavity", MouthMaskAssociation.select(teeth, candidates) { it.second }?.first)
        }
    }
}
