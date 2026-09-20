package io.github.psd2live.ui.tutorial

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TutorialGeometryTest {
	@Test
	fun separateAndOverlappingHolesLeaveAnEvenScrim() {
		val window = Rect(0f, 0f, 100f, 100f)
		val holes = listOf(Rect(10f, 10f, 30f, 30f), Rect(20f, 20f, 40f, 40f), Rect(60f, 10f, 80f, 30f))
		val masks = spotlightMaskRects(window, holes)
		assertEquals(8900f, masks.sumOf { (it.width * it.height).toDouble() }.toFloat())
		masks.forEachIndexed { index, mask ->
			holes.forEach { assertFalse(mask.overlaps(it)) }
			masks.drop(index + 1).forEach { assertFalse(mask.overlaps(it)) }
		}
	}

	@Test
	fun clipsOffscreenTargetsAndHandlesMissingTargets() {
		val window = Rect(0f, 0f, 100f, 100f)
		assertEquals(listOf(window), spotlightMaskRects(window, emptyList()))
		val masks = spotlightMaskRects(window, listOf(Rect(-20f, -20f, 50f, 50f)))
		assertEquals(7500f, masks.sumOf { (it.width * it.height).toDouble() }.toFloat())
		assertTrue(masks.all { it.left >= 0 && it.top >= 0 && it.right <= 100 && it.bottom <= 100 })
	}

	@Test
	fun measuredTallCoachFitsBesideRightDock() {
		val hole = Rect(700f, 40f, 990f, 790f)
		val position = coachPosition(hole, IntSize(1000, 800), IntSize(340, 460), 16f, true)
		assertTrue(position.x >= 16 && position.y >= 16)
		assertTrue(position.x + 340 <= hole.left)
		assertTrue(position.y + 460 <= 784)
	}

	@Test
	fun fallsBackAboveTargetWhenNeitherSideFits() {
		val hole = Rect(10f, 600f, 790f, 700f)
		val position = coachPosition(hole, IntSize(800, 800), IntSize(340, 400), 16f, true)
		assertTrue(position.y + 400 <= hole.top)
	}
}
