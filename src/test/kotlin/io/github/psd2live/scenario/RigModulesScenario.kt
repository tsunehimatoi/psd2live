package io.github.psd2live.scenario

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * The rig's modules and the layers' part roles are project data like any other: a module switched off through
 * `settings.rig` changes the rig in one history step and survives a reopen, a project returned to the legacy recipe
 * stores what a project from before it did, and a role set with tags or `layer_classify` is the layer's through undo
 * and a saved project, while one taken away keeps the analysis from guessing it again.
 */
class RigModulesScenario {
	@TempDir lateinit var temp: Path

	@Test fun modulesSwitchThroughSettingsAndRolesThroughTags() = Studio.run(temp, Characters.eyed()) {
		allow("REGENERATION_CONFLICT")
		val copies = { puppet.drawables.count { it.id.raw.endsWith(SeeThroughLayers.SUFFIX) } }
		val before = copies()
		expect("default", before > 0 && WorkspaceSettingsCodec.RIG in now.document.settings) { "a new project is modern and shows its eyes through the bangs: $before" }

		settings("eyes behind the bangs", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { putJsonObject("eye.see-through") { put("on", false) } } }
		})
		expect("switched off", !now.document.settings.getValue("seeThroughEyes").jsonPrimitive.boolean && copies() < before) {
			"the module did not switch its setting: ${now.document.settings["seeThroughEyes"]}, ${copies()} copies" }
		undo("undo the switch")
		expect("undone", copies() == before) { "undo did not bring the copies back" }

		settings("legacy rig", buildJsonObject { put(WorkspaceSettingsCodec.RIG, JsonNull) })
		expect("legacy", WorkspaceSettingsCodec.RIG !in now.document.settings) { "a legacy project stores no recipe" }
		reopen()
		expect("reopened legacy", WorkspaceSettingsCodec.decode(now.document.settings).recipe == io.github.psd2live.core.recipe.RigRecipe.Legacy) {
			"the legacy recipe did not survive a reopen" }
		settings("upgrade", buildJsonObject { putJsonObject(WorkspaceSettingsCodec.RIG) {} })
		expect("upgraded", now.document.settings[WorkspaceSettingsCodec.RIG] == JsonObject(emptyMap())) { "the upgrade stores an empty modern recipe" }

		fun role(layer: String) = now.model.analysis.layers.single { it.source.id.raw == layer }.semantic.role
		edits("tag the right iris a highlight", "layer_tag" to req("layer_ids" to listOf("iris_r"), "add" to listOf("highlight")), "rig_generate" to req())
		expect("tagged", role("iris_r") == PartRole.HIGHLIGHT && now.document.layerOverrides["iris_r"]?.role == PartRole.HIGHLIGHT) {
			"the role word did not become the layer's role: ${role("iris_r")}, ${now.document.layerOverrides["iris_r"]}" }
		val row = inspect("layers").getValue("items").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "iris_r" }
		expect("inspected", "highlight" in row.getValue("tags").jsonArray.map { it.jsonPrimitive.content }) { "inspect does not show the role: $row" }
		reopen()
		expect("reopened", role("iris_r") == PartRole.HIGHLIGHT) { "the saved project lost the role" }

		edits("no role", "layer_classify" to req("layer_id" to "iris_r", "part" to "none"), "rig_generate" to req())
		expect("cleared", role("iris_r") == null && now.document.layerOverrides["iris_r"]?.role == PartRole.PLAIN) {
			"part none did not clear the role: ${now.document.layerOverrides["iris_r"]}" }
		edits("guess again", "layer_classify" to req("layer_id" to "iris_r", "part" to "auto"), "rig_generate" to req())
		expect("auto", now.document.layerOverrides["iris_r"]?.role == null) { "part auto did not hand the role back to the analysis" }
	}

	@Test fun newProjectsCloseTheEyesWithTheFittedLidsAndTheLegacyBaseKeepsTheOldOnes() = Studio.run(temp, Characters.eyed()) {
		allow("REGENERATION_CONFLICT")
		fun drawable(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }
		fun keys(layer: String) = drawable(layer).geometryGrid!!.axes.single { it.parameterId == StandardParameters.EYE_L_OPEN }.keys.toList()
		fun swap(layer: String) = drawable(layer).channelGrids[org.umamo.runtime.model.FormChannel.OPACITY]?.axes?.single()?.keys?.toList()
		val fitted = listOf(0f, io.github.psd2live.core.fit.EyeRig.SWAP)
		fun isFitted(label: String) = expect(label, keys("lash_l").take(2) == fitted && keys("lash_l").takeLast(3) == listOf(1f, 1.5f, 2f) && keys("white_l") == keys("lash_l") &&
			puppet.parameters.single { it.id == StandardParameters.EYE_L_OPEN }.max == 2f &&
			drawable("lash_l").blendShapes.any { it.parameterId.raw == "ParamEyeBallY" } && puppet.parameters.any { it.id.raw == "ParamEyeLSmile" } &&
			swap("lash_l") == listOf(0f, io.github.psd2live.core.fit.EyeRig.SWAP, 1f) && swap("close_l") == listOf(0f, io.github.psd2live.core.fit.EyeRig.SWAP, 1f)) {
			"the fitted eye keys its lids at closed, the swap to the drawn closed line, the crease and on: ${keys("lash_l")} / ${keys("white_l")}, " +
				"swaps ${swap("lash_l")} / ${swap("close_l")}" }
		isFitted("new project")

		settings("legacy eyes", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("eye") { put("base", "eye/legacy") } } }
		})
		expect("legacy", keys("lash_l") == listOf(0f, 1f) && swap("lash_l") == null) { "the legacy base keys ${keys("lash_l")}" }
		undo("back to the fitted eyes")
		isFitted("undone")
		settings("rounder", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("eye") { putJsonObject("values") { put("roundness", 150) } } } }
		})
		reopen()
		isFitted("reopened")
		expect("value", WorkspaceSettingsCodec.decode(now.document.settings).recipe.baseValue("eye", "roundness") == 150f) { "the value did not survive a reopen" }
	}
	@Test fun fittedBrowsTurnAlongTheirAngleAndTheLegacyBaseKeepsTheOldOnes() = Studio.run(temp, Characters.browed()) {
		allow("REGENERATION_CONFLICT")
		fun drawable(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }
		fun axes(layer: String) = drawable(layer).geometryGrid!!.axes.map { it.parameterId.raw to it.keys.toList() }
		fun isFitted(label: String) = expect(label, axes("brow_l") == listOf("ParamBrowLY" to listOf(-1f, 0f, 1f), "ParamBrowLAngle" to listOf(-1f, -0.5f, 0f, 0.5f, 1f)) &&
			drawable("brow_l").blendShapes.map { it.parameterId.raw }.containsAll(listOf("ParamBrowLX", "ParamBrowLForm")) &&
			puppet.parameters.any { it.id.raw == "ParamBrowRAngle" }) { "the fitted brow keys its height by its angle: ${axes("brow_l")}" }
		isFitted("new project")
		settings("legacy brows", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("brow") { put("base", "brow/legacy") } } }
		})
		expect("legacy", axes("brow_l").none { it.first == "ParamBrowLAngle" } && puppet.parameters.none { it.id.raw == "ParamBrowLAngle" }) {
			"the legacy base keys ${axes("brow_l")}" }
		undo("back to the fitted brows")
		isFitted("undone")
	}
	@Test fun fittedMouthOpensInStagesAndTheLegacyBaseKeepsTheOldOne() = Studio.run(temp, Characters.mouthed()) {
		allow("REGENERATION_CONFLICT")
		fun drawable(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }
		fun axes(layer: String) = drawable(layer).geometryGrid!!.axes.map { it.parameterId.raw to it.keys.toList() }
		fun isFitted(label: String) = expect(label, axes("mouth") == listOf("ParamMouthForm" to io.github.psd2live.core.fit.MouthRig.FORM_KEYS.toList(),
			"ParamMouthOpenY" to io.github.psd2live.core.fit.MouthRig.OPEN_KEYS.toList()) &&
			drawable("mouth").blendShapes.map { it.parameterId.raw }.containsAll(listOf("ParamA", "ParamO", "ParamMouthX")) &&
			drawable("face").blendShapes.any { it.parameterId.raw == "ParamMouthOpenY" } && puppet.parameters.any { it.id.raw == "ParamCheekPuff" }) {
			"the fitted mouth keys its form by its staged opening, with vowels and the jaw: ${axes("mouth")}" }
		isFitted("new project")
		settings("legacy mouth", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("bases") { putJsonObject("mouth") { put("base", "mouth/legacy") } } }
		})
		expect("legacy", axes("mouth").last().second == listOf(0f, 0.5f, 1f) && puppet.parameters.none { it.id.raw == "ParamA" }) {
			"the legacy base keys ${axes("mouth")}" }
		undo("back to the fitted mouth")
		isFitted("undone")
	}

	@Test fun theHeadTurnsInDepthUntilTheModuleGoesOff() = Studio.run(temp, Characters.mouthed()) {
		allow("REGENERATION_CONFLICT")
		fun turns(layer: String) = puppet.drawables.single { it.id.raw == mesh(layer) }.blendShapes.any { it.parameterId.raw == "ParamAngleX" }
		expect("new project", turns("face") && turns("mouth") && turns("back_hair")) { "every layer of the head turns on its shell" }
		settings("no depth turn", buildJsonObject {
			putJsonObject(WorkspaceSettingsCodec.RIG) { putJsonObject("modules") { putJsonObject("face.depth-turn") { put("on", false) } } }
		})
		expect("off", !turns("face") && !turns("mouth")) { "switched off, the head turns as it did" }
	}
}
