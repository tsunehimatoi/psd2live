package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.DeformPathPoint
import kotlin.math.abs
import kotlin.math.max

/** Barycentric path attachment shared by authoring and independent algorithms. */
object BindingPaths {
    fun bind(vertices: FloatArray, indices: IntArray, x: Float, y: Float, corner: Boolean = false): DeformPathPoint {
        require(x.isFinite() && y.isFinite())
        var best: DeformPathPoint? = null
        var score = Float.POSITIVE_INFINITY
        for (i in indices.indices step 3) {
            val a=indices[i]; val b=indices[i+1]; val c=indices[i+2]
            val ax=vertices[a*2]; val ay=vertices[a*2+1]
            val bx=vertices[b*2]; val by=vertices[b*2+1]
            val cx=vertices[c*2]; val cy=vertices[c*2+1]
            val det=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy)
            if (abs(det)<1e-12f) continue
            val wa=((by-cy)*(x-cx)+(cx-bx)*(y-cy))/det
            val wb=((cy-ay)*(x-cx)+(ax-cx)*(y-cy))/det
            val wc=1f-wa-wb
            val outside=max(0f,-wa)+max(0f,-wb)+max(0f,-wc)
            if (outside<score) { score=outside; best=DeformPathPoint(a,b,c,wa,wb,wc,corner) }
            if (outside == 0f) break
        }
        return requireNotNull(best) { "A non-degenerate triangle is required to attach a path" }
    }

}
