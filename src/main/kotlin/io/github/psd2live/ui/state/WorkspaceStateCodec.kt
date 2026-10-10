package io.github.psd2live.ui.state

import io.github.psd2live.project.HistoryAnnotation
import io.github.psd2live.project.WorkspaceAuxiliaryCodec
import io.github.psd2live.project.WorkspaceAuxiliaryData

import io.github.psd2live.project.ParameterSnapshot

import io.github.psd2live.project.WorkspaceSettingsCodec.encodeFillParameters
import io.github.psd2live.project.WorkspaceSettingsCodec.decodeFillParameters
import io.github.psd2live.project.WorkspaceSettingsCodec.mergeFillParameters
import io.github.psd2live.project.WorkspaceSettingsCodec.encodeRigTuning
import io.github.psd2live.project.WorkspaceSettingsCodec.decodeRigTuning
import io.github.psd2live.project.WorkspaceSettingsCodec.mergeRigTuning
import io.github.psd2live.project.WorkspaceSettingsCodec.decodeMouthCurve

import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MeshFillAlgorithm
import io.github.psd2live.core.MeshEdgeMode

import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.*
import org.umamo.runtime.model.ParameterId
import kotlinx.serialization.json.*

/** Explicit durable UI/config schema; excludes live SDK handles, jobs and network state. */
internal object WorkspaceStateCodec {
    /** Capture a completed GUI draft without reading the mutable preview model as authority. */
    fun document(state: PSD2LiveState) = io.github.psd2live.project.WorkspaceDocument(
        source = requireNotNull(state.analysis).source,
        layerVisibility = state.documentLayerVisibility.toMap(),
        deletedLayerIds = state.deletedLayerIds.toSet(),
        layerOverrides = state.layerOverrides.toMap(),
        parentOverrides = state.parentOverrides.toMap(),
        rigEdits = state.rigEdits,
        settings = settings(state),
        meshOverrides = state.meshOverrides.toMap(),
        generationSource = state.generationSource,
        meshSource = state.meshSource,
        textureOverrides = state.textureOverrides,
    )
    /**
     * Stamped into every workspace this build writes. A file that predates the selection-bounds
     * default flipping to off stored that option as `true` whether or not anyone had asked for it,
     * and nothing in the file distinguishes the two. So a file without the revision has the one key
     * ignored on load and the new default applies; the next save stamps the revision, after which
     * the user's own toggle is honoured again.
     */
    private const val VIEW_OPTIONS_REVISION = 1

    private fun booleanOr(obj: JsonObject, key: String, fallback: Boolean): Boolean =
        obj[key]?.jsonPrimitive?.booleanOrNull ?: fallback

    private fun decodeViewOptions(
        value: JsonElement?,
        defaults: TabViewOptions = TabViewOptions.Default,
        legacySelectionBounds: Boolean = false,
    ): TabViewOptions {
        val obj = value?.jsonObject ?: return defaults
        return TabViewOptions(
            showTexture = booleanOr(obj, "showTexture", defaults.showTexture),
            showMesh = booleanOr(obj, "showMesh", defaults.showMesh),
            showWarp = booleanOr(obj, "showWarp", defaults.showWarp),
            showRotation = booleanOr(obj, "showRotation", defaults.showRotation),
            showDeformPaths = booleanOr(obj, "showDeformPaths", defaults.showDeformPaths),
            showSkeleton = booleanOr(obj, "showSkeleton", defaults.showSkeleton),
            warpShowNames = booleanOr(obj, "warpShowNames", defaults.warpShowNames),
            warpShowIndices = booleanOr(obj, "warpShowIndices", defaults.warpShowIndices),
            pathShowWidth = booleanOr(obj, "pathShowWidth", defaults.pathShowWidth),
            pathShowHardness = booleanOr(obj, "pathShowHardness", defaults.pathShowHardness),
            filterSelectedOnly = booleanOr(obj, "filterSelectedOnly", defaults.filterSelectedOnly),
            dimUnselected = booleanOr(obj, "dimUnselected", defaults.dimUnselected),
            contextualWarp = booleanOr(obj, "contextualWarp", defaults.contextualWarp),
            showSelectionBounds = if (legacySelectionBounds) defaults.showSelectionBounds
                                  else booleanOr(obj, "showSelectionBounds", defaults.showSelectionBounds),
            sourcePixels = booleanOr(obj, "sourcePixels", defaults.sourcePixels),
            simulationView = obj["simulationView"]?.jsonPrimitive?.contentOrNull?.let(SimulationView::parse) ?: defaults.simulationView,
        )
    }

    private fun decodeCamera(value: JsonElement?): TabCamera {
        val obj = value?.jsonObject ?: return TabCamera()
        return TabCamera(
            zoom = obj["zoom"]?.jsonPrimitive?.floatOrNull ?: 1f,
            panX = obj["panX"]?.jsonPrimitive?.floatOrNull ?: 0f,
            panY = obj["panY"]?.jsonPrimitive?.floatOrNull ?: 0f,
        )
    }

    private fun encodeViewOptions(options: TabViewOptions): JsonObject = buildJsonObject {
        put("showTexture", options.showTexture)
        put("showMesh", options.showMesh)
        put("showWarp", options.showWarp)
        put("showRotation", options.showRotation)
        put("showDeformPaths", options.showDeformPaths)
        put("showSkeleton", options.showSkeleton)
        put("warpShowNames", options.warpShowNames)
        put("warpShowIndices", options.warpShowIndices)
        put("pathShowWidth", options.pathShowWidth)
        put("pathShowHardness", options.pathShowHardness)
        put("filterSelectedOnly", options.filterSelectedOnly)
        put("dimUnselected", options.dimUnselected)
        put("contextualWarp", options.contextualWarp)
        put("showSelectionBounds", options.showSelectionBounds)
        // Written only when on, so workspaces saved before the option keep their bytes.
        if (options.sourcePixels) put("sourcePixels", true)
        if (options.simulationView != SimulationView.REFERENCE) put("simulationView", options.simulationView.jsonName)
    }

