package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.*
import kotlin.math.*

/** Geometry stays in parent-local units; selectors use a stable normalized rest domain. */
internal object RigGeometryTools {
    data class Geometry(val points: FloatArray, val base: FloatArray, val domain: FloatArray,
        val rows: Int?, val columns: Int?, val axes: List<KeyformAxis>, val keyCount: Int,
        val name: String, val parent: String?)

    fun geometry(model: PuppetModel, kind: String, id: String, pose: Map<String, Float>): Geometry {
        val params = model.parameters.associateBy { it.id.raw }
        require(pose.all { (id, v) -> params[id]?.let { v.isFinite() && v in it.min..it.max } == true }) { "Unknown or out-of-range parameter" }
        fun <T> sample(grid: KeyformGrid<T>?, base: FloatArray, values: (T) -> FloatArray): FloatArray {
            if (grid == null) return base.copyOf()
            val result = base.copyOf()
            val cells = cellsByLinearIndex(grid)
            val corners = requireNotNull(gridCorners(grid) { params[it.raw]?.let { p -> pose[it.raw] ?: p.default } ?: 0f }) { "No geometry at pose" }
            for (corner in corners) {
                val form = values(requireNotNull(cells[corner.linearIndex]).form)
                require(form.size == result.size) { "Malformed geometry" }
                for (i in result.indices) result[i] += corner.weight * form[i]
            }
            return result
        }
        return when (kind) {
            "warp" -> {
                val w = model.deformers.singleOrNull { it.id.raw == id } as? Deformer.Warp ?: error("Warp not found: $id")
                val domain = FloatArray((w.rows + 1) * (w.columns + 1) * 2)
                for (r in 0..w.rows) for (c in 0..w.columns) {
                    val i = (r * (w.columns + 1) + c) * 2
                    domain[i] = c.toFloat() / w.columns; domain[i + 1] = r.toFloat() / w.rows
                }
                val points = if (w.geometryGrid == null) domain.copyOf() else sample(w.geometryGrid, FloatArray(domain.size)) { it.controlPoints }
                Geometry(points, domain, domain, w.rows, w.columns, w.geometryGrid?.axes.orEmpty(), w.geometryGrid?.cells?.size ?: 0, w.name, w.parent?.raw)
            }
            "mesh" -> {
                val d = model.drawables.singleOrNull { it.id.raw == id } ?: error("Mesh not found: $id")
                val base = requireNotNull(d.mesh).positions
                val bounds = bounds(base)
                val domain = base.copyOf()
                for (i in domain.indices step 2) { domain[i] = (base[i] - bounds[0]) / bounds[2]; domain[i+1] = (base[i+1] - bounds[1]) / bounds[3] }
                Geometry(sample(d.geometryGrid, base) { it.positionDeltas }, base, domain, null, null,
                    d.geometryGrid?.axes.orEmpty(), d.geometryGrid?.cells?.size ?: 0, d.name, d.parentDeformerId?.raw)
            }
            else -> error("Expected warp or mesh")
        }
    }

    fun bounds(p: FloatArray): FloatArray {
        require(p.size >= 2 && p.size % 2 == 0 && p.all(Float::isFinite))
        val xs = p.indices.step(2).map { p[it] }; val ys = p.indices.step(2).map { p[it+1] }
        return floatArrayOf(xs.min(), ys.min(), (xs.max()-xs.min()).coerceAtLeast(1e-6f), (ys.max()-ys.min()).coerceAtLeast(1e-6f))
    }

