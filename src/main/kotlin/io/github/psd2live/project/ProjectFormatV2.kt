package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The v2 layout of a `.psd2live` archive, and its conversion to and from the working store.
 *
 * The working store (and a v1 archive) keeps one self-contained JSON snapshot per history revision. v2 splits
 * each snapshot into content-addressed document nodes by kind - source art, layer state, settings, rig
 * definitions, the authoring journal, generator overrides and motion clips - so revisions share every part
 * they did not change and each part carries its own schema version:
 *
 * | path | content |
 * | --- | --- |
 * | `history/HEAD.json`, `history/nodes/` | the current node, node order and immutable node metadata (as v1) |
 * | `history/revisions/<key>.json` | per revision: the hash of each document node it is made of |
 * | `document/nodes/<kind>/<sha256>.json` | document nodes, `{schema, kind, value}` |
 * | `document/nodes/payload/<sha256>.json` | large journal entries a schema-2 revision lists under `payloads` |
 * | `document/overrides/<sha256>.json` | generator overrides with their place in the journal |
 * | `document/clips/<sha256>.json` | motion clips and generated-motion settings |
 * | `assets/` | deduplicated rasters (PNG) |
 * | `auxiliary/` | staged assets, views, workflow records and tasks |
 *
 * A node's file name is the SHA-256 of its bytes. Splitting is lossless: unpacking rebuilds each snapshot with
 * the same content, so revision ids and history survive a v1 → v2 migration. The rig model is never stored;
 * opening rebuilds it from the document as before.
 *
 * A node holding a field that schema-1 readers would silently drop has schema 2, and a revision whose journal
 * names payload nodes has schema 2; every other node and revision stays schema 1, byte for byte, so documents
 * without the new fields still open in builds that read only schema 1, and builds that do reject the rest.
 */
internal object ProjectFormatV2 {
	const val VERSION = 2
	/** Schema of document nodes v1 writers produced; still written for every node without newer fields. */
	const val NODE_SCHEMA = 1
	/** Schema of a node holding texture fields (layer `rect`, `textureOverrides`, the `atlas` setting). */
	const val NODE_SCHEMA_TEXTURES = 2
	/** A node with a layer moved as a whole ([LayerTransform], `transform`), which schema-2 readers would drop. */
	const val NODE_SCHEMA_TRANSFORMS = 3
	/** Schema of a revision index whose journal names no payload node. */
	const val REVISION_SCHEMA = 1
	/** Schema of a revision index whose journal names payload nodes (listed under `payloads`). */
	const val REVISION_SCHEMA_PAYLOADS = 2
	/**
	 * A revision whose journal holds `rig_checkpoint` records: it lists the rig objects they name under `rig`, which
	 * the archive holds in `rig/objects/`. Readers of schema 1-2 cannot build it and must refuse it.
	 */
	const val REVISION_SCHEMA_RIG = 3
	/** Where an archive keeps rig objects ([io.github.psd2live.core.RigObjects]); the working store keeps them in [WORKING_RIG_OBJECTS]. */
	const val RIG_OBJECTS = "rig/objects"
	const val WORKING_RIG_OBJECTS = "rig-objects"
	/** Kind and folder of content-addressed payload nodes for large journal entries. */
	const val PAYLOAD = "payload"
	/** Key of a journal entry that stands for a payload node: `{"$payload": "<sha256>"}`. */
	const val PAYLOAD_REF = "\$payload"
	/**
	 * Saved journal entries at least this long (in characters) become payload nodes, in revisions that already
	 * need schema 2: the reference is under 80 characters, so each revision's journal node stays small.
	 */
	const val PAYLOAD_MIN_CHARS = 512

	private val json = Json { ignoreUnknownKeys = true }

	private val sourceKeys = listOf("canvasWidth", "canvasHeight", "groups", "layers")
	private val layerKeys = listOf("layerVisibility", "deletedLayerIds", "layerOverrides", "parentOverrides", "meshOverrides", "textureOverrides")
	private val sourceParts = mapOf("generationSource" to "generation-source", "meshSource" to "mesh-source", "placementSource" to "placement-source")
	private val clipKeys = listOf("motions", "motionPresets")
	private const val JOURNAL = "authoringJournal"
	private const val OVERRIDE = "generated_override"
	private val auxiliaryFolders = listOf("assets", "views", "view-images", "workflow")

