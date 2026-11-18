package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR

/** An ordinary authoring stage; its existing override journal carries edits, while generators remain live. */
internal object ModifierAuthorEdits {
    const val TYPE = "author/adjustments"

    fun append(stack: RigModifierStack, targets: List<ModifierTarget>): RigModifierStack {
        val next = stack.add(TYPE, 1, targets, presetGroup = "author_edits")
        val instance = next.instances.last()
        // An identity materialized stage has no frozen generator output or upstream read dependency.
        // Only its ordinary override journal modifies the current input, using the same replay as canvas edits.
        val empty = AppliedModifierNode.encode(RigIR(Canvas(1f, 1f), emptyList()))
        val node = AppliedModifierNode(empty, empty, instance.capability(provides = emptySet()), emptySet(), emptyMap(),
            setOf("mesh", "deformer", "part", "parameter", "hierarchy", "relations"), null, reads = emptyList())
        return next.copy(appliedNodes = next.appliedNodes + (instance.id to node))
    }
}
