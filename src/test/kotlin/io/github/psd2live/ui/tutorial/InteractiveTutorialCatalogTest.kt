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

	@Test
	fun hierarchyIncludesImportAndPlacementSteps() {
		val keys = tutorialDefinition(TutorialId.HIERARCHY).steps.map { it.key }
		assertTrue("importLayer" in keys)
		assertTrue("placeSession" in keys)
		assertEquals("done", keys.last())
	}

	@Test
	fun variantsCoversToggleAndSwitchFlow() {
		assertEquals(
			listOf("types", "toggleWhy", "toggleSetup", "switchWhy", "switchSetup", "done"),
			tutorialDefinition(TutorialId.VARIANTS).steps.map { it.key },
		)
	}

	@Test
	fun parametersCoversPanelDragKeysAndLink() {
		assertEquals(
			listOf("findTab", "panel", "drag", "keys", "operate", "link", "done"),
			tutorialDefinition(TutorialId.PARAMETERS).steps.map { it.key },
		)
	}

	@Test
	fun createDeformerUsesTreeMenusAndPlacement() {
		val steps = tutorialDefinition(TutorialId.CREATE_DEFORMER).steps
		assertEquals(
			listOf("selectFirst", "contextDeformer", "contextLayer", "placement", "done"),
			steps.map { it.key },
		)
		assertTrue(steps[0].requireSelection)
		assertEquals(TutorialTargetId.PLACEMENT_PANEL, steps[3].targetId)
	}

	@Test
	fun projectHistoryAndUpscaleTargetsAreCorrect() {
		val history = tutorialDefinition(TutorialId.PROJECT_HISTORY).steps.first { it.key == "historyTab" }
		assertEquals(TutorialTargetId.HISTORY_TAB, history.targetId)
		val entry = tutorialDefinition(TutorialId.TEXTURE_UPSCALE).steps.first { it.key == "entry" }
		assertEquals(TutorialTargetId.TOOLS_TEXTURE_UPSCALE, entry.targetId)
		assertEquals("tools", entry.forcesMenu)
	}

	@Test
	fun deformBrushesRequireSelection() {
		val brushes = tutorialDefinition(TutorialId.DEFORM_MODE).steps.first { it.key == "brushes" }
		assertTrue(brushes.requireSelection)
		assertTrue(brushes.showAction)
	}
}
