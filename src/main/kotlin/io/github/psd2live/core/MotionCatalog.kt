package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import kotlinx.serialization.json.JsonObject

/** The motion editor and playback address generated clips by their persistent output identity. */
internal object MotionCatalog {
    private class Snapshot(val overlay: RigEditOverlay, val clips: List<MotionClip>)
    @Volatile private var snapshot: Snapshot? = null
    fun entries(overlay: RigEditOverlay): List<MotionClip> {
        snapshot?.takeIf { it.overlay === overlay }?.let { return it.clips }
        val visible = overlay.motionClips.associateByTo(LinkedHashMap()) { it.id }
        overlay.modifierStack?.let { raw ->
            val stack = RigModifierStack.fromJson(raw)
            for (instance in stack.instances) {
                val node = stack.appliedNodes[instance.id]
                if (node != null) {
                    node.motionOutputs.forEach(visible::remove)
                    node.authoredMotions.orEmpty().map(MotionClips::fromJson).forEach { visible[it.id] = it }
                } else if (instance.type == "motion") {
                    val clip = MotionClips.fromJson(instance.config).copy(id = instance.outputId("motion"), builtin = null, enabled = instance.enabled)
                    visible[clip.id] = clip
                }
            }
        }
        val clips = visible.values.toList()
        return clips.also { snapshot = Snapshot(overlay, it) }
    }
    fun replacing(overlay: RigEditOverlay, clips: List<MotionClip>): RigEditOverlay {
        val raw = overlay.modifierStack ?: return overlay.copy(motionClips = clips,
            motionSources = overlay.motionSources.filterKeys { id -> clips.any { it.id == id } })
        val before = RigModifierStack.fromJson(raw)
        val generated = before.instances.filter { it.type == "motion" && it.id !in before.appliedNodes }.associateBy { it.outputId("motion") }
        val owners = buildMap<String, String> {
            before.instances.forEach { instance ->
                val node = before.appliedNodes[instance.id]
                if (node != null) (node.motionOutputs + node.authoredMotions.orEmpty().map { MotionClips.fromJson(it).id })
                    .forEach { put(it, instance.id) }
                else if (instance.type == "motion") put(instance.outputId("motion"), instance.id)
            }
        }
        var next = before
        val materializedIds = HashSet<String>()
        for (instance in before.instances) {
            val applied = before.appliedNodes[instance.id] ?: continue
            val records = applied.authoredMotions?.map(MotionClips::fromJson) ?: continue
            val ids = records.mapTo(HashSet()) { it.id }
            materializedIds += applied.motionOutputs + ids
            val replacement = applied.replaceMotions(records.mapNotNull { record ->
                if (owners[record.id] != instance.id) record else clips.firstOrNull { it.id == record.id }
            })
            next = next.copy(appliedNodes = next.appliedNodes + (instance.id to replacement))
        }
        for ((id, instance) in generated) {
            val clip = clips.firstOrNull { it.id == id }
            next = if (clip == null) next.remove(instance.id) else next.update(instance.id) {
                it.copy(enabled = clip.enabled, config = JsonObject(it.config + MotionClips.toJson(
                    clip.copy(id = MotionClips.fromJson(it.config).id, enabled = true))))
            }
        }
        val ordinary = overlay.motionClips.filter { it.id in materializedIds } +
            clips.filter { it.id !in generated && it.id !in materializedIds }
        return overlay.copy(motionClips = ordinary, motionSources = overlay.motionSources.filterKeys { id -> ordinary.any { it.id == id } },
            modifierStack = next.toJson())
    }
}
