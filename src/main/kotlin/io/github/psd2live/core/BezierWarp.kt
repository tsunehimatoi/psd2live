package io.github.psd2live.core

import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CDeformerSourceSet
import org.umamo.format.cmo3.model.gen.CWarpDeformerBezierExtension
import org.umamo.format.cmo3.model.gen.CWarpDeformerSource
import org.umamo.format.cmo3.type.CArrayList

/** Cubism stores the editable Bezier divisions separately from the baked deformation lattice. */
internal object BezierWarp {
    fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val s = 1f - t
        return s * s * s * p0 + 3f * s * s * t * p1 + 3f * s * t * t * p2 + t * t * t * p3
    }

    fun configureEditor(model: CModelSource) {
        val sources = (model.deformerSourceSet as? CDeformerSourceSet)?._sources as? Iterable<*> ?: return
        for (warp in sources.filterIsInstance<CWarpDeformerSource>()) {
            val extensions = CArrayList<Any?>()
            (warp._extensions as? Iterable<*>)?.filterNot { it is CWarpDeformerBezierExtension }?.forEach { extensions.add(it) }
            extensions.add(CWarpDeformerBezierExtension().apply {
                editLevel = 2
                bezierCol = warp.col.coerceIn(1, 3)
                bezierRow = warp.row.coerceIn(1, 3)
            })
            warp._extensions = extensions
        }
    }
}