	/** Moves the working-store layout under [root]/workspace/[projectId] into the v2 layout under [root]. */
	fun pack(root: Path, projectId: String) {
		val workspace = root.resolve("workspace")
		val store = workspace.resolve(projectId)
		require(Files.isRegularFile(store.resolve("HEAD.json"))) { "Project has no history" }
		val history = root.resolve("history")
		move(store.resolve("HEAD.json"), history.resolve("HEAD.json"))
		moveTree(store.resolve("history/nodes"), history.resolve("nodes"))
		val snapshots = store.resolve("history/snapshots")
		val shared = WorkspaceStore.SharedContent()
		val payloads = PayloadHashes()
		if (Files.isDirectory(snapshots)) Files.list(snapshots).use { paths -> paths.sorted().toList() }.forEach { file ->
			val snapshot = WorkspaceStore.expandSnapshot(store, json.parseToJsonElement(Files.readString(file)).jsonObject, shared)
			// Revisions shared chunks, so their entries are the same objects: each payload is written and hashed once.
			val index = split(root, snapshot, PAYLOAD_MIN_CHARS.takeIf { needsNewerSchema(snapshot) }, payloads)
			write(history.resolve("revisions").resolve(file.fileName.toString()), index.toString().encodeToByteArray())
			Files.delete(file)
		}
		// Journal chunks and payloads shared by working snapshots are now inside the document nodes.
		WorkspaceStore.sharedContentFolders.forEach { deleteTree(store.resolve(it)) }
		moveTree(store.resolve("blobs"), root.resolve("assets"))
		moveTree(store.resolve(WORKING_RIG_OBJECTS), root.resolve(RIG_OBJECTS))
		for (folder in auxiliaryFolders) moveTree(store.resolve(folder), root.resolve("auxiliary").resolve(folder))
		if (Files.isRegularFile(store.resolve("tasks.json"))) move(store.resolve("tasks.json"), root.resolve("auxiliary/tasks.json"))
		val left = Files.walk(store).use { paths -> paths.filter(Files::isRegularFile).map { store.relativize(it).toString() }.toList() }
		require(left.isEmpty()) { "Unknown project store entries: $left" }
		deleteTree(workspace)
	}

	/** Rebuilds the working-store layout under [root]/workspace/[projectId] from an extracted v2 archive. */
	fun unpack(root: Path, projectId: String) {
		val history = root.resolve("history")
		require(Files.isRegularFile(history.resolve("HEAD.json"))) { "Project has no history" }
		val store = root.resolve("workspace").resolve(projectId)
		require(!Files.exists(store)) { "Project store entries in a v2 archive" }
		val revisions = history.resolve("revisions")
		val cache = HashMap<String, JsonObject>()
		val written = HashSet<String>()
		if (Files.isDirectory(revisions)) Files.list(revisions).use { paths -> paths.sorted().toList() }.forEach { file ->
			val snapshot = join(root, json.parseToJsonElement(Files.readString(file)).jsonObject, cache)
			// Written as the working store writes a commit: revisions share journal chunks, so opening reads each
			// entry once and every revision holds the same entry objects instead of its own copy of the journal.
			val stored = WorkspaceStore.shareContent(snapshot) { relative, bytes -> if (written.add(relative)) write(store.resolve(relative), bytes()) }
			write(store.resolve("history/snapshots").resolve(file.fileName.toString()), stored.toString().encodeToByteArray())
		}
		move(history.resolve("HEAD.json"), store.resolve("HEAD.json"))
		moveTree(history.resolve("nodes"), store.resolve("history/nodes"))
		moveTree(root.resolve("assets"), store.resolve("blobs"))
		for (folder in auxiliaryFolders) moveTree(root.resolve("auxiliary").resolve(folder), store.resolve(folder))
		if (Files.isRegularFile(root.resolve("auxiliary/tasks.json"))) move(root.resolve("auxiliary/tasks.json"), store.resolve("tasks.json"))
		deleteTree(history); deleteTree(root.resolve("document")); deleteTree(root.resolve("auxiliary"))
	}

