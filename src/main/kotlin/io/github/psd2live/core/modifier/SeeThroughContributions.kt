package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR

internal object SeeThroughContributions {
    fun references(before: RigIR, after: RigIR, instance: RigModifierInstance): List<ModifierContribution> {
        if (before.meshes === after.meshes) return emptyList()
        val existing = before.meshes.associateBy { it.id }
        val current = after.meshes.associateBy { it.id }
        val reused = LinkedHashSet<String>()
        for (target in instance.targets) {
            val copyId = instance.outputId("see-through:${target.id}")
            if (copyId in existing) continue
            val copy = current[copyId] ?: continue
            reused += target.id
            copy.textureSource?.let { reused += it }
            reused += copy.maskedBy
        }
        return reused.filter { it in existing && existing[it] == current[it] }
            .map { ModifierContribution("mesh", it, ModifierChange.REUSED) }
    }
}
