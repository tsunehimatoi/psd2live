package io.github.psd2live.core

import io.github.psd2live.format.compile.document.ContentHash
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerBounds
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ParameterId

/**
 * Version 2 of the `art_primitive` journal record ([ArtPrimitiveJournal]): a materialized split whose parts the base
 * generation builds like any layer, instead of a snapshot of the superseded drawable's replayed state.
 *
 * Record: `{op: "art_primitive", v: 2, origin, texture_source_id, supersedes, supersedes_layers, replace, masks?,
 * depth?, primitives: [...], glues: {replaced, appended}}` - the record-level fields mean what they mean in version 1.
 * A primitive splits into what the base build reads ([decodePrimitive], [ResolvedPart]) and [AUTHORED], the user's own
 * data on top of the generated part: material and part fields only where they differ from the generated value, user
 * parameters, keyform cells on the user-axis plane plus [FROZEN_AXES], blends, paths and vertex groups. Glues hold user
 * glues only; skeleton welds come from the base.
 *
 * Division of work around this interface:
 * - base generation builds from [resolve]: superseded layers and drawables as stubs in their slots, every part on its
 *   recorded mesh, and hands the parts, their paths, generated axes and the stubs over in [PrimitiveSkins]
 *   ([BuiltRig.resolvedPuppet] removes the stubs and splices the parts in);
 * - a split of a generated original writes a version 2 record that only declares the parts (an empty [AUTHORED]
 *   layer) and a `rig_checkpoint` right after it: the split is a regeneration merged onto the user's rig
 *   ([RigRegenerationCheckpoint.split]), so the record itself is never replayed;
 * - records with an authored layer come from earlier builds, which replayed them: they still replay where no checkpoint
 *   follows them ([ArtPrimitiveJournal.replay] places each part from [PrimitiveSkins] with its authored layer; a missing
 *   part is an error), and replay tolerates entries before them that address only their stubs.
 *
 * Documents without version 2 records resolve to [ResolvedLayers.Empty] and build exactly as before.
 */
object ArtPrimitiveV2 {
	const val VERSION_V2 = 2

	// Record level.
	const val OP = ArtPrimitiveJournal.OP
	const val FIELD_OP = "op"
	const val FIELD_VERSION = "v"
	const val ORIGIN = "origin"
	const val ORIGIN_SPLIT = "split"
	const val ORIGIN_DEPTH = "depth"
	const val TEXTURE_SOURCE_ID = "texture_source_id"
	const val SUPERSEDES = "supersedes"
	const val SUPERSEDES_LAYERS = "supersedes_layers"
	const val REPLACE = "replace"
	const val MASKS = "masks"
	const val DEPTH = "depth"
	const val PRIMITIVES = "primitives"
	const val GLUES = "glues"
	const val GLUES_REPLACED = "replaced"
	const val GLUES_APPENDED = "appended"

	// Primitive, read by the base build.
	const val ID = "id"
	const val LAYER_ID = "layer_id"
	const val SOURCE_ID = "source_id"
	const val SOURCE_BOUNDS = "source_bounds"
	const val NEUTRAL_BOUNDS = "neutral_bounds"
	const val NAME = "name"
	const val CLASSIFICATION = "classification"
	const val CLASS_TYPE = "type"
	const val CLASS_TAG = "tag"
	const val CLASS_SIDE = "side"
	const val CLASS_PARAMETER = "parameter"
	const val CLASS_SWITCH = "switch"
	const val PARENT = "parent"
	const val POSITIONS = "positions"
	const val TRIANGLES = "triangles"
	const val CANVAS_UVS = "canvas_uvs"
	/** The pinned mesh: `{canvas_positions, triangles, canvas_uvs}`, positions in canvas units at rest (default pose). */
	const val MESH = "mesh"
	const val CANVAS_POSITIONS = "canvas_positions"
	const val FIXED_TOPOLOGY = "fixed_topology"
	const val FROZEN_AXES = "frozen_axes"

