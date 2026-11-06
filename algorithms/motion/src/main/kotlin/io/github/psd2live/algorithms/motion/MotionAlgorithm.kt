package io.github.psd2live.algorithms.motion

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*

/** Builds a clip from persisted configuration without accessing editor state. */
class MotionAlgorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        checkpoint()
        val source = MotionData.fromJson(instance.config)
        require(source.enabled) { "Disable the modifier to disable its motion" }
        require(source.curves.isNotEmpty() && source.curves.all { input.parameter(it.parameterId) != null }) {
            "Motion parameters are missing"
        }
        val file = source.fileStem ?: ("modifier_" + java.util.HexFormat.of().formatHex(instance.id.encodeToByteArray()))
        val clip = Clip(instance.outputId("motion"), source.name, source.exportGroup ?: if (source.loop) "Idle" else source.name,
            file, source.duration, source.fps, source.loop, source.fadeIn.takeUnless { source.runtimeDefaultFades },
            source.fadeOut.takeUnless { source.runtimeDefaultFades },
            source.curves.map(MotionData::curve), source.events.map { ClipEvent(it.time, it.value) })
        require(input.clips.none { it.id == clip.id || it.file.equals(clip.file, ignoreCase = true) }) {
            "Motion ID or file collision: ${clip.id} / ${clip.file}"
        }
        val existingRoles = input.parameterRoles
        val roles = source.parameterRoles.map { (role, parameters) ->
            require(parameters.all { input.parameter(it) != null }) { "Motion role $role references missing parameters" }
            require(existingRoles.none { it.role == role && it.parameters != parameters }) { "Conflicting motion parameter role $role" }
            ParameterRole(role, parameters)
        }
        val combinedRoles = (roles + existingRoles).distinct()
        return ModifierModelChanges(clips = ModifierObjectChanges(added = listOf(clip)),
            parameterRoles = if (combinedRoles == existingRoles) null else ModifierFieldChange(existingRoles, combinedRoles))
    }
}
