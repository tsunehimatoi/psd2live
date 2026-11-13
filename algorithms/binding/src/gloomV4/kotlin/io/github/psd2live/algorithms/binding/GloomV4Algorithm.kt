package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/** Reconstructs skin motion from source measurements, then owns only Gloom's channel/allocation changes. */
class GloomV4Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        require(instance.version == VERSION && instance.targets.isNotEmpty() && instance.targets.all { it.kind == "mesh" } &&
            instance.targets.distinct().size == instance.targets.size)
        val context = input.context
        require(context.values.keys == fields && context.values.getValue("schema") == JsonPrimitive("expression.gloom/4")) {
            "Gloom v4 requires source measurements, not binding projections"
        }
        val captured = context.values.getValue("providers").jsonArray.map {
            Json.decodeFromJsonElement(ModifierUpstreamCapability.serializer(), it)
        }
        val actual = input.upstream.filter { it.capability in observed }.map { it.copy(capability = it.type) }.distinct()
        require(actual.toSet() == captured.toSet()) { "Gloom v4 requires its exact captured providers, aliases and packages" }
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
        }) { "Gloom v4 requires whole open-mouth artwork without separate closed or layered drawings" }
        val hasMouthLayer = mouthArtwork.any { it.getValue("tag") == JsonPrimitive("MOUTH") }
        val groups = context.values.getValue("groups").jsonArray.map {
            MouthFittedInputs.decode(ModifierAlgorithmContext(it.jsonObject, context.resources))
        }
        require(groups.isNotEmpty() && groups.size <= 2 && groups.all { group ->
            val source = group.source
            val vertices = group.sourceVertices
            source != null && group.closedMouth == hasMouthLayer && source.fit.layered == null && vertices != null &&
                group.head?.provider == face.instanceId && group.layers.keys == vertices.targets.keys
        }) { "Gloom v4 requires whole open-mouth source measurements" }
        val ids = groups.flatMap { it.layers.keys }
        val materials = context.values.getValue("materials").jsonObject
        require(ids.size == ids.distinct().size && ids.toSet() == materials.keys &&
            ids.all { ModifierTarget("mesh", it) in instance.targets })
        // Every binary resource must be a referenced raw topology or mouth-fit measurement. No generated models.
        val resources = groups.flatMap { group -> listOfNotNull(group.sourceVertices?.resource?.digest,
            group.source?.encode()?.values?.get("measurement")?.jsonPrimitive?.content) }.toSet()
        require(context.resources.keys == resources && context.resources.all { (id, resource) -> id == resource.digest }) {
            "Gloom v4 accepts only content-addressed source measurements"
        }
        val highlights = context.values.getValue("eye_highlights").jsonArray.map { it.jsonPrimitive.content }
        require(highlights.distinct().size == highlights.size && highlights.all { ModifierTarget("mesh", it) in instance.targets })
        if (highlights.isNotEmpty()) {
            val eye = captured.singleOrNull { it.type == "eye.highlight" }
            require(eye != null && highlights.all { ModifierTarget("mesh", it) in eye.targets })
            for (id in highlights) require(requireNotNull(input.mesh(id)).channels[Channel.OPACITY]?.axes.orEmpty().any {
                it.parameter in eyeOpen
            }) { "Gloom v4 requires the preceding eye-highlight opacity owner" }
        }
        // Ordinary-axis evidence comes from real upstream meshes/warps. Never synthesize axes to prevent retyping.
        val witnesses = instance.targets.map { requireNotNull(input.mesh(it.id)) }
        val witnessWarps = instance.targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
        val updated = mutableListOf<ModifierObjectUpdate<Mesh>>()
        val addedCarriers = linkedMapOf<String, Deformer>()
        val carrierOrder = hashMapOf<String, Int>()
        val meshOrder = input.meshIds.withIndex().associate { it.value to it.index }
        val updatedCarriers = linkedMapOf<String, ModifierObjectUpdate<Deformer>>()
        for (group in groups) {
            val mouth = requireNotNull(group.source).rig()
            require(mouth.options.jaw && mouth.options.spring && mouth.options.capture && mouth.options.vowels)
            val turn = requireNotNull(group.head).rig(checkpoint)
            val vertices = requireNotNull(group.sourceVertices)
            val rawMeshes = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
            for ((id, layer) in group.layers) {
                checkpoint()
                require(layer.tag in setOf("FACE", "NOSE", "EARS", "FACE_DETAIL") && layer.preset)
                val live = requireNotNull(input.mesh(id))
                val sourceMaterial = materials.getValue(id).jsonObject
                require(sourceMaterial.keys == setOf("name", "opacity") &&
                    live.opacity == sourceMaterial.getValue("opacity").jsonPrimitive.float) {
                    "Gloom v4 requires the captured source opacity for $id"
                }
                val target = vertices.targets.getValue(id)
                val raw = rawMeshes.getValue(target.canonical)
                val frame = layer.frame
                val positions = FloatArray(raw.rigPositions.size) { i ->
                    if (i % 1024 == 0) checkpoint()
                    if (target.parent == null) raw.rigPositions[i]
                    else if (i % 2 == 0) (raw.rigPositions[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
                    else (raw.rigPositions[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
                }
                val ancestry = input.parentsOfMesh(id)
                val parent = target.parent?.let { canonical ->
                    val candidates = owners.filter { ModifierTarget("mesh", id) in it.targets }.map {
                        ScaffoldBinding.deformerId(it, id, canonical, shared = true)
                    }.distinct().filter { candidate -> ancestry.any { it.id == candidate } }
                    require(candidates.size == 1) { "Gloom v4 cannot resolve the source parent for $id" }
                    candidates.single()
                }
                require(parent != null) { "Gloom v4 requires bound source skin" }
                val local = ancestry.takeWhile { it.id != parent }
                val originalParent = ancestry.single { it.id == parent }
                val nativeParent = stripShapes(originalParent).let {
                    when (it) {
                        is Deformer.Warp -> it.copy(parent = null)
                        is Deformer.Rotation -> it.copy(parent = null)
                    }
                }
                val lowerFace = (layer.role == null || layer.role == "SHADOW") &&
                    (layer.tag == "FACE" || layer.tag == "NOSE" || layer.tag == "FACE_DETAIL" &&
                        mouth.fit.toMouth(layer.centroidX, layer.centroidY).second > 0f)
                val motion = (if (lowerFace) mouth.faceShapes(raw.rigPositions, frame, group.chin, layer.tag == "NOSE",
                    ParameterId("ParamMouthOpenY"), MouthRig.SPRING) else emptyList()) +
                    (if (layer.turnsWithHeadMesh) DepthTurnMotion.shell(layer.tag, layer.role)?.let { shell ->
                        turn.shapes(raw.rigPositions, frame, shell, ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
                    }.orEmpty() else emptyList())
                val ordinary = motion.map { it.parameterId.raw }.filterTo(hashSetOf()) { axis ->
                    controls.singleOrNull { it.id == axis }?.blend == false
                }
                val ordinaryMeshes = witnesses.filter { it.id != id && it.offsets?.axes.orEmpty().any { it.parameter in ordinary } }
                    .map { it.copy(parent = null, shapes = emptyList(), channels = emptyMap()) }
                val ordinaryWarps = witnessWarps.filterIsInstance<Deformer.Warp>().filter {
                    it.id != parent && it !in local && it.lattice?.axes.orEmpty().any { axis -> axis.parameter in ordinary }
                }.map { it.copy(parent = null, shapes = emptyList(), channels = emptyMap()) }
                val evidenced = ordinaryMeshes.flatMap { it.offsets?.axes.orEmpty() } + ordinaryWarps.flatMap { it.lattice?.axes.orEmpty() } +
                    (nativeParent as? Deformer.Warp)?.lattice?.axes.orEmpty()
                require(ordinary.all { axis -> evidenced.any { it.parameter == axis } }) {
                    "Gloom v4 requires live ordinary-axis witnesses for $id"
                }
                val skin = layer.tag != "FACE_DETAIL" || layer.role != "HIGHLIGHT"
                val warmth = when {
                    layer.tag == "FACE_DETAIL" && layer.role == "BLUSH" -> .3f
                    layer.tag == "FACE" || layer.tag == "NOSE" -> .06f
                    layer.tag == "EARS" -> .05f
                    else -> 0f
                } * cheekStrength / 100f
                fun lower(gloom: Boolean): PuppetModel {
                    val zero = KeyGrid(emptyList(), listOf(KeyCell(Ints.wrap(IntArray(0)),
                        MeshOffsets(Floats.wrap(FloatArray(positions.size))))))
                    val native = live.copy(id = target.canonical, name = sourceMaterial.getValue("name").jsonPrimitive.content,
                        parent = parent, geometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
                        offsets = zero, shapes = emptyList(), channels = emptyMap())
                    val model = PuppetIr.toPuppet(RigIR(input.canvas, controls, meshes = listOf(native) + ordinaryMeshes,
                        deformers = listOf(nativeParent) + ordinaryWarps))
                    val drawable = model.drawables.first()
                    val shaded = MoodBinding.applyChannels(drawable,
                        MoodBinding.Influence(warmth, (if (skin) 1f else 0f) * amount / 100f, layer.role == "HIGHLIGHT"), true, gloom)
                    val pose = { axis: ParameterId -> if (axis.raw in eyeOpen) 1f else 0f }
                    val rest = meshGridDefaultDeltas(shaded, pose)
                    val opacity = shaded.channelGrids.scalarAt(FormChannel.OPACITY, shaded.opacity, pose)
                    val order = shaded.channelGrids.scalarAt(FormChannel.DRAW_ORDER, shaded.drawOrder, pose)
                    val multiply = shaded.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, shaded.multiplyColor, pose)
                    val screen = shaded.channelGrids.colorAt(FormChannel.SCREEN_COLOR, shaded.screenColor, pose)
                    val shaped = shaded.copy(blendShapes = motion.map { binding -> binding.copy(forms = binding.forms.map { form ->
                        form?.let {
                            val base = rest?.takeIf { deltas -> deltas.size == it.positionDeltas.size && binding.parameterId.raw in headAxes }
                            val deltas = if (base == null) it.positionDeltas else FloatArray(base.size) { i -> it.positionDeltas[i] + base[i] }
                            MeshForm(deltas, order, opacity, multiply, screen)
                        }
                    }) })
                    val lowered = BlendShapeLowering({ head, name ->
                        (if (head) group.turnCarrierName else group.shapeCarrierName).replace("{meshName}", name)
                    }, checkpoint).lawful(model.copy(drawables = listOf(shaped) + model.drawables.drop(1)))
                    require(lowered.parameters == model.parameters) { "Gloom v4 cannot retype upstream parameters" }
                    return lowered.copy(drawables = listOf(lowered.drawables.first()), deformers = lowered.deformers.filterNot {
                        it.id.raw == parent || ordinaryWarps.any { warp -> warp.id == it.id.raw }
                    })
                }
                val before = lower(false)
                val after = lower(true)
                val aliases = linkedMapOf<DeformerId, DeformerId>()
                val liveCarriers = local.associateBy { it.id }
                for (carrier in before.deformers) {
                    val candidates = owners.filter { ModifierTarget("mesh", id) in it.targets }.map {
                        ScaffoldBinding.deformerId(it, id, carrier.id.raw, shared = true)
                    }.distinct().filter { it in liveCarriers }
                    require(candidates.size == 1) { "Gloom v4 cannot resolve a preceding skin carrier for $id: ${carrier.id}" }
                    aliases[carrier.id] = DeformerId(candidates.single())
                }
                require(aliases.values.map { it.raw }.toSet() == liveCarriers.keys && aliases.values.distinct().size == aliases.size) {
                    "Gloom v4 requires the complete preceding skin carrier branch for $id"
                }
                require(after.deformers.map { it.id }.containsAll(before.deformers.map { it.id })) {
                    "Gloom v4 cannot retire a preceding carrier"
                }
                for (carrier in after.deformers) aliases.putIfAbsent(carrier.id,
                    DeformerId(ScaffoldBinding.deformerId(instance, id, carrier.id.raw, shared = true)))
                require(aliases.values.distinct().size == aliases.size) { "Gloom v4 carrier aliases must be distinct" }
                val remap = ModelIdRemap(deformers = aliases)
                for (carrier in before.deformers) {
                    val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
                    require(liveCarriers.getValue(expected.id) == expected) {
                        "Gloom v4 requires the exact preceding skin carrier: ${expected.id}"
                    }
                }
                fun converted(model: PuppetModel): Mesh = PuppetIr.meshToIr(remap.drawable(model.drawables.single())).copy(id = id)
                val expected = converted(before)
                require(live.parent == expected.parent && live.geometry == expected.geometry && live.offsets == expected.offsets &&
                    live.shapes == expected.shapes && live.channels == expected.channels) {
                    fun floats(a: Floats?, b: Floats?): String {
                        if (a == b) return "equal"
                        if (a == null || b == null) return "null=${a == null}/${b == null}"
                        val first = (0 until minOf(a.size, b.size)).firstOrNull { a[it].toBits() != b[it].toBits() }
                        return "size=${a.size}/${b.size}, first=$first" + (first?.let { ", value=${a[it]}/${b[it]}" } ?: "")
                    }
                    "Gloom v4 requires exact preceding skin topology, motion and channels: $id " +
                        "(parent=${live.parent == expected.parent}, geometry=${live.geometry == expected.geometry}, " +
                        "offsets=${live.offsets == expected.offsets}, shapes=${live.shapes == expected.shapes}, channels=${live.channels == expected.channels}); " +
                        "positions=${floats(live.geometry?.positions, expected.geometry?.positions)}, " +
                        "uvs=${floats(live.geometry?.uvs, expected.geometry?.uvs)}, indices=${live.geometry?.indices == expected.geometry?.indices}, " +
                        "frame=$frame, raw=${raw.rigPositions.size}, " +
                        "offsetAxes=${live.offsets?.axes}/${expected.offsets?.axes}, offsetCells=${live.offsets?.cells?.size}/${expected.offsets?.cells?.size}, " +
                        "offsetFirst=${floats(live.offsets?.cells?.firstOrNull()?.form?.deltas, expected.offsets?.cells?.firstOrNull()?.form?.deltas)}, " +
                        "shapeAxes=${live.shapes.map { it.parameter }}/${expected.shapes.map { it.parameter }}, " +
                        "shapeFirst=${floats(live.shapes.firstOrNull()?.shapes?.firstOrNull()?.deltas, expected.shapes.firstOrNull()?.shapes?.firstOrNull()?.deltas)}"
                }
                val result = converted(after)
                for (carrier in after.deformers) {
                    val next = PuppetIr.deformerToIr(remap.deformer(carrier))
                    val previous = liveCarriers[next.id]
                    if (previous == null) {
                        require(input.deformer(next.id) == null && addedCarriers.put(next.id, next) == null) {
                            "Gloom v4 cannot reuse an unrelated carrier id: ${next.id}"
                        }
                        carrierOrder[next.id] = meshOrder.getValue(id)
                    } else if (previous != next) {
                        GloomV4CarrierScope.check(input, previous.id, id, liveCarriers.keys, checkpoint)
                        val change = ModifierObjectUpdate(previous, next)
                        require(updatedCarriers.put(next.id, change)?.let { it == change } != false)
                    }
                }
                val next = live.copy(parent = result.parent, geometry = result.geometry, offsets = result.offsets,
                    shapes = result.shapes, channels = result.channels)
                if (next != live) updated += ModifierObjectUpdate(live, next)
            }
        }
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
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions, reused = listOfNotNull(existingParameter)),
            meshes = ModifierObjectChanges(updated = updated),
            deformers = ModifierObjectChanges(added = addedCarriers.values.sortedBy { carrierOrder.getValue(it.id) },
                updated = updatedCarriers.values.toList()), parameterTree = tree)
    }

    private fun stripShapes(parent: Deformer): Deformer = when (parent) {
        is Deformer.Warp -> parent.copy(shapes = emptyList())
        is Deformer.Rotation -> parent.copy(shapes = emptyList())
    }


    companion object {
        const val TYPE = "expression.gloom"
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.65787072657373696f6e2e676c6f6f6d.v4/gloom"
        const val PARAMETER = "ParamGloom"
        val requiredVersions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3,
            "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 3, "mouth.outline" to 7, "mouth.vowel" to 6,
            "mouth.capture" to 5, "mouth.jaw" to 7, "mouth.spring" to 8, "expression.cheek" to 1)
        val requires = requiredVersions.keys
        val mouthModules = setOf("mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.spring")
        val optionalVersions = mapOf("hair/legacy" to setOf(2), "hair/chains" to setOf(2), "brow.shape" to setOf(6), "brow.lid-coupling" to setOf(8),
            "brow.blink" to setOf(1, 6), "brow.spring" to setOf(1, 7), "mouth.cheek-lid" to setOf(9),
            "mouth.tongue" to setOf(5), "mouth.interior-depth" to setOf(3), "eye.smile" to setOf(3), "eye.wide" to setOf(3),
            "eye.gaze-lid" to setOf(3), "eye.emotion" to setOf(3), "eye.highlight" to setOf(3), "eye.aftershock" to setOf(4),
            "eye.squash" to setOf(4), "eye.lid-fade" to setOf(3), "face.skin-clip" to setOf(1),
            "eye.see-through" to setOf(1), "brow.see-through" to setOf(1), "body.bust" to setOf(2))
        val unsupported = setOf(TYPE, "face/legacy", "eye/legacy", "brow/legacy", "mouth/legacy", "face.depth-turn")
        val observed = requires + optionalVersions.keys + unsupported
        val writes = setOf("mesh", "deformer", "parameter", "hierarchy")
        private val fields = setOf("schema", "providers", "groups", "mouth_artwork", "materials", "eye_highlights", "parameter_name", "group_name")
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
        private fun strength(config: JsonObject) = (config["values"]?.jsonObject?.get("strength")?.jsonPrimitive?.float ?: 100f)
            .also { require(it in 0f..200f) { "Mood strength must be within 0..200" } }

        fun validateProviders(providers: List<ModifierUpstreamCapability>, instance: RigModifierInstance) {
            require(providers.map { it.instanceId }.distinct().size == providers.size && providers.all {
                it.instanceId != null && it.type == it.capability && it.type in observed && it.type !in unsupported
            }) { "Gloom v4 requires unique recognized source providers" }
            val scaffold = instance.config.getValue("scaffold")
            fun required(type: String): ModifierUpstreamCapability = requireNotNull(providers.singleOrNull { it.type == type }).also {
                require(it.version == requiredVersions.getValue(type) && it.targets.containsAll(instance.targets) &&
                    (if (type == "rig.scaffold") JsonPrimitive(it.instanceId) == scaffold else it.config["scaffold"] == scaffold)) {
                    "Gloom v4 requires $type@${requiredVersions.getValue(type)} on its full target scope"
                }
            }
            requiredVersions.keys.forEach(::required)
            require(required("rig.scaffold").targets.toSet() == instance.targets.toSet())
            val mouth = JsonPrimitive(required("mouth/fitted").instanceId)
            val eye = JsonPrimitive(required("eye/fitted").instanceId)
            val brow = JsonPrimitive(required("brow/fitted").instanceId)
            require(mouthModules.all { required(it).config["base"] == mouth } &&
                required("mouth.spring").config["dependencies"]?.jsonObject?.get("mouth.jaw") == JsonPrimitive(required("mouth.jaw").instanceId)) {
                "Gloom v4 requires the same fitted mouth, jaw and spring"
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
                            type.startsWith("mouth.") -> owner.config["base"] == mouth
                            else -> true
                        }
                }) { "Gloom v4 requires compatible $type source providers" }
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

object GloomV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (GloomV4Algorithm.ENTRYPOINT to GloomV4Algorithm.VERSION) to GloomV4Algorithm()))
}

/** Membership is observed only when an existing lowering carrier will actually change. */
object GloomV4CarrierScope {
    fun check(input: ModifierEvaluationInput, carrier: String, mesh: String, branch: Set<String>, checkpoint: () -> Unit) {
        val deformers = input.deformers.groupBy { it.parent }
        val meshes = input.meshes.groupBy { it.parent }
        val pending = ArrayDeque<String>()
        val visited = hashSetOf<String>()
        pending.add(carrier)
        while (pending.isNotEmpty()) {
            checkpoint()
            val parent = pending.removeFirst()
            require(visited.add(parent)) { "Cyclic gloom carrier descendants: $parent" }
            require(meshes[parent].orEmpty().all { it.id == mesh }) { "Gloom v4 cannot modify a carrier used by a foreign mesh" }
            for (child in deformers[parent].orEmpty()) {
                require(child.id in branch) { "Gloom v4 cannot modify a carrier used by a foreign deformer" }
                pending.add(child.id)
            }
        }
    }

}
