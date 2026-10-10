package io.github.psd2live.project

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigKeyformChannelsEdit
import io.github.psd2live.core.RigKeyformCopyEdit
import io.github.psd2live.core.RigKeyformDeleteEdit
import io.github.psd2live.core.RigKeyformGeometryEdit
import io.github.psd2live.core.RigKeyformSetEdit
import io.github.psd2live.core.RigParameterEdit
import io.github.psd2live.core.RigTargetKind
import io.github.psd2live.core.RigTargetRef
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.history.WorkspaceHistoryNode
import io.github.psd2live.history.WorkspaceHistorySelection
import io.github.psd2live.history.WorkspaceHistoryState
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ParameterKind
import java.io.ByteArrayOutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Disk repository for Agent workspace state. History node, snapshot, raster and staged-asset files
 * are immutable/content-addressed. Only HEAD and the task checkpoint document are atomically replaced.
 */
internal class WorkspaceStore(
	internal val root: Path = defaultRoot(),
	/**
	 * A store whose raster files this one takes instead of encoding the pixels again: a save stages the live
	 * working store's PNG for every raster it already holds, linked (or copied with its attributes).
	 */
	private val rasterSource: WorkspaceStore? = null,
) : WorkspaceAssetRepository {
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
	/**
	 * Content-addressed files (snapshots, nodes, journal chunks, payloads, rasters) this store wrote or found. They
	 * never change or go away, so a commit stats only the files new to it instead of every node of the history.
	 */
	private val stored = HashSet<Path>()

	private fun isStored(path: Path): Boolean = path in stored || Files.isRegularFile(path).also { if (it) stored.add(path) }

    /**
     * Copies [projectId]'s auxiliary data to [target]. [viewImages] includes the PNG of every view the agent tools
     * rendered: nothing reads them back, and a long session left hundreds in each saved project, so saving leaves
     * them out. The views' spatial references stay, since assets placed from a view name it.
     */
    @Synchronized
    internal fun copyAuxiliary(projectId: String, target: Path, catalog: WorkspaceAssetCatalog? = null, targetProjectId: String = projectId,
                               viewImages: Boolean = true) {
        val source = projectRoot(projectId)
        for (folder in listOf("assets", "views", "view-images", "workflow")) {
            if (folder == "view-images" && !viewImages) continue
            val directory = source.resolve(folder)
            if (!Files.isDirectory(directory)) continue
            Files.walk(directory).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                if (folder == "assets" && catalog != null && readJson(file).requiredString("id") !in catalog.assets) return@forEach
                if (folder == "workflow" && catalog != null && readJson(file).requiredString("id") !in catalog.workflow) return@forEach
                val destination = target.resolve(source.relativize(file))
                Files.createDirectories(destination.parent)
                if (folder == "workflow" && targetProjectId != projectId) {
                    ProjectArchive.writeJson(destination, JsonObject(readJson(file) + ("project_id" to JsonPrimitive(targetProjectId))))
                } else ProjectArchive.copyKeepingTime(file, destination, StandardCopyOption.REPLACE_EXISTING)
            } }
        }
        // Staged assets' rasters as PNG, including assets not yet used by a layer: the stored PNG when there is
        // one, else (a legacy .rgba.gz blob) encoded from the pixels.
        val assets = source.resolve("assets")
        if (Files.isDirectory(assets)) Files.list(assets).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
            val metadata = readJson(file)
            val id = metadata.requiredString("id")
            if (catalog != null && id !in catalog.assets) return@forEach
            val name = rasterFileName(metadata.requiredString("rgbaBlob"), metadata.requiredInt("pixelWidth"), metadata.requiredInt("pixelHeight"))
            if (linkRaster(source.resolve("blobs").resolve(name), target.resolve("blobs").resolve(name))) return@forEach
            val asset = loadAsset(projectId, id)!!
            persistRaster(target, asset.rgba, asset.public.pixelWidth, asset.public.pixelHeight)
        } }
    }

	@Synchronized
	fun loadHistory(projectId: String): WorkspaceHistoryTree<WorkspaceDocument>? {
		val project = projectRoot(projectId)
		val headFile = project.resolve("HEAD.json")
		val nodesDirectory = project.resolve("history/nodes")
		if (!Files.isRegularFile(headFile) || !Files.isDirectory(nodesDirectory)) return null
		io.github.psd2live.core.RigObjects.addFolder(project.resolve(ProjectFormatV2.WORKING_RIG_OBJECTS))
		val headDocument = readJson(headFile)
        val headId = headDocument.requiredString("headNodeId")
		val nodeFiles = Files.list(nodesDirectory).use { stream ->
			stream.filter(Files::isRegularFile).sorted().toList()
		}
		if (nodeFiles.isEmpty()) return null
		val nodes = nodeFiles.map { nodeFile ->
			val nodeJson = readJson(nodeFile)
			WorkspaceHistoryNode(
				id = nodeJson.requiredString("id"),
				parentId = nodeJson.optionalString("parentId"),
				revisionId = nodeJson.requiredString("revisionId"),
				snapshotHash = nodeJson.requiredString("snapshotHash"),
				summary = nodeJson.requiredString("summary"),
				actor = nodeJson.requiredString("actor"),
				taskId = nodeJson.optionalString("taskId"),
				createdAt = Instant.parse(nodeJson.requiredString("createdAt")),
			)
		}.sortedWith(compareBy<WorkspaceHistoryNode> { it.createdAt }.thenBy { it.id })
		val shared = SharedContent()
		val snapshots = LinkedHashMap<String, JsonObject>()
		for (node in nodes) {
			val snapshotFile = project.resolve("history/snapshots/${fileKey(node.snapshotHash)}.json")
			require(Files.isRegularFile(snapshotFile)) { "History snapshot is missing for ${node.id}" }
			if (node.snapshotHash !in snapshots) snapshots[node.snapshotHash] = expandSnapshot(project, readJson(snapshotFile), shared)
		}
		val rasters = HashMap(loadRasters(project, snapshots.values))
		val documents = snapshots.mapValues { (_, snapshot) -> decodeDocument(snapshot, project, rasters) }
		val selections = nodes.map { node -> WorkspaceHistorySelection(node, documents.getValue(node.snapshotHash)) }
		val order = headDocument["nodeOrder"]?.jsonArray?.map { it.jsonPrimitive.content }
        val ordered = if (order != null) {
            require(order.size == selections.size && order.toSet() == selections.map { it.node.id }.toSet()) { "Invalid history node order" }
            val byId = selections.associateBy { it.node.id }; order.map { byId.getValue(it) }
        } else selections
        return WorkspaceHistoryTree.restore(ordered, headId)
	}

    /** Legacy v1 projects have no index; capture their existing immutable IDs once. */
    @Synchronized
    fun existingAssetCatalog(projectId: String): WorkspaceAssetCatalog {
        fun ids(folder: String): Set<String> {
            val directory = projectRoot(projectId).resolve(folder)
            if (!Files.isDirectory(directory)) return emptySet()
            return Files.list(directory).use { paths -> paths.filter(Files::isRegularFile)
                .map { readJson(it).requiredString("id") }.toList().toSet() }
        }
        return WorkspaceAssetCatalog(ids("assets"), ids("workflow"))
    }

    /**
     * Checks the catalog's references from the stored metadata. Rasters are not decoded here: the archive's
     * manifest has checked every file of an opened project, and [loadAsset] checks the pixels against their digest.
     */
    @Synchronized
    fun validateAssetCatalog(projectId: String, catalog: WorkspaceAssetCatalog) {
        val project = projectRoot(projectId)
        for (id in catalog.assets) {
            val asset = requireNotNull(storedAsset(project, id)) { "Catalog asset is missing: $id" }
            asset.details["reference_id"]?.jsonPrimitive?.content?.let { reference ->
                require(reference in catalog.workflow) { "Asset reference is outside the catalog" }
                require(loadWorkflow(projectId, reference).requiredString("kind") == "reference") { "Asset reference has an invalid kind" }
            }
        }
        for (id in catalog.workflow) {
            val record = loadWorkflow(projectId, id)
            require(record.requiredString("project_id") == projectId) { "Workflow belongs to another project" }
            when (record.requiredString("kind")) {
                "reference" -> require(loadSpatial(projectId, id) != null) { "Reference spatial metadata is missing" }
                "registration" -> {
                    require(record.requiredString("asset_id") in catalog.assets && record.requiredString("reference_id") in catalog.workflow) {
                        "Registration references are outside the catalog"
                    }
                    require(loadWorkflow(projectId, record.requiredString("reference_id")).requiredString("kind") == "reference") {
                        "Registration reference has an invalid kind"
                    }
                }
                else -> throw IllegalArgumentException("Unknown workflow record kind")
            }
        }
    }

    /** Compression and candidate writes occur outside the runtime lock in an owned directory. */
    fun stageAssets(projectId: String, assets: Collection<WorkspacePngAsset>, workflow: Map<String, JsonObject>,
                    spatial: Map<String, WorkspaceViewSpatialMetadata>, checkCancelled: () -> Unit = {}): WorkspaceAssetStage {
        val pending = projectRoot(projectId).resolve(".pending")
        Files.createDirectories(pending)
        val directory = Files.createTempDirectory(pending, "asset-")
        try {
            val staging = WorkspaceStore(directory)
            assets.forEach { checkCancelled(); staging.persistAsset(projectId, it) }
            workflow.forEach { (id, value) -> checkCancelled(); staging.persistWorkflow(projectId, id, value) }
            spatial.forEach { (id, value) -> checkCancelled(); staging.persistSpatial(projectId, id, value) }
            checkCancelled()
            return WorkspaceAssetStage(directory, staging.projectRoot(projectId), this, projectId)
        } catch (failure: Throwable) {
            try { deleteAssetStage(directory, projectId) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Only delete a staging directory owned by this store and project, without following links. */
    internal fun deleteAssetStage(directory: Path, projectId: String) {
        val path = directory.toAbsolutePath().normalize()
        val pending = projectRoot(projectId).resolve(".pending").toAbsolutePath().normalize()
        require(path.parent == pending && path.fileName.toString().startsWith("asset-")) { "Invalid asset staging directory" }
        if (Files.exists(path)) Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    /** Publish immutable files together, or remove only the files created by this attempt. */
    @Synchronized
    internal fun publishAssetStage(stage: WorkspaceAssetStage, projectId: String, checkCancelled: () -> Unit, beforePublished: () -> Unit) {
        val destinationRoot = projectRoot(projectId).toAbsolutePath().normalize()
        val created = mutableListOf<Path>()
        try {
            Files.walk(stage.source).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                checkCancelled()
                val target = destinationRoot.resolve(stage.source.relativize(file)).normalize()
                check(target.startsWith(destinationRoot)) { "Asset candidate escaped its project" }
                Files.createDirectories(target.parent)
                if (Files.exists(target)) {
                    // Raster names address RGBA/dimensions; keep the existing PNG encoder output.
                    val same = target.parent == destinationRoot.resolve("blobs") ||
                        if (target.fileName.toString().endsWith(".json")) readJson(file) == readJson(target) else Files.mismatch(file, target) == -1L
                    require(same) { "Immutable asset content changed" }
                } else {
                    try { Files.move(file, target, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: AtomicMoveNotSupportedException) { Files.move(file, target) }
                    created.add(target)
                }
            } }
            checkCancelled()
            beforePublished()
        } catch (failure: Throwable) {
            stored.removeAll(created.toSet())
            created.asReversed().forEach { file ->
                try { Files.deleteIfExists(file) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

	@Synchronized
	fun persistHistory(projectId: String, state: WorkspaceHistoryState<WorkspaceDocument>) {
		val project = projectRoot(projectId)
		// The rig objects checkpoints name are part of the document: on disk before the snapshot that names them.
		val rigObjects = project.resolve(ProjectFormatV2.WORKING_RIG_OBJECTS)
		// A history removed behind the store's back is written again in full.
		if (!Files.isDirectory(project.resolve("history"))) stored.removeIf { it.startsWith(project) }
		for (selection in state.selections) {
			val nodePath = project.resolve("history/nodes/${fileKey(selection.node.id)}.json")
			// A node is written after its snapshot, so a stored node has its snapshot too.
			if (nodePath in stored) continue
			val snapshotPath = project.resolve("history/snapshots/${fileKey(selection.node.snapshotHash)}.json")
			if (!isStored(snapshotPath)) {
				io.github.psd2live.core.RigObjects.writeTo(rigObjects, io.github.psd2live.core.RigCheckpoint.hashes(selection.snapshot.rigEdits.authoringJournal))
				val snapshotBytes = shareContent(encodeDocument(selection.snapshot, project), project).toString().encodeToByteArray()
				writeAtomic(snapshotPath, snapshotBytes, replace = false, pretty = false)
				stored.add(snapshotPath)
			}
			if (!isStored(nodePath)) writeImmutable(nodePath, encodeNode(selection.node).toString().encodeToByteArray())
			stored.add(nodePath)
		}
		writeAtomic(
			project.resolve("HEAD.json"),
			buildJsonObject {
				put("version", STORE_VERSION)
				put("headNodeId", state.headNodeId)
                putJsonArray("nodeOrder") { state.selections.forEach { add(JsonPrimitive(it.node.id)) } }
			}.toString().encodeToByteArray(),
		)
	}

	@Synchronized
    override fun persistWorkflow(projectId: String, id: String, value: JsonObject) {
        writeImmutable(projectRoot(projectId).resolve("workflow/${fileKey(id)}.json"), value.toString().encodeToByteArray())
    }

    @Synchronized
    override fun loadWorkflow(projectId: String, id: String): JsonObject {
        val path = projectRoot(projectId).resolve("workflow/${fileKey(id)}.json")
        require(Files.isRegularFile(path)) { "Workflow record not found: $id" }
        return readJson(path).also { require(it.requiredString("id") == id) }
    }

    @Synchronized
    override fun registrationsForAsset(projectId: String, assetId: String): List<JsonObject> {
        val directory=projectRoot(projectId).resolve("workflow")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths -> paths.filter(Files::isRegularFile).sorted().map { readJson(it) }
            .filter { it.optionalString("kind")=="registration" && it.optionalString("asset_id")==assetId }.toList() }
    }

    @Synchronized
	override fun persistAsset(projectId: String, asset: WorkspacePngAsset) {
		val project = projectRoot(projectId)
		val blobHash = persistRaster(project, asset.rgba, asset.public.pixelWidth, asset.public.pixelHeight)
		val value = asset.public
		val metadata = buildJsonObject {
			put("version", STORE_VERSION)
			put("id", value.id)
			put("sha256", value.sha256)
            put("details", value.details)
            asset.originalPng?.let { put("originalPng", java.util.Base64.getEncoder().encodeToString(it)) }
			put("pixelWidth", value.pixelWidth)
			put("pixelHeight", value.pixelHeight)
			put("rgbaBlob", blobHash)
			put("coordinateSpace", value.placement.coordinateSpace)
			put("sourceViewId", value.placement.sourceViewId)
			putBounds("canvasRect", value.placement.canvasRect.left, value.placement.canvasRect.top, value.placement.canvasRect.right, value.placement.canvasRect.bottom)
		}
		writeImmutable(project.resolve("assets/${fileKey(value.id)}.json"), metadata.toString().encodeToByteArray())
	}

	@Synchronized
	override fun loadAsset(projectId: String, assetId: String): WorkspacePngAsset? {
		val project = projectRoot(projectId)
		val (objectValue, public) = assetMetadata(project, assetId) ?: return null
		return WorkspacePngAsset(
			public = public,
			rgba = loadRaster(project, objectValue.requiredString("rgbaBlob"), public.pixelWidth, public.pixelHeight),
            originalPng = objectValue.optionalString("originalPng")?.let { java.util.Base64.getDecoder().decode(it) },
		)
	}

	/** [assetId]'s stored metadata and public description, without its raster; null when it is not stored. */
	private fun assetMetadata(project: Path, assetId: String): Pair<JsonObject, WorkspaceImportedPngAsset>? {
		val file = project.resolve("assets/${fileKey(assetId)}.json")
		if (!Files.isRegularFile(file)) return null
		val objectValue = readJson(file)
		require(objectValue.requiredString("id") == assetId) { "Stored asset identity mismatch" }
		val width = objectValue.requiredInt("pixelWidth")
		val height = objectValue.requiredInt("pixelHeight")
		val rect = objectValue.requiredObject("canvasRect")
		val bounds = io.github.psd2live.core.Bounds(rect.requiredFloat("left"), rect.requiredFloat("top"), rect.requiredFloat("right"), rect.requiredFloat("bottom"))
		val placement = WorkspaceCanvasPlacement(
			coordinateSpace = objectValue.requiredString("coordinateSpace"),
			canvasRect = bounds,
			imagePixelWidth = width,
			imagePixelHeight = height,
			canvasUnitsPerPixelX = bounds.width / width,
			canvasUnitsPerPixelY = bounds.height / height,
			sourceViewId = objectValue.requiredString("sourceViewId"),
		)
		return objectValue to WorkspaceImportedPngAsset(assetId, objectValue.requiredString("sha256"), width, height, placement,
			objectValue["details"] as? JsonObject ?: JsonObject(emptyMap()))
	}

	@Synchronized
	fun persistView(projectId: String, view: WorkspaceRenderedView) {
        persistSpatial(projectId, view.viewId, view.spatial)
        val project = projectRoot(projectId)
        val hash = sha256(view.png)
        writeImmutable(project.resolve("view-images/$hash.png"), view.png)
        writeImmutable(project.resolve("view-images/${fileKey(view.viewId)}.json"), buildJsonObject {
            put("viewId", view.viewId); put("image", "$hash.png");put("revisionId",view.revisionId)
            putJsonObject("parameters") { view.appliedParameters.forEach { (id,value)->put(id,value) } }
            putJsonArray("annotatedDeformerIds") { view.annotatedDeformerIds.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("annotatedLayerIds") { view.annotatedLayerIds.forEach { add(JsonPrimitive(it)) } }
            put("pointIndices",view.pointIndices)
        }.toString().encodeToByteArray())
    }

    @Synchronized
    override fun persistSpatial(projectId: String, viewId: String, spatial: WorkspaceViewSpatialMetadata) {
		val project = projectRoot(projectId)
		val metadata = buildJsonObject {
			put("version", STORE_VERSION)
			put("viewId", viewId)
			put("coordinateSpace", spatial.coordinateSpace)
			put("pixelWidth", spatial.pixelWidth)
			put("pixelHeight", spatial.pixelHeight)
			put("canvasWidth", spatial.canvasWidth)
			put("canvasHeight", spatial.canvasHeight)
			putBounds("requestedViewRect", spatial.requestedViewRect.left, spatial.requestedViewRect.top, spatial.requestedViewRect.right, spatial.requestedViewRect.bottom)
			putBounds("viewRect", spatial.viewRect.left, spatial.viewRect.top, spatial.viewRect.right, spatial.viewRect.bottom)
			spatial.focusRect?.let { putBounds("focusRect", it.left, it.top, it.right, it.bottom) }
			putJsonArray("focusLayerIds") { spatial.focusLayerIds.forEach { add(JsonPrimitive(it)) } }
			spatial.objectScale?.let { put("objectScale", it) }
			put("canvasUnitsPerPixelX", spatial.canvasUnitsPerPixelX)
			put("canvasUnitsPerPixelY", spatial.canvasUnitsPerPixelY)
		}
		writeImmutable(project.resolve("views/${fileKey(viewId)}.json"), metadata.toString().encodeToByteArray())
	}

	@Synchronized
	override fun loadSpatial(projectId: String, viewId: String): WorkspaceViewSpatialMetadata? {
		val file = projectRoot(projectId).resolve("views/${fileKey(viewId)}.json")
		if (!Files.isRegularFile(file)) return null
		val metadata = readJson(file)
		require(metadata.requiredString("viewId") == viewId) { "Stored View identity mismatch" }
		fun bounds(name: String): io.github.psd2live.core.Bounds {
			val value = metadata.requiredObject(name)
			return io.github.psd2live.core.Bounds(
				value.requiredFloat("left"),
				value.requiredFloat("top"),
				value.requiredFloat("right"),
				value.requiredFloat("bottom"),
			)
		}
		return WorkspaceViewSpatialMetadata(
			coordinateSpace = metadata.requiredString("coordinateSpace"),
			pixelWidth = metadata.requiredInt("pixelWidth"),
			pixelHeight = metadata.requiredInt("pixelHeight"),
			canvasWidth = metadata.requiredFloat("canvasWidth"),
			canvasHeight = metadata.requiredFloat("canvasHeight"),
			requestedViewRect = bounds("requestedViewRect"),
			viewRect = bounds("viewRect"),
			focusRect = metadata["focusRect"]?.let { bounds("focusRect") },
			focusLayerIds = metadata.optionalArray("focusLayerIds").map { it.jsonPrimitive.content },
			objectScale = metadata["objectScale"]?.jsonPrimitive?.floatOrNull,
			canvasUnitsPerPixelX = metadata.requiredFloat("canvasUnitsPerPixelX"),
			canvasUnitsPerPixelY = metadata.requiredFloat("canvasUnitsPerPixelY"),
		)
	}

	@Synchronized
	fun persistTasks(projectId: String, tasks: List<WorkspaceTaskSnapshot>) {
		val project = projectRoot(projectId)
		writeAtomic(project.resolve("tasks.json"), buildJsonObject {
			put("version", STORE_VERSION)
			putJsonArray("tasks") { tasks.forEach { add(encodeTask(it)) } }
		}.toString().encodeToByteArray())
	}

	@Synchronized
	fun loadTasks(projectId: String): List<WorkspaceTaskSnapshot> {
		val file = projectRoot(projectId).resolve("tasks.json")
		if (!Files.isRegularFile(file)) return emptyList()
		return readJson(file).optionalArray("tasks").map(::decodeTask)
	}

	private fun encodeDocument(document: WorkspaceDocument, project: Path): JsonObject = buildJsonObject {
        put("settings", document.settings)
		put("version", STORE_VERSION)
		encodeSource(document.source, project).forEach { (key, value) -> put(key, value) }
		document.generationSource?.let { put("generationSource", encodeSource(it, project)) }
		document.meshSource?.let { put("meshSource", encodeSource(it, project)) }
		putJsonObject("layerVisibility") { document.layerVisibility.toSortedMap().forEach { (id, visible) -> put(id, visible) } }
		putJsonArray("deletedLayerIds") { document.deletedLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
		putJsonObject("layerOverrides") {
			document.layerOverrides.toSortedMap().forEach { (id, override) ->
				put(id, buildJsonObject {
					put("type", override.type.name)
					put("tag", override.tag.name)
					put("side", override.side.name)
					put("parameter", override.parameter)
					put("switchId", override.switchId)
				})
			}
		}
		putJsonObject("parentOverrides") {
			document.parentOverrides.toSortedMap().forEach { (id, parent) ->
				if (parent == null) put(id, JsonNull) else put(id, parent)
			}
		}
		putJsonObject("meshOverrides") {
			document.meshOverrides.toSortedMap().forEach { (id, s) ->
				put(id, buildJsonObject {
					put("outerMargin", s.outerMargin)
					put("edgeMode", s.edgeMode.name)
					put("edgeWidth", s.edgeWidth)
					put("maxEdgeDistance", s.maxEdgeDistance)
					put("interiorDensity", s.interiorDensity)
					put("fillAlgorithm", s.fillAlgorithm.name)
					put("suppressBoundaryDiagonals", s.suppressBoundaryDiagonals)
					put("fillParameters", io.github.psd2live.project.WorkspaceSettingsCodec.encodeFillParameters(s.fillParameters))
					if (s.wrap != 0f) put("wrap", s.wrap)
				})
			}
		}
		// Written only when a layer has one, so earlier documents keep their stored form.
		TextureOverrideCodec.encodeAll(document.textureOverrides)?.let { put(TEXTURE_OVERRIDES, it) }
		putJsonObject("rigEdits") {
			document.rigEdits.importedCmo3?.let { put("importedCmo3", it) }
			put("importedLayerIds", JsonObject(document.rigEdits.importedLayerIds.mapValues { JsonPrimitive(it.value) }))
			put("skeleton", document.rigEdits.skeleton?.toJson() ?: JsonNull)
            put("assetLayers", JsonObject(document.rigEdits.assetLayers))
            putJsonArray("calibrationLayerIds") { document.rigEdits.calibrationLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("splitBaselineLayerIds") { document.rigEdits.splitBaselineLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
            put("splitDrawableIds", JsonObject(document.rigEdits.splitDrawableIds.mapValues { JsonPrimitive(it.value) }))
            put("structure", JsonArray(document.rigEdits.structureEdits))
            put("authoringJournal", JsonArray(document.rigEdits.authoringJournal))
            putJsonArray("warps") { document.rigEdits.warpEdits.forEach { add(it.toJson()) } }
            putJsonArray("physics") { document.rigEdits.physicsEdits.forEach { add(it.toJson()) } }
            if (document.rigEdits.disabledPhysicsIds.isNotEmpty()) putJsonArray("physicsDisabled") { document.rigEdits.disabledPhysicsIds.sorted().forEach { add(JsonPrimitive(it)) } }
            if (document.rigEdits.physicsOrder.isNotEmpty()) putJsonArray("physicsOrder") { document.rigEdits.physicsOrder.forEach { add(JsonPrimitive(it)) } }
            if (document.rigEdits.physicsFps != io.github.psd2live.core.RigEditOverlay.DEFAULT_PHYSICS_FPS) put("physicsFps", document.rigEdits.physicsFps)
            putJsonArray("swings") { document.rigEdits.swingEdits.forEach { add(it.toJson()) } }
            putJsonArray("motions") { document.rigEdits.motionClips.forEach { add(io.github.psd2live.core.MotionClips.toJson(it)) } }
            if (document.rigEdits.motionPresets.isNotEmpty()) put("motionPresets", io.github.psd2live.core.MotionPresets.toJson(document.rigEdits.motionPresets))
            if (document.rigEdits.simEdits.isNotEmpty()) putJsonArray("simulations") { document.rigEdits.simEdits.forEach { add(it.toJson()) } }
			putJsonArray("parameters") {
				document.rigEdits.parameterEdits.forEach { edit ->
					add(buildJsonObject {
						put("id", edit.id)
						put("name", edit.name)
						put("min", edit.min)
						put("max", edit.max)
						put("default", edit.default)
						put("kind", edit.kind.name)
						put("repeat", edit.repeat)
						put("created", edit.created)
					})
				}
			}
			putJsonArray("deletedParameterIds") {
				document.rigEdits.deletedParameterIds.sorted().forEach { add(JsonPrimitive(it)) }
			}
			putJsonArray("keyformSets") {
				document.rigEdits.keyformSetEdits.forEach { set ->
					add(buildJsonObject {
						putJsonObject("target") {
							put("kind", set.target.kind.name)
							put("id", set.target.id)
							set.target.secondaryId?.let { put("secondaryId", it) }
							set.target.glueId?.let { put("glueId", it) }
						}
						putJsonObject("coordinate") {
							set.coordinate.toSortedMap().forEach { (k, v) -> put(k, v) }
						}
						set.geometry?.let { geo ->
							putJsonObject("geometry") {
								geo.controlPoints?.let { pts -> putJsonArray("controlPoints") { pts.forEach { add(JsonPrimitive(it)) } } }
								geo.originX?.let { put("originX", it) }
								geo.originY?.let { put("originY", it) }
								geo.angle?.let { put("angle", it) }
								geo.scale?.let { put("scale", it) }
								geo.positionDeltas?.let { deltas -> putJsonArray("positionDeltas") { deltas.forEach { add(JsonPrimitive(it)) } } }
							}
						}
						set.channels?.let { ch ->
							putJsonObject("channels") {
								ch.opacity?.let { put("opacity", it) }
								ch.drawOrder?.let { put("drawOrder", it) }
								ch.multiplyColor?.let { c -> putJsonArray("multiplyColor") { c.forEach { add(JsonPrimitive(it)) } } }
								ch.screenColor?.let { c -> putJsonArray("screenColor") { c.forEach { add(JsonPrimitive(it)) } } }
								ch.glueIntensity?.let { put("glueIntensity", it) }
								ch.flipX?.let { put("flipX", it) }
								ch.flipY?.let { put("flipY", it) }
							}
						}
					})
				}
			}
			putJsonArray("keyformDeletes") {
				document.rigEdits.keyformDeleteEdits.forEach { del ->
					add(buildJsonObject {
						putJsonObject("target") {
							put("kind", del.target.kind.name)
							put("id", del.target.id)
							del.target.secondaryId?.let { put("secondaryId", it) }
							del.target.glueId?.let { put("glueId", it) }
						}
						put("parameterId", del.parameterId)
						del.keyValue?.let { put("keyValue", it) }
						del.channel?.let { put("channel", it) }
					})
				}
			}
			putJsonArray("keyformCopies") {
				document.rigEdits.keyformCopyEdits.forEach { copy ->
					add(buildJsonObject {
						putJsonObject("sourceTarget") {
							put("kind", copy.sourceTarget.kind.name)
							put("id", copy.sourceTarget.id)
							copy.sourceTarget.secondaryId?.let { put("secondaryId", it) }
							copy.sourceTarget.glueId?.let { put("glueId", it) }
						}
						putJsonObject("sourceCoordinate") {
							copy.sourceCoordinate.toSortedMap().forEach { (k, v) -> put(k, v) }
						}
						putJsonObject("destinationTarget") {
							put("kind", copy.destinationTarget.kind.name)
							put("id", copy.destinationTarget.id)
							copy.destinationTarget.secondaryId?.let { put("secondaryId", it) }
							copy.destinationTarget.glueId?.let { put("glueId", it) }
						}
						putJsonObject("destinationCoordinate") {
							copy.destinationCoordinate.toSortedMap().forEach { (k, v) -> put(k, v) }
						}
						copy.channels?.let { chList ->
							putJsonArray("channels") { chList.forEach { add(JsonPrimitive(it)) } }
						}
					})
				}
			}
		}
	}

	private fun decodeDocument(
		value: JsonObject,
		project: Path,
		rasterCache: MutableMap<String, ByteArray> = mutableMapOf(),
	): WorkspaceDocument {
		val source = decodeSource(value, project, rasterCache)
		val overrides = value.optionalObject("layerOverrides").mapValues { (_, element) ->
			val override = element.jsonObject
			LayerClassificationOverride(
				type = enumValue(override.requiredString("type")),
				tag = enumValue(override.requiredString("tag")),
				side = enumValue(override.requiredString("side")),
				parameter = override.requiredString("parameter"),
				switchId = override.requiredInt("switchId"),
			)
		}
		val rigEditObject = value.optionalObject("rigEdits")
		val rigEdits = RigEditOverlay(
			importedCmo3 = rigEditObject["importedCmo3"]?.jsonPrimitive?.contentOrNull,
			importedLayerIds = rigEditObject.optionalObject("importedLayerIds").mapValues { it.value.jsonPrimitive.content },
			// Missing legacy fields disable automatic skeleton generation. Explicit null preserves
			// a newly saved document's automatic-generation policy and its revision identity.
			skeleton = when (val skeleton = rigEditObject["skeleton"]) {
				null -> SkeletonSpec.Disabled
				JsonNull -> null
				else -> SkeletonSpec.fromJson(skeleton.jsonObject)
			},
            assetLayers = rigEditObject.optionalObject("assetLayers").mapValues { it.value.jsonObject },
            calibrationLayerIds = rigEditObject.optionalArray("calibrationLayerIds").map { it.jsonPrimitive.content }.toSet(),
            splitBaselineLayerIds = rigEditObject.optionalArray("splitBaselineLayerIds").map { it.jsonPrimitive.content }.toSet(),
            splitDrawableIds = rigEditObject.optionalObject("splitDrawableIds").mapValues { it.value.jsonPrimitive.content },
            structureEdits = rigEditObject.optionalArray("structure").map { it.jsonObject },
            authoringJournal = rigEditObject.optionalArray("authoringJournal").map { it.jsonObject },
            warpEdits = rigEditObject.optionalArray("warps").map { io.github.psd2live.core.RigWarpEdit.fromJson(it.jsonObject) },
            physicsEdits = rigEditObject.optionalArray("physics").map { io.github.psd2live.core.RigPhysicsEdit.fromJson(it.jsonObject) },
            disabledPhysicsIds = rigEditObject.optionalArray("physicsDisabled").map { it.jsonPrimitive.content }.toSet(),
            physicsOrder = rigEditObject.optionalArray("physicsOrder").map { it.jsonPrimitive.content },
            physicsFps = rigEditObject["physicsFps"]?.jsonPrimitive?.intOrNull ?: io.github.psd2live.core.RigEditOverlay.DEFAULT_PHYSICS_FPS,
            swingEdits = rigEditObject.optionalArray("swings").map { io.github.psd2live.core.RigSwingEdit.fromJson(it.jsonObject) },
            motionClips = rigEditObject.optionalArray("motions").map { io.github.psd2live.core.MotionClips.fromJson(it.jsonObject) },
            motionPresets = io.github.psd2live.core.MotionPresets.fromJson(rigEditObject["motionPresets"]?.jsonObject),
            simEdits = rigEditObject.optionalArray("simulations").map { io.github.psd2live.core.sim.RigSimEdit.fromJson(it.jsonObject) },
			parameterEdits = rigEditObject.optionalArray("parameters").map { element ->
				val edit = element.jsonObject
				RigParameterEdit(
					id = edit.requiredString("id"),
					name = edit.requiredString("name"),
					min = edit.requiredFloat("min"),
					max = edit.requiredFloat("max"),
					default = edit.requiredFloat("default"),
					kind = enumValue<ParameterKind>(edit.requiredString("kind")),
					repeat = edit.requiredBoolean("repeat"),
					created = edit.requiredBoolean("created"),
				)
			},
			deletedParameterIds = rigEditObject.optionalArray("deletedParameterIds").map { it.jsonPrimitive.content }.toSet(),
			keyformSetEdits = rigEditObject.optionalArray("keyformSets").map { element ->
				val obj = element.jsonObject
				val targetObj = obj.requiredObject("target")
				val coordObj = obj.requiredObject("coordinate")
				val geoObj = obj.optionalObject("geometry")
				val chObj = obj.optionalObject("channels")
				RigKeyformSetEdit(
					target = RigTargetRef(
						kind = enumValue(targetObj.requiredString("kind")),
						id = targetObj.requiredString("id"),
						secondaryId = targetObj.optionalString("secondaryId"),
						glueId = targetObj.optionalString("glueId"),
					),
					coordinate = coordObj.mapValues { it.value.jsonPrimitive.floatOrNull ?: 0f },
					geometry = if (geoObj.isEmpty()) null else RigKeyformGeometryEdit(
						controlPoints = geoObj.optionalArray("controlPoints").mapNotNull { it.jsonPrimitive.floatOrNull }.takeIf { it.isNotEmpty() },
						originX = geoObj.optionalFloat("originX"),
						originY = geoObj.optionalFloat("originY"),
						angle = geoObj.optionalFloat("angle"),
						scale = geoObj.optionalFloat("scale"),
						positionDeltas = geoObj.optionalArray("positionDeltas").mapNotNull { it.jsonPrimitive.floatOrNull }.takeIf { it.isNotEmpty() },
					),
					channels = if (chObj.isEmpty()) null else RigKeyformChannelsEdit(
						opacity = chObj.optionalFloat("opacity"),
						drawOrder = chObj.optionalFloat("drawOrder"),
						multiplyColor = chObj.optionalArray("multiplyColor").mapNotNull { it.jsonPrimitive.floatOrNull }.takeIf { it.isNotEmpty() },
						screenColor = chObj.optionalArray("screenColor").mapNotNull { it.jsonPrimitive.floatOrNull }.takeIf { it.isNotEmpty() },
						glueIntensity = chObj.optionalFloat("glueIntensity"),
						flipX = chObj.optionalBoolean("flipX"),
						flipY = chObj.optionalBoolean("flipY"),
					),
				)
			},
			keyformDeleteEdits = rigEditObject.optionalArray("keyformDeletes").map { element ->
				val obj = element.jsonObject
				val targetObj = obj.requiredObject("target")
				RigKeyformDeleteEdit(
					target = RigTargetRef(
						kind = enumValue(targetObj.requiredString("kind")),
						id = targetObj.requiredString("id"),
						secondaryId = targetObj.optionalString("secondaryId"),
						glueId = targetObj.optionalString("glueId"),
					),
					parameterId = obj.requiredString("parameterId"),
					keyValue = obj.optionalFloat("keyValue"),
					channel = obj.optionalString("channel"),
				)
			},
			keyformCopyEdits = rigEditObject.optionalArray("keyformCopies").map { element ->
				val obj = element.jsonObject
				val sourceTargetObj = obj.requiredObject("sourceTarget")
				val destTargetObj = obj.requiredObject("destinationTarget")
				RigKeyformCopyEdit(
					sourceTarget = RigTargetRef(
						kind = enumValue(sourceTargetObj.requiredString("kind")),
						id = sourceTargetObj.requiredString("id"),
						secondaryId = sourceTargetObj.optionalString("secondaryId"),
						glueId = sourceTargetObj.optionalString("glueId"),
					),
					sourceCoordinate = obj.requiredObject("sourceCoordinate").mapValues { it.value.jsonPrimitive.floatOrNull ?: 0f },
					destinationTarget = RigTargetRef(
						kind = enumValue(destTargetObj.requiredString("kind")),
						id = destTargetObj.requiredString("id"),
						secondaryId = destTargetObj.optionalString("secondaryId"),
						glueId = destTargetObj.optionalString("glueId"),
					),
					destinationCoordinate = obj.requiredObject("destinationCoordinate").mapValues { it.value.jsonPrimitive.floatOrNull ?: 0f },
					channels = obj.optionalArray("channels").map { it.jsonPrimitive.content }.takeIf { it.isNotEmpty() },
				)
			},
		)
		val meshOverrides = value.optionalObject("meshOverrides").mapValues { (_, element) ->
			val obj = element.jsonObject
			io.github.psd2live.core.MeshSettings(
				outerMargin = obj["outerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f,
				edgeMode = obj["edgeMode"]?.jsonPrimitive?.contentOrNull?.let {
					runCatching { io.github.psd2live.core.MeshEdgeMode.valueOf(it) }.getOrNull()
				} ?: if (obj["innerMarginEnabled"]?.jsonPrimitive?.booleanOrNull == true)
					io.github.psd2live.core.MeshEdgeMode.DOUBLE else io.github.psd2live.core.MeshEdgeMode.SINGLE,
				edgeWidth = obj["edgeWidth"]?.jsonPrimitive?.floatOrNull
					?: ((obj["outerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f) +
						(obj["innerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f)),
				maxEdgeDistance = obj["maxEdgeDistance"]?.jsonPrimitive?.floatOrNull ?: 48.0f,
				interiorDensity = obj["interiorDensity"]?.jsonPrimitive?.floatOrNull ?: 48.0f,
				fillAlgorithm = obj["fillAlgorithm"]?.jsonPrimitive?.contentOrNull
					?.let { runCatching { io.github.psd2live.core.MeshFillAlgorithm.valueOf(it) }.getOrNull() }
					?: io.github.psd2live.core.MeshFillAlgorithm.GRADED_POISSON,
				suppressBoundaryDiagonals = obj["suppressBoundaryDiagonals"]?.jsonPrimitive?.booleanOrNull ?: false,
				fillParameters = io.github.psd2live.project.WorkspaceSettingsCodec.decodeFillParameters(obj["fillParameters"]),
				wrap = io.github.psd2live.project.WorkspaceSettingsCodec.decodeWrap(obj["wrap"]),
			)
		}
		return WorkspaceDocument(
			source = source,
			generationSource = value["generationSource"]?.let { decodeSource(it.jsonObject, project, rasterCache) },
			meshSource = value["meshSource"]?.let { decodeSource(it.jsonObject, project, rasterCache) },
			layerVisibility = value.optionalObject("layerVisibility").mapValues { it.value.jsonPrimitive.booleanOrNull ?: invalid("layerVisibility.${it.key}") },
			deletedLayerIds = value.optionalArray("deletedLayerIds").map { it.jsonPrimitive.content }.toSet(),
			layerOverrides = overrides,
			parentOverrides = value.optionalObject("parentOverrides").mapValues { it.value.jsonPrimitive.contentOrNull },
			rigEdits = rigEdits,
            settings = value["settings"] as? JsonObject ?: JsonObject(emptyMap()),
			meshOverrides = meshOverrides,
			textureOverrides = TextureOverrideCodec.decodeAll(value[TEXTURE_OVERRIDES]?.jsonObject),
		)
	}

	private fun encodeSource(source: SourceArt, project: Path): JsonObject = buildJsonObject {
		put("canvasWidth", source.widthPx)
		put("canvasHeight", source.heightPx)
		putJsonArray("groups") {
			source.groups.forEach { group ->
				add(buildJsonObject {
					put("path", group.path)
					put("name", group.name)
					put("visible", group.visible)
					put("opacity", group.opacity)
					put("clipped", group.clipped)
					put("blend", group.blend.name)
					put("passThrough", group.passThrough)
				})
			}
		}
		putJsonArray("layers") {
			source.layers.forEach { layer ->
				val workspaceLayer = layer as? WorkspaceSourceMetadata
				val blobHash = persistRaster(project, layer.raster.rgba, layer.raster.width, layer.raster.height)
				add(buildJsonObject {
					put("id", layer.id.raw)
					put("name", layer.name)
					put("groupPath", layer.groupPath)
					put("kind", layer.kind.name)
					put("visible", layer.visible)
					put("order", layer.order)
					put("left", layer.bounds.left)
					put("top", layer.bounds.top)
					put("width", layer.bounds.width)
					put("height", layer.bounds.height)
					put("opacity", layer.opacity)
					put("clipped", layer.clipped)
					put("blend", layer.blend.name)
					put("channelRed", layer.channelMask.red)
					put("channelGreen", layer.channelMask.green)
					put("channelBlue", layer.channelMask.blue)
					put("channelAlpha", layer.channelMask.alpha)
					put("rasterWidth", layer.raster.width)
					put("rasterHeight", layer.raster.height)
					put("rgbaBlob", blobHash)
					put("derived", workspaceLayer?.derived == true)
					workspaceLayer?.sourceAssetId?.let { put("sourceAssetId", it) }
					workspaceLayer?.sourceSpatialReferenceId?.let { put("sourceSpatialReferenceId", it) }
					layer.storedCanvasRect?.let { rect ->
						putJsonArray("rect") { add(JsonPrimitive(rect.left)); add(JsonPrimitive(rect.top)); add(JsonPrimitive(rect.width)); add(JsonPrimitive(rect.height)) }
					}
					layer.transform.takeUnless { it.isIdentity }?.let { transform ->
						putJsonArray("transform") { transform.toList().forEach { add(JsonPrimitive(it)) } }
					}
				})
			}
		}
	}

	private fun decodeSource(value: JsonObject, project: Path, rasterCache: MutableMap<String, ByteArray>): SourceArt {
		val layers: List<SourceLayer> = value.optionalArray("layers").map { element ->
			val layer = element.jsonObject
			val rasterWidth = layer.requiredInt("rasterWidth")
			val rasterHeight = layer.requiredInt("rasterHeight")
			val blobHash = layer.requiredString("rgbaBlob")
			WorkspaceSourceLayer(
				id = LayerId(layer.requiredString("id")),
				name = layer.requiredString("name"),
				groupPath = layer.requiredString("groupPath"),
				kind = enumValue(layer.requiredString("kind")),
				visible = layer.requiredBoolean("visible"),
				order = layer.requiredInt("order"),
				bounds = LayerBounds(layer.requiredInt("left"), layer.requiredInt("top"), layer.requiredInt("width"), layer.requiredInt("height")),
				opacity = layer.requiredFloat("opacity"),
				clipped = layer.requiredBoolean("clipped"),
				blend = enumValue(layer.requiredString("blend")),
				channelMask = ChannelMask(
					red = layer.requiredBoolean("channelRed"),
					green = layer.requiredBoolean("channelGreen"),
					blue = layer.requiredBoolean("channelBlue"),
					alpha = layer.requiredBoolean("channelAlpha"),
				),
				raster = LayerRaster(
					rasterWidth,
					rasterHeight,
					rasterCache.getOrPut(blobHash) { loadRaster(project, blobHash, rasterWidth, rasterHeight) },
				),
				sourceAssetId = layer.optionalString("sourceAssetId"),
				sourceSpatialReferenceId = layer.optionalString("sourceSpatialReferenceId"),
				derived = layer.requiredBoolean("derived"),
				rect = layer["rect"]?.let { element ->
					val values = element.jsonArray.map { it.jsonPrimitive.floatOrNull ?: invalid("rect") }
					if (values.size != 4) invalid("rect")
					LayerCanvasRect(values[0], values[1], values[2], values[3])
				},
				layerTransform = layer["transform"]?.let { element ->
					val values = element.jsonArray.map { it.jsonPrimitive.floatOrNull ?: invalid("transform") }
					runCatching { LayerTransform.of(values) }.getOrElse { invalid("transform") }.takeUnless { it.isIdentity }
				},
			)
		}
		val groups: List<SourceGroup> = value.optionalArray("groups").map { element ->
			val group = element.jsonObject
			WorkspaceSourceGroup(
				path = group.requiredString("path"),
				name = group.requiredString("name"),
				visible = group.requiredBoolean("visible"),
				opacity = group.requiredFloat("opacity"),
				clipped = group.requiredBoolean("clipped"),
				blend = enumValue(group.requiredString("blend")),
				passThrough = group.requiredBoolean("passThrough"),
			)
		}
		return WorkspaceSourceArt(value.requiredInt("canvasWidth"), value.requiredInt("canvasHeight"), layers, groups)
	}

	private fun encodeNode(node: WorkspaceHistoryNode): JsonObject = buildJsonObject {
		put("version", STORE_VERSION)
		put("id", node.id)
		node.parentId?.let { put("parentId", it) }
		put("revisionId", node.revisionId)
		put("snapshotHash", node.snapshotHash)
		put("summary", node.summary)
		put("actor", node.actor)
		node.taskId?.let { put("taskId", it) }
		put("createdAt", node.createdAt.toString())
	}

	private fun encodeTask(task: WorkspaceTaskSnapshot): JsonObject = buildJsonObject {
		put("id", task.id)
		put("objective", task.objective)
		putJsonArray("plan") { task.plan.forEach { add(JsonPrimitive(it)) } }
		put("status", task.status.name)
		task.currentStep?.let { put("currentStep", it) }
		put("progress", task.progress)
		put("inputRevisionId", task.inputRevisionId)
		put("inputHistoryHeadNodeId", task.inputHistoryHeadNodeId)
		put("createdAt", task.createdAt)
		put("updatedAt", task.updatedAt)
		putJsonArray("artifactIds") { task.artifactIds.forEach { add(JsonPrimitive(it)) } }
		putJsonArray("events") {
			task.events.forEach { event ->
				add(buildJsonObject {
					put("sequence", event.sequence)
					put("createdAt", event.createdAt)
					put("status", event.status.name)
					put("message", event.message)
					putJsonArray("artifactIds") { event.artifactIds.forEach { add(JsonPrimitive(it)) } }
				})
			}
		}
	}

	private fun decodeTask(element: JsonElement): WorkspaceTaskSnapshot {
		val task = element.jsonObject
		return WorkspaceTaskSnapshot(
			id = task.requiredString("id"),
			objective = task.requiredString("objective"),
			plan = task.optionalArray("plan").map { it.jsonPrimitive.content },
			status = enumValue(task.requiredString("status")),
			currentStep = task["currentStep"]?.jsonPrimitive?.intOrNull,
			progress = task.requiredFloat("progress"),
			inputRevisionId = task.requiredString("inputRevisionId"),
			inputHistoryHeadNodeId = task.requiredString("inputHistoryHeadNodeId"),
			createdAt = task.requiredString("createdAt"),
			updatedAt = task.requiredString("updatedAt"),
			artifactIds = task.optionalArray("artifactIds").map { it.jsonPrimitive.content },
			events = task.optionalArray("events").map { eventElement ->
				val event = eventElement.jsonObject
				WorkspaceTaskEventSnapshot(
					sequence = event.requiredLong("sequence"),
					createdAt = event.requiredString("createdAt"),
					status = enumValue(event.requiredString("status")),
					message = event.requiredString("message"),
					artifactIds = event.optionalArray("artifactIds").map { it.jsonPrimitive.content },
				)
			},
		)
	}

	private fun persistRaster(project: Path, rgba: ByteArray, width: Int, height: Int): String {
		val hash = WorkspaceRevisions.rasterDigest(rgba)
        val name = rasterFileName(hash, width, height)
        val path = project.resolve("blobs/$name")
        if (isStored(path)) return hash
        val existing = rasterSource?.projectRoot(project.fileName.toString())?.resolve("blobs/$name")
        if (existing != null && linkRaster(existing, path)) { stored.add(path); return hash }
        val image = io.github.psd2live.core.PreviewRenderer.rasterImage(width, height, rgba)
        val bytes = ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(image, "png", out); out.toByteArray() }
        writeImmutable(path, bytes)
		stored.add(path)
		return hash
	}

	private fun rasterFileName(hash: String, width: Int, height: Int) = "${fileKey(hash)}-${width}x${height}.png"

	/**
	 * Gives [target] the immutable raster file [source] when there is one: a hard link, or where links are not
	 * possible a copy that keeps its attributes, so the archive writer knows the file's digest
	 * ([ProjectArchive.Digests]). False when [source] is missing; true when [target] exists already.
	 */
	private fun linkRaster(source: Path, target: Path): Boolean {
		if (Files.isRegularFile(target)) return true
		if (!Files.isRegularFile(source)) return false
		Files.createDirectories(target.parent)
		try { Files.createLink(target, source) }
		catch (_: java.nio.file.FileAlreadyExistsException) {}
		catch (_: Exception) {
			val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
			try {
				ProjectArchive.copyKeepingTime(source, temporary)
				try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE) }
				catch (_: java.nio.file.FileAlreadyExistsException) {}
				catch (_: AtomicMoveNotSupportedException) { if (!Files.exists(target)) Files.move(temporary, target) }
			} finally { Files.deleteIfExists(temporary) }
		}
		return true
	}

	private fun loadRaster(project: Path, hash: String, width: Int, height: Int): ByteArray {
		val expected = Math.multiplyExact(Math.multiplyExact(width, height), 4)
		require(expected > 0) { "Stored raster dimensions must be positive" }
		val png = project.resolve("blobs/${fileKey(hash)}-${width}x${height}.png")
        if (Files.isRegularFile(png)) {
            val encoded = Files.readAllBytes(png)
            // The store's own RGBA PNGs decode directly; anything else, or a result that does not match, through ImageIO.
            PngRgbaDecoder.decode(encoded, width, height)?.let { bytes -> if (sha256(bytes) == hash) return verifiedRaster(bytes, hash) }
            val image = javax.imageio.ImageIO.read(javax.imageio.stream.MemoryCacheImageInputStream(java.io.ByteArrayInputStream(encoded))) ?: error("Invalid PNG: $hash")
            require(image.width == width && image.height == height) { "Raster dimensions mismatch" }
            val bytes = rgbaOf(image)
            require(sha256(bytes) == hash) { "Stored raster hash mismatch: $hash" }
            return verifiedRaster(bytes, hash)
        }
        val file = project.resolve("blobs/${fileKey(hash)}.rgba.gz")
		require(Files.isRegularFile(file)) { "Stored raster blob is missing: $hash" }
		val rgba = GZIPInputStream(Files.newInputStream(file)).use { input -> input.readNBytes(expected + 1) }
		require(rgba.size == expected) { "Stored raster length mismatch for $hash" }
		require(sha256(rgba) == hash) { "Stored raster hash mismatch for $hash" }
		return verifiedRaster(rgba, hash)
	}

	/**
	 * Every distinct raster the [snapshots]' sources name, by digest, decoded on several threads: each blob is an
	 * independent immutable file. Threads are bounded by the cores and by the heap the decodes hold at once (a
	 * file's bytes and its pixels). The first failure, in snapshot order, is thrown.
	 */
	private fun loadRasters(project: Path, snapshots: Collection<JsonObject>): Map<String, ByteArray> {
		class Blob(val hash: String, val width: Int, val height: Int)
		val blobs = LinkedHashMap<String, Blob>()
		for (snapshot in snapshots) {
			val sources = listOf(snapshot) + listOf("generationSource", "meshSource", "placementSource").mapNotNull { snapshot[it] as? JsonObject }
			for (source in sources) for (element in source.optionalArray("layers")) {
				val layer = element as? JsonObject ?: continue
				val hash = layer.optionalString("rgbaBlob") ?: continue
				val width = layer["rasterWidth"]?.jsonPrimitive?.intOrNull ?: continue
				val height = layer["rasterHeight"]?.jsonPrimitive?.intOrNull ?: continue
				blobs.getOrPut(hash) { Blob(hash, width, height) }
			}
		}
		if (blobs.isEmpty()) return emptyMap()
		val largest = blobs.values.maxOf { it.width.toLong() * it.height * 4 }
		val threads = minOf(Runtime.getRuntime().availableProcessors().toLong(), 8L, blobs.size.toLong(),
			Runtime.getRuntime().maxMemory() / 4 / maxOf(1L, largest * 2)).toInt().coerceAtLeast(1)
		if (threads == 1) return blobs.mapValues { (_, blob) -> loadRaster(project, blob.hash, blob.width, blob.height) }
		val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { task -> Thread(task, "psd2live-raster-load").apply { isDaemon = true } }
		try {
			val futures = blobs.mapValues { (_, blob) -> pool.submit<ByteArray> { loadRaster(project, blob.hash, blob.width, blob.height) } }
			return futures.mapValues { (_, future) ->
				try { future.get() } catch (failure: java.util.concurrent.ExecutionException) {
					futures.values.forEach { it.cancel(true) }
					throw failure.cause ?: failure
				} catch (interrupted: InterruptedException) {
					futures.values.forEach { it.cancel(true) }
					Thread.currentThread().interrupt()
					throw interrupted
				}
			}
		} finally {
			pool.shutdownNow()
		}
	}

	/** The raster digest is the SHA-256 just checked: the revision and rig-stage memos take it instead of hashing again. */
	private fun verifiedRaster(rgba: ByteArray, hash: String): ByteArray {
		WorkspaceRevisions.seedRasterDigest(rgba, hash)
		io.github.psd2live.core.RasterDigest.seed(rgba, hash)
		return rgba
	}

	/**
	 * [assetId]'s metadata as [loadAsset] reads it, checking that its raster file is there without decoding it;
	 * null when the asset is not stored. The raster's pixels are checked against their digest when loaded.
	 */
	private fun storedAsset(project: Path, assetId: String): WorkspaceImportedPngAsset? {
		val (metadata, public) = assetMetadata(project, assetId) ?: return null
		val hash = metadata.requiredString("rgbaBlob")
		require(public.pixelWidth > 0 && public.pixelHeight > 0) { "Stored raster dimensions must be positive" }
		val blobs = project.resolve("blobs")
		require(Files.isRegularFile(blobs.resolve(rasterFileName(hash, public.pixelWidth, public.pixelHeight))) ||
			Files.isRegularFile(blobs.resolve("${fileKey(hash)}.rgba.gz"))) { "Stored raster blob is missing: $hash" }
		return public
	}

	internal fun projectRoot(projectId: String): Path {
		require(projectId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid project ID" }
		val normalizedRoot = root.toAbsolutePath().normalize()
		return normalizedRoot.resolve(projectId).normalize().also { path ->
			require(path.startsWith(normalizedRoot)) { "Project store path escapes its root" }
		}
	}

	private fun readJson(path: Path): JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject

	private fun writeImmutable(path: Path, bytes: ByteArray) {
		if (Files.isRegularFile(path)) {
			val same = if (path.fileName.toString().endsWith(".json")) readJson(path) == json.parseToJsonElement(bytes.decodeToString()) else Files.readAllBytes(path).contentEquals(bytes)
            require(same) { "Immutable workspace record changed: ${path.fileName}" }
			return
		}
		writeAtomic(path, bytes, replace = false)
	}

	/** [WorkspaceStore.shareContent] into [project], writing each shared file this store does not have yet. */
	private fun shareContent(value: JsonObject, project: Path): JsonObject = shareContent(value) { relative, bytes ->
		val path = project.resolve(relative)
		if (!isStored(path)) { writeAtomic(path, bytes(), replace = false, pretty = false); stored.add(path) }
	}

	private fun writeAtomic(path: Path, bytes: ByteArray, replace: Boolean = true, pretty: Boolean = true) {
		Files.createDirectories(path.parent)
		val temporary = path.parent.resolve(".${path.fileName}.${UUID.randomUUID()}.tmp")
		try {
			val output = if (pretty && path.fileName.toString().endsWith(".json")) json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(bytes.decodeToString())).encodeToByteArray() else bytes
            Files.write(temporary, output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
			val options = if (replace) {
				arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			} else {
				arrayOf(StandardCopyOption.ATOMIC_MOVE)
			}
			// Windows refuses to replace a file another process (a scanner or indexer) holds open for a moment.
			var attempt = 0
			while (true) {
				try {
					try {
						Files.move(temporary, path, *options)
					} catch (_: AtomicMoveNotSupportedException) {
						if (replace) Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
						else Files.move(temporary, path)
					}
					break
				} catch (error: AccessDeniedException) {
					if (!replace || ++attempt >= REPLACE_ATTEMPTS) throw error
					Thread.sleep(10L * attempt)
				}
			}
		} finally {
			Files.deleteIfExists(temporary)
		}
	}

	private fun fileKey(value: String): String = sha256(value.encodeToByteArray())

	private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private fun kotlinx.serialization.json.JsonObjectBuilder.putBounds(
		name: String,
		left: Float,
		top: Float,
		right: Float,
		bottom: Float,
	) = putJsonObject(name) {
		put("left", left)
		put("top", top)
		put("right", right)
		put("bottom", bottom)
	}

	/** Chunks and payloads read while loading one history, shared by the snapshots that name them. */
	internal class SharedContent {
		val chunks = HashMap<String, List<JsonObject>>()
		val payloads = HashMap<String, String>()
		/**
		 * One object per distinct entry: a journal's last chunk ends at its end, so a revision and the next one
		 * hold the same trailing entries in different chunks.
		 */
		val entries = HashMap<JsonObject, JsonObject>()
	}

	companion object {
		const val STORE_VERSION = 1
		private const val JOURNAL = "authoringJournal"
		private const val IMPORTED_CMO3 = "importedCmo3"
		private const val TEXTURE_OVERRIDES = "textureOverrides"
		/** Embedded CMO3 baselines at least this long are stored once as a payload. */
		private const val PAYLOAD_MIN_CHARS = 64 * 1024
		/** A chunk ends after an entry whose digest starts with one of 4 of the 16 hex digits (about 4 entries per chunk). */
		private const val CHUNK_MAX_ENTRIES = 16
		/** Bounds what one commit rewrites: the open tail chunk is at most about this long plus one entry. */
		private const val CHUNK_MAX_CHARS = 64 * 1024
		/** Replacing a file retries for about half a second in all before the refusal is reported. */
		private const val REPLACE_ATTEMPTS = 10
		private val payloadDigests = IdentityWeakCache<String, String>()

		/**
		 * Content-defined chunks of [journal]: a chunk closes after an entry chosen by its own digest, so appending
		 * entries or changing one entry leaves every other closed chunk, and its file, unchanged.
		 */
		internal fun journalChunks(journal: List<JsonObject>): List<List<JsonObject>> {
			val chunks = ArrayList<List<JsonObject>>()
			var start = 0
			var chars = 0L
			for (i in journal.indices) {
				val digest = JournalEntryDigests.of(journal[i])
				chars += digest.length
				val boundary = digest.sha256[0].digitToInt(16) % 4 == 0
				if (boundary || i + 1 - start >= CHUNK_MAX_ENTRIES || chars >= CHUNK_MAX_CHARS || i == journal.lastIndex) {
					chunks.add(journal.subList(start, i + 1))
					start = i + 1
					chars = 0
				}
			}
			return chunks
		}

		/** A chunk's key: the SHA-256 of its entries' digests, so known entries are not hashed again. */
		internal fun journalChunkKey(entries: List<JsonObject>): String =
			WorkspaceRevisions.sha256(entries.joinToString("\n") { JournalEntryDigests.of(it).sha256 }.encodeToByteArray())

		/**
		 * Replaces the parts of an encoded snapshot that later revisions mostly repeat with references to shared,
		 * content-addressed files: the authoring journal becomes chunks under `history/journal/`, and a large embedded
		 * CMO3 baseline a payload under `history/payloads/`. A revision then adds only its changed chunks instead of
		 * a full copy of the journal. [expandSnapshot] restores the original snapshot exactly. The reference shapes
		 * (an object where v1 has an array or string) make builds without this support fail to read the snapshot
		 * instead of silently dropping the journal. [write] receives each shared file's path under the project and
		 * its bytes, and writes those not already there.
		 */
		internal fun shareContent(value: JsonObject, write: (String, () -> ByteArray) -> Unit): JsonObject {
			val rig = value["rigEdits"]?.jsonObject ?: return value
			val changes = LinkedHashMap<String, JsonElement>()
			val journal = (rig[JOURNAL] as? JsonArray)?.map { it.jsonObject }.orEmpty()
			if (journal.isNotEmpty()) changes[JOURNAL] = buildJsonObject {
				put("count", journal.size)
				putJsonArray("chunks") {
					for (chunk in journalChunks(journal)) {
						val key = journalChunkKey(chunk)
						write("history/journal/$key.json") { JsonArray(chunk).toString().encodeToByteArray() }
						add(JsonPrimitive(key))
					}
				}
			}
			(rig[IMPORTED_CMO3] as? JsonPrimitive)?.takeIf { it.isString && it.content.length >= PAYLOAD_MIN_CHARS }?.content?.let { text ->
				val hash = payloadDigests.getOrPut(text) { WorkspaceRevisions.sha256(text.encodeToByteArray()) }
				write("history/payloads/$hash.txt") { text.encodeToByteArray() }
				changes[IMPORTED_CMO3] = buildJsonObject { put("payload", hash) }
			}
			if (changes.isEmpty()) return value
			return JsonObject(value + ("rigEdits" to JsonObject(rig + changes)))
		}

		/** The self-contained v1 snapshot that [snapshot] stands for, resolving shared journal chunks and payloads. */
		internal fun expandSnapshot(project: Path, snapshot: JsonObject, shared: SharedContent = SharedContent()): JsonObject {
			val rig = snapshot["rigEdits"] as? JsonObject ?: return snapshot
			val changes = LinkedHashMap<String, JsonElement>()
			(rig[JOURNAL] as? JsonObject)?.let { reference ->
				val keys = reference["chunks"]?.jsonArray?.map { it.jsonPrimitive.content } ?: invalid("authoringJournal.chunks")
				val entries = keys.flatMap { key ->
					shared.chunks.getOrPut(key) {
						require(key.matches(Regex("[0-9a-f]{64}"))) { "Invalid journal chunk reference" }
						val file = project.resolve("history/journal/$key.json")
						require(Files.isRegularFile(file)) { "Journal chunk is missing: $key" }
						val chunk = Json.parseToJsonElement(Files.readString(file)).jsonArray.map { it.jsonObject }
						require(journalChunkKey(chunk) == key) { "Journal chunk checksum mismatch: $key" }
						chunk.map { entry -> shared.entries.getOrPut(entry) { entry } }
					}
				}
				require(entries.size == reference["count"]?.jsonPrimitive?.intOrNull) { "Journal entry count mismatch" }
				changes[JOURNAL] = JsonArray(entries)
			}
			(rig[IMPORTED_CMO3] as? JsonObject)?.let { reference ->
				val hash = reference["payload"]?.jsonPrimitive?.contentOrNull ?: invalid("importedCmo3.payload")
				changes[IMPORTED_CMO3] = JsonPrimitive(shared.payloads.getOrPut(hash) {
					require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid payload reference" }
					val file = project.resolve("history/payloads/$hash.txt")
					require(Files.isRegularFile(file)) { "Payload is missing: $hash" }
					val bytes = Files.readAllBytes(file)
					require(WorkspaceRevisions.sha256(bytes) == hash) { "Payload checksum mismatch: $hash" }
					bytes.decodeToString()
				})
			}
			if (changes.isEmpty()) return snapshot
			return JsonObject(snapshot + ("rigEdits" to JsonObject(rig + changes)))
		}

		/**
		 * [image]'s pixels as non-premultiplied RGBA bytes, exactly as [BufferedImage.getRGB] gives them. 8-bit
		 * sRGB interleaved rasters (what the PNG reader returns for RGB and RGBA files) and int ARGB images are read
		 * from their buffer directly; any other layout goes through `getRGB` a block of rows at a time.
		 */
		internal fun rgbaOf(image: java.awt.image.BufferedImage): ByteArray {
			val width = image.width
			val height = image.height
			val out = ByteArray(Math.multiplyExact(Math.multiplyExact(width, height), 4))
			if (copyInterleaved(image, out) || copyIntArgb(image, out)) return out
			val rows = maxOf(1, 65536 / maxOf(1, width))
			val argb = IntArray(width * minOf(rows, height))
			var y = 0
			while (y < height) {
				val count = minOf(rows, height - y)
				image.getRGB(0, y, width, count, argb, 0, width)
				var o = y * width * 4
				for (i in 0 until width * count) {
					val pixel = argb[i]
					out[o] = (pixel ushr 16).toByte(); out[o + 1] = (pixel ushr 8).toByte()
					out[o + 2] = pixel.toByte(); out[o + 3] = (pixel ushr 24).toByte()
					o += 4
				}
				y += count
			}
			return out
		}

		/** An 8-bit sRGB RGB or non-premultiplied RGBA image with one interleaved byte buffer, copied into [out]. */
		private fun copyInterleaved(image: java.awt.image.BufferedImage, out: ByteArray): Boolean {
			val model = image.colorModel as? java.awt.image.ComponentColorModel ?: return false
			val raster = image.raster
			val sample = raster.sampleModel as? java.awt.image.PixelInterleavedSampleModel ?: return false
			val buffer = raster.dataBuffer as? java.awt.image.DataBufferByte ?: return false
			val bands = sample.numBands
			val alpha = model.hasAlpha()
			if (!model.colorSpace.isCS_sRGB || model.isAlphaPremultiplied || bands != (if (alpha) 4 else 3) ||
				model.componentSize.any { it != 8 } || buffer.numBanks != 1 || raster.parent != null ||
				raster.sampleModelTranslateX != 0 || raster.sampleModelTranslateY != 0) return false
			val stride = sample.scanlineStride
			val step = sample.pixelStride
			val offsets = sample.bandOffsets
			val base = buffer.offset
			val data = buffer.data
			val r = offsets[0]; val g = offsets[1]; val b = offsets[2]; val a = if (alpha) offsets[3] else -1
			var o = 0
			for (y in 0 until image.height) {
				var i = base + y * stride
				for (x in 0 until image.width) {
					out[o] = data[i + r]; out[o + 1] = data[i + g]; out[o + 2] = data[i + b]
					out[o + 3] = if (a >= 0) data[i + a] else -1
					o += 4; i += step
				}
			}
			return true
		}

		/** A `TYPE_INT_ARGB` image with one int buffer, copied into [out]. */
		private fun copyIntArgb(image: java.awt.image.BufferedImage, out: ByteArray): Boolean {
			if (image.type != java.awt.image.BufferedImage.TYPE_INT_ARGB) return false
			val raster = image.raster
			val sample = raster.sampleModel as? java.awt.image.SinglePixelPackedSampleModel ?: return false
			val buffer = raster.dataBuffer as? java.awt.image.DataBufferInt ?: return false
			if (buffer.numBanks != 1 || raster.parent != null || raster.sampleModelTranslateX != 0 || raster.sampleModelTranslateY != 0) return false
			val data = buffer.data
			var o = 0
			for (y in 0 until image.height) {
				var i = buffer.offset + y * sample.scanlineStride
				for (x in 0 until image.width) {
					val pixel = data[i++]
					out[o] = (pixel ushr 16).toByte(); out[o + 1] = (pixel ushr 8).toByte()
					out[o + 2] = pixel.toByte(); out[o + 3] = (pixel ushr 24).toByte()
					o += 4
				}
			}
			return true
		}

		/** Working-store folders holding content shared by snapshots; [expandSnapshot] reads them. */
		internal val sharedContentFolders = listOf("history/journal", "history/payloads")

		fun defaultRoot(): Path {
			System.getProperty("psd2live.agent.store")?.takeIf(String::isNotBlank)?.let { return Path.of(it) }
			val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
			return if (localAppData != null) Path.of(localAppData, "PSD2Live", "agent-workspaces")
			else Path.of(System.getProperty("user.home"), ".psd2live", "agent-workspaces")
		}

		inline fun <reified T : Enum<T>> enumValue(raw: String): T =
			runCatching { enumValueOf<T>(raw) }.getOrElse { invalid("enum value '$raw'") }

		fun invalid(field: String): Nothing = throw IllegalArgumentException("Invalid workspace store field: $field")
	}
}

internal data class WorkspaceSourceGroup(
	override val path: String,
	override val name: String,
	override val visible: Boolean,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val passThrough: Boolean,
) : SourceGroup

private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: WorkspaceStore.invalid(name)
private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredInt(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: WorkspaceStore.invalid(name)
private fun JsonObject.requiredLong(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: WorkspaceStore.invalid(name)
private fun JsonObject.requiredFloat(name: String): Float = this[name]?.jsonPrimitive?.floatOrNull ?: WorkspaceStore.invalid(name)
private fun JsonObject.requiredBoolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: WorkspaceStore.invalid(name)
private fun JsonObject.requiredObject(name: String): JsonObject = this[name]?.jsonObject ?: WorkspaceStore.invalid(name)
private fun JsonObject.optionalObject(name: String): JsonObject = this[name]?.jsonObject ?: JsonObject(emptyMap())
private fun JsonObject.optionalArray(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())
private fun JsonObject.optionalFloat(name: String): Float? = this[name]?.jsonPrimitive?.floatOrNull
private fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
