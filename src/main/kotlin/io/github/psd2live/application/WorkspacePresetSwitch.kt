package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*

/** Whole replacement is prepared inside the existing document candidate/CAS transaction. */
internal object WorkspacePresetSwitch {
    fun apply(document: WorkspaceDocument, id: String, work: WorkspaceRasterWork): WorkspaceDocument {
        require(id in WorkspacePresetBaseline.ids) { "Unknown preset baseline: $id" }
        val previous = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
        require(previous.overrides.values.all { it.isEmpty() }) {
            "Preset content has authored overrides. Apply the affected presets before switching the complete preset."
        }
        val cleared = previous.instances.fold(previous) { stack, entry -> stack.remove(entry.id) }
        if (id == "empty") return document.copy(rigEdits = document.rigEdits.copy(modifierStack = cleared.toJson()))
        require(previous.appliedCapabilities.isEmpty()) { "Applied preset content already exists; switch presets before applying them" }
        val config = WorkspacePresetBaseline.configure(document.config(), id)
        val candidate = document.copy(settings = WorkspaceSettingsCodec.encode(config),
            rigEdits = document.rigEdits.copy(modifierStack = cleared.toJson()))
        return WorkspacePresetInitialization.create(candidate, work)
    }
}