    private fun encodeCamera(camera: TabCamera): JsonObject = buildJsonObject {
        put("zoom", camera.zoom)
        put("panX", camera.panX)
        put("panY", camera.panY)
    }

    private fun encodePresentation(p: CanvasPresentation): JsonObject = buildJsonObject {
        put("selectedLayerId", p.selectedLayerId)
        putJsonArray("selectedLayerIds") { p.selectedLayerIds.forEach { add(it) } }
        put("selectedDeformerId", p.selectedDeformerId)
        put("isolatedLayerId", p.isolatedLayerId)
        put("animationEnabled", p.animationEnabled)
        put("mouseTrackingEnabled", p.mouseTrackingEnabled)
        put("smoothMouseTracking", p.smoothMouseTracking)
        putJsonObject("layerVisibility") { p.layerVisibility.forEach { (id, visible) -> put(id, visible) } }
        putJsonObject("deformerVisibility") { p.deformerVisibility.forEach { (id, visible) -> put(id, visible) } }
        p.isolationSnapshot?.let { snapshot -> putJsonObject("isolationSnapshot") { snapshot.forEach { (id, visible) -> put(id, visible) } } }
        putJsonObject("parameterValues") { p.parameterValues.forEach { (id, number) -> put(id.raw, number) } }
        putJsonArray("lockedParameters") { p.lockedParameters.forEach { add(it.raw) } }
    }

