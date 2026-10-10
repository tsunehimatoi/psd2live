package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*

/**
 * The `art_primitive` journal record: drawables the document owns outright, with their whole authored state.
 *
 * A split writes one record. Where it replays, the drawables it [supersedes] are removed and its primitives
 * take their place: in the drawable list and the part tree, as masks, and in every Glue that touched them.
 * Each primitive carries its mesh (parent-space positions, triangles and canvas-unit texture coordinates),
 * material and channels, keyforms, blend shapes, paths and vertex groups. Nothing is derived from the
 * superseded drawable, so replay does not depend on regenerating it bit for bit.
 *
 * Texture coordinates are canvas units, the convention of `canvas_mesh_create` and `canvas_mesh_rebuild`:
 * a later paint re-crops the layer, and the same canvas point still names the same pixel. Replay converts them
 * through the current atlas ([RasterMeshJournal.TextureCoordinates]).
 *
 * Record (version 1):
 * - `op`, `v`, `origin` (`split` or `depth`), `texture_source_id`;
 * - `supersedes` (drawable ids) and `supersedes_layers` (their source layers, removed from the source art);
 * - `replace`: superseded drawable id → primitive ids, for its slot in the part tree and, unless `masks` says
 *   otherwise, as a mask of other drawables;
 * - `primitives`: one object per part (see [encodePrimitive]);
 * - `glues`: `replaced` - per Glue touching a superseded drawable, in model order, its replacements;
 *   `appended` - Glues added after all others.
 */
internal object ArtPrimitiveJournal {
	const val OP = "art_primitive"
	const val VERSION = 1

	fun commands(overlay: RigEditOverlay): List<JsonObject> = overlay.authoringJournal.filter { isRecord(it) }

	fun isRecord(command: JsonObject) = command["op"]?.jsonPrimitive?.contentOrNull == OP

	fun primitives(command: JsonObject): List<JsonObject> = command.getValue("primitives").jsonArray.map { it.jsonObject }

	/** Layers whose pixels only document-owned primitives show; the rig generator never builds them. */
	fun ownedLayers(overlay: RigEditOverlay): Set<String> = commands(overlay).flatMapTo(LinkedHashSet()) { command ->
		primitives(command).map { it.getValue("layer_id").jsonPrimitive.content }
	}

	/** Layers a record removed. They exist only before that record's position in the journal. */
	fun supersededLayers(overlay: RigEditOverlay): Set<String> = commands(overlay).flatMapTo(LinkedHashSet()) { command ->
		command.getValue("supersedes_layers").jsonArray.map { it.jsonPrimitive.content }
	}

	/** Superseded layer → the layers that replaced it, following later splits of those layers too. */
	fun replacementLayers(overlay: RigEditOverlay): Map<String, List<String>> {
		val direct = LinkedHashMap<String, List<String>>()
		for (command in commands(overlay)) {
			val layers = primitives(command).map { it.getValue("layer_id").jsonPrimitive.content }.distinct()
			command.getValue("supersedes_layers").jsonArray.forEach { direct[it.jsonPrimitive.content] = layers }
		}
		fun resolve(id: String, seen: Set<String>): List<String> = direct[id]?.flatMap { next ->
			if (next in seen || next !in direct) listOf(next) else resolve(next, seen + next)
		}?.distinct() ?: listOf(id)
		return direct.keys.associateWith { resolve(it, setOf(it)) }
	}

	/** The same for superseded drawable ids. */
	fun replacementDrawables(overlay: RigEditOverlay): Map<String, List<String>> {
		val direct = LinkedHashMap<String, List<String>>()
		for (command in commands(overlay)) command.getValue("replace").jsonObject.forEach { (id, value) ->
			direct[id] = value.jsonArray.map { it.jsonPrimitive.content }
		}
		fun resolve(id: String, seen: Set<String>): List<String> = direct[id]?.flatMap { next ->
			if (next in seen || next !in direct) listOf(next) else resolve(next, seen + next)
		}?.distinct() ?: listOf(id)
		return direct.keys.associateWith { resolve(it, setOf(it)) }
	}

