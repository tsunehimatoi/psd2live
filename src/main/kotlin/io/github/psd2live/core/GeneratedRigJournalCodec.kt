package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** The generated scaffold is materialized once, without storing editor state or raster bytes. */
internal object GeneratedRigJournalCodec {
    fun encode(model: PuppetModel) = buildJsonObject {
        put("parameters", parameters(model))
        put("deformers", JsonArray(model.deformers.map(::deformer)))
        put("drawables", JsonArray(model.drawables.map { drawable -> buildJsonObject {
            put("id", drawable.id.raw); put("name", drawable.name); put("parent", drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
            put("positions", drawable.mesh?.let { floats(it.positions) } ?: JsonNull)
            put("uvs", drawable.mesh?.let { floats(it.uvs) } ?: JsonNull)
            put("triangles", drawable.mesh?.let { JsonArray(it.indices.map(::JsonPrimitive)) } ?: JsonNull)
            put("geometry", drawable.geometryGrid?.let { grid -> RasterMeshCreation.grid(grid) { floats(it.positionDeltas) } } ?: JsonNull)
            put("channels", channels(drawable.channelGrids)); put("opacity", drawable.opacity)
            put("multiply", color(drawable.multiplyColor)); put("screen", color(drawable.screenColor))
            put("order", drawable.drawOrder); put("visible", drawable.isVisible)
            put("masks", JsonArray(drawable.maskedBy.map { JsonPrimitive(it.raw) })); put("invert_mask", drawable.invertMask)
            // A rig module's additive shapes (gaze on the lids, an emotion's eye shape); left out when there are none, so
            // scaffolds stored before them keep their text.
            if (drawable.blendShapes.isNotEmpty()) put("blends", JsonArray(drawable.blendShapes.map(::blend)))
        } }))
        put("width", model.canvasWidth); put("height", model.canvasHeight)
    }

    fun decode(value: JsonObject): PuppetModel {
        val parameters = parameters(value.getValue("parameters").jsonArray)
        require(parameters.map { it.id }.distinct().size == parameters.size) { "Duplicate generated parameters" }
        var model = PuppetModel(parameters, emptyList(), emptyList(), emptyList(), emptyList(), null,
            canvasWidth = value.number("width"), canvasHeight = value.number("height"))
        val deformers = value.getValue("deformers").jsonArray.map { deformer(it.jsonObject, model) }
        require(deformers.map { it.id }.distinct().size == deformers.size && deformers.all { it.parent == null || parentExists(it.parent, deformers) }) {
            "Invalid generated parent hierarchy"
        }
        model = model.copy(deformers = deformers)
        deformers.forEach { d ->
            var parent: DeformerId? = d.id; val seen = HashSet<DeformerId>()
            while (parent != null) { require(seen.add(parent)) { "Generated parent hierarchy contains a cycle" }; parent = deformers.single { it.id == parent }.parent }
        }
        val drawables = value.getValue("drawables").jsonArray.map { element ->
            val d = element.jsonObject
            val mesh = d["positions"]?.takeIf { it != JsonNull }?.let {
                DrawableMesh(it.jsonArray.map { v -> v.jsonPrimitive.float }.toFloatArray(),
                    d.getValue("uvs").jsonArray.map { v -> v.jsonPrimitive.float }.toFloatArray(),
                    d.getValue("triangles").jsonArray.map { v -> v.jsonPrimitive.int }.toIntArray()).also(RasterMeshJournal::validateMesh)
            }
            val grid = d["geometry"]?.takeIf { it != JsonNull }?.jsonObject?.let { encoded ->
                RasterMeshCreation.decodeGrid(encoded, model) { form -> MeshDeltaForm(form.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
                    require(mesh != null && it.size == mesh.positions.size && it.all(Float::isFinite)) { "Invalid generated mesh keyform" }
                }) }
            }
            Drawable(DrawableId(d.text("id")), d.text("name"), d.getValue("parent").jsonPrimitive.contentOrNull?.let(::DeformerId),
                BlendMode.Normal, d.getValue("masks").jsonArray.map { DrawableId(it.jsonPrimitive.content) }, mesh, grid,
                channelGrids = channels(d.getValue("channels").jsonObject, model), opacity = d.number("opacity"),
                multiplyColor = color(d.getValue("multiply")), screenColor = color(d.getValue("screen")),
                drawOrder = d.number("order"), isVisible = d.getValue("visible").jsonPrimitive.boolean,
                invertMask = d.getValue("invert_mask").jsonPrimitive.boolean,
                blendShapes = d["blends"]?.jsonArray?.map { blend(it.jsonObject, mesh?.positions?.size ?: 0, model) }.orEmpty())
        }
        require(drawables.map { it.id }.distinct().size == drawables.size) { "Duplicate generated mesh IDs" }
        require(drawables.all { it.parentDeformerId == null || parentExists(it.parentDeformerId, deformers) }) { "Generated mesh parent is missing" }
        return model.copy(drawables = drawables)
    }

