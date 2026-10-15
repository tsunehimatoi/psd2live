package io.github.psd2live.application

import io.github.psd2live.project.MaterialKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layout operations: semantic tags, variants, generation, the material library and placement. Each document
 * operation runs as a one-member document batch ([startDocumentBatch]), so the GUI and MCP commit it exactly as
 * `workspace_apply_edits` does, and it is a batch member itself.
 */
internal object WorkspaceLayoutOperations {
    /** Operations whose job result is the batch result. */
    val batches: Set<String> get() = WorkspaceLayoutEdits.supported + WorkspaceMaterialEdits.supported + IMPORT
    const val IMPORT = "material_import"
    const val LIST = "material_list"
    const val GET = "material_get"
    const val PREVIEW = "material_preview"
    const val EXPORT_PSD = "material_export_psd"
    const val EXPORT_PNG = "material_export_png"
    val queries = setOf(LIST, GET, PREVIEW)

    private fun string() = buildJsonObject { put("type", "string"); put("minLength", 1) }
    private fun nullableString() = buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }
    private fun integer(min: Int, max: Int) = buildJsonObject { put("type", "integer"); put("minimum", min); put("maximum", max) }
    private fun number() = buildJsonObject { put("type", "number") }
    private fun boolean() = buildJsonObject { put("type", "boolean") }
    private fun strings(min: Int, max: Int) = buildJsonObject { put("type", "array"); put("items", string()); put("minItems", min); put("maxItems", max) }
    fun schema(fields: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object"); put("additionalProperties", false); put("properties", JsonObject(fields))
        put("required", JsonArray(required.map(::JsonPrimitive)))
    }
    private val rect = schema(listOf("left", "top", "width", "height").associateWith { number() }, listOf("left", "top", "width", "height"))
    private val state = mapOf("state" to string())
    private fun choices(vararg values: String) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
    private fun range(min: Int, max: Int) = buildJsonObject { put("type", "number"); put("minimum", min); put("maximum", max) }
    private val point = buildJsonObject { put("type", "array"); put("items", number()); put("minItems", 2); put("maxItems", 2) }
    /** The fields of one paint gesture, as `paint` takes them. */
    private val gesture = mapOf("mode" to choices("brush", "pencil", "eraser", "bucket", "shape", "clear"),
        "points" to buildJsonObject { put("type", "array"); put("items", point); put("minItems", 1); put("maxItems", 512) },
        "radius" to range(0, 512), "hardness" to range(0, 1), "point" to point, "tolerance" to integer(0, 255),
        "from" to point, "to" to point, "shape" to choices("line", "rectangle", "ellipse"), "stroke_width" to range(1, 512), "filled" to boolean(),
        "color" to buildJsonObject { put("type", "array"); put("items", integer(0, 255)); put("minItems", 4); put("maxItems", 4) },
        "opacity" to range(0, 1))

    /** Request schemas and descriptions of the material edits. */
    val materialSchemas: Map<String, JsonObject> = mapOf(
        WorkspaceMaterialEdits.ADD to schema(state + mapOf("staging" to string(), "folder_id" to nullableString()), listOf("state", "staging")),
        WorkspaceMaterialEdits.FOLDER to schema(state + mapOf("name" to string(), "parent_id" to nullableString(), "id" to string()), listOf("state", "name")),
        WorkspaceMaterialEdits.MOVE to schema(state + mapOf("ids" to strings(1, 1024), "folder_id" to nullableString()), listOf("state", "ids")),
        WorkspaceMaterialEdits.RENAME to schema(state + mapOf("id" to string(), "name" to string()), listOf("state", "id", "name")),
        WorkspaceMaterialEdits.DELETE to schema(state + mapOf("ids" to strings(1, 1024)), listOf("state", "ids")),
        WorkspaceMaterialEdits.TAG to schema(state + mapOf("ids" to strings(1, 1024), "set" to strings(0, 64), "add" to strings(0, 64),
            "remove" to strings(0, 64)), listOf("state", "ids")),
        WorkspaceMaterialEdits.RESOLUTION to schema(state + mapOf("id" to string(), "width" to integer(1, WorkspaceMaterialFiles.MAX_VECTOR_SIDE),
            "height" to integer(1, WorkspaceMaterialFiles.MAX_VECTOR_SIDE)), listOf("state", "id", "width", "height")),
        WorkspaceMaterialEdits.PLACE to schema(state + mapOf("material_id" to string(), "rect" to rect,
            "at" to buildJsonObject { put("type", "array"); put("items", number()); put("minItems", 2); put("maxItems", 2) },
            "layer_id" to string()), listOf("state", "material_id")),
        WorkspaceMaterialPixels.PAINT to schema(state + mapOf("id" to string()) + gesture + mapOf("gestures" to buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", WorkspaceMaterialPixels.MAX_GESTURES)
            put("items", schema(gesture, listOf("mode")))
        }), listOf("state", "id")),
        WorkspaceMaterialEdits.UNLINK to schema(state + mapOf("layer_ids" to strings(1, 128)), listOf("state", "layer_ids")),
        WorkspaceMaterialEdits.REFERENCE to schema(state + mapOf("material_id" to nullableString(), "rect" to rect,
            "opacity" to range(0, 1), "visible" to boolean()), listOf("state")),
        WorkspaceMaterialEdits.CROP to schema(state + mapOf("id" to string(), "rect" to rect, "canvas_rect" to rect, "name" to string(),
            "new_id" to string(), "folder_id" to nullableString()), listOf("state", "id")),
        WorkspaceMaterialPixels.SET_PIXELS to schema(state + mapOf("id" to string(), "staging" to string()), listOf("state", "id", "staging")),
        WorkspaceDrawingEdits.DRAW to schema(state + mapOf("id" to string(),
            "svg" to buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", WorkspaceVectorDrawing.MAX_LENGTH) },
            "density" to buildJsonObject { put("type", "number"); put("minimum", WorkspaceDrawingEdits.MIN_DENSITY); put("maximum", WorkspaceDrawingEdits.MAX_DENSITY) },
            "name" to string(), "folder_id" to nullableString(),
            "edits" to buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 256)
                put("items", schema(mapOf("id" to string(), "set" to buildJsonObject { put("type", "object"); put("minProperties", 1)
                    put("additionalProperties", buildJsonObject { put("oneOf", buildJsonArray {
                        add(buildJsonObject { put("type", "string") }); add(buildJsonObject { put("type", "null") }) }) }) }), listOf("id", "set"))) }), listOf("state", "id")),
        WorkspaceDrawingEdits.KIT to schema(state + mapOf(
            "colors" to buildJsonObject { put("type", "object")
                put("additionalProperties", buildJsonObject {
                    put("oneOf", buildJsonArray {
                        add(buildJsonObject { put("type", "string"); put("pattern", "^" + io.github.psd2live.project.DrawingKit.COLOR.pattern + "$") })
                        add(buildJsonObject { put("type", "null") })
                    })
                }) },
            "styles" to buildJsonObject { put("type", "string"); put("maxLength", 262144) },
            "defs" to buildJsonObject { put("type", "string"); put("maxLength", WorkspaceVectorDrawing.MAX_LENGTH) }), listOf("state")),
        WorkspaceMaterialPixels.ADJUST to schema(state + mapOf("id" to string(), "flip" to choices(*WorkspaceMaterialPixels.flips.toTypedArray()),
            "hue" to range(-180, 180), "saturation" to range(-100, 100), "lightness" to range(-100, 100), "contrast" to range(-100, 100)),
            listOf("state", "id")),
        WorkspaceMaterialPeel.PEEL to schema(state + mapOf("id" to string(), "revealed" to string(), "region" to string(),
            "threshold" to buildJsonObject { put("type", "array"); put("items", range(0, 255)); put("minItems", 2); put("maxItems", 2) },
            "grow" to integer(0, WorkspaceMaterialPeel.MAX_RADIUS), "core" to integer(0, WorkspaceMaterialPeel.MAX_RADIUS),
            "close" to integer(0, WorkspaceMaterialPeel.MAX_RADIUS), "band" to integer(0, WorkspaceMaterialPeel.MAX_RADIUS), "color_match" to boolean(),
            "keep" to buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", WorkspaceMaterialPeel.MAX_KEEP)
                put("items", buildJsonObject { put("type", "array"); put("items", integer(0, 65535)); put("minItems", 2); put("maxItems", 2) }) },
            "compare_scale" to integer(1, WorkspaceMaterialPeel.MAX_COMPARE_SCALE),
            "name" to string(), "part_id" to string(), "base_id" to string()), listOf("state", "id", "revealed")),
        WorkspaceMaterialPeel.PLACEMENT to schema(state + mapOf("id" to string(),
            "canvas_rect" to buildJsonObject { put("oneOf", buildJsonArray { add(rect); add(buildJsonObject { put("type", "null") }) }) },
            "from" to string()), listOf("state", "id")),
    )

    val materialDescriptions: Map<String, String> = mapOf(
        WorkspaceMaterialEdits.ADD to "Add materials material_import read and staged (staging token) to the library under folder_id; material_import runs it. One history edit.",
        WorkspaceMaterialEdits.FOLDER to "Create a folder in the material library under parent_id (top level when omitted); give id to name it for later batch members. One history edit.",
        WorkspaceMaterialEdits.MOVE to "Move material items and folders into folder_id (null: the top level). A folder cannot move into itself. One history edit.",
        WorkspaceMaterialEdits.RENAME to "Rename a material item or folder. One history edit.",
        WorkspaceMaterialEdits.DELETE to "Delete material items and folders with everything in them. Refused while a layer shows one of the items; the error lists those layers. Undo brings them back. One history edit.",
        WorkspaceMaterialEdits.TAG to "Set, add or remove tags on material items and folders. When a material is placed, its tags and its folders' tags become the layer's tags (see layer_tag). One history edit.",
        WorkspaceMaterialEdits.RESOLUTION to "Render a vector (SVG) material at width x height pixels, its texture resolution. Every layer showing it takes the new pixels over its unchanged canvas rectangle, as layer_replace_image does. One history edit.",
        WorkspaceMaterialEdits.PLACE to "Place a material on the canvas as new source layers: an item as one layer, a folder as one layer per item in it with its subfolders as source groups, items keeping their places relative to each other. " +
            "Default size: the item's own place (a PSD layer's position) with the PSD's canvas centred on the project canvas and shrunk to fit it (unchanged when both are one size), else one material pixel per canvas unit fitted into the canvas. Canvas units are the model's coordinates (exported as the Cubism model canvas, one pixel per unit) and set its size, not its sharpness, which comes from the material's own pixels and the atlas budget; rect sets an item's canvas rectangle (the pixels are not resampled, so a smaller rectangle is a denser texture); at [x, y] centres the placement there. " +
            "The material's tags and its folders' tags become the layer's tags. Placed layers paint over the others. In a generated project they wait for rig_generate and show as plain meshes until then; " +
            "move them with layer_set_canvas_rect or layer_transform (move, scale, mirror), replace their pixels with layer_replace_image. layer_id names a single item's new layer. The changed list names the new layers (layer:<id>). One history edit.",
        WorkspaceMaterialPixels.PAINT to "Paint on a material item's own pixels, in its pixel coordinates (origin at the top left), with the raster engine the paint operation uses: one gesture given by mode and its fields, or gestures, a list of up to ${WorkspaceMaterialPixels.MAX_GESTURES} applied in order (a painting session's strokes as one edit). " +
            "brush, pencil and eraser take points, radius and hardness; bucket a point and tolerance; shape from, to, shape, stroke_width and filled; clear erases everything. color is RGBA bytes (default opaque black), opacity 0..1. " +
            "Every layer showing the item takes the new pixels over its unchanged canvas rectangle, as layer_replace_image does; a vector item becomes a bitmap of its current raster. One history edit.",
        WorkspaceMaterialEdits.UNLINK to "Unlink layers from the materials they show: their pixels stay as they are, become their own, and painting them no longer paints the material. A layer linked to a material shows its pixels; painting it (Paint mode, source_paint_*, paint_session_begin with its layer_id) paints the material and every layer showing it, and an edit that gives it other pixels unlinks it. One history edit.",
        WorkspaceMaterialEdits.REFERENCE to "Set the reference image: a material item laid over the canvas at opacity (default 0.5) to place layers against and compare the model with. " +
            "It is not a layer: generation, export and the rig never see it. Without rect a new material is fitted into the canvas and centred (scaled up or down, aspect kept); " +
            "omitted fields keep their values; material_id null clears it, visible false hides it. view_render_model draws it with reference {mode: over, under or difference}; " +
            "material_crop takes close-ups from it by canvas rectangle. One history edit.",
        WorkspaceMaterialEdits.CROP to "Crop a material item into a new item, losslessly: rect in the item's own pixels, or canvas_rect in canvas units when the item is the reference image " +
            "(or placed on this canvas by its own placement). The new item remembers where its pixels sit on the canvas, so layer_place without rect lands it on the reference; " +
            "material_export_png writes it to a file, for example to draw a sharper version of the part from it. It keeps the item's tags and folder unless folder_id is given; " +
            "new_id names it for later batch members. One history edit; the changed list names the new item (material:<id>).",
        WorkspaceDrawingEdits.DRAW to "Draw a part as a vector material, or draw it again: id names the item (a new one when free). svg is the drawing; its viewBox is the part's place on the canvas in canvas units, " +
            "so paths are written in canvas coordinates, read off the reference image (view_render_model with grid, reference_sample, reference_trace). It renders at density texture pixels per canvas unit " +
            "(default: the item's current density, else 1; up to ${WorkspaceDrawingEdits.MAX_DENSITY.toInt()}, at most ${WorkspaceMaterialFiles.MAX_VECTOR_SIDE} pixels a side), so small parts get as many pixels as they need. " +
            "Besides what SVG renders (paths, gradients, clip paths, masks, feGaussianBlur and other filters, opacity), drawings take <style> rules with element, #id and .class selectors, " +
            "var(--name) for a colour of the drawing kit (layout_set_drawing_kit) and data-taper on a path: width factors spread from start to end (\"0.1 1 0.2\"), which turn its stroke into a filled line thinning in and out. " +
            "Blend modes and CSS custom properties of your own do not render. Layers showing the item take the new pixels, and those over its old place move to the new viewBox. " +
            "edits changes the current drawing (or the given svg) in place: each names an element by id and sets attributes, null removing one, text replacing a <style>'s text - a path's d, a colour. " +
            "material_get with include_svg reads the drawing back. Without svg or edits the item renders again at density. name and folder_id name and file it. One history edit; the changed list names the item (material:<id>).",
        WorkspaceDrawingEdits.KIT to "Set what every drawing shares (material_draw): colors names #rgb or #rrggbb colours for var(--name) and merges into the kit (null removes one), styles is a style sheet of class rules " +
            "(.line { stroke: var(--line) }) and defs SVG definitions (gradients, filters with ids) every drawing can use; both replace the kit's. A drawing's own rules and ids win. " +
            "Every vector material renders again with the new kit at its resolution and layers showing one that changed take its pixels; refused when a drawing would use a colour the kit no longer has. One history edit.",
        WorkspaceMaterialPixels.SET_PIXELS to "Write the pixels a material paint session committed (staging names them; paint_session_commit stages and runs this). The item keeps its size; every layer showing it takes the new pixels over its unchanged canvas rectangle; a vector item becomes a bitmap. One history edit.",
        WorkspaceMaterialPixels.ADJUST to "Adjust a material item's pixels: flip (horizontal or vertical), then hue (-180..180 degrees), saturation, lightness and contrast (-100..100 each, 0 unchanged). " +
            "The item keeps its size; every layer showing it takes the new pixels over its unchanged canvas rectangle; a vector item becomes a bitmap of its current raster. One history edit.",
        WorkspaceMaterialPeel.PEEL to "Peel a part off an image: id is the image (a close-up, or the image a previous peel left), revealed the same image generated elsewhere with the part removed and what was under it drawn in, " +
            "the same size in pixels (a generator's output at the input's size). Makes two new items placed where id sits: the part, id's own pixels with alpha from where the two differ " +
            "(threshold [low, high] colour difference 0..255, default [8, 30], soft between), and the image under it, id with only the part's area taken from revealed. " +
            "Generators redraw a whole image slightly, so everything outside the part keeps id's pixels and the part over the image under it gives id again; a tone shift over the whole image (each channel's median offset, so the part should cover less than half of it) is not counted as a difference. " +
            "region (an item of the same size, white or opaque where the part may be, such as a segmentation mask) keeps the part inside it, grown by grow pixels; core forces the region shrunk by core pixels fully into the part; " +
            "close fills gaps up to that radius (default 2). keep ([x, y] points in id's pixels, on or within ${WorkspaceMaterialPeel.KEEP_REACH} pixels of the part) keeps only the connected pieces of the part those points touch, dropping specks the generator changed elsewhere. " +
            "compare_scale (1..${WorkspaceMaterialPeel.MAX_COMPARE_SCALE}, default 1) measures the difference with both images shrunk by that factor: give the factor revealed was enlarged by when the generator ran smaller than id, so the enlargement's softer lines do not read as the part. color_match (default true) moves revealed's colours to id's over a band pixels wide (default 24) of untouched pixels around the part. " +
            "Peel the front part first, then peel the next part off the image under it. name names the part; part_id and base_id name the new items for later batch members. One history edit; the changed list names them (material:<id>).",
        WorkspaceMaterialPeel.PLACEMENT to "Set where a material item sits on the canvas, its default place for layer_place: canvas_rect in canvas units, from another item's place (a sharper image generated from a close-up takes the close-up's place, " +
            "its extra pixels becoming a denser texture), or canvas_rect null to clear it. The pixels are unchanged. One history edit.",
    )

    fun summary(id: String, request: JsonObject): String = when (id) {
        WorkspaceLayoutEdits.TAG -> "Tagged ${request.getValue("layer_ids").jsonArray.size} layer(s)"
        WorkspaceLayoutEdits.VARIANT -> "Set variant of ${request.getValue("layer_id").jsonPrimitive.content}"
        WorkspaceGenerationEdits.GENERATE -> "Generated the rig"
        WorkspaceMaterialEdits.ADD -> "Imported materials"
        WorkspaceMaterialEdits.FOLDER -> "Created material folder ${request.getValue("name").jsonPrimitive.content}"
        WorkspaceMaterialEdits.MOVE -> "Moved materials"
        WorkspaceMaterialEdits.RENAME -> "Renamed material"
        WorkspaceMaterialEdits.DELETE -> "Deleted materials"
        WorkspaceMaterialEdits.TAG -> "Tagged materials"
        WorkspaceMaterialEdits.RESOLUTION -> "Set vector resolution"
        WorkspaceMaterialEdits.PLACE -> "Placed material ${request.getValue("material_id").jsonPrimitive.content}"
        WorkspaceMaterialPixels.PAINT -> "Painted material ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceMaterialEdits.REFERENCE -> "Set the reference image"
        WorkspaceMaterialEdits.CROP -> "Cropped material ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceMaterialEdits.UNLINK -> "Unlinked ${request.getValue("layer_ids").jsonArray.size} layer(s) from their materials"
        WorkspaceMaterialPixels.SET_PIXELS -> "Painted material ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceDrawingEdits.DRAW -> "Drew ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceDrawingEdits.KIT -> "Set the drawing kit"
        WorkspaceMaterialPixels.ADJUST -> "Adjusted material ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceMaterialPeel.PEEL -> "Peeled ${request["name"]?.jsonPrimitive?.content ?: "a part"} off ${request.getValue("id").jsonPrimitive.content}"
        WorkspaceMaterialPeel.PLACEMENT -> "Placed material ${request.getValue("id").jsonPrimitive.content} on the canvas"
        else -> id
    }

    /** The result of a material query or preview. */
    val listSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        val tags = s.array(s.string())
        val folder = s.obj(mapOf("id" to s.handle(), "name" to s.string(), "parent_id" to s.handle(), "tags" to tags, "origin" to s.string(),
            "folders" to s.integer(0), "items" to s.integer(0)), setOf("id", "name", "tags", "folders", "items"))
        val item = s.obj(mapOf("id" to s.handle(), "name" to s.string(), "folder_id" to s.handle(), "kind" to s.choices("bitmap", "vector"),
            "width" to s.integer(1), "height" to s.integer(1), "tags" to tags, "origin" to s.string(), "placement" to s.vector(4),
            "references" to s.integer(0)), setOf("id", "name", "kind", "width", "height", "tags", "references"))
        s.obj(mapOf("folder_id" to s.handle(), "path" to s.array(s.obj(mapOf("id" to s.handle(), "name" to s.string()))),
            "total" to s.integer(0), "folders" to s.array(folder), "items" to s.array(item), "next" to s.integer(1),
            "reference" to s.obj(mapOf("material_id" to s.handle(), "rect" to s.vector(4), "opacity" to s.number(), "visible" to s.boolean()))),
            setOf("path", "total", "folders", "items"))
    }
    val getSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        s.obj(mapOf("type" to s.choices("item", "folder"), "id" to s.handle(), "name" to s.string(), "folder_id" to s.handle(),
            "parent_id" to s.handle(), "kind" to s.choices("bitmap", "vector"), "width" to s.integer(1), "height" to s.integer(1),
            "tags" to s.array(s.string()), "origin" to s.string(), "placement" to s.vector(4), "references" to s.integer(0),
            "layers" to s.array(s.handle()), "folder_path" to s.array(s.string()), "vector_size" to s.array(s.integer(1), 2, 2), "svg" to s.string(),
            "folders" to s.integer(0), "items" to s.integer(0)), setOf("type", "id", "name", "tags", "folder_path"))
    }
    val previewSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        s.obj(mapOf("id" to s.handle(), "width" to s.integer(1), "height" to s.integer(1)))
    }
    val pngSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        s.obj(mapOf("state" to s.handle(), "path" to s.handle(), "bytes" to s.integer(0), "width" to s.integer(1), "height" to s.integer(1)))
    }
    val exportSchema: JsonObject by lazy {
        val s = WorkspaceResultSchema
        s.obj(mapOf("state" to s.handle(), "path" to s.handle(), "bytes" to s.integer(0), "layers" to s.integer(0)))
    }
}

