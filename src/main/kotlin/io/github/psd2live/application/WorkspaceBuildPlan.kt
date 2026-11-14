package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.modifier.ModifierPluginReference
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Captured together with a successful candidate, not inferred later from mutable global settings. */
internal data class WorkspaceBuildInputs(
    val document: WorkspaceDocument,
    val config: PipelineConfig,
    val pluginRevision: Long,
    val language: String,
    val rawSourceRotations: Boolean,
) {
    init { require(document.rigEdits == config.rigEdits) { "Build inputs must describe the same overlay" } }
}

internal data class ModifierBuildIdentity(val type: String, val version: Int, val plugin: ModifierPluginReference?) {
    companion object {
        fun of(instance: RigModifierInstance) = ModifierBuildIdentity(instance.type, instance.version, instance.plugin)
    }
}

internal enum class WorkspaceBuildStage { SOURCE_PIXELS, SOURCE_TOPOLOGY, AUTHOR_INPUT, MODIFIERS, ATLAS, TARGET }
internal enum class WorkspaceBuildAction { INVALIDATE, CHECK_EXISTING_KEY }
internal enum class WorkspaceBuildReason {
    FIRST_BUILD, CACHE_DISABLED, SOURCE_CHANGED, DOCUMENT_CHANGED, CONFIG_CHANGED,
    PLUGINS_CHANGED, LANGUAGE_CHANGED, EXECUTION_CHANGED, UNKNOWN_READS,
    UNSUPPORTED_EDIT, STACK_STRUCTURE_CHANGED, UNCHANGED_INPUTS,
    PART_SUB_CONFIG_CHANGED, MESH_SPLIT_APPENDED, UNCHANGED_PREFIX, ORDERED_SUCCESSOR,
}
internal enum class WorkspaceBuildPilot { NONE, PART_SUB_CONFIG, APPEND_MESH_SPLIT }
internal data class WorkspaceStageDecision(val stage: WorkspaceBuildStage, val action: WorkspaceBuildAction, val reason: WorkspaceBuildReason)
internal data class WorkspaceModifierDecision(val id: String, val action: WorkspaceBuildAction, val reason: WorkspaceBuildReason)

/**
 * Invalidation policy only: CHECK_EXISTING_KEY is permission to try an existing cache's own complete
 * key/precondition checks, never permission to skip execution. No cache, resource or history is owned here.
 * DocumentGenerators' ownership graph is not a complete read contract, so it cannot grant this permission.
 */
internal data class WorkspaceBuildPlan(
    val pilot: WorkspaceBuildPilot,
    val stages: List<WorkspaceStageDecision>,
    val modifiers: List<WorkspaceModifierDecision>,
) {
    val fullyInvalidated get() = stages.all { it.action == WorkspaceBuildAction.INVALIDATE }
}

