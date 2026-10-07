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

	/**
	 * Replaces the superseded drawables of [command] in [input] by its primitives. A primitive in [skins] - one the
	 * skeleton skinned with the base rig - is placed as skinned, with the welds the bake gave it.
	 */
	fun replay(input: PuppetModel, command: JsonObject, skins: PrimitiveSkins = PrimitiveSkins.None): PuppetModel {
		if (command["v"]?.jsonPrimitive?.intOrNull == ArtPrimitiveV2.VERSION_V2) return replayV2(input, command, skins)
		require(command["v"]?.jsonPrimitive?.intOrNull == VERSION) { "Unsupported art primitive record version" }
		val superseded = command.getValue("supersedes").jsonArray.mapTo(LinkedHashSet()) { DrawableId(it.jsonPrimitive.content) }
		val records = primitives(command)
		val ids = records.map { DrawableId(it.text("id")) }
		require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.none { it in superseded } &&
			input.drawables.none { it.id in ids }) { "Art primitive IDs must be new and unique" }
		val replace = command.getValue("replace").jsonObject.entries.associate { (id, value) ->
			DrawableId(id) to value.jsonArray.map { DrawableId(it.jsonPrimitive.content) }
		}
		require(replace.keys.all { it in superseded } && replace.values.flatten().all { it in ids }) { "Invalid art primitive replacement" }
		// What masks by a superseded drawable now; its slot in the tree by default.
		val masks = (command["masks"] as? JsonObject)?.entries?.associate { (id, value) ->
			DrawableId(id) to value.jsonArray.map { DrawableId(it.jsonPrimitive.content) }
		} ?: replace
		require(masks.keys.all { it in superseded } && masks.values.flatten().all { it in ids }) { "Invalid art primitive mask replacement" }
		var model = input
		for (record in records) for (element in record.getValue("parameters").jsonArray) {
			val p = element.jsonObject
			if (model.parameters.any { it.id.raw == p.text("id") }) continue
			model = RigStructureEdits.replay(model, listOf(buildJsonObject {
				put("action", "create"); put("kind", "parameter"); put("id", p.getValue("id")); put("name", p.getValue("name"))
				put("min", p.getValue("min")); put("max", p.getValue("max")); put("default", p.getValue("default"))
				put("parameter_kind", p.getValue("kind")); put("repeat", p.getValue("repeat"))
			}))
		}
		val textureSource = command.text("texture_source_id")
		val built = records.map { record ->
			skins.drawables[DrawableId(record.text("id"))]?.let { adopt(model, it, record, textureSource) }
				?: decodePrimitive(model, record, textureSource, home = rehome(model, command, record, emptyList()))
		}
		val byId = built.associateBy { it.id }
		fun replacing(id: DrawableId) = replace[id].orEmpty()
		val placed = HashSet<DrawableId>()
		val drawables = model.drawables.flatMap { drawable ->
			if (drawable.id in superseded) replacing(drawable.id).map { byId.getValue(it).also { placed += it.id } }
			else if (drawable.maskedBy.none { it in superseded }) listOf(drawable)
			else listOf(drawable.copy(maskedBy = drawable.maskedBy.flatMap { if (it in superseded) masks[it].orEmpty() else listOf(it) }.distinct()))
		} + built.filterNot { it.id in placed }
		val drawableIds = drawables.mapTo(HashSet()) { it.id }
		built.forEach { drawable -> require(drawable.maskedBy.all { it in drawableIds }) { "Art primitive mask is missing" } }
		val inTree = HashSet<DrawableId>()
		fun children(old: List<OrgChild>) = old.flatMap { child ->
			if (child is OrgChild.Drawable && child.id in superseded) replacing(child.id).map { OrgChild.Drawable(it).also { inTree += it.id } }
			else listOf(child)
		}
		var parts = model.parts.map { it.copy(children = children(it.children)) }
		var roots = children(model.rootChildren)
		for ((index, drawable) in built.withIndex()) {
			if (drawable.id in inTree) continue
			// The superseded drawable was gone already: the primitive joins its recorded part, else the root.
			val part = records[index]["part"]?.jsonPrimitive?.contentOrNull?.let(::PartId)?.takeIf { id -> parts.any { it.id == id } }
			if (part == null) roots = roots + OrgChild.Drawable(drawable.id)
			else parts = parts.map { if (it.id == part) it.copy(children = it.children + OrgChild.Drawable(drawable.id)) else it }
		}
		val glues = command.getValue("glues").jsonObject
		val replaced = glues.getValue("replaced").jsonArray.map { group -> group.jsonArray.map { decodeGlue(model, it.jsonObject) } }
		val appended = glues.getValue("appended").jsonArray.map { decodeGlue(model, it.jsonObject) }
		val touching = model.glues.count { it.meshA in superseded || it.meshB in superseded }
		var next = 0
		val rewired = if (touching == replaced.size) model.glues.flatMap { glue ->
			if (glue.meshA in superseded || glue.meshB in superseded) replaced[next++] else listOf(glue)
		} else model.glues.filterNot { it.meshA in superseded || it.meshB in superseded } + replaced.flatten()
		val counts = drawables.associate { it.id to (it.mesh?.vertexCount ?: 0) }
		// A skinned primitive's welds join it once both its meshes are in place: with the later of the two records.
		val welds = skins.glues.filter { glue -> (glue.meshA in ids || glue.meshB in ids) && glue.meshA in counts && glue.meshB in counts }
		var result = model.copy(drawables = drawables, parts = parts, rootChildren = roots, glues = rewired + appended + welds,
			deformPaths = model.deformPaths.filterNot { it.drawableId in superseded },
			vertexGroups = model.vertexGroups.filterNot { it.drawableId in superseded })
		result.glues.forEach { glue ->
			require(glue.meshA in counts && glue.meshB in counts && glue.pairs.all { it.indexA in 0 until counts.getValue(glue.meshA) &&
				it.indexB in 0 until counts.getValue(glue.meshB) }) { "Art primitive Glue does not match its meshes" }
		}
		for ((index, record) in records.withIndex()) {
			val id = ids[index]
			for (path in record.getValue("paths").jsonArray) {
				val encoded = path.jsonObject
				require(encoded["target"]?.jsonPrimitive?.contentOrNull == "mesh:${id.raw}") { "Art primitive path targets another mesh" }
				result = DeformPathJournal.apply(result, encoded)
			}
			val groups = record.getValue("vertex_groups").jsonArray.map { element ->
				val group = element.jsonObject
				val weights = group.floats("weights")
				require(weights.size == counts.getValue(id)) { "Art primitive vertex group does not match its mesh" }
				VertexGroup(group.text("name"), id, VertexGroupKind.parse(group.text("kind")), weights)
			}
			require(groups.map { it.name }.distinct().size == groups.size) { "Duplicate art primitive vertex group" }
			result = result.copy(vertexGroups = result.vertexGroups + groups)
		}
		return result.withDerivedRenderRoot()
	}

	/**
	 * The primitives of [command] that the skeleton bake of [model] - a base rig, before the journal - can skin
	 * among [ids]: their mesh with canvas-unit texture coordinates and no tile, on their recorded parent. A
	 * primitive whose parent or keyform parameters the base lacks (they come from earlier journal entries), or that
	 * carries paths or vertex groups (the bake would not carry them onto new vertices), is left to its record.
	 */
	fun skinnable(model: PuppetModel, command: JsonObject, ids: Set<String>, earlier: List<JsonObject> = emptyList()): List<Drawable> {
		if (command["v"]?.jsonPrimitive?.intOrNull != VERSION) return emptyList()
		return primitives(command).filter { it.text("id") in ids && model.drawables.none { d -> d.id.raw == it.text("id") } &&
			it.getValue("paths").jsonArray.isEmpty() && it.getValue("vertex_groups").jsonArray.isEmpty() }
			.mapNotNull { record ->
				try {
					// Masks name drawables of the replayed rig; the record gives them back when it places the part.
					decodePrimitive(model, record, command.text("texture_source_id"), textured = false,
						home = rehome(model, command, record, earlier)).copy(maskedBy = emptyList())
				} catch (_: IllegalArgumentException) {
					null
				}
			}
	}

	/** [skinned], decoded from [record] and skinned with the base rig, textured from [model]'s atlas like a decoded one. */
	private fun adopt(model: PuppetModel, skinned: Drawable, record: JsonObject, textureSource: String): Drawable {
		val layer = record.text("layer_id")
		require(skinned.parentDeformerId == null || model.deformers.any { it.id == skinned.parentDeformerId }) {
			"Art primitive parent is missing: ${skinned.id.raw}"
		}
		val tile = model.atlas.tiles.singleOrNull { it.source?.let { source ->
			source.sourceId.raw == textureSource && source.layerKey == layer } == true }
			?: throw IllegalArgumentException("Art primitive artwork is missing: $layer")
		val placed = skinned.copy(atlasTileId = tile.id, texturePage = tile.placement?.pageIndex ?: -1,
			maskedBy = record.getValue("masks").jsonArray.map { DrawableId(it.jsonPrimitive.content) })
		val mesh = requireNotNull(skinned.mesh)
		return placed.copy(mesh = DrawableMesh(mesh.positions, RasterMeshJournal.TextureCoordinates(model, placed).toUvs(mesh.uvs), mesh.indices))
	}

	/** The parent a primitive takes in place of a recorded one the rig no longer has ([rehome]). */
	private class Home(val parent: DeformerId?)

	/**
	 * Where a primitive of [command] goes when [model] lacks its recorded parent: null while [model] has it (or it
	 * has none), and when the drawable it replaces cannot be found either - the record then fails as before.
	 *
	 * A recorded parent missing at the record is one the base generation no longer makes ([VanishedParent]). The
	 * primitive then goes where the drawable it replaces lives now. That drawable is in [model] when the record replays; a part of an earlier
	 * record is not in the base the skeleton bake decodes from, so [earlier] (the `art_primitive` records before
	 * [command]) resolve it the same way, through the drawable that part replaced.
	 */
	private fun rehome(model: PuppetModel, command: JsonObject, record: JsonObject, earlier: List<JsonObject>): Home? {
		val parent = record["parent"]?.jsonPrimitive?.contentOrNull?.let(::DeformerId) ?: return null
		if (model.deformers.any { it.id == parent }) return null
		val id = record.text("id")
		val replaced = command.getValue("replace").jsonObject.entries.firstOrNull { (_, value) ->
			value.jsonArray.any { it.jsonPrimitive.content == id }
		}?.key ?: command.getValue("supersedes").jsonArray.singleOrNull()?.jsonPrimitive?.content ?: return null
		model.drawables.firstOrNull { it.id.raw == replaced }?.let { return Home(it.parentDeformerId) }
		for (index in earlier.indices.reversed()) {
			val previous = primitives(earlier[index]).firstOrNull { it.text("id") == replaced } ?: continue
			val previousParent = previous["parent"]?.jsonPrimitive?.contentOrNull?.let(::DeformerId)
			if (previousParent == null || model.deformers.any { it.id == previousParent }) return Home(previousParent)
			return rehome(model, earlier[index], previous, earlier.subList(0, index))
		}
		return null
	}

	/**
	 * [drawable], decoded from a record whose positions and keyforms are in the space of a parent the rig no longer
	 * has, moved into the space of its new parent in [model] ([VanishedParent]): positions keep their canvas place at
	 * the default pose, keyform deltas their canvas displacement there.
	 */
	private fun rehomed(model: PuppetModel, drawable: Drawable, canvas: FloatArray): Drawable {
		val failure = "Art primitive parent cannot be evaluated: ${drawable.id.raw}"
		val mesh = requireNotNull(drawable.mesh)
		val fit = requireNotNull(VanishedParent.Affine.fit(mesh.positions, canvas)) { failure }
		val rest = fit.map(mesh.positions)
		val space = VanishedParent.Space(model, drawable, failure)
		val local = space.toLocal(rest)
		fun carry(deltas: FloatArray): FloatArray {
			val moved = space.toLocal(fit.displace(rest, deltas), local)
			return FloatArray(deltas.size) { moved[it] - local[it] }
		}
		return drawable.copy(mesh = DrawableMesh(local, mesh.uvs, mesh.indices),
			geometryGrid = drawable.geometryGrid?.let { grid ->
				KeyformGrid(grid.axes, grid.cells.map { KeyformCell(it.coordinate, MeshDeltaForm(carry(it.form.positionDeltas))) })
			},
			blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
				form?.let { MeshForm(carry(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
			}) })
	}

	/**
	 * One primitive of [record]: textured from [model]'s atlas, or with its canvas texture coordinates and no tile.
	 * [home] stands in for a recorded parent [model] no longer has ([rehome]).
	 */
	private fun decodePrimitive(model: PuppetModel, record: JsonObject, textureSource: String, textured: Boolean = true,
	                            home: Home? = null): Drawable {
		val decoded = decodeRecorded(model, record, textureSource, textured, home)
		return if (home == null) decoded else rehomed(model, decoded, record.floats("canvas_uvs"))
	}

	private fun decodeRecorded(model: PuppetModel, record: JsonObject, textureSource: String, textured: Boolean, home: Home?): Drawable {
		val id = DrawableId(record.text("id"))
		val layer = record.text("layer_id")
		require(layer.isNotBlank()) { "Art primitive layer is missing" }
		RasterMeshCreation.sourceBounds(record)
		val parent = if (home != null) home.parent else record["parent"]?.jsonPrimitive?.contentOrNull?.let(::DeformerId)
		require(parent == null || model.deformers.any { it.id == parent }) { "Art primitive parent is missing: ${id.raw}" }
		val tile = if (!textured) null else model.atlas.tiles.singleOrNull { it.source?.let { source ->
			source.sourceId.raw == textureSource && source.layerKey == layer } == true }
			?: throw IllegalArgumentException("Art primitive artwork is missing: $layer")
		val shell = Drawable(id, record.text("name"), parent, BlendMode.valueOf(record.text("blend")),
			record.getValue("masks").jsonArray.map { DrawableId(it.jsonPrimitive.content) }, null, null, atlasTileId = tile?.id,
			texturePage = tile?.placement?.pageIndex ?: -1)
		val positions = record.floats("positions")
		val canvasUvs = record.floats("canvas_uvs")
		require(canvasUvs.size == positions.size && canvasUvs.size % 2 == 0) { "Invalid art primitive texture coordinates" }
		val uvs = if (tile == null) canvasUvs else RasterMeshJournal.TextureCoordinates(model, shell).toUvs(canvasUvs)
		val mesh = DrawableMesh(positions, uvs, record.getValue("triangles").jsonArray.map { it.jsonPrimitive.int }.toIntArray())
		RasterMeshJournal.validateMesh(mesh)
		val geometry = record["geometry"]?.takeIf { it != JsonNull }?.jsonObject?.let { data ->
			RasterMeshCreation.decodeGrid(data, model) { value -> MeshDeltaForm(value.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
				require(it.size == positions.size && it.all(Float::isFinite)) { "Invalid art primitive keyform" }
			}) }
		}
		val blends = record.getValue("blends").jsonArray.map { element ->
			val b = element.jsonObject
			val parameter = ParameterId(b.text("parameter"))
			require(model.parameters.any { it.id == parameter }) { "Art primitive blend parameter is missing" }
			val keys = b.floats("keys"); val neutral = b.getValue("neutral").jsonPrimitive.int
			val forms = b.getValue("forms").jsonArray.map { form -> if (form == JsonNull) null else form.jsonObject.let { f ->
				val deltas = f.floats("deltas")
				require(deltas.size == positions.size) { "Invalid art primitive blend shape" }
				MeshForm(deltas, f.number("order"), f.number("opacity"), decodeColor(f.getValue("multiply")), decodeColor(f.getValue("screen")))
			} }
			require(keys.isNotEmpty() && keys.indices.drop(1).all { keys[it] > keys[it - 1] } && neutral in keys.indices && forms.size == keys.size) {
				"Invalid art primitive blend shape"
			}
			val limits = b.getValue("limits").jsonArray.map { item ->
				val limit = item.jsonObject
				val limitParameter = ParameterId(limit.text("parameter"))
				require(model.parameters.any { it.id == limitParameter }) { "Art primitive blend limit parameter is missing" }
				BlendWeightLimit(limitParameter, limit.getValue("points").jsonArray.map { point ->
					val pair = point.jsonArray.map { it.jsonPrimitive.float }
					require(pair.size == 2 && pair.all(Float::isFinite) && pair[1] in 0f..1f) { "Invalid art primitive blend limit" }
					BlendWeightLimitPoint(pair[0], pair[1])
				})
			}
			BlendShapeBinding(parameter, keys, neutral, forms, limits)
		}
		return shell.copy(mesh = mesh, geometryGrid = geometry,
			channelGrids = GeneratedRigJournalCodec.channels(record.getValue("channels").jsonObject, model), blendShapes = blends,
			drawOrder = record.number("order"), opacity = record.number("opacity"),
			multiplyColor = decodeColor(record.getValue("multiply")), screenColor = decodeColor(record.getValue("screen")),
			isVisible = record.getValue("visible").jsonPrimitive.boolean, isSelectable = record.getValue("selectable").jsonPrimitive.boolean,
			invertMask = record.getValue("invert_mask").jsonPrimitive.boolean, alphaBlendMode = AlphaBlendMode.valueOf(record.text("alpha_blend")),
			culling = record.getValue("culling").jsonPrimitive.boolean, userData = record.text("user_data"))
	}

	fun decodeGlue(model: PuppetModel, value: JsonObject): Glue = Glue(DrawableId(value.text("a")), DrawableId(value.text("b")),
		value.getValue("pairs").jsonArray.map { element ->
			val row = element.jsonArray
			require(row.size == 4) { "Invalid art primitive Glue pair" }
			GluePair(row[0].jsonPrimitive.int, row[1].jsonPrimitive.int, row[2].jsonPrimitive.float, row[3].jsonPrimitive.float).also {
				require(it.weightA.isFinite() && it.weightB.isFinite()) { "Invalid art primitive Glue weight" }
			}
		}, GeneratedRigJournalCodec.channels(value.getValue("channels").jsonObject, model), value.number("intensity"),
		value["id"]?.jsonPrimitive?.contentOrNull)

	// ---- Version 2 ([ArtPrimitiveV2]) ----

	/** A Glue the skeleton bake generates; version 2 records keep user Glues only and take welds from the base. */
	fun isSkeletonWeld(glue: Glue): Boolean = glue.id?.startsWith("GlueSkel__") == true

	/**
	 * How the vertices of a recorded mesh ([canvas] texture coordinates and [triangles]) reach a generated part's
	 * vertices: null when they are the same vertices, else a migration plan keyed on canvas texture coordinates -
	 * a regenerated part keeps the pixels its vertices sample - with each generated vertex's source among the
	 * recorded ones and, as its Glue map, each recorded vertex's nearest generated one.
	 */
	fun vertexMap(canvas: FloatArray, triangles: IntArray, parkedCanvas: FloatArray, parkedTriangles: IntArray): RasterMeshJournal.Plan? {
		if (canvas.size == parkedCanvas.size && triangles.contentEquals(parkedTriangles) &&
			canvas.indices.all { kotlin.math.abs(canvas[it] - parkedCanvas[it]) <= 0.01f }) return null
		return RasterMeshJournal.prepare(DrawableMesh(canvas, canvas, triangles), DrawableMesh(parkedCanvas, parkedCanvas, parkedTriangles))
	}

	/**
	 * [vertexMap] from a recorded mesh to a part as the base parked it ([PrimitiveSkins.drawables], texture
	 * coordinates in canvas units): null when it kept the recorded vertices and triangles (the base never re-meshes
	 * a part; only skeleton joint rows add vertices).
	 */
	fun partMap(parked: Drawable, canvas: FloatArray, triangles: IntArray): RasterMeshJournal.Plan? {
		val mesh = requireNotNull(parked.mesh)
		if (mesh.positions.size == canvas.size && mesh.indices.contentEquals(triangles)) return null
		return vertexMap(canvas, triangles, mesh.uvs, mesh.indices)
	}

	/** One part of a version 2 record and the `generated_override` entries for its edited generator cells. */
	class EncodedPart(val primitive: JsonObject, val overrides: List<JsonObject>)

	/**
	 * One version 2 primitive. [authoredPart] is the part in [authoredModel] (the superseded drawable's authored
	 * state carried onto it by the partition, texture coordinates in canvas units). [ghost] is the superseded
	 * drawable in [ghostAuthored] (the authored rig the partition ran on) and [ghostGenerated] (the base rig);
	 * [sources] derive the part's vertices from the ghost's. [parked] is the part the pinned base generated, in
	 * [parkedModel] (that base with the part spliced in); null for the first pass, which records no residual. The
	 * record's base fields never depend on [parked], so the base the first pass builds stays valid.
	 */
	fun encodePrimitiveV2(authoredModel: PuppetModel, authoredPart: Drawable, ghostAuthored: PuppetModel, ghostGenerated: PuppetModel,
	                      ghost: DrawableId, sources: List<VertexSource>,
	                      layerId: String, textureSourceId: String, sourceBounds: LayerBounds, neutralBounds: Bounds,
	                      classification: LayerClassificationOverride, fixedTopology: Boolean,
	                      parkedModel: PuppetModel? = null, parked: Drawable? = null, skins: PrimitiveSkins = PrimitiveSkins.None,
	                      checkpoint: () -> Unit = {}): EncodedPart {
		val mesh = requireNotNull(authoredPart.mesh) { "An art primitive needs a mesh" }
		RasterMeshJournal.validateMesh(mesh)
		require(sources.size == mesh.vertexCount) { "Art primitive vertex sources do not match its mesh" }
		val authoredGhost = ghostAuthored.drawables.single { it.id == ghost }
		val generatedPart = ghostGenerated.drawables.singleOrNull { it.id == ghost }
			?: throw IllegalArgumentException("The split original is not generated: ${ghost.raw}")
		val generatedModel = ghostGenerated
		val ghostVertices = requireNotNull(authoredGhost.mesh).vertexCount
		require(requireNotNull(generatedPart.mesh).vertexCount == ghostVertices &&
			RasterMeshJournal.fingerprint(generatedPart.mesh!!) == RasterMeshJournal.fingerprint(authoredGhost.mesh!!)) {
			"The split original's mesh was edited by hand"
		}
		// Rest positions through the parent at the default pose, without the part's own keyforms (those are the residual's).
		// Under its generated parent the part is placed through the generated deformers: the base normalises these
		// positions into that parent, and the journal's own edits of the parent chain (a moved rest lattice) replay on
		// top, so taking them from the authored rig would apply those edits twice. A parent a journal edit chose is
		// held in canvas units and placed into the authored parent on replay, so it maps through the authored rig.
		val generatedParent = authoredPart.parentDeformerId == generatedPart.parentDeformerId &&
			(authoredPart.parentDeformerId == null || ghostGenerated.deformers.any { it.id == authoredPart.parentDeformerId })
		val placement = if (generatedParent) ghostGenerated.copy(drawables = ghostGenerated.drawables.filterNot { it.id == authoredPart.id } +
			authoredPart.copy(geometryGrid = null, blendShapes = emptyList())) else authoredModel
		val world = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(placement, emptyMap(), authoredPart.id)) {
			"Art primitive parent cannot be evaluated: ${authoredPart.id.raw}"
		}.localToWorld(mesh.positions)
		val canvasPositions = FloatArray(world.size) { if (it % 2 == 0) world[it] else -world[it] }
		fun owner(model: PuppetModel, drawable: Drawable) = model.parts.singleOrNull { OrgChild.Drawable(drawable.id) in it.children }?.id
		val a = authoredPart; val g = generatedPart
		val generatedPaths = generatedModel.deformPaths.filter { it.drawableId == g.id }.mapTo(HashSet()) { it.id }
		val generatedGroups = generatedModel.vertexGroups.filter { it.drawableId == g.id }
		val generatedBlends = g.blendShapes.mapTo(HashSet()) { it.parameterId }
		val userBlends = a.blendShapes.filter { it.parameterId !in generatedBlends }
		// The partition names a carried path `<part>/<path>` (a depth front `<glue>/<path>`); generated ones stay with the base.
		val userPaths = authoredModel.deformPaths.filter { path -> path.drawableId == a.id &&
			path.id !in generatedPaths && generatedPaths.none { path.id.endsWith("/$it") } }
		val userGroups = authoredModel.vertexGroups.filter { group -> group.drawableId == a.id &&
			generatedGroups.none { it.name == group.name && it.kind == group.kind &&
				PrimitiveResidual.transferScalars(it.weights, sources).let { weights -> weights.indices.all { i -> kotlin.math.abs(weights[i] - group.weights[i]) <= 1e-6f } } } }
		val parameters = authoredModel.parameters.associateBy { it.id }
		val delta = PrimitiveResidual.delta(ghostAuthored, authoredGhost, ghostGenerated, generatedPart, sources, checkpoint)
		// Per-vertex user data pins the recorded topology: the skeleton must not insert joint rows under it.
		val pinned = fixedTopology || delta != null || userBlends.isNotEmpty() || userPaths.isNotEmpty() || userGroups.isNotEmpty()
		val residual = if (parked == null || parkedModel == null) null else {
			val parkedMesh = requireNotNull(parked.mesh)
			val map = partMap(parked, mesh.uvs, mesh.indices)
			val seed = if (map == null) parkedMesh.positions else FloatArray(mesh.positions.size) { 0.5f }
			val space = PrimitiveResidual.ParentSpace(authoredModel, a, parkedModel, parked, seed)
			// A part the base generates no keyforms for (a zero grid: a split lip ribbon, a part under a parent a journal
			// edit made) has nothing to add back the original's generated motion: its residual is the whole authored form.
			val unkeyed = parked.geometryGrid?.axes.isNullOrEmpty() && generatedPart.geometryGrid?.axes?.isNotEmpty() == true
			val residualDelta = if (!unkeyed) delta else PrimitiveResidual.delta(ghostAuthored, authoredGhost, ghostGenerated,
				generatedPart.copy(geometryGrid = null), sources, checkpoint)
			PrimitiveResidual.geometry(parameters, residualDelta, mesh.positions.size, parked, space::convert,
				{ values -> map?.let { PrimitiveResidual.transfer(values, it.sources) } ?: values }, { skins.owner(parked.id, it) }, checkpoint)
		}
		// Channels belong to the drawable, not its vertices: the part's own (a depth slice drops its draw order grid) over the original's.
		val channels = if (parked == null) ChannelGrids.Empty else PrimitiveResidual.channels(authoredModel, a, g, checkpoint)
		val used = (residual?.residual?.axes.orEmpty().map { it.parameterId } + channels.gridsByChannel.values.flatMap { grid -> grid.axes.map { it.parameterId } } +
			userBlends.flatMap { binding -> listOf(binding.parameterId) + binding.limits.map { it.parameterId } }).distinct()
		val generatedParameters = generatedModel.parameters.mapTo(HashSet()) { it.id }
		val authored = buildJsonObject {
			if (owner(authoredModel, a) != owner(generatedModel, g)) put(ArtPrimitiveV2.PART, owner(authoredModel, a)?.raw?.let(::JsonPrimitive) ?: JsonNull)
			if (a.blendMode != g.blendMode) put(ArtPrimitiveV2.BLEND, a.blendMode.name)
			if (a.opacity != g.opacity) put(ArtPrimitiveV2.OPACITY, a.opacity)
			put(ArtPrimitiveV2.ORDER, a.drawOrder)
			if (a.isVisible != g.isVisible) put(ArtPrimitiveV2.VISIBLE, a.isVisible)
			if (a.isSelectable != g.isSelectable) put(ArtPrimitiveV2.SELECTABLE, a.isSelectable)
			if (a.multiplyColor != g.multiplyColor) put(ArtPrimitiveV2.MULTIPLY, color(a.multiplyColor))
			if (a.screenColor != g.screenColor) put(ArtPrimitiveV2.SCREEN, color(a.screenColor))
			if (a.invertMask != g.invertMask) put(ArtPrimitiveV2.INVERT_MASK, a.invertMask)
			if (a.alphaBlendMode != g.alphaBlendMode) put(ArtPrimitiveV2.ALPHA_BLEND, a.alphaBlendMode.name)
			if (a.culling != g.culling) put(ArtPrimitiveV2.CULLING, a.culling)
			if (a.userData != g.userData) put(ArtPrimitiveV2.USER_DATA, a.userData)
			if (a.maskedBy != g.maskedBy) put(ArtPrimitiveV2.MASKS, JsonArray(a.maskedBy.map { JsonPrimitive(it.raw) }))
			put(ArtPrimitiveV2.PARAMETERS, JsonArray(used.filter { it !in generatedParameters }.map { axis ->
				val parameter = authoredModel.parameters.single { it.id == axis }
				buildJsonObject {
					put("id", axis.raw); put("name", parameter.name); put("min", parameter.min)
					put("max", parameter.max); put("default", parameter.default); put("kind", parameter.kind.name); put("repeat", parameter.repeat)
				}
			}))
			put(ArtPrimitiveV2.GEOMETRY_RESIDUAL, residual?.residual?.let { grid -> RasterMeshCreation.grid(grid) { floats(it.positionDeltas) } } ?: JsonNull)
			put(ArtPrimitiveV2.CHANNELS_RESIDUAL, GeneratedRigJournalCodec.channels(channels))
			put(ArtPrimitiveV2.BLENDS, JsonArray(userBlends.map(::encodeBlend)))
			put(ArtPrimitiveV2.PATHS, JsonArray(userPaths.map(DeformPathJournal::encode)))
			put(ArtPrimitiveV2.VERTEX_GROUPS, JsonArray(userGroups.map { group -> buildJsonObject {
				put("name", group.name); put("kind", group.kind.jsonName); put("weights", floats(group.weights))
			} }))
		}
		val primitive = buildJsonObject {
			put(ArtPrimitiveV2.ID, a.id.raw); put(ArtPrimitiveV2.LAYER_ID, layerId); put(ArtPrimitiveV2.SOURCE_ID, textureSourceId)
			put(ArtPrimitiveV2.SOURCE_BOUNDS, floats(floatArrayOf(sourceBounds.left.toFloat(), sourceBounds.top.toFloat(),
				(sourceBounds.left + sourceBounds.width).toFloat(), (sourceBounds.top + sourceBounds.height).toFloat())))
			put(ArtPrimitiveV2.NEUTRAL_BOUNDS, floats(floatArrayOf(neutralBounds.left, neutralBounds.top, neutralBounds.right, neutralBounds.bottom)))
			put(ArtPrimitiveV2.NAME, a.name)
			put(ArtPrimitiveV2.CLASSIFICATION, ArtPrimitiveV2.encodeClassification(classification))
			// Generated unless a journal edit had reparented the original.
			put(ArtPrimitiveV2.PARENT, a.parentDeformerId?.takeIf { it != g.parentDeformerId }?.raw?.let(::JsonPrimitive) ?: JsonNull)
			putJsonObject(ArtPrimitiveV2.MESH) {
				put(ArtPrimitiveV2.CANVAS_POSITIONS, floats(canvasPositions))
				put(ArtPrimitiveV2.TRIANGLES, JsonArray(mesh.indices.map(::JsonPrimitive)))
				put(ArtPrimitiveV2.CANVAS_UVS, floats(mesh.uvs))
			}
			put(ArtPrimitiveV2.FIXED_TOPOLOGY, pinned)
			put(ArtPrimitiveV2.FROZEN_AXES, JsonArray(emptyList()))
			put(ArtPrimitiveV2.AUTHORED, authored)
		}
		return EncodedPart(primitive, residual?.overrides.orEmpty())
	}

	private fun encodeBlend(binding: BlendShapeBinding<MeshForm>) = buildJsonObject {
		put("parameter", binding.parameterId.raw); put("keys", floats(binding.keys)); put("neutral", binding.neutralIndex)
		put("forms", JsonArray(binding.forms.map { form -> form?.let { buildJsonObject {
			put("deltas", floats(it.positionDeltas)); put("order", it.drawOrder); put("opacity", it.opacity)
			put("multiply", color(it.multiplyColor)); put("screen", color(it.screenColor))
		} } ?: JsonNull }))
		put("limits", JsonArray(binding.limits.map { limit -> buildJsonObject {
			put("parameter", limit.parameterId.raw)
			put("points", JsonArray(limit.points.map { point -> buildJsonArray { add(point.value); add(point.weight) } }))
		} }))
	}

	private fun decodeBlends(blends: JsonArray, model: PuppetModel, size: Int, carry: (FloatArray) -> FloatArray): List<BlendShapeBinding<MeshForm>> = blends.map { element ->
		val b = element.jsonObject
		val parameter = ParameterId(b.text("parameter"))
		require(model.parameters.any { it.id == parameter }) { "Art primitive blend parameter is missing" }
		val keys = b.floats("keys"); val neutral = b.getValue("neutral").jsonPrimitive.int
		val forms = b.getValue("forms").jsonArray.map { form -> if (form == JsonNull) null else form.jsonObject.let { f ->
			val deltas = f.floats("deltas")
			require(deltas.size == size) { "Invalid art primitive blend shape" }
			MeshForm(carry(deltas), f.number("order"), f.number("opacity"), decodeColor(f.getValue("multiply")), decodeColor(f.getValue("screen")))
		} }
		require(keys.isNotEmpty() && keys.indices.drop(1).all { keys[it] > keys[it - 1] } && neutral in keys.indices && forms.size == keys.size) {
			"Invalid art primitive blend shape"
		}
		val limits = b.getValue("limits").jsonArray.map { item ->
			val limit = item.jsonObject
			val limitParameter = ParameterId(limit.text("parameter"))
			require(model.parameters.any { it.id == limitParameter }) { "Art primitive blend limit parameter is missing" }
			BlendWeightLimit(limitParameter, limit.getValue("points").jsonArray.map { point ->
				val pair = point.jsonArray.map { it.jsonPrimitive.float }
				require(pair.size == 2 && pair.all(Float::isFinite) && pair[1] in 0f..1f) { "Invalid art primitive blend limit" }
				BlendWeightLimitPoint(pair[0], pair[1])
			})
		}
		BlendShapeBinding(parameter, keys, neutral, forms, limits)
	}

	private fun creationOf(p: JsonObject) = buildJsonObject {
		put("action", "create"); put("kind", "parameter"); put("id", p.getValue("id")); put("name", p.getValue("name"))
		put("min", p.getValue("min")); put("max", p.getValue("max")); put("default", p.getValue("default"))
		put("parameter_kind", p.getValue("kind")); put("repeat", p.getValue("repeat"))
	}

	/**
	 * A version 2 record: the superseded drawables give way to the parts the base generated and held back in
	 * [skins] - every part must be there - with the generated masks of other drawables restored, the skeleton's
	 * welds once both their meshes are placed, and the record's authored layer applied on top: material fields,
	 * masks, slot, residual geometry and channels, user blends, paths, vertex groups and Glues. When a part's
	 * generated mesh is not the recorded one (joint rows the skeleton inserted, a later regeneration), everything
	 * per vertex is carried over by canvas texture coordinates ([vertexMap]).
	 */
	private fun replayV2(input: PuppetModel, command: JsonObject, skins: PrimitiveSkins): PuppetModel {
		val superseded = command.getValue("supersedes").jsonArray.mapTo(LinkedHashSet()) { DrawableId(it.jsonPrimitive.content) }
		val records = primitives(command)
		val ids = records.map { DrawableId(it.text("id")) }
		require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.none { it in superseded } &&
			input.drawables.none { it.id in ids }) { "Art primitive IDs must be new and unique" }
		val replace = command.getValue("replace").jsonObject.entries.associate { (id, value) ->
			DrawableId(id) to value.jsonArray.map { DrawableId(it.jsonPrimitive.content) }
		}
		require(replace.keys.all { it in superseded } && replace.values.flatten().all { it in ids }) { "Invalid art primitive replacement" }
		val masks = (command["masks"] as? JsonObject)?.entries?.associate { (id, value) ->
			DrawableId(id) to value.jsonArray.map { DrawableId(it.jsonPrimitive.content) }
		} ?: replace
		require(masks.keys.all { it in superseded } && masks.values.flatten().all { it in ids }) { "Invalid art primitive mask replacement" }
		for (id in ids) require(skins.holds(id)) { "Art primitive part was not generated: ${id.raw}" }
		val authoredLayers = records.map { it.getValue(ArtPrimitiveV2.AUTHORED).jsonObject }
		var model = input
		for (authored in authoredLayers) for (element in authored.getValue(ArtPrimitiveV2.PARAMETERS).jsonArray) {
			val p = element.jsonObject
			if (model.parameters.any { it.id.raw == p.text("id") }) continue
			model = RigStructureEdits.replay(model, listOf(creationOf(p)))
		}
		val parameters = model.parameters.associateBy { it.id }
		val textureSource = command.text("texture_source_id")
		val maps = ArrayList<RasterMeshJournal.Plan?>()
		val recordedMeshes = ArrayList<DrawableMesh>()
		val built = records.mapIndexed { index, record ->
			val authored = authoredLayers[index]
			ArtPrimitiveV2.decodePrimitive(record, 0).also { require(it.frozenAxes.isEmpty()) { "Art primitive frozen axes are not supported" } }
			val held = skins.drawables.getValue(ids[index])
			var part = held
			require(part.parentDeformerId == null || model.deformers.any { it.id == part.parentDeformerId }) {
				"Art primitive parent is missing: ${part.id.raw}"
			}
			// A part whose record names its parent (a journal edit made it) is held in canvas units, without keyforms:
			// it moves into that parent's space now that the parent exists.
			if (part.id in skins.deferredParents && part.parentDeformerId != null) {
				val m = requireNotNull(part.mesh)
				val mapping = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(model.copy(drawables = model.drawables + part), emptyMap(), part.id)) {
					"Art primitive parent cannot be evaluated: ${part.id.raw}"
				}
				val world = FloatArray(m.positions.size) { if (it % 2 == 0) m.positions[it] else -m.positions[it] }
				val local = mapping.worldToLocalLinearized(world, FloatArray(world.size) { 0.5f }, world, (0 until m.vertexCount).toSet())
				part = part.copy(mesh = DrawableMesh(local, m.uvs, m.indices))
			}
			// Held parts texture in canvas units; the current atlas turns them into page uvs. A rig without any atlas
			// tile (an untextured rig) keeps them.
			val tile = model.atlas.tiles.firstOrNull { it.id == part.atlasTileId } ?: model.atlas.tiles.singleOrNull { it.source?.let { source ->
				source.sourceId.raw == textureSource && source.layerKey == record.text("layer_id") } == true }
			if (tile != null) {
				val placed = part.copy(atlasTileId = tile.id, texturePage = tile.placement?.pageIndex ?: -1)
				val m = requireNotNull(part.mesh)
				part = placed.copy(mesh = DrawableMesh(m.positions, RasterMeshJournal.TextureCoordinates(model, placed).toUvs(m.uvs), m.indices))
			} else require(model.atlas.tiles.isEmpty()) { "Art primitive artwork is missing: ${record.text("layer_id")}" }
			val parkedMesh = requireNotNull(part.mesh)
			val recorded = record.getValue(ArtPrimitiveV2.MESH).jsonObject
			val recordedCanvas = recorded.floats(ArtPrimitiveV2.CANVAS_UVS)
			val recordedTriangles = recorded.getValue(ArtPrimitiveV2.TRIANGLES).jsonArray.map { it.jsonPrimitive.int }.toIntArray()
			recordedMeshes += DrawableMesh(recordedCanvas, recordedCanvas, recordedTriangles)
			val map = partMap(held, recordedCanvas, recordedTriangles)
			maps += map
			val recordedSize = recordedCanvas.size
			fun carry(values: FloatArray): FloatArray = map?.let { PrimitiveResidual.transfer(values, it.sources) } ?: values
			val residual = authored[ArtPrimitiveV2.GEOMETRY_RESIDUAL]?.takeIf { it != JsonNull }?.jsonObject?.let { data ->
				RasterMeshCreation.decodeGrid(data, model) { value -> MeshDeltaForm(value.jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also {
					require(it.size == recordedSize && it.all(Float::isFinite)) { "Invalid art primitive residual" }
				}) }
			}
			val geometry = PrimitiveResidual.compose(parameters, part.geometryGrid, PrimitiveResidual.carry(residual, ::carry), parkedMesh.positions.size)
			val channels = PrimitiveResidual.composeChannels(parameters, part,
				GeneratedRigJournalCodec.channels(authored.getValue(ArtPrimitiveV2.CHANNELS_RESIDUAL).jsonObject, model))
			val blends = decodeBlends(authored.getValue(ArtPrimitiveV2.BLENDS).jsonArray, model, recordedSize, ::carry)
			val userBlends = blends.mapTo(HashSet()) { it.parameterId }
			part.copy(geometryGrid = geometry, channelGrids = channels,
				blendShapes = part.blendShapes.filterNot { it.parameterId in userBlends } + blends,
				drawOrder = authored[ArtPrimitiveV2.ORDER]?.let { it.jsonPrimitive.float.also { v -> require(v.isFinite()) } } ?: part.drawOrder,
				opacity = authored[ArtPrimitiveV2.OPACITY]?.jsonPrimitive?.float ?: part.opacity,
				blendMode = authored[ArtPrimitiveV2.BLEND]?.let { BlendMode.valueOf(it.jsonPrimitive.content) } ?: part.blendMode,
				isVisible = authored[ArtPrimitiveV2.VISIBLE]?.jsonPrimitive?.boolean ?: part.isVisible,
				isSelectable = authored[ArtPrimitiveV2.SELECTABLE]?.jsonPrimitive?.boolean ?: part.isSelectable,
				multiplyColor = authored[ArtPrimitiveV2.MULTIPLY]?.let(::decodeColor) ?: part.multiplyColor,
				screenColor = authored[ArtPrimitiveV2.SCREEN]?.let(::decodeColor) ?: part.screenColor,
				invertMask = authored[ArtPrimitiveV2.INVERT_MASK]?.jsonPrimitive?.boolean ?: part.invertMask,
				alphaBlendMode = authored[ArtPrimitiveV2.ALPHA_BLEND]?.let { AlphaBlendMode.valueOf(it.jsonPrimitive.content) } ?: part.alphaBlendMode,
				culling = authored[ArtPrimitiveV2.CULLING]?.jsonPrimitive?.boolean ?: part.culling,
				userData = authored[ArtPrimitiveV2.USER_DATA]?.jsonPrimitive?.content ?: part.userData,
				maskedBy = authored[ArtPrimitiveV2.MASKS]?.takeIf { it != JsonNull }?.jsonArray?.map { DrawableId(it.jsonPrimitive.content) } ?: part.maskedBy)
		}
		val byId = built.associateBy { it.id }
		fun replacing(id: DrawableId) = replace[id].orEmpty()
		// A drawable masked by a superseded one: the parts its generated masks name, else the record's mapping.
		fun maskReplacement(drawable: DrawableId, mask: DrawableId) =
			skins.generatedMasks[drawable]?.filter { it in byId }?.takeIf { it.isNotEmpty() } ?: masks[mask].orEmpty()
		val placed = HashSet<DrawableId>()
		val drawables = model.drawables.flatMap { drawable ->
			if (drawable.id in superseded) replacing(drawable.id).map { byId.getValue(it).also { placed += it.id } }
			else if (drawable.maskedBy.none { it in superseded }) listOf(drawable)
			else listOf(drawable.copy(maskedBy = drawable.maskedBy.flatMap { if (it in superseded) maskReplacement(drawable.id, it) else listOf(it) }.distinct()))
		} + built.filterNot { it.id in placed }
		val drawableIds = drawables.mapTo(HashSet()) { it.id }
		require(drawableIds.size == drawables.size) {
			"Art primitive placement duplicates drawable ${drawables.groupBy { it.id }.filterValues { it.size > 1 }.keys.joinToString { it.raw }}"
		}
		// A mask or Glue naming a drawable an earlier journal entry creates is dropped when that entry is not replayed:
		// migration builds replay the version 2 records alone.
		val drawablesPlaced = drawables.map { drawable ->
			if (drawable.id !in byId || drawable.maskedBy.all { it in drawableIds }) drawable
			else drawable.copy(maskedBy = drawable.maskedBy.filter { it in drawableIds })
		}
		// Slots: the authored part, else the generated slot, else the superseded drawable's.
		val slots = LinkedHashMap<DrawableId, PartId?>()
		for ((index, id) in ids.withIndex()) {
			val authoredPart = authoredLayers[index][ArtPrimitiveV2.PART]
			if (authoredPart != null) slots[id] = authoredPart.jsonPrimitive.contentOrNull?.let(::PartId)?.takeIf { part -> model.parts.any { it.id == part } }
			else skins.partSlots[id]?.takeIf { part -> model.parts.any { it.id == part } }?.let { slots[id] = it }
		}
		val inTree = HashSet<DrawableId>()
		// A part whose slot is the superseded drawable's takes its place there; one slotted elsewhere joins that part.
		fun children(container: PartId?, old: List<OrgChild>) = old.flatMap { child ->
			if (child is OrgChild.Drawable && child.id in superseded)
				replacing(child.id).filter { it !in slots || slots[it] == container }.map { OrgChild.Drawable(it).also { inTree += it.id } }
			else listOf(child)
		}
		var parts = model.parts.map { it.copy(children = children(it.id, it.children)) }
		var roots = children(null, model.rootChildren)
		for (drawable in built) {
			if (drawable.id in inTree) continue
			val part = slots[drawable.id]
			if (part == null) roots = roots + OrgChild.Drawable(drawable.id)
			else parts = parts.map { if (it.id == part) it.copy(children = it.children + OrgChild.Drawable(drawable.id)) else it }
		}
		// User Glues name recorded vertices; generated ones reach the parked vertices through the Glue map.
		fun vertex(mesh: DrawableId, index: Int): Int = ids.indexOf(mesh).takeIf { it >= 0 }?.let { maps[it]?.glueMap?.getOrNull(index) } ?: index
		fun remap(glue: Glue) = glue.copy(pairs = glue.pairs.map { GluePair(vertex(glue.meshA, it.indexA), vertex(glue.meshB, it.indexB), it.weightA, it.weightB) })
		val glues = command.getValue("glues").jsonObject
		fun present(glue: Glue) = glue.meshA in drawableIds && glue.meshB in drawableIds
		val replaced = glues.getValue("replaced").jsonArray.map { group -> group.jsonArray.map { remap(decodeGlue(model, it.jsonObject)) }.filter(::present) }
		val appended = glues.getValue("appended").jsonArray.map { remap(decodeGlue(model, it.jsonObject)) }.filter(::present)
		fun touches(glue: Glue) = glue.meshA in superseded || glue.meshB in superseded
		val touching = model.glues.count { touches(it) && !isSkeletonWeld(it) }
		var next = 0
		val rewired = if (touching == replaced.size) model.glues.flatMap { glue ->
			if (!touches(glue)) listOf(glue) else if (isSkeletonWeld(glue)) emptyList() else replaced[next++]
		} else model.glues.filterNot(::touches) + replaced.flatten()
		val counts = drawablesPlaced.associate { it.id to (it.mesh?.vertexCount ?: 0) }
		val welds = skins.glues.filter { glue -> (glue.meshA in byId || glue.meshB in byId) && glue.meshA in counts && glue.meshB in counts }
		var result = model.copy(drawables = drawablesPlaced, parts = parts, rootChildren = roots, glues = rewired + appended + welds,
			deformPaths = model.deformPaths.filterNot { it.drawableId in superseded } + skins.paths.filter { it.drawableId in byId },
			vertexGroups = model.vertexGroups.filterNot { it.drawableId in superseded })
		result.glues.forEach { glue ->
			require(glue.meshA in counts && glue.meshB in counts && glue.pairs.all { it.indexA in 0 until counts.getValue(glue.meshA) &&
				it.indexB in 0 until counts.getValue(glue.meshB) }) { "Art primitive Glue does not match its meshes" }
		}
		for ((index, record) in records.withIndex()) {
			val id = ids[index]
			val authored = authoredLayers[index]
			val map = maps[index]
			val parked = byId.getValue(id)
			for (path in authored.getValue(ArtPrimitiveV2.PATHS).jsonArray) {
				val encoded = path.jsonObject
				require(encoded["target"]?.jsonPrimitive?.contentOrNull == "mesh:${id.raw}") { "Art primitive path targets another mesh" }
				result = if (map == null) DeformPathJournal.apply(result, encoded) else {
					// Bound on the recorded mesh, then moved onto the generated one through canvas texture coordinates.
					val recorded = recordedMeshes[index]
					val bound = DeformPathJournal.apply(result.copy(drawables = listOf(parked.copy(mesh = recorded)), deformPaths = emptyList()), encoded)
						.deformPaths.single()
					val held = requireNotNull(skins.drawables.getValue(id).mesh)
					val target = DrawableMesh(held.uvs, held.uvs, held.indices)
					val rebound = DeformPathJournal.rebind(bound, recorded, target)
					require(result.deformPaths.none { it.id == rebound.id && it.drawableId != id }) { "Path belongs to another mesh" }
					result.copy(deformPaths = result.deformPaths.filterNot { it.id == rebound.id } + rebound)
				}
			}
			val groups = authored.getValue(ArtPrimitiveV2.VERTEX_GROUPS).jsonArray.map { element ->
				val group = element.jsonObject
				val weights = group.floats("weights")
				require(weights.size == recordedMeshes[index].vertexCount) { "Art primitive vertex group does not match its mesh" }
				VertexGroup(group.text("name"), id, VertexGroupKind.parse(group.text("kind")),
					map?.let { PrimitiveResidual.transferScalars(weights, it.sources) } ?: weights)
			}
			require(groups.map { it.name }.distinct().size == groups.size) { "Duplicate art primitive vertex group" }
			// A user group replaces a generated one of the same name.
			val names = groups.mapTo(HashSet()) { it.name }
			result = result.copy(vertexGroups = result.vertexGroups.filterNot { it.drawableId == id && it.name in names } + groups)
		}
		return result.withDerivedRenderRoot()
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
	private fun decodeColor(value: JsonElement): ColorRgb {
		val numbers = value.jsonArray.map { it.jsonPrimitive.float }
		require(numbers.size == 3 && numbers.all(Float::isFinite)) { "Invalid art primitive color" }
		return ColorRgb(numbers[0], numbers[1], numbers[2])
	}
	private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
	private fun JsonObject.number(name: String) = getValue(name).jsonPrimitive.float.also { require(it.isFinite()) }
	private fun JsonObject.floats(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.float }.toFloatArray().also { require(it.all(Float::isFinite)) }
}
