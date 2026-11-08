package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Projects the depth and shading of source teeth and the mouth interior in single-eye fitted frames. */
object MouthDepthV12Algorithm : ChangingModifierAlgorithmPlugin {
    const val VERSION = 12
    val WRITES = setOf("mesh", "deformer", "part", "hierarchy")
    const val ENTRYPOINT = "builtin.6d6f7574682e696e746572696f722d6465707468.v12/depth"

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val upstream = context.upstream.distinctBy { it.instanceId }.mapNotNull { capability ->
            capability.instanceId?.let { id -> RigModifierInstance(id, capability.type, capability.version, capability.targets,
                config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin) }
        }
        val scaffold = upstream.single { it.id == scaffoldId && it.type == "rig.scaffold" && it.version == 2 &&
            it.targets.containsAll(instance.targets) }
        val mouthId = instance.config.getValue("base").jsonPrimitive.content
        require(upstream.any { it.id == mouthId && it.type == "mouth/fitted" && it.version == 7 &&
            it.config["scaffold"] == JsonPrimitive(scaffoldId) && it.targets.containsAll(instance.targets) }) {
            "Mouth interior depth v12 requires its captured fitted mouth"
        }
        val observed = context.values.getValue("providers").jsonObject
        val versions = mapOf("mouth.outline" to 10, "mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11)
        require(observed.keys == versions.keys)
        for ((type, version) in versions) {
            require(upstream.any { it.id == observed.getValue(type).jsonPrimitive.content && it.type == type &&
                it.version == version && it.config["scaffold"] == JsonPrimitive(scaffoldId) && it.targets.containsAll(instance.targets) &&
                (type == "mouth.outline" || it.config["base"] == instance.config["base"]) }) {
                "Mouth interior depth v12 requires its captured $type provider"
            }
        }
        val (prior, desired) = project(instance, context, checkpoint)
        val ids = prior.drawables.mapTo(linkedSetOf()) { it.id.raw }
        require(ids.isNotEmpty() && ids == desired.drawables.mapTo(linkedSetOf()) { it.id.raw } &&
            ids.all { ModifierTarget("mesh", it) in instance.targets })
        val live = PuppetIr.toPuppet(input)
        val liveDeformers = live.deformers.mapTo(hashSetOf()) { it.id }
        val liveParts = live.parts.mapTo(hashSetOf()) { it.id }
        val owners = listOf(scaffold) + upstream.filterNot { it.id == scaffoldId }
        val canonicalDeformers = (prior.deformers + desired.deformers).map { it.id }.distinct()
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = scaffold
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to desired
            override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                canonicalDeformers.associateWith { canonical ->
                    owners.asSequence().filter { target in it.targets }
                        .map { DeformerId(ScaffoldBinding.deformerId(it, target.id, canonical.raw, shared = true)) }
                        .firstOrNull { it in liveDeformers }
                        ?: DeformerId(ScaffoldBinding.deformerId(instance, target.id, canonical.raw, shared = true))
                }
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                (prior.deformers + desired.deformers).mapNotNull { it.partId }.distinct().associateWith { canonical ->
                    owners.asSequence().map { PartId(ScaffoldBinding.partId(it, target.id, canonical.raw, shared = true)) }
                        .first { it in liveParts }
                }
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                ScaffoldBinding.partId(instance, mesh, canonical, shared)
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val scoped = instance.copy(targets = instance.targets.filter { it.id in ids })
        val bound = PuppetIr.toIr(BindingMerge.apply(live, scoped, source, checkpoint).withParametersSyncedFromTree())
        fun <T> objects(before: List<T>, after: List<T>, id: (T) -> String): ModifierObjectChanges<T> {
            val old = before.associateBy(id)
            val next = after.associateBy(id)
            val added = after.filter { id(it) !in old }
            require(after.map(id) == before.map(id).filter { it in next } + added.map(id)) {
                "Mouth interior depth v12 cannot reorder existing objects"
            }
            return ModifierObjectChanges(added = added, removed = before.filter { id(it) !in next },
                updated = after.mapNotNull { value -> old[id(value)]?.takeIf { it != value }?.let { ModifierObjectUpdate(it, value) } })
        }
        fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        require(bound.parameters == input.parameters) { "Mouth interior depth v12 cannot add parameters" }
        return ModifierModelChanges(meshes = objects(input.meshes, bound.meshes) { it.id },
            deformers = objects(input.deformers, bound.deformers) { it.id }, parts = objects(input.parts, bound.parts) { it.id },
            rootChildren = field(input.rootChildren, bound.rootChildren), renderRoot = field(input.renderRoot, bound.renderRoot))

    }
    private fun project(instance: RigModifierInstance, context: ModifierAlgorithmContext,
                        checkpoint: () -> Unit): Pair<PuppetModel, PuppetModel> {
        val measured = MouthFittedInputs.decode(ModifierAlgorithmContext(
            context.values.getValue("measurement").jsonObject, context.resources))
        val source = requireNotNull(measured.source)
        require(source.fit.layered == null && !source.options.tongue && !source.options.depth)
        val amount = instance.config["values"]?.jsonObject?.get("depth")?.jsonPrimitive?.float ?: 100f
        require(amount.isFinite() && amount in 0f..200f)
        val mouth = source.copy(options = source.options.copy(depth = true, depthAmount = amount / 100f)).rig()
        val vertices = requireNotNull(measured.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val shell = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("shell").read()))
        val follows = context.values.getValue("follows").jsonObject
        val drawnMouth = context.values.getValue("drawnMouth").jsonPrimitive.boolean
        val axes = context.values.getValue("axes").jsonArray.mapTo(hashSetOf()) { ParameterId(it.jsonPrimitive.content) }
        val opening = ParameterId("ParamMouthOpenY")
        val angleX = ParameterId("ParamAngleX")
        val angleY = ParameterId("ParamAngleY")
        val headAxes = setOf(angleX, angleY, ParameterId("ParamAngleZ"))
        val rest = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val drawables = shell.drawables.map { drawable ->
            checkpoint()
            val followsSource = follows[drawable.id.raw]?.jsonObject ?: return@map drawable
            val layer = measured.layers.getValue(drawable.id.raw)
            val follow = MouthRig.Follow.valueOf(followsSource.getValue("frame").jsonPrimitive.content)
            val shadeFollow = MouthRig.Follow.valueOf(followsSource.getValue("art").jsonPrimitive.content)
            val separateInside = layer.tag == "MOUTH_OPEN" && drawnMouth
            val depth = when (follow) {
                MouthRig.Follow.UPPER_TEETH -> .06f
                MouthRig.Follow.LOWER_TEETH -> .1f
                MouthRig.Follow.DEEP -> .2f
                MouthRig.Follow.INSIDE, MouthRig.Follow.OPENING -> 0f
                else -> if (separateInside) .12f else 0f
            }
            val shade = shadeFollow in setOf(MouthRig.Follow.DEEP, MouthRig.Follow.INSIDE, MouthRig.Follow.OPENING) ||
                (separateInside && layer.role == null)
            val shaded = if (shade) shadeOpening(drawable, opening) else drawable
            if (depth == 0f) return@map shaded
            val material = raw.getValue(vertices.targets.getValue(drawable.id.raw).canonical)
            val positions = material.rigPositions
            val neutral = meshGridDefaultDeltas(shaded, rest)?.takeIf { it.size == positions.size }
            val order = shaded.channelGrids.scalarAt(FormChannel.DRAW_ORDER, shaded.drawOrder, rest)
            val opacity = shaded.channelGrids.scalarAt(FormChannel.OPACITY, shaded.opacity, rest)
            val multiply = shaded.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, shaded.multiplyColor, rest)
            val screen = shaded.channelGrids.colorAt(FormChannel.SCREEN_COLOR, shaded.screenColor, rest)
            val shifts = mouth.depthShapes(positions, layer.frame, depth, angleX, angleY).map { binding ->
                binding.copy(forms = binding.forms.map { form -> form?.let {
                    val deltas = if (neutral == null) it.positionDeltas else FloatArray(positions.size) { i ->
                        it.positionDeltas[i] + neutral[i]
                    }
                    MeshForm(deltas, order, opacity, multiply, screen)
                } })
            }
            val beforeHead = shaded.blendShapes.indexOfFirst { it.parameterId in headAxes }.let {
                if (it == -1) shaded.blendShapes.size else it
            }
            shaded.copy(blendShapes = shaded.blendShapes.take(beforeHead) + shifts + shaded.blendShapes.drop(beforeHead))
        }
        val lowering = BlendShapeLowering(carrierName = { head, name ->
            val key = if (head) "turnCarrier" else "shapeCarrier"
            context.values.getValue(key).jsonPrimitive.content.replace("{meshName}", name)
        }, checkpoint = checkpoint)
        fun phase(model: PuppetModel) =
            BindingProjection.retain(lowering.lawful(model), axes, mapOf(opening to 1f), checkpoint)
                .let { it.copy(drawables = it.drawables.filter { mesh -> mesh.id.raw in measured.layers }) }
        return phase(shell) to phase(shell.copy(drawables = drawables))
    }

    private fun shadeOpening(drawable: Drawable, opening: ParameterId): Drawable {
        if (FormChannel.MULTIPLY_COLOR in drawable.channelGrids.gridsByChannel) return drawable
        fun color(value: Float) = ColorRgb(value, value * .97f, value * .97f)
        val grid = KeyformGrid<ChannelValue>(listOf(KeyformAxis(opening, floatArrayOf(0f, .25f, 1f))),
            listOf(.72f, .82f, 1f).mapIndexed { index, value ->
                KeyformCell(intArrayOf(index), ChannelValue.Color(color(value)))
            })
        return drawable.copy(channelGrids = ChannelGrids(drawable.channelGrids.gridsByChannel + (FormChannel.MULTIPLY_COLOR to grid)),
            blendShapes = drawable.blendShapes.map { binding ->
                binding.copy(forms = binding.forms.map { form ->
                    form?.let { MeshForm(it.positionDeltas, it.drawOrder, it.opacity, color(.72f), it.screenColor) }
                })
            })
    }

}

object MouthDepthV12Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthDepthV12Algorithm.ENTRYPOINT to MouthDepthV12Algorithm.VERSION) to MouthDepthV12Algorithm,
    ))
}