internal object WorkspaceBuildPlanner {
    /**
     * [completeReads] must come from audited algorithm/author-context contracts for these exact identities.
     * An omitted declaration invalidates everything, including stages that a heuristic could call unchanged.
     * Final builder/cache-owner integration is deliberately separate from this pure planning boundary.
     */
    fun plan(before: WorkspaceBuildInputs?, after: WorkspaceBuildInputs,
             completeReads: Set<ModifierBuildIdentity> = emptySet(), cacheEnabled: Boolean = true): WorkspaceBuildPlan {
        val next = after.document.rigEdits.modifierStack?.let(RigModifierStack::fromJson)
        fun full(reason: WorkspaceBuildReason) = WorkspaceBuildPlan(WorkspaceBuildPilot.NONE,
            WorkspaceBuildStage.entries.map { WorkspaceStageDecision(it, WorkspaceBuildAction.INVALIDATE, reason) },
            next?.instances.orEmpty().map { WorkspaceModifierDecision(it.id, WorkspaceBuildAction.INVALIDATE, reason) })
        if (!cacheEnabled) return full(WorkspaceBuildReason.CACHE_DISABLED)
        if (before == null) return full(WorkspaceBuildReason.FIRST_BUILD)
        if (before.pluginRevision != after.pluginRevision) return full(WorkspaceBuildReason.PLUGINS_CHANGED)
        if (before.language != after.language) return full(WorkspaceBuildReason.LANGUAGE_CHANGED)
        if (before.rawSourceRotations != after.rawSourceRotations) return full(WorkspaceBuildReason.EXECUTION_CHANGED)
        if (before.document.source != after.document.source) return full(WorkspaceBuildReason.SOURCE_CHANGED)
        val old = before.document.rigEdits.modifierStack?.let(RigModifierStack::fromJson)
            ?: return full(WorkspaceBuildReason.UNSUPPORTED_EDIT)
        if (next == null) return full(WorkspaceBuildReason.UNSUPPORTED_EDIT)
        val restoredOverlay = after.document.rigEdits.copy(modifierStack = before.document.rigEdits.modifierStack)
        if (after.document.copy(rigEdits = restoredOverlay) != before.document) return full(WorkspaceBuildReason.DOCUMENT_CHANGED)
        if (after.config.copy(rigEdits = restoredOverlay) != before.config) return full(WorkspaceBuildReason.CONFIG_CHANGED)
        if ((old.instances + next.instances).any { ModifierBuildIdentity.of(it) !in completeReads })
            return full(WorkspaceBuildReason.UNKNOWN_READS)
        if (old == next) return WorkspaceBuildPlan(WorkspaceBuildPilot.NONE,
            WorkspaceBuildStage.entries.map { WorkspaceStageDecision(it, WorkspaceBuildAction.CHECK_EXISTING_KEY, WorkspaceBuildReason.UNCHANGED_INPUTS) },
            next.instances.map { WorkspaceModifierDecision(it.id, WorkspaceBuildAction.CHECK_EXISTING_KEY, WorkspaceBuildReason.UNCHANGED_INPUTS) })

        val split = next.instances.lastOrNull()
        val appendSplit = split != null && split.enabled && split.type == "mesh.split" && split.version == 1 &&
            next.instances.size == old.instances.size + 1 && next.instances.dropLast(1) == old.instances &&
            next.toJson() - setOf("instances", "nextId") == old.toJson() - setOf("instances", "nextId")
        val firstChanged: Int
        val pilot: WorkspaceBuildPilot
        val reason: WorkspaceBuildReason
        if (appendSplit) {
            firstChanged = old.instances.size
            pilot = WorkspaceBuildPilot.APPEND_MESH_SPLIT
            reason = WorkspaceBuildReason.MESH_SPLIT_APPENDED
        } else {
            if (old.instances.size != next.instances.size || old.toJson() - "instances" != next.toJson() - "instances")
                return full(WorkspaceBuildReason.STACK_STRUCTURE_CHANGED)
            if (old.instances.zip(next.instances).any { (a, b) -> a.copy(config = b.config) != b })
                return full(WorkspaceBuildReason.STACK_STRUCTURE_CHANGED)
            val changed = old.instances.indices.filter { old.instances[it] != next.instances[it] }
            if (changed.size != 1) return full(WorkspaceBuildReason.UNSUPPORTED_EDIT)
            firstChanged = changed.single()
            val a = old.instances[firstChanged]
            val b = next.instances[firstChanged]
            if (!a.enabled || a.copy(config = b.config) != b) return full(WorkspaceBuildReason.STACK_STRUCTURE_CHANGED)
            if (!localSubConfigChange(a, b)) return full(WorkspaceBuildReason.UNSUPPORTED_EDIT)
            pilot = WorkspaceBuildPilot.PART_SUB_CONFIG
            reason = WorkspaceBuildReason.PART_SUB_CONFIG_CHANGED
        }
        return WorkspaceBuildPlan(pilot, WorkspaceBuildStage.entries.map { stage ->
            // Output topology, generated pixels, atlas and compilation remain invalid even for scalar subfeatures.
            val mayCheck = stage in setOf(WorkspaceBuildStage.SOURCE_PIXELS, WorkspaceBuildStage.SOURCE_TOPOLOGY, WorkspaceBuildStage.AUTHOR_INPUT)
            WorkspaceStageDecision(stage, if (mayCheck) WorkspaceBuildAction.CHECK_EXISTING_KEY else WorkspaceBuildAction.INVALIDATE,
                if (mayCheck) WorkspaceBuildReason.UNCHANGED_INPUTS else reason)
        }, next.instances.mapIndexed { index, instance ->
            WorkspaceModifierDecision(instance.id,
                if (index < firstChanged) WorkspaceBuildAction.CHECK_EXISTING_KEY else WorkspaceBuildAction.INVALIDATE,
                when { index < firstChanged -> WorkspaceBuildReason.UNCHANGED_PREFIX
                    index == firstChanged -> reason
                    else -> WorkspaceBuildReason.ORDERED_SUCCESSOR })
        })
    }

    private fun localSubConfigChange(before: RigModifierInstance, after: RigModifierInstance): Boolean {
        if (after.type !in setOf("eye", "brow", "mouth", "face", "body") || after.version != 1) return false
        if (before.config - "subConfigs" != after.config - "subConfigs") return false
        val a = before.config["subConfigs"] as? JsonObject ?: return false
        val b = after.config["subConfigs"] as? JsonObject ?: return false
        if (a.keys != b.keys) return false
        val changed = a.keys.filter { a[it] != b[it] }.singleOrNull() ?: return false
        val old = a[changed] as? JsonObject ?: return false
        val next = b[changed] as? JsonObject ?: return false
        val section = setOf("values", "tuning").singleOrNull { old[it] != next[it] } ?: return false
        if (old - section != next - section) return false // Targets, dependencies and resource settings stay fixed.
        val oldValues = old[section] as? JsonObject ?: return false
        val newValues = next[section] as? JsonObject ?: return false
        if (oldValues.keys != newValues.keys) return false
        val key = oldValues.keys.filter { oldValues[it] != newValues[it] }.singleOrNull() ?: return false
        fun numeric(value: kotlinx.serialization.json.JsonElement?): Boolean {
            val primitive = value as? JsonPrimitive ?: return false
            return !primitive.isString && primitive.doubleOrNull?.isFinite() == true
        }
        return numeric(oldValues[key]) && numeric(newValues[key])
    }
}
