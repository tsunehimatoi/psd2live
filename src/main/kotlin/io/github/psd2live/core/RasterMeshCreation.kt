package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import org.umamo.format.art.LayerBounds

/** Captures generated meshes once; replay resolves their pixels against the current atlas. */
internal object RasterMeshCreation {
    const val OP = "canvas_mesh_create"

    fun encode(rig: BuiltRig, id: DrawableId): JsonObject {
        val model = rig.puppet
        val drawable = model.drawables.single { it.id == id }
        val mesh = requireNotNull(drawable.mesh)
        RasterMeshJournal.validateMesh(mesh)
        require(drawable.blendShapes.isEmpty()) { "Generated mesh creation expects no authored blend shapes" }
        val texture = RasterMeshJournal.TextureCoordinates(model, drawable)
        val owner = model.parts.singleOrNull { OrgChild.Drawable(id) in it.children }
        val ancestors = mutableListOf<Part>()
        var part = owner
        while (part != null) {
            val currentPart = part
            require(ancestors.none { it.id == currentPart.id }) { "Part hierarchy contains a cycle" }
            ancestors.add(0, currentPart)
            part = model.parts.singleOrNull { OrgChild.Part(currentPart.id) in it.children }
        }
        val axes = drawable.geometryGrid?.axes.orEmpty() + drawable.channelGrids.gridsByChannel.values.flatMap { it.axes }
        return buildJsonObject {
            put("op", OP); put("id", id.raw); put("layer_id", rig.layerIdByDrawableId.getValue(id.raw))
            put("source_id", texture.sourceId.raw); put("source", texture.layer.key)
            put("source_bounds", floats(floatArrayOf(texture.layer.left.toFloat(), texture.layer.top.toFloat(),
                (texture.layer.left + texture.layer.width).toFloat(), (texture.layer.top + texture.layer.height).toFloat())))
            put("parent", drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
            put("part", owner?.id?.raw?.let(::JsonPrimitive) ?: JsonNull)
            put("parts", JsonArray(ancestors.map { buildJsonObject { put("id", it.id.raw); put("name", it.name) } }))
            put("name", drawable.name); put("blend", drawable.blendMode.name); put("opacity", drawable.opacity)
            put("order", drawable.drawOrder); put("visible", drawable.isVisible); put("selectable", drawable.isSelectable)
            put("multiply", color(drawable.multiplyColor)); put("screen", color(drawable.screenColor))
            put("invert_mask", drawable.invertMask); put("alpha_blend", drawable.alphaBlendMode.name); put("culling", drawable.culling)
            put("masks", JsonArray(drawable.maskedBy.map { JsonPrimitive(it.raw) })); put("user_data", drawable.userData)
            put("positions", floats(mesh.positions)); put("triangles", JsonArray(mesh.indices.map(::JsonPrimitive)))
            put("canvas_uvs", floats(texture.toCanvas(mesh.uvs)))
            rig.sourceBoundsByDrawableId[id.raw]?.let { put("neutral_bounds", floats(floatArrayOf(it.left, it.top, it.right, it.bottom))) }
            put("parameters", JsonArray(axes.map { it.parameterId }.distinct().map { axis ->
                val parameter = model.parameters.single { it.id == axis }
                buildJsonObject {
                    put("id", axis.raw); put("name", parameter.name); put("min", parameter.min)
                    put("max", parameter.max); put("default", parameter.default); put("kind", parameter.kind.name); put("repeat", parameter.repeat)
                }
            }))
            put("geometry", drawable.geometryGrid?.let { grid(it) { form -> floats(form.positionDeltas) } } ?: JsonNull)
            put("channels", buildJsonObject { drawable.channelGrids.gridsByChannel.forEach { (channel, track) ->
                put(channel.name, grid(track) { value -> when (value) {
                    is ChannelValue.Scalar -> JsonPrimitive(value.value)
                    is ChannelValue.Color -> color(value.color)
                    is ChannelValue.Flag -> JsonPrimitive(value.flag)
                } })
            } })
            put("paths", JsonArray(model.deformPaths.filter { it.drawableId == id }.map(DeformPathJournal::encode)))
        }
    }

    /**
     * [command] as a replacement: replay swaps the mesh the rig holds under its ID for the recorded one, in its place in
     * the draw list and its part, and creates it when the rig has none. A placement records one for a mesh the authored
     * rig already holds; a regeneration merge before it may have dropped that mesh (one the generators made and the
     * user never changed), or kept it.
     */
    fun replacing(command: JsonObject) = JsonObject(command + ("replace" to JsonPrimitive(true)))

    fun replaces(command: JsonObject) = command["replace"]?.jsonPrimitive?.booleanOrNull == true

    fun replay(input: PuppetModel, command: JsonObject): PuppetModel {
        val id = DrawableId(command.text("id"))
        if (replaces(command)) return replaced(input, id, JsonObject(command - "replace"))
        require(input.drawables.none { it.id == id }) { "Mesh creation ID already exists: ${id.raw}" }
        sourceBounds(command)
        require(command.text("layer_id").isNotBlank()) { "Mesh creation layer is missing" }
        val parent = command["parent"]?.jsonPrimitive?.contentOrNull?.let(::DeformerId)
        require(parent == null || input.deformers.any { it.id == parent }) { "Mesh creation parent is missing" }
        val tile = input.atlas.tiles.singleOrNull { it.source?.let { source ->
            source.sourceId.raw == command.text("source_id") && source.layerKey == command.text("source") } == true }
            ?: throw IllegalArgumentException("Mesh creation artwork is missing: ${command.text("source")}")
        var model = input
        for (element in command.getValue("parameters").jsonArray) {
            val p = element.jsonObject
            if (model.parameters.any { it.id.raw == p.text("id") }) continue
            model = RigStructureEdits.replay(model, listOf(buildJsonObject {
                put("action", "create"); put("kind", "parameter"); put("id", p.getValue("id")); put("name", p.getValue("name"))
                put("min", p.getValue("min")); put("max", p.getValue("max")); put("default", p.getValue("default"))
                put("parameter_kind", p.getValue("kind")); put("repeat", p.getValue("repeat"))
            }))
        }
        val shell = Drawable(id, command.text("name"), parent, BlendMode.valueOf(command.text("blend")),
            command.getValue("masks").jsonArray.map { DrawableId(it.jsonPrimitive.content) }, null, null, atlasTileId = tile.id)
        require(shell.maskedBy.all { mask -> model.drawables.any { it.id == mask } }) { "Mesh creation mask is missing" }
        val texture = RasterMeshJournal.TextureCoordinates(model, shell)
        val positions = command.floats("positions")
        val canvasUvs = command.floats("canvas_uvs")
        require(canvasUvs.size == positions.size && canvasUvs.size % 2 == 0) { "Invalid created mesh texture coordinates" }
        val mesh = DrawableMesh(positions, texture.toUvs(canvasUvs), command.getValue("triangles").jsonArray.map { it.jsonPrimitive.int }.toIntArray())
        RasterMeshJournal.validateMesh(mesh)
        val geometry = command["geometry"]?.takeIf { it != JsonNull }?.jsonObject?.let { data ->
            decodeGrid(data, model) { value -> MeshDeltaForm(value.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
                require(it.size == positions.size && it.all(Float::isFinite)) { "Invalid created mesh keyform" }
            }) }
        }
        val channels = command.getValue("channels").jsonObject.map { (name, data) ->
            val channel = FormChannel.valueOf(name)
            channel to decodeGrid(data.jsonObject, model) { value -> when (channel.valueKind) {
                ChannelValueKind.SCALAR -> ChannelValue.Scalar(value.jsonPrimitive.float.also { require(it.isFinite()) })
                ChannelValueKind.COLOR -> ChannelValue.Color(decodeColor(value))
                ChannelValueKind.FLAG -> ChannelValue.Flag(value.jsonPrimitive.boolean)
            } }
        }.toMap()
        val drawable = shell.copy(mesh = mesh, geometryGrid = geometry, channelGrids = ChannelGrids(channels),
            drawOrder = command.number("order"), opacity = command.number("opacity"),
            multiplyColor = decodeColor(command.getValue("multiply")), screenColor = decodeColor(command.getValue("screen")),
            isVisible = command.getValue("visible").jsonPrimitive.boolean, isSelectable = command.getValue("selectable").jsonPrimitive.boolean,
            invertMask = command.getValue("invert_mask").jsonPrimitive.boolean, alphaBlendMode = AlphaBlendMode.valueOf(command.text("alpha_blend")),
            culling = command.getValue("culling").jsonPrimitive.boolean, texturePage = tile.placement?.pageIndex ?: -1, userData = command.text("user_data"))
        var parts = model.parts
        var roots = model.rootChildren
        var previous: PartId? = null
        val partIds = command.getValue("parts").jsonArray.map { PartId(it.jsonObject.text("id")) }
        require(partIds.distinct().size == partIds.size) { "Duplicate created mesh part" }
        for (element in command.getValue("parts").jsonArray) {
            val p = element.jsonObject; val partId = PartId(p.text("id"))
            if (parts.none { it.id == partId }) {
                parts = parts + Part(partId, p.text("name"), emptyList())
                val child = OrgChild.Part(partId)
                if (previous == null) roots = roots + child
                else parts = parts.map { if (it.id == previous) it.copy(children = it.children + child) else it }
            }
            previous = partId
        }
        val owner = command["part"]?.jsonPrimitive?.contentOrNull?.let(::PartId)
        require(owner == partIds.lastOrNull()) { "Mesh creation part chain is invalid" }
        val child = OrgChild.Drawable(id)
        if (owner == null) roots = roots + child else {
            require(parts.any { it.id == owner }) { "Mesh creation part is missing" }
            parts = parts.map { if (it.id == owner) it.copy(children = it.children + child) else it }
        }
        model = model.copy(drawables = model.drawables + drawable, parts = parts, rootChildren = roots).withDerivedRenderRoot()
        for (path in command.getValue("paths").jsonArray) model = DeformPathJournal.apply(model, path.jsonObject)
        return model
    }

