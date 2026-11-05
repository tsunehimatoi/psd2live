package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.JsonObject

/** The motion editor and playback address generated clips by their persistent output identity. */
internal object MotionCatalog {
    private class Snapshot(val overlay: RigEditOverlay, val clips: List<MotionClip>)
    @Volatile private var snapshot: Snapshot? = null
    fun entries(overlay: RigEditOverlay): List<MotionClip> {
        snapshot?.takeIf { it.overlay === overlay }?.let { return it.clips }
        val clips = overlay.motionClips + overlay.modifierStack?.let { raw ->
            RigModifierStack.fromJson(raw).instances.filter { it.type == "motion" }.map {
                MotionClips.fromJson(it.config).copy(id = it.outputId("motion"), builtin = null, enabled = it.enabled)
            }
        }.orEmpty()
        return clips.also { snapshot = Snapshot(overlay, it) }
    }
    fun replacing(overlay: RigEditOverlay, clips: List<MotionClip>): RigEditOverlay {
        val raw = overlay.modifierStack ?: return overlay.copy(motionClips = clips)
        val before = RigModifierStack.fromJson(raw)
        val generated = before.instances.filter { it.type == "motion" }.associateBy { it.outputId("motion") }
        var next = before
        for ((id, instance) in generated) {
            val clip = clips.firstOrNull { it.id == id }
            next = if (clip == null) next.remove(instance.id) else next.update(instance.id) {
                it.copy(enabled = clip.enabled, config = JsonObject(it.config + MotionClips.toJson(
                    clip.copy(id = MotionClips.fromJson(it.config).id, enabled = true))))
            }
        }
        return overlay.copy(motionClips = clips.filter { it.id !in generated }, modifierStack = next.toJson())
    }
}
