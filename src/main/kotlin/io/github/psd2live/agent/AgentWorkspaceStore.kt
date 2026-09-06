package io.github.psd2live.agent

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
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ParameterKind
import java.io.ByteArrayOutputStream
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
internal class AgentWorkspaceStore(
	private val root: Path = defaultRoot(),
) {
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Synchronized
    internal fun copyAuxiliary(projectId: String, target: Path) {
        val source = projectRoot(projectId)
        for (folder in listOf("assets", "views", "view-images", "workflow")) {
            val directory = source.resolve(folder)
            if (!Files.isDirectory(directory)) continue
            Files.walk(directory).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
                val destination = target.resolve(source.relativize(file))
                Files.createDirectories(destination.parent)
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
            } }
        }
        // Re-encode staged assets as PNG, including assets not yet used by a layer.
        val assets = source.resolve("assets")
        if (Files.isDirectory(assets)) Files.list(assets).use { paths -> paths.filter(Files::isRegularFile).forEach { file ->
            val asset = loadAsset(projectId, readJson(file).requiredString("id"))!!
            persistRaster(target, asset.rgba, asset.public.pixelWidth, asset.public.pixelHeight)
        } }
    }

	@Synchronized
	fun loadHistory(projectId: String): WorkspaceHistoryTree<AgentWorkspaceDocument>? {
		val project = projectRoot(projectId)
		val headFile = project.resolve("HEAD.json")
		val nodesDirectory = project.resolve("history/nodes")
		if (!Files.isRegularFile(headFile) || !Files.isDirectory(nodesDirectory)) return null
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
		val documents = mutableMapOf<String, AgentWorkspaceDocument>()
		val rasters = mutableMapOf<String, ByteArray>()
		val selections = nodes.map { node ->
			val snapshotFile = project.resolve("history/snapshots/${fileKey(node.snapshotHash)}.json")
			require(Files.isRegularFile(snapshotFile)) { "History snapshot is missing for ${node.id}" }
			val document = documents.getOrPut(node.snapshotHash) {
				decodeDocument(readJson(snapshotFile), project, rasters)
			}
			WorkspaceHistorySelection(node, document)
		}
		val order = headDocument["nodeOrder"]?.jsonArray?.map { it.jsonPrimitive.content }
        val ordered = if (order != null) {
            require(order.size == selections.size && order.toSet() == selections.map { it.node.id }.toSet()) { "Invalid history node order" }
            val byId = selections.associateBy { it.node.id }; order.map { byId.getValue(it) }
        } else selections
        return WorkspaceHistoryTree.restore(ordered, headId)
	}

	@Synchronized
	fun persistHistory(projectId: String, state: WorkspaceHistoryState<AgentWorkspaceDocument>) {
		val project = projectRoot(projectId)
		for (selection in state.selections) {
			val snapshotPath = project.resolve("history/snapshots/${fileKey(selection.node.snapshotHash)}.json")
			if (!Files.isRegularFile(snapshotPath)) {
				val snapshotBytes = encodeDocument(selection.snapshot, project).toString().encodeToByteArray()
				writeImmutable(snapshotPath, snapshotBytes)
			}
			val nodePath = project.resolve("history/nodes/${fileKey(selection.node.id)}.json")
			if (!Files.isRegularFile(nodePath)) {
				writeImmutable(nodePath, encodeNode(selection.node).toString().encodeToByteArray())
			}
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
    fun persistWorkflow(projectId: String, id: String, value: JsonObject) {
        writeImmutable(projectRoot(projectId).resolve("workflow/${fileKey(id)}.json"), value.toString().encodeToByteArray())
    }

    @Synchronized
    fun loadWorkflow(projectId: String, id: String): JsonObject {
        val path = projectRoot(projectId).resolve("workflow/${fileKey(id)}.json")
        require(Files.isRegularFile(path)) { "Workflow record not found: $id" }
        return readJson(path).also { require(it.requiredString("id") == id) }
    }

    @Synchronized
    fun registrationsForAsset(projectId: String, assetId: String): List<JsonObject> {
        val directory=projectRoot(projectId).resolve("workflow")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths -> paths.filter(Files::isRegularFile).sorted().map { readJson(it) }
            .filter { it.optionalString("kind")=="registration" && it.optionalString("asset_id")==assetId }.toList() }
    }

    @Synchronized
	fun persistAsset(projectId: String, asset: AgentPngAsset) {
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
	fun loadAsset(projectId: String, assetId: String): AgentPngAsset? {
		val project = projectRoot(projectId)
		val file = project.resolve("assets/${fileKey(assetId)}.json")
		if (!Files.isRegularFile(file)) return null
		val objectValue = readJson(file)
		require(objectValue.requiredString("id") == assetId) { "Stored asset identity mismatch" }
		val width = objectValue.requiredInt("pixelWidth")
		val height = objectValue.requiredInt("pixelHeight")
		val rect = objectValue.requiredObject("canvasRect")
		val bounds = io.github.psd2live.core.Bounds(rect.requiredFloat("left"), rect.requiredFloat("top"), rect.requiredFloat("right"), rect.requiredFloat("bottom"))
		val placement = AgentCanvasPlacement(
			coordinateSpace = objectValue.requiredString("coordinateSpace"),
			canvasRect = bounds,
			imagePixelWidth = width,
			imagePixelHeight = height,
			canvasUnitsPerPixelX = bounds.width / width,
			canvasUnitsPerPixelY = bounds.height / height,
			sourceViewId = objectValue.requiredString("sourceViewId"),
		)
		return AgentPngAsset(
			public = AgentImportedPngAsset(assetId, objectValue.requiredString("sha256"), width, height, placement, objectValue["details"] as? JsonObject ?: JsonObject(emptyMap())),
			rgba = loadRaster(project, objectValue.requiredString("rgbaBlob"), width, height),
            originalPng = objectValue.optionalString("originalPng")?.let { java.util.Base64.getDecoder().decode(it) },
		)
	}

	@Synchronized
	fun persistView(projectId: String, view: AgentRenderedView) {
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
    fun persistSpatial(projectId: String, viewId: String, spatial: AgentViewSpatialMetadata) {
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
	fun loadSpatial(projectId: String, viewId: String): AgentViewSpatialMetadata? {
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
		return AgentViewSpatialMetadata(
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
	fun persistTasks(projectId: String, tasks: List<AgentTaskSnapshot>) {
		val project = projectRoot(projectId)
		writeAtomic(project.resolve("tasks.json"), buildJsonObject {
			put("version", STORE_VERSION)
			putJsonArray("tasks") { tasks.forEach { add(encodeTask(it)) } }
		}.toString().encodeToByteArray())
	}

	@Synchronized
	fun loadTasks(projectId: String): List<AgentTaskSnapshot> {
		val file = projectRoot(projectId).resolve("tasks.json")
		if (!Files.isRegularFile(file)) return emptyList()
		return readJson(file).optionalArray("tasks").map(::decodeTask)
	}

	private fun encodeDocument(document: AgentWorkspaceDocument, project: Path): JsonObject = buildJsonObject {
        put("settings", document.settings)
		put("version", STORE_VERSION)
		put("canvasWidth", document.source.widthPx)
		put("canvasHeight", document.source.heightPx)
		putJsonArray("groups") {
			document.source.groups.forEach { group ->
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
			document.source.layers.forEach { layer ->
				val workspaceLayer = layer as? AgentWorkspaceSourceLayer
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
				})
			}
		}
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
		putJsonObject("rigEdits") {
            put("assetLayers", JsonObject(document.rigEdits.assetLayers))
            putJsonArray("calibrationLayerIds") { document.rigEdits.calibrationLayerIds.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("warps") { document.rigEdits.warpEdits.forEach { add(it.toJson()) } }
            putJsonArray("physics") { document.rigEdits.physicsEdits.forEach { add(it.toJson()) } }
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
						}
						putJsonObject("sourceCoordinate") {
							copy.sourceCoordinate.toSortedMap().forEach { (k, v) -> put(k, v) }
						}
						putJsonObject("destinationTarget") {
							put("kind", copy.destinationTarget.kind.name)
							put("id", copy.destinationTarget.id)
							copy.destinationTarget.secondaryId?.let { put("secondaryId", it) }
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
	): AgentWorkspaceDocument {
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
            assetLayers = rigEditObject.optionalObject("assetLayers").mapValues { it.value.jsonObject },
            calibrationLayerIds = rigEditObject.optionalArray("calibrationLayerIds").map { it.jsonPrimitive.content }.toSet(),
            warpEdits = rigEditObject.optionalArray("warps").map { io.github.psd2live.core.RigWarpEdit.fromJson(it.jsonObject) },
            physicsEdits = rigEditObject.optionalArray("physics").map { io.github.psd2live.core.RigPhysicsEdit.fromJson(it.jsonObject) },
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
					),
					sourceCoordinate = obj.requiredObject("sourceCoordinate").mapValues { it.value.jsonPrimitive.floatOrNull ?: 0f },
					destinationTarget = RigTargetRef(
						kind = enumValue(destTargetObj.requiredString("kind")),
						id = destTargetObj.requiredString("id"),
						secondaryId = destTargetObj.optionalString("secondaryId"),
					),
					destinationCoordinate = obj.requiredObject("destinationCoordinate").mapValues { it.value.jsonPrimitive.floatOrNull ?: 0f },
					channels = obj.optionalArray("channels").map { it.jsonPrimitive.content }.takeIf { it.isNotEmpty() },
				)
			},
		)
		return AgentWorkspaceDocument(
			source = WorkspaceSourceArt(value.requiredInt("canvasWidth"), value.requiredInt("canvasHeight"), layers, groups),
			layerVisibility = value.optionalObject("layerVisibility").mapValues { it.value.jsonPrimitive.booleanOrNull ?: invalid("layerVisibility.${it.key}") },
			deletedLayerIds = value.optionalArray("deletedLayerIds").map { it.jsonPrimitive.content }.toSet(),
			layerOverrides = overrides,
			parentOverrides = value.optionalObject("parentOverrides").mapValues { it.value.jsonPrimitive.contentOrNull },
			rigEdits = rigEdits,
            settings = value["settings"] as? JsonObject ?: JsonObject(emptyMap()),
		)
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

	private fun encodeTask(task: AgentTaskSnapshot): JsonObject = buildJsonObject {
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

	private fun decodeTask(element: JsonElement): AgentTaskSnapshot {
		val task = element.jsonObject
		return AgentTaskSnapshot(
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
				AgentTaskEventSnapshot(
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
		val hash = sha256(rgba)
        val path = project.resolve("blobs/${fileKey(hash)}-${width}x${height}.png")
        if (Files.isRegularFile(path)) return hash
        val image = io.github.psd2live.core.PreviewRenderer.rasterImage(width, height, rgba)
        val bytes = ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(image, "png", out); out.toByteArray() }
        writeImmutable(path, bytes)
		return hash
	}

	private fun loadRaster(project: Path, hash: String, width: Int, height: Int): ByteArray {
		val expected = Math.multiplyExact(Math.multiplyExact(width, height), 4)
		require(expected > 0) { "Stored raster dimensions must be positive" }
		val png = project.resolve("blobs/${fileKey(hash)}-${width}x${height}.png")
        if (Files.isRegularFile(png)) {
            val image = javax.imageio.ImageIO.read(png.toFile()) ?: error("Invalid PNG: $hash")
            require(image.width == width && image.height == height) { "Raster dimensions mismatch" }
            val bytes = ByteArray(expected)
            for (y in 0 until height) for (x in 0 until width) {
                val pixel = image.getRGB(x, y); val i = (y * width + x) * 4
                bytes[i] = (pixel ushr 16).toByte(); bytes[i+1] = (pixel ushr 8).toByte()
                bytes[i+2] = pixel.toByte(); bytes[i+3] = (pixel ushr 24).toByte()
            }
            require(sha256(bytes) == hash) { "Stored raster hash mismatch: $hash" }
            return bytes
        }
        val file = project.resolve("blobs/${fileKey(hash)}.rgba.gz")
		require(Files.isRegularFile(file)) { "Stored raster blob is missing: $hash" }
		val rgba = GZIPInputStream(Files.newInputStream(file)).use { input -> input.readNBytes(expected + 1) }
		require(rgba.size == expected) { "Stored raster length mismatch for $hash" }
		require(sha256(rgba) == hash) { "Stored raster hash mismatch for $hash" }
		return rgba
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

	private fun writeAtomic(path: Path, bytes: ByteArray, replace: Boolean = true) {
		Files.createDirectories(path.parent)
		val temporary = path.parent.resolve(".${path.fileName}.${UUID.randomUUID()}.tmp")
		try {
			val output = if (path.fileName.toString().endsWith(".json")) json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(bytes.decodeToString())).encodeToByteArray() else bytes
            Files.write(temporary, output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
			val options = if (replace) {
				arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			} else {
				arrayOf(StandardCopyOption.ATOMIC_MOVE)
			}
			try {
				Files.move(temporary, path, *options)
			} catch (_: AtomicMoveNotSupportedException) {
				if (replace) Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
				else Files.move(temporary, path)
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

	companion object {
		const val STORE_VERSION = 1

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

private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull ?: AgentWorkspaceStore.invalid(name)
private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.requiredInt(name: String): Int = this[name]?.jsonPrimitive?.intOrNull ?: AgentWorkspaceStore.invalid(name)
private fun JsonObject.requiredLong(name: String): Long = this[name]?.jsonPrimitive?.longOrNull ?: AgentWorkspaceStore.invalid(name)
private fun JsonObject.requiredFloat(name: String): Float = this[name]?.jsonPrimitive?.floatOrNull ?: AgentWorkspaceStore.invalid(name)
private fun JsonObject.requiredBoolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull ?: AgentWorkspaceStore.invalid(name)
private fun JsonObject.requiredObject(name: String): JsonObject = this[name]?.jsonObject ?: AgentWorkspaceStore.invalid(name)
private fun JsonObject.optionalObject(name: String): JsonObject = this[name]?.jsonObject ?: JsonObject(emptyMap())
private fun JsonObject.optionalArray(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())
private fun JsonObject.optionalFloat(name: String): Float? = this[name]?.jsonPrimitive?.floatOrNull
private fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