    private fun replaced(input: PuppetModel, id: DrawableId, command: JsonObject): PuppetModel {
        val index = input.drawables.indexOfFirst { it.id == id }
        if (index < 0) return replay(input, command)
        val child = OrgChild.Drawable(id)
        val owner = input.parts.singleOrNull { child in it.children }
        val position = owner?.children?.indexOf(child) ?: input.rootChildren.indexOf(child)
        val removed = input.copy(drawables = input.drawables.filterNot { it.id == id },
            parts = input.parts.map { if (it === owner) it.copy(children = it.children - child) else it },
            rootChildren = input.rootChildren - child, deformPaths = input.deformPaths.filterNot { it.drawableId == id })
        val created = replay(removed, command)
        fun <T> List<T>.movedTo(item: T, at: Int) = (this - item).toMutableList().apply { add(at.coerceIn(0, size), item) }
        val drawable = created.drawables.single { it.id == id }
        val newOwner = created.parts.singleOrNull { child in it.children }
        return created.copy(drawables = created.drawables.movedTo(drawable, index),
            parts = if (newOwner == null || newOwner.id != owner?.id) created.parts
                else created.parts.map { if (it.id == newOwner.id) it.copy(children = it.children.movedTo(child, position)) else it },
            rootChildren = if (newOwner == null && owner == null && position >= 0) created.rootChildren.movedTo(child, position) else created.rootChildren
        ).withDerivedRenderRoot()
    }

