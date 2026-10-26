package io.github.psd2live.project

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import org.umamo.runtime.model.Parameter

/**
 * The document's Layout state: what the material library holds, which source layers come from which material, the
 * user's own layer tags, and where generation stands. Every part is empty in a document written before Layout, which
 * then reads and hashes exactly as it did ([isEmpty]).
 */
data class WorkspaceLayout(
	/** The user's own tags per source layer: words outside the generator vocabulary ([io.github.psd2live.core.LayerTags]). */
	val layerTags: Map<String, Set<String>> = emptyMap(),
	/** The material each placed source layer shows, by layer ID. */
	val layerMaterials: Map<String, String> = emptyMap(),
	val generation: WorkspaceGeneration = WorkspaceGeneration.Generated,
	val materials: MaterialLibrary = MaterialLibrary.Empty,
	/** The images laid over or under the canvas to place layers against, in drawing order; never layers, so never generated or exported. */
	val references: List<LayoutReference> = emptyList(),
	/** What every vector drawing shares: colour tokens, class rules and definitions ([DrawingKit]). */
	val drawingKit: DrawingKit? = null,
	/**
	 * Source layers kept as reference, by layer ID: they stay in the stack and on the canvas, but the generators never
	 * read them, so the rig and every export leave them out. Only layers the generator has not read become reference.
	 */
	val referenceLayerIds: Set<String> = emptySet(),
	/**
	 * Reference layers pinned to a keyform, by layer ID: parameter ID to value. A pinned layer shows only where every
	 * pinned parameter sits at its value ([ReferencePins]); an unpinned one shows at every pose.
	 */
	val referencePins: Map<String, List<PinPoint>> = emptyMap(),
) {
	val isEmpty: Boolean get() = this == Empty

	/** The first reference image: the one the reference queries and renders compare against. */
	val reference: LayoutReference? get() = references.firstOrNull()

	fun reference(id: String): LayoutReference? = references.firstOrNull { it.id == id }

	/** [layerTags] and [layerMaterials] without layers no longer in [layerIds]. */
	fun retaining(layerIds: Set<String>): WorkspaceLayout = copy(
		layerTags = layerTags.filterKeys { it in layerIds },
		layerMaterials = layerMaterials.filterKeys { it in layerIds },
		generation = generation.retaining(layerIds),
		referenceLayerIds = referenceLayerIds.filterTo(LinkedHashSet()) { it in layerIds },
		referencePins = referencePins.filterKeys { it in layerIds },
	)

	companion object {
		val Empty = WorkspaceLayout()
	}
}

/**
 * Where generation stands. A document is generated unless it was created empty for Layout and never generated
 * ([generated] false): its rig then is only the placement preview. After generation, layers placed since
 * ([pendingLayerIds]) and classification changes ([pendingClassifications]) wait for the next generation; the rig keeps
 * reading the classification the last one used ([WorkspaceDocument.layerOverrides]). [refreshLayerIds] are layers whose
 * image was replaced since, which the next generation may mesh again from their new pixels.
 */
data class WorkspaceGeneration(
	val generated: Boolean = true,
	val pendingLayerIds: Set<String> = emptySet(),
	val pendingClassifications: Map<String, LayerClassificationOverride> = emptyMap(),
	val refreshLayerIds: Set<String> = emptySet(),
) {
	/** Nothing waits for the next generation. */
	val settled: Boolean get() = generated && pendingLayerIds.isEmpty() && pendingClassifications.isEmpty()

	fun retaining(layerIds: Set<String>) = copy(
		pendingLayerIds = pendingLayerIds.filterTo(LinkedHashSet()) { it in layerIds },
		pendingClassifications = pendingClassifications.filterKeys { it in layerIds },
		refreshLayerIds = refreshLayerIds.filterTo(LinkedHashSet()) { it in layerIds },
	)

	companion object {
		val Generated = WorkspaceGeneration()
		val NotGenerated = WorkspaceGeneration(generated = false)
	}
}

