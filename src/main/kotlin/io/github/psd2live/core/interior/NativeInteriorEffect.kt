package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.colorAt
import java.io.*
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.*

/** Stored authoring result: playback/replay uses ordinary native blend fields and physics3. */
class NativeInteriorEffect internal constructor(
    val id: String, val target: String, val name: String, val enabled: Boolean,
    internal val settings: InteriorSurfaceSettings, internal val motion: InteriorMotionSettings,
    val sources: List<Parameter>, val created: Set<String>,
    internal val generated: List<Parameter>, internal val bindings: List<BlendShapeBinding<MeshForm>>,
    val groups: List<RigPhysicsEdit>, val meshStamp: String,
    internal val guides: List<InteriorGuide>, val errorPixels: Double, val tolerancePixels: Double, val sourceHash: String? = null,
) {
    init {
        require(listOf(id,target,name).all { it.isNotBlank() && it.none(Char::isISOControl) })
        require(sources.size==2 && sources.map { it.id }.distinct().size==2)
        require(sources.all { !it.repeat && it.min<it.default && it.default<it.max })
        require((sources+generated).map { it.id }.distinct().size==sources.size+generated.size)
        val known=(sources+generated).map { it.id }.toSet()
        require(bindings.all { it.parameterId in known && it.limits.all { l -> l.parameterId in known && l.points.all { p -> p.value.isFinite() && p.weight in 0f..1f } } })
        require(groups.flatMap { it.outputParameters }.distinct().size==groups.sumOf { it.outputParameters.size })
        require(groups.all { g -> g.outputParameters.all { id -> generated.any { it.id.raw==id } } })

        require(bindings.isNotEmpty() && bindings.sumOf { b -> b.forms.sumOf { it?.positionDeltas?.size?.toLong()?:0L } }<=16_000_000L)
        require(bindings.all { b -> b.keys.size==b.forms.size && b.neutralIndex in b.keys.indices && b.keys[b.neutralIndex]==0f && b.keys.all(Float::isFinite) && b.keys.asList().zipWithNext().all { (a,b)->a<b } })
    }
    val parameterIds get() = (sources.filter { it.id.raw in created } + generated).map { it.id.raw }
    val axes get() = bindings.map { it.parameterId.raw }.distinct()
    internal fun enabled(value: Boolean) = NativeInteriorEffect(id,target,name,value,settings,motion,sources,created,generated,bindings,groups,meshStamp,guides,errorPixels,tolerancePixels,sourceHash)
    fun issue(model: PuppetModel): String? {
        val d=model.drawables.singleOrNull { it.id.raw==target }?:return "interior.issue.target"
        val mesh=d.mesh?:return "interior.issue.target"
        if(InteriorKeyforms.stamp(mesh)!=meshStamp)return "interior.issue.mesh"
        if(sourceHash!=null && runCatching { LayerTexture.of(model,d).layer.contentHash }.getOrNull()!=sourceHash)return "interior.issue.source"
        if(sources.filterNot { it.id.raw in created }.any { p -> model.parameters.singleOrNull { it.id==p.id }?.let { it.min==p.min && it.max==p.max && it.default==p.default && !it.repeat }!=true })return "interior.issue.binding"
        return null
    }
    internal fun apply(model: PuppetModel): PuppetModel {
        val problem=issue(model)
        if(problem!=null)return model
        val d=model.drawables.single { it.id.raw==target }
        val definitions = sources.filter { it.id.raw in created } + generated
        require(definitions.all { p -> model.parameters.none { it.id == p.id } }) { "Interior generated parameter already has an owner" }
        val defaults=model.parameters.associate { it.id to it.default }
        val defaultValue: (ParameterId)->Float={ defaults[it]?:0f }
        val reference=org.umamo.runtime.eval.meshGridDefaultDeltas(d,defaultValue)
        val order=d.channelGrids.scalarAt(FormChannel.DRAW_ORDER,d.drawOrder,defaultValue)
        val opacity=d.channelGrids.scalarAt(FormChannel.OPACITY,d.opacity,defaultValue)
        val multiply=d.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR,d.multiplyColor,defaultValue)
        val screen=d.channelGrids.colorAt(FormChannel.SCREEN_COLOR,d.screenColor,defaultValue)
        val native=bindings.map { b -> b.copy(forms=b.forms.map { f -> f?.let { MeshForm(FloatArray(it.positionDeltas.size) { i -> it.positionDeltas[i]+(reference?.get(i)?:0f) },order,opacity,multiply,screen) } }) }
        return model.copy(parameters=model.parameters+definitions,drawables=if(!enabled)model.drawables else model.drawables.map {
            if(it.id.raw==target)it.copy(blendShapes=it.blendShapes+native) else it
        })
    }
    private val json by lazy { buildJsonObject {
        put("schema",1);put("id",id);put("target",target);put("name",name);put("enabled",enabled);put("meshStamp",meshStamp)
        put("errorPixels",errorPixels);put("tolerancePixels",tolerancePixels);sourceHash?.let { put("sourceHash",it) }
        put("settings",NativeInteriorAuthoring.settingsJson(settings));put("motion",motion.toJson())
        put("sources",JsonArray(sources.map(::parameterJson)));put("generated",JsonArray(generated.map(::parameterJson)))
        put("created",JsonArray(created.map(::JsonPrimitive)));put("groups",JsonArray(groups.map { it.toJson() }))
        put("guides",JsonArray(guides.map { g -> buildJsonObject {
            put("id",g.id);put("purpose",g.purpose.name);put("radius",g.radius);put("amount",g.amount);put("pitchOnly",g.pitchOnly)
            put("points",JsonArray(g.points.map { JsonArray(listOf(JsonPrimitive(it.x),JsonPrimitive(it.y))) }))
        } }))
        put("bindings",JsonArray(bindings.map { b -> buildJsonObject {
            put("parameter",b.parameterId.raw);put("keys",floats(b.keys));put("neutral",b.neutralIndex)
            put("limits",JsonArray(b.limits.map { l -> buildJsonObject { put("parameter",l.parameterId.raw);put("points",JsonArray(l.points.map { JsonArray(listOf(JsonPrimitive(it.value),JsonPrimitive(it.weight))) })) } }))
            put("forms",JsonArray(b.forms.map { f -> f?.let { buildJsonObject {
                put("positions",packed(f.positionDeltas));put("count",f.positionDeltas.size);put("order",f.drawOrder);put("opacity",f.opacity)
                put("multiply",floats(floatArrayOf(f.multiplyColor.red,f.multiplyColor.green,f.multiplyColor.blue)))
                put("screen",floats(floatArrayOf(f.screenColor.red,f.screenColor.green,f.screenColor.blue)))
            } }?:JsonNull }))
        } }))
    } }
    fun toJson(): JsonObject = json
    private val fingerprint by lazy { java.security.MessageDigest.getInstance("SHA-256").digest(json.toString().encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }
    override fun toString(): String = "NativeInteriorEffect($fingerprint)"
    override fun equals(other: Any?): Boolean = other is NativeInteriorEffect && fingerprint==other.fingerprint
    override fun hashCode(): Int = fingerprint.hashCode()
    companion object {
        private fun floats(a: FloatArray)=JsonArray(a.map(::JsonPrimitive))
        private fun parameterJson(p: Parameter)=buildJsonObject { put("id",p.id.raw);put("name",p.name);put("min",p.min);put("max",p.max);put("default",p.default);put("kind",p.kind.name) }
        private fun parameter(o: JsonObject)=Parameter(ParameterId(o.text("id")),o.text("name"),o.number("min"),o.number("max"),o.number("default"),ParameterKind.valueOf(o.text("kind")))
        private fun packed(a: FloatArray): String = ByteArrayOutputStream().also { out -> DataOutputStream(GZIPOutputStream(out)).use { s -> a.forEach(s::writeFloat) } }.toByteArray().let { Base64.getEncoder().encodeToString(it) }
        private fun unpacked(o: JsonObject): FloatArray {
            val count=o.getValue("count").jsonPrimitive.int;require(count in 2..2_000_000 && count%2==0)
            return DataInputStream(GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(o.text("positions"))))).use { s -> FloatArray(count) { s.readFloat().also { require(it.isFinite()) } }.also { require(s.read()==-1) } }
        }
        fun fromJson(o: JsonObject): NativeInteriorEffect {
            require(o.getValue("schema").jsonPrimitive.int==1)
            fun color(a: JsonElement)=a.jsonArray.map { it.jsonPrimitive.float }.let { ColorRgb(it[0],it[1],it[2]) }
            val raw=o.getValue("bindings").jsonArray
            require(raw.size in 1..16384)
            require(raw.sumOf { b -> b.jsonObject.getValue("forms").jsonArray.sumOf { f -> if(f==JsonNull)0L else f.jsonObject.getValue("count").jsonPrimitive.long } }<=16_000_000L)
            val bindings=raw.map { e -> val b=e.jsonObject
                BlendShapeBinding(ParameterId(b.text("parameter")),b.getValue("keys").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),b.getValue("neutral").jsonPrimitive.int,
                    b.getValue("forms").jsonArray.map { f -> if(f==JsonNull)null else f.jsonObject.let { MeshForm(unpacked(it),it.number("order"),it.number("opacity"),color(it.getValue("multiply")),color(it.getValue("screen"))) } },
                    b.getValue("limits").jsonArray.map { l -> l.jsonObject.let { v -> BlendWeightLimit(ParameterId(v.text("parameter")),v.getValue("points").jsonArray.map { p -> p.jsonArray.let { BlendWeightLimitPoint(it[0].jsonPrimitive.float,it[1].jsonPrimitive.float) } }) } })
            }
            val guides=o.getValue("guides").jsonArray.map { it.jsonObject.let { g -> InteriorGuide(g.text("id"),g.getValue("points").jsonArray.map { p -> p.jsonArray.let { InteriorPoint(it[0].jsonPrimitive.float,it[1].jsonPrimitive.float) } },InteriorGuidePurpose.valueOf(g.text("purpose")),g.number("radius"),g.number("amount"),g.getValue("pitchOnly").jsonPrimitive.boolean) } }
            return NativeInteriorEffect(o.text("id"),o.text("target"),o.text("name"),o.getValue("enabled").jsonPrimitive.boolean,NativeInteriorAuthoring.settings(o.getValue("settings").jsonObject),InteriorMotionSettings.fromJson(o.getValue("motion").jsonObject),o.getValue("sources").jsonArray.map { parameter(it.jsonObject) },o.getValue("created").jsonArray.map { it.jsonPrimitive.content }.toSet(),o.getValue("generated").jsonArray.map { parameter(it.jsonObject) },bindings,o.getValue("groups").jsonArray.map { RigPhysicsEdit.fromJson(it.jsonObject) },o.text("meshStamp"),guides,o.getValue("errorPixels").jsonPrimitive.double,o.getValue("tolerancePixels").jsonPrimitive.double,o["sourceHash"]?.jsonPrimitive?.contentOrNull)
        }
        private fun JsonObject.text(k: String)=getValue(k).jsonPrimitive.content
        private fun JsonObject.number(k: String)=getValue(k).jsonPrimitive.float
    }
}

