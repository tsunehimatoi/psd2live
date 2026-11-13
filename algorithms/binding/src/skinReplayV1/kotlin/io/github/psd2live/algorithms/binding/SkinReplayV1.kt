package io.github.psd2live.algorithms.binding

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

/**
 * Immutable v1 source-skin reconstruction and exact replay mechanics. This compilation is deliberately
 * outside main: only explicitly selected future workers may include it. Never edit this implementation
 * to change published behavior; introduce a new helper version instead. Historical Gloom4 is unchanged.
 */
object SkinReplayV1 {
    data class Mood(val influence: MoodBinding.Influence, val cheek: Boolean = false, val gloom: Boolean = false)

    fun changes(
        input: ModifierEvaluationInput,
        instance: RigModifierInstance,
        groups: List<MouthFittedInputs>,
        materials: JsonObject,
        owners: List<RigModifierInstance>,
        controls: List<Parameter>,
        beforeInfluence: (MouthFittedInputs.Layer) -> Mood,
        afterInfluence: (MouthFittedInputs.Layer) -> Mood,
        label: String,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
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
                    "$label requires the captured source opacity for $id"
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
                    require(candidates.size == 1) { "$label cannot resolve the source parent for $id" }
                    candidates.single()
                }
                require(parent != null) { "$label requires bound source skin" }
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
                    "$label requires live ordinary-axis witnesses for $id"
                }
                val beforeMood = beforeInfluence(layer)
                val afterMood = afterInfluence(layer)
                fun lower(mood: Mood): PuppetModel {
                    val zero = KeyGrid(emptyList(), listOf(KeyCell(Ints.wrap(IntArray(0)),
                        MeshOffsets(Floats.wrap(FloatArray(positions.size))))))
                    val native = live.copy(id = target.canonical, name = sourceMaterial.getValue("name").jsonPrimitive.content,
                        parent = parent, geometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
                        offsets = zero, shapes = emptyList(), channels = emptyMap())
                    val model = PuppetIr.toPuppet(RigIR(input.canvas, controls, meshes = listOf(native) + ordinaryMeshes,
                        deformers = listOf(nativeParent) + ordinaryWarps))
                    val drawable = model.drawables.first()
                    val shaded = MoodBinding.applyChannels(drawable, mood.influence, mood.cheek, mood.gloom)
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
                    require(lowered.parameters == model.parameters) { "$label cannot retype upstream parameters" }
                    return lowered.copy(drawables = listOf(lowered.drawables.first()), deformers = lowered.deformers.filterNot {
                        it.id.raw == parent || ordinaryWarps.any { warp -> warp.id == it.id.raw }
                    })
                }
                val before = lower(beforeMood)
                val after = lower(afterMood)
                val aliases = linkedMapOf<DeformerId, DeformerId>()
                val liveCarriers = local.associateBy { it.id }
                for (carrier in before.deformers) {
                    val candidates = owners.filter { ModifierTarget("mesh", id) in it.targets }.map {
                        ScaffoldBinding.deformerId(it, id, carrier.id.raw, shared = true)
                    }.distinct().filter { it in liveCarriers }
                    require(candidates.size == 1) { "$label cannot resolve a preceding skin carrier for $id: ${carrier.id}" }
                    aliases[carrier.id] = DeformerId(candidates.single())
                }
                require(aliases.values.map { it.raw }.toSet() == liveCarriers.keys && aliases.values.distinct().size == aliases.size) {
                    "$label requires the complete preceding skin carrier branch for $id"
                }
                require(after.deformers.map { it.id }.containsAll(before.deformers.map { it.id })) {
                    "$label cannot retire a preceding carrier"
                }
                for (carrier in after.deformers) aliases.putIfAbsent(carrier.id,
                    DeformerId(ScaffoldBinding.deformerId(instance, id, carrier.id.raw, shared = true)))
                require(aliases.values.distinct().size == aliases.size) { "$label carrier aliases must be distinct" }
                val remap = ModelIdRemap(deformers = aliases)
                for (carrier in before.deformers) {
                    val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
                    SkinReplayV1CarrierScope.check(input, expected.id, id, liveCarriers.keys, checkpoint)
                    require(liveCarriers.getValue(expected.id) == expected) {
                        "$label requires the exact preceding skin carrier: ${expected.id}"
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
                    "$label requires exact preceding skin topology, motion and channels: $id " +
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
                            "$label cannot reuse an unrelated carrier id: ${next.id}"
                        }
                        carrierOrder[next.id] = meshOrder.getValue(id)
                    } else if (previous != next) {
                        val change = ModifierObjectUpdate(previous, next)
                        require(updatedCarriers.put(next.id, change)?.let { it == change } != false)
                    }
                }
                val next = live.copy(parent = result.parent, geometry = result.geometry, offsets = result.offsets,
                    shapes = result.shapes, channels = result.channels)
                if (next != live) updated += ModifierObjectUpdate(live, next)
            }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updated),
            deformers = ModifierObjectChanges(added = addedCarriers.values.sortedBy { carrierOrder.getValue(it.id) },
                updated = updatedCarriers.values.toList()))
    }

    private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
    private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
    private fun stripShapes(parent: Deformer): Deformer = when (parent) {
        is Deformer.Warp -> parent.copy(shapes = emptyList())
        is Deformer.Rotation -> parent.copy(shapes = emptyList())
    }
}

/** Existing source carriers must exclusively own the complete reconstructed mesh branch. */
object SkinReplayV1CarrierScope {
    fun check(input: ModifierEvaluationInput, carrier: String, mesh: String, branch: Set<String>, checkpoint: () -> Unit) {
        val deformers = input.deformers.groupBy { it.parent }
        val meshes = input.meshes.groupBy { it.parent }
        val pending = ArrayDeque<String>()
        val visited = hashSetOf<String>()
        pending.add(carrier)
        while (pending.isNotEmpty()) {
            checkpoint()
            val parent = pending.removeFirst()
            require(visited.add(parent)) { "Cyclic skin carrier descendants: $parent" }
            require(meshes[parent].orEmpty().all { it.id == mesh }) { "Source skin replay v1 cannot modify a carrier used by a foreign mesh" }
            for (child in deformers[parent].orEmpty()) {
                require(child.id in branch) { "Source skin replay v1 cannot modify a carrier used by a foreign deformer" }
                pending.add(child.id)
            }
        }
    }

}
