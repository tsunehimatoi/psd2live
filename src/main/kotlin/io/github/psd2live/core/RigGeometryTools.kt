package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.*
import kotlin.math.*

/** Geometry stays in parent-local units; selectors use a stable normalized rest domain. */
internal object RigGeometryTools {
    fun rotationHandleLength(model: PuppetModel, rotation: Deformer.Rotation): Float =
        rotation.handleLength ?: 100f

    data class Geometry(val points: FloatArray, val base: FloatArray, val domain: FloatArray,
        val rows: Int?, val columns: Int?, val axes: List<KeyformAxis>, val keyCount: Int,
        val name: String, val parent: String?, val rotationAngle: Float? = null)

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
            "rotation" -> {
                val rotation = model.deformers.single { it.id.raw == id } as Deformer.Rotation
                val grid = rotation.geometryGrid
                val cells = grid?.let(::cellsByLinearIndex)
                val corners = grid?.let { gridCorners(it) { p -> pose[p.raw] ?: params[p.raw]?.default ?: 0f } }
                var x=0f; var y=0f; var angle=0f; var scale=if(grid==null)1f else 0f
                corners?.forEach { corner -> cells?.get(corner.linearIndex)?.form?.let { form ->
                    x+=corner.weight*form.originX; y+=corner.weight*form.originY
                    angle+=corner.weight*form.angle; scale+=corner.weight*form.scale
                } }
                val length = rotationHandleLength(model, rotation)
                val radians=(angle+rotation.baseAngle)*PI.toFloat()/180f
                val points=floatArrayOf(x,y,x+cos(radians)*length*scale,y+sin(radians)*length*scale)
                Geometry(points,points.copyOf(),floatArrayOf(0f,0f,1f,1f),null,null,grid?.axes.orEmpty(),grid?.cells?.size ?: 0,rotation.name,rotation.parent?.raw,angle)
            }
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

