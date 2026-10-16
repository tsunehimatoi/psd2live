package io.github.psd2live.core.interior

import org.umamo.runtime.model.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import java.util.TreeMap
import kotlin.math.*

/** Compact author-time representation used by NativeInteriorAuthoring; dense recipes remain separate.
 * Each mixed row is a Y curve gated by one local X hat. No Cartesian geometry table at playback.
 * Formula/contour/topology remain owned by InteriorSurface. */
internal object InteriorBasisKeyforms {
    data class Curve(val keys: FloatArray, val values: List<FloatArray>)
    data class Row(val left: Float, val center: Float, val right: Float, val curve: Curve)
    data class Bake(val x: Curve, val y: Curve, val rows: List<Row>, val errorPixels: Double,
                    val tolerancePixels: Double, val evaluations: Int, val visualRefinements: Int,
                    val safetyFailures: Int, val safetyScale: Float = 1f, val unscaledError: Double = 0.0,
                    val unscaledSafetyFailures: Int = 0, val swapAxes: Boolean = false) {
        val storedForms: Int get() = x.keys.size - 1 + y.keys.size - 1 + rows.sumOf { it.curve.keys.size - 1 }
        val rawBytes: Long get() = storedForms.toLong() * x.values.first().size * 4
    }

    private fun interpolate(curve: Curve, input: Float): FloatArray {
        var i = curve.keys.binarySearch(input)
        if (i >= 0) return curve.values[i].copyOf()
        i = (-i - 2).coerceIn(0, curve.keys.lastIndex - 1)
        val f = ((input - curve.keys[i]) / (curve.keys[i+1] - curve.keys[i])).coerceIn(0f, 1f)
        return FloatArray(curve.values[i].size) { curve.values[i][it]*(1-f) + curve.values[i+1][it]*f }
    }
    private fun weight(row: Row, input: Float): Float = when {
        input == row.center -> 1f
        input < row.left || input > row.right -> 0f
        input < row.center -> (input-row.left)/(row.center-row.left)
        else -> (row.right-input)/(row.right-row.center)
    }
    fun displacements(bake: Bake,x: Float,y: Float): FloatArray = localDisplacements(bake,
        if(bake.swapAxes)y else x,if(bake.swapAxes)x else y)
    private fun localDisplacements(bake: Bake, x: Float, y: Float): FloatArray {
        val a = interpolate(bake.x,x); val b = interpolate(bake.y,y)
        for (i in a.indices) a[i] += b[i]
        for (row in bake.rows) {
            val w = weight(row,x)
            if (w > 0f) { val r = interpolate(row.curve,y); for (i in a.indices) a[i] += w*r[i] }
        }
        if(bake.safetyScale!=1f)for(i in a.indices)a[i]*=bake.safetyScale
        return a
    }
    private fun distance(a: FloatArray,b: FloatArray): Double = (a.indices step 2).maxOf {
        hypot((a[it]-b[it]).toDouble(),(a[it+1]-b[it+1]).toDouble())
    }
    private fun peak(a: FloatArray): Double = (a.indices step 2).maxOf { hypot(a[it].toDouble(),a[it+1].toDouble()) }

