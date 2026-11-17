package io.github.psd2live.core

import io.github.psd2live.core.modifier.*

/** Routes ordinary physics editing to materialized output at its original evaluation position. */
internal object AppliedPhysics {
    fun owner(overlay: RigEditOverlay, id: String): String? = overlay.modifierStack?.let { raw ->
        val stack = RigModifierStack.fromJson(raw)
        stack.instances.lastOrNull { instance -> stack.appliedNodes[instance.id]?.authoredPhysics.orEmpty()
            .any { RigPhysicsEdit.fromJson(it).id == id } }?.id
    }

    fun edit(overlay: RigEditOverlay, id: String, setting: RigPhysicsEdit? = null,
             enabled: Boolean? = null, delete: Boolean = false): RigEditOverlay? {
        val owner = owner(overlay, id) ?: return null
        val stack = RigModifierStack.fromJson(requireNotNull(overlay.modifierStack))
        val node = stack.appliedNodes.getValue(owner).editPhysics(id, setting, enabled, delete)
        return overlay.copy(modifierStack = stack.copy(appliedNodes = stack.appliedNodes + (owner to node)).toJson())
    }
}
