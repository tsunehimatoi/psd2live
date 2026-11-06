package io.github.psd2live.algorithms.binding

import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*
import java.util.IdentityHashMap

/** Candidate-local structural comparisons; inputs must remain immutable for this cache's lifetime. */
class BindingComparison {
    private class Index(model: PuppetModel) {
        val meshes = model.drawables.associateBy { it.id }
        val deformers = model.deformers.associateBy { it.id }
    }
    private val models = IdentityHashMap<PuppetModel, Index>()
    private val meshes = IdentityHashMap<Drawable, io.github.psd2live.format.model.Mesh>()
    private val deformers = IdentityHashMap<Deformer, io.github.psd2live.format.model.Deformer>()

    /** Compares the complete target mesh except clipping, and every specified ancestor. */
    fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: DrawableId, branch: Set<DeformerId>): Boolean {
        val a = models.getOrPut(before) { Index(before) }
        val b = models.getOrPut(after) { Index(after) }
        fun mesh(index: Index) = index.meshes.getValue(target).let { meshes.getOrPut(it) { PuppetIr.meshToIr(it) } }
        val old = mesh(a)
        val next = mesh(b)
        if (old.copy(maskedBy = next.maskedBy, invertMask = next.invertMask) != next) return false
        fun deformer(index: Index, id: DeformerId) = index.deformers[id]?.let {
            deformers.getOrPut(it) { PuppetIr.deformerToIr(it) }
        }
        return branch.all { deformer(a, it) == deformer(b, it) }
    }
}