/** One keyform a reference shows at: parameter ID to value. */
typealias PinPoint = Map<String, Float>

/**
 * Where pinned references show ([WorkspaceLayout.referencePins], [LayoutReference.pins]): at any of their keyform
 * points - a pose whose every parameter of the point lies within [TOLERANCE] of the parameter's range of the point's
 * value. No points shows them at every pose; a parameter the rig no longer has does not hide.
 */
object ReferencePins {
	/** The share of a parameter's range a pose may be off a pinned value and still show the layer. */
	const val TOLERANCE = 0.005f

	/** Whether pose [values] (parameter ID to value, a missing one at its default) of [parameters] sits on [point]. */
	fun at(point: PinPoint, parameters: Map<String, Parameter>, values: (String) -> Float?): Boolean = point.all { (id, value) ->
		val parameter = parameters[id] ?: return@all true
		val at = values(id) ?: parameter.default
		kotlin.math.abs(at - value) <= maxOf(1e-4f, (parameter.max - parameter.min) * TOLERANCE)
	}

	/** Whether a reference pinned to [points] shows at [values] of [parameters]: at one of them, or anywhere without any. */
	fun shows(points: List<PinPoint>?, parameters: Map<String, Parameter>, values: (String) -> Float?): Boolean =
		points.isNullOrEmpty() || points.any { at(it, parameters, values) }

	/** The references of [pins] that do not show at [values] of [parameters]. */
	fun hidden(pins: Map<String, List<PinPoint>>, parameters: List<Parameter>, values: (String) -> Float?): Set<String> {
		if (pins.isEmpty()) return emptySet()
		val byId = parameters.associateBy { it.id.raw }
		return pins.filterTo(LinkedHashMap()) { (_, points) -> !shows(points, byId, values) }.keys
	}

	/** A pin as an archive writes it: one point as the object it always was, several as an array of them. */
	fun encode(points: List<PinPoint>): JsonElement =
		if (points.size == 1) encodePoint(points.single()) else JsonArray(points.map(::encodePoint))

	private fun encodePoint(point: PinPoint) = JsonObject(point.toSortedMap().mapValues { JsonPrimitive(it.value) })

	fun decode(value: JsonElement): List<PinPoint> = when (value) {
		is JsonArray -> value.map { point -> point.jsonObject.mapValues { it.value.jsonPrimitive.float } }
		else -> listOf(value.jsonObject.mapValues { it.value.jsonPrimitive.float }).filter { it.isNotEmpty() }
	}
}

/**
 * A reference image: material [materialId] shown over [rect] on the canvas, turned [rotation] degrees clockwise about
 * its centre, at [opacity], over the model or [under] it, to place and compare layers against. A [locked] one cannot
 * be moved on the canvas. It is view data in the document - saved and undone with it - but no layer: the other kind
 * of reference, a reference layer ([WorkspaceLayout.referenceLayerIds]), sits in the layer stack instead.
 */
data class LayoutReference(
	val materialId: String,
	val rect: LayerCanvasRect,
	val opacity: Float = 0.5f,
	val visible: Boolean = true,
	val id: String = FIRST_ID,
	val rotation: Float = 0f,
	val locked: Boolean = false,
	val under: Boolean = false,
	/** The keyform points it shows at, as a reference layer's [WorkspaceLayout.referencePins]: none shows it at every pose. */
	val pins: List<PinPoint> = emptyList(),
) {
	init {
		require(opacity in 0f..1f) { "Reference opacity is 0..1" }
		require(rotation.isFinite()) { "Reference rotation must be finite" }
	}

	companion object {
		/** The ID of a document's first reference image, which documents with a single one keep. */
		const val FIRST_ID = "reference"
	}
}

