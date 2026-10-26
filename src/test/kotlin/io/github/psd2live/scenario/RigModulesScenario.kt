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
}