    fun bake(mesh: InteriorMesh, raster: InteriorRaster, settings: InteriorSurfaceSettings,
             guides: List<InteriorGuide>, tolerancePixels: Double = .25, maxDepth: Int = 12,
             maxRawBytes: Long = 32_000_000, swapAxes: Boolean = false): Bake {
        require(tolerancePixels.isFinite() && tolerancePixels > 0 && maxDepth in 1..12)
        val source = mesh.positions(); val plan = InteriorSurface.compile(mesh,raster,settings,guides)
        var evaluations = 0
        val cache = object : LinkedHashMap<Long,FloatArray>(256,.75f,true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long,FloatArray>?) = size > 4096
        }
        fun exact(x: Float,y: Float): FloatArray = cache.getOrPut((x.toRawBits().toLong() shl 32) xor (y.toRawBits().toLong() and 0xffffffffL)) {
            evaluations++
            plan.evaluate(if(swapAxes)y else x,if(swapAxes)x else y).positions().let { p -> FloatArray(p.size) { p[it]-source[it] } }
        }
        fun curve(fn: (Float)->FloatArray, epsilon: Double): Curve {
            val values = TreeMap<Float,FloatArray>()
            for (p in listOf(-1f,0f,1f)) values[p] = fn(p)
            fun split(left: Float,right: Float,depth: Int) {
                val a = values.getValue(left); val b = values.getValue(right)
                val probes = listOf(.25f,.5f,.75f).map { f ->
                    val p = left+(right-left)*f
                    Triple(p,fn(p),FloatArray(a.size) { a[it]*(1-f)+b[it]*f })
                }
                val error=probes.maxOf { distance(it.second,it.third) }
                if (error <= epsilon) return
                require(depth < maxDepth) { "Basis curve exceeds refinement depth: interval=$left..$right error=$error target=$epsilon; no result published" }
                val mid = probes[1]; values[mid.first] = mid.second
                split(left,mid.first,depth+1); split(mid.first,right,depth+1)
            }
            split(-1f,0f,0);split(0f,1f,0)
            return Curve(values.keys.toFloatArray(),values.values.toList())
        }
        val x = curve({ exact(it,0f) },tolerancePixels*.08)
        val y = curve({ exact(0f,it) },tolerancePixels*.08)
        require((x.keys.size+y.keys.size-2).toLong()*source.size*4<=maxRawBytes) { "Basis baseline exceeds storage budget" }
        val rows = mutableListOf<Row>()
        val zero = FloatArray(source.size)
        val probeY = ((-32..32).map { it/32f } + (1..maxDepth).flatMap { listOf(2f.pow(-it),-2f.pow(-it)) }).distinct()
        fun residual(x: Float,y: Float): FloatArray {
            if (x == 0f || y == 0f) return zero
            val a=exact(x,y); val b=exact(x,0f); val c=exact(0f,y)
            return FloatArray(a.size) { a[it]-b[it]-c[it] }
        }
        fun add(left: Float,center: Float,right: Float,fn: (Float)->FloatArray): Boolean {
            if (probeY.maxOf { peak(fn(it)) } <= tolerancePixels*.06) return false
            val fitted = curve(fn,tolerancePixels*.18)
            rows += Row(left,center,right,fitted)
            require((x.keys.size+y.keys.size+rows.sumOf { it.curve.keys.size }).toLong()*source.size*4 <= maxRawBytes) {
                "Basis native data exceeds budget: baseline=${x.keys.size},${y.keys.size} rows=${rows.size} forms=${rows.sumOf { it.curve.keys.size }}; no result published"
            }
            return true
        }
        add(-1f,-1f,0f) { residual(-1f,it) }
        add(0f,1f,1f) { residual(1f,it) }
        var refinements = 0
        fun splitX(left: Float,right: Float,depth: Int) {
            val mid = (left+right)/2
            fun surplus(y: Float): FloatArray {
                val a=residual(mid,y);val b=residual(left,y);val c=residual(right,y)
                return FloatArray(a.size) { a[it]-(b[it]+c[it])*.5f }
            }
            val p = listOf(.25f,.5f,.75f)
            val error = probeY.maxOf { y -> p.maxOf { f ->
                val a=residual(left+(right-left)*f,y);val b=residual(left,y);val c=residual(right,y)
                distance(a,FloatArray(a.size) { b[it]*(1-f)+c[it]*f })
            } }
            if (error <= tolerancePixels*.45) return
            require(depth < maxDepth) { "Basis mixed shape exceeds refinement depth; no result published" }
            refinements++
            add(left,mid,right,::surplus)
            splitX(left,mid,depth+1);splitX(mid,right,depth+1)
        }
        splitX(-1f,0f,0);splitX(0f,1f,0)
        var maximum = 0.0;var unsafe = 0;var scale = 1.0
        val candidate=Bake(x,y,rows.toList(),0.0,tolerancePixels,evaluations,refinements,0,swapAxes=swapAxes)
        val probeX=((x.keys.toList()+rows.flatMap { listOf(it.left,it.center,it.right) }).distinct().sorted()).let { k ->
            k+(0 until k.lastIndex).flatMap { i -> listOf(.25f,.5f,.75f).map { k[i]+(k[i+1]-k[i])*it } }
        }
        for (a in probeX) for (b in probeY) {
            val d=localDisplacements(candidate,a,b)
            maximum=max(maximum,distance(d,exact(a,b)))
            if (!safe(mesh,source,d)) unsafe++
            scale=min(scale,safeScale(mesh,source,d))
        }
        val unscaledError=maximum;val unscaledUnsafe=unsafe
        val conservative=if(scale<1.0)candidate.copy(safetyScale=(scale*(1-1e-6)).toFloat()) else candidate
        maximum=0.0;unsafe=0
        for(a in probeX)for(b in probeY) {
            val d=localDisplacements(conservative,a,b)
            maximum=max(maximum,distance(d,exact(a,b)))
            if(!safe(mesh,source,d))unsafe++
        }
        // Keep safety separate from visual error: a failed experiment is never advertised as accepted.
        return conservative.copy(errorPixels=maximum,evaluations=evaluations,safetyFailures=unsafe,
            unscaledError=unscaledError,unscaledSafetyFailures=unscaledUnsafe)
    }

    /** Author-time conservative coefficient fit, not a playback constraint or a change to the reference.
     * Its entire displacement error is included in validation. Never used to label an over-tolerance fit ready. */
    private fun safeScale(mesh: InteriorMesh,source: FloatArray,d: FloatArray): Double {
        var scale=1.0
        fun root(a: Double,b: Double,c: Double): Double {
            if(abs(a)<1e-14)return if(abs(b)>1e-14 && -c/b>0)-c/b else Double.POSITIVE_INFINITY
            val disc=b*b-4*a*c
            if(disc<0)return Double.POSITIVE_INFINITY
            val q=-.5*(b+if(b>=0)sqrt(disc) else -sqrt(disc))
            val r1=q/a;val r2=if(q==0.0)Double.POSITIVE_INFINITY else c/q
            return min(if(r1>0)r1 else Double.POSITIVE_INFINITY,if(r2>0)r2 else Double.POSITIVE_INFINITY)
        }
        for(t in mesh.triangles) {
            val ax=source[t.a*2].toDouble();val ay=source[t.a*2+1].toDouble()
            val bx=source[t.b*2]-ax;val by=source[t.b*2+1]-ay
            val cx=source[t.c*2]-ax;val cy=source[t.c*2+1]-ay
            val area=bx*cy-by*cx
            val dx=d[t.b*2].toDouble()-d[t.a*2];val dy=d[t.b*2+1].toDouble()-d[t.a*2+1]
            val ex=d[t.c*2].toDouble()-d[t.a*2];val ey=d[t.c*2+1].toDouble()-d[t.a*2+1]
            val linear=(bx*ey+dx*cy-by*ex-dy*cx)/area;val quadratic=(dx*ey-dy*ex)/area
            scale=min(scale,min(root(quadratic,linear,.6),root(quadratic,linear,-1.4)))
        }
        return scale.coerceIn(0.0,1.0)
    }

    fun safe(mesh: InteriorMesh,source: FloatArray,delta: FloatArray): Boolean = mesh.triangles.all { t ->
        fun area(add: Boolean): Double {
            fun v(i: Int) = source[i].toDouble()+if(add)delta[i] else 0f
            return (v(t.b*2)-v(t.a*2))*(v(t.c*2+1)-v(t.a*2+1))-(v(t.b*2+1)-v(t.a*2+1))*(v(t.c*2)-v(t.a*2))
        }
        area(true)/area(false) in .39999..2.40001
    }

    /** Prototype on an unkeyed mesh. Existing projects/generators are not migrated or changed. */
    fun apply(model: PuppetModel,target: DrawableId,parameters: List<Parameter>,bake: Bake): PuppetModel {
        require(parameters.size==2 && parameters.all { it.kind==ParameterKind.BLEND_SHAPE && it.min < 0 && it.max > 0 && it.default == 0f && !it.repeat }) {
            "Experimental basis requires explicit new zero-neutral blend parameters; never convert an existing ordinary axis"
        }
        require(parameters.all { p -> model.parameters.single { it.id==p.id } == p })
        val d=model.drawables.single { it.id==target }
        require(d.geometryGrid==null && d.blendShapes.isEmpty()) { "Experimental basis output requires an unkeyed target" }
        val defaults=model.parameters.associate { it.id to it.default }
        val defaultValue: (ParameterId)->Float = { defaults[it]?:0f }
        val order=d.channelGrids.scalarAt(FormChannel.DRAW_ORDER,d.drawOrder,defaultValue)
        val opacity=d.channelGrids.scalarAt(FormChannel.OPACITY,d.opacity,defaultValue)
        val multiply=d.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR,d.multiplyColor,defaultValue)
        val screen=d.channelGrids.colorAt(FormChannel.SCREEN_COLOR,d.screenColor,defaultValue)
        fun parameter(axis: Int)=parameters[if(bake.swapAxes)1-axis else axis]
        fun value(axis: Int,v: Float) = if(v<0)-v*parameter(axis).min else v*parameter(axis).max
        fun binding(curve: Curve,axis: Int,row: Row?=null): BlendShapeBinding<MeshForm> {
            val neutral=curve.keys.indexOfFirst { it==0f };require(neutral>=0)
            val limits=if(row==null)emptyList() else listOf(BlendWeightLimit(parameter(0).id,
                listOf(row.left to if(row.left==row.center)1f else 0f,row.center to 1f,row.right to if(row.right==row.center)1f else 0f)
                    .distinctBy { it.first }.map { BlendWeightLimitPoint(value(0,it.first),it.second) }))
            return BlendShapeBinding(parameter(axis).id,curve.keys.map { value(axis,it) }.toFloatArray(),neutral,
                curve.values.mapIndexed { i,v -> if(i==neutral)null else MeshForm(FloatArray(v.size) { v[it]*bake.safetyScale },order,opacity,multiply,screen) },limits)
        }
        val bindings=listOf(binding(bake.x,0),binding(bake.y,1))+bake.rows.map { binding(it.curve,1,it) }
        return model.copy(drawables=model.drawables.map { if(it.id==target)it.copy(blendShapes=bindings) else it })
    }
}
