package io.github.psd2live.scenario

import io.github.psd2live.core.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

/**
 * Semantic tags drive generation exactly as the classification they stand for: tagging a layer gives the rig and mesh
 * IDs that `layer_classify` gives, and the user's own words ride along without reaching the generators, through undo
 * and a saved project.
 */
class LayerTagScenario {
	@TempDir lateinit var temp: Path

	@Test fun tagsClassifyAsLayerClassifyDoesAndKeepTheUsersWords() = Studio.run(temp, Characters.figure()) {
		val sleeve = mesh("sleeve_r")
		allow("REGENERATION_CONFLICT")
		edits("tag the right sleeve an object", "layer_tag" to req("layer_ids" to listOf("sleeve_r"), "set" to listOf("object", "外套")),
			"rig_generate" to req())
		expect("tagged", now.document.layerOverrides["sleeve_r"] == LayerClassificationOverride(LayerType.PRESET, SemanticTag.OBJECTS, Side.NONE)) {
			"the tags did not become the classification: ${now.document.layerOverrides["sleeve_r"]}"
		}
		expect("tagged", now.document.layout.layerTags["sleeve_r"] == setOf("外套")) { "the user's tag is not kept: ${now.document.layout.layerTags}" }
		val tagged = hash()
		undo("take the tags back")
		edits("classify the sleeve instead", "layer_classify" to req("layer_id" to "sleeve_r", "role" to "objects", "side" to "none"),
			"rig_generate" to req())
		expect("classified", hash() == tagged) { "layer_tag and layer_classify made different rigs" }
		expect("classified", mesh("sleeve_r") == sleeve) { "the sleeve's mesh was renamed" }

		val before = hash(); val classified = now.document.layerOverrides
		edit("mark the left sleeve", "layer_tag", req("layer_ids" to listOf("sleeve_l", "leg_l"), "add" to listOf("可换色")))
		expect("marked", hash() == before) { "a user's tag changed the rig" }
		expect("marked", now.document.layerOverrides == classified) { "a user's tag changed a classification" }
		val row = inspect("layers").getValue("items").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "sleeve_l" }
		val tags = row.getValue("tags").jsonArray.map { it.jsonPrimitive.content }.toSet()
		// The analysis reads the layer's name as its role within the arm.
		expect("inspected", tags == setOf("arm", "left", "sleeve", "可换色")) { "inspect shows tags $tags" }

		edit("make the left leg a toggle", "layer_set_variant", req("layer_id" to "leg_l", "variant" to "toggle", "parameter" to "ParamLegs"))
		expect("variant", now.document.layout.generation.pendingClassifications["leg_l"] == LayerClassificationOverride(LayerType.TOGGLE, SemanticTag.LEGWEAR, Side.LEFT, "ParamLegs", 0)) {
			"the variant did not keep the tags: ${now.document.layout.generation.pendingClassifications["leg_l"]}"
		}
		reopen()
		expect("reopened", now.document.layout.layerTags == mapOf("sleeve_l" to setOf("可换色"), "leg_l" to setOf("可换色"))) {
			"the saved project lost user tags: ${now.document.layout.layerTags}"
		}
	}
}