	// Primitive, the authored layer.
	const val AUTHORED = "authored"
	const val PART = "part"
	const val BLEND = "blend"
	const val OPACITY = "opacity"
	const val ORDER = "order"
	const val VISIBLE = "visible"
	const val SELECTABLE = "selectable"
	const val MULTIPLY = "multiply"
	const val SCREEN = "screen"
	const val INVERT_MASK = "invert_mask"
	const val ALPHA_BLEND = "alpha_blend"
	const val CULLING = "culling"
	const val USER_DATA = "user_data"
	const val PARAMETERS = "parameters"
	const val GEOMETRY = "geometry"
	const val CHANNELS = "channels"
	const val BLENDS = "blends"
	const val PATHS = "paths"
	const val VERTEX_GROUPS = "vertex_groups"
	/**
	 * Geometry the user added on top of the generated part on the user-axis plane (every generator axis at its
	 * default): a sparse keyform grid holding only plane cells (`{axes: [{id, keys}], cells: [[coordinate, deltas]]}`,
	 * the version 1 `geometry` encoding), deltas per recorded vertex in the part's generated parent space; null when
	 * there is none. A cell with a generator axis away from its default is a `generated_override` entry after the
	 * record instead.
	 */
	const val GEOMETRY_RESIDUAL = "geometry_residual"
	/**
	 * Channel values the user added on top of the generated part, per channel a grid over every axis (the version 1
	 * `channels` encoding); replay adds them cell by cell to the generated channels.
	 */
	const val CHANNELS_RESIDUAL = "channels_residual"

	/** Whether [command] is an `art_primitive` record of version 2. */
	fun isV2(command: JsonObject): Boolean =
		ArtPrimitiveJournal.isRecord(command) && command[FIELD_VERSION]?.jsonPrimitive?.intOrNull == VERSION_V2

	/**
	 * The layer set the base build resolves from [overlay]'s version 2 records, in journal order. Throws
	 * [IllegalArgumentException] when a v2 record is malformed. [ResolvedLayers.Empty] when there are none.
	 */
	fun resolve(overlay: RigEditOverlay): ResolvedLayers {
		val parts = ArrayList<ResolvedPart>()
		val stubLayers = LinkedHashSet<String>()
		val stubDrawables = LinkedHashSet<DrawableId>()
		for ((index, command) in overlay.authoringJournal.withIndex()) {
			if (!isV2(command)) continue
			val primitives = requireArray(command, PRIMITIVES).map { requireObject(it, PRIMITIVES) }
			require(primitives.isNotEmpty()) { "Art primitive record has no primitives" }
			requireArray(command, SUPERSEDES).forEach { stubDrawables += DrawableId(requireText(it, SUPERSEDES)) }
			requireArray(command, SUPERSEDES_LAYERS).forEach { stubLayers += requireText(it, SUPERSEDES_LAYERS) }
			requireText(command[TEXTURE_SOURCE_ID], TEXTURE_SOURCE_ID)
			val ids = primitives.map { decodePrimitive(it, index).also(parts::add).drawableId }
			require(ids.distinct().size == ids.size) { "Art primitive IDs must be unique" }
		}
		if (parts.isEmpty()) return ResolvedLayers.Empty
		return ResolvedLayers(parts, stubLayers, stubDrawables)
	}

