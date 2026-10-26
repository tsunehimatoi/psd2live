package io.github.psd2live.core

import io.github.psd2live.application.mergeProjectSettings
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.ModuleTier
import io.github.psd2live.core.recipe.RigModule
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rig's areas and modules, a project's recipe and its settings encoding (docs/zh/spec/RIG_PRESETS.md). */
class RigRecipeTest {
	@Test fun newProjectsAreModernAndProjectsSavedBeforeReadAsLegacy() {
		val settings = WorkspaceSettingsCodec.encode(PipelineConfig())
		assertEquals(JsonObject(emptyMap()), settings[WorkspaceSettingsCodec.RIG], "a new project stores an empty modern recipe")
		assertEquals(RigRecipe.Modern, WorkspaceSettingsCodec.decode(settings).recipe)
		val saved = JsonObject(settings - WorkspaceSettingsCodec.RIG)
		assertEquals(RigRecipe.Legacy, WorkspaceSettingsCodec.decode(saved).recipe, "complete settings without a recipe were saved before it")
		assertEquals(saved, WorkspaceSettingsCodec.encode(WorkspaceSettingsCodec.decode(saved)), "a legacy project's settings text does not change")
	}

	@Test fun legacyRecipesBuildOnlyWhatOlderSettingsSwitch() {
		val module = RigModule("eye.test", ModuleTier.MAJOR)
		assertFalse(RigRecipe.Legacy.switchedOn(module) { true })
		assertTrue(RigRecipe.Modern.switchedOn(module) { true })
		assertFalse(RigRecipe(true, modules = mapOf("eye.test" to ModuleChoice(on = false))).switchedOn(module) { true })
		val config = PipelineConfig(recipe = RigRecipe.Legacy, physicsEyeJelly = false)
		assertFalse(RigRecipe.Legacy.switchedOn(RigModules.EYE_JELLY) { RigModules.setting(config, it) })
		assertTrue(RigRecipe.Legacy.switchedOn(RigModules.EYE_SEE_THROUGH) { RigModules.setting(config, it) })
		for (area in RigModules.areas) assertEquals(area.legacy, RigRecipe.Legacy.base(area.id))
	}

	@Test fun settingsUpgradeSwitchAndReturnToLegacy() {
		val legacy = JsonObject(WorkspaceSettingsCodec.encode(PipelineConfig()) - WorkspaceSettingsCodec.RIG)
		val upgraded = mergeProjectSettings(legacy, buildJsonObject { putJsonObject(WorkspaceSettingsCodec.RIG) {} })
		assertEquals(RigRecipe.Modern, WorkspaceSettingsCodec.decode(upgraded).recipe)
		val back = mergeProjectSettings(upgraded, buildJsonObject { put(WorkspaceSettingsCodec.RIG, JsonNull) })
		assertEquals(legacy, back, "returning to the legacy recipe restores the settings text of before")

		// A module standing for an older setting switches that setting, in legacy projects too.
		val off = mergeProjectSettings(legacy, buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { putJsonObject("eye.jelly") { put("on", false) } } }
		})
		assertFalse(off.getValue("physicsEyeJelly").jsonPrimitive.boolean)
		assertTrue(WorkspaceSettingsCodec.decode(off).recipe.modern, "an object change upgrades the project")
		val reset = mergeProjectSettings(off, buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { put("eye.jelly", JsonNull) } }
		})
		assertTrue(reset.getValue("physicsEyeJelly").jsonPrimitive.boolean, "null returns a module to on")

		assertFailsWith<IllegalArgumentException> { mergeProjectSettings(legacy, buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { putJsonObject("eye.nope") { put("on", false) } } } }) }
		assertFailsWith<IllegalArgumentException> { mergeProjectSettings(legacy, buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("eye") { put("base", "mouth/legacy") } } } }) }
		assertFailsWith<IllegalArgumentException> { mergeProjectSettings(legacy, buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { putJsonObject("eye.jelly") { putJsonObject("values") { put("x", 1) } } } } }) }
		assertFailsWith<IllegalArgumentException> { mergeProjectSettings(legacy, buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { put("areas", JsonNull) } }) }
	}

	@Test fun recipesKeepOnlyWhatDiffersAndSkipWhatTheRegistryDoesNotKnow() {
		val recipe = RigRecipe(true, modules = mapOf("later.module" to ModuleChoice(on = false), "eye.jelly" to ModuleChoice(on = false),
			"eye.see-through" to ModuleChoice(on = true))).normalized()
		assertEquals(RigRecipe.Modern, recipe, "unknown modules, setting modules and on-at-default choices store nothing")
		assertEquals(RigRecipe.Legacy, RigRecipe(false, modules = mapOf("x.y" to ModuleChoice(false))).normalized())
	}

	@Test fun theBudgetSwitchesOffTheLeastImportantAndCostliestFirst() {
		val core = RigModule("eye.core", ModuleTier.CORE)
		val major = RigModule("eye.major", ModuleTier.MAJOR)
		val cheap = RigModule("eye.cheap", ModuleTier.MINOR)
		val dear = RigModule("eye.dear", ModuleTier.MINOR)
		val cost = mapOf(core to 4, major to 3, cheap to 1, dear to 5)
		assertEquals(setOf("eye.dear"), RigModules.withinBudget(cost.keys.toList(), 8) { cost.getValue(it) })
		assertEquals(setOf("eye.dear", "eye.cheap", "eye.major"), RigModules.withinBudget(cost.keys.toList(), 4) { cost.getValue(it) })
		assertEquals(emptySet(), RigModules.withinBudget(cost.keys.toList(), 13) { cost.getValue(it) })
	}
}