internal object NativeInteriorAuthoring {
    fun settingsJson(s: InteriorSurfaceSettings)=buildJsonObject { put("horizontal",s.horizontal);put("vertical",s.vertical);put("bulge",s.bulge);put("press",s.press);put("folds",s.folds);put("contour",s.contour) }
    fun settings(o: JsonObject)=InteriorSurfaceSettings(o.getValue("horizontal").jsonPrimitive.float,o.getValue("vertical").jsonPrimitive.float,o.getValue("bulge").jsonPrimitive.float,o.getValue("press").jsonPrimitive.float,o.getValue("folds").jsonPrimitive.float,o.getValue("contour").jsonPrimitive.float)

    /** UI and MCP share this authoring command; no runtime or image-space formulas live in the UI. */
    fun put(preview: RigPreviewModel,overlay: RigEditOverlay,o: JsonObject): RigEditOverlay {
        val target=o.getValue("target").jsonPrimitive.content.removePrefix("mesh:")
        val old=overlay.interiorEffects.singleOrNull { it.target==target }
        val id=old?.id?:"Interior"+java.security.MessageDigest.getInstance("SHA-256").digest(target.toByteArray()).take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
        if("enabled" in o && o.keys.all { it in setOf("target","enabled","state","project_id","request_id","task_id") } && old!=null)
            return overlay.copy(interiorEffects=overlay.interiorEffects.map { if(it.id==id)it.enabled(o.getValue("enabled").jsonPrimitive.boolean) else it })
        val authored=preview.authored.rig.puppet
        val d=requireNotNull(authored.drawables.singleOrNull { it.id.raw==target }) { "Select a generated mesh" }
        val mesh=requireNotNull(d.mesh)
        val layerId=preview.rig.layerIdByDrawableId[target]?:target
        val layer=requireNotNull(preview.analysis.layers.singleOrNull { it.source.id.raw==layerId }) { "Source pixels unavailable" }
        val resolved=LayerTexture.of(authored,d)
        val texture=resolved.texture
        val pixels=layer.source.textureLayer.raster
        val raster=InteriorRaster(pixels.width,pixels.height,pixels.rgba)
        val xy=FloatArray(mesh.positions.size)
        for(i in 0 until mesh.vertexCount) { val p=texture.rasterOfUv(mesh.uvs[i*2],mesh.uvs[i*2+1]);xy[i*2]=p[0];xy[i*2+1]=p[1] }
        val source=InteriorMesh(xy,mesh.indices)
        val s=o["settings"]?.jsonObject?.let(::settings)?:old?.settings?:InteriorSurfaceSettings()
        val motion=o["motion"]?.jsonObject?.let(InteriorMotionSettings::fromJson)?:old?.motion?:InteriorMotionSettings()
        val bindings=o["parameters"]?.jsonArray?.mapIndexed { axis,e -> val p=e.jsonPrimitive.content
            if(p.isBlank())Parameter(ParameterId("Param${id}${if(axis==0)"X" else "Y"}"),"${d.name} ${if(axis==0)"X" else "Y"}",-30f,30f,0f) else authored.parameters.singleOrNull { it.id.raw==p }?:old?.sources?.singleOrNull { it.id.raw==p }?:error("Unknown source parameter: $p")
        }?:old?.sources?.map { p -> authored.parameters.singleOrNull { it.id==p.id }?:p }?:listOf("X","Y").mapIndexed { axis,label ->
            val standard=if(axis==0)StandardParameters.BODY_X else StandardParameters.BODY_Y
            authored.parameters.singleOrNull { it.id==standard && !it.repeat && it.min<it.default && it.default<it.max }
                ?:Parameter(ParameterId("Param${id}$label"),"${d.name} $label",-30f,30f,0f)
        }
        require(bindings.size==2 && bindings.map { it.id }.distinct().size==2 && bindings.all { !it.repeat && it.min<it.default && it.default<it.max }) { "Choose two distinct parameters with an interior neutral value" }
        val created=bindings.filter { authored.parameters.none { p -> p.id==it.id } }.mapTo(linkedSetOf()) { it.id.raw }
        val guides=o["guides"]?.jsonArray?.mapIndexed { i,e -> val g=e.jsonObject
            InteriorGuide(g["id"]?.jsonPrimitive?.content?:"fold$i",g.getValue("points").jsonArray.map { p -> p.jsonArray.let { InteriorPoint(it[0].jsonPrimitive.float,it[1].jsonPrimitive.float) } },InteriorGuidePurpose.valueOf(g["purpose"]?.jsonPrimitive?.content?:"FOLD"),g["radius"]?.jsonPrimitive?.float?:16f,g["amount"]?.jsonPrimitive?.float?:1f,g["pitchOnly"]?.jsonPrimitive?.boolean?:false)
        }?:old?.guides.orEmpty()
        val bake=InteriorBasisKeyforms.bake(source,raster,s,guides,tolerancePixels=old?.tolerancePixels?:4.0)
        require(bake.safetyFailures==0) { "The selected settings exceed the mesh area limits" }
        val canonical=listOf("X","Y").map { axis -> Parameter(ParameterId("${id}Source$axis"),axis,-30f,30f,0f,ParameterKind.BLEND_SHAPE) }
        val isolated=authored.copy(parameters=canonical,parts=emptyList(),deformers=emptyList(),drawables=listOf(d.copy(parentDeformerId=null,mesh=DrawableMesh(xy,mesh.uvs,mesh.indices),geometryGrid=null,blendShapes=emptyList())),rootChildren=listOf(OrgChild.Drawable(d.id)))
        val static=InteriorBasisKeyforms.apply(isolated,d.id,canonical,bake)
        val plan=InteriorMotionGenerator.prepare(static,d.id,canonical,motion,owner=id,physicsFps=overlay.physicsFps.takeIf { it>0 }?:RigEditOverlay.DEFAULT_PHYSICS_FPS)
        if(motion.enabled) {
            val controller=plan.controller();controller.sample(0f,0f,0f)
            val evaluator=org.umamo.render.eval.CpuDeformationEvaluator()
            for(frame in 1..600) {
                if(Thread.currentThread().isInterrupted)throw java.util.concurrent.CancellationException("Interior generation cancelled")
                val time=frame/60.0
                val x=if(time<4)if(frame%48<24)1f else -1f else sin(time*3.7).toFloat()
                val y=if(time<4)if(frame%36<18)-1f else 1f else cos(time*4.3).toFloat()
                val pose=controller.sample(x,y,1f/60)
                val world=evaluator.evaluate(plan.model,pose).worldPositions.getValue(d.id)
                val delta=FloatArray(xy.size) { (if(it%2==0)world[it] else -world[it])-xy[it] }
                require(InteriorBasisKeyforms.safe(source,xy,delta)) { "Dynamic propagation folds this mesh; reduce propagation time or strength" }
            }
        }
        // Each local displacement is the material-space differential of the original mesh's UV map.
        // Existing geometry, UVs, skin weights and deformer hierarchy are never rewritten.
        val jac=Array(mesh.vertexCount) { DoubleArray(5) }
        for(t in mesh.indices.indices step 3) {
            val a=mesh.indices[t];val b=mesh.indices[t+1];val c=mesh.indices[t+2]
            val ux=(xy[b*2]-xy[a*2]).toDouble();val uy=(xy[b*2+1]-xy[a*2+1]).toDouble();val vx=(xy[c*2]-xy[a*2]).toDouble();val vy=(xy[c*2+1]-xy[a*2+1]).toDouble()
            val det=ux*vy-uy*vx;require(abs(det)>1e-12) { "Texture topology is degenerate" }
            val lx=(mesh.positions[b*2]-mesh.positions[a*2]).toDouble();val ly=(mesh.positions[b*2+1]-mesh.positions[a*2+1]).toDouble();val mx=(mesh.positions[c*2]-mesh.positions[a*2]).toDouble();val my=(mesh.positions[c*2+1]-mesh.positions[a*2+1]).toDouble()
            for(i in listOf(a,b,c)) { val j=jac[i];j[0]+=(lx*vy-mx*uy)/det;j[1]+=(mx*ux-lx*vx)/det;j[2]+=(ly*vy-my*uy)/det;j[3]+=(my*ux-ly*vx)/det;j[4]++ }
        }
        fun range(axis: Int): Pair<Float,Float> { val p=bindings[axis];val k=60f/(p.max-p.min);return k*(p.min-p.default) to k*(p.max-p.default) }
        val stages=plan.stageParameters
        val generated=plan.model.parameters.filter { p -> canonical.none { it.id==p.id } }.map { p -> val axis=stages.indexOfFirst { p.id in it }.let { stage -> stages[stage].indexOf(p.id) };val r=range(axis);p.copy(min=r.first,max=r.second) }
        fun axis(id: ParameterId)=canonical.indexOfFirst { it.id==id }.takeIf { it>=0 }?:stages.first { id in it }.indexOf(id)
        fun remap(id: ParameterId)=canonical.indexOfFirst { it.id==id }.let { if(it<0)id else bindings[it].id }
        fun key(id: ParameterId,value: Float): Float { val a=axis(id);val q=value/30f;return if(id in canonical.map { it.id })bindings[a].let { it.default+q*(if(q<0)it.default-it.min else it.max-it.default) } else range(a).let { if(q<0)-q*it.first else q*it.second } }
        val rawForms=plan.model.drawables.single().blendShapes.map { b -> b.copy(parameterId=remap(b.parameterId),keys=b.keys.map { key(b.parameterId,it) }.toFloatArray(),limits=b.limits.map { l -> l.copy(parameterId=remap(l.parameterId),points=l.points.map { it.copy(value=key(l.parameterId,it.value)) }) },forms=b.forms.map { f -> f?.let { value -> MeshForm(FloatArray(value.positionDeltas.size) { i -> val j=jac[i/2];require(j[4]>0);val x=value.positionDeltas[i/2*2];val y=value.positionDeltas[i/2*2+1];((if(i%2==0)j[0]*x+j[1]*y else j[2]*x+j[3]*y)/j[4]).toFloat() },value.drawOrder,value.opacity,value.multiplyColor,value.screenColor) } }) }
        val groups=plan.groups.map { g -> val input=g.inputs.single();val a=axis(ParameterId(input.parameter));val p=bindings[a];val r=range(a)
            val external=canonical.any { it.id.raw==input.parameter }
            g.copy(inputs=listOf(input.copy(parameter=remap(ParameterId(input.parameter)).raw)),normalization=g.normalization.copy(angleMin=r.first,angleMax=r.second,angleDefault=(r.first+r.second)/2))
        }
        // Cubism blend shapes always have neutral VALUE zero. Existing sources may have another
        // default; split off F(0), including gated constants, without moving the original rest mesh.
        val offset=Parameter(ParameterId("Param${id}Offset"),"${d.name} offset",0f,30f,30f,ParameterKind.BLEND_SHAPE)
        var needsOffset=false
        fun at(b: BlendShapeBinding<MeshForm>,v: Float): FloatArray {
            val count=b.forms.firstNotNullOf { it?.positionDeltas?.size }
            val k=b.keys.binarySearch(v);if(k>=0)return b.forms[k]?.positionDeltas?.copyOf()?:FloatArray(count)
            val a=(-k-2).coerceIn(0,b.keys.lastIndex-1);val q=((v-b.keys[a])/(b.keys[a+1]-b.keys[a])).coerceIn(0f,1f)
            return FloatArray(count) { (b.forms[a]?.positionDeltas?.get(it)?:0f)*(1-q)+(b.forms[a+1]?.positionDeltas?.get(it)?:0f)*q }
        }
        fun weight(l: BlendWeightLimit,v: Float): Float {
            val p=l.points;if(v<=p.first().value)return p.first().weight;if(v>=p.last().value)return p.last().weight
            val i=p.indexOfLast { it.value<=v };val q=(v-p[i].value)/(p[i+1].value-p[i].value);return p[i].weight*(1-q)+p[i+1].weight*q
        }
        val forms=rawForms.flatMap { b ->
            val zero=at(b,0f)
            if(zero.all { it==0f } && b.keys[b.neutralIndex]==0f)listOf(b) else {
                require(b.limits.size<=1)
                val template=b.forms.firstNotNullOf { it }
                fun form(a: FloatArray)=MeshForm(a,template.drawOrder,template.opacity,template.multiplyColor,template.screenColor)
                val keys=(b.keys.toList()+0f).distinct().sorted().toFloatArray()
                val parts=mutableListOf(b.copy(keys=keys,neutralIndex=keys.indexOfFirst { it==0f },forms=keys.map { v -> if(v==0f)null else form(at(b,v).let { a -> FloatArray(a.size) { a[it]-zero[it] } }) }))
                val gate=b.limits.singleOrNull();val w0=gate?.let { weight(it,0f) }?:1f
                if(gate!=null) {
                    val gkeys=(gate.points.map { it.value }+0f).distinct().sorted().toFloatArray()
                    parts+=BlendShapeBinding(gate.parameterId,gkeys,gkeys.indexOfFirst { it==0f },gkeys.map { v -> if(v==0f)null else form(FloatArray(zero.size) { zero[it]*(weight(gate,v)-w0) }) })
                }
                if(w0!=0f && zero.any { it!=0f }) { needsOffset=true;parts+=BlendShapeBinding(offset.id,floatArrayOf(0f,30f),0,listOf(null,form(FloatArray(zero.size) { zero[it]*w0 }))) }
                parts
            }
        }
        val effect=NativeInteriorEffect(id,target,d.name,o["enabled"]?.jsonPrimitive?.boolean?:old?.enabled?:true,s,motion,bindings,created,generated+if(needsOffset)listOf(offset)else emptyList(),forms,groups,InteriorKeyforms.stamp(mesh),guides,bake.errorPixels,bake.tolerancePixels,resolved.layer.contentHash)
        val others=overlay.interiorEffects.filterNot { it.id==id }
        require(others.none { it.target==target || it.parameterIds.any { p -> p in effect.parameterIds } }) { "Interior output already owned" }
        effect.apply(DocumentGenerators.generate(authored,overlay.copy(interiorEffects=others)))
        return overlay.copy(interiorEffects=others+effect)
    }
}
