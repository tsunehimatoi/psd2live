package io.github.psd2live.application

import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.config
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/** Finish source import after artwork splitting/naming, without replacing any existing bindings. */
internal object WorkspacePresetInitialize {
    fun apply(document: WorkspaceDocument, request: JsonObject, work: WorkspaceRasterWork): WorkspaceDocument {
        val mode = WorkspaceInitialModifiers.valueOf(request.getValue("modifiers").jsonPrimitive.content.uppercase())
        val preset = request["preset"]?.let { Json.decodeFromJsonElement(WorkspaceInitialPreset.serializer(), it) }
        val (candidate, choices) = prepare(document, mode, preset)
        return if (choices == null) candidate else WorkspacePresetInitialization.create(candidate, work, choices)
    }

    /** Only binding settings change here: the imported artwork and all split/classification edits survive. */
    internal fun prepare(document: WorkspaceDocument, mode: WorkspaceInitialModifiers,
                         preset: WorkspaceInitialPreset? = null): Pair<WorkspaceDocument, WorkspaceInitialPreset?> {
        val stack = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack) {
            "Initialize presets only on an empty source modifier stack"
        })
        require(stack.instances.isEmpty() && stack.appliedCapabilities.isEmpty() && stack.retiredIds.isEmpty() &&
            stack.overrides.isEmpty() && stack.nextId == 1L && stack.organization.isNullOrEmpty() &&
            document.rigEdits.importedCmo3 == null) {
            "Initialize presets only before any modifiers have been added or applied; use preset switching for existing projects"
        }
        require(preset == null || mode == WorkspaceInitialModifiers.CUSTOM) { "Custom choices require the custom modifier mode" }
        val choices = when (mode) {
            WorkspaceInitialModifiers.RECOMMENDED -> WorkspaceModelPresetLibrary.shared.get("modern").choices
            WorkspaceInitialModifiers.CUSTOM -> requireNotNull(preset) { "Custom modifier choices are required" }
            WorkspaceInitialModifiers.MINIMAL, WorkspaceInitialModifiers.DEFAULT, WorkspaceInitialModifiers.FULL -> WorkspaceInitialPreset.forMode(mode)
            WorkspaceInitialModifiers.EMPTY -> return document to null
        }
        // Match SourceImporter: only Recommended resets the baseline; shortcuts/custom keep the import settings.
        val baseline = if (mode == WorkspaceInitialModifiers.RECOMMENDED)
            WorkspacePresetBaseline.configure(document.config(), "modern") else document.config()
        val config = choices.configure(baseline).copy(
            hairSimulationFront = choices.frontHair == WorkspaceInitialHair.SIMULATION,
            hairSimulationBack = choices.backHair == WorkspaceInitialHair.SIMULATION)
        return document.copy(settings = WorkspaceSettingsCodec.encode(config)) to choices
    }
}
