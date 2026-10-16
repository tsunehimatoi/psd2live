package io.github.psd2live.core.interior

import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.*

/** Explicit source-pixel -> mesh-parent registration. View zoom, DPR and atlas placement are not inputs. */
internal data class InteriorRegistration(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
    val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    init { require(listOf(a,b,c,d,tx,ty).all(Double::isFinite) && abs(a*d-b*c) > 1e-12) { "Invalid material registration" } }
    fun source(x: Double, y: Double): InteriorPoint {
        val det = a*d-b*c
        return InteriorPoint(((d*(x-tx)-c*(y-ty))/det).toFloat(), ((a*(y-ty)-b*(x-tx))/det).toFloat())
    }
    fun local(offsets: FloatArray) = FloatArray(offsets.size) { i ->
        val j = i/2*2
        (if (i%2 == 0) a*offsets[j]+c*offsets[j+1] else b*offsets[j]+d*offsets[j+1]).toFloat()
    }
}

/** A sampled two-axis surface. Keys use a wide normal parameter domain to respect native key snapping. */
internal class InteriorKeyformBake(val grid: KeyformGrid<MeshDeltaForm>, val meshStamp: String,
    val errorPixels: Double, val tolerancePixels: Double, val sourceHash: String) {
    init {
        require(errorPixels.isFinite() && tolerancePixels.isFinite() && tolerancePixels > 0 && errorPixels <= tolerancePixels)
        require(grid.axes.size == 2 && grid.axes.map { it.parameterId }.distinct().size == 2)
        require(grid.axes.all { axis -> axis.keys.size in 3..129 && axis.keys.all(Float::isFinite) &&
            axis.keys.asList().zipWithNext().all { (a,b) -> b-a > org.umamo.runtime.eval.EPS_SPAN } })
        require(grid.cells.size == grid.axes[0].keys.size*grid.axes[1].keys.size)
        val count = grid.cells.first().form.positionDeltas.size
        require(count > 0 && count%2 == 0 && grid.cells.all { it.form.positionDeltas.size == count && it.form.positionDeltas.all(Float::isFinite) })
        require(grid.cellsByLinearIndex.size == grid.cells.size && grid.cells.all { cell -> cell.coordinate.size == 2 &&
            cell.coordinate.indices.all { cell.coordinate[it] in grid.axes[it].keys.indices } })
    }
}

/** Authoring-only sampling; playback uses the project's ordinary keyform evaluator without surface math. */
internal object InteriorKeyforms {
    private data class ProbeKey(val x: Int,val y: Int,val x0: Int,val x1: Int,val y0: Int,val y1: Int)
    private data class ProbeResult(val error: Double,val safe: Boolean,val detail: String)
    private val axisX = ParameterId("__interior_x")
    private val axisY = ParameterId("__interior_y")
    const val DOMAIN = 30f
    const val MAX_SCALARS = 32_000_000

    fun stamp(mesh: DrawableMesh): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(8 + mesh.positions.size*4 + mesh.indices.size*4)
        bytes.putInt(mesh.positions.size).putInt(mesh.indices.size)
        mesh.positions.forEach { bytes.putFloat(it) }; mesh.indices.forEach { bytes.putInt(it) }
        return hash.digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun sample(grid: KeyformGrid<MeshDeltaForm>, x: Float, y: Float): FloatArray {
        val corners = requireNotNull(gridCorners(grid) { if (it == grid.axes[0].parameterId) x else y })
        val out = FloatArray(grid.cells.first().form.positionDeltas.size)
        for (corner in corners) {
            val value = grid.cellsByLinearIndex.getValue(corner.linearIndex).form.positionDeltas
            for (i in out.indices) out[i] += value[i]*corner.weight
        }
        return out
    }