/**
 * What the vector drawings of one document share, so their parts read as one painting: [colors] named for
 * `var(--name)` in any drawing, [styles] class rules (`.line { stroke: var(--line) }`) and [defs] (gradients, filters)
 * every drawing can refer to. A drawing's own rules and definitions of the same name take precedence.
 */
data class DrawingKit(val colors: Map<String, String> = emptyMap(), val styles: String = "", val defs: String = "") {
	init {
		colors.forEach { (name, value) ->
			require(name.matches(NAME)) { "A kit colour name is letters, digits, - and _: $name" }
			require(value.matches(COLOR)) { "Kit colour $name must be #rgb or #rrggbb: $value" }
		}
	}
	val isEmpty: Boolean get() = colors.isEmpty() && styles.isBlank() && defs.isBlank()

	companion object {
		val NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
		val COLOR = Regex("#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})")
	}
}

enum class MaterialKind { BITMAP, VECTOR }

/**
 * A folder of the material library. A null [parentId] puts it at the top. [origin] names the file it was read from
 * (a PSD or CMO3, whose layers it holds); empty for a folder made in the library or read from a directory.
 */
data class MaterialFolder(val id: String, val name: String, val parentId: String?, val tags: Set<String> = emptySet(), val origin: String = "")

/**
 * One material: a raster, or a vector drawing with the raster it is rendered to at its texture resolution
 * ([raster]'s size). [placement] is the canvas rectangle its source gave it (a PSD layer's position), used as its
 * default placement, on a [placementWidth] x [placementHeight] canvas (the PSD's; 0 when unknown); [origin] names
 * where it was imported from.
 */
data class MaterialItem(
	val id: String,
	val name: String,
	val folderId: String?,
	val kind: MaterialKind,
	val raster: LayerRaster,
	val placement: LayerCanvasRect? = null,
	val svg: String? = null,
	val tags: Set<String> = emptySet(),
	val origin: String = "",
	val opacity: Float = 1f,
	val visible: Boolean = true,
	val placementWidth: Int = 0,
	val placementHeight: Int = 0,
	/** Placed as a reference layer unless the placement says otherwise ([WorkspaceLayout.referenceLayerIds]). */
	val reference: Boolean = false,
) {
	init {
		require(kind != MaterialKind.VECTOR || svg != null) { "A vector material keeps its drawing" }
		require(raster.width > 0 && raster.height > 0) { "A material raster cannot be empty" }
	}
}

/** The material library: folders and items, each list in display order. */
data class MaterialLibrary(val folders: List<MaterialFolder> = emptyList(), val items: List<MaterialItem> = emptyList()) {
	private val folderById by lazy { folders.associateBy { it.id } }
	private val itemById by lazy { items.associateBy { it.id } }

	fun folder(id: String): MaterialFolder? = folderById[id]
	fun item(id: String): MaterialItem? = itemById[id]

	/** [folderId] and the folders above it, nearest first. */
	fun ancestry(folderId: String?): List<MaterialFolder> = generateSequence(folderId?.let(::folder)) { it.parentId?.let(::folder) }.toList()

	/** [folderId] and every folder below it. */
	fun subtree(folderId: String): Set<String> {
		val children = folders.groupBy { it.parentId }
		val out = LinkedHashSet<String>()
		fun visit(id: String) { if (out.add(id)) children[id]?.forEach { visit(it.id) } }
		visit(folderId)
		return out
	}

	/** The folder path of [folderId] as names, top first. */
	fun path(folderId: String?): List<String> = ancestry(folderId).reversed().map { it.name }

	companion object {
		val Empty = MaterialLibrary()
	}
}

/**
 * JSON form of [WorkspaceLayout], under two top-level snapshot keys: [LAYOUT] (tags, material links, generation)
 * and [MATERIALS] (the library). Each is written only when not empty. Rasters are written as references
 * ([rasterRef]) and read back through [rasterOf].
 */