internal fun registerLayoutOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort,
                                      reads: WorkspaceReadPort, jobs: WorkspaceJobs) {
    val schemas = WorkspaceLayoutEdits.schemas + WorkspaceLayoutOperations.materialSchemas
    val descriptions = WorkspaceLayoutEdits.descriptions + WorkspaceLayoutOperations.materialDescriptions
    for (id in WorkspaceLayoutEdits.supported + WorkspaceMaterialEdits.supported) {
        registry.register(WorkspaceOperationDefinition(id,
            descriptions.getValue(id) + " Returns a process-owned job handle; use job_wait/job_get for the result.",
            schemas.getValue(id), WorkspaceOperationKind.DOCUMENT, jobBacked = true,
            resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)),
            jobResultSchema = WorkspaceJobResultSchemas.result(id))) { request, context ->
            startDocumentBatch(port, reads, jobs, id, request.getValue("state").jsonPrimitive.content, WorkspaceLayoutOperations.summary(id, request),
                listOf(WorkspaceDocumentOperation(id, JsonObject(request - setOf("state", "request_id", "project_id")))), context.author)
        }
    }

    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.IMPORT,
        "Import 1..${WorkspaceMaterialFiles.MAX_PATHS} absolute paths into the material library under folder_id: PNG, BMP, TIFF, WebP and JPEG images as items; " +
            "a PSD as a folder of its layers (its groups as subfolders, each layer keeping its place, opacity and visibility); a CMO3's layered images the same way " +
            "(its texture pages when it has none - the whole model imports with project_import_cmo3); an SVG as a vector item rendered at its own size " +
            "(material_set_resolution changes it); a folder as a folder of the files it holds, its subfolders nested as they are (hidden ones left out, names in number order). Each image is at most 16 megapixels. The library is part of the " +
            "document: one history edit, undoable. Returns a process-owned job handle; the result's changed list names the new materials (material:<id>).",
        WorkspaceLayoutOperations.schema(mapOf("state" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "paths" to buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", WorkspaceMaterialFiles.MAX_PATHS)
                put("items", buildJsonObject { put("type", "string"); put("minLength", 1) }) },
            "folder_id" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }), listOf("state", "paths")),
        WorkspaceOperationKind.DOCUMENT, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(WorkspaceLayoutOperations.IMPORT)),
        jobResultSchema = WorkspaceJobResultSchemas.result(WorkspaceLayoutOperations.IMPORT))) { request, context ->
        val paths = request.getValue("paths").jsonArray.map { Path.of(it.jsonPrimitive.content) }
        val state = request.getValue("state").jsonPrimitive.content
        val folder = request["folder_id"]?.takeUnless { it is JsonNull }
        startWorkspaceOperationJob(reads, jobs, WorkspaceLayoutOperations.IMPORT) {
            val coroutine = currentCoroutineContext()
            coroutine[WorkspaceJobContext]?.progress(0.05f, "Reading files")
            val entries = runInterruptible(Dispatchers.IO) { WorkspaceMaterialFiles.read(paths) { coroutine.ensureActive() } }
            val edits = listOf(WorkspaceDocumentOperation(WorkspaceMaterialEdits.ADD, buildJsonObject {
                put("staging", WorkspaceMaterialStaging.stage(entries)); folder?.let { put("folder_id", it) }
            }))
            val execution = WorkspaceBatchJobExecution(edits, requireNotNull(coroutine[WorkspaceJobCompletion]), coroutine[WorkspaceJobContext])
            withContext(execution) {
                WorkspaceOperationOutput(port.applyDocumentEdits(state, "Imported ${entries.size} material(s)", edits, context.author).batchResult(1))
            }
        }
    }

    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.LIST,
        "List the material library: the folders and items in folder_id (the top level when omitted), or everywhere below it with recursive, and the reference image when one is set. " +
            "query matches names and tags (an item's folders' tags included), kind filters bitmap or vector items, unused keeps items no layer shows. Paged by offset and limit (default 64).",
        WorkspaceLayoutOperations.schema(mapOf("folder_id" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) },
            "recursive" to buildJsonObject { put("type", "boolean") }, "query" to buildJsonObject { put("type", "string") },
            "kind" to buildJsonObject { put("type", "string"); put("enum", JsonArray(MaterialKind.entries.map { JsonPrimitive(it.name.lowercase()) })) },
            "unused" to buildJsonObject { put("type", "boolean") },
            "offset" to buildJsonObject { put("type", "integer"); put("minimum", 0) },
            "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 256) }), emptyList()),
        WorkspaceOperationKind.QUERY, resultSchema = WorkspaceLayoutOperations.listSchema)) { request, _ ->
        val state = reads.captureQueries().layoutState() ?: throw IllegalStateException("No workspace is loaded")
        WorkspaceOperationOutput(WorkspaceMaterialQueries.list(state, request))
    }
    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.GET,
        "One material item or folder: its size, tags, origin, default placement, and the layers that show it; include_svg returns a vector item's drawing as stored.",
        WorkspaceLayoutOperations.schema(mapOf("id" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "include_svg" to buildJsonObject { put("type", "boolean") }), listOf("id")),
        WorkspaceOperationKind.QUERY, resultSchema = WorkspaceLayoutOperations.getSchema)) { request, _ ->
        val state = reads.captureQueries().layoutState() ?: throw IllegalStateException("No workspace is loaded")
        WorkspaceOperationOutput(WorkspaceMaterialQueries.get(state, request.getValue("id").jsonPrimitive.content, request["include_svg"]?.jsonPrimitive?.boolean ?: false))
    }
    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.PREVIEW,
        "A PNG of a material fitted into size pixels (default 512): an item's pixels, or a folder's items composited where they sit.",
        WorkspaceLayoutOperations.schema(mapOf("id" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "size" to buildJsonObject { put("type", "integer"); put("minimum", 16); put("maximum", 4096) }), listOf("id")),
        WorkspaceOperationKind.QUERY, resultSchema = WorkspaceLayoutOperations.previewSchema)) { request, _ ->
        val state = reads.captureQueries().layoutState() ?: throw IllegalStateException("No workspace is loaded")
        val (json, png) = WorkspaceMaterialQueries.preview(state, request.getValue("id").jsonPrimitive.content, request["size"]?.jsonPrimitive?.int ?: 512)
        WorkspaceOperationOutput(json, listOf(png))
    }
    val r = WorkspaceResultSchema
    val canvasPoint = buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "number") }); put("minItems", 2); put("maxItems", 2) }
    fun points(min: Int) = buildJsonObject { put("type", "array"); put("items", canvasPoint); put("minItems", min); put("maxItems", 256) }
    fun number(min: Number, max: Number) = buildJsonObject { put("type", "number"); put("minimum", min); put("maximum", max) }
    val canvasRect = WorkspaceLayoutOperations.schema(listOf("left", "top", "width", "height").associateWith { buildJsonObject { put("type", "number") } },
        listOf("left", "top", "width", "height"))
    fun referenceQuery(id: String, description: String, request: JsonObject, result: JsonObject, read: (WorkspaceLayoutState, JsonObject) -> JsonObject) =
        registry.register(WorkspaceOperationDefinition(id, description, request, WorkspaceOperationKind.QUERY, resultSchema = result)) { arguments, _ ->
            val state = reads.captureQueries().layoutState() ?: throw IllegalStateException("No workspace is loaded")
            WorkspaceOperationOutput(read(state, arguments))
        }
    referenceQuery(WorkspaceReferenceQueries.SAMPLE,
        "Read colours off the reference image (layout_set_reference), in canvas units: the colour and alpha at each of points (the mean within radius canvas units when given), " +
            "and with rect the palette of its opaque pixels - colors main colours (default 6) with their shares, darkest first - to set drawing kit colours from.",
        WorkspaceLayoutOperations.schema(mapOf("points" to points(0), "radius" to number(0, 64), "rect" to canvasRect,
            "colors" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 16) }), emptyList()),
        r.obj(mapOf("points" to r.array(r.obj(mapOf("point" to r.vector(2), "color" to r.string(), "alpha" to r.number(0, 1), "inside" to r.boolean()), setOf("point"))),
            "palette" to r.array(r.obj(mapOf("color" to r.string(), "share" to r.number(0, 1), "lightness" to r.number()))) ), emptySet()),
        WorkspaceReferenceQueries::sample)
    referenceQuery(WorkspaceReferenceQueries.TRACE,
        "Trace an area of the reference image, like a magic wand: from seed (canvas units) over the opaque pixels within tolerance (Lab distance, default 12) of the seed's colour, " +
            "or with compare neighbor of the neighbouring pixel it grew from, which follows soft shading up to the lines. Returns its outer outline as a polygon simplified to simplify " +
            "canvas units (default 0.6) and path, smooth closed SVG path data through it in canvas coordinates - a start for a drawing's shape - with its area, bounds, mean colour, " +
            "the holes inside it, and touches_edge when it ran to the reference's border (the tolerance leaked). holes returns the outlines of that many of the largest holes " +
            "as well: traced from the background, the largest is the figure's silhouette.",
        WorkspaceLayoutOperations.schema(mapOf("seed" to canvasPoint, "tolerance" to number(0, 100),
            "compare" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("seed"), JsonPrimitive("neighbor")))) },
            "simplify" to number(0, 64), "holes" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 16) }), listOf("seed")),
        r.obj(mapOf("pixels" to r.integer(1), "area" to r.number(), "bounds" to r.vector(4), "mean_color" to r.string(), "holes" to r.integer(0),
            "touches_edge" to r.boolean(), "polygon" to r.array(r.vector(2)), "path" to r.string(),
            "hole_outlines" to r.array(r.obj(mapOf("area" to r.number(), "polygon" to r.array(r.vector(2)), "path" to r.string())))), setOf("pixels", "area", "bounds",
            "mean_color", "holes", "touches_edge", "polygon", "path")),
        WorkspaceReferenceQueries::trace)
    referenceQuery(WorkspaceReferenceQueries.SNAP,
        "Move points onto the drawn lines of the reference image: each point goes to the centre of the nearest line within radius canvas units (default 4) - the nearest pixel clearly darker than its surroundings, then the darkest right around it; " +
            "snapped false keeps a point with no line near. For laying a drawing's line work on the reference's.",
        WorkspaceLayoutOperations.schema(mapOf("points" to points(1), "radius" to number(0, 64)), listOf("points")),
        r.obj(mapOf("points" to r.array(r.obj(mapOf("point" to r.vector(2), "snapped" to r.boolean(), "moved" to r.number(0), "strength" to r.number()))))),
        WorkspaceReferenceQueries::snap)

    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.EXPORT_PNG,
        "Write a material item's own pixels to an absolute PNG path, unscaled - a reference crop to draw a sharper part from, for example. Returns a job handle.",
        WorkspaceLayoutOperations.schema(mapOf("state" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "id" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "path" to buildJsonObject { put("type", "string"); put("minLength", 1) }), listOf("state", "id", "path")),
        WorkspaceOperationKind.OUTPUT, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(WorkspaceLayoutOperations.EXPORT_PNG)),
        jobResultSchema = WorkspaceJobResultSchemas.result(WorkspaceLayoutOperations.EXPORT_PNG))) { request, _ ->
        val path = Path.of(request.getValue("path").jsonPrimitive.content)
        require(path.isAbsolute && path.fileName.toString().endsWith(".png", true)) { "Provide an absolute PNG output path" }
        val queries = reads.captureQueries()
        val captured = queries.snapshot().state
        if (captured != request.getValue("state").jsonPrimitive.content) throw WorkspaceConflict(request.getValue("state").jsonPrimitive.content, captured)
        val state = queries.layoutState() ?: throw IllegalStateException("No workspace is loaded")
        startWorkspaceOperationJob(reads, jobs, WorkspaceLayoutOperations.EXPORT_PNG) {
            val coroutine = currentCoroutineContext()
            val (bytes, width, height) = runInterruptible(Dispatchers.Default) { WorkspaceMaterialQueries.png(state, request.getValue("id").jsonPrimitive.content) }
            coroutine.ensureActive()
            val output = buildJsonObject { put("state", captured); put("path", path.toString()); put("bytes", bytes.size); put("width", width); put("height", height) }
            withContext(NonCancellable + Dispatchers.IO) {
                path.parent?.let { Files.createDirectories(it) }
                val temporary = Files.createTempFile(path.parent, ".material", ".png")
                try { Files.write(temporary, bytes); Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                finally { Files.deleteIfExists(temporary) }
                coroutine[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(output))
            }
            WorkspaceOperationOutput(output)
        }
    }
    registry.register(WorkspaceOperationDefinition(WorkspaceLayoutOperations.EXPORT_PSD,
        "Export a material folder to an absolute PSD path: its items as layers where they sit (in a row from the corner when they have no place), its subfolders as groups, " +
            "on the project canvas grown to hold them; vector items at their texture resolution. Returns a job handle.",
        WorkspaceLayoutOperations.schema(mapOf("state" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "folder_id" to buildJsonObject { put("type", "string"); put("minLength", 1) },
            "path" to buildJsonObject { put("type", "string"); put("minLength", 1) }), listOf("state", "folder_id", "path")),
        WorkspaceOperationKind.OUTPUT, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(WorkspaceLayoutOperations.EXPORT_PSD)),
        jobResultSchema = WorkspaceJobResultSchemas.result(WorkspaceLayoutOperations.EXPORT_PSD))) { request, _ ->
        val path = Path.of(request.getValue("path").jsonPrimitive.content)
        require(path.isAbsolute && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute PSD output path" }
        val queries = reads.captureQueries()
        val captured = queries.snapshot().state
        if (captured != request.getValue("state").jsonPrimitive.content) throw WorkspaceConflict(request.getValue("state").jsonPrimitive.content, captured)
        val state = queries.layoutState() ?: throw IllegalStateException("No workspace is loaded")
        startWorkspaceOperationJob(reads, jobs, WorkspaceLayoutOperations.EXPORT_PSD) {
            val coroutine = currentCoroutineContext()
            val (bytes, layers) = runInterruptible(Dispatchers.Default) { WorkspaceMaterialQueries.psd(state, request.getValue("folder_id").jsonPrimitive.content) }
            coroutine.ensureActive()
            val output = buildJsonObject { put("state", captured); put("path", path.toString()); put("bytes", bytes.size); put("layers", layers) }
            withContext(NonCancellable + Dispatchers.IO) {
                Files.createDirectories(path.parent)
                val temporary = Files.createTempFile(path.parent, ".material", ".psd")
                try { Files.write(temporary, bytes); Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                finally { Files.deleteIfExists(temporary) }
                coroutine[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(output))
            }
            WorkspaceOperationOutput(output)
        }
    }
}
