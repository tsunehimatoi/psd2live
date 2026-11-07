package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.ClipEvent
import io.github.psd2live.format.model.PhysicsGroup
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.PuppetModel
import org.umamo.edit.withParametersSyncedFromTree
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Adapters for generators that already accept an authored model. Binding recipes need source-aware adapters. */
internal object RigModifierAlgorithms {
    val swing get() = SwingAlgorithmPlugin.definition
    val simulation get() = SimulationAlgorithmPlugin.definition
    val physics get() = PhysicsAlgorithmPlugin.definition
    val motion get() = MotionAlgorithmPlugin.definition

    private val emptyRegistry = RigModifierRegistry(emptyList())
    @Volatile private var catalog: Pair<Long, RigModifierRegistry>? = null
    val registry: RigModifierRegistry get() {
        val revision = ModifierPluginStore.shared.revision.value
        return catalog?.takeIf { it.first == revision }?.second ?: registryFor(null).also { catalog = revision to it }
    }

    private fun registryFor(source: RigModifierSource?, stack: RigModifierStack? = null): RigModifierRegistry =
        RigBuildProfile.stage("modifier source registry") {
            val builtins = BuiltinModifierPlugins.definitions(source)
            val resolveRasters = source?.let { it::resolveRasters } ?: RigModifierRasterAssets::resolve
            // Current canonical packages use the in-process definitions. Explicit worker names and older package
            // digests still resolve through the catalog, including providers preserved by prefix application.
            val references = stack?.let {
                buildSet {
                    fun include(type: String, version: Int, plugin: ModifierPluginReference?) {
                        if (plugin != null && plugin != BuiltinModifierPlugins.reference(type, version)) add(plugin)
                    }
                    it.instances.forEach { entry -> include(entry.type, entry.version, entry.plugin) }
                    it.appliedCapabilities.forEach { provider -> include(provider.type, provider.version, provider.plugin) }
                }
            }
            val workers = if (references?.isEmpty() == true) emptyList() else ModifierPluginCatalog.definitions(
                captured = builtins, resolveRasters = resolveRasters, references = references)
            RigModifierRegistry(builtins + workers)
        }

    fun evaluator(source: RigModifierSource? = null, stack: RigModifierStack? = null) = RigModifierEvaluator(
        when {
            stack?.instances?.isEmpty() == true -> emptyRegistry
            source == null -> registry
            else -> registryFor(source, stack)
        },
        sourceTargetExists = { it.kind == "layer" && source?.hasLayer(it.id) == true },
        physicsOrder = source?.physicsOrder.orEmpty(),
        resolveRasters = source?.let { it::resolveRasters } ?: RigModifierRasterAssets::resolve) { rig, instance, commands ->
        var model = PuppetIr.toPuppet(rig)
        val issues = ArrayList<GeneratedOverrideIssue>()
        val parameterOverrides = HashSet<String>()
        for (command in commands) {
            if (GeneratedOverrides.isOverride(command)) {
                val result = GeneratedOverrides.apply(model, command)
                model = result.model
                issues += result.issues
            } else if (command["op"]?.jsonPrimitive?.content == "structure" && command["generator"]?.jsonPrimitive?.content == "modifier:${instance.id}") {
                val edit = command.getValue("edits").jsonArray.single().jsonObject
                val kind = edit.getValue("kind").jsonPrimitive.content
                val id = edit.getValue("id").jsonPrimitive.content
                try {
                    model = RigAuthoringJournal.apply(model, command)
                    if (kind == "parameter" && edit.getValue("action").jsonPrimitive.content == "update") parameterOverrides += id
                } catch (_: IllegalArgumentException) {
                    issues += GeneratedOverrideIssue(GeneratedOverrideIssueKind.ORPHANED, "modifier:${instance.id}",
                        "$kind:$id", emptyMap(), 1, 1, GeneratedOverrideOrphanReason.SUPERSEDED)
                }
            } else if (command["op"]?.jsonPrimitive?.content in setOf("parameter_keys", "delete") &&
                command["generator"]?.jsonPrimitive?.content == "modifier:${instance.id}") {
                val changed = try {
                    RigAuthoringJournal.apply(model, command)
                } catch (_: IllegalArgumentException) {
                    model
                } catch (_: NoSuchElementException) {
                    model
                }
                val target = command["target"]?.jsonPrimitive?.content
                val parameter = command.getValue("parameter").jsonPrimitive.content
                if (changed === model) {
                    issues += GeneratedOverrideIssue(GeneratedOverrideIssueKind.ORPHANED, "modifier:${instance.id}",
                        target ?: "parameter:$parameter", emptyMap(), 1, 1, GeneratedOverrideOrphanReason.MISSING_KEYFORM)
                } else if (target == null) parameterOverrides += parameter
                model = changed
            } else model = RigAuthoringJournal.apply(model, command)
        }
        val updated = rig.withPuppet(model)
        ModifierOverrideResult(updated, issues, updated.parameters.filter { it.id in parameterOverrides }.associateBy { it.id })
    }

    internal fun simulationOutputs(instance: RigModifierInstance, source: RigSimEdit): RigSimEdit =
        io.github.psd2live.algorithms.simulation.SimulationOutputs.outputs(instance, source)

    internal fun addPhysics(rig: RigIR, groups: List<PhysicsGroup>): RigIR =
        io.github.psd2live.algorithms.physics.PhysicsGroups.append(rig, groups)

}

/** Keep sidecars and export-only fields while replacing all fields owned by the editable puppet. */
internal fun RigIR.withPuppet(model: PuppetModel): RigIR = PuppetIr.toIr(model.withParametersSyncedFromTree()).copy(
    textures = textures, physics = physics, clips = clips, parameterRoles = parameterRoles,
    restPose = restPose, advanced = advanced, hitAreas = hitAreas,
)