	/**
	 * Writes [snapshot]'s parts as content-addressed nodes under [root] and returns the revision index naming them.
	 *
	 * With [payloadMinChars], authored journal entries whose text is at least that long are stored once each as
	 * `payload` nodes and the journal node names them (`{"$PAYLOAD_REF": sha}`); such a revision has schema 2.
	 * [payloadHashes] remembers the payloads already written, by entry identity, across the revisions of a save.
	 */
	internal fun split(root: Path, snapshot: JsonObject, payloadMinChars: Int? = null, payloadHashes: PayloadHashes = PayloadHashes()): JsonObject {
		val nodes = LinkedHashMap<String, String>()
		var overrides: String? = null
		var clips: String? = null
		val payloads = LinkedHashSet<String>()
		for ((kind, value) in plainParts(snapshot)) nodes[kind] = node(root, kind, value)
		snapshot["rigEdits"]?.jsonObject?.let { rig ->
			nodes["rig"] = node(root, "rig", JsonObject(mapOf("rigEdits" to JsonObject(rig.filterKeys { it != JOURNAL && it !in clipKeys }))))
			rig[JOURNAL]?.jsonArray?.let { journal ->
				val (generated, authored) = journal.withIndex().partition { (_, command) ->
					(command as? JsonObject)?.get("op")?.jsonPrimitive?.contentOrNull == OVERRIDE
				}
				val entries = authored.map { (_, command) ->
					if (payloadMinChars == null || command !is JsonObject || JournalEntryDigests.of(command).length < payloadMinChars) command
					else buildJsonObject { put(PAYLOAD_REF, payloadHashes.getOrPut(command) { payload(root, command) }.also(payloads::add)) }
				}
				nodes["journal"] = node(root, "journal", buildJsonObject { put("entries", JsonArray(entries)) })
				if (generated.isNotEmpty()) overrides = content(root, "document/overrides", buildJsonObject {
					put("schema", NODE_SCHEMA); put("kind", "overrides")
					putJsonArray("entries") { generated.forEach { (index, command) -> addJsonObject { put("index", index); put("command", command) } } }
				})
			}
			JsonObject(rig.filterKeys { it in clipKeys }).takeIf { it.isNotEmpty() }?.let { value ->
				clips = content(root, "document/clips", buildJsonObject { put("schema", NODE_SCHEMA); put("kind", "clips"); put("value", value) })
			}
		}
		val known = sourceKeys + layerKeys + "settings" + sourceParts.keys + "rigEdits"
		nodes["document"] = node(root, "document", JsonObject(snapshot.filterKeys { it !in known }))
		val rigObjects = io.github.psd2live.core.RigCheckpoint.hashes(snapshot["rigEdits"]?.jsonObject?.get(JOURNAL)?.jsonArray.orEmpty()
			.mapNotNull { it as? JsonObject }).distinct()
		return buildJsonObject {
			put("schema", when { rigObjects.isNotEmpty() -> REVISION_SCHEMA_RIG; payloads.isEmpty() -> REVISION_SCHEMA; else -> REVISION_SCHEMA_PAYLOADS })
			put("nodes", JsonObject(nodes.mapValues { JsonPrimitive(it.value) }))
			overrides?.let { put("overrides", it) }
			clips?.let { put("clips", it) }
			if (payloads.isNotEmpty()) putJsonArray("payloads") { payloads.forEach { add(JsonPrimitive(it)) } }
			if (rigObjects.isNotEmpty()) putJsonArray("rig") { rigObjects.forEach { add(JsonPrimitive(it)) } }
		}
	}

	/** The source, layer, settings and extra source nodes of [snapshot], in index order, before they are written. */
	private fun plainParts(snapshot: JsonObject): List<Pair<String, JsonObject>> = buildList {
		fun subset(keys: List<String>) = JsonObject(snapshot.filterKeys { it in keys })
		subset(sourceKeys).takeIf { it.isNotEmpty() }?.let { add("source" to it) }
		subset(layerKeys).takeIf { it.isNotEmpty() }?.let { add("layers" to it) }
		subset(listOf("settings")).takeIf { it.isNotEmpty() }?.let { add("settings" to it) }
		for ((key, kind) in sourceParts) snapshot[key]?.let { add(kind to JsonObject(mapOf(key to it))) }
	}

	/**
	 * Whether [snapshot] already has a schema-2 node. Only such a revision names payloads when saved: readers that
	 * can open it read payloads too, and a document without newer fields keeps its schema-1 nodes and index.
	 */
	internal fun needsNewerSchema(snapshot: JsonObject): Boolean = plainParts(snapshot).any { (kind, value) -> schemaOf(kind, value) != NODE_SCHEMA }

	/** Payload node hashes by journal entry identity. */
	internal class PayloadHashes {
		private val hashes = java.util.IdentityHashMap<JsonObject, String>()
		fun getOrPut(entry: JsonObject, write: () -> String): String = hashes.getOrPut(entry, write)
	}

	/** Stores [value] once as a content-addressed `payload` node and returns its hash. */
	internal fun payload(root: Path, value: JsonElement): String =
		content(root, "document/nodes/$PAYLOAD", buildJsonObject { put("schema", NODE_SCHEMA); put("kind", PAYLOAD); put("value", value) })

