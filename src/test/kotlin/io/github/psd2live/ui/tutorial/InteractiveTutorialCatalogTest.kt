package io.github.psd2live.ui.tutorial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InteractiveTutorialCatalogTest {
	@Test
	fun progressiveOrderCoversAllIds() {
		assertEquals(TutorialId.entries, TutorialId.progressiveOrder)
		assertEquals(TutorialId.WORKSPACE, TutorialId.BASIC.nextId)
		assertEquals(null, TutorialId.TEXTURE_UPSCALE.nextId)
	}

	@Test
	fun everyTutorialHasStepsAndDone() {
		TutorialId.entries.forEach { id ->
			val def = tutorialDefinition(id)
			assertTrue(def.steps.isNotEmpty(), id.name)
			assertTrue(def.steps.last().isDone, id.name)
			assertTrue(def.actionableSteps.isNotEmpty(), id.name)
			assertFalse(def.actionableSteps.any { it.isDone }, id.name)
		}
	}

	@Test
	fun advanceAndContinueNextTutorial() {
		var state = InteractiveTutorialState().start(TutorialId.BASIC)
		assertTrue(state.active)
		assertEquals(TutorialId.BASIC, state.tutorialId)
		while (!state.isDoneStep) {
			state = state.advance()
			assertTrue(state.active)
		}
		state = state.continueNextTutorial()
		assertEquals(TutorialId.WORKSPACE, state.tutorialId)
		assertEquals(0, state.stepIndex)
	}

	@Test
	fun retreatDoesNotGoBeforeStart() {
		val state = InteractiveTutorialState().start(TutorialId.HIERARCHY).retreat()
		assertEquals(0, state.stepIndex)
		assertNotNull(state.step)
	}
}