    fun bake(mesh: DrawableMesh, raster: InteriorRaster, settings: InteriorSurfaceSettings,
        guides: List<InteriorGuide> = emptyList(), registration: InteriorRegistration = InteriorRegistration(),
        tolerancePixels: Double = .05, maxSegments: Int = 128,
        parameters: List<Parameter> = listOf(Parameter(axisX,"Interior X",-DOMAIN,DOMAIN,0f),
            Parameter(axisY,"Interior Y",-DOMAIN,DOMAIN,0f))): InteriorKeyformBake {
        require(tolerancePixels.isFinite() && tolerancePixels > 0 && maxSegments in listOf(2,4,8,16,32,64,128))
        require(parameters.size == 2 && parameters.map { it.id }.distinct().size == 2 && parameters.all {
            it.kind == ParameterKind.NORMAL && !it.repeat && it.min.isFinite() && it.max.isFinite() && it.default.isFinite() && it.min < it.default && it.default < it.max
        }) { "Interior shaping needs two distinct non-repeating normal parameters with interior defaults" }
        fun value(p: Parameter,t: Float) = p.default + t*(if (t < 0) p.default-p.min else p.max-p.default)
        fun normalized(p: Parameter,v: Float) = (v-p.default)/(if (v < p.default) p.default-p.min else p.max-p.default)
        val points = mesh.positions.asList().chunked(2).map { registration.source(it[0].toDouble(),it[1].toDouble()) }
        val source = InteriorMesh(points.flatMap { listOf(it.x.toFloat(),it.y.toFloat()) }.toFloatArray(),mesh.indices)
        val base = source.positions()
        val plan = InteriorSurface.compile(source,raster,settings,guides)
        val exactCache = object : LinkedHashMap<Long,FloatArray>(64,.75f,true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long,FloatArray>?) =
                size > max(16,min(4096,MAX_SCALARS/max(1,base.size)/4))
        }
        fun exact(x: Float,y: Float): FloatArray = exactCache.getOrPut((x.toRawBits().toLong() shl 32) xor (y.toRawBits().toLong() and 0xffffffffL)) {
            val p = plan.evaluate(x,y).positions()
            FloatArray(p.size) { p[it]-base[it] }
        }
        val cellCache = HashMap<Long,FloatArray>()
        val probeCache = HashMap<ProbeKey,ProbeResult>()
        val phases = listOf(mutableListOf(-1f,0f,1f),mutableListOf(-1f,0f,1f))
        while (true) {
            require(phases[0].size.toLong()*phases[1].size*mesh.positions.size <= MAX_SCALARS) { "Interior bake exceeds authoring storage budget: keys=${phases.map { it.size }}; no result published" }
            val keys = parameters.indices.map { axis -> phases[axis].map { value(parameters[axis],it) }.toFloatArray().also {
                it[0]=parameters[axis].min; it[it.lastIndex]=parameters[axis].max
                it[phases[axis].indexOf(0f)]=parameters[axis].default
            } }
            val cells = phases[1].indices.flatMap { y -> phases[0].indices.map { x ->
                KeyformCell(intArrayOf(x,y),MeshDeltaForm(cellCache.getOrPut((phases[0][x].toRawBits().toLong() shl 32) xor (phases[1][y].toRawBits().toLong() and 0xffffffffL)) { exact(phases[0][x],phases[1][y]) }))
            } }
            val grid = KeyformGrid(parameters.indices.map { KeyformAxis(parameters[it].id,keys[it]) },cells)
            var error = 0.0
            var worst = ""
            var failedPose = 0f to 0f
            var safe = true
            // Check interiors AND near-key native snap behaviour; endpoints alone cannot validate a bake.
            val regular = keys.map { k -> k.toList() + (0 until k.lastIndex).flatMap { i -> listOf(.25f,.5f,.75f).map { k[i]+(k[i+1]-k[i])*it } } }
            val poses = sequence {
                for (x in regular[0]) for (y in regular[1]) yield(x to y)
                for (axis in 0..1) for (key in keys[axis]) for (sign in listOf(-1,1)) {
                    val p = key + sign*org.umamo.runtime.eval.EPS_KEY*.99f
                    if (p in keys[axis].first()..keys[axis].last()) for (other in keys[1-axis])
                        yield(if (axis == 0) p to other else other to p)
                }
            }
            for ((x,y) in poses) {
                val bx = requireNotNull(org.umamo.runtime.eval.bindBracket(keys[0],x))
                val by = requireNotNull(org.umamo.runtime.eval.bindBracket(keys[1],y))
                val key = ProbeKey(x.toRawBits(),y.toRawBits(),keys[0][bx.index].toRawBits(),keys[0][if(bx.fraction==0f)bx.index else bx.index+1].toRawBits(),
                    keys[1][by.index].toRawBits(),keys[1][if(by.fraction==0f)by.index else by.index+1].toRawBits())
                // Adding a distant key does not change this pose's corner values, brackets or arithmetic.
                // Cache that proof by its actual native brackets, never just by pose or grid dimensions.
                val checked = probeCache.getOrPut(key) {
                    val expected = exact(normalized(parameters[0],x),normalized(parameters[1],y)); val actual = sample(grid,x,y)
                    var peak = 0.0; var detail = ""
                    for (i in actual.indices step 2) {
                        val e = hypot((actual[i]-expected[i]).toDouble(),(actual[i+1]-expected[i+1]).toDouble())
                        if (e > peak) { peak=e; detail="pose=$x,$y vertex=${i/2} expected=${expected[i]},${expected[i+1]} actual=${actual[i]},${actual[i+1]}" }
                    }
                    ProbeResult(peak,safe(source,base,actual),detail)
                }
                if (checked.error > error) { error=checked.error;worst=checked.detail }
                if (!checked.safe) safe=false
                if (error > tolerancePixels*.8 || !safe) { failedPose = x to y; break }
            }
            if (error <= tolerancePixels*.8 && safe) {
                val local = KeyformGrid(grid.axes,grid.cells.map { KeyformCell(it.coordinate,MeshDeltaForm(registration.local(it.form.positionDeltas))) })
                return InteriorKeyformBake(local,stamp(mesh),error,tolerancePixels,
                    MessageDigest.getInstance("SHA-256").digest(raster.rgba()).joinToString("") { "%02x".format(it.toInt() and 255) })
            }
            // Refine only the failed intervals, preserving every existing key. Uniform grids wasted
            // most keys where the clamped surface is smooth and missed its narrow transition regions.
            var refined = false
            val failed = listOf(failedPose.first,failedPose.second)
            val reference = exact(normalized(parameters[0],failed.first()),normalized(parameters[1],failed.last()))
            val ranked = (0..1).map { axis ->
                val bracket = requireNotNull(org.umamo.runtime.eval.bindBracket(keys[axis],failed[axis]))
                val lo = keys[axis][bracket.index]
                val hi = keys[axis][if(bracket.fraction==0f)bracket.index else bracket.index+1]
                fun endpoint(v: Float) = if(axis==0) exact(normalized(parameters[0],v),normalized(parameters[1],failed[1]))
                    else exact(normalized(parameters[0],failed[0]),normalized(parameters[1],v))
                val a=endpoint(lo);val b=endpoint(hi);var curvature=0.0
                for(i in reference.indices step 2) curvature=max(curvature,hypot(
                    (a[i]*(1-bracket.fraction)+b[i]*bracket.fraction-reference[i]).toDouble(),
                    (a[i+1]*(1-bracket.fraction)+b[i+1]*bracket.fraction-reference[i+1]).toDouble()))
                axis to curvature
            }.sortedByDescending { it.second }
            for ((axis,_) in ranked) {
                if (keys[axis].any { abs(it-failed[axis]) <= org.umamo.runtime.eval.EPS_KEY*1.01f }) continue
                if (phases[axis].size >= maxSegments+1) continue
                val i = keys[axis].indexOfLast { it < failed[axis] }.coerceIn(0,keys[axis].lastIndex-1)
                val middle = (phases[axis][i]+phases[axis][i+1])*.5f
                val actual = value(parameters[axis],middle)
                if (actual-keys[axis][i] <= org.umamo.runtime.eval.EPS_SPAN || keys[axis][i+1]-actual <= org.umamo.runtime.eval.EPS_SPAN) continue
                phases[axis].add(i+1,middle);refined = true;break
            }
            require(refined) { "Interior keyform sampling did not meet fidelity/safety: keys=${keys.map { it.size }}, error=$error px, safe=$safe, tolerance=$tolerancePixels px; $worst; no result published" }
        }
    }

    private fun safe(mesh: InteriorMesh, base: FloatArray, delta: FloatArray): Boolean = mesh.triangles.all { t ->
        fun area(p: FloatArray, add: Boolean): Double {
            fun v(i: Int) = p[i].toDouble() + if (add) delta[i].toDouble() else 0.0
            return (v(t.b*2)-v(t.a*2))*(v(t.c*2+1)-v(t.a*2+1)) - (v(t.b*2+1)-v(t.a*2+1))*(v(t.c*2)-v(t.a*2))
        }
        area(base,true)/area(base,false) in .39999..2.40001
    }
}