    internal fun sourceBounds(command: JsonObject): LayerBounds {
        val edges = command.floats("source_bounds")
        require(edges.size == 4 && edges[2] > edges[0] && edges[3] > edges[1] &&
            edges.all { it.toDouble() >= Int.MIN_VALUE.toDouble() && it.toDouble() <= Int.MAX_VALUE.toDouble() }) {
            "Invalid created mesh artwork bounds"
        }
        val left = kotlin.math.floor(edges[0]).toInt(); val top = kotlin.math.floor(edges[1]).toInt()
        val width = kotlin.math.ceil(edges[2]).toLong() - left; val height = kotlin.math.ceil(edges[3]).toLong() - top
        require(width in 1..16_777_216 && height in 1..16_777_216 && width * height <= 16_777_216) {
            "Created mesh artwork exceeds raster limits"
        }
        return LayerBounds(left, top, width.toInt(), height.toInt())
    }

    private fun floats(values: FloatArray) = JsonArray(values.map(::JsonPrimitive))
    private fun color(value: ColorRgb) = floats(floatArrayOf(value.red, value.green, value.blue))
    private fun decodeColor(value: JsonElement): ColorRgb {
        val numbers = value.jsonArray.map { it.jsonPrimitive.float }
        require(numbers.size == 3 && numbers.all(Float::isFinite)) { "Invalid created mesh color" }
        return ColorRgb(numbers[0], numbers[1], numbers[2])
    }
    internal fun <T> grid(value: KeyformGrid<T>, form: (T) -> JsonElement) = buildJsonObject {
        put("axes", JsonArray(value.axes.map { axis -> buildJsonObject { put("id", axis.parameterId.raw); put("keys", floats(axis.keys)) } }))
        put("cells", JsonArray(value.cells.map { cell -> buildJsonArray { add(JsonArray(cell.coordinate.map(::JsonPrimitive))); add(form(cell.form)) } }))
    }
    internal fun <T> decodeGrid(data: JsonObject, model: PuppetModel, form: (JsonElement) -> T): KeyformGrid<T> {
        val axes = data.getValue("axes").jsonArray.map { element ->
            val axis = element.jsonObject; val id = ParameterId(axis.text("id")); val keys = axis.floats("keys")
            require(model.parameters.any { it.id == id } && keys.isNotEmpty() && keys.all(Float::isFinite) &&
                keys.asList().zipWithNext().all { (a, b) -> a < b }) { "Invalid created mesh keyform axis" }
            KeyformAxis(id, keys)
        }
        require(axes.map { it.parameterId }.distinct().size == axes.size) { "Duplicate created mesh keyform axis" }
        val cells = data.getValue("cells").jsonArray.map { element ->
            val row = element.jsonArray; require(row.size == 2)
            val coordinate = row[0].jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            require(coordinate.size == axes.size && coordinate.indices.all { coordinate[it] in axes[it].keys.indices }) { "Invalid created mesh keyform coordinate" }
            KeyformCell(coordinate, form(row[1]))
        }
        require(cells.map { it.coordinate.toList() }.distinct().size == cells.size) { "Duplicate created mesh keyform coordinate" }
        return KeyformGrid(axes, cells)
    }
    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.float.also { require(it.isFinite()) }
    private fun JsonObject.floats(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also { require(it.all(Float::isFinite)) }
}
