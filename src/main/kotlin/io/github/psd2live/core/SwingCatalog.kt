package io.github.psd2live.core

import io.github.psd2live.core.modifier.*
import org.umamo.runtime.model.PuppetModel

internal object SwingCatalog {
    fun owner(overlay: RigEditOverlay, id: String) = overlay.modifierStack?.let {
        RigModifierStack.fromJson(it).instances.firstOrNull { entry -> entry.type == "swing" && entry.id == id }
    }
    fun resolved(instance: RigModifierInstance): RigSwingEdit = RigSwingEdit.fromJson(instance.config).let { source ->
        source.copy(id = instance.id, motions = source.motions.map { motion ->
            motion.copy(parameterIds = motion.parameterIds.indices.map { instance.outputId("swing:${motion.kind.name.lowercase()}:$it") })
        })
    }
    fun entries(overlay: RigEditOverlay): List<RigSwingEdit> = overlay.swingEdits + overlay.modifierStack?.let {
        RigModifierStack.fromJson(it).instances.filter { entry -> entry.type == "swing" }.map(::resolved)
    }.orEmpty()
    fun find(overlay: RigEditOverlay, puppet: PuppetModel, targets: List<String>): RigSwingEdit? {
        val parents = targets.mapNotNull { id -> puppet.drawables.firstOrNull { it.id.raw == id }?.parentDeformerId?.raw }
        return entries(overlay).firstOrNull { swing ->
            swing.targets.any { it in targets || it in parents } || swing.targets.any { target ->
                puppet.drawables.firstOrNull { it.id.raw == target }?.parentDeformerId?.raw in targets
            }
        }
    }
}
