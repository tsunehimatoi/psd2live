package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.edit.withParameterCreated
import org.umamo.runtime.keyform.isDense
import org.umamo.runtime.model.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Existing parameters are read, never claimed or recreated. Creation is an explicit authoring choice. */
data class InteriorParameter(val definition: Parameter, val create: Boolean = false)

/** Replayable authoring result, analogous to a simulation bake; no image processing during playback/replay. */
class RigInteriorEdit internal constructor(val id: String, val name: String, val target: String,
    val parameters: List<InteriorParameter>, internal val settings: InteriorSurfaceSettings,
    internal val guides: List<InteriorGuide>, internal val registration: InteriorRegistration,
    internal val bake: InteriorKeyformBake) {
    init {
        require(listOf(id,name,target).all { it.isNotBlank() && it.none(Char::isISOControl) })
        require(parameters.size == 2 && parameters.map { it.definition.id }.distinct().size == 2)
        require(parameters.all { p -> p.definition.run { kind == ParameterKind.NORMAL && !repeat && listOf(min,max,default).all(Float::isFinite) && min < default && default < max } })
        require(parameters.indices.all { bake.grid.axes[it].parameterId == parameters[it].definition.id &&
            bake.grid.axes[it].keys.first() == parameters[it].definition.min && bake.grid.axes[it].keys.last() == parameters[it].definition.max &&
            bake.grid.axes[it].keys.any { key -> key == parameters[it].definition.default } })
    }
    val parameterIds: List<String> get() = parameters.map { it.definition.id.raw }
    val createdParameterIds: List<String> get() = parameters.filter { it.create }.map { it.definition.id.raw }

    private val json: JsonObject by lazy {
        val grid = bake.grid
        val buffer = ByteBuffer.allocate(grid.cells.size*grid.cells.first().form.positionDeltas.size*4)
        for (cell in grid.cells.sortedBy { grid.linearIndexOf(it.coordinate) }) cell.form.positionDeltas.forEach { buffer.putFloat(it) }
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(buffer.array()) } }.toByteArray()
        buildJsonObject {
            put("schema",1); put("id",id); put("name",name); put("target",target)
            put("parameters",JsonArray(parameters.map { binding -> binding.definition.let { p -> buildJsonObject {
                put("id",p.id.raw); put("name",p.name); put("min",p.min); put("max",p.max); put("default",p.default); put("create",binding.create)
            } } }))
            put("settings",buildJsonObject {
                put("horizontal",settings.horizontal); put("vertical",settings.vertical); put("bulge",settings.bulge)
                put("press",settings.press); put("folds",settings.folds); put("contour",settings.contour)
            })
            put("guides",JsonArray(guides.map { g -> buildJsonObject {
                put("id",g.id); put("purpose",g.purpose.name); put("radius",g.radius); put("amount",g.amount); put("pitchOnly",g.pitchOnly)
                put("points",floats(g.points.flatMap { listOf(it.x.toFloat(),it.y.toFloat()) }))
            } }))
            put("registration",JsonArray(listOf(registration.a,registration.b,registration.c,registration.d,registration.tx,registration.ty).map(::JsonPrimitive)))
            put("bake",buildJsonObject {
                put("meshStamp",bake.meshStamp); put("sourceHash",bake.sourceHash); put("errorPixels",bake.errorPixels); put("tolerancePixels",bake.tolerancePixels)
                put("keys",JsonArray(grid.axes.map { floats(it.keys.toList()) })); put("scalars",grid.cells.first().form.positionDeltas.size)
                put("encoding","float32-be-gzip-base64"); put("forms",Base64.getEncoder().encodeToString(compressed))
            })
        }
    }
    fun toJson(): JsonObject = json
    private val fingerprint by lazy { java.security.MessageDigest.getInstance("SHA-256").digest(json.toString().encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }
    override fun toString() = "RigInteriorEdit($fingerprint)"
    override fun equals(other: Any?) = other is RigInteriorEdit && fingerprint == other.fingerprint
    override fun hashCode() = fingerprint.hashCode()

    companion object {
        private fun floats(values: List<Float>) = JsonArray(values.map(::JsonPrimitive))
        fun fromJson(o: JsonObject): RigInteriorEdit {
            require(o.getValue("schema").jsonPrimitive.int == 1) { "Unsupported interior recipe schema" }
            val parameters = o.getValue("parameters").jsonArray.map { v -> v.jsonObject.let { p ->
                InteriorParameter(Parameter(ParameterId(p.text("id")),p.text("name"),p.float("min"),p.float("max"),p.float("default")),p.getValue("create").jsonPrimitive.boolean)
            } }
            require(parameters.size == 2)
            val s = o.getValue("settings").jsonObject
            val settings = InteriorSurfaceSettings(s.float("horizontal"),s.float("vertical"),s.float("bulge"),s.float("press"),s.float("folds"),s.float("contour"))
            val guides = o.getValue("guides").jsonArray.map { v -> v.jsonObject.let { g ->
                val points = g.getValue("points").jsonArray; require(points.size%2 == 0)
                InteriorGuide(g.text("id"),points.chunked(2).map { InteriorPoint(it[0].jsonPrimitive.float,it[1].jsonPrimitive.float) },
                    InteriorGuidePurpose.valueOf(g.text("purpose")),g.float("radius"),g.float("amount"),g.getValue("pitchOnly").jsonPrimitive.boolean)
            } }
            val r = o.getValue("registration").jsonArray.map { it.jsonPrimitive.double }; require(r.size == 6)
            val binding = InteriorRegistration(r[0],r[1],r[2],r[3],r[4],r[5])
            val b = o.getValue("bake").jsonObject; require(b.text("encoding") == "float32-be-gzip-base64")
            val keys = b.getValue("keys").jsonArray.map { a -> a.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() }
            require(keys.size == 2 && keys.all { it.size in 3..129 })
            val scalars = b.getValue("scalars").jsonPrimitive.int
            val total = keys[0].size.toLong()*keys[1].size*scalars
            require(scalars > 0 && scalars%2 == 0 && total in 1..InteriorKeyforms.MAX_SCALARS.toLong()) { "Invalid interior bake size" }
            val bytes = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(b.text("forms")))).use { it.readNBytes(total.toInt()*4+1) }
            require(bytes.size == total.toInt()*4) { "Interior bake payload size mismatch" }
            val buffer = ByteBuffer.wrap(bytes)
            val cells = keys[1].indices.flatMap { y -> keys[0].indices.map { x ->
                KeyformCell(intArrayOf(x,y),MeshDeltaForm(FloatArray(scalars) { buffer.float }))
            } }
            val bake = InteriorKeyformBake(KeyformGrid(parameters.indices.map { KeyformAxis(parameters[it].definition.id,keys[it]) },cells),
                b.text("meshStamp"),b.getValue("errorPixels").jsonPrimitive.double,b.getValue("tolerancePixels").jsonPrimitive.double,b.text("sourceHash"))
            return RigInteriorEdit(o.text("id"),o.text("name"),o.text("target"),parameters,settings,guides,binding,bake)
        }
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonObject.float(key: String) = getValue(key).jsonPrimitive.float
    }
}

