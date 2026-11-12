package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode

/** Explicit source-only spring candidate. The worker owns both source states and preserves allocation. */
class MouthSpringV10Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        require(instance.version == VERSION && instance.targets.isNotEmpty() && instance.targets.all { it.kind == "mesh" } &&
            instance.targets.distinct().size == instance.targets.size)
        val context = input.context
        require(context.values.keys == fields && context.values.getValue("schema") == JsonPrimitive("mouth.spring/10")) {
            "Mouth spring v10 requires source measurements, not binding projections"
        }
        val captured = context.values.getValue("providers").jsonArray.map {
            Json.decodeFromJsonElement(ModifierUpstreamCapability.serializer(), it)
        }
        // No capability may be silently ignored, including an otherwise valid owner's extra capability.
        require(input.upstream.all { it.capability == it.type && it.type in observed }) {
            "Mouth spring v10 rejects unknown source capabilities"
        }
        require(input.upstream.toSet() == captured.toSet()) {
            "Mouth spring v10 requires its exact captured providers, aliases and packages"
        }
        validateProviders(captured, instance)
        val owners = captured.map { owner -> RigModifierInstance(requireNotNull(owner.instanceId), owner.type, owner.version,
            owner.targets, config = owner.config, outputAliases = owner.outputAliases, plugin = owner.plugin) }
        val face = captured.single { it.type == "face/fitted" }
        val artwork = context.values.getValue("mouth_artwork").jsonArray.map { it.jsonObject }
        require(artwork.isNotEmpty() && artwork.all { art ->
            art.keys == setOf("id", "tag", "role") && art.getValue("id").jsonPrimitive.content.isNotBlank() &&
                art.getValue("tag").jsonPrimitive.content in setOf("MOUTH", "MOUTH_OPEN", "MOUTH_CLOSE")
        } && artwork.map { it.getValue("id") }.distinct().size == artwork.size)
        require(artwork.any { it.getValue("tag").jsonPrimitive.content in setOf("MOUTH", "MOUTH_OPEN") &&
            it.getValue("role") == JsonNull } && artwork.none {
            it.getValue("tag") == JsonPrimitive("MOUTH_CLOSE") ||
                it.getValue("role").jsonPrimitive.contentOrNull in setOf("UPPER_LIP", "LOWER_LIP", "UPPER_LINE", "LOWER_LINE")
        }) { "Mouth spring v10 requires whole open-mouth artwork without separate closed or layered drawings" }
        val hasMouthLayer = artwork.any { it.getValue("tag") == JsonPrimitive("MOUTH") }
        val groups = context.values.getValue("groups").jsonArray.map {
            checkpoint()
            MouthFittedInputs.decode(ModifierAlgorithmContext(it.jsonObject, context.resources))
        }
        require(groups.isNotEmpty() && groups.size <= 2 && groups.all { group ->
            val source = group.source
            val vertices = group.sourceVertices
            source != null && group.closedMouth == hasMouthLayer && source.fit.layered == null && vertices != null &&
                group.head?.provider == face.instanceId && group.layers.isNotEmpty() && group.layers.keys == vertices.targets.keys &&
                group.layers.values.map { it.tag == "FACE_DETAIL" }.distinct().size == 1
        }) { "Mouth spring v10 requires whole open-mouth source measurements" }
        require(groups.map { it.layers.values.first().tag == "FACE_DETAIL" }.distinct().size == groups.size)
        val ids = groups.flatMap { it.layers.keys }
        val materials = context.values.getValue("materials").jsonObject
        require(ids.size == ids.distinct().size && ids.toSet() == materials.keys &&
            ids.all { ModifierTarget("mesh", it) in instance.targets })
        require(materials.values.map { it.jsonObject.getValue("source_id").jsonPrimitive.content }.let {
            it.all(String::isNotBlank) && it.distinct().size == it.size
        }) { "Mouth spring v10 requires unique authored source identities" }
        val resources = groups.flatMap { group -> listOfNotNull(group.sourceVertices?.resource?.digest,
            group.source?.encode()?.values?.get("measurement")?.jsonPrimitive?.content) }.toSet()
        require(context.resources.keys == resources && context.resources.all { (id, resource) -> id == resource.digest }) {
            "Mouth spring v10 accepts only content-addressed source measurements"
        }
        val declared = parameters(context.values.getValue("name").jsonPrimitive.content).single()
        val parameters = input.parameters
        require(setOf(OPEN, FORM).all { axis -> parameters.any { it.id == axis && !it.blend && it.default == 0f } })
        val existing = input.parameter(SPRING)
        require(existing == null || existing.copy(name = declared.name) == declared) { "Incompatible mouth spring parameter" }
        val additions = if (existing == null) listOf(declared) else emptyList()
        val controls = parameters + additions
        val updates = MouthSpringV10Replay.changes(input, instance, groups, materials, owners, controls, checkpoint)
        val tree = input.parameterTree
        val model = PuppetIr.toPuppet(RigIR(input.canvas, parameters, parameterTree = tree))
        val folder = PuppetParameterNode.Group(ParameterGroupId(instance.outputId("parameter-folder:ParamGroupPhysics")),
            context.values.getValue("folder").jsonPrimitive.content, true, listOf(PuppetParameterNode.Param(MouthRig.SPRING)))
        val updatedTree = BindingMerge.appendTree(model, listOf(folder), additions.mapTo(hashSetOf()) { ParameterId(it.id) },
            instance.outputAliases.keys.any { it.startsWith("parameter-folder:") })
        val convertedTree = PuppetIr.toIr(model.copy(parameterTree = updatedTree)).parameterTree
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions, reused = listOfNotNull(existing)),
            meshes = ModifierObjectChanges(updated = updates),
            parameterTree = convertedTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) },
            physicsGroups = physics(input, instance, controls.mapTo(hashSetOf()) { it.id }, context))
    }

    private fun physics(input: ModifierEvaluationInput, instance: RigModifierInstance, available: Set<String>,
                        context: ModifierAlgorithmContext): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { it == PHYSICS }) { "${instance.id}: unknown module physics group" }
        val edit = overrides[PHYSICS]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: RigPhysicsEdit(
            id = PHYSICS, name = context.values.getValue("physics").jsonPrimitive.content,
            inputs = listOf(PhysicsInput(OPEN, 80f, PhysicsSourceType.X), PhysicsInput(FORM, 20f, PhysicsSourceType.X)),
            outputs = listOf(PhysicsOutput(SPRING, 2, 1.2f)),
            segments = listOf(PhysicsSegment(1f, 0.9f, 0.5f, 1.6f), PhysicsSegment(1.1f, 0.85f, 0.4f, 1.8f)),
            normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f))
        require(edit.parameters.all { it in available } && edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() &&
            edit.inputs.none { source -> edit.outputs.any { it.parameter == source.parameter } }) { "${instance.id}: invalid mouth spring physics" }
        val requested = PhysicsGroups.fromSetting(edit.copy(id = instance.outputId("physics:$PHYSICS")))
        val before = input.physics
        val compatible = before.groups.firstOrNull { it.copy(id = requested.id, name = requested.name) == requested }
        if (compatible != null) return ModifierObjectChanges(reused = listOf(compatible))
        PhysicsGroups.validateAppend(before, listOf(requested))
        return ModifierObjectChanges(added = listOf(requested))
    }

    companion object {
        const val TYPE = "mouth.spring"
        const val VERSION = 10
        const val ENTRYPOINT = "builtin.6d6f7574682e737072696e67.v10/spring"
        private const val SPRING = "ParamMouthSpring"
        private const val OPEN = "ParamMouthOpenY"
        private const val FORM = "ParamMouthForm"
        private const val PHYSICS = "PhysicsMouthSpring"
        val requiredVersions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3,
            "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 3, "mouth.outline" to 7,
            "mouth.vowel" to 6, "mouth.capture" to 5, "mouth.jaw" to 7)
        val requires = requiredVersions.keys
        val mouthModules = setOf("mouth.vowel", "mouth.capture", "mouth.jaw")
        val optionalVersions = mapOf("hair/legacy" to setOf(2), "hair/chains" to setOf(2),
            "brow.shape" to setOf(6), "brow.lid-coupling" to setOf(8), "brow.blink" to setOf(1, 6),
            "brow.spring" to setOf(1, 7), "mouth.cheek-lid" to setOf(9), "mouth.tongue" to setOf(5),
            "mouth.interior-depth" to setOf(3), "eye.smile" to setOf(3), "eye.wide" to setOf(3),
            "eye.gaze-lid" to setOf(3), "eye.emotion" to setOf(3), "eye.highlight" to setOf(3),
            "eye.aftershock" to setOf(4), "eye.squash" to setOf(4), "eye.lid-fade" to setOf(3),
            "face.skin-clip" to setOf(1), "eye.see-through" to setOf(1), "brow.see-through" to setOf(1),
            "body.bust" to setOf(2))
        val observed = requires + optionalVersions.keys
        val writes = setOf("mesh", "parameter", "hierarchy", "physics")
        private val fields = setOf("schema", "providers", "groups", "mouth_artwork", "materials", "name", "folder", "physics")

        fun parameters(name: String): List<Parameter> = listOf(Parameter(SPRING, name, -1f, 1f, 0f,
            blend = true, keys = Floats.values(-1f, 0f, 1f)))

        fun validateProviders(providers: List<ModifierUpstreamCapability>, instance: RigModifierInstance) {
            require(providers.map { it.instanceId }.distinct().size == providers.size && providers.all {
                it.instanceId != null && it.type == it.capability && it.type in observed &&
                    it.targets.all { target -> target.kind == "mesh" } &&
                    it.plugin?.id == "builtin.${java.util.HexFormat.of().formatHex(it.type.encodeToByteArray())}.v${it.version}"
            }) { "Mouth spring v10 requires unique recognized source providers and packages" }
            val scaffold = instance.config.getValue("scaffold")
            fun required(type: String): ModifierUpstreamCapability = requireNotNull(providers.singleOrNull { it.type == type }).also {
                require(it.version == requiredVersions.getValue(type) && it.targets.containsAll(instance.targets) &&
                    (if (type == "rig.scaffold") JsonPrimitive(it.instanceId) == scaffold else it.config["scaffold"] == scaffold)) {
                    "Mouth spring v10 requires $type@${requiredVersions.getValue(type)} on its full target scope"
                }
            }
            requiredVersions.keys.forEach(::required)
            require(required("rig.scaffold").targets.toSet() == instance.targets.toSet())
            val mouth = JsonPrimitive(required("mouth/fitted").instanceId)
            val eye = JsonPrimitive(required("eye/fitted").instanceId)
            val brow = JsonPrimitive(required("brow/fitted").instanceId)
            require(instance.config["base"] == mouth && mouthModules.all { required(it).config["base"] == mouth } &&
                instance.config["dependencies"]?.jsonObject?.get("mouth.jaw") == JsonPrimitive(required("mouth.jaw").instanceId)) {
                "Mouth spring v10 requires the same fitted mouth and jaw"
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
                }) { "Mouth spring v10 requires compatible $type source providers" }
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

object MouthSpringV10Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthSpringV10Algorithm.ENTRYPOINT to MouthSpringV10Algorithm.VERSION) to MouthSpringV10Algorithm()))
}
