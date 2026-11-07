package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode as PuppetParameterNode
import org.umamo.runtime.model.PuppetModel

/** Uses the local source hanging-arm transition when there is only one arm layer. */
class ArmSwingV4Algorithm : ReadingModifierAlgorithmPlugin {
    private val paired = ArmSwingV3Algorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val context = input.context
        val values = context.values
        val meshId = values["singleArm"]?.jsonPrimitive?.content
            ?: return paired.changes(input, instance.copy(version = ArmSwingV3Algorithm.VERSION), checkpoint)
        val target = instance.targets.single { it.id == meshId && it.kind == "mesh" }
        val dependencies = instance.config.getValue("dependencies").jsonObject
        require(dependencies.keys == setOf("body/legacy", "face/legacy"))
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        fun provider(type: String, id: String) = input.providers(type).single {
            it.instanceId == id && it.targets.containsAll(instance.targets)
        }
        val scaffold = provider("rig.scaffold", scaffoldId)
        val body = provider("body/legacy", dependencies.getValue("body/legacy").jsonPrimitive.content)
        val face = provider("face/legacy", dependencies.getValue("face/legacy").jsonPrimitive.content)
        require(scaffold.type == "rig.scaffold" && scaffold.version == 2 && scaffold.targets.toSet() == instance.targets.toSet())
        require(body.type == "body/legacy" && body.version in 1..2 && body.config["scaffold"] == JsonPrimitive(scaffoldId) &&
            face.type in setOf("face/legacy", "face/fitted") && face.config["scaffold"] == JsonPrimitive(scaffoldId))
        val structure = RigModifierInstance(scaffoldId, scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val old = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("armBefore").read()))
        val nativeAfter = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue("armAfter").read()))
        val definitions = ArmSwingAlgorithm.parameters(ModifierAlgorithmContext(JsonObject(values - "singleArm")))
        val axes = definitions.mapTo(linkedSetOf()) { ParameterId(it.id) }
        val folder = PuppetParameterNode.Group(ParameterGroupId("ParamGroupPhysics"),
            values.getValue("folder").jsonPrimitive.content, true, axes.map { PuppetParameterNode.Param(it) })
        val next = nativeAfter.copy(parameterTree = BindingMerge.appendTree(nativeAfter, listOf(folder), axes, preserveSourceOrder = false))
        val oldMesh = old.drawables.single()
        val nextMesh = next.drawables.single()
        require(oldMesh.id.raw == meshId && nextMesh.id == oldMesh.id && oldMesh.parentDeformerId == nextMesh.parentDeformerId)
        val carrier = requireNotNull(oldMesh.parentDeformerId)
        require(carrier.raw in setOf("DeformArmHang_L", "DeformArmHang_R"))
        val aliases = old.deformers.associate { it.id to DeformerId(ScaffoldBinding.deformerId(structure, meshId, it.id.raw)) }
        val liveMesh = requireNotNull(input.mesh(meshId))
        require(liveMesh.parent == aliases.getValue(carrier).raw)
        val liveBranch = input.parentsOfMesh(meshId)
        val liveParameters = input.parameters
        val tree = input.parameterTree
        val live = PuppetIr.toPuppet(RigIR(input.canvas, liveParameters, meshes = listOf(liveMesh), deformers = liveBranch,
            parameterTree = tree, textures = input.textures, authoring = input.authoring))
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = structure
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = old to next
            override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
            override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(org.umamo.runtime.model.ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = aliases
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = error("Arm swing cannot replace the arm hierarchy")
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                error("Arm swing cannot replace an organizational part")
            override fun vertexSources(from: PuppetModel, original: org.umamo.runtime.model.Drawable, to: PuppetModel,
                                       target: org.umamo.runtime.model.Drawable, checkpoint: () -> Unit) =
                BodyVertexSources.match(to, original, target, checkpoint)
        }
        val bound = PuppetIr.toIr(BindingMerge.apply(live, instance.copy(targets = listOf(target)), source, checkpoint))
        val liveIds = liveParameters.mapTo(hashSetOf()) { it.id }
        val added = definitions.filterNot { it.id in liveIds }
        require(bound.parameters == liveParameters + added) { "Arm swing parameters differ from the measured source declarations" }
        val meshUpdates = bound.meshes.single().takeIf { it != liveMesh }?.let { listOf(ModifierObjectUpdate(liveMesh, it)) }.orEmpty()
        val deformerUpdates = liveBranch.zip(bound.deformers).mapNotNull { (before, after) ->
            require(before.id == after.id)
            after.takeIf { it != before }?.let { ModifierObjectUpdate(before, it) }
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = added),
            deformers = ModifierObjectChanges(updated = deformerUpdates), meshes = ModifierObjectChanges(updated = meshUpdates),
            physicsGroups = physics(input, instance, added, values),
            parameterTree = bound.parameterTree.takeIf { it != tree }?.let { ModifierFieldChange(tree, it) })
    }
    private fun physics(input: ModifierEvaluationInput, instance: RigModifierInstance, added: List<Parameter>, context: JsonObject): ModifierObjectChanges<PhysicsGroup> {
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val available = (input.parameters + added).mapTo(hashSetOf()) { it.id }
        val names = context.getValue("physics_names").jsonObject
        val rules = ArmSwingMotion.physics(available) { names.getValue(it).jsonPrimitive.content }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { slot -> rules.any { it.id == slot } }) { "Unknown or unavailable arm physics slot" }
        if (rules.isEmpty()) return ModifierObjectChanges()
        val existing = input.physics.groups
        val additions = mutableListOf<PhysicsGroup>()
        val reused = mutableListOf<PhysicsGroup>()
        for (rule in rules) {
            val edit = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
            require(edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() && edit.parameters.all { it in available } &&
                edit.inputs.none { it.parameter in edit.outputParameters }) { "Invalid arm physics slot: ${rule.id}" }
            val group = PhysicsGroups.fromSetting(edit.copy(id = instance.outputId("physics:${rule.id}")))
            val compatible = (existing + additions).firstOrNull { it.copy(id = group.id, name = group.name) == group }
            if (compatible != null) {
                if (compatible in existing && compatible !in reused) reused += compatible
            } else {
                require((existing + additions).none { it.id == group.id || it.outputs.any { output -> output.parameter in edit.outputParameters } }) {
                    "Conflicting arm physics slot: ${rule.id}"
                }
                additions += group
            }
        }
        return ModifierObjectChanges(added = additions, reused = reused)
    }

    companion object {
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.626f64792e61726d73.v4/swing"
    }
}

object ArmSwingV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (ArmSwingV4Algorithm.ENTRYPOINT to ArmSwingV4Algorithm.VERSION) to ArmSwingV4Algorithm(),
    ))
}