/** Adds an ordinary two-axis Mesh grid, preserving every unrelated authoring field and axis. */
internal object InteriorGenerator {
    fun apply(model: PuppetModel, edit: RigInteriorEdit): PuppetModel {
        val drawable = requireNotNull(model.drawables.singleOrNull { it.id.raw == edit.target }) { "Interior target missing: ${edit.target}" }
        val mesh = requireNotNull(drawable.mesh)
        require(InteriorKeyforms.stamp(mesh) == edit.bake.meshStamp) { "Interior target geometry changed; regenerate its surface keyforms" }
        var current = model
        for (binding in edit.parameters) {
            val p = binding.definition
            val actual = current.parameters.singleOrNull { it.id == p.id }
            require(actual != null || binding.create) { "Interior parameter missing: ${p.id.raw}" }
            if (actual == null) current = current.withParameterCreated(p.id,p.name).let { created -> created.copy(parameters = created.parameters.map { if (it.id == p.id) p else it }) }
            else require(actual.kind == ParameterKind.NORMAL && !actual.repeat && actual.min == p.min && actual.max == p.max && actual.default == p.default) {
                "Interior parameter range changed; regenerate keyforms: ${p.id.raw}"
            }
        }
        val previous = drawable.geometryGrid ?: KeyformGrid(emptyList(),listOf(KeyformCell(intArrayOf(),MeshDeltaForm(FloatArray(mesh.positions.size)))))
        require(previous.isDense && previous.axes.none { it.parameterId.raw in edit.parameterIds }) { "Interior target axes already have an owner; existing keyforms are preserved" }
        require(previous.axes.size+2 <= 4) { "Interior grid would exceed the native evaluator's four continuous axes" }
        require(previous.cells.size.toLong()*edit.bake.grid.cells.size*mesh.positions.size <= InteriorKeyforms.MAX_SCALARS) { "Interior combined grid exceeds authoring storage budget" }
        require(previous.cells.all { it.form.positionDeltas.size == mesh.positions.size })
        val cells = edit.bake.grid.cells.flatMap { shape -> previous.cells.map { base ->
            KeyformCell(base.coordinate+shape.coordinate,MeshDeltaForm(FloatArray(mesh.positions.size) { base.form.positionDeltas[it]+shape.form.positionDeltas[it] }))
        } }
        val next = drawable.copy(geometryGrid = KeyformGrid(previous.axes+edit.bake.grid.axes,cells))
        return current.copy(drawables = current.drawables.map { if (it.id == next.id) next else it })
    }
}

