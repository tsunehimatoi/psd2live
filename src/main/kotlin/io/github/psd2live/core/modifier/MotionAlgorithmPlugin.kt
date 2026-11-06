package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.ClipEvent

/** Bundled in-process algorithm entrypoint. */
internal object MotionAlgorithmPlugin {
    val definition = RigModifierDefinition("motion", 1, motionOutputs = { setOf(it.outputId("motion")) }, evaluate = { rig, instance, checkpoint ->
        checkpoint()
        val source = MotionClips.fromJson(instance.config)
        require(source.enabled) { "Disable the modifier to disable its motion" }
        val available = rig.parameters.mapTo(HashSet()) { it.id }
        require(source.curves.isNotEmpty() && source.curves.all { it.parameterId in available }) { "Motion parameters are missing" }
        val file = source.fileStem ?: ("modifier_" + java.util.HexFormat.of().formatHex(instance.id.encodeToByteArray()))
        val clip = Clip(instance.outputId("motion"), source.name, source.exportGroup ?: if (source.loop) "Idle" else source.name,
            file, source.duration, source.fps, source.loop, source.fadeIn.takeUnless { source.runtimeDefaultFades },
            source.fadeOut.takeUnless { source.runtimeDefaultFades },
            source.curves.map(MotionGenerator::curve), source.events.map { ClipEvent(it.time, it.value) })
        require(rig.clips.none { it.id == clip.id || it.file.equals(clip.file, ignoreCase = true) }) { "Motion ID or file collision: ${clip.id} / ${clip.file}" }
        val roles = source.parameterRoles.map { (role, parameters) ->
            require(parameters.all { it in available }) { "Motion role $role references missing parameters" }
            require(rig.parameterRoles.none { it.role == role && it.parameters != parameters }) { "Conflicting motion parameter role $role" }
            io.github.psd2live.format.model.ParameterRole(role, parameters)
        }
        rig.copy(clips = rig.clips + clip, parameterRoles = (roles + rig.parameterRoles).distinct())
    })

}
