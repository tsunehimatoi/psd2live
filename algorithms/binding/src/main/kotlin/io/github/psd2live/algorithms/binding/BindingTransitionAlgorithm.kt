package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.model.*

/**
 * Shared transition worker algorithm for binding modifiers. When source before/after transitions
 * are captured, merges them onto the live model while preserving authored residuals; otherwise
 * delegates to the underlying standalone source-measurement algorithm when supplied.
 */
class BindingTransitionAlgorithm(
    private val delegate: ModifierAlgorithmPlugin? = null,
    private val delegateVersion: Int? = null,
) : ContextualModifierAlgorithmPlugin {

    override fun evaluate(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): RigIR {
        if ("before" in context.resources && "after" in context.resources) {
            return changesFromTransition(input, instance, context, checkpoint).apply(input)
        }
        val target = requireNotNull(delegate) {
            "Binding algorithm ${instance.type}@${instance.version} requires source transition resources"
        }
        val forwarded = if (delegateVersion != null) instance.copy(version = delegateVersion) else instance
        return when (target) {
            is ReadingModifierAlgorithmPlugin -> target.changes(ModifierEvaluationInput(input, context), forwarded, checkpoint).apply(input)
            is ChangingModifierAlgorithmPlugin -> target.changes(input, forwarded, context, checkpoint).apply(input)
            is ContextualModifierAlgorithmPlugin -> target.evaluate(input, forwarded, context, checkpoint)
            else -> target.evaluate(input, forwarded, checkpoint)
        }
    }

    fun changesFromTransition(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        checkpoint: () -> Unit,
    ): ModifierModelChanges {
        fun phase(name: String) = PuppetIr.toPuppet(RigIrBinary.decode(context.resources.getValue(name).read()))
        val prior = phase("before")
        val after = phase("after")
        val oldParameters = prior.parameters.mapTo(hashSetOf()) { it.id }
        val controls = after.parameters.filterNot { it.id in oldParameters }
        val groupName = context.values["group"]?.jsonPrimitive?.contentOrNull
        val desired = if (groupName == null) after else after.copy(
            parameterTree = listOf(ParameterNode.Group(ParameterGroupId("ParamGroupPhysics"), groupName, true, controls.map { ParameterNode.Param(it.id) }))
        )
        val ids = prior.drawables.mapTo(linkedSetOf()) { it.id.raw }
        require(ids.isNotEmpty() && ids == desired.drawables.mapTo(linkedSetOf()) { it.id.raw } &&
            ids.all { ModifierTarget("mesh", it) in instance.targets }) {
            "Invalid transition targets for ${instance.type}"
        }
        val scaffoldId = instance.config["scaffold"]?.jsonPrimitive?.contentOrNull
        val upstream = context.upstream.distinctBy { it.instanceId }.mapNotNull { capability ->
            capability.instanceId?.let { id ->
                RigModifierInstance(id, capability.type, capability.version, capability.targets,
                    config = capability.config, outputAliases = capability.outputAliases, plugin = capability.plugin)
            }
        }
        val scaffold = scaffoldId?.let { sid -> upstream.singleOrNull { it.id == sid && it.type == "rig.scaffold" } }
            ?: instance
        val owners = listOf(scaffold) + upstream.filterNot { it.id == scaffold.id }
        val live = PuppetIr.toPuppet(input)
        val liveDeformers = live.deformers.mapTo(hashSetOf()) { it.id }
        val liveParts = live.parts.mapTo(hashSetOf()) { it.id }
        val canonicalDeformers = (prior.deformers + desired.deformers).map { it.id }.distinct()
        val hair = context.values["hair"]?.jsonObject.orEmpty()
        val hairProvider = upstream.singleOrNull { it.type == "hair/chains" }
        val sharedScaffold = scaffold.type == "rig.scaffold" && scaffold.version >= 2
        val comparison = BindingComparison()
        val source = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = scaffold
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to desired
            override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
            override fun static(model: PuppetModel) =
                BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) =
                comparison.onlyMasksChange(before, after, DrawableId(target.id), branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                canonicalDeformers.associateWith { canonical ->
                    val chain = hair[canonical.raw]?.jsonPrimitive?.contentOrNull
                    val provider = hairProvider?.takeIf { target in it.targets }
                    val hairAlias = if (chain != null && provider != null)
                        DeformerId(ScaffoldBinding.deformerId(provider, target.id, chain, shared = sharedScaffold)) else null
                    hairAlias?.takeIf { it in liveDeformers }
                        ?: owners.asSequence().filter { target in it.targets }
                            .map { DeformerId(ScaffoldBinding.deformerId(it, target.id, canonical.raw, shared = sharedScaffold)) }
                            .firstOrNull { it in liveDeformers }
                        ?: DeformerId(ScaffoldBinding.deformerId(instance, target.id, canonical.raw, shared = sharedScaffold))
                }
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) =
                (prior.deformers + desired.deformers).mapNotNull { it.partId }.distinct().associateWith { canonical ->
                    owners.asSequence().map { PartId(ScaffoldBinding.partId(it, target.id, canonical.raw, shared = sharedScaffold)) }
                        .firstOrNull { it in liveParts }
                        ?: PartId(ScaffoldBinding.partId(scaffold, target.id, canonical.raw, shared = sharedScaffold))
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
                "${instance.type} cannot reorder existing objects"
            }
            return ModifierObjectChanges(
                added = added,
                removed = before.filter { id(it) !in next },
                updated = after.mapNotNull { value ->
                    old[id(value)]?.takeIf { it != value }?.let { ModifierObjectUpdate(it, value) }
                },
            )
        }
        fun <T> field(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        val restPose = if (context.values["rest_pose_open"]?.jsonPrimitive?.booleanOrNull == true) {
            field(input.restPose, input.restPose + ("ParamMouthOpenY" to 1f))
        } else null
        val physics = physics(input, instance, context, bound.parameters.mapTo(hashSetOf()) { it.id })
        return ModifierModelChanges(
            parameters = objects(input.parameters, bound.parameters) { it.id },
            meshes = objects(input.meshes, bound.meshes) { it.id },
            deformers = objects(input.deformers, bound.deformers) { it.id },
            parts = objects(input.parts, bound.parts) { it.id },
            parameterLinks = field(input.parameterLinks, bound.parameterLinks),
            parameterTree = field(input.parameterTree, bound.parameterTree),
            rootChildren = field(input.rootChildren, bound.rootChildren),
            renderRoot = field(input.renderRoot, bound.renderRoot),
            restPose = restPose,
            physicsGroups = physics,
        )
    }

    private fun physics(
        input: RigIR,
        instance: RigModifierInstance,
        context: ModifierAlgorithmContext,
        available: Set<String>,
    ): ModifierObjectChanges<PhysicsGroup> {
        val entries = context.values["physics"]?.jsonArray ?: return ModifierObjectChanges()
        if (instance.config["physicsEnabled"]?.jsonPrimitive?.boolean == false) return ModifierObjectChanges()
        val settings = entries.map { RigPhysicsEdit.fromJson(it.jsonObject) }
            .filter { rule -> rule.parameters.all { it in available } }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { slot -> settings.any { it.id == slot } }) {
            "${instance.id}: unavailable ${instance.type} physics slot"
        }
        if (settings.isEmpty()) return ModifierObjectChanges()
        val existing = input.physics.groups
        val added = ArrayList<PhysicsGroup>()
        val reused = ArrayList<PhysicsGroup>()
        for (rule in settings) {
            val configured = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
            require(configured.parameters.all { it in available } && configured.inputs.isNotEmpty() &&
                configured.outputs.isNotEmpty() && configured.inputs.none { source -> configured.outputs.any { it.parameter == source.parameter } }) {
                "${instance.id}: invalid ${instance.type} physics"
            }
            val requested = PhysicsGroups.fromSetting(configured.copy(id = instance.outputId("physics:${rule.id}")))
            val compatible = existing.firstOrNull { it.copy(id = requested.id, name = requested.name) == requested }
            if (compatible != null) reused += compatible else added += requested
        }
        PhysicsGroups.validateAppend(input.physics, added)
        return ModifierObjectChanges(added = added, reused = reused)
    }
}