/** Shared authoring entry: later native UI and MCP must call this, rather than maintaining separate generators. */
internal object InteriorAuthoring {
    fun prepare(id: String,name: String,target: Drawable,raster: InteriorRaster,parameters: List<InteriorParameter>,
        settings: InteriorSurfaceSettings = InteriorSurfaceSettings(),guides: List<InteriorGuide> = emptyList(),
        registration: InteriorRegistration = InteriorRegistration(),tolerancePixels: Double = .05): RigInteriorEdit {
        val bake = InteriorKeyforms.bake(requireNotNull(target.mesh),raster,settings,guides,registration,tolerancePixels,
            parameters = parameters.map { it.definition })
        return RigInteriorEdit(id,name,target.id.raw,parameters.toList(),settings,guides.map { it.copy(points = it.points.toList()) },registration,bake)
    }

    fun put(overlay: RigEditOverlay,authored: PuppetModel,edit: RigInteriorEdit): RigEditOverlay {
        val prior = overlay.interiorEdits.firstOrNull { it.id == edit.id }
        require(edit.createdParameterIds.none { id -> authored.parameters.any { it.id.raw == id } && id !in prior?.createdParameterIds.orEmpty() }) {
            "New interior parameter ID already exists; choose existing binding or a new ID"
        }
        val other = overlay.interiorEdits.filterNot { it.id == edit.id }
        val without = overlay.copy(interiorEdits = other)
        val graph = DocumentGenerators.graph(without)
        require(edit.parameterIds.all { DocumentGenerators.owner(graph,DocumentGenerators.keyform("mesh",edit.target,it)) == null }) { "Another generator owns the interior target axis" }
        require(edit.createdParameterIds.all { id -> DocumentGenerators.owner(graph,"parameter:$id") == null }) { "Another generator owns the new interior parameter" }
        InteriorGenerator.apply(DocumentGenerators.generate(authored,without),edit) // complete validation before publishing a new immutable overlay
        val edits = overlay.interiorEdits.toMutableList()
        val index = edits.indexOfFirst { it.id == edit.id }
        if (index < 0) edits += edit else edits[index] = edit
        return overlay.copy(interiorEdits = edits)
    }
}
