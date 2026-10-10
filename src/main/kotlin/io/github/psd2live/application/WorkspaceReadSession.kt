package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.isEffectivelyVisible
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.PuppetModel
import io.github.psd2live.core.legacy.SupersededEntryNote

/** Ephemeral host indicators only; model, source, settings, history and authored pose come from the read. */
internal data class WorkspaceQueryPresentation(
    val workspaceId: String? = null,
    val inputName: String? = null,
    val busy: Boolean = false,
    val status: String = "ready",
    val selectedLayerId: String? = null,
    val projectFile: String? = null,
    val projectDirty: Boolean = false,
    val projectSaving: Boolean = false,
    val projectSaveError: String? = null,
    val persistenceStatus: String = "memory_only",
    val persistenceError: String? = null,
)

/** Detached, read-only application queries. A concurrent commit or reload cannot mix versions. */
internal class WorkspaceReadSession(
    private val read: WorkspaceReadCapture<RigPreviewModel>,
    private val presentation: WorkspaceQueryPresentation = WorkspaceQueryPresentation(),
) : WorkspaceQueries {
    private fun capture() = read.runtime.capture ?: throw IllegalStateException("No workspace is loaded")

    private val pose: WorkspacePose by lazy {
        val parameters = currentPuppet()?.parameters.orEmpty()
        PreviewSessions.read(parameters, read.runtime.capture?.auxiliary ?: JsonObject(emptyMap()), presentation.workspaceId)
    }

    private val projectSnapshot: WorkspaceProjectSnapshot by lazy { buildSnapshot() }

    private companion object {
        /** Read sessions are captured per call (every editor tick), while the committed document changes rarely. */
        @Volatile var deletedCache: Triple<WorkspaceDocument, PipelineAnalysis, List<WorkspaceLayerSnapshot>>? = null
    }
    override fun snapshot(): WorkspaceProjectSnapshot = projectSnapshot

    override fun sourceMeshComponents(layerId: String): JsonObject {
        val captured = capture()
        val model = captured.model
        WorkspaceArtPrimitives.requireCurrent(captured.document.rigEdits, layerId)
        val source = captured.document.source.layers.firstOrNull { it.id.raw == layerId }
            ?: model.analysis.layers.firstOrNull { it.source.id.raw == layerId }?.source
            ?: throw IllegalArgumentException("Source layer not found: $layerId")
        val components = WorkspacePartitionEdits.componentPlan(model, layerId)?.components.orEmpty()
        return buildJsonObject {
            put("project_id", captured.projectId); put("state", captured.state); put("revision", captured.revision)
            put("layer_id", layerId); put("can_split", components.size > 1); put("count", components.size)
            putJsonArray("components") { components.forEachIndexed { index, component -> add(buildJsonObject {
                put("index", index)
                put("canvas_x", source.bounds.left + component.centerX * source.bounds.width / source.raster.width)
                put("canvas_y", source.bounds.top + component.centerY * source.bounds.height / source.raster.height)
            }) } }
        }
    }

    private fun buildSnapshot(): WorkspaceProjectSnapshot {
        val captured = read.runtime.capture
        val document = captured?.document
        val analysis = captured?.model?.analysis
        val active = analysis?.layers.orEmpty().map { layer ->
            val source = layer.source
            val id = source.id.raw
            val metadata = source as? WorkspaceSourceMetadata
            val deleted = id in document!!.deletedLayerIds
            WorkspaceLayerSnapshot(id, source.name, source.raster.width, source.raster.height,
                source.groupPath, source.order, layer.semantic.tag.name.lowercase(), layer.semantic.side.name.lowercase(),
                layer.semantic.type.name.lowercase(), layer.semantic.parameter, layer.semantic.switchId, layer.semantic.confidence,
                Bounds(source.bounds.left.toFloat(), source.bounds.top.toFloat(),
                    (source.bounds.left + source.bounds.width).toFloat(), (source.bounds.top + source.bounds.height).toFloat()),
                layer.bounds, (document.layerVisibility[id] ?: analysis!!.source.isEffectivelyVisible(source)) && !deleted, deleted,
                metadata?.derived == true, metadata?.sourceAssetId, metadata?.sourceSpatialReferenceId)
        }
        // Deleted artwork remains in the immutable source even when the generator omits it from analysis.
        val deleted = if (document == null || analysis == null) emptyList() else deletedLayers(document, analysis, active)
        return WorkspaceProjectSnapshot(
            projectId = captured?.projectId, revisionId = captured?.revision ?: "unloaded", historyHeadNodeId = captured?.historyHead,
            loaded = captured != null, inputName = presentation.inputName, canvasWidth = document?.source?.widthPx,
            canvasHeight = document?.source?.heightPx, busy = presentation.busy, status = presentation.status,
            selectedLayerId = presentation.selectedLayerId, layers = active + deleted,
            parameters = currentPuppet()?.parameters.orEmpty().map { parameter ->
                WorkspaceParameterSnapshot(parameter.id.raw, parameter.name, parameter.min, parameter.max, parameter.default,
                    pose.values[parameter.id] ?: parameter.default, parameter.kind.name.lowercase())
            }, projectFile = presentation.projectFile, projectDirty = captured?.dirty == true || presentation.projectDirty,
            projectSaving = presentation.projectSaving, projectSaveError = presentation.projectSaveError,
            persistenceStatus = presentation.persistenceStatus, persistenceError = presentation.persistenceError,
            state = read.runtime.state,
        )
    }

    /** Classifying soft-deleted artwork is expensive; one immutable document and analysis always yield the same entries. */
    private fun deletedLayers(document: WorkspaceDocument, analysis: PipelineAnalysis,
                              active: List<WorkspaceLayerSnapshot>): List<WorkspaceLayerSnapshot> {
        deletedCache?.let { (cachedDocument, cachedAnalysis, layers) ->
            if (cachedDocument === document && cachedAnalysis === analysis) return layers
        }
        val included = active.mapTo(HashSet()) { it.id }
        val config = document.config()
        val missingDeleted = document.deletedLayerIds - included
        val layers = document.source.layers.filter { source ->
            source.id.raw in missingDeleted || "${source.id.raw}:l" in missingDeleted || "${source.id.raw}:r" in missingDeleted
        }.flatMap { source ->
            if (config.rigEdits.importedCmo3 != null) listOf(LayerClassifier.classify(source, config.alphaThreshold))
            else CharacterAnalyzer.expandLayer(CharacterAnalyzer.classify(source, config), config,
                MeshResolution.unitScale(config, document.source))
        }.filter { it.source.id.raw in missingDeleted }.map { classified ->
            val source = classified.source
            val id = source.id.raw
            val semantic = classified.semantic
            val bounds = Bounds(source.bounds.left.toFloat(), source.bounds.top.toFloat(),
                (source.bounds.left + source.bounds.width).toFloat(), (source.bounds.top + source.bounds.height).toFloat())
            val metadata = source as? WorkspaceSourceMetadata
            WorkspaceLayerSnapshot(id, source.name, source.raster.width, source.raster.height, source.groupPath, source.order,
                semantic.tag.name.lowercase(), semantic.side.name.lowercase(), semantic.type.name.lowercase(), semantic.parameter, semantic.switchId,
                semantic.confidence, bounds, classified.bounds, false, true, metadata?.derived == true, metadata?.sourceAssetId, metadata?.sourceSpatialReferenceId)
        }
        deletedCache = Triple(document, analysis, layers)
        return layers
    }

    override fun history(): WorkspaceHistorySnapshot {
        val history = requireNotNull(read.history) { "No workspace is loaded" }
        return WorkspaceHistorySnapshot(history.headNodeId, history.selections.map { selection ->
            val node = selection.node
            WorkspaceHistoryNodeSnapshot(node.id, node.parentId, node.revisionId, node.summary, node.actor,
                node.taskId, node.createdAt.toString(), node.id == history.headNodeId)
        })
    }

    override fun currentPuppet(): PuppetModel? = read.runtime.capture?.model?.rig?.puppet
    override fun skeletonSpec(): SkeletonSpec? = read.runtime.capture?.document?.rigEdits?.skeleton
    override fun motionClips(): List<MotionClip> = read.runtime.capture?.document?.rigEdits?.motionClips.orEmpty()
    override fun projectSettings(): JsonObject {
        val document = capture().document
        return WorkspaceSettingsCodec.encode(WorkspaceSettingsCodec.decode(document.settings).copy(meshOverrides = document.meshOverrides))
    }
    override fun listSwings(): List<RigSwingEdit> = capture().document.rigEdits.swingEdits
    override fun generatedOverrideIssues(): List<GeneratedOverrideIssue> = read.runtime.capture?.model?.rig?.overrideIssues.orEmpty()
    override fun supersededEntryNotes(): List<SupersededEntryNote> = read.runtime.capture?.model?.rig?.supersededEntryNotes.orEmpty()
    override fun regenerationIssues(): List<io.github.psd2live.core.RigRegeneration.Issue> =
        read.runtime.capture?.document?.rigEdits?.authoringJournal?.lastOrNull(io.github.psd2live.core.RigCheckpoint::isRecord)
            ?.let(io.github.psd2live.core.RigCheckpoint::issues).orEmpty()
    override fun staleRegenerations(): List<io.github.psd2live.core.RigRegeneration.Issue> =
        read.runtime.capture?.model?.let { io.github.psd2live.core.RigRegenerationCheckpoint.staleMerges(it) }.orEmpty()
    override fun skeletonBindingIssues(): List<io.github.psd2live.core.quality.SkeletonBindingIssue> {
        val captured = read.runtime.capture ?: return emptyList()
        return io.github.psd2live.core.quality.SkeletonBindingQuality.issues(captured.model.rig.puppet, captured.document.rigEdits.skeleton)
    }
    override fun listSimulations(): List<io.github.psd2live.core.sim.RigSimEdit> = capture().document.rigEdits.simEdits
    override fun physicsFps(): Int = capture().document.rigEdits.physicsFps
    override fun previewSession(): JsonObject = PreviewSessions.encode(pose)
    override fun sampleSourceColor(layerId: String, x: Int, y: Int): List<Int> = capture().document.sampleSourceColor(layerId, x, y)

    override fun layerMeshSettings(layerId: String): JsonObject {
        val captured = capture()
        require(captured.document.source.layers.any { it.id.raw == layerId } ||
            captured.model.analysis.layers.any { it.source.id.raw == layerId }) { "Layer not found: $layerId" }
        val settings = captured.document.meshOverrides[layerId] ?: captured.document.config().defaultMeshSettings(
            captured.model.analysis.layers.firstOrNull { it.source.id.raw == layerId }?.semantic?.tag)
        return buildJsonObject {
            put("overridden", layerId in captured.document.meshOverrides)
            put("outerMargin", settings.outerMargin); put("edgeMode", settings.edgeMode.name)
            put("edgeWidth", settings.edgeWidth); put("maxEdgeDistance", settings.maxEdgeDistance)
            put("interiorDensity", settings.interiorDensity); put("fillAlgorithm", settings.fillAlgorithm.name)
            put("suppressBoundaryDiagonals", settings.suppressBoundaryDiagonals)
            put("fillParameters", WorkspaceSettingsCodec.encodeFillParameters(settings.fillParameters))
            put("wrap", settings.wrap)
        }
    }

    override fun solveSkeletonPose(request: JsonObject): JsonObject {
        val captured = capture()
        val spec = requireNotNull(skeletonSpec()?.takeIf { it.enabled }) { "No enabled skeleton is available" }
        val id = request.getValue("bone_id").jsonPrimitive.content
        val bones = SkeletonPoseSolver.posed(captured.model.rig.puppet, spec, pose.values)
        require(bones.any { it.bone.id == id }) { "Poseable bone not found: $id" }
        val target = request.getValue("target").jsonArray
        require(target.size == 2) { "Pose target must contain x and y" }
        val x = target[0].jsonPrimitive.float; val y = target[1].jsonPrimitive.float
        require(x.isFinite() && y.isFinite()) { "Pose target must be finite" }
        val ik = request["ik"]?.jsonPrimitive?.booleanOrNull ?: false
        val values = SkeletonPoseSolver.drag(spec, bones, BoneHit(id, tip = ik), x, y, pose.values, ik)
        return buildJsonObject {
            put("state", captured.state); put("project_id", captured.projectId)
            putJsonObject("values") { values.forEach { (parameter, value) -> put(parameter.raw, value) } }
        }
    }

    override fun getObject(target: WorkspaceKeyformTargetRef): WorkspaceObjectSnapshot {
        val rig = capture().model.rig
        val puppet = rig.puppet
        val kind = RigTargetKind.fromString(target.kind)

        return when (kind) {
            RigTargetKind.WARP_DEFORMER, RigTargetKind.ROTATION_DEFORMER -> {
                val deformer = puppet.deformers.firstOrNull { it.id.raw == target.id }
                    ?: throw IllegalArgumentException("Deformer not found: ${target.id}")
                when (deformer) {
                    is Deformer.Warp -> {
                        val grid = deformer.geometryGrid
                        val topo = mapOf(
                            "type" to "warp",
                            "rows" to deformer.rows.toString(),
                            "columns" to deformer.columns.toString(),
                            "controlPointsCount" to ((deformer.rows + 1) * (deformer.columns + 1)).toString(),
                            "isQuadTransform" to deformer.isQuadTransform.toString(),
                        )
                        val geoSnapshot = grid?.let { g ->
                            WorkspaceObjectGeometrySnapshot(
                                axes = g.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                                keyformCount = g.cells.size,
                                cells = g.cells.map { cell ->
                                    val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
                                    WorkspaceObjectCellSnapshot(
                                        coordinate = coord,
                                        controlPoints = cell.form.controlPoints.toList(),
                                    )
                                },
                            )
                        }
                        val channelSnapshots = deformer.channelGrids.gridsByChannel.map { (ch, track) ->
                            WorkspaceObjectChannelTrackSnapshot(
                                channel = ch.name.lowercase(),
                                staticValue = when (ch) {
                                    FormChannel.OPACITY -> deformer.opacity.toString()
                                    FormChannel.MULTIPLY_COLOR -> deformer.multiplyColor.toString()
                                    FormChannel.SCREEN_COLOR -> deformer.screenColor.toString()
                                    else -> ""
                                },
                                axes = track.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                                keyformCount = track.cells.size,
                            )
                        }
                        WorkspaceObjectSnapshot(
                            target = target,
                            name = deformer.name,
                            parentId = deformer.parent?.raw,
                            partId = deformer.partId?.raw,
                            visible = deformer.isVisible,
                            topologyInfo = topo,
                            geometry = geoSnapshot,
                            channels = channelSnapshots,
                        )
                    }
                    is Deformer.Rotation -> {
                        val grid = deformer.geometryGrid
                        val topo = mapOf(
                            "type" to "rotation",
                            "baseAngle" to deformer.baseAngle.toString(),
                        )
                        val geoSnapshot = grid?.let { g ->
                            WorkspaceObjectGeometrySnapshot(
                                axes = g.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                                keyformCount = g.cells.size,
                                cells = g.cells.map { cell ->
                                    val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
                                    WorkspaceObjectCellSnapshot(
                                        coordinate = coord,
                                        originX = cell.form.originX,
                                        originY = cell.form.originY,
                                        angle = cell.form.angle,
                                        scale = cell.form.scale,
                                    )
                                },
                            )
                        }
                        val channelSnapshots = deformer.channelGrids.gridsByChannel.map { (ch, track) ->
                            WorkspaceObjectChannelTrackSnapshot(
                                channel = ch.name.lowercase(),
                                staticValue = when (ch) {
                                    FormChannel.OPACITY -> deformer.opacity.toString()
                                    FormChannel.MULTIPLY_COLOR -> deformer.multiplyColor.toString()
                                    FormChannel.SCREEN_COLOR -> deformer.screenColor.toString()
                                    FormChannel.FLIP_X -> deformer.flipX.toString()
                                    FormChannel.FLIP_Y -> deformer.flipY.toString()
                                    else -> ""
                                },
                                axes = track.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                                keyformCount = track.cells.size,
                            )
                        }
                        WorkspaceObjectSnapshot(
                            target = target,
                            name = deformer.name,
                            parentId = deformer.parent?.raw,
                            partId = deformer.partId?.raw,
                            visible = deformer.isVisible,
                            topologyInfo = topo,
                            geometry = geoSnapshot,
                            channels = channelSnapshots,
                        )
                    }
                }
            }
            RigTargetKind.ART_MESH -> {
                val drawable = puppet.findDrawable(target.id)
                    ?: puppet.drawables.firstOrNull { rig.layerIdByDrawableId[it.id.raw] == target.id }
                    ?: throw IllegalArgumentException("Drawable not found: ${target.id}")
                val grid = drawable.geometryGrid
                val topo = mapOf(
                    "type" to "art_mesh",
                    "vertexCount" to (drawable.mesh?.vertexCount ?: 0).toString(),
                    "triangleCount" to (drawable.mesh?.triangleCount ?: 0).toString(),
                    "layerId" to (rig.layerIdByDrawableId[drawable.id.raw] ?: ""),
                )
                val geoSnapshot = grid?.let { g ->
                    WorkspaceObjectGeometrySnapshot(
                        axes = g.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                        keyformCount = g.cells.size,
                        cells = g.cells.map { cell ->
                            val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
                            WorkspaceObjectCellSnapshot(
                                coordinate = coord,
                                positionDeltas = cell.form.positionDeltas.toList(),
                            )
                        },
                    )
                }
                val channelSnapshots = drawable.channelGrids.gridsByChannel.map { (ch, track) ->
                    WorkspaceObjectChannelTrackSnapshot(
                        channel = ch.name.lowercase(),
                        staticValue = when (ch) {
                            FormChannel.OPACITY -> drawable.opacity.toString()
                            FormChannel.DRAW_ORDER -> drawable.drawOrder.toString()
                            FormChannel.MULTIPLY_COLOR -> drawable.multiplyColor.toString()
                            FormChannel.SCREEN_COLOR -> drawable.screenColor.toString()
                            else -> ""
                        },
                        axes = track.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                        keyformCount = track.cells.size,
                    )
                }
                WorkspaceObjectSnapshot(
                    target = target,
                    name = drawable.name,
                    parentId = drawable.parentDeformerId?.raw,
                    partId = null,
                    visible = drawable.isVisible,
                    topologyInfo = topo,
                    geometry = geoSnapshot,
                    channels = channelSnapshots,
                )
            }
            RigTargetKind.PART -> {
                val part = puppet.parts.firstOrNull { it.id.raw == target.id }
                    ?: throw IllegalArgumentException("Part not found: ${target.id}")
                val channelSnapshots = part.channelGrids.gridsByChannel.map { (ch, track) ->
                    WorkspaceObjectChannelTrackSnapshot(
                        channel = ch.name.lowercase(),
                        staticValue = when (ch) {
                            FormChannel.OPACITY -> part.composite.opacity.toString()
                            FormChannel.DRAW_ORDER -> part.drawOrder.toString()
                            FormChannel.MULTIPLY_COLOR -> part.composite.multiplyColor.toString()
                            FormChannel.SCREEN_COLOR -> part.composite.screenColor.toString()
                            else -> ""
                        },
                        axes = track.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                        keyformCount = track.cells.size,
                    )
                }
                WorkspaceObjectSnapshot(
                    target = target,
                    name = part.name,
                    parentId = null,
                    partId = part.id.raw,
                    visible = part.isVisible,
                    topologyInfo = mapOf("type" to "part", "childrenCount" to part.children.size.toString()),
                    geometry = null,
                    channels = channelSnapshots,
                )
            }
            RigTargetKind.GLUE -> {
                val glue = puppet.glues.firstOrNull { if (target.glueId != null) it.id == target.glueId
                    else it.id == target.id || (it.meshA.raw == target.id && it.meshB.raw == target.secondaryId) }
                    ?: throw IllegalArgumentException("Glue not found: ${target.id} -> ${target.secondaryId}")
                val channelSnapshots = glue.channelGrids.gridsByChannel.map { (ch, track) ->
                    WorkspaceObjectChannelTrackSnapshot(
                        channel = ch.name.lowercase(),
                        staticValue = glue.intensity.toString(),
                        axes = track.axes.map { WorkspaceObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
                        keyformCount = track.cells.size,
                    )
                }
                WorkspaceObjectSnapshot(
                    target = target,
                    name = "Glue_${glue.meshA.raw}_${glue.meshB.raw}",
                    parentId = null,
                    partId = null,
                    visible = true,
                    topologyInfo = mapOf("type" to "glue", "meshA" to glue.meshA.raw, "meshB" to glue.meshB.raw),
                    geometry = null,
                    channels = channelSnapshots,
                )
            }
        }
    }

    override fun listRigObjectSummaries(): List<JsonObject> {
        val rig=capture().model.rig
        val model=rig.puppet
        fun record(kind: String, id: String, name: String, parent: String?, layer: String? = null) =
            buildJsonObject {
                put("kind",JsonPrimitive(kind));put("id",JsonPrimitive(id))
                put("name",JsonPrimitive(name));put("parentId",JsonPrimitive(parent))
                layer?.let { put("layerId",JsonPrimitive(it)) }
            }
        return model.drawables.map { record("mesh",it.id.raw,it.name,it.parentDeformerId?.raw,rig.layerIdByDrawableId[it.id.raw]) } +
            model.deformers.map { record(if(it is Deformer.Warp) "warp" else "rotation",it.id.raw,it.name,it.parent?.raw) } +
            model.parts.map { p -> record("part",p.id.raw,p.name,model.parts.firstOrNull { org.umamo.runtime.model.OrgChild.Part(p.id) in it.children }?.id?.raw) }
    }

    fun listRigObjects(): List<WorkspaceKeyformTargetRef> {
        val puppet = capture().model.rig.puppet
        return puppet.drawables.map { WorkspaceKeyformTargetRef("mesh", it.id.raw) } +
            puppet.deformers.map { WorkspaceKeyformTargetRef(if (it is Deformer.Warp) "warp" else "rotation", it.id.raw) } +
            puppet.parts.map { WorkspaceKeyformTargetRef("part", it.id.raw) }
    }

    override fun proposeSkeleton(): SkeletonSpec {
        val preview = capture().model
        return SkeletonAutoBuilder.build(preview.analysis, preview.rig)
    }

    override fun inspectRigGeometry(arguments: JsonObject): JsonObject {
        val captured = capture()
        return WorkspaceRigGeometry.inspect(captured.model.rig.puppet, arguments, captured.revision, captured.document.rigEdits)
    }

    override fun listPhysics(): List<PhysicsGroup> {
        val captured = capture()
        return WorkspaceDocumentEdits.physicsCatalog(captured.document, captured.model)
    }

    override fun reportSimulation(id: String, hold: Float, release: Float, wind: Pair<Float, Float>?,
                                  progress: (Float) -> Unit, cancelled: () -> Boolean): JsonObject {
        val captured = capture()
        val edit = requireNotNull(captured.document.rigEdits.simEdits.firstOrNull { it.id == id }) { "Simulation not found: $id" }
        return io.github.psd2live.core.sim.SimAuthoring.report(captured.model.rig.puppet, edit, hold, release, wind, progress, cancelled)
    }

    override fun compareSimulation(id: String, motions: List<String>, progress: (Float) -> Unit, cancelled: () -> Boolean): JsonObject {
        val captured = capture()
        val results = io.github.psd2live.core.sim.SimCompare.compare(captured.document.rigEdits,
            io.github.psd2live.core.sim.SimAuthoring.AuthoredRigs(captured.model::authoredPuppet), id, motions,
            progress = progress, cancelled = cancelled)
        return buildJsonObject {
            put("id", id)
            putJsonArray("motions") { for (result in results) add(io.github.psd2live.core.sim.SimMotionCheck(result.motion, result.check).toJson()) }
        }
    }

    override fun simulatePhysics(arguments: JsonObject, progress: (Float) -> Unit, cancelled: () -> Boolean): JsonObject {
        val captured = capture()
        val config = captured.document.config()
        return io.github.psd2live.core.PhysicsSimulation.run(WorkspaceDocumentEdits.physicsCatalog(captured.document, captured.model),
            captured.model.rig.puppet.parameters, arguments, config.generatePhysics, captured.document.rigEdits.physicsFps, progress, cancelled)
    }
}