internal object WorkspaceLayoutCodec {
	private fun encodeReference(reference: LayoutReference) = buildJsonObject {
		put("materialId", reference.materialId)
		putJsonArray("rect") { reference.rect.let { listOf(it.left, it.top, it.width, it.height) }.forEach { add(JsonPrimitive(it)) } }
		put("opacity", reference.opacity)
		if (!reference.visible) put("visible", false)
		// Written only when not the default, so a document from before these fields reads and writes as it was.
		if (reference.id != LayoutReference.FIRST_ID) put("id", reference.id)
		if (reference.rotation != 0f) put("rotation", reference.rotation)
		if (reference.locked) put("locked", true)
		if (reference.under) put("under", true)
		if (reference.pins.isNotEmpty()) put("pins", ReferencePins.encode(reference.pins))
	}

	private fun decodeReference(value: JsonObject): LayoutReference {
		val rect = value.getValue("rect").jsonArray.map { it.jsonPrimitive.float }
		return LayoutReference(value.getValue("materialId").jsonPrimitive.content, LayerCanvasRect(rect[0], rect[1], rect[2], rect[3]),
			value["opacity"]?.jsonPrimitive?.float ?: 0.5f, value["visible"]?.jsonPrimitive?.boolean ?: true,
			value["id"]?.jsonPrimitive?.content ?: LayoutReference.FIRST_ID, value["rotation"]?.jsonPrimitive?.float ?: 0f,
			value["locked"]?.jsonPrimitive?.boolean ?: false, value["under"]?.jsonPrimitive?.boolean ?: false,
			value["pins"]?.let(ReferencePins::decode).orEmpty())
	}

	const val LAYOUT = "layout"
	const val MATERIALS = "materials"

