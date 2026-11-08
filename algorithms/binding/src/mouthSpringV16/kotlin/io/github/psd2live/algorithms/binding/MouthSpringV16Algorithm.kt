package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Projects the source mouth spring in fallback lower-face frames and emits its pendulum. */
class MouthSpringV16Algorithm : ChangingModifierAlgorithmPlugin {
    companion object {
        const val VERSION = 16
        const val ENTRYPOINT = "builtin.6d6f7574682e737072696e67.v16/spring"

        fun parameters(context: ModifierAlgorithmContext): List<Parameter> =
            listOf(Parameter(MouthRig.SPRING.raw, context.values.getValue("name").jsonPrimitive.content,
                -1f, 1f, 0f, blend = true, keys = Floats.values(-1f, 0f, 1f)))
    }

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
        val base = instance.config.getValue("base")
        require(upstream.any { it.id == base.jsonPrimitive.content && it.type == "mouth/fitted" && it.version == 7 &&
            it.config["scaffold"] == JsonPrimitive(scaffoldId) && it.targets.containsAll(instance.targets) }) {
            "Mouth spring v16 requires its fitted mouth v7"
        }
        val observed = context.values.getValue("providers").jsonObject
        val jaw = instance.config.getValue("dependencies").jsonObject.getValue("mouth.jaw")
        require(observed["mouth.jaw"] == jaw)
        val versions = mapOf("mouth.outline" to 10, "mouth.vowel" to 9, "mouth.capture" to 8,
            "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.interior-depth" to 11)
        require(observed.keys == versions.keys)
        for ((type, version) in versions) {
            require(upstream.any { it.id == observed.getValue(type).jsonPrimitive.content && it.type == type &&
                it.version == version && it.config["scaffold"] == JsonPrimitive(scaffoldId) && it.targets.containsAll(instance.targets) &&
                (type == "mouth.outline" || it.config["base"] == base) }) {
                "Mouth spring v16 requires its captured $type provider"
            }
        }
        val (prior, after) = project(context, checkpoint)
        val oldParameters = prior.parameters.mapTo(hashSetOf()) { it.id }
        val controls = after.parameters.filterNot { it.id in oldParameters }
        val desired = after.copy(parameterTree = listOf(ParameterNode.Group(ParameterGroupId("ParamGroupPhysics"),
            context.values.getValue("group").jsonPrimitive.content, true, controls.map { ParameterNode.Param(it.id) })))
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
                "Mouth spring v16 cannot reorder existing objects"
            }
            return ModifierObjectChanges(added = added, removed = before.filter { id(it) !in next },
                updated = after.mapNotNull { value -> old[id(value)]?.takeIf { it != value }?.let { ModifierObjectUpdate(it, value) } })
        }
        fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        return ModifierModelChanges(parameters = objects(input.parameters, bound.parameters) { it.id },
            meshes = objects(input.meshes, bound.meshes) { it.id }, deformers = objects(input.deformers, bound.deformers) { it.id },
            parts = objects(input.parts, bound.parts) { it.id }, parameterTree = field(input.parameterTree, bound.parameterTree),
            rootChildren = field(input.rootChildren, bound.rootChildren), renderRoot = field(input.renderRoot, bound.renderRoot),
            physicsGroups = physics(input, instance, context, bound.parameters.mapTo(hashSetOf()) { it.id }))
    }
    private fun project(context: ModifierAlgorithmContext, checkpoint: () -> Unit): Pair<PuppetModel, PuppetModel> {
        val measurements = MouthFittedInputs.decode(ModifierAlgorithmContext(
            context.values.getValue("measurement").jsonObject, context.resources))
        val mouth = requireNotNull(measurements.source).rig()
        require(mouth.options.jaw && !mouth.options.tongue)
        val vertices = requireNotNull(measurements.sourceVertices)
        val raw = ScaffoldMeshContext.decodeRawMeshes(vertices.resource, checkpoint)
        val shell = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("shell").read()))
        val lipped = context.values.getValue("lipped").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        val projected = context.values.getValue("projected").jsonArray.mapTo(hashSetOf()) { it.jsonPrimitive.content }
        val axes = context.values.getValue("axes").jsonArray.mapTo(hashSetOf()) { ParameterId(it.jsonPrimitive.content) }
        val opening = ParameterId("ParamMouthOpenY")
        val spring = MouthRig.SPRING
        val drawables = shell.drawables.map { drawable ->
            checkpoint()
            val layer = measurements.layers[drawable.id.raw] ?: return@map drawable
            val material = raw.getValue(vertices.targets.getValue(drawable.id.raw).canonical)
            val shapes = mouth.faceShapes(material.rigPositions, layer.frame, measurements.chin, layer.tag == "NOSE",
                opening, spring, lippedSkin = drawable.id.raw in lipped)
            val index = shapes.indexOfFirst { it.parameterId == spring }
            require(index in 0..drawable.blendShapes.size && drawable.blendShapes.none { it.parameterId == spring })
            val rest = { id: ParameterId -> if (id.raw in setOf("ParamEyeLOpen", "ParamEyeROpen")) 1f else 0f }
            val order = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, rest)
            val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, rest)
            val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, rest)
            val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, rest)
            val binding = shapes[index]
            val neutralized = binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
            })
            return@map drawable.copy(blendShapes = drawable.blendShapes.take(index) + neutralized + drawable.blendShapes.drop(index))
        }
        val lowering = BlendShapeLowering(carrierName = { head, name ->
            val key = if (head) "turnCarrier" else "shapeCarrier"
            context.values.getValue(key).jsonPrimitive.content.replace("{meshName}", name)
        }, checkpoint = checkpoint)
        fun phase(model: PuppetModel, keep: Set<ParameterId>) =
            BindingProjection.retain(lowering.lawful(model), keep, mapOf(opening to 1f), checkpoint)
                .let { it.copy(drawables = it.drawables.filter { mesh -> mesh.id.raw in projected }) }
        val declared = parameters(context).single()
        val parameter = org.umamo.runtime.model.Parameter(spring, declared.name, declared.min, declared.max, declared.default)
        return phase(shell, axes) to phase(shell.copy(drawables = drawables, parameters = shell.parameters + parameter), axes + spring)
    }

    private fun physics(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                        available: Set<String>): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val rule = RigPhysicsEdit(id = "PhysicsMouthSpring", name = context.values.getValue("physicsName").jsonPrimitive.content,
            inputs = listOf(PhysicsInput("ParamMouthOpenY", 80f, PhysicsSourceType.X), PhysicsInput("ParamMouthForm", 20f, PhysicsSourceType.X)),
            outputs = listOf(PhysicsOutput(MouthRig.SPRING.raw, 2, 1.2f)),
            segments = listOf(PhysicsSegment(1f, 0.9f, 0.5f, 1.6f), PhysicsSegment(1.1f, 0.85f, 0.4f, 1.8f)),
            normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f))
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { it == rule.id }) { "Unavailable mouth spring physics slot" }
        val setting = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
        require(setting.inputs.isNotEmpty() && setting.outputs.isNotEmpty() && setting.parameters.all { it in available } &&
            setting.inputs.none { it.parameter in setting.outputParameters }) { "Invalid mouth spring physics inputs or outputs" }
        val group = PhysicsGroups.fromSetting(setting.copy(id = instance.outputId("physics:${rule.id}")))
        val compatible = input.physics.groups.firstOrNull { it.copy(id = group.id, name = group.name) == group }
        if (compatible != null) return ModifierObjectChanges(reused = listOf(compatible))
        PhysicsGroups.validateAppend(input.physics, listOf(group))
        return ModifierObjectChanges(added = listOf(group))

    }

}

object MouthSpringV16Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthSpringV16Algorithm.ENTRYPOINT to MouthSpringV16Algorithm.VERSION) to MouthSpringV16Algorithm(),
    ))
}