	/** The value of the `payload` node [hash], verified against its hash. */
	internal fun readPayload(root: Path, hash: String): JsonElement {
		val node = read(root, "document/nodes/$PAYLOAD", hash)
		require(node["schema"]?.jsonPrimitive?.intOrNull == NODE_SCHEMA && node["kind"]?.jsonPrimitive?.contentOrNull == PAYLOAD) {
			"Unsupported document node: $PAYLOAD $hash"
		}
		return node.getValue("value")
	}

	/**
	 * The schema a node of [kind] needs: 2 when it holds a field schema-1 readers would drop (a layer's float
	 * `rect`, `textureOverrides`, the `atlas` setting), so those builds reject it instead of losing data.
	 */
	private fun schemaOf(kind: String, value: JsonObject): Int {
		fun layersHaveRect(source: JsonObject?) = source?.get("layers")?.jsonArray.orEmpty().any { (it as? JsonObject)?.containsKey("rect") == true }
		fun layersHaveTransform(source: JsonObject?) = source?.get("layers")?.jsonArray.orEmpty().any { (it as? JsonObject)?.containsKey("transform") == true }
		val transformed = when (kind) {
			"source" -> layersHaveTransform(value)
			in sourceParts.values -> value.values.any { layersHaveTransform(it as? JsonObject) }
			else -> false
		}
		if (transformed) return NODE_SCHEMA_TRANSFORMS
		val newer = when (kind) {
			"source" -> layersHaveRect(value)
			in sourceParts.values -> value.values.any { layersHaveRect(it as? JsonObject) }
			"layers" -> "textureOverrides" in value
			"settings" -> (value["settings"] as? JsonObject)?.let { it.containsKey(WorkspaceSettingsCodec.ATLAS) || it.containsKey(AtlasArrangementCodec.KEY) } == true
			else -> false
		}
		return if (newer) NODE_SCHEMA_TEXTURES else NODE_SCHEMA
	}

