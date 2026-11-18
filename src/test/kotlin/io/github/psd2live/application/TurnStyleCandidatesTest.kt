package io.github.psd2live.application

import io.github.psd2live.application.TurnStyleCandidates.Distance
import io.github.psd2live.core.recipe.RigModules
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnStyleCandidatesTest {
    @Test fun nearbyVariantsKeepTheAnchorAndUseDistinctDistanceBandsEvenAtSliderLimits() {
        val knobs = RigModules.FACE_DEPTH_TURN.knobs
        val anchor = TurnStyleCandidates.Option(knobs.associate { it.id to it.range.endInclusive })
        val nearby = TurnStyleCandidates.nearbyBatch(anchor, Random(123))
        assertEquals(anchor.values, nearby[4].values)
        assertEquals(Distance.ANCHOR, nearby[4].distance)
        assertEquals(9, nearby.map { it.values }.toSet().size)
        assertEquals(mapOf(Distance.ANCHOR to 1, Distance.NEAR to 4, Distance.MEDIUM to 3, Distance.FAR to 1),
            nearby.groupingBy { requireNotNull(it.distance) }.eachCount())
        for (option in nearby) {
            assertEquals(anchor.values["style"], option.values["style"])
            assertTrue(knobs.all { option.values.getValue(it.id) in it.range })
            val largestChange = knobs.maxOf { knob ->
                abs(option.values.getValue(knob.id) - anchor.values.getValue(knob.id)) /
                    (knob.range.endInclusive - knob.range.start)
            }
            val expected = when (option.distance) {
                Distance.ANCHOR -> 0f..0f
                Distance.NEAR -> 0.04f..0.07f
                Distance.MEDIUM -> 0.10f..0.15f
                Distance.FAR -> 0.21f..0.28f
                null -> error("Nearby variants have a distance")
            }
            assertTrue(largestChange in expected)
        }
    }

    @Test fun freshBatchHasNineDifferentInRangeStylesAndCoversEveryPreset() {
        val knobs = RigModules.FACE_DEPTH_TURN.knobs
        val presets = knobs.single { it.id == "style" }.range.endInclusive.toInt()
        val first = TurnStyleCandidates.randomBatch(Random(1))
        val second = TurnStyleCandidates.randomBatch(Random(2))
        assertEquals(9, first.map { it.values }.toSet().size)
        assertEquals((0..presets).map(Int::toFloat).toSet(), first.map { it.values.getValue("style") }.toSet())
        assertTrue(first.none { it in second })
        assertTrue((first + second).all { option -> knobs.all { option.values.getValue(it.id) in it.range } })
    }
}