    private fun parentExists(parent: DeformerId?, deformers: List<Deformer>) = deformers.any { it.id == parent }

    private fun blend(binding: BlendShapeBinding<MeshForm>) = buildJsonObject {
        put("parameter", binding.parameterId.raw); put("keys", floats(binding.keys)); put("neutral", binding.neutralIndex)
        put("forms", JsonArray(binding.forms.map { form -> form?.let { buildJsonObject {
            put("deltas", floats(it.positionDeltas)); put("order", it.drawOrder); put("opacity", it.opacity)
            put("multiply", color(it.multiplyColor)); put("screen", color(it.screenColor))
        } } ?: JsonNull }))
        if (binding.limits.isNotEmpty()) put("limits", JsonArray(binding.limits.map { limit -> buildJsonObject {
            put("parameter", limit.parameterId.raw)
            put("points", JsonArray(limit.points.map { floats(floatArrayOf(it.value, it.weight)) }))
        } }))
    }

    private fun blend(value: JsonObject, size: Int, model: PuppetModel): BlendShapeBinding<MeshForm> {
        val parameter = ParameterId(value.text("parameter"))
        require(model.parameters.any { it.id == parameter }) { "Generated blend shape names an unknown parameter" }
        val keys = value.getValue("keys").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        val forms = value.getValue("forms").jsonArray.map { element ->
            if (element == JsonNull) null else element.jsonObject.let { f ->
                MeshForm(f.getValue("deltas").jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
                    require(it.size == size && it.all(Float::isFinite)) { "Invalid generated blend shape" }
                }, f.number("order"), f.number("opacity"), color(f.getValue("multiply")), color(f.getValue("screen")))
            }
        }
        val neutral = value.getValue("neutral").jsonPrimitive.int
        require(keys.size == forms.size && neutral in keys.indices) { "Invalid generated blend shape keys" }
        val limits = value["limits"]?.jsonArray?.map { element -> element.jsonObject.let { l ->
            BlendWeightLimit(ParameterId(l.text("parameter")), l.getValue("points").jsonArray.map { point ->
                point.jsonArray.map { it.jsonPrimitive.float }.let { BlendWeightLimitPoint(it[0], it[1]) }
            })
        } }.orEmpty()
        return BlendShapeBinding(parameter, keys, neutral, forms, limits)
    }
    fun parameters(model: PuppetModel) = JsonArray(model.parameters.map { parameter -> buildJsonObject {
        put("id", parameter.id.raw); put("name", parameter.name); put("min", parameter.min); put("max", parameter.max)
        put("default", parameter.default); put("kind", parameter.kind.name); put("repeat", parameter.repeat)
        put("keys", parameter.keys?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
    } })

    fun parameters(value: JsonArray): List<Parameter> = value.map { element ->
        val p = element.jsonObject
        Parameter(ParameterId(p.text("id")), p.text("name"), p.number("min"), p.number("max"), p.number("default"),
            ParameterKind.valueOf(p.text("kind")), p.getValue("repeat").jsonPrimitive.boolean,
            p.getValue("keys").takeIf { it != JsonNull }?.jsonArray?.map { it.jsonPrimitive.float }).also {
            require(it.min < it.max && it.default in it.min..it.max) { "Invalid generated parameter" }
        }
    }

    fun channels(value: ChannelGrids) = buildJsonObject { value.gridsByChannel.forEach { (channel, grid) ->
        put(channel.name, RasterMeshCreation.grid(grid) { form -> when (form) {
            is ChannelValue.Scalar -> JsonPrimitive(form.value)
            is ChannelValue.Color -> color(form.color)
            is ChannelValue.Flag -> JsonPrimitive(form.flag)
        } })
    } }

    fun channels(value: JsonObject, model: PuppetModel) = ChannelGrids(value.map { (name, encoded) ->
        val channel = FormChannel.valueOf(name)
        channel to RasterMeshCreation.decodeGrid(encoded.jsonObject, model) { form -> when (channel.valueKind) {
            ChannelValueKind.SCALAR -> ChannelValue.Scalar(form.jsonPrimitive.float.also { require(it.isFinite()) })
            ChannelValueKind.COLOR -> ChannelValue.Color(color(form))
            ChannelValueKind.FLAG -> ChannelValue.Flag(form.jsonPrimitive.boolean)
        } }
    }.toMap())

    fun deformer(value: Deformer) = buildJsonObject {
        put("id", value.id.raw); put("name", value.name); put("parent", value.parent?.raw?.let(::JsonPrimitive) ?: JsonNull)
        put("part", value.partId?.raw?.let(::JsonPrimitive) ?: JsonNull)
        put("channels", channels(value.channelGrids)); put("selectable", value.isSelectable); put("visible", value.isVisible); put("enabled", value.isEnabled)
        when (value) {
            is Deformer.Warp -> {
                require(value.blendShapes.isEmpty()) { "Generated scaffold must not carry author blend shapes" }
                put("kind", "warp"); put("rows", value.rows); put("columns", value.columns); put("quad", value.isQuadTransform)
                put("opacity", value.opacity); put("multiply", color(value.multiplyColor)); put("screen", color(value.screenColor))
                put("geometry", value.geometryGrid?.let { grid -> RasterMeshCreation.grid(grid) { floats(it.controlPoints) } } ?: JsonNull)
            }
            is Deformer.Rotation -> {
                require(value.blendShapes.isEmpty()) { "Generated scaffold must not carry author blend shapes" }
                put("kind", "rotation"); put("base_angle", value.baseAngle)
                put("flip_x", value.flipX); put("flip_y", value.flipY)
                put("handle_length", value.handleLength?.let(::JsonPrimitive) ?: JsonNull)
                put("opacity", value.opacity); put("multiply", color(value.multiplyColor)); put("screen", color(value.screenColor))
                put("geometry", value.geometryGrid?.let { grid -> RasterMeshCreation.grid(grid) {
                    floats(floatArrayOf(it.originX, it.originY, it.angle, it.scale))
                } } ?: JsonNull)
            }
        }
    }

    fun deformer(value: JsonObject, model: PuppetModel): Deformer {
        val id = DeformerId(value.text("id")); val name = value.text("name")
        val parent = value.getValue("parent").jsonPrimitive.contentOrNull?.let(::DeformerId)
        val part = value.getValue("part").jsonPrimitive.contentOrNull?.let(::PartId)
        val geometry = value["geometry"]?.takeIf { it != JsonNull }?.jsonObject
        return when (value.text("kind")) {
            "warp" -> {
                val rows = value.getValue("rows").jsonPrimitive.int; val columns = value.getValue("columns").jsonPrimitive.int
                require(rows in 1..128 && columns in 1..128) { "Invalid generated warp divisions" }
                Deformer.Warp(id, name, parent, part, rows, columns, value.getValue("quad").jsonPrimitive.boolean,
                    geometry?.let { RasterMeshCreation.decodeGrid(it, model) { form ->
                        WarpLatticeForm(form.jsonArray.map { number -> number.jsonPrimitive.float }.toFloatArray().also { points ->
                            require(points.size == (rows + 1) * (columns + 1) * 2 && points.all(Float::isFinite)) { "Invalid generated warp geometry" }
                        })
                    } }, channels(value.getValue("channels").jsonObject, model), value.number("opacity"),
                    color(value.getValue("multiply")), color(value.getValue("screen")), value.getValue("selectable").jsonPrimitive.boolean,
                    value.getValue("visible").jsonPrimitive.boolean, value.getValue("enabled").jsonPrimitive.boolean)
            }
            "rotation" -> Deformer.Rotation(id, name, parent, part, value.number("base_angle"),
                geometry?.let { RasterMeshCreation.decodeGrid(it, model) { form ->
                    val numbers = form.jsonArray.map { it.jsonPrimitive.float }
                    require(numbers.size == 4 && numbers.all(Float::isFinite)) { "Invalid generated rotation geometry" }
                    RotationPivotForm(numbers[0], numbers[1], numbers[2], numbers[3])
                } }, channels(value.getValue("channels").jsonObject, model), value.number("opacity"),
                color(value.getValue("multiply")), color(value.getValue("screen")),
                flipX = value.getValue("flip_x").jsonPrimitive.boolean, flipY = value.getValue("flip_y").jsonPrimitive.boolean,
                isSelectable = value.getValue("selectable").jsonPrimitive.boolean,
                isVisible = value.getValue("visible").jsonPrimitive.boolean, isEnabled = value.getValue("enabled").jsonPrimitive.boolean,
                handleLength = value.getValue("handle_length").jsonPrimitive.floatOrNull)
            else -> error("Invalid generated deformer kind")
        }
    }

    private fun floats(values: FloatArray) = JsonArray(values.map(::JsonPrimitive))
    private fun color(value: ColorRgb) = floats(floatArrayOf(value.red, value.green, value.blue))
    private fun color(value: JsonElement): ColorRgb {
        val numbers = value.jsonArray.map { it.jsonPrimitive.float }
        require(numbers.size == 3 && numbers.all(Float::isFinite)) { "Invalid generated color" }
        return ColorRgb(numbers[0], numbers[1], numbers[2])
    }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.float.also { require(it.isFinite()) }
}