	/** The snapshot a revision [index] names, read from the nodes under [root]. */
	internal fun join(root: Path, index: JsonObject, cache: MutableMap<String, JsonObject> = HashMap()): JsonObject {
		val schema = index["schema"]?.jsonPrimitive?.intOrNull
		require(schema in REVISION_SCHEMA..REVISION_SCHEMA_RIG) { "Unsupported revision schema" }
		val nodes = index.getValue("nodes").jsonObject.mapValues { it.value.jsonPrimitive.content }
		val unknown = nodes.keys - (listOf("source", "layers", "settings", "rig", "journal", "document") + sourceParts.values).toSet()
		require(unknown.isEmpty()) { "Unsupported document nodes: $unknown" }
		val payloads = index["payloads"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
		require(if (schema == REVISION_SCHEMA_RIG) true else (schema == REVISION_SCHEMA_PAYLOADS) == payloads.isNotEmpty()) {
			"Payloads need revision schema $REVISION_SCHEMA_PAYLOADS"
		}
		val rigObjects = index["rig"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
		require((schema == REVISION_SCHEMA_RIG) == rigObjects.isNotEmpty()) { "Rig objects need revision schema $REVISION_SCHEMA_RIG" }
		for (hash in rigObjects) {
			require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid rig object name" }
			require(Files.isRegularFile(root.resolve(RIG_OBJECTS).resolve("$hash.bin"))) { "The archive lacks rig object $hash" }
		}
		fun load(folder: String, kind: String, hash: String): JsonObject {
			val value = cache.getOrPut("$folder/$hash") { read(root, folder, hash) }
			require(value["schema"]?.jsonPrimitive?.intOrNull in NODE_SCHEMA..NODE_SCHEMA_TRANSFORMS && value["kind"]?.jsonPrimitive?.contentOrNull == kind) {
				"Unsupported document node: $kind $hash"
			}
			return value
		}
		fun resolve(command: JsonElement): JsonElement {
			val reference = (command as? JsonObject)?.takeIf { it.size == 1 }?.get(PAYLOAD_REF) ?: return command
			val hash = reference.jsonPrimitive.content
			require(hash in payloads) { "Journal names a payload its revision does not list: $hash" }
			return cache.getOrPut("document/nodes/$PAYLOAD/$hash") { read(root, "document/nodes/$PAYLOAD", hash) }.let { node ->
				require(node["schema"]?.jsonPrimitive?.intOrNull == NODE_SCHEMA && node["kind"]?.jsonPrimitive?.contentOrNull == PAYLOAD) {
					"Unsupported document node: $PAYLOAD $hash"
				}
				node.getValue("value")
			}
		}
		fun value(kind: String) = nodes[kind]?.let { load("document/nodes/$kind", kind, it).getValue("value").jsonObject }
		val snapshot = LinkedHashMap<String, JsonElement>()
		value("document")?.let(snapshot::putAll)
		for (kind in listOf("settings", "source")) value(kind)?.let(snapshot::putAll)
		for (kind in sourceParts.values) value(kind)?.let(snapshot::putAll)
		value("layers")?.let(snapshot::putAll)
		val rig = value("rig")?.getValue("rigEdits")?.jsonObject
		if (rig != null) {
			val edits = LinkedHashMap<String, JsonElement>(rig)
			val authored = value("journal")?.getValue("entries")?.jsonArray?.let { entries ->
				if (payloads.isEmpty()) entries else JsonArray(entries.map(::resolve))
			}
			val generated = index["overrides"]?.jsonPrimitive?.content?.let { load("document/overrides", "overrides", it).getValue("entries").jsonArray }
			if (authored != null || generated != null) {
				val placed = generated.orEmpty().associate { it.jsonObject.getValue("index").jsonPrimitive.int to it.jsonObject.getValue("command") }
				val size = authored.orEmpty().size + generated.orEmpty().size
				require(placed.size == generated.orEmpty().size && placed.keys.all { it in 0 until size }) { "Invalid override positions" }
				val remaining = authored.orEmpty().iterator()
				edits[JOURNAL] = JsonArray(List(size) { i -> placed[i] ?: remaining.next() })
			}
			index["clips"]?.jsonPrimitive?.content?.let { edits.putAll(load("document/clips", "clips", it).getValue("value").jsonObject) }
			snapshot["rigEdits"] = JsonObject(edits)
		} else require(nodes["journal"] == null && index["overrides"] == null && index["clips"] == null) { "Journal without rig definitions" }
		return JsonObject(snapshot)
	}

	private fun node(root: Path, kind: String, value: JsonObject): String =
		content(root, "document/nodes/$kind", buildJsonObject { put("schema", schemaOf(kind, value)); put("kind", kind); put("value", value) })

	/** Writes [value] under [folder] named by the SHA-256 of its bytes, once. */
	private fun content(root: Path, folder: String, value: JsonObject): String {
		val bytes = value.toString().encodeToByteArray()
		val hash = sha256(bytes)
		val path = root.resolve(folder).resolve("$hash.json")
		if (!Files.exists(path)) write(path, bytes)
		return hash
	}

	private fun read(root: Path, folder: String, hash: String): JsonObject {
		require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid document node reference" }
		val path = root.resolve(folder).resolve("$hash.json")
		require(Files.isRegularFile(path)) { "Document node is missing: $folder/$hash" }
		val bytes = Files.readAllBytes(path)
		require(sha256(bytes) == hash) { "Document node checksum mismatch: $folder/$hash" }
		return json.parseToJsonElement(bytes.decodeToString()).jsonObject
	}

	/** The format version of the archive at [file], or null when it is not a readable project archive. */
	fun versionOf(file: Path): Int? = runCatching {
		java.util.zip.ZipFile(file.toFile()).use { zip ->
			val entry = zip.getEntry("manifest.json") ?: return null
			val manifest = zip.getInputStream(entry).use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
			manifest["version"]?.jsonPrimitive?.intOrNull.takeIf { manifest["format"]?.jsonPrimitive?.contentOrNull == "PSD2Live" }
		}
	}.getOrNull()

	/**
	 * Before a v2 save replaces the v1 project at [target], keeps a copy of it beside it as `<name>.v1.psd2live`,
	 * once: the migration never destroys the only v1 file. Returns the backup made, if any.
	 */
	fun backupV1(target: Path): Path? {
		if (!Files.isRegularFile(target) || versionOf(target) != 1) return null
		val name = target.fileName.toString()
		val stem = if (name.endsWith(".psd2live", ignoreCase = true)) name.dropLast(".psd2live".length) else name
		val backup = target.resolveSibling("$stem.v1.psd2live")
		if (Files.exists(backup)) return null
		Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES)
		return backup
	}

	private fun write(path: Path, bytes: ByteArray) {
		Files.createDirectories(path.parent)
		Files.write(path, bytes)
	}

	private fun move(from: Path, to: Path) {
		Files.createDirectories(to.parent)
		Files.move(from, to)
	}

	private fun moveTree(from: Path, to: Path) {
		if (!Files.isDirectory(from)) return
		Files.walk(from).use { paths -> paths.filter(Files::isRegularFile).toList() }.forEach { file -> move(file, to.resolve(from.relativize(file).toString())) }
		deleteTree(from)
	}

	private fun deleteTree(path: Path) {
		if (!Files.exists(path)) return
		Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
	}

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