	/** The canvas rectangle each primitive layer's texture must cover, so its texture coordinates stay inside its tile. */
	fun coverage(overlay: RigEditOverlay): Map<String, LayerBounds> {
		val result = LinkedHashMap<String, LayerBounds>()
		for (command in commands(overlay)) for (primitive in primitives(command)) {
			val layer = primitive.getValue("layer_id").jsonPrimitive.content
			val bounds = RasterMeshCreation.sourceBounds(primitive)
			result[layer] = result[layer]?.let { union(it, bounds) } ?: bounds
		}
		return result
	}

	/**
	 * A transparent stand-in for a superseded layer's texture. Journal entries before the record still address
	 * the superseded drawable's tile; the stand-in keeps them replayable without packing the old pixels.
	 */
	fun placeholder(id: String, name: String, bounds: LayerBounds): SourceLayer = WorkspaceSourceLayer(LayerId(id), name, "",
		SourceLayerKind.Raster, true, 0, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(1, 1, ByteArray(4)), null, null, false)

	fun placeholder(layer: SourceLayer, bounds: LayerBounds = layer.bounds): SourceLayer = object : SourceLayer by layer {
		override val bounds = bounds
		override val raster = LayerRaster(1, 1, ByteArray(4))
	}

	/** The generation order a superseded layer keeps: the slot its replacements hold in the current source. */
	fun anchorOrder(overlay: RigEditOverlay, id: String, current: Map<String, SourceLayer>): Int? {
		val replacements = replacementLayers(overlay)[id] ?: return null
		return replacements.mapNotNull { current[it]?.order }.maxOrNull()
	}

	/** The analysis without superseded layers: they keep a transparent tile only while their records replay. */
	fun visibleAnalysis(analysis: PipelineAnalysis, overlay: RigEditOverlay): PipelineAnalysis {
		val superseded = supersededLayers(overlay)
		if (superseded.isEmpty()) return analysis
		val current = analysis.source.layers.mapTo(HashSet()) { it.id.raw }
		// Ribbons generated from a superseded mouth stay: their meshes are not superseded, only a sliced ribbon is.
		return analysis.copy(layers = analysis.layers.filterNot { layer ->
			val id = layer.source.id.raw
			id !in current && (id in superseded || layer.source !is MouthLipLayer && superseded.any { id.startsWith("$it:") })
		})
	}

	/** Drops the atlas tiles and art inventory of superseded layers once no drawable samples them. */
	fun pruneAtlas(model: PuppetModel, overlay: RigEditOverlay): PuppetModel {
		val superseded = supersededLayers(overlay)
		if (superseded.isEmpty()) return model
		val used = model.drawables.mapNotNullTo(HashSet()) { it.atlasTileId }
		fun gone(key: String) = superseded.any { key == it || key.startsWith("$it:") }
		val removed = model.atlas.tiles.filter { tile -> tile.id !in used && tile.source?.layerKey?.let(::gone) == true }
		if (removed.isEmpty()) return model
		val keys = removed.mapNotNullTo(HashSet()) { it.source?.let { source -> source.sourceId to source.layerKey } }
		val remaining = model.atlas.tiles - removed.toSet()
		val kept = remaining.mapNotNullTo(HashSet()) { it.source?.let { source -> source.sourceId to source.layerKey } }
		return model.copy(atlas = model.atlas.copy(tiles = remaining), sources = model.sources.map { source ->
			source.copy(layers = source.layers.filterNot { (source.id to it.key) in keys && (source.id to it.key) !in kept })
		})
	}

