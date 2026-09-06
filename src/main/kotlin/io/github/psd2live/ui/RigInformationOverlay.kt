package io.github.psd2live.ui

import org.umamo.runtime.model.*
import org.umamo.render.eval.CpuDeformationEvaluator
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D

/** Probe the actual parent cascade, rather than drawing undeformed rectangles. */
internal object RigInformationOverlay {
    fun warpPoints(model: PuppetModel, parameters: Map<ParameterId, Float>, ids: Set<String>): Map<String, FloatArray> {
        val warps = model.deformers.filterIsInstance<Deformer.Warp>().filter { it.id.raw in ids }
        require(warps.size == ids.size) { "Information layer requires existing Warp IDs" }
        val probes = warps.map { w ->
            val points = FloatArray((w.rows+1)*(w.columns+1)*2)
            for(r in 0..w.rows) for(c in 0..w.columns) { val i=(r*(w.columns+1)+c)*2; points[i]=c.toFloat()/w.columns; points[i+1]=r.toFloat()/w.rows }
            Drawable(DrawableId("__overlay_${w.id.raw}"), w.name, w.id, BlendMode.Normal, emptyList(),
                DrawableMesh(points, FloatArray(points.size), intArrayOf()), null)
        }
        val geometry = CpuDeformationEvaluator().evaluate(model.copy(drawables=probes, glues=emptyList()).withDerivedRenderRoot(), parameters)
        return warps.zip(probes).mapNotNull { (warp,probe) -> geometry.worldPositions[probe.id]?.let { warp.id.raw to it } }.toMap()
    }

    fun paint(g: Graphics2D, model: PuppetModel, parameters: Map<ParameterId,Float>, viewport: CanvasViewport,
        ids: Set<String>, labels: Boolean = true, pointIndices: Boolean = false) {
        if(ids.isEmpty())return
        val pointsById=warpPoints(model, parameters, ids)
        for(w in model.deformers.filterIsInstance<Deformer.Warp>().filter { it.id.raw in ids }) {
            val p=pointsById[w.id.raw] ?: continue
            g.color=ComponentPalette.strong(w.id.raw); g.stroke=BasicStroke(1.3f)
            fun x(i:Int)=viewport.x(p[i*2]).toInt()
            fun y(i:Int)=viewport.yFromWorld(p[i*2+1]).toInt()
            for(r in 0..w.rows)for(c in 0..w.columns) {
                val i=r*(w.columns+1)+c
                if(c<w.columns)g.drawLine(x(i),y(i),x(i+1),y(i+1))
                if(r<w.rows)g.drawLine(x(i),y(i),x(i+w.columns+1),y(i+w.columns+1))
                g.fillOval(x(i)-2,y(i)-2,4,4)
                if(pointIndices)g.drawString(i.toString(),x(i)+3,y(i)-3)
            }
            if(labels) {
                val label="${w.name} [${w.id.raw}] ${w.columns}×${w.rows}"
                val x=x(0).coerceAtLeast(0); val y=y(0).coerceAtLeast(16)
                val color=g.color; g.color=Color(20,20,24,220)
                g.fillRect(x,y-14,g.fontMetrics.stringWidth(label)+6,17);g.color=color
                g.drawString(label,x+3,y)
            }
        }
    }
}