    /**
     * The affine map from a drawable's parent-local positions to its atlas UVs, recovered from the mesh
     * itself, as `(a, b, e, c, d, f)` for `u = a·x + b·y + e` and `v = c·x + d·y + f`.
     *
     * Recovered rather than stored because every mesh the rig generator produces already satisfies it:
     * a vertex's UV is that vertex's own layer pixel placed in the atlas, so position and UV are two
     * affines of the same layer-local point and are therefore affine in each other. Recovering the map
     * from the mesh lets the canvas re-derive a UV from wherever the vertex has been dragged to without
     * the map ever being written down, and without a schema for it.
     *
     * Null when the mesh cannot determine one - fewer than three vertices, or all of them collinear.
     *
     * @param FloatArray positions The mesh's interleaved parent-local positions.
     * @param FloatArray uvs The mesh's interleaved atlas coordinates.
     * @return FloatArray? The six affine coefficients, or null when the mesh does not pin one down.
     */
    fun uvAffine(positions: FloatArray, uvs: FloatArray): FloatArray? {
        require(positions.size == uvs.size && positions.size % 2 == 0) { "Positions and UVs must match" }
        val count = positions.size / 2
        if (count < 3) return null
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var su = 0.0; var sv = 0.0; var sxu = 0.0; var syu = 0.0; var sxv = 0.0; var syv = 0.0
        for (i in 0 until count) {
            val x = positions[i * 2].toDouble(); val y = positions[i * 2 + 1].toDouble()
            val u = uvs[i * 2].toDouble(); val v = uvs[i * 2 + 1].toDouble()
            sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
            su += u; sv += v; sxu += x * u; syu += y * u; sxv += x * v; syv += y * v
        }
        val n = count.toDouble()
        val m = doubleArrayOf(sxx, sxy, sx, sxy, syy, sy, sx, sy, n)
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        // A collinear (or single-point) vertex set spans no area, so it cannot pin a 2-D affine down.
        if (abs(det) < 1e-12) return null
        fun solve(r0: Double, r1: Double, r2: Double): DoubleArray {
            val d0 = r0 * (m[4] * m[8] - m[5] * m[7]) - m[1] * (r1 * m[8] - m[5] * r2) + m[2] * (r1 * m[7] - m[4] * r2)
            val d1 = m[0] * (r1 * m[8] - m[5] * r2) - r0 * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * r2 - r1 * m[6])
            val d2 = m[0] * (m[4] * r2 - r1 * m[7]) - m[1] * (m[3] * r2 - r1 * m[6]) + r0 * (m[3] * m[7] - m[4] * m[6])
            return doubleArrayOf(d0 / det, d1 / det, d2 / det)
        }
        val u = solve(sxu, syu, su)
        val v = solve(sxv, syv, sv)
        val out = floatArrayOf(u[0].toFloat(), u[1].toFloat(), v[0].toFloat(), v[1].toFloat(), u[2].toFloat(), v[2].toFloat())
        return if (out.all(Float::isFinite)) out else null
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
        val b = bounds(g.points) // One immutable editing frame for this entire operation sequence.
        for (element in operations) {
            val op = element.jsonObject
            val type = op.text("type")
            require(type in setOf("translate", "scale", "rotate", "bend", "curve", "smooth", "sway", "arc", "landmarks")) { "Unknown operation: $type" }
            val fields = when(type) {
                "translate" -> setOf("delta")
                "scale" -> setOf("factors","pivot")
                "rotate" -> setOf("degrees","pivot")
                "bend" -> setOf("axis","amount")
                "curve" -> setOf("axis","controls")
                "sway", "arc" -> setOf("root","tip","degrees","softness","root_pin")
                "landmarks" -> setOf("from","to")
                else -> setOf("strength")
            }
            require((op.keys - fields - setOf("type","selection")).isEmpty()) { "Unexpected field for $type" }
            val required = when(type) {
                "translate" -> "delta"; "scale" -> "factors"; "rotate" -> "degrees"
                "bend" -> "amount"; "curve" -> "controls"; "sway", "arc" -> "degrees"; "landmarks" -> "from"
                else -> null
            }
            require(required == null || required in op) { "Missing $required for $type" }
            val pivot = op.vector("pivot", listOf(0.5f, 0.5f), 2)
            val selection = op["selection"]?.jsonObject ?: sharedSelection
            require((selection.keys - setOf("indices","rect","center","line","radius","feather","hardness")).isEmpty()) { "Unexpected selection field" }
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
            val root = op.vector("root", listOf(0.5f, 0f), 2)
            val tip = op.vector("tip", listOf(0.5f, 1f), 2)
            val softness = op.number("softness", 1f)
            val pin = op.number("root_pin", 0f)
            if(type == "sway" || type == "arc") {
                require("root" in op && "tip" in op) { "Sway needs explicit root and tip in normalized input bounds" }
                require(softness in 0f..8f && pin in 0f..0.95f)
                require(hypot((tip[0]-root[0])*b[2], (tip[1]-root[1])*b[3]) > 1e-6f) { "Root and tip must differ" }
            }
            val from = if(type=="landmarks") op.getValue("from").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.float } } else emptyList()
            val to = if(type=="landmarks") op.getValue("to").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.float } } else emptyList()
            if(type=="landmarks") {
                require(from.size in 1..64 && from.size==to.size && (from+to).all { it.size==2 && it.all(Float::isFinite) }) { "from/to need matching lists of 1..64 finite point pairs" }
                require(from.distinct().size==from.size) { "Source landmarks must be distinct" }
            }
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
                    "landmarks" -> {
                        var sum=0.0; var dx=0.0; var dy=0.0
                        for(k in from.indices) {
                            val fx=b[0]+from[k][0]*b[2]; val fy=b[1]+from[k][1]*b[3]
                            val distance=(x.toDouble()-fx).pow(2)+(y.toDouble()-fy).pow(2)
                            val ox=(to[k][0]-from[k][0])*b[2]; val oy=(to[k][1]-from[k][1])*b[3]
                            if(distance<1e-14) { dx=ox.toDouble();dy=oy.toDouble();sum=1.0;break }
                            val w=1.0/distance;sum+=w;dx+=ox*w;dy+=oy*w
                        }
                        nx=x+(dx/sum).toFloat();ny=y+(dy/sum).toFloat()
                    }
                    "sway" -> {
                        val rx=b[0]+root[0]*b[2]; val ry=b[1]+root[1]*b[3]
                        val tx=(tip[0]-root[0])*b[2]; val ty=(tip[1]-root[1])*b[3]
                        val along=((x-rx)*tx+(y-ry)*ty)/(tx*tx+ty*ty)
                        val t=((along-pin)/(1-pin)).coerceIn(0f,1f)
                        val a=degrees*PI.toFloat()/180f * if(t == 0f) 0f else t.pow(softness)
                        val dx=x-rx; val dy=y-ry
                        nx=rx+dx*cos(a)-dy*sin(a); ny=ry+dx*sin(a)+dy*cos(a)
                    }
                    "arc" -> {
                        val rx = b[0] + root[0] * b[2]; val ry = b[1] + root[1] * b[3]
                        val tx = (tip[0] - root[0]) * b[2]; val ty = (tip[1] - root[1]) * b[3]
                        val length = hypot(tx, ty); val ex = tx / length; val ey = ty / length
                        val along = (x - rx) * ex + (y - ry) * ey
                        val across = -(x - rx) * ey + (y - ry) * ex
                        val fixed = length * pin
                        val angle = degrees * PI.toFloat() / 180f
                        if (along > fixed && abs(angle) > 1e-6f) {
                            val radius = (length - fixed) / angle
                            val theta = (along - fixed) / radius
                            val cx = radius * sin(theta); val cy = radius * (1 - cos(theta))
                            nx = rx + ex * (fixed + cx - across * sin(theta)) - ey * (cy + across * cos(theta))
                            ny = ry + ey * (fixed + cx - across * sin(theta)) + ex * (cy + across * cos(theta))
                        }
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
        val hardness = s.number("hardness", 0f); require(hardness in 0f..0.95f)
        val falloff = ((d - hardness) / (1 - hardness)).coerceIn(0f, 1f)
        return BezierWarp.cubic(1f,1f,0f,0f,falloff)*BezierWarp.cubic(0f,0f,1f,1f,fade)
    }
}

private fun JsonObject.text(key: String): String = requireNotNull(this[key]?.jsonPrimitive?.contentOrNull) { "Missing $key" }
private fun JsonObject.number(key: String, default: Float): Float = (this[key]?.jsonPrimitive?.float ?: default).also { require(it.isFinite()) { "$key must be finite" } }
private fun JsonObject.vector(key: String, default: List<Float>, size: Int): List<Float> =
    (this[key]?.jsonArray?.map { it.jsonPrimitive.float } ?: default).also { require(it.size == size && it.all(Float::isFinite)) { "$key needs $size finite numbers" } }