	fun encodeLayout(layout: WorkspaceLayout): JsonObject? {
		if (layout.layerTags.isEmpty() && layout.layerMaterials.isEmpty() && layout.generation == WorkspaceGeneration.Generated && layout.references.isEmpty() && layout.drawingKit == null &&
			layout.referenceLayerIds.isEmpty() && layout.referencePins.isEmpty()) return null
		return buildJsonObject {
			if (layout.layerTags.isNotEmpty()) putJsonObject("layerTags") {
				layout.layerTags.toSortedMap().forEach { (id, tags) -> putJsonArray(id) { tags.sorted().forEach { add(JsonPrimitive(it)) } } }
			}
			if (layout.layerMaterials.isNotEmpty()) putJsonObject("layerMaterials") {
				layout.layerMaterials.toSortedMap().forEach { (id, material) -> put(id, material) }
			}
			val generation = layout.generation
			if (generation != WorkspaceGeneration.Generated) putJsonObject("generation") {
				put("generated", generation.generated)
				if (generation.pendingLayerIds.isNotEmpty()) putJsonArray("pendingLayerIds") { generation.pendingLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
				if (generation.pendingClassifications.isNotEmpty()) putJsonObject("pendingClassifications") {
					generation.pendingClassifications.toSortedMap().forEach { (id, value) -> put(id, encodeClassification(value)) }
				}
				if (generation.refreshLayerIds.isNotEmpty()) putJsonArray("refreshLayerIds") { generation.refreshLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
			}
			// One reference image keeps the single object documents had before there could be several.
			layout.references.singleOrNull()?.let { put("reference", encodeReference(it)) }
			if (layout.references.size > 1) putJsonArray("references") { layout.references.forEach { add(encodeReference(it)) } }
			layout.drawingKit?.let { kit -> putJsonObject("drawingKit") {
				if (kit.colors.isNotEmpty()) putJsonObject("colors") { kit.colors.toSortedMap().forEach { (name, value) -> put(name, value) } }
				if (kit.styles.isNotEmpty()) put("styles", kit.styles)
				if (kit.defs.isNotEmpty()) put("defs", kit.defs)
			} }
			if (layout.referenceLayerIds.isNotEmpty()) putJsonArray("referenceLayerIds") { layout.referenceLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
			if (layout.referencePins.isNotEmpty()) putJsonObject("referencePins") {
				layout.referencePins.toSortedMap().forEach { (id, points) -> put(id, ReferencePins.encode(points)) }
			}
		}
	}

	fun encodeMaterials(library: MaterialLibrary, rasterRef: (LayerRaster) -> String): JsonObject? {
		if (library.folders.isEmpty() && library.items.isEmpty()) return null
		return buildJsonObject {
			putJsonArray("folders") {
				library.folders.forEach { folder -> addJsonObject {
					put("id", folder.id); put("name", folder.name)
					folder.parentId?.let { put("parentId", it) }
					if (folder.tags.isNotEmpty()) putJsonArray("tags") { folder.tags.sorted().forEach { add(JsonPrimitive(it)) } }
					if (folder.origin.isNotEmpty()) put("origin", folder.origin)
				} }
			}
			putJsonArray("items") {
				library.items.forEach { item -> addJsonObject {
					put("id", item.id); put("name", item.name)
					item.folderId?.let { put("folderId", it) }
					put("kind", item.kind.name)
					put("rasterWidth", item.raster.width); put("rasterHeight", item.raster.height)
					put("rgbaBlob", rasterRef(item.raster))
					item.placement?.let { rect -> putJsonArray("placement") { listOf(rect.left, rect.top, rect.width, rect.height).forEach { add(JsonPrimitive(it)) } } }
					if (item.placementWidth > 0 && item.placementHeight > 0) putJsonArray("placementCanvas") { add(JsonPrimitive(item.placementWidth)); add(JsonPrimitive(item.placementHeight)) }
					item.svg?.let { put("svg", it) }
					if (item.tags.isNotEmpty()) putJsonArray("tags") { item.tags.sorted().forEach { add(JsonPrimitive(it)) } }
					if (item.origin.isNotEmpty()) put("origin", item.origin)
					if (item.opacity != 1f) put("opacity", item.opacity)
					if (!item.visible) put("visible", false)
					if (item.reference) put("reference", true)
				} }
			}
		}
	}

	fun decode(layout: JsonObject?, materials: JsonObject?, rasterOf: (hash: String, width: Int, height: Int) -> ByteArray): WorkspaceLayout {
		if (layout == null && materials == null) return WorkspaceLayout.Empty
		fun strings(value: JsonElement?) = value?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }.orEmpty()
		val generation = layout?.get("generation")?.jsonObject?.let { value ->
			WorkspaceGeneration(
				generated = value.getValue("generated").jsonPrimitive.boolean,
				pendingLayerIds = strings(value["pendingLayerIds"]),
				pendingClassifications = value["pendingClassifications"]?.jsonObject?.mapValues { decodeClassification(it.value.jsonObject) }.orEmpty(),
				refreshLayerIds = strings(value["refreshLayerIds"]),
			)
		} ?: WorkspaceGeneration.Generated
		val library = materials?.let { value ->
			MaterialLibrary(
				folders = value["folders"]?.jsonArray.orEmpty().map { element ->
					val folder = element.jsonObject
					MaterialFolder(folder.getValue("id").jsonPrimitive.content, folder.getValue("name").jsonPrimitive.content,
						folder["parentId"]?.jsonPrimitive?.contentOrNull, strings(folder["tags"]), folder["origin"]?.jsonPrimitive?.content.orEmpty())
				},
				items = value["items"]?.jsonArray.orEmpty().map { element ->
					val item = element.jsonObject
					val width = item.getValue("rasterWidth").jsonPrimitive.int
					val height = item.getValue("rasterHeight").jsonPrimitive.int
					MaterialItem(
						id = item.getValue("id").jsonPrimitive.content,
						name = item.getValue("name").jsonPrimitive.content,
						folderId = item["folderId"]?.jsonPrimitive?.contentOrNull,
						kind = MaterialKind.valueOf(item.getValue("kind").jsonPrimitive.content),
						raster = LayerRaster(width, height, rasterOf(item.getValue("rgbaBlob").jsonPrimitive.content, width, height)),
						placement = item["placement"]?.jsonArray?.map { it.jsonPrimitive.float }?.let { LayerCanvasRect(it[0], it[1], it[2], it[3]) },
						svg = item["svg"]?.jsonPrimitive?.contentOrNull,
						tags = strings(item["tags"]),
						origin = item["origin"]?.jsonPrimitive?.contentOrNull.orEmpty(),
						opacity = item["opacity"]?.jsonPrimitive?.float ?: 1f,
						visible = item["visible"]?.jsonPrimitive?.boolean ?: true,
						placementWidth = item["placementCanvas"]?.jsonArray?.get(0)?.jsonPrimitive?.int ?: 0,
						placementHeight = item["placementCanvas"]?.jsonArray?.get(1)?.jsonPrimitive?.int ?: 0,
						reference = item["reference"]?.jsonPrimitive?.boolean ?: false,
					)
				},
			)
		} ?: MaterialLibrary.Empty
		return WorkspaceLayout(
			layerTags = layout?.get("layerTags")?.jsonObject?.mapValues { strings(it.value) }.orEmpty(),
			layerMaterials = layout?.get("layerMaterials")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
			generation = generation,
			materials = library,
			references = layout?.get("references")?.jsonArray?.map { decodeReference(it.jsonObject) }
				?: listOfNotNull(layout?.get("reference")?.jsonObject?.let(::decodeReference)),
			drawingKit = layout?.get("drawingKit")?.jsonObject?.let { value ->
				DrawingKit(value["colors"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
					value["styles"]?.jsonPrimitive?.content.orEmpty(), value["defs"]?.jsonPrimitive?.content.orEmpty())
			},
			referenceLayerIds = strings(layout?.get("referenceLayerIds")),
			referencePins = layout?.get("referencePins")?.jsonObject?.mapValues { ReferencePins.decode(it.value) }?.filterValues { it.isNotEmpty() }.orEmpty(),
		)
	}

	/** The rasters [materials] names, as `(hash, width, height)`, for loading them ahead of decoding. */
	fun rasterRefs(materials: JsonObject?): List<Triple<String, Int, Int>> = materials?.get("items")?.jsonArray.orEmpty().mapNotNull { element ->
		val item = element as? JsonObject ?: return@mapNotNull null
		val hash = item["rgbaBlob"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
		Triple(hash, item["rasterWidth"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null, item["rasterHeight"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null)
	}

	fun encodeClassification(value: LayerClassificationOverride) = buildJsonObject {
		put("type", value.type.name); put("tag", value.tag.name); put("side", value.side.name)
		put("parameter", value.parameter); put("switchId", value.switchId)
		value.role?.let { put("part", it.name) }
	}

	fun decodeClassification(value: JsonObject) = LayerClassificationOverride(
		LayerType.valueOf(value.getValue("type").jsonPrimitive.content), SemanticTag.valueOf(value.getValue("tag").jsonPrimitive.content),
		Side.valueOf(value.getValue("side").jsonPrimitive.content), value.getValue("parameter").jsonPrimitive.content,
		value.getValue("switchId").jsonPrimitive.int,
		value["part"]?.jsonPrimitive?.contentOrNull?.let { name -> PartRole.entries.firstOrNull { it.name == name } })

	/** The text a revision identity hashes for [layout]: its two JSON parts, with rasters as their digests. Empty for an empty layout. */
	fun canonical(layout: WorkspaceLayout): String = buildString {
		encodeLayout(layout)?.let { append("|layout:").append(it) }
		encodeMaterials(layout.materials) { WorkspaceRevisions.rasterDigest(it.rgba) }?.let { append("|materials:").append(it) }
	}
}
