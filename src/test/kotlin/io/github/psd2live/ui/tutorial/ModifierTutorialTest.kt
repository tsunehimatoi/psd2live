package io.github.psd2live.ui.tutorial

import kotlin.test.*

class ModifierTutorialTest {
    @Test fun simulationStackStepsOpenTheirActualEditorAndKeepProgressKeys() {
        val original = tutorialDefinition(TutorialId.SIMULATION).steps
        val projected = original.map { it.forModifierStack(TutorialId.SIMULATION, true) }
        assertEquals(original.map { it.key }, projected.map { it.key })
        assertTrue(projected.none { it.targetId == TutorialTargetId.SIMULATION_DOCK || it.selectDock == "simulation" })
        for (key in listOf("bodies", "glue", "material", "materialValues", "inputs", "bakeSettings", "bake")) {
            val step = projected.single { it.key == key }
            assertEquals(TutorialTargetId.MODEL_SETTINGS, step.targetId)
            assertEquals("settings", step.selectDock)
            assertTrue(step.expandModelSettings)
        }
        assertEquals(TutorialTargetId.PREVIEW_TAB, projected.single { it.key == "preview" }.targetId)
        assertEquals(original.single { it.key == "weights" }, projected.single { it.key == "weights" })
    }

    @Test fun legacyProjectsAndOtherChaptersKeepTheirNavigation() {
        for ((id, definition) in TutorialCatalog) {
            for (step in definition.steps) {
                assertSame(step, step.forModifierStack(id, false))
                if (id != TutorialId.SIMULATION) assertSame(step, step.forModifierStack(id, true))
            }
        }
    }
}
