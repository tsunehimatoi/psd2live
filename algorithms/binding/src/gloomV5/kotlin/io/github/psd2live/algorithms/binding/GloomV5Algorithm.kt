package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*

/** Composes Gloom over the actual source-only Spring10 and Cheek5 provider chain. */
class GloomV5Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        require(instance.version == VERSION && instance.targets.isNotEmpty() && instance.targets.all { it.kind == "mesh" } &&
            instance.targets.distinct().size == instance.targets.size)
        val context = input.context
        require(context.values.keys == fields && context.values.getValue("schema") == JsonPrimitive("expression.gloom/5")) {
            "Gloom v5 requires source measurements, not binding projections"
        }
        val captured = context.values.getValue("providers").jsonArray.map {
            Json.decodeFromJsonElement(ModifierUpstreamCapability.serializer(), it)
        }
        val upstream = input.upstream
        require(upstream.all { it.type in supported && (it.capability == it.type ||
            it.type == "face/fitted" && it.capability == "face/legacy") }) {
            "Gloom v5 rejects unrecognized upstream capabilities"
        }
        val actual = upstream.map { it.copy(capability = it.type) }.distinct()
        require(actual.toSet() == captured.toSet()) { "Gloom v5 requires its exact captured providers, aliases and packages" }
        validateProviders(captured, instance)
        val owners = captured.map { owner -> RigModifierInstance(requireNotNull(owner.instanceId), owner.type, owner.version,
            owner.targets, config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin) }
        val face = captured.single { it.type == "face/fitted" }
        val cheek = captured.single { it.type == "expression.cheek" }
        val cheekStrength = strength(cheek.config)
        val amount = strength(instance.config)
        val declared = Parameter(PARAMETER, context.values.getValue("parameter_name").jsonPrimitive.content, 0f, 1f, 0f)
        val parameters = input.parameters
        val existingParameter = input.parameter(PARAMETER)
        require(existingParameter == null || existingParameter.copy(name = declared.name) == declared) { "Incompatible gloom parameter" }
        val additions = if (existingParameter == null) listOf(declared) else emptyList()
        val controls = parameters + additions
        val mouthArtwork = context.values.getValue("mouth_artwork").jsonArray.map { it.jsonObject }
        require(mouthArtwork.isNotEmpty() && mouthArtwork.all { art ->
            art.keys == setOf("id", "tag", "role") && art.getValue("id").jsonPrimitive.content.isNotBlank() &&
                art.getValue("tag").jsonPrimitive.content in setOf("MOUTH", "MOUTH_OPEN", "MOUTH_CLOSE")
        } && mouthArtwork.map { it.getValue("id") }.distinct().size == mouthArtwork.size)
        require(mouthArtwork.any { it.getValue("tag").jsonPrimitive.content in setOf("MOUTH", "MOUTH_OPEN") &&
            it.getValue("role") == JsonNull } && mouthArtwork.none {
            it.getValue("tag") == JsonPrimitive("MOUTH_CLOSE") ||
                it.getValue("role").jsonPrimitive.contentOrNull in setOf("UPPER_LIP", "LOWER_LIP", "UPPER_LINE", "LOWER_LINE")
        }) { "Gloom v5 requires whole open-mouth artwork without separate closed or layered drawings" }
        val hasMouthLayer = mouthArtwork.any { it.getValue("tag") == JsonPrimitive("MOUTH") }
        val groups = context.values.getValue("groups").jsonArray.map {
            MouthFittedInputs.decode(ModifierAlgorithmContext(it.jsonObject, context.resources))
        }
        require(groups.isNotEmpty() && groups.size <= 2 && groups.all { group ->
            val source = group.source
            val vertices = group.sourceVertices
            source != null && group.layers.isNotEmpty() && group.closedMouth == hasMouthLayer && source.fit.layered == null && vertices != null &&
                group.head?.provider == face.instanceId && group.layers.keys == vertices.targets.keys &&
                group.layers.values.map { it.tag == "FACE_DETAIL" }.distinct().size == 1
        }) { "Gloom v5 requires whole open-mouth source measurements" }
        require(groups.map { it.layers.values.first().tag == "FACE_DETAIL" }.distinct().size == groups.size)
        for (group in groups) {
            checkpoint()
            val vertices = requireNotNull(group.sourceVertices)
            val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
            require(raw.keys == vertices.targets.values.mapTo(hashSetOf()) { it.canonical } &&
                vertices.targets.values.map { it.canonical }.distinct().size == vertices.targets.size) {
                "Gloom v5 rejects extra or repeated source topology"
            }
        }
        val ids = groups.flatMap { it.layers.keys }
        val materials = context.values.getValue("materials").jsonObject
        require(ids.size == ids.distinct().size && ids.toSet() == materials.keys &&
            ids.all { ModifierTarget("mesh", it) in instance.targets })
        // Every binary resource must be a referenced raw topology or mouth-fit measurement. No generated models.
        val resources = groups.flatMap { group -> listOfNotNull(group.sourceVertices?.resource?.digest,
            group.source?.encode()?.values?.get("measurement")?.jsonPrimitive?.content) }.toSet()
        require(context.resources.keys == resources && context.resources.all { (id, resource) -> id == resource.digest }) {
            "Gloom v5 accepts only content-addressed source measurements"
        }
        val highlights = context.values.getValue("eye_highlights").jsonArray.map { it.jsonPrimitive.content }
        require(highlights.distinct().size == highlights.size && highlights.all { ModifierTarget("mesh", it) in instance.targets })
        if (highlights.isNotEmpty()) {
            val eye = captured.singleOrNull { it.type == "eye.highlight" }
            require(eye != null && highlights.all { ModifierTarget("mesh", it) in eye.targets })
            for (id in highlights) require(requireNotNull(input.mesh(id)).channels[Channel.OPACITY]?.axes.orEmpty().any {
                it.parameter in eyeOpen
            }) { "Gloom v5 requires the preceding eye-highlight opacity owner" }
        }
        fun mood(layer: MouthFittedInputs.Layer, gloom: Boolean): SkinReplayV1.Mood {
            val skin = layer.tag != "FACE_DETAIL" || layer.role != "HIGHLIGHT"
            val warmth = when {
                layer.tag == "FACE_DETAIL" && layer.role == "BLUSH" -> .3f
                layer.tag == "FACE" || layer.tag == "NOSE" -> .06f
                layer.tag == "EARS" -> .05f
                else -> 0f
            } * cheekStrength / 100f
            return SkinReplayV1.Mood(MoodBinding.Influence(warmth, (if (skin) 1f else 0f) * amount / 100f,
                layer.role == "HIGHLIGHT"), cheek = true, gloom = gloom)
        }
        val replay = SkinReplayV1.changes(input, instance, groups, materials, owners, controls,
            beforeInfluence = { mood(it, false) }, afterInfluence = { mood(it, true) },
            label = "Gloom v5", checkpoint = checkpoint)
        val tree = if (additions.isEmpty()) null else {
            val before = input.parameterTree
            val roots = before.ifEmpty { parameters.map { ParameterNode.Param(it.id) } }
            val folder = instance.outputId("parameter-folder:ParamGroupFace")
            var found = false
            fun append(nodes: List<ParameterNode>): List<ParameterNode> = nodes.map { node ->
                when {
                    node !is ParameterNode.Group -> node
                    node.id == folder -> node.copy(children = node.children + ParameterNode.Param(PARAMETER)).also { found = true }
                    else -> node.copy(children = append(node.children))
                }
            }
            val next = append(roots).let { if (found) it else it + ParameterNode.Group(folder,
                context.values.getValue("group_name").jsonPrimitive.content, true, listOf(ParameterNode.Param(PARAMETER))) }
            ModifierFieldChange(before, next)
        }
        return replay.copy(parameters = ModifierObjectChanges(added = additions, reused = listOfNotNull(existingParameter)), parameterTree = tree)
    }

    companion object {
        const val TYPE = "expression.gloom"
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.65787072657373696f6e2e676c6f6f6d.v5/gloom"
        const val PARAMETER = "ParamGloom"
        val requiredVersions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3,
            "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 3, "mouth.outline" to 7, "mouth.vowel" to 6,
            "mouth.capture" to 5, "mouth.jaw" to 7, "mouth.spring" to 10, "expression.cheek" to 5)
        val requires = requiredVersions.keys
        val mouthModules = setOf("mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.spring")
        val optionalVersions = mapOf("hair/legacy" to setOf(2), "hair/chains" to setOf(2), "brow.shape" to setOf(6), "brow.lid-coupling" to setOf(8),
            "brow.blink" to setOf(1, 6), "brow.spring" to setOf(1, 7), "mouth.cheek-lid" to setOf(9),
            "mouth.tongue" to setOf(5), "mouth.interior-depth" to setOf(3), "eye.smile" to setOf(3), "eye.wide" to setOf(3),
            "eye.gaze-lid" to setOf(3), "eye.emotion" to setOf(3), "eye.highlight" to setOf(3), "eye.aftershock" to setOf(4),
            "eye.squash" to setOf(4), "eye.lid-fade" to setOf(3), "face.skin-clip" to setOf(1),
            "eye.see-through" to setOf(1), "brow.see-through" to setOf(1), "body.bust" to setOf(2))
        val unsupported = setOf(TYPE, "expression.toggles", "face/legacy", "eye/legacy", "brow/legacy", "mouth/legacy", "face.depth-turn")
        val supported = requires + optionalVersions.keys
        val observed = supported + unsupported
        val writes = setOf("mesh", "deformer", "parameter", "hierarchy")
        private val fields = setOf("schema", "providers", "groups", "mouth_artwork", "materials", "eye_highlights", "parameter_name", "group_name")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private fun strength(config: JsonObject) = (config["values"]?.jsonObject?.get("strength")?.jsonPrimitive?.float ?: 100f)
            .also { require(it in 0f..200f) { "Mood strength must be within 0..200" } }

        fun validateProviders(providers: List<ModifierUpstreamCapability>, instance: RigModifierInstance) {
            require(providers.map { it.instanceId }.distinct().size == providers.size && providers.all {
                it.instanceId != null && it.type == it.capability && it.type in supported &&
                    it.targets.all { target -> target.kind == "mesh" } &&
                    it.plugin?.id == "builtin.${java.util.HexFormat.of().formatHex(it.type.encodeToByteArray())}.v${it.version}"
            }) { "Gloom v5 requires unique recognized source providers" }
            val scaffold = instance.config.getValue("scaffold")
            fun required(type: String): ModifierUpstreamCapability = requireNotNull(providers.singleOrNull { it.type == type }).also {
                require(it.version == requiredVersions.getValue(type) && it.targets.containsAll(instance.targets) &&
                    (if (type == "rig.scaffold") JsonPrimitive(it.instanceId) == scaffold else it.config["scaffold"] == scaffold)) {
                    "Gloom v5 requires $type@${requiredVersions.getValue(type)} on its full target scope"
                }
            }
            requiredVersions.keys.forEach(::required)
            require(required("rig.scaffold").targets.toSet() == instance.targets.toSet())
            val mouth = JsonPrimitive(required("mouth/fitted").instanceId)
            val eye = JsonPrimitive(required("eye/fitted").instanceId)
            val brow = JsonPrimitive(required("brow/fitted").instanceId)
            require(mouthModules.all { required(it).config["base"] == mouth } &&
                required("mouth.spring").config["dependencies"]?.jsonObject?.get("mouth.jaw") == JsonPrimitive(required("mouth.jaw").instanceId)) {
                "Gloom v5 requires the same fitted mouth, jaw and spring"
            }
            for ((type, versions) in optionalVersions) {
                val chosen = providers.filter { it.type == type }
                require(chosen.size <= 1 && chosen.all { owner ->
                    owner.version in versions && owner.config["scaffold"] == scaffold && owner.targets.any(instance.targets::contains) &&
                        when {
                            type == "body.bust" -> owner.targets.toSet() == instance.targets.toSet() &&
                                owner.config["dependencies"]?.jsonObject?.get("body/legacy") == JsonPrimitive(required("body/legacy").instanceId) &&
                                owner.config["dependencies"]?.jsonObject?.get("face/legacy") == JsonPrimitive(required("face/fitted").instanceId)
                            type in setOf("eye.see-through", "brow.see-through") -> true
                            type.startsWith("eye.") -> owner.config["base"] == eye && owner.targets.containsAll(instance.targets)
                            type.startsWith("brow.") -> owner.config["base"] == brow && owner.targets.containsAll(instance.targets)
                            type == "mouth.cheek-lid" -> owner.config["base"] == mouth && owner.targets.containsAll(instance.targets) &&
                                owner.config["dependencies"]?.jsonObject?.get("eye/fitted") == eye
                            type.startsWith("mouth.") -> owner.config["base"] == mouth
                            else -> true
                        }
                }) { "Gloom v5 requires compatible $type source providers" }
            }
            providers.singleOrNull { it.type == "brow.lid-coupling" }?.let {
                require(it.config["dependencies"]?.jsonObject?.get("eye/fitted") == eye)
            }
            providers.singleOrNull { it.type == "mouth.tongue" }?.let {
                require(it.config["dependencies"]?.jsonObject?.get("mouth.capture") == JsonPrimitive(required("mouth.capture").instanceId))
            }
        }
    }
}

object GloomV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (GloomV5Algorithm.ENTRYPOINT to GloomV5Algorithm.VERSION) to GloomV5Algorithm()))
}
