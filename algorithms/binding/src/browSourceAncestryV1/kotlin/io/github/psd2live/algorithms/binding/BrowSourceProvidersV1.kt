package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.*

/** Future-only source-chain contract. Versions form coherent tuples, never independent choices. */
object BrowSourceProvidersV1 {
    enum class Stage { SHAPE, LID, BLINK, SPRING }
    private val common = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5)
    private val browOrder = listOf("brow/fitted", "brow.shape", "brow.lid-coupling", "brow.blink")
    val eyeAddons = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.emotion" to 3,
        "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4, "eye.lid-fade" to 3)
    val optionalVersions = eyeAddons + mapOf("hair/legacy" to 2, "hair/chains" to 2,
        "mouth/fitted" to 3, "mouth.outline" to 7, "mouth.vowel" to 6, "mouth.capture" to 5, "mouth.jaw" to 7,
        "face.skin-clip" to 1, "eye.see-through" to 1, "brow.see-through" to 1)
    private fun tuples(stage: Stage): List<List<Int>> = when (stage) {
        Stage.SHAPE -> listOf(listOf(5))
        Stage.LID -> listOf(listOf(5, 7))
        Stage.BLINK -> listOf(listOf(4, 6, 8), listOf(5, 7, 9))
        Stage.SPRING -> listOf(listOf(4, 6, 8, 6), listOf(4, 6, 8, 7), listOf(5, 7, 9, 7))
    }
    fun required(stage: Stage): Set<String> = common.keys + browOrder.take(tuples(stage).first().size)
    fun supported(stage: Stage): Set<String> = required(stage) + optionalVersions.keys
    fun observed(stage: Stage): Set<String> = supported(stage) + setOf("brow.shape", "brow.lid-coupling", "brow.blink",
        "brow.spring", "face.depth-turn", "mouth.cheek-lid", "face/legacy", "eye/legacy", "brow/legacy",
        "mouth/legacy", "expression.toggles", "eye.gaze-lid")

    /** Preliminary membership only. Every evaluator must also call validate for the whole tuple. */
    fun acceptsVersion(stage: Stage, type: String, version: Int): Boolean {
        val index = browOrder.indexOf(type)
        return if (index >= 0) tuples(stage).any { it.getOrNull(index) == version }
        else version == (common[type] ?: optionalVersions[type])
    }

    fun validate(providers: List<ModifierUpstreamCapability>, instance: RigModifierInstance, stage: Stage) {
        val scaffold = instance.config.getValue("scaffold")
        require(instance.targets.isNotEmpty() && instance.targets.distinct().size == instance.targets.size &&
            instance.targets.all { it.kind == "mesh" })
        require(providers.map { it.instanceId }.distinct().size == providers.size && providers.all { owner ->
            owner.instanceId != null && owner.capability == owner.type && owner.type in supported(stage) &&
                owner.targets.toSet() == instance.targets.toSet() && owner.targets.distinct().size == owner.targets.size &&
                owner.targets.all { it.kind == "mesh" } &&
                owner.plugin?.id == "builtin.${java.util.HexFormat.of().formatHex(owner.type.encodeToByteArray())}.v${owner.version}" &&
                (if (owner.type == "rig.scaffold") JsonPrimitive(owner.instanceId) == scaffold else owner.config["scaffold"] == scaffold)
        }) { "Source brow requires unique identified built-in providers on its complete target scope" }
        fun provider(type: String) = requireNotNull(providers.singleOrNull { it.type == type }).also {
            require(acceptsVersion(stage, type, it.version)) { "Incompatible source brow provider: $type@${it.version}" }
        }
        required(stage).forEach(::provider)
        val tuple = browOrder.take(tuples(stage).first().size).map { provider(it).version }
        require(tuple in tuples(stage)) { "Source brow requires one coherent fitted/shape/lid/blink version tuple" }
        val brow = JsonPrimitive(provider("brow/fitted").instanceId)
        val eye = JsonPrimitive(provider("eye/fitted").instanceId)
        require(instance.config["base"] == brow && browOrder.drop(1).filter { it in required(stage) }.all {
            provider(it).config["base"] == brow
        }) { "Source brow requires its exact fitted brow owner" }
        if (stage != Stage.SHAPE && stage != Stage.LID) require(
            provider("brow.lid-coupling").config["dependencies"]?.jsonObject?.get("eye/fitted") == eye)
        if (stage == Stage.LID) require(instance.config["dependencies"]?.jsonObject?.get("eye/fitted") == eye)
        val mouth = providers.singleOrNull { it.type == "mouth/fitted" }?.instanceId?.let(::JsonPrimitive)
        for ((type, version) in optionalVersions) {
            val owners = providers.filter { it.type == type }
            require(owners.size <= 1 && owners.all { owner -> owner.version == version && when {
                type in eyeAddons -> owner.config["base"] == eye
                type.startsWith("mouth.") -> mouth != null && owner.config["base"] == mouth
                else -> true
            } }) { "Source brow requires compatible $type@$version providers" }
        }
        require(providers.filter { it.type in required(stage) }.map { it.type } == required(stage).toList()) {
            "Source brow requires the canonical required provider order"
        }
    }

    fun captured(input: ModifierEvaluationInput, instance: RigModifierInstance, stage: Stage): List<ModifierUpstreamCapability> {
        val context = input.context
        val captured = context.values.getValue("providers").jsonArray.map {
            Json.decodeFromJsonElement(ModifierUpstreamCapability.serializer(), it)
        }
        require(input.upstream.all { it.type in supported(stage) && (it.capability == it.type ||
            it.type == "face/fitted" && it.capability == "face/legacy") }) { "Unrecognized source brow capability" }
        require(input.upstream.map { it.copy(capability = it.type) }.distinct().toSet() == captured.toSet()) {
            "Source brow requires its exact captured providers, aliases and packages"
        }
        validate(captured, instance, stage)
        if (stage != Stage.LID && captured.single { it.type == "brow/fitted" }.version == 5) {
            val layers = context.values.getValue("source_ancestry").jsonObject.getValue("layers").jsonArray.map { it.jsonObject }
            require(layers.count { it.getValue("tag") == JsonPrimitive("MOUTH") } == 1 &&
                layers.none { it.getValue("tag") == JsonPrimitive("MOUTH_OPEN") } &&
                layers.filter { it.getValue("tag").jsonPrimitive.content in setOf("EYEBROW", "EYEWHITE", "EYELASH") }
                    .all { it.getValue("role") == JsonNull }) {
                "The fitted v5 source brow chain requires whole-mouth and ordinary brow, white and lash materials"
            }
        }
        val packages = context.values.getValue("provider_packages").jsonObject.mapValues {
            Json.decodeFromJsonElement(ModifierPluginReference.serializer(), it.value)
        }
        require(packages.keys == captured.mapTo(hashSetOf()) { requireNotNull(it.instanceId) } &&
            captured.all { it.plugin == packages.getValue(requireNotNull(it.instanceId)) }) {
            "Source brow requires complete captured package references"
        }
        return captured
    }
}
