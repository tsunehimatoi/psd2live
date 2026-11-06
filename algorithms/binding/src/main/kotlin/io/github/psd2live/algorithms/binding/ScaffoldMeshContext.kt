package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.*
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId

/** Transfers source topology and pose measurements before any scaffold binding. */
object ScaffoldMeshContext {
    private const val SCHEMA = "scaffold_meshes/1"
    private const val INPUTS = "mesh_inputs"
    private const val RESOURCE = "source_meshes"

    data class Material(val id: String, val sourceId: String, val raw: ScaffoldMesh.RawMesh, val geometry: ScaffoldMesh.Geometry)

    fun capture(materials: List<Material>): ModifierAlgorithmContext {
        val ids = HashSet<String>()
        val meshes = materials.map { material ->
            require(material.id.isNotBlank() && material.sourceId.isNotBlank() && ids.add(material.id)) { "Invalid scaffold mesh identity" }
            validateRaw(material.raw)
            val raw = material.raw
            mesh(material.id, MeshGeometry(Floats.wrap(raw.rigPositions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)))
        }
        val inputs = buildJsonObject {
            put("schema", SCHEMA)
            put("materials", buildJsonArray {
                for (material in materials) add(buildJsonObject {
                    put("id", material.id)
                    put("source_id", material.sourceId)
                    put("geometry", encode(material.geometry))
                })
            })
        }
        return ModifierAlgorithmContext(
            buildJsonObject { put(INPUTS, inputs) },
            mapOf(RESOURCE to ModifierAlgorithmResource(RigIrBinary.encode(model(meshes)))),
        )
    }

    fun build(context: ModifierAlgorithmContext, parents: Map<String, ScaffoldArms.Parent>, checkpoint: () -> Unit = {}): List<Drawable> {
        checkpoint()
        val inputs = fields(requireNotNull(context.values[INPUTS]) { "Missing scaffold mesh measurements" }, "schema", "materials")
        require(string(inputs.getValue("schema")) == SCHEMA) { "Unknown scaffold mesh schema" }
        val resource = requireNotNull(context.resources[RESOURCE]) { "Missing scaffold source mesh resource" }
        val byId = decodeRawMeshes(resource, checkpoint).toMutableMap()
        val records = array(inputs.getValue("materials"))
        require(records.size == byId.size) { "Scaffold mesh measurements do not match source geometry" }
        val result = records.map { value ->
            checkpoint()
            val record = fields(value, "id", "source_id", "geometry")
            val id = string(record.getValue("id"))
            val sourceId = string(record.getValue("source_id"))
            val raw = requireNotNull(byId.remove(id)) { "Missing or duplicate source mesh: $id" }
            require(sourceId.isNotBlank()) { "Invalid scaffold source layer ID" }
            val parent = requireNotNull(parents[sourceId]) { "Missing scaffold parent for $sourceId" }
            validateBounds(parent.frame)
            val (geometry, grid) = ScaffoldMesh.build(raw, parent, decode(record.getValue("geometry")), checkpoint)
            Drawable(DrawableId(id), id, parent.id, BlendMode.Normal, emptyList(), geometry, grid)
        }
        checkpoint()
        return result
    }

    /** Reads the same original rig coordinates and topology used by the scaffold. */
    fun decodeRawMeshes(resource: ModifierAlgorithmResource, checkpoint: () -> Unit = {}): Map<String, ScaffoldMesh.RawMesh> {
        checkpoint()
        val captured = RigIrBinary.decode(resource.read())
        checkpoint()
        val byId = LinkedHashMap<String, ScaffoldMesh.RawMesh>()
        val cleanMeshes = captured.meshes.map { source ->
            checkpoint()
            val geometry = requireNotNull(source.geometry) { "Missing source mesh geometry" }
            val raw = ScaffoldMesh.RawMesh(geometry.positions.shared(), geometry.uvs.shared(), geometry.indices.shared())
            require(source.id.isNotBlank() && byId.put(source.id, raw) == null) { "Invalid scaffold mesh identity" }
            validateRaw(raw, checkpoint)
            mesh(source.id, geometry)
        }
        require(captured == model(cleanMeshes)) { "Scaffold mesh resource contains bound model data" }
        return byId
    }

    private fun model(meshes: List<Mesh>) = RigIR(Canvas(0f, 0f), emptyList(), meshes = meshes)
    private fun mesh(id: String, raw: MeshGeometry) = Mesh(id, id, null, geometry = raw, offsets = null)

    private fun validateRaw(raw: ScaffoldMesh.RawMesh, checkpoint: () -> Unit = {}) {
        val size = raw.rigPositions.size
        require(size > 0 && size % 2 == 0 && raw.atlasUvs.size == size && raw.triangles.size % 3 == 0) { "Invalid scaffold mesh topology" }
        for (index in 0 until size) {
            if (index % 1024 == 0) checkpoint()
            require(raw.rigPositions[index].isFinite() && raw.atlasUvs[index].isFinite()) { "Non-finite scaffold mesh coordinate" }
        }
        for (index in raw.triangles.indices) {
            if (index % 1024 == 0) checkpoint()
            require(raw.triangles[index] in 0 until size / 2) { "Scaffold triangle references an unknown vertex" }
        }
    }