    fun transform(g: Geometry, operations: JsonArray, selection: JsonObject = JsonObject(emptyMap()),
        range: JsonObject = JsonObject(emptyMap())): FloatArray {
        require((range.keys - setOf("radius", "feather")).isEmpty()) { "Unexpected range field" }
        val sharedSelection = JsonObject(selection + range)
        require(operations.size in 1..32) { "Use 1..32 ordered operations" }
        val result = g.points.copyOf()
        for (element in operations) {
            val op = element.jsonObject
            val type = op.text("type")
            require(type in setOf("translate", "scale", "rotate", "bend", "curve", "smooth")) { "Unknown operation: $type" }
            val fields = when(type) {
                "translate" -> setOf("delta")
                "scale" -> setOf("factors","pivot")
                "rotate" -> setOf("degrees","pivot")
                "bend" -> setOf("axis","amount")
                "curve" -> setOf("axis","controls")
                else -> setOf("strength")
            }
            require((op.keys - fields - setOf("type","selection")).isEmpty()) { "Unexpected field for $type" }
            val required = when(type) {
                "translate" -> "delta"; "scale" -> "factors"; "rotate" -> "degrees"
                "bend" -> "amount"; "curve" -> "controls"
                else -> null
            }
            require(required == null || required in op) { "Missing $required for $type" }
            val b = bounds(result)
            val pivot = op.vector("pivot", listOf(0.5f, 0.5f), 2)
            val selection = op["selection"]?.jsonObject ?: sharedSelection
            require((selection.keys - setOf("indices","rect","center","line","radius","feather")).isEmpty()) { "Unexpected selection field" }
            require(!("center" in selection && "line" in selection)) { "Choose point or line falloff" }
            require("radius" !in selection || "center" in selection || "line" in selection) { "radius requires center or line" }
            val weights = FloatArray(result.size / 2) { i -> weight(selection, g.domain[i*2], g.domain[i*2+1], i, result.size/2) }
            val delta = op.vector("delta", listOf(0f, 0f), 2)
            val factors = op.vector("factors", listOf(1f, 1f), 2)
            if (type == "scale") require(factors.all { it > 0f }) { "Scale factors must be positive" }
            val axis = op["axis"]?.jsonPrimitive?.content ?: "x"
            require(axis in setOf("x", "y"))
            val amount = op.number("amount", 0f)
            val degrees = op.number("degrees", 0f)
            val curve = op.vector("controls", listOf(0f, 0f, 0f, 0f), 4)
            val before = result.copyOf()
            for (i in weights.indices) {
                val j = i*2; val x = before[j]; val y = before[j+1]
                val u = g.domain[j]; val v = g.domain[j+1]
                var nx = x; var ny = y
                when (type) {
                    "translate" -> { nx += delta[0]*b[2]; ny += delta[1]*b[3] }
                    "scale" -> { val px=b[0]+pivot[0]*b[2]; val py=b[1]+pivot[1]*b[3]; nx=px+(x-px)*factors[0]; ny=py+(y-py)*factors[1] }
                    "rotate" -> { val a=degrees*PI.toFloat()/180f; val dx=x-b[0]-pivot[0]*b[2]; val dy=y-b[1]-pivot[1]*b[3]; nx=x+dx*(cos(a)-1)-dy*sin(a); ny=y+dx*sin(a)+dy*(cos(a)-1) }
                    "bend", "curve" -> {
                        val t = if (axis == "x") v else u
                        val d = if (type == "bend") BezierWarp.cubic(0f, amount*4/3, amount*4/3, 0f, t)
                            else BezierWarp.cubic(curve[0],curve[1],curve[2],curve[3],t)
                        if (axis == "x") nx += d*b[2] else ny += d*b[3]
                    }
                    "smooth" -> {
                        require(g.columns != null && g.rows != null) { "Smooth requires a warp grid" }
                        val c=i%(g.columns+1); val r=i/(g.columns+1)
                        val neighbors = listOfNotNull(if(c>0)i-1 else null, if(c<g.columns)i+1 else null,
                            if(r>0)i-g.columns-1 else null, if(r<g.rows)i+g.columns+1 else null)
                        val strength=op.number("strength",0.25f); require(strength in 0f..1f)
                        if (c>0 && c<g.columns && r>0 && r<g.rows) {
                            nx += (neighbors.map { before[it*2] }.average().toFloat()-x)*strength
                            ny += (neighbors.map { before[it*2+1] }.average().toFloat()-y)*strength
                        }
                    }
                }
                result[j] = x+(nx-x)*weights[i]; result[j+1] = y+(ny-y)*weights[i]
            }
            require(result.all(Float::isFinite)) { "Non-finite transform" }
        }
        return result
    }

    private fun weight(s: JsonObject, u: Float, v: Float, index: Int, count: Int): Float {
        val ids=s["indices"]?.jsonArray?.map { it.jsonPrimitive.int }
        if(ids != null) { require(ids.size <= 512 && ids.all { it in 0 until count }); if(index !in ids) return 0f }
        val rect=s.vector("rect", listOf(0f,0f,1f,1f),4)
        require(rect[0] < rect[2] && rect[1] < rect[3])
        if(u<rect[0] || u>rect[2] || v<rect[1] || v>rect[3]) return 0f
        val radius=s.number("radius",1f); require(radius>0)
        val center=s["center"]?.let { s.vector("center",emptyList(),2) }
        val line=s["line"]?.let { s.vector("line",emptyList(),4) }
        val d= when {
            line != null -> { val dx=line[2]-line[0]; val dy=line[3]-line[1]; val length=dx*dx+dy*dy; require(length>0)
                val t=(((u-line[0])*dx+(v-line[1])*dy)/length).coerceIn(0f,1f); hypot(u-line[0]-t*dx,v-line[1]-t*dy) / radius }
            center != null -> hypot(u-center[0],v-center[1])/radius
            else -> 0f
        }
        if(d>=1f)return 0f
        val edge=s.number("feather",0f); require(edge in 0f..0.5f)
        val fade=if(edge>0) (minOf(u-rect[0],rect[2]-u,v-rect[1],rect[3]-v)/edge).coerceIn(0f,1f) else 1f
        return BezierWarp.cubic(1f,1f,0f,0f,d)*BezierWarp.cubic(0f,0f,1f,1f,fade)
    }
}

private fun JsonObject.text(key: String): String = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "Missing $key" }
private fun JsonObject.number(key: String, default: Float): Float = (this[key]?.jsonPrimitive?.float ?: default).also { require(it.isFinite()) { "$key must be finite" } }
private fun JsonObject.vector(key: String, default: List<Float>, size: Int): List<Float> =
    (this[key]?.jsonArray?.map { it.jsonPrimitive.float } ?: default).also { require(it.size == size && it.all(Float::isFinite)) { "$key needs $size finite numbers" } }