    private fun decodePresentation(value: JsonElement?): CanvasPresentation {
        val obj = value as? JsonObject ?: return CanvasPresentation()
        return CanvasPresentation(
            selectedLayerId = obj["selectedLayerId"]?.jsonPrimitive?.contentOrNull,
            selectedLayerIds = obj["selectedLayerIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                ?: setOfNotNull(obj["selectedLayerId"]?.jsonPrimitive?.contentOrNull),
            selectedDeformerId = obj["selectedDeformerId"]?.jsonPrimitive?.contentOrNull,
            isolatedLayerId = obj["isolatedLayerId"]?.jsonPrimitive?.contentOrNull,
            animationEnabled = booleanOr(obj, "animationEnabled", false),
            mouseTrackingEnabled = booleanOr(obj, "mouseTrackingEnabled", true),
            smoothMouseTracking = booleanOr(obj, "smoothMouseTracking", false),
            layerVisibility = obj["layerVisibility"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap(),
            deformerVisibility = obj["deformerVisibility"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap(),
            isolationSnapshot = obj["isolationSnapshot"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean },
            parameterValues = obj["parameterValues"]?.jsonObject?.map { (id, v) -> ParameterId(id) to v.jsonPrimitive.float }?.toMap() ?: emptyMap(),
            lockedParameters = obj["lockedParameters"]?.jsonArray?.map { ParameterId(it.jsonPrimitive.content) }?.toSet() ?: emptySet(),
        )
    }

    private fun decodeSession(value: JsonElement?, mode: CanvasMode, legacyViewOptions: Boolean): CanvasModeSession {
        val obj = value as? JsonObject
        val defaults = mode.defaultViewOptions()
        val session = CanvasModeSession(
            view = decodeViewOptions(obj?.get("view"), defaults, legacyViewOptions),
            camera = decodeCamera(obj?.get("camera")),
            presentation = decodePresentation(obj?.get("presentation")),
            viewMode = obj?.get("viewMode")?.jsonPrimitive?.contentOrNull
                ?.let { name -> EditHierarchyMode.entries.firstOrNull { it.name == name } },
            modeViews = (obj?.get("modeViews") as? JsonObject).orEmpty().mapNotNull { (name, view) ->
                EditHierarchyMode.entries.firstOrNull { it.name == name }
                    ?.let { it to decodeViewOptions(view, defaults, legacyViewOptions) }
            }.toMap(),
        )
        // A reopened canvas starts in Object mode, so its toggles come up as the active set.
        return if (session.viewMode == null) session else session.withHierarchyView(EditHierarchyMode.SELECT)
    }

    private fun encodeSession(session: CanvasModeSession, presentation: CanvasPresentation = session.presentation): JsonObject =
        buildJsonObject {
            put("view", encodeViewOptions(session.view))
            session.viewMode?.let { put("viewMode", it.name) }
            if (session.modeViews.isNotEmpty()) putJsonObject("modeViews") {
                session.modeViews.forEach { (mode, view) -> put(mode.name, encodeViewOptions(view)) }
            }
            put("camera", encodeCamera(session.camera))
            put("presentation", encodePresentation(presentation))
        }

    private fun decodeCanvas(obj: JsonObject, legacyViewOptions: Boolean): CanvasWindowState? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val mode = obj["mode"]?.jsonPrimitive?.contentOrNull
            ?.let { name -> CanvasMode.entries.firstOrNull { it.name == name } }
            ?: CanvasMode.EDIT
        val savedEdit = obj["editSession"] ?: if (mode == CanvasMode.EDIT) obj else null
        val savedPreview = obj["previewSession"] ?: if (mode == CanvasMode.PREVIEW) obj else null
        return CanvasWindowState(
            id = id,
            mode = mode,
            editSession = decodeSession(savedEdit, CanvasMode.EDIT, legacyViewOptions),
            previewSession = decodeSession(savedPreview, CanvasMode.PREVIEW, legacyViewOptions),
        )
    }

    private fun decodeWorkspace(obj: JsonObject, legacyViewOptions: Boolean): EditorWorkspace? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val canvases = obj["canvases"]?.jsonArray?.mapNotNull { element ->
            (element as? JsonObject)?.let { decodeCanvas(it, legacyViewOptions) }
        }?.distinctBy { it.id }.orEmpty().ifEmpty { listOf(defaultEditCanvas()) }
        val activeCanvasId = obj["activeCanvasId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { requested -> canvases.any { it.id == requested } }
            ?: canvases.first().id
        // Older files migrate the focused canvas's pose once; every canvas then shares it.
        val pose = WorkspacePose.capture(obj["pose"]?.let(::decodePresentation)
            ?: canvases.first { it.id == activeCanvasId }.presentation)
            .copy(authoringPose = obj["authoringPose"]?.jsonPrimitive?.booleanOrNull ?: false)
        return EditorWorkspace(
            id = id,
            name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            preset = obj["preset"]?.jsonPrimitive?.contentOrNull
                ?.let { saved -> WorkspacePreset.entries.firstOrNull { it.name == saved } }
                ?: WorkspacePreset.EDIT,
            layoutJson = obj["layout"]?.jsonPrimitive?.contentOrNull,
            hiddenModules = obj["hiddenModules"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet().let { hidden ->
                // Layouts from before the simulation tab gain it beside physics, hidden wherever physics is.
                val layout = obj["layout"]?.jsonPrimitive?.contentOrNull
                if (layout != null && "\"simulation\"" !in layout && "physics" in hidden) hidden + "simulation" else hidden
            },
            sidebarRestore = (obj["sidebarRestore"] as? JsonObject)?.mapNotNull { (side, modules) ->
                (modules as? JsonArray)?.let { array -> side to array.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet() }
            }?.toMap().orEmpty(),
            canvases = canvases,
            activeCanvasId = activeCanvasId,
            pose = pose,
        ).withPose(pose)
    }

    /**
     * Old files stored edit/preview/history as tabs of one session. Those tabs did not keep
     * separate documents, so they collapse into one workspace and the canvas that was in front.
     */
    private fun migrateLegacyTabs(value: JsonObject, legacyViewOptions: Boolean): EditorWorkspace {
        val legacyCamera = if ("canvasZoom" in value || "canvasPanX" in value || "canvasPanY" in value) {
            TabCamera(
                zoom = value["canvasZoom"]?.jsonPrimitive?.floatOrNull ?: 1f,
                panX = value["canvasPanX"]?.jsonPrimitive?.floatOrNull ?: 0f,
                panY = value["canvasPanY"]?.jsonPrimitive?.floatOrNull ?: 0f,
            )
        } else null
        data class LegacyTab(val id: String, val kind: String, val view: TabViewOptions, val camera: TabCamera)
        val tabs = value["workspaceTabs"]?.jsonArray?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val mode = if (kind == "PREVIEW") CanvasMode.PREVIEW else CanvasMode.EDIT
            LegacyTab(
                id = id,
                kind = kind,
                view = decodeViewOptions(obj["view"], mode.defaultViewOptions(), legacyViewOptions),
                camera = if (obj["camera"] != null) decodeCamera(obj["camera"]) else (legacyCamera ?: TabCamera()),
            )
        }.orEmpty()
        val requested = value["activeWorkspaceTabId"]?.jsonPrimitive?.contentOrNull
            ?: value["activeWorkspaceTab"]?.jsonPrimitive?.contentOrNull
        val active = tabs.firstOrNull { it.id == requested }
            ?: when (requested) {
                "HIERARCHY", "TOPOLOGY", "EDIT" -> tabs.firstOrNull { it.kind == "EDIT" }
                "PREVIEW" -> tabs.firstOrNull { it.kind == "PREVIEW" }
                else -> null
            }
            ?: tabs.firstOrNull { it.kind == "EDIT" }
            ?: tabs.firstOrNull { it.kind != "HISTORY" }
        val mode = if (active?.kind == "PREVIEW") CanvasMode.PREVIEW else CanvasMode.EDIT
        val editTab = active?.takeIf { it.kind == "EDIT" } ?: tabs.firstOrNull { it.kind == "EDIT" }
        val previewTab = active?.takeIf { it.kind == "PREVIEW" } ?: tabs.firstOrNull { it.kind == "PREVIEW" }
        val canvas = CanvasWindowState(
            id = PRIMARY_CANVAS_ID,
            mode = mode,
            editSession = CanvasModeSession(
                view = editTab?.view ?: CanvasMode.EDIT.defaultViewOptions(),
                camera = editTab?.camera ?: legacyCamera ?: TabCamera(),
            ),
            previewSession = CanvasModeSession(
                view = previewTab?.view ?: CanvasMode.PREVIEW.defaultViewOptions(),
                camera = previewTab?.camera ?: legacyCamera ?: TabCamera(),
            ),
        )
        return defaultEditorWorkspace().copy(canvases = listOf(canvas), activeCanvasId = canvas.id)
    }

    /**
     * Decodes workspaces. A missing key keeps [base] (these decode calls also rebuild a config
     * from saved settings). Files that still have the old tab list are folded into one workspace.
     */
    private fun decodeWorkspaces(value: JsonObject, base: PSD2LiveState): Pair<List<EditorWorkspace>, String> {
        val legacyViewOptions = (value["viewOptionsRevision"]?.jsonPrimitive?.intOrNull ?: 0) < VIEW_OPTIONS_REVISION
        val array = value["workspaces"]?.jsonArray
        if (array == null) {
            if ("workspaceTabs" !in value && "activeWorkspaceTab" !in value && "activeWorkspaceTabId" !in value) {
                return base.workspaces to base.activeWorkspaceId
            }
            val migrated = migrateLegacyTabs(value, legacyViewOptions)
            return listOf(migrated) to migrated.id
        }
        val parsed = array.mapNotNull { element ->
            (element as? JsonObject)?.let { decodeWorkspace(it, legacyViewOptions) }
        }.distinctBy { it.id }
        if (parsed.isEmpty()) return base.workspaces to base.activeWorkspaceId
        val requested = value["activeWorkspaceId"]?.jsonPrimitive?.contentOrNull
        val activeId = parsed.firstOrNull { it.id == requested }?.id ?: parsed.first().id
        return parsed to activeId
    }

    fun editableIdentity(state: PSD2LiveState): JsonObject = encode(state)
    /**
     * The document settings of [state]: the document codec's own text of its chosen settings, so the two never
     * differ - a revision hashes the settings text, and any difference would make every save commit a spurious
     * history node - followed by the atlas budget and arrangement, written only when set so documents without
     * them keep their settings text and revision.
     */
    fun settings(state: PSD2LiveState): JsonObject = buildJsonObject {
        io.github.psd2live.project.WorkspaceSettingsCodec.encode(state.rawConfig()).forEach { (key, value) -> put(key, value) }
        state.atlasBudget?.let { put(io.github.psd2live.project.WorkspaceSettingsCodec.ATLAS, io.github.psd2live.project.WorkspaceSettingsCodec.encodeAtlasBudget(it)) }
        state.atlasArrangement?.let { put(io.github.psd2live.project.AtlasArrangementCodec.KEY, io.github.psd2live.project.AtlasArrangementCodec.encode(it)) }
    }
    fun encode(state: PSD2LiveState): JsonObject = buildJsonObject {
        put("projectSourceName", state.projectSourceName)
        put("historyZoom", state.historyZoom)
        put("historyPanX", state.historyPanX)
        put("historyPanY", state.historyPanY)
        put("historySearch", state.historySearch)
        put("historyShowHidden", state.historyShowHidden)
        put("hierarchyWidth", state.hierarchyWidth)
        put("hierarchyCollapsed", state.hierarchyCollapsed)
        put("hierarchySearch", state.hierarchySearch)
        put("drawOrderRulerWidth", state.drawOrderRulerWidth)

        put("workspaceSplitRatio", state.workspaceSplitRatio)
        put("inspectorCollapsed", state.inspectorCollapsed)
        put("viewOptionsRevision", VIEW_OPTIONS_REVISION)
        putJsonArray("workspaces") { state.workspaces.forEach { workspace -> add(buildJsonObject {
            put("id", workspace.id)
            put("name", workspace.name)
            put("preset", workspace.preset.name)
            workspace.layoutJson?.let { put("layout", it) }
            putJsonArray("hiddenModules") { workspace.hiddenModules.sorted().forEach { add(it) } }
            if (workspace.sidebarRestore.isNotEmpty()) putJsonObject("sidebarRestore") {
                workspace.sidebarRestore.toSortedMap().forEach { (side, modules) ->
                    putJsonArray(side) { modules.sorted().forEach { add(it) } }
                }
            }
            put("activeCanvasId", workspace.activeCanvasId)
            val pose = if (workspace.id == state.activeWorkspace.id) WorkspacePose.capture(state)
                else workspace.pose ?: WorkspacePose.capture(workspace.activeCanvas.presentation)
            put("pose", encodePresentation(pose.applyTo(CanvasPresentation())))
            put("authoringPose", pose.authoringPose)
            putJsonArray("canvases") { workspace.canvases.forEach { canvas -> add(buildJsonObject {
                put("id", canvas.id)
                put("mode", canvas.mode.name)
                val active = workspace.id == state.activeWorkspace.id && canvas.id == state.activeCanvas.id
                put("editSession", encodeSession(canvas.editSession,
                    if (active && canvas.mode == CanvasMode.EDIT) CanvasPresentation.capture(state) else canvas.editSession.presentation))
                put("previewSession", encodeSession(canvas.previewSession,
                    if (active && canvas.mode == CanvasMode.PREVIEW) CanvasPresentation.capture(state) else canvas.previewSession.presentation))
            }) } }
        }) } }
        put("activeWorkspaceId", state.activeWorkspaceId)
        put("outputPath", state.outputPath)
        put("atlasSize", state.atlasSize)
        put("textureUpscale", Json.encodeToJsonElement(state.textureUpscale))
        put("meshSpacing", state.meshSpacing)
        put("meshOuterMargin", state.meshOuterMargin)
        put("meshEdgeMode", state.meshEdgeMode.name)
        put("meshEdgeWidth", state.meshEdgeWidth)
        put("meshMaxEdgeDistance", state.meshMaxEdgeDistance)
        put("meshInteriorDensity", state.meshInteriorDensity)
        put("meshFillAlgorithm", state.meshFillAlgorithm.name)
        put("meshSuppressBoundaryDiagonals", state.meshSuppressBoundaryDiagonals)
        put("meshFillParameters", encodeFillParameters(state.meshFillParameters))
        put("meshUnits", state.meshUnits.name)
        if (state.meshTrace != io.github.psd2live.core.MeshTrace.CANVAS) put("meshTrace", state.meshTrace.name)
        if (state.meshWrap != 0f) put("meshWrap", state.meshWrap)
        putJsonObject("meshOverrides") {
            state.meshOverrides.toSortedMap().forEach { (k, v) ->
                put(k, buildJsonObject {
                    put("outerMargin", v.outerMargin)
                    put("edgeMode", v.edgeMode.name)
                    put("edgeWidth", v.edgeWidth)
                    put("maxEdgeDistance", v.maxEdgeDistance)
                    put("interiorDensity", v.interiorDensity)
                    put("fillAlgorithm", v.fillAlgorithm.name)
                    put("suppressBoundaryDiagonals", v.suppressBoundaryDiagonals)
                    put("fillParameters", encodeFillParameters(v.fillParameters))
                    if (v.wrap != 0f) put("wrap", v.wrap)
                })
            }
        }
        put("texturePadding", state.texturePadding)
        put("alphaThreshold", state.alphaThreshold)
        put("headStrength", state.headStrength)
        put("bodyStrength", state.bodyStrength)
        put("rigTuning", encodeRigTuning(state.rigTuning))
        put("meshOnly", state.meshOnly)
        put("generateDeformers", state.generateDeformers)
        put("featureDisplacementEnabled", state.featureDisplacementEnabled)
        put("mouthOutlineEnabled", state.mouthOutlineEnabled)
        put("mouthShape", state.mouthShape)
        putJsonArray("mouthCurve") { state.mouthCurve.points.forEach { p ->
            add(buildJsonObject { put("x", p.x); put("y", p.y) })
        } }
        put("mouthColor", state.mouthColor?.let(::JsonPrimitive) ?: JsonNull)
        put("mouthThickness", state.mouthThickness)
        put("exportMotions", state.exportMotions)
        put("motionBasic", state.motionBasic)
        put("motionIdle", state.motionIdle)
        put("motionBlink", state.motionBlink)
        put("motionNod", state.motionNod)
        put("motionShake", state.motionShake)
        put("motionSkeleton", state.motionSkeleton)
        put("generatePhysics", state.generatePhysics)
        put("physicsFrontHair", state.physicsFrontHair)
        put("physicsBackHair", state.physicsBackHair)
        put("physicsEyeJelly", state.physicsEyeJelly)
        put("hairSimulationFront", state.hairSimulationFront)
        put("hairSimulationBack", state.hairSimulationBack)
        put("exportCmo3", state.exportCmo3)
        put("exportMoc3", state.exportMoc3)
        put("exportJson", state.exportJson)
        put("runtimeTarget", state.runtimeTarget.name)
        put("exportHiddenParts", state.exportHiddenParts)
        put("exportHiddenDrawables", state.exportHiddenDrawables)
        put("exportGuideImageParts", state.exportGuideImageParts)
        put("exportIncludePhysics", state.exportIncludePhysics)
        put("exportIncludeUserData", state.exportIncludeUserData)
        put("exportIncludeDisplayInfo", state.exportIncludeDisplayInfo)
        put("exportPixelsPerUnit", state.exportPixelsPerUnit?.let(::JsonPrimitive) ?: JsonNull)
        state.atlasBudget?.let { put(io.github.psd2live.project.WorkspaceSettingsCodec.ATLAS, io.github.psd2live.project.WorkspaceSettingsCodec.encodeAtlasBudget(it)) }
        state.atlasArrangement?.let { put(io.github.psd2live.project.AtlasArrangementCodec.KEY, io.github.psd2live.project.AtlasArrangementCodec.encode(it)) }
        put("exportOptionsExpanded", state.exportOptionsExpanded)
        put("motionSubExpanded", state.motionSubExpanded)
        put("physicsSubExpanded", state.physicsSubExpanded)
        put("dynamicsSubExpanded", state.dynamicsSubExpanded)
        put("projectOutputsExpanded", state.projectOutputsExpanded)
        put("advancedExpanded", state.advancedExpanded)
        put("logPanelExpanded", state.logPanelExpanded)
        put("logPanelHeight", state.logPanelHeight)
        put("selectedHistoryNodeId", state.selectedHistoryNodeId)
        put("selectedLayerId", state.selectedLayerId)
        putJsonArray("selectedLayerIds") { state.selectedLayerIds.forEach { add(it) } }
        put("selectedDeformerId", state.selectedDeformerId)
        put("isolatedLayerId", state.isolatedLayerId)
        put("parameterSearchQuery", state.parameterSearchQuery)
        put("animationEnabled", state.animationEnabled)
        put("mouseTrackingEnabled", state.mouseTrackingEnabled)
        put("smoothMouseTracking", state.smoothMouseTracking)
        put("activeInspectorTab", state.activeInspectorTab.name)
        state.isolationSnapshot?.let { values -> putJsonObject("isolationSnapshot") { values.forEach { (id, v) -> put(id, v) } } }
        putJsonObject("parameterValues") { state.parameterValues.forEach { (id, v) -> put(id.raw, v) } }
        putJsonArray("lockedParameters") { state.lockedParameters.forEach { add(it.raw) } }
        WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData(state.parameterSnapshots, state.historyAnnotations))
            .forEach { (key, value) -> put(key, value) }
        putJsonObject("drawOrderOverrides") { state.drawOrderOverrides.forEach { (k, v) -> put(k, v) } }
        putJsonArray("logEntries") { state.logEntries.forEach { log -> add(buildJsonObject {
            put("id", log.id); put("timestamp", log.timestamp.toString()); put("source", log.source.name)
            put("level", log.level.name); put("tag", log.tag); put("message", log.message); put("detail", log.detail)
            put("imageLabel", log.imageLabel)
            log.imageBytes?.let { put("image", java.util.Base64.getEncoder().encodeToString(it)) }
        }) } }
    }
    fun decode(value: JsonObject, base: PSD2LiveState = PSD2LiveState()): PSD2LiveState {
        val (workspaces, activeWorkspaceId) = decodeWorkspaces(value, base)
        return base.copy(
        projectSourceName = value["projectSourceName"]?.jsonPrimitive?.contentOrNull ?: base.projectSourceName,
        historyZoom = value["historyZoom"]?.jsonPrimitive?.float ?: base.historyZoom,
        historyPanX = value["historyPanX"]?.jsonPrimitive?.float ?: base.historyPanX,
        historyPanY = value["historyPanY"]?.jsonPrimitive?.float ?: base.historyPanY,
        historySearch = value["historySearch"]?.jsonPrimitive?.content ?: base.historySearch,
        historyShowHidden = value["historyShowHidden"]?.jsonPrimitive?.boolean ?: base.historyShowHidden,
        hierarchyWidth = value["hierarchyWidth"]?.jsonPrimitive?.float ?: base.hierarchyWidth,
        hierarchySearch = value["hierarchySearch"]?.jsonPrimitive?.content ?: base.hierarchySearch,
        drawOrderRulerWidth = value["drawOrderRulerWidth"]?.jsonPrimitive?.float ?: base.drawOrderRulerWidth,

        workspaceSplitRatio = value["workspaceSplitRatio"]?.jsonPrimitive?.float ?: base.workspaceSplitRatio,
        workspaces = workspaces,
        activeWorkspaceId = activeWorkspaceId,
        outputPath = value["outputPath"]?.jsonPrimitive?.content ?: base.outputPath,
        atlasSize = value["atlasSize"]?.jsonPrimitive?.int ?: base.atlasSize,
        textureUpscale = value["textureUpscale"]?.let { Json.decodeFromJsonElement<io.github.psd2live.core.TextureUpscaleConfig>(it) } ?: base.textureUpscale,
        meshSpacing = value["meshSpacing"]?.jsonPrimitive?.int ?: base.meshSpacing,
        meshOuterMargin = value["meshOuterMargin"]?.jsonPrimitive?.float ?: base.meshOuterMargin,
        meshEdgeMode = value["meshEdgeMode"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { MeshEdgeMode.valueOf(it) }.getOrNull() } ?: base.meshEdgeMode,
        meshEdgeWidth = value["meshEdgeWidth"]?.jsonPrimitive?.floatOrNull
            ?: value["meshInnerMargin"]?.jsonPrimitive?.floatOrNull?.let { inner ->
                (value["meshOuterMargin"]?.jsonPrimitive?.floatOrNull ?: base.meshOuterMargin) + inner
            } ?: base.meshEdgeWidth,
        meshMaxEdgeDistance = value["meshMaxEdgeDistance"]?.jsonPrimitive?.float ?: base.meshMaxEdgeDistance,
        meshInteriorDensity = value["meshInteriorDensity"]?.jsonPrimitive?.float ?: base.meshInteriorDensity,
        meshFillAlgorithm = value["meshFillAlgorithm"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { MeshFillAlgorithm.valueOf(it) }.getOrNull() } ?: base.meshFillAlgorithm,
        meshSuppressBoundaryDiagonals = value["meshSuppressBoundaryDiagonals"]?.jsonPrimitive?.booleanOrNull ?: base.meshSuppressBoundaryDiagonals,
        meshFillParameters = decodeFillParameters(value["meshFillParameters"], base.meshFillParameters),
        // Settings saved before mesh units were source pixels; keep them so the same meshes come back.
        meshUnits = value["meshUnits"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { io.github.psd2live.core.MeshUnits.valueOf(it) }.getOrNull() }
            ?: if ("meshSpacing" in value) io.github.psd2live.core.MeshUnits.PIXELS else base.meshUnits,
        // Likewise traced from the canvas view before the texture trace.
        meshTrace = value["meshTrace"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { io.github.psd2live.core.MeshTrace.valueOf(it) }.getOrNull() }
            ?: if ("meshSpacing" in value) io.github.psd2live.core.MeshTrace.CANVAS else base.meshTrace,
        meshWrap = value["meshWrap"]?.let(io.github.psd2live.project.WorkspaceSettingsCodec::decodeWrap)
            ?: if ("meshSpacing" in value) 0f else base.meshWrap,
        meshOverrides = value["meshOverrides"]?.jsonObject?.mapNotNull { (k, v) ->
            val obj = v.jsonObject
            val outerMargin = obj["outerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f
            val edgeMode = obj["edgeMode"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { MeshEdgeMode.valueOf(it) }.getOrNull() }
                ?: if (obj["innerMarginEnabled"]?.jsonPrimitive?.booleanOrNull == true) MeshEdgeMode.DOUBLE
                    else MeshEdgeMode.SINGLE
            val edgeWidth = obj["edgeWidth"]?.jsonPrimitive?.floatOrNull
                ?: (outerMargin + (obj["innerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f))
            val maxEdgeDistance = obj["maxEdgeDistance"]?.jsonPrimitive?.floatOrNull ?: 48.0f
            val interiorDensity = obj["interiorDensity"]?.jsonPrimitive?.floatOrNull ?: 48.0f
            val fillAlgorithm = obj["fillAlgorithm"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { MeshFillAlgorithm.valueOf(it) }.getOrNull() } ?: MeshFillAlgorithm.GRADED_POISSON
            val suppressBoundaryDiagonals = obj["suppressBoundaryDiagonals"]?.jsonPrimitive?.booleanOrNull ?: false
            k to MeshSettings(outerMargin, edgeMode, edgeWidth, maxEdgeDistance, interiorDensity,
                fillAlgorithm, suppressBoundaryDiagonals, decodeFillParameters(obj["fillParameters"]),
                io.github.psd2live.project.WorkspaceSettingsCodec.decodeWrap(obj["wrap"]))
        }?.toMap() ?: base.meshOverrides,
        texturePadding = value["texturePadding"]?.jsonPrimitive?.int ?: base.texturePadding,
        alphaThreshold = value["alphaThreshold"]?.jsonPrimitive?.int ?: base.alphaThreshold,
        headStrength = value["headStrength"]?.jsonPrimitive?.float ?: base.headStrength,
        bodyStrength = value["bodyStrength"]?.jsonPrimitive?.float ?: base.bodyStrength,
        rigTuning = decodeRigTuning(value["rigTuning"] ?: value["bodyTuning"], base.rigTuning),
        meshOnly = value["meshOnly"]?.jsonPrimitive?.boolean ?: base.meshOnly,
        generateDeformers = value["generateDeformers"]?.jsonPrimitive?.boolean ?: base.generateDeformers,
        mouthOutlineEnabled = value["mouthOutlineEnabled"]?.jsonPrimitive?.boolean ?: base.mouthOutlineEnabled,
        mouthShape = value["mouthShape"]?.jsonPrimitive?.content?.takeIf { it in listOf("flat", "smile", "w", "custom") } ?: base.mouthShape,
        mouthCurve = decodeMouthCurve(value["mouthCurve"]) ?: base.mouthCurve,
        mouthColor = if ("mouthColor" in value) value["mouthColor"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..0xFFFFFF } else base.mouthColor,
        mouthThickness = value["mouthThickness"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() }?.coerceIn(0.5f, 8f) ?: base.mouthThickness,
        featureDisplacementEnabled = value["featureDisplacementEnabled"]?.jsonPrimitive?.boolean ?: base.featureDisplacementEnabled,
        exportMotions = value["exportMotions"]?.jsonPrimitive?.boolean ?: base.exportMotions,
        motionBasic = value["motionBasic"]?.jsonPrimitive?.boolean ?: base.motionBasic,
        motionIdle = value["motionIdle"]?.jsonPrimitive?.boolean ?: base.motionIdle,
        motionBlink = value["motionBlink"]?.jsonPrimitive?.boolean ?: base.motionBlink,
        motionNod = value["motionNod"]?.jsonPrimitive?.boolean ?: base.motionNod,
        motionShake = value["motionShake"]?.jsonPrimitive?.boolean ?: base.motionShake,
        motionSkeleton = value["motionSkeleton"]?.jsonPrimitive?.boolean ?: base.motionSkeleton,
        generatePhysics = value["generatePhysics"]?.jsonPrimitive?.boolean ?: base.generatePhysics,
        physicsFrontHair = value["physicsFrontHair"]?.jsonPrimitive?.boolean ?: base.physicsFrontHair,
        physicsBackHair = value["physicsBackHair"]?.jsonPrimitive?.boolean ?: base.physicsBackHair,
        physicsEyeJelly = value["physicsEyeJelly"]?.jsonPrimitive?.boolean ?: base.physicsEyeJelly,
        hairSimulationFront = value["hairSimulationFront"]?.jsonPrimitive?.boolean ?: base.hairSimulationFront,
        hairSimulationBack = value["hairSimulationBack"]?.jsonPrimitive?.boolean ?: base.hairSimulationBack,
        exportCmo3 = value["exportCmo3"]?.jsonPrimitive?.boolean ?: base.exportCmo3,
        exportMoc3 = value["exportMoc3"]?.jsonPrimitive?.boolean ?: base.exportMoc3,
        exportJson = value["exportJson"]?.jsonPrimitive?.boolean ?: base.exportJson,
        runtimeTarget = value["runtimeTarget"]?.jsonPrimitive?.content?.let {
            runCatching { org.umamo.runtime.model.RuntimeTarget.valueOf(it) }.getOrNull()
        } ?: base.runtimeTarget,
        exportHiddenParts = value["exportHiddenParts"]?.jsonPrimitive?.boolean ?: base.exportHiddenParts,
        exportHiddenDrawables = value["exportHiddenDrawables"]?.jsonPrimitive?.boolean ?: base.exportHiddenDrawables,
        exportGuideImageParts = value["exportGuideImageParts"]?.jsonPrimitive?.boolean ?: base.exportGuideImageParts,
        exportIncludePhysics = value["exportIncludePhysics"]?.jsonPrimitive?.boolean ?: base.exportIncludePhysics,
        exportIncludeUserData = value["exportIncludeUserData"]?.jsonPrimitive?.boolean ?: base.exportIncludeUserData,
        exportIncludeDisplayInfo = value["exportIncludeDisplayInfo"]?.jsonPrimitive?.boolean ?: base.exportIncludeDisplayInfo,
        exportPixelsPerUnit = if ("exportPixelsPerUnit" in value) {
            value["exportPixelsPerUnit"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0f }
        } else base.exportPixelsPerUnit,
        // A settings payload (it always has atlasSize) without a budget means none; other payloads keep base's.
        atlasBudget = if (io.github.psd2live.project.WorkspaceSettingsCodec.ATLAS in value) io.github.psd2live.project.WorkspaceSettingsCodec.decodeAtlasBudget(value)
            else if ("atlasSize" in value) null else base.atlasBudget,
        atlasArrangement = if (io.github.psd2live.project.AtlasArrangementCodec.KEY in value) io.github.psd2live.project.AtlasArrangementCodec.decode(value)
            else if ("atlasSize" in value) null else base.atlasArrangement,
        exportOptionsExpanded = value["exportOptionsExpanded"]?.jsonPrimitive?.boolean ?: base.exportOptionsExpanded,
        motionSubExpanded = value["motionSubExpanded"]?.jsonPrimitive?.boolean ?: base.motionSubExpanded,
        physicsSubExpanded = value["physicsSubExpanded"]?.jsonPrimitive?.boolean ?: base.physicsSubExpanded,
        dynamicsSubExpanded = value["dynamicsSubExpanded"]?.jsonPrimitive?.boolean ?: base.dynamicsSubExpanded,
        projectOutputsExpanded = value["projectOutputsExpanded"]?.jsonPrimitive?.boolean ?: base.projectOutputsExpanded,
        advancedExpanded = value["advancedExpanded"]?.jsonPrimitive?.boolean ?: base.advancedExpanded,
        logPanelHeight = value["logPanelHeight"]?.jsonPrimitive?.float ?: base.logPanelHeight,
        selectedHistoryNodeId = if ("selectedHistoryNodeId" in value) value["selectedHistoryNodeId"]?.jsonPrimitive?.contentOrNull else base.selectedHistoryNodeId,
        selectedLayerId = if ("selectedLayerId" in value) value["selectedLayerId"]?.jsonPrimitive?.contentOrNull else base.selectedLayerId,
        // Absent means "not part of this payload", as for selectedLayerId: a settings-only decode (every
        // authoring commit runs one) must keep the live multi-selection rather than empty it.
        selectedLayerIds = value["selectedLayerIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            ?: if ("selectedLayerId" in value) setOfNotNull(value["selectedLayerId"]?.jsonPrimitive?.contentOrNull)
            else base.selectedLayerIds,
        selectedDeformerId = if ("selectedDeformerId" in value) value["selectedDeformerId"]?.jsonPrimitive?.contentOrNull else base.selectedDeformerId,
        isolatedLayerId = if ("isolatedLayerId" in value) value["isolatedLayerId"]?.jsonPrimitive?.contentOrNull else base.isolatedLayerId,
        parameterSearchQuery = value["parameterSearchQuery"]?.jsonPrimitive?.content ?: base.parameterSearchQuery,
        animationEnabled = value["animationEnabled"]?.jsonPrimitive?.boolean ?: base.animationEnabled,
        mouseTrackingEnabled = value["mouseTrackingEnabled"]?.jsonPrimitive?.boolean ?: base.mouseTrackingEnabled,
        smoothMouseTracking = value["smoothMouseTracking"]?.jsonPrimitive?.boolean ?: false,
        activeInspectorTab = value["activeInspectorTab"]?.jsonPrimitive?.content?.let { runCatching { InspectorTab.valueOf(it) }.getOrNull() } ?: base.activeInspectorTab,
        isolationSnapshot = value["isolationSnapshot"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean },
        parameterValues = value["parameterValues"]?.jsonObject?.map { (id, v) -> ParameterId(id) to v.jsonPrimitive.float }?.toMap() ?: base.parameterValues,
        lockedParameters = value["lockedParameters"]?.jsonArray?.map { ParameterId(it.jsonPrimitive.content) }?.toSet() ?: base.lockedParameters,
        parameterSnapshots = WorkspaceAuxiliaryCodec.decode(value, WorkspaceAuxiliaryData(base.parameterSnapshots, base.historyAnnotations)).parameterSnapshots,
        drawOrderOverrides = value["drawOrderOverrides"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float.coerceIn(0f, 1000f) } ?: base.drawOrderOverrides,
        historyAnnotations = WorkspaceAuxiliaryCodec.decode(value, WorkspaceAuxiliaryData(base.parameterSnapshots, base.historyAnnotations)).historyAnnotations,
        logEntries = value["logEntries"]?.jsonArray?.mapNotNull { v -> val l = v.jsonObject
            val source = runCatching { LogSource.valueOf(l.getValue("source").jsonPrimitive.content) }.getOrNull() ?: return@mapNotNull null
            val level = runCatching { LogLevel.valueOf(l.getValue("level").jsonPrimitive.content) }.getOrDefault(LogLevel.INFO)
            AppLogEntry(id = l.getValue("id").jsonPrimitive.content, timestamp = java.time.Instant.parse(l.getValue("timestamp").jsonPrimitive.content),
                source = source, level = level,
                tag = l.getValue("tag").jsonPrimitive.content, message = l.getValue("message").jsonPrimitive.content,
                detail = l["detail"]?.jsonPrimitive?.contentOrNull, imageLabel = l["imageLabel"]?.jsonPrimitive?.contentOrNull,
                imageBytes = l["image"]?.jsonPrimitive?.content?.let { java.util.Base64.getDecoder().decode(it) })
        }?.takeLast(LOG_ENTRY_LIMIT) ?: base.logEntries,
        ).let { decoded ->
            val savedActive = value["workspaces"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["id"]?.jsonPrimitive?.content == decoded.activeWorkspace.id }
                ?.get("canvases")?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["id"]?.jsonPrimitive?.content == decoded.activeCanvas.id }
            val activeSessionKey = if (decoded.activeCanvas.mode == CanvasMode.EDIT) "editSession" else "previewSession"
            val hasPresentation = savedActive?.containsKey("presentation") == true ||
                (savedActive?.get(activeSessionKey) as? JsonObject)?.containsKey("presentation") == true
            if (hasPresentation) (decoded.activeWorkspace.pose ?: WorkspacePose.capture(decoded.activeCanvas.presentation)).applyTo(decoded.activeCanvas.presentation.applyTo(decoded))
            else decoded.updateCanvas(decoded.activeCanvas.id) { canvas ->
                canvas.updateSession { it.copy(presentation = CanvasPresentation.capture(decoded)) }
            }
        }.let { decoded ->
            if ("workspaces" in value) decoded
            else decoded.updateActiveWorkspace { workspace ->
                val hidden = workspace.hiddenModules.toMutableSet()
                // Files from before workspaces kept the three title-bar toggles as flags.
                fun flag(key: String) = value[key]?.jsonPrimitive?.booleanOrNull
                flag("hierarchyCollapsed")?.let { if (it) hidden += "hierarchy" else hidden -= "hierarchy" }
                flag("logPanelExpanded")?.let { if (it) hidden -= "log" else hidden += "log" }
                flag("inspectorCollapsed")?.let { if (it) hidden += INSPECTOR_DOCK_MODULES else hidden -= INSPECTOR_DOCK_MODULES }
                workspace.copy(hiddenModules = hidden)
            }
        }
    }
}
