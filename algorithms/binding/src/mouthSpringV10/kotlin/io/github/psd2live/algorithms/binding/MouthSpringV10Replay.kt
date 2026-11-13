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

/** Spring-specific source reconstruction. No host-generated forms, mood replay, or carrier changes. */
internal object MouthSpringV10Replay {
    fun changes(
        input: ModifierEvaluationInput,
        instance: RigModifierInstance,
        groups: List<MouthFittedInputs>,
        materials: JsonObject,
        owners: List<RigModifierInstance>,
        controls: List<Parameter>,
        checkpoint: () -> Unit,
    ): List<ModifierObjectUpdate<Mesh>> {
        val witnesses = instance.targets.map { checkpoint(); requireNotNull(input.mesh(it.id)) }
        val witnessWarps = instance.targets.flatMap { checkpoint(); input.parentsOfMesh(it.id) }.distinctBy { it.id }
        val defaults = controls.associate { it.id to it.default }
        val updates = mutableListOf<ModifierObjectUpdate<Mesh>>()
        for (group in groups) {
            checkpoint()
            val mouth = requireNotNull(group.source).rig()
            require(mouth.options.jaw && mouth.options.spring && mouth.options.capture && mouth.options.vowels)
            val turn = requireNotNull(group.head).rig(checkpoint)
            val vertices = requireNotNull(group.sourceVertices)
            val rawMeshes = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
            require(rawMeshes.keys == vertices.targets.values.mapTo(hashSetOf()) { it.canonical } &&
                vertices.targets.values.map { it.canonical }.distinct().size == vertices.targets.size) {
                "Mouth spring v10 rejects extra or repeated source topology"
            }
            for ((id, layer) in group.layers) {
                checkpoint()
                require(layer.tag in setOf("FACE", "NOSE", "FACE_DETAIL") && layer.preset &&
                    (layer.role == null || layer.role == "SHADOW") &&
                    (layer.tag != "FACE_DETAIL" || mouth.fit.toMouth(layer.centroidX, layer.centroidY).second > 0f)) {
                    "Mouth spring v10 requires preset lower-face skin"
                }
                val live = requireNotNull(input.mesh(id))
                val sourceMaterial = materials.getValue(id).jsonObject
                require(sourceMaterial.keys == setOf("name", "source_id", "opacity") &&
                    live.opacity == sourceMaterial.getValue("opacity").jsonPrimitive.float &&
                    live.shapes.none { it.parameter == SPRING } &&
                    live.channels.values.none { channel -> channel.axes.any { it.parameter == SPRING } }) {
                    "Mouth spring v10 requires unused source skin and its captured opacity for $id"
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
                    require(candidates.size == 1) { "Mouth spring v10 cannot resolve the source parent for $id" }
                    candidates.single()
                }
                require(parent != null) { "Mouth spring v10 requires bound source skin" }
                val local = ancestry.takeWhile { it.id != parent }
                val originalParent = ancestry.single { it.id == parent }
                val nativeParent = when (originalParent) {
                    is Deformer.Warp -> originalParent.copy(parent = null, shapes = emptyList())
                    is Deformer.Rotation -> originalParent.copy(parent = null, shapes = emptyList())
                }
                // Preserve native order: mouth-open, spring, capture jaw/cheek, then head forms.
                fun motion(spring: Boolean) = mouth.faceShapes(raw.rigPositions, frame, group.chin,
                    layer.tag == "NOSE", ParameterId("ParamMouthOpenY"), if (spring) MouthRig.SPRING else null) +
                    (if (layer.turnsWithHeadMesh) DepthTurnMotion.shell(layer.tag, layer.role)?.let { shell ->
                        turn.shapes(raw.rigPositions, frame, shell, ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
                    }.orEmpty() else emptyList())
                val beforeMotion = motion(false)
                val afterMotion = motion(true)
                require(afterMotion.count { it.parameterId.raw == SPRING } == 1)
                val ordinary = afterMotion.map { it.parameterId.raw }.filterTo(hashSetOf()) { axis ->
                    controls.singleOrNull { it.id == axis }?.blend == false
                }
                // Evidence must be actual upstream allocation, never invented axes or key cells.
                val ordinaryMeshes = witnesses.filter { it.id != id && it.offsets?.axes.orEmpty().any { it.parameter in ordinary } }
                    .map { it.copy(parent = null, shapes = emptyList(), channels = emptyMap()) }
                val ordinaryWarps = witnessWarps.filterIsInstance<Deformer.Warp>().filter {
                    it.id != parent && it !in local && it.lattice?.axes.orEmpty().any { axis -> axis.parameter in ordinary }
                }.map { it.copy(parent = null, shapes = emptyList(), channels = emptyMap()) }
                val evidenced = ordinaryMeshes.flatMap { it.offsets?.axes.orEmpty() } + ordinaryWarps.flatMap { it.lattice?.axes.orEmpty() } +
                    (nativeParent as? Deformer.Warp)?.lattice?.axes.orEmpty()
                require(ordinary.all { axis -> evidenced.any { it.parameter == axis } }) {
                    "Mouth spring v10 requires live ordinary-axis witnesses for $id"
                }
                val pose = { axis: ParameterId -> if (axis.raw in eyeOpen) 1f else 0f }
                val liveDrawable = PuppetIr.toPuppet(RigIR(input.canvas, controls, meshes = listOf(live))).drawables.single()
                val springOpacity = liveDrawable.channelGrids.scalarAt(FormChannel.OPACITY, liveDrawable.opacity, pose)
                val springOrder = liveDrawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, liveDrawable.drawOrder,
                    { defaults[it.raw] ?: 0f })
                val springMultiply = liveDrawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, liveDrawable.multiplyColor, pose)
                val springScreen = liveDrawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, liveDrawable.screenColor, pose)
                fun lower(spring: Boolean): PuppetModel {
                    val zero = KeyGrid(emptyList(), listOf(KeyCell(Ints.wrap(IntArray(0)),
                        MeshOffsets(Floats.wrap(FloatArray(positions.size))))))
                    val native = live.copy(id = target.canonical, name = sourceMaterial.getValue("name").jsonPrimitive.content,
                        parent = parent, geometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
                        offsets = zero, shapes = emptyList(), channels = emptyMap())
                    val model = PuppetIr.toPuppet(RigIR(input.canvas, controls, meshes = listOf(native) + ordinaryMeshes,
                        deformers = listOf(nativeParent) + ordinaryWarps))
                    val drawable = model.drawables.first()
                    val rest = meshGridDefaultDeltas(drawable, pose)
                    val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, pose)
                    val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, pose)
                    val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, pose)
                    val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, pose)
                    val shaped = drawable.copy(blendShapes = (if (spring) afterMotion else beforeMotion).map { binding ->
                        binding.copy(forms = binding.forms.map { form -> form?.let {
                            val base = rest?.takeIf { deltas -> deltas.size == it.positionDeltas.size && binding.parameterId.raw in headAxes }
                            val deltas = if (base == null) it.positionDeltas else FloatArray(base.size) { i ->
                                if (i % 1024 == 0) checkpoint()
                                it.positionDeltas[i] + base[i]
                            }
                            if (binding.parameterId.raw == SPRING) MeshForm(deltas, springOrder, springOpacity, springMultiply, springScreen)
                            else MeshForm(deltas, order, opacity, multiply, screen)
                        } })
                    })
                    val lowered = BlendShapeLowering({ head, name ->
                        (if (head) group.turnCarrierName else group.shapeCarrierName).replace("{meshName}", name)
                    }, checkpoint).lawful(model.copy(drawables = listOf(shaped) + model.drawables.drop(1)))
                    require(lowered.parameters == model.parameters) { "Mouth spring v10 cannot retype upstream parameters" }
                    return lowered.copy(drawables = listOf(lowered.drawables.first()), deformers = lowered.deformers.filterNot {
                        it.id.raw == parent || ordinaryWarps.any { warp -> warp.id == it.id.raw }
                    })
                }
                val before = lower(false)
                val after = lower(true)
                // Reject any reallocation before resolving the live branch or preparing an update.
                require(after.deformers == before.deformers) { "Mouth spring v10 cannot reallocate or modify source carriers" }
                val aliases = linkedMapOf<DeformerId, DeformerId>()
                val liveCarriers = local.associateBy { it.id }
                for (carrier in before.deformers) {
                    checkpoint()
                    val candidates = owners.filter { ModifierTarget("mesh", id) in it.targets }.map {
                        ScaffoldBinding.deformerId(it, id, carrier.id.raw, shared = true)
                    }.distinct().filter { it in liveCarriers }
                    require(candidates.size == 1) { "Mouth spring v10 cannot resolve a preceding skin carrier for $id: ${carrier.id}" }
                    aliases[carrier.id] = DeformerId(candidates.single())
                }
                require(aliases.values.map { it.raw }.toSet() == liveCarriers.keys && aliases.values.distinct().size == aliases.size) {
                    "Mouth spring v10 requires the complete preceding skin carrier branch for $id"
                }
                val remap = ModelIdRemap(deformers = aliases)
                for (carrier in before.deformers) {
                    checkpoint()
                    val expected = PuppetIr.deformerToIr(remap.deformer(carrier))
                    checkScope(input, expected.id, id, liveCarriers.keys, checkpoint)
                    require(liveCarriers.getValue(expected.id) == expected) {
                        "Mouth spring v10 requires the exact preceding skin carrier: ${expected.id}"
                    }
                }
                fun converted(model: PuppetModel): Mesh = PuppetIr.meshToIr(remap.drawable(model.drawables.single())).copy(id = id)
                val expected = converted(before)
                require(live.parent == expected.parent && live.geometry == expected.geometry && live.offsets == expected.offsets &&
                    live.shapes == expected.shapes && live.channels == expected.channels) {
                    "Mouth spring v10 requires exact preceding skin topology, motion and channels: $id " +
                        "(parent=${live.parent == expected.parent}, geometry=${live.geometry == expected.geometry}, " +
                        "offsets=${live.offsets == expected.offsets}, shapes=${live.shapes == expected.shapes}, channels=${live.channels == expected.channels})"
                }
                val result = converted(after)
                require(result.parent == expected.parent && result.geometry == expected.geometry && result.offsets == expected.offsets &&
                    result.channels == expected.channels && result.shapes.filterNot { it.parameter == SPRING } == expected.shapes) {
                    "Mouth spring v10 cannot change existing source allocation or channels"
                }
                val next = live.copy(shapes = result.shapes)
                if (next != live) updates += ModifierObjectUpdate(live, next)
            }
        }
        return updates
    }

    private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
    private val headAxes = setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ")
    private const val SPRING = "ParamMouthSpring"

    /** Even an unchanged carrier must be the exact exclusive branch witnessed by this source. */
    private fun checkScope(input: ModifierEvaluationInput, carrier: String, mesh: String, branch: Set<String>, checkpoint: () -> Unit) {
        val deformers = input.deformers.groupBy { it.parent }
        val meshes = input.meshes.groupBy { it.parent }
        val pending = ArrayDeque<String>()
        val visited = hashSetOf<String>()
        pending.add(carrier)
        while (pending.isNotEmpty()) {
            checkpoint()
            val parent = pending.removeFirst()
            require(visited.add(parent)) { "Cyclic mouth spring carrier descendants: $parent" }
            require(meshes[parent].orEmpty().all { it.id == mesh }) { "Mouth spring v10 rejects a carrier used by a foreign mesh" }
            for (child in deformers[parent].orEmpty()) {
                require(child.id in branch) { "Mouth spring v10 rejects a carrier used by a foreign deformer" }
                pending.add(child.id)
            }
        }
    }
}
