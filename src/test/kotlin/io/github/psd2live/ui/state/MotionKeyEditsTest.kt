package io.github.psd2live.ui.state

import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MotionKeyEditsTest {
    private val clip = MotionClip(
        id = "m", name = "m", duration = 2f,
        curves = listOf(
            MotionCurve("A", listOf(MotionKey(0f, 0f), MotionKey(1f, 5f), MotionKey(2f, 0f))),
            MotionCurve("B", listOf(MotionKey(0.5f, 1f))),
        ),
    )

    @Test fun movingKeysShiftsThemWithinTheClipAndFollowsTheSelection() {
        val selection = setOf(MotionKeyRef("A", 1f), MotionKeyRef("B", 0.5f))
        val (moved, follow) = MotionKeyEdits.move(clip, selection, dt = 5f, dv = 20f, ranges = mapOf("A" to -10f..10f))
        // Clamped so the latest selected key stops at the clip's end.
        assertEquals(listOf(0f, 2f), moved.curve("A")!!.keys.map { it.time })
        assertEquals(10f, moved.curve("A")!!.keys.last().value)
        assertEquals(1.5f, moved.curve("B")!!.keys.single().time)
        assertEquals(setOf(MotionKeyRef("A", 2f), MotionKeyRef("B", 1.5f)), follow)
    }

    @Test fun normalizedMovesScaleByEachParameterRange() {
        val (moved, _) = MotionKeyEdits.move(
            clip, setOf(MotionKeyRef("A", 1f), MotionKeyRef("B", 0.5f)), dt = 0f, dv = 0.1f,
            ranges = mapOf("A" to -30f..30f, "B" to 0f..1f), normalized = true,
        )
        assertEquals(11f, moved.curve("A")!!.keys[1].value, 1e-4f)
        assertEquals(1f, moved.curve("B")!!.keys.single().value, 1e-4f)
    }

    @Test fun deletingEveryKeyOfACurveRemovesIt() {
        val next = MotionKeyEdits.delete(clip, setOf(MotionKeyRef("B", 0.5f), MotionKeyRef("A", 1f)))
        assertNull(next.curve("B"))
        assertEquals(listOf(0f, 2f), next.curve("A")!!.keys.map { it.time })
    }

    @Test fun copiedKeysPasteRelativeToThePlayhead() {
        val copied = MotionKeyEdits.copy(clip, setOf(MotionKeyRef("A", 1f), MotionKeyRef("B", 0.5f)))
        assertEquals(listOf("A" to 0.5f, "B" to 0f), copied.map { it.first to it.second.time })
        val (pasted, selection) = MotionKeyEdits.paste(clip, copied, at = 1.25f)
        assertEquals(listOf(0f, 1f, 1.75f, 2f), pasted.curve("A")!!.keys.map { it.time })
        assertEquals(setOf(MotionKeyRef("A", 1.75f), MotionKeyRef("B", 1.25f)), selection)
    }

    @Test fun shorteningAClipHoldsEachCurveAtTheNewEnd() {
        val next = MotionKeyEdits.withDuration(clip, 1.5f)
        assertEquals(1.5f, next.duration)
        assertEquals(listOf(0f to 0f, 1f to 5f, 1.5f to 2.5f), next.curve("A")!!.keys.map { it.time to it.value })
        assertEquals(clip.curve("B"), next.curve("B"))
    }
}