    private fun encode(geometry: ScaffoldMesh.Geometry): JsonObject = buildJsonObject {
        when (geometry) {
            ScaffoldMesh.Geometry.Zero -> put("kind", "zero")
            is ScaffoldMesh.Geometry.Eye -> {
                put("kind", "eye")
                put("lash", geometry.lash)
                put("bounds", bounds(geometry.bounds))
                put("white_bounds", JsonArray(geometry.whiteBounds.map(::bounds)))
                put("centroid_y", number(geometry.centroidY))
                put("source_left", geometry.sourceLeft)
                val centerline = geometry.centerline?.also { require(it.isNotEmpty()) { "Empty scaffold eye centerline" } }
                put("centerline", centerline?.let { JsonArray(it.map(::number)) } ?: JsonNull)
                put("blink_corner", number(geometry.blinkCorner))
                put("blink_depth", number(geometry.blinkDepth))
                put("eyelash_squash", number(geometry.eyelashSquash))
            }
            is ScaffoldMesh.Geometry.Iris -> {
                put("kind", "iris")
                put("centroid_x", number(geometry.centroidX))
                put("centroid_y", number(geometry.centroidY))
                put("jelly_squash", number(geometry.jellySquash))
                put("jelly_stretch", number(geometry.jellyStretch))
            }
            is ScaffoldMesh.Geometry.Brow -> {
                put("kind", "brow")
                put("height", number(geometry.height))
                put("lift", number(geometry.lift))
            }
            is ScaffoldMesh.Geometry.Mouth -> {
                put("kind", "mouth")
                put("aperture", bounds(geometry.aperture))
                put("curve", JsonArray(geometry.curve.map { JsonArray(listOf(number(it.x), number(it.y))) }))
                put("narrow", number(geometry.narrow))
                put("seam", number(geometry.seam))
                put("smile_base", number(geometry.smileBase))
                put("smile", number(geometry.smile))
                put("widen", number(geometry.widen))
            }
        }
    }

    private fun decode(value: JsonElement): ScaffoldMesh.Geometry {
        val geometry = requireNotNull(value as? JsonObject) { "Invalid scaffold mesh measurements" }
        fun check(vararg keys: String) = fields(geometry, "kind", *keys)
        fun field(name: String) = geometry.getValue(name)
        fun float(name: String) = readFloat(field(name))
        return when (string(requireNotNull(geometry["kind"]) { "Missing scaffold mesh geometry kind" })) {
            "zero" -> {
                check()
                ScaffoldMesh.Geometry.Zero
            }
            "eye" -> {
                check("lash", "bounds", "white_bounds", "centroid_y", "source_left", "centerline",
                    "blink_corner", "blink_depth", "eyelash_squash")
                val centerline = field("centerline").takeUnless { it == JsonNull }?.let {
                    array(it).map(::readFloat).toFloatArray().also { points ->
                        require(points.isNotEmpty()) { "Empty scaffold eye centerline" }
                    }
                }
                ScaffoldMesh.Geometry.Eye(
                    requireNotNull(literal(field("lash")).booleanOrNull) { "Invalid scaffold eye type" },
                    readBounds(field("bounds")), array(field("white_bounds")).map(::readBounds), float("centroid_y"),
                    requireNotNull(literal(field("source_left")).intOrNull) { "Invalid scaffold eye origin" },
                    centerline, float("blink_corner"), float("blink_depth"), float("eyelash_squash"),
                )
            }
            "iris" -> {
                check("centroid_x", "centroid_y", "jelly_squash", "jelly_stretch")
                ScaffoldMesh.Geometry.Iris(float("centroid_x"), float("centroid_y"), float("jelly_squash"), float("jelly_stretch"))
            }
            "brow" -> {
                check("height", "lift")
                ScaffoldMesh.Geometry.Brow(float("height"), float("lift"))
            }
            "mouth" -> {
                check("aperture", "curve", "narrow", "seam", "smile_base", "smile", "widen")
                val curve = array(field("curve")).map {
                    val (x, y) = tuple(it, 2)
                    ScaffoldMesh.CurvePoint(readFloat(x), readFloat(y))
                }
                ScaffoldMesh.Geometry.Mouth(readBounds(field("aperture")), curve,
                    float("narrow"), float("seam"), float("smile_base"), float("smile"), float("widen"))
            }
            else -> throw IllegalArgumentException("Unknown scaffold mesh geometry kind")
        }
    }

    private fun number(value: Float): JsonPrimitive {
        require(value.isFinite()) { "Non-finite scaffold mesh measurement" }
        return JsonPrimitive(value)
    }

    private fun bounds(value: Bounds): JsonArray = validateBounds(value).let {
        JsonArray(listOf(number(it.left), number(it.top), number(it.right), number(it.bottom)))
    }

    private fun readBounds(value: JsonElement): Bounds = tuple(value, 4).let {
        validateBounds(Bounds(readFloat(it[0]), readFloat(it[1]), readFloat(it[2]), readFloat(it[3])))
    }

    private fun validateBounds(value: Bounds): Bounds = value.also {
        require(it.width.isFinite() && it.height.isFinite() && it.width >= 0f && it.height >= 0f) {
            "Invalid scaffold mesh measurement bounds"
        }
    }

    private fun fields(value: JsonElement, vararg keys: String): JsonObject {
        require(value is JsonObject && value.keys == keys.toSet()) { "Invalid scaffold mesh fields" }
        return value
    }

    private fun array(value: JsonElement): JsonArray =
        requireNotNull(value as? JsonArray) { "Expected a scaffold mesh array" }

    private fun tuple(value: JsonElement, size: Int): JsonArray = array(value).also {
        require(it.size == size) { "Invalid scaffold mesh coordinate count" }
    }

    private fun string(value: JsonElement): String =
        requireNotNull((value as? JsonPrimitive)?.takeIf { it.isString }) { "Expected scaffold mesh text" }.content

    private fun literal(value: JsonElement): JsonPrimitive =
        requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }) { "Expected scaffold mesh literal" }

    private fun readFloat(value: JsonElement): Float =
        requireNotNull(literal(value).floatOrNull?.takeIf(Float::isFinite)) { "Expected finite scaffold mesh measurement" }
}