	/**
	 * One primitive of a version 2 record at journal index [recordIndex], parsed and validated. Throws
	 * [IllegalArgumentException] naming the first invalid field. The [AUTHORED] layer must be an object; its
	 * contents are validated where the record replays.
	 */
	fun decodePrimitive(primitive: JsonObject, recordIndex: Int): ResolvedPart {
		require(recordIndex >= 0) { "Invalid art primitive record index" }
		val id = requireText(primitive[ID], ID)
		val layer = requireText(primitive[LAYER_ID], LAYER_ID)
		val sourceId = requireText(primitive[SOURCE_ID], SOURCE_ID)
		val name = primitive[NAME]?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
			?: throw IllegalArgumentException("Art primitive $NAME is missing")
		floats(primitive, SOURCE_BOUNDS)
		val sourceBounds = RasterMeshCreation.sourceBounds(primitive)
		val neutral = floats(primitive, NEUTRAL_BOUNDS)
		require(neutral.size == 4 && neutral[2] >= neutral[0] && neutral[3] >= neutral[1]) { "Invalid art primitive $NEUTRAL_BOUNDS" }
		val parent = when (val value = primitive[PARENT]) {
			null, JsonNull -> null
			else -> DeformerId(requireText(value, PARENT))
		}
		// Revision 2 records pin the mesh in canvas units under `mesh`; the flat form holds parent-space positions.
		val mesh = when (val value = primitive[MESH]) {
			null -> null
			is JsonObject -> value
			else -> throw IllegalArgumentException("Invalid art primitive $MESH")
		}
		val geometry = mesh ?: primitive
		val positions = floats(geometry, if (mesh != null) CANVAS_POSITIONS else POSITIONS)
		val canvasUvs = floats(geometry, CANVAS_UVS)
		val triangles = requireArray(geometry, TRIANGLES).map {
			(it as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("Invalid art primitive $TRIANGLES")
		}.toIntArray()
		RasterMeshJournal.validateMesh(DrawableMesh(positions, canvasUvs, triangles))
		val fixedTopology = (primitive[FIXED_TOPOLOGY] as? JsonPrimitive)?.booleanOrNull
			?: throw IllegalArgumentException("Art primitive $FIXED_TOPOLOGY is missing")
		val frozenAxes = requireArray(primitive, FROZEN_AXES).mapTo(LinkedHashSet()) { ParameterId(requireText(it, FROZEN_AXES)) }
		require(primitive[AUTHORED] is JsonObject) { "Art primitive $AUTHORED is missing" }
		return ResolvedPart(recordIndex, DrawableId(id), layer, sourceId, name, parent, positions, triangles, canvasUvs,
			decodeClassification(primitive[CLASSIFICATION]), frozenAxes, fixedTopology, sourceBounds,
			Bounds(neutral[0], neutral[1], neutral[2], neutral[3]), canvasPositions = mesh != null)
	}

	/** The `classification` object of a primitive: `{type, tag, side, parameter, switch}`, enum names as stored in layer overrides. */
	fun decodeClassification(value: JsonElement?): LayerClassificationOverride {
		val data = value as? JsonObject ?: throw IllegalArgumentException("Art primitive $CLASSIFICATION is missing")
		fun <T> named(field: String, parse: (String) -> T): T = try {
			parse(requireText(data[field], "$CLASSIFICATION.$field"))
		} catch (_: IllegalStateException) {
			throw IllegalArgumentException("Invalid art primitive $CLASSIFICATION.$field")
		}
		val type = named(CLASS_TYPE) { name -> LayerType.entries.firstOrNull { it.name == name } ?: error(name) }
		val tag = named(CLASS_TAG) { name -> SemanticTag.entries.firstOrNull { it.name == name } ?: error(name) }
		val side = named(CLASS_SIDE) { name -> Side.entries.firstOrNull { it.name == name } ?: error(name) }
		val parameter = (data[CLASS_PARAMETER] as? JsonPrimitive)?.takeIf { it.isString }?.content
			?: throw IllegalArgumentException("Invalid art primitive $CLASSIFICATION.$CLASS_PARAMETER")
		val switch = (data[CLASS_SWITCH] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
			?: throw IllegalArgumentException("Invalid art primitive $CLASSIFICATION.$CLASS_SWITCH")
		return LayerClassificationOverride(type, tag, side, parameter, switch)
	}

	/** [classification] as a primitive's `classification` object; [decodeClassification] reads it back. */
	fun encodeClassification(classification: LayerClassificationOverride): JsonObject = buildJsonObject {
		put(CLASS_TYPE, classification.type.name); put(CLASS_TAG, classification.tag.name); put(CLASS_SIDE, classification.side.name)
		put(CLASS_PARAMETER, classification.parameter); put(CLASS_SWITCH, classification.switchId)
	}

	private fun requireArray(data: JsonObject, field: String): JsonArray =
		data[field] as? JsonArray ?: throw IllegalArgumentException("Art primitive $field is missing")

	private fun requireObject(value: JsonElement, field: String): JsonObject =
		value as? JsonObject ?: throw IllegalArgumentException("Invalid art primitive $field")

	private fun requireText(value: JsonElement?, field: String): String =
		(value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
			?: throw IllegalArgumentException("Invalid art primitive $field")

	private fun floats(data: JsonObject, field: String): FloatArray = requireArray(data, field).map {
		(it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.floatOrNull?.takeIf(Float::isFinite)
			?: throw IllegalArgumentException("Invalid art primitive $field")
	}.toFloatArray()
}

/**
 * Journal entries before a version 2 split that address only drawables the split supersedes. The base builds those
 * as stubs - meshed from frozen pixels, outside every aggregate stage - so such an entry may no longer apply as it
 * did. Replay then treats it as a no-op and notes it ([GeneratedOverrides.Outcome.notes]); any entry that touches
 * a non-stub, or fails anywhere else, still fails the replay.
 */
/**
 * A journal entry before a version 2 split that failed only on drawables the split supersedes, so replay skipped it
 * ([StubTolerance]): its [index] in the journal, its [op], the superseded [targets] it names and the failure [detail].
 */
data class SupersededEntryNote(val index: Int, val op: String, val targets: List<String>, val detail: String) {
	fun describe(): String = "Skipped $op: it addresses only ${targets.joinToString()}, superseded by a later split ($detail)"
}

/** The state a journal replay checkpoints: the model and the entries skipped so far. */
internal class ReplayState(val model: org.umamo.runtime.model.PuppetModel, val notes: List<SupersededEntryNote>)

internal object StubTolerance {
	/** Per entry (by identity) before at least one v2 record: every drawable those later records supersede. */
	fun of(journal: List<JsonObject>): java.util.IdentityHashMap<JsonObject, Set<String>> {
		val result = java.util.IdentityHashMap<JsonObject, Set<String>>()
		var later = emptySet<String>()
		for (index in journal.indices.reversed()) {
			val entry = journal[index]
			if (later.isNotEmpty()) result[entry] = later
			if (ArtPrimitiveV2.isV2(entry)) later = later + ((entry[ArtPrimitiveV2.SUPERSEDES] as? JsonArray)
				?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
		}
		return result
	}

	/** The drawables and deformers of [model] and the [stubs] that [entry] names, alone or as `kind:id` (a Glue as `glue:a:b`). */
	fun targets(model: org.umamo.runtime.model.PuppetModel, entry: JsonObject, stubs: Set<String>): Set<String> {
		val known = HashSet<String>(stubs)
		model.drawables.forEach { known += it.id.raw }; model.deformers.forEach { known += it.id.raw }
		val found = LinkedHashSet<String>()
		fun visit(value: JsonElement) {
			when (value) {
				is JsonObject -> value.values.forEach(::visit)
				is JsonArray -> value.forEach(::visit)
				is JsonPrimitive -> if (value.isString) {
					val text = value.content
					for (candidate in listOf(text) + text.split(':').drop(1)) if (candidate in known) found += candidate
				}
				else -> Unit
			}
		}
		visit(entry)
		return found
	}

	/** Whether every drawable or deformer [entry] names is one of [stubs] (and it names at least one). */
	fun onlyStubs(model: org.umamo.runtime.model.PuppetModel, entry: JsonObject, stubs: Set<String>): Boolean {
		val targets = targets(model, entry, stubs)
		return targets.isNotEmpty() && targets.all { it in stubs }
	}
}

/**
 * One part of a version 2 `art_primitive` record, as the base build reads it. Arrays compare and hash by content.
 */
class ResolvedPart(
	/** Index of the record in [RigEditOverlay.authoringJournal]. */
	val recordIndex: Int,
	/** The part's drawable id (`id`), new to the document. */
	val drawableId: DrawableId,
	/** The source layer whose pixels the part shows (`layer_id`); its raster is frozen into the generation source. */
	val layerId: String,
	/** The source the part's texture comes from (`source_id`). */
	val sourceId: String,
	/** The drawable's name (`name`). */
	val name: String,
	/**
	 * The recorded parent deformer (`parent`). Null: with [canvasPositions] the generated parent (no journal edit
	 * had reparented the original), else the root.
	 */
	val parent: DeformerId?,
	/**
	 * Vertex positions, x/y pairs; the base never re-meshes a part. With [canvasPositions] canvas units at rest
	 * (`mesh.canvas_positions`, normalised into the generated parent frame by the base), else parent-space
	 * positions (`positions`, the flat form).
	 */
	val positions: FloatArray,
	/** Triangle vertex indices (`triangles`). */
	val triangles: IntArray,
	/** Canvas-unit texture coordinates per vertex (`canvas_uvs`), converted through the current atlas on build. */
	val canvasUvs: FloatArray,
	/** Classification the record captured (`classification`); the layer's override, when set, takes precedence. */
	val classification: LayerClassificationOverride,
	/** Generator axes kept as authored because they could not be mapped onto the generated part (`frozen_axes`). */
	val frozenAxes: Set<ParameterId>,
	/** Whether the skeleton must keep the recorded topology (`fixed_topology`): the part carries per-vertex user data. */
	val fixedTopology: Boolean,
	/** The canvas rectangle the texture must cover (`source_bounds`). */
	val sourceBounds: LayerBounds,
	/** The part's neutral extent (`neutral_bounds`). */
	val neutralBounds: Bounds,
	/** Whether [positions] are canvas units from the record's `mesh` object (revision 2 records). */
	val canvasPositions: Boolean = false,
) {
	/** Canonical text of every field; [ResolvedLayers.contentKey] hashes it. */
	internal fun canonical(): String = listOf(recordIndex, drawableId.raw, layerId, sourceId, name, parent?.raw,
		positions.contentToString(), triangles.contentToString(), canvasUvs.contentToString(), classification,
		frozenAxes.map { it.raw }.sorted(), fixedTopology, sourceBounds, neutralBounds, canvasPositions).joinToString("|")

	override fun equals(other: Any?): Boolean = this === other || other is ResolvedPart &&
		recordIndex == other.recordIndex && drawableId == other.drawableId && layerId == other.layerId && sourceId == other.sourceId &&
		name == other.name && parent == other.parent && positions.contentEquals(other.positions) &&
		triangles.contentEquals(other.triangles) && canvasUvs.contentEquals(other.canvasUvs) &&
		classification == other.classification && frozenAxes == other.frozenAxes && fixedTopology == other.fixedTopology &&
		sourceBounds == other.sourceBounds && neutralBounds == other.neutralBounds && canvasPositions == other.canvasPositions

	override fun hashCode(): Int {
		var result = recordIndex
		for (value in listOf(drawableId, layerId, sourceId, name, parent, classification, frozenAxes, fixedTopology, sourceBounds, neutralBounds, canvasPositions))
			result = 31 * result + value.hashCode()
		result = 31 * result + positions.contentHashCode()
		result = 31 * result + triangles.contentHashCode()
		return 31 * result + canvasUvs.contentHashCode()
	}

	override fun toString(): String = "ResolvedPart(${drawableId.raw}, layer=$layerId, record=$recordIndex)"
}

/**
 * The version 2 layer set of a document ([ArtPrimitiveV2.resolve]). The base build generates from the generation
 * layers minus [stubLayers] plus [parts]; [stubLayers] and [stubDrawables] are still built in their slots, flagged
 * and kept out of every aggregate stage. A part a later v2 record supersedes is in [parts] and also a stub.
 */
data class ResolvedLayers(
	/** Every primitive of every v2 record, in journal order. */
	val parts: List<ResolvedPart>,
	/** Layers v2 records supersede (`supersedes_layers`). */
	val stubLayers: Set<String>,
	/** Drawables v2 records supersede (`supersedes`). */
	val stubDrawables: Set<DrawableId>,
) {
	/** A deterministic content hash of [parts], [stubLayers] and [stubDrawables], for build cache keys. */
	val contentKey: String by lazy {
		ContentHash.of("resolved-layers", parts.size, *parts.map(ResolvedPart::canonical).toTypedArray(),
			stubLayers.sorted(), stubDrawables.map { it.raw }.sorted())
	}

	fun isEmpty(): Boolean = parts.isEmpty() && stubLayers.isEmpty() && stubDrawables.isEmpty()

	companion object {
		val Empty = ResolvedLayers(emptyList(), emptySet(), emptySet())
	}
}
