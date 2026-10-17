package io.github.psd2live.application

import io.github.psd2live.core.PhysicsCatalog
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.RigLayerDeletion
import io.github.psd2live.core.minimumAtlasSize
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSettingsPolicy
import io.github.psd2live.project.config
import io.github.psd2live.project.rawConfig
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/**
 * One project settings patch, read once against the captured document. Linked fields follow the switch that
 * implies them unless the patch names them itself, and a switch turned off releases the authored poses it drove.
 * GUI toggles and public settings_update share it, so neither carries a private reset or dependency.
 */
internal class WorkspaceSettingsIntent private constructor(
    /** The complete raw settings after the patch, already validated and canonical. */
    val settings: JsonObject,
    /** Parameters every workspace's authored pose returns to its default, except where that workspace locked it. */
    val released: Released,
) {
    sealed interface Released {
        data object None : Released
        data object All : Released
        data class Only(val ids: Set<ParameterId>) : Released
    }

    /** The document and authored poses after this patch; other auxiliary data is untouched. */
    fun apply(draft: WorkspaceDraft, model: RigPreviewModel): WorkspaceDraft {
        val document = draft.document.copy(settings = settings)
        val parameters = model.rig.puppet.parameters
        val released = when (val released = released) {
            Released.None -> return draft.copy(document = document)
            Released.All -> parameters.mapTo(HashSet()) { it.id }
            is Released.Only -> released.ids
        }
        val records = draft.auxiliary["posesByWorkspace"]?.jsonObject ?: return draft.copy(document = document)
        val defaults = parameters.associate { it.id to it.default }
        val poses = records.mapValues { (id, record) ->
            val pose = PreviewSessions.read(parameters, draft.auxiliary, id)
            val reset = released.filter { it !in pose.locked }.mapNotNull { parameter -> defaults[parameter]?.let { parameter to it } }
            val next = pose.copy(values = pose.values + reset)
            if (next == pose) record else PreviewSessions.encode(next)
        }
        val auxiliary = if (poses == records) draft.auxiliary else JsonObject(draft.auxiliary + ("posesByWorkspace" to JsonObject(poses)))
        return WorkspaceDraft(document, auxiliary)
    }

    companion object {
        private val motionOptions = setOf("motionIdle", "motionBlink", "motionNod", "motionShake", "motionSkeleton")
        private val basic = setOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z,
            StandardParameters.BODY_X, StandardParameters.BODY_Y, StandardParameters.BODY_Z, StandardParameters.BREATH,
            StandardParameters.MOUTH_OPEN, StandardParameters.MOUTH_FORM)
        private val blink = setOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN)

        fun parse(document: WorkspaceDocument, model: RigPreviewModel, changes: JsonObject): WorkspaceSettingsIntent {
            val before = document.rawConfig()
            var settings = patchSettings(document.settings, changes, document.rigEdits.importedCmo3 != null)
            val after = WorkspaceSettingsCodec.decode(settings)
            val minimum = minimumAtlasSize(RigLayerDeletion.generationAnalysis(model.analysis, model.config),
                after.textureUpscale.scale, after.texturePadding)
            if (after.atlasSize < minimum) settings = JsonObject(settings + ("atlasSize" to JsonPrimitive(minimum)))
            return WorkspaceSettingsIntent(settings, released(document, model, before, after))
        }

        /** Pure display projection of the same linked settings the commit applies. */
        fun patchSettings(settings: JsonObject, changes: JsonObject, imported: Boolean): JsonObject {
            require(changes.isNotEmpty()) { "Provide at least one setting" }
            val before = WorkspaceSettingsCodec.decode(settings)
            // Validate what the caller wrote before deriving anything from it.
            val requested = WorkspaceSettingsCodec.decode(mergeProjectSettings(settings, changes))
            val derived = buildJsonObject {
                if ("generateDeformers" !in changes && requested.meshOnly != before.meshOnly && !imported)
                    put("generateDeformers", !requested.meshOnly)
                if ("exportMotions" !in changes && motionOptions.any { it in changes } &&
                    WorkspaceSettingsPolicy.motionOptionOn(requested) != WorkspaceSettingsPolicy.motionOptionOn(before))
                    put("exportMotions", WorkspaceSettingsPolicy.motionOptionOn(requested))
            }
            return mergeProjectSettings(settings, JsonObject(changes + derived))
        }

        /**
         * Switching a source of motion off returns what it drove to rest. Nod and Shake only play transient
         * frames over the authored pose, so turning them off leaves the authored pose as it is.
         */
        private fun released(document: WorkspaceDocument, model: RigPreviewModel, before: PipelineConfig, after: PipelineConfig): Released {
            if (!before.meshOnly && after.meshOnly) return Released.All
            val ids = buildSet {
                if (before.motionBasic && !after.motionBasic) addAll(basic + blink)
                if (before.motionIdle && !after.motionIdle) addAll(basic)
                if (before.motionBlink && !after.motionBlink) addAll(blink)
                if (before.generatePhysics && !after.generatePhysics) {
                    val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
                    PhysicsCatalog.groups(model.analysis, document.config(), available, model.rig.puppet)
                        .flatMap { it.setting.outputParameters }.forEach { add(ParameterId(it)) }
                }
            }
            return if (ids.isEmpty()) Released.None else Released.Only(ids)
        }
    }
}