	/**
	 * One primitive from [drawable] of [model], whose mesh stores canvas-unit texture coordinates in place of uvs.
	 * [sourceBounds] is the canvas rectangle the texture must cover; [neutralBounds] the drawable's neutral extent.
	 */
	fun encodePrimitive(model: PuppetModel, drawable: Drawable, layerId: String, textureSourceId: String,
	                    sourceBounds: LayerBounds, neutralBounds: Bounds): JsonObject {
		val mesh = requireNotNull(drawable.mesh) { "An art primitive needs a mesh" }
		RasterMeshJournal.validateMesh(mesh)
		val owner = model.parts.singleOrNull { OrgChild.Drawable(drawable.id) in it.children }
		val axes = (drawable.geometryGrid?.axes.orEmpty() + drawable.channelGrids.gridsByChannel.values.flatMap { it.axes })
			.map { it.parameterId } + drawable.blendShapes.flatMap { binding -> listOf(binding.parameterId) + binding.limits.map { it.parameterId } }
		return buildJsonObject {
			put("id", drawable.id.raw); put("layer_id", layerId); put("source_id", textureSourceId)
			put("source_bounds", floats(floatArrayOf(sourceBounds.left.toFloat(), sourceBounds.top.toFloat(),
				(sourceBounds.left + sourceBounds.width).toFloat(), (sourceBounds.top + sourceBounds.height).toFloat())))
			put("neutral_bounds", floats(floatArrayOf(neutralBounds.left, neutralBounds.top, neutralBounds.right, neutralBounds.bottom)))
			put("name", drawable.name); put("parent", drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
			put("part", owner?.id?.raw?.let(::JsonPrimitive) ?: JsonNull)
			put("blend", drawable.blendMode.name); put("opacity", drawable.opacity)
			put("order", drawable.drawOrder); put("visible", drawable.isVisible); put("selectable", drawable.isSelectable)
			put("multiply", color(drawable.multiplyColor)); put("screen", color(drawable.screenColor))
			put("invert_mask", drawable.invertMask); put("alpha_blend", drawable.alphaBlendMode.name); put("culling", drawable.culling)
			put("masks", JsonArray(drawable.maskedBy.map { JsonPrimitive(it.raw) })); put("user_data", drawable.userData)
			put("positions", floats(mesh.positions)); put("triangles", JsonArray(mesh.indices.map(::JsonPrimitive)))
			put("canvas_uvs", floats(mesh.uvs))
			put("parameters", JsonArray(axes.distinct().map { axis ->
				val parameter = model.parameters.single { it.id == axis }
				buildJsonObject {
					put("id", axis.raw); put("name", parameter.name); put("min", parameter.min)
					put("max", parameter.max); put("default", parameter.default); put("kind", parameter.kind.name); put("repeat", parameter.repeat)
				}
			}))
			put("geometry", drawable.geometryGrid?.let { grid -> RasterMeshCreation.grid(grid) { floats(it.positionDeltas) } } ?: JsonNull)
			put("channels", GeneratedRigJournalCodec.channels(drawable.channelGrids))
			put("blends", JsonArray(drawable.blendShapes.map { binding -> buildJsonObject {
				put("parameter", binding.parameterId.raw); put("keys", floats(binding.keys)); put("neutral", binding.neutralIndex)
				put("forms", JsonArray(binding.forms.map { form -> form?.let { buildJsonObject {
					put("deltas", floats(it.positionDeltas)); put("order", it.drawOrder); put("opacity", it.opacity)
					put("multiply", color(it.multiplyColor)); put("screen", color(it.screenColor))
				} } ?: JsonNull }))
				put("limits", JsonArray(binding.limits.map { limit -> buildJsonObject {
					put("parameter", limit.parameterId.raw)
					put("points", JsonArray(limit.points.map { point -> buildJsonArray { add(point.value); add(point.weight) } }))
				} }))
			} }))
			put("paths", JsonArray(model.deformPaths.filter { it.drawableId == drawable.id }.map(DeformPathJournal::encode)))
			put("vertex_groups", JsonArray(model.vertexGroups.filter { it.drawableId == drawable.id }.map { group -> buildJsonObject {
				put("name", group.name); put("kind", group.kind.jsonName); put("weights", floats(group.weights))
			} }))
		}
	}

	fun encodeGlue(glue: Glue): JsonObject = buildJsonObject {
		put("id", glue.id?.let(::JsonPrimitive) ?: JsonNull); put("a", glue.meshA.raw); put("b", glue.meshB.raw)
		put("intensity", glue.intensity); put("channels", GeneratedRigJournalCodec.channels(glue.channelGrids))
		put("pairs", JsonArray(glue.pairs.map { pair -> buildJsonArray { add(pair.indexA); add(pair.indexB); add(pair.weightA); add(pair.weightB) } }))
	}

	fun encode(origin: String, textureSourceId: String, supersedes: List<DrawableId>, supersededLayers: List<String>,
	           replace: Map<DrawableId, List<DrawableId>>, primitives: List<JsonObject>,
	           replacedGlues: List<List<Glue>>, appendedGlues: List<Glue>,
	           masks: Map<DrawableId, List<DrawableId>> = replace, version: Int = VERSION): JsonObject = buildJsonObject {
		put("op", OP); put("v", version); put("origin", origin); put("texture_source_id", textureSourceId)
		put("supersedes", JsonArray(supersedes.map { JsonPrimitive(it.raw) }))
		put("supersedes_layers", JsonArray(supersededLayers.map(::JsonPrimitive)))
		put("replace", buildJsonObject { replace.forEach { (id, ids) -> put(id.raw, JsonArray(ids.map { JsonPrimitive(it.raw) })) } })
		if (masks != replace) put("masks", buildJsonObject { masks.forEach { (id, ids) -> put(id.raw, JsonArray(ids.map { JsonPrimitive(it.raw) })) } })
		put("primitives", JsonArray(primitives))
		putJsonObject("glues") {
			put("replaced", JsonArray(replacedGlues.map { group -> JsonArray(group.map(::encodeGlue)) }))
			put("appended", JsonArray(appendedGlues.map(::encodeGlue)))
		}
	}

	/** The neutral bounds a primitive layer's canvas texture coordinates span. */
	fun canvasBounds(canvas: FloatArray): Bounds {
		var left = Float.POSITIVE_INFINITY; var top = Float.POSITIVE_INFINITY
		var right = Float.NEGATIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
		for (index in 0 until canvas.size - 1 step 2) {
			left = minOf(left, canvas[index]); right = maxOf(right, canvas[index])
			top = minOf(top, canvas[index + 1]); bottom = maxOf(bottom, canvas[index + 1])
		}
		return Bounds(left, top, right, bottom)
	}

	/** The integer rectangle covering [layer]'s raster and every canvas texture coordinate in [canvas]. */
	fun coverage(layer: LayerBounds, canvas: FloatArray): LayerBounds {
		val bounds = canvasBounds(canvas)
		val left = kotlin.math.floor(bounds.left.toDouble()).toInt(); val top = kotlin.math.floor(bounds.top.toDouble()).toInt()
		val right = maxOf(left + 1, kotlin.math.ceil(bounds.right.toDouble()).toInt())
		val bottom = maxOf(top + 1, kotlin.math.ceil(bounds.bottom.toDouble()).toInt())
		return union(layer, LayerBounds(left, top, right - left, bottom - top))
	}

	private fun union(a: LayerBounds, b: LayerBounds): LayerBounds {
		val left = minOf(a.left, b.left); val top = minOf(a.top, b.top)
		val right = maxOf(a.left + a.width, b.left + b.width); val bottom = maxOf(a.top + a.height, b.top + b.height)
		return LayerBounds(left, top, right - left, bottom - top)
	}

	private fun floats(values: FloatArray) = JsonArray(values.map(::JsonPrimitive))
	private fun color(value: ColorRgb) = floats(floatArrayOf(value.red, value.green, value.blue))
}
