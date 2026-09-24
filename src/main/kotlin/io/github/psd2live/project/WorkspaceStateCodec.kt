package io.github.psd2live.project

import io.github.psd2live.core.MeshSettings
import io.github.psd2live.core.MeshFillAlgorithm

import io.github.psd2live.ui.state.*
import org.umamo.runtime.model.ParameterId
import kotlinx.serialization.json.*

/** Explicit durable UI/config schema; excludes live SDK handles, jobs and network state. */
internal object WorkspaceStateCodec {
    /**
     * Stamped into every workspace this build writes. A file that predates the selection-bounds
     * default flipping to off stored that option as `true` whether or not anyone had asked for it,
     * and nothing in the file distinguishes the two. So a file without the revision has the one key
     * ignored on load and the new default applies; the next save stamps the revision, after which
     * the user's own toggle is honoured again.
     */
    private const val VIEW_OPTIONS_REVISION = 1

    private fun decodeMouthCurve(value: JsonElement?): io.github.psd2live.core.MouthCurve? = runCatching {
        io.github.psd2live.core.MouthCurve(value!!.jsonArray.map { p ->
            io.github.psd2live.core.MouthCurvePoint(p.jsonObject.getValue("x").jsonPrimitive.float,
                p.jsonObject.getValue("y").jsonPrimitive.float)
        })
    }.getOrNull()

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
            warpShowNames = booleanOr(obj, "warpShowNames", defaults.warpShowNames),
            warpShowIndices = booleanOr(obj, "warpShowIndices", defaults.warpShowIndices),
            pathShowWidth = booleanOr(obj, "pathShowWidth", defaults.pathShowWidth),
            pathShowHardness = booleanOr(obj, "pathShowHardness", defaults.pathShowHardness),
            filterSelectedOnly = booleanOr(obj, "filterSelectedOnly", defaults.filterSelectedOnly),
            dimUnselected = booleanOr(obj, "dimUnselected", defaults.dimUnselected),
            contextualWarp = booleanOr(obj, "contextualWarp", defaults.contextualWarp),
            showSelectionBounds = if (legacySelectionBounds) defaults.showSelectionBounds
                                  else booleanOr(obj, "showSelectionBounds", defaults.showSelectionBounds),
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
        put("warpShowNames", options.warpShowNames)
        put("warpShowIndices", options.warpShowIndices)
        put("pathShowWidth", options.pathShowWidth)
        put("pathShowHardness", options.pathShowHardness)
        put("filterSelectedOnly", options.filterSelectedOnly)
        put("dimUnselected", options.dimUnselected)
        put("contextualWarp", options.contextualWarp)
        put("showSelectionBounds", options.showSelectionBounds)
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
            layerVisibility = obj["layerVisibility"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap(),
            deformerVisibility = obj["deformerVisibility"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap(),
            isolationSnapshot = obj["isolationSnapshot"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean },
            parameterValues = obj["parameterValues"]?.jsonObject?.map { (id, v) -> ParameterId(id) to v.jsonPrimitive.float }?.toMap() ?: emptyMap(),
            lockedParameters = obj["lockedParameters"]?.jsonArray?.map { ParameterId(it.jsonPrimitive.content) }?.toSet() ?: emptySet(),
        )
    }

    private fun decodeSession(value: JsonElement?, mode: CanvasMode, legacyViewOptions: Boolean): CanvasModeSession {
        val obj = value as? JsonObject
        return CanvasModeSession(
            view = decodeViewOptions(obj?.get("view"), mode.defaultViewOptions(), legacyViewOptions),
            camera = decodeCamera(obj?.get("camera")),
            presentation = decodePresentation(obj?.get("presentation")),
        )
    }

    private fun encodeSession(session: CanvasModeSession, presentation: CanvasPresentation = session.presentation): JsonObject =
        buildJsonObject {
            put("view", encodeViewOptions(session.view))
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
        return EditorWorkspace(
            id = id,
            name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            layoutJson = obj["layout"]?.jsonPrimitive?.contentOrNull,
            hiddenModules = obj["hiddenModules"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty().toSet(),
            canvases = canvases,
            activeCanvasId = activeCanvasId,
        )
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
    fun settings(state: PSD2LiveState): JsonObject = buildJsonObject {
        put("atlasSize", state.atlasSize)
        put("textureUpscale", Json.encodeToJsonElement(state.textureUpscale))
        put("meshSpacing", state.meshSpacing)
        put("meshOuterMargin", state.meshOuterMargin)
        put("meshInnerMargin", state.meshInnerMargin)
        put("meshMaxEdgeDistance", state.meshMaxEdgeDistance)
        put("meshInteriorDensity", state.meshInteriorDensity)
        put("meshFillAlgorithm", state.meshFillAlgorithm.name)
        put("meshSuppressBoundaryDiagonals", state.meshSuppressBoundaryDiagonals)
        putJsonObject("meshOverrides") {
            state.meshOverrides.toSortedMap().forEach { (k, v) ->
                put(k, buildJsonObject {
                    put("outerMargin", v.outerMargin)
                    put("innerMarginEnabled", v.innerMarginEnabled)
                    put("innerMargin", v.innerMargin)
                    put("maxEdgeDistance", v.maxEdgeDistance)
                    put("interiorDensity", v.interiorDensity)
                    put("fillAlgorithm", v.fillAlgorithm.name)
                    put("suppressBoundaryDiagonals", v.suppressBoundaryDiagonals)
                })
            }
        }
        put("texturePadding", state.texturePadding)
        put("alphaThreshold", state.alphaThreshold)
        put("headStrength", state.headStrength)
        put("bodyStrength", state.bodyStrength)
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
        put("motionIdle", state.motionIdle)
        put("motionBlink", state.motionBlink)
        put("motionNod", state.motionNod)
        put("motionShake", state.motionShake)
        put("generatePhysics", state.generatePhysics)
        put("physicsFrontHair", state.physicsFrontHair)
        put("physicsBackHair", state.physicsBackHair)
        put("physicsEyeJelly", state.physicsEyeJelly)
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
        put("modelSettingsExpanded", state.modelSettingsExpanded)

        put("workspaceSplitRatio", state.workspaceSplitRatio)
        put("inspectorCollapsed", state.inspectorCollapsed)
        put("viewOptionsRevision", VIEW_OPTIONS_REVISION)
        putJsonArray("workspaces") { state.workspaces.forEach { workspace -> add(buildJsonObject {
            put("id", workspace.id)
            put("name", workspace.name)
            workspace.layoutJson?.let { put("layout", it) }
            putJsonArray("hiddenModules") { workspace.hiddenModules.sorted().forEach { add(it) } }
            put("activeCanvasId", workspace.activeCanvasId)
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
        put("meshInnerMargin", state.meshInnerMargin)
        put("meshMaxEdgeDistance", state.meshMaxEdgeDistance)
        put("meshInteriorDensity", state.meshInteriorDensity)
        put("meshFillAlgorithm", state.meshFillAlgorithm.name)
        put("meshSuppressBoundaryDiagonals", state.meshSuppressBoundaryDiagonals)
        putJsonObject("meshOverrides") {
            state.meshOverrides.toSortedMap().forEach { (k, v) ->
                put(k, buildJsonObject {
                    put("outerMargin", v.outerMargin)
                    put("innerMarginEnabled", v.innerMarginEnabled)
                    put("innerMargin", v.innerMargin)
                    put("maxEdgeDistance", v.maxEdgeDistance)
                    put("interiorDensity", v.interiorDensity)
                    put("fillAlgorithm", v.fillAlgorithm.name)
                    put("suppressBoundaryDiagonals", v.suppressBoundaryDiagonals)
                })
            }
        }
        put("texturePadding", state.texturePadding)
        put("alphaThreshold", state.alphaThreshold)
        put("headStrength", state.headStrength)
        put("bodyStrength", state.bodyStrength)
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
        put("motionIdle", state.motionIdle)
        put("motionBlink", state.motionBlink)
        put("motionNod", state.motionNod)
        put("motionShake", state.motionShake)
        put("generatePhysics", state.generatePhysics)
        put("physicsFrontHair", state.physicsFrontHair)
        put("physicsBackHair", state.physicsBackHair)
        put("physicsEyeJelly", state.physicsEyeJelly)
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
        put("activeInspectorTab", state.activeInspectorTab.name)
        state.isolationSnapshot?.let { values -> putJsonObject("isolationSnapshot") { values.forEach { (id, v) -> put(id, v) } } }
        putJsonObject("parameterValues") { state.parameterValues.forEach { (id, v) -> put(id.raw, v) } }
        putJsonArray("lockedParameters") { state.lockedParameters.forEach { add(it.raw) } }
        putJsonObject("drawOrderOverrides") { state.drawOrderOverrides.forEach { (k, v) -> put(k, v) } }
        putJsonObject("historyAnnotations") { state.historyAnnotations.forEach { (id, a) ->
            putJsonObject(id) { put("title", a.title); put("note", a.note); put("hidden", a.hidden) }
        } }
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
        hierarchyCollapsed = value["hierarchyCollapsed"]?.jsonPrimitive?.boolean ?: base.hierarchyCollapsed,
        hierarchySearch = value["hierarchySearch"]?.jsonPrimitive?.content ?: base.hierarchySearch,
        drawOrderRulerWidth = value["drawOrderRulerWidth"]?.jsonPrimitive?.float ?: base.drawOrderRulerWidth,
        modelSettingsExpanded = value["modelSettingsExpanded"]?.jsonPrimitive?.boolean ?: base.modelSettingsExpanded,

        workspaceSplitRatio = value["workspaceSplitRatio"]?.jsonPrimitive?.float ?: base.workspaceSplitRatio,
        inspectorCollapsed = value["inspectorCollapsed"]?.jsonPrimitive?.boolean ?: base.inspectorCollapsed,
        workspaces = workspaces,
        activeWorkspaceId = activeWorkspaceId,
        outputPath = value["outputPath"]?.jsonPrimitive?.content ?: base.outputPath,
        atlasSize = value["atlasSize"]?.jsonPrimitive?.int ?: base.atlasSize,
        textureUpscale = value["textureUpscale"]?.let { Json.decodeFromJsonElement<io.github.psd2live.core.TextureUpscaleConfig>(it) } ?: base.textureUpscale,
        meshSpacing = value["meshSpacing"]?.jsonPrimitive?.int ?: base.meshSpacing,
        meshOuterMargin = value["meshOuterMargin"]?.jsonPrimitive?.float ?: base.meshOuterMargin,
        meshInnerMargin = value["meshInnerMargin"]?.jsonPrimitive?.float ?: base.meshInnerMargin,
        meshMaxEdgeDistance = value["meshMaxEdgeDistance"]?.jsonPrimitive?.float ?: base.meshMaxEdgeDistance,
        meshInteriorDensity = value["meshInteriorDensity"]?.jsonPrimitive?.float ?: base.meshInteriorDensity,
        meshFillAlgorithm = value["meshFillAlgorithm"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { MeshFillAlgorithm.valueOf(it) }.getOrNull() } ?: base.meshFillAlgorithm,
        meshSuppressBoundaryDiagonals = value["meshSuppressBoundaryDiagonals"]?.jsonPrimitive?.booleanOrNull ?: base.meshSuppressBoundaryDiagonals,
        meshOverrides = value["meshOverrides"]?.jsonObject?.mapNotNull { (k, v) ->
            val obj = v.jsonObject
            val outerMargin = obj["outerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f
            val innerMarginEnabled = obj["innerMarginEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
            val innerMargin = obj["innerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f
            val maxEdgeDistance = obj["maxEdgeDistance"]?.jsonPrimitive?.floatOrNull ?: 48.0f
            val interiorDensity = obj["interiorDensity"]?.jsonPrimitive?.floatOrNull ?: 48.0f
            val fillAlgorithm = obj["fillAlgorithm"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { MeshFillAlgorithm.valueOf(it) }.getOrNull() } ?: MeshFillAlgorithm.GRADED_POISSON
            val suppressBoundaryDiagonals = obj["suppressBoundaryDiagonals"]?.jsonPrimitive?.booleanOrNull ?: false
            k to MeshSettings(outerMargin, innerMarginEnabled, innerMargin, maxEdgeDistance, interiorDensity,
                fillAlgorithm, suppressBoundaryDiagonals)
        }?.toMap() ?: base.meshOverrides,
        texturePadding = value["texturePadding"]?.jsonPrimitive?.int ?: base.texturePadding,
        alphaThreshold = value["alphaThreshold"]?.jsonPrimitive?.int ?: base.alphaThreshold,
        headStrength = value["headStrength"]?.jsonPrimitive?.float ?: base.headStrength,
        bodyStrength = value["bodyStrength"]?.jsonPrimitive?.float ?: base.bodyStrength,
        meshOnly = value["meshOnly"]?.jsonPrimitive?.boolean ?: base.meshOnly,
        generateDeformers = value["generateDeformers"]?.jsonPrimitive?.boolean ?: base.generateDeformers,
        mouthOutlineEnabled = value["mouthOutlineEnabled"]?.jsonPrimitive?.boolean ?: base.mouthOutlineEnabled,
        mouthShape = value["mouthShape"]?.jsonPrimitive?.content?.takeIf { it in listOf("flat", "smile", "w", "custom") } ?: base.mouthShape,
        mouthCurve = decodeMouthCurve(value["mouthCurve"]) ?: base.mouthCurve,
        mouthColor = if ("mouthColor" in value) value["mouthColor"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..0xFFFFFF } else base.mouthColor,
        mouthThickness = value["mouthThickness"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() }?.coerceIn(0.5f, 8f) ?: base.mouthThickness,
        featureDisplacementEnabled = value["featureDisplacementEnabled"]?.jsonPrimitive?.boolean ?: base.featureDisplacementEnabled,
        exportMotions = value["exportMotions"]?.jsonPrimitive?.boolean ?: base.exportMotions,
        motionIdle = value["motionIdle"]?.jsonPrimitive?.boolean ?: base.motionIdle,
        motionBlink = value["motionBlink"]?.jsonPrimitive?.boolean ?: base.motionBlink,
        motionNod = value["motionNod"]?.jsonPrimitive?.boolean ?: base.motionNod,
        motionShake = value["motionShake"]?.jsonPrimitive?.boolean ?: base.motionShake,
        generatePhysics = value["generatePhysics"]?.jsonPrimitive?.boolean ?: base.generatePhysics,
        physicsFrontHair = value["physicsFrontHair"]?.jsonPrimitive?.boolean ?: base.physicsFrontHair,
        physicsBackHair = value["physicsBackHair"]?.jsonPrimitive?.boolean ?: base.physicsBackHair,
        physicsEyeJelly = value["physicsEyeJelly"]?.jsonPrimitive?.boolean ?: base.physicsEyeJelly,
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
        exportOptionsExpanded = value["exportOptionsExpanded"]?.jsonPrimitive?.boolean ?: base.exportOptionsExpanded,
        motionSubExpanded = value["motionSubExpanded"]?.jsonPrimitive?.boolean ?: base.motionSubExpanded,
        physicsSubExpanded = value["physicsSubExpanded"]?.jsonPrimitive?.boolean ?: base.physicsSubExpanded,
        dynamicsSubExpanded = value["dynamicsSubExpanded"]?.jsonPrimitive?.boolean ?: base.dynamicsSubExpanded,
        projectOutputsExpanded = value["projectOutputsExpanded"]?.jsonPrimitive?.boolean ?: base.projectOutputsExpanded,
        advancedExpanded = value["advancedExpanded"]?.jsonPrimitive?.boolean ?: base.advancedExpanded,
        logPanelExpanded = value["logPanelExpanded"]?.jsonPrimitive?.boolean ?: base.logPanelExpanded,
        logPanelHeight = value["logPanelHeight"]?.jsonPrimitive?.float ?: base.logPanelHeight,
        selectedHistoryNodeId = if ("selectedHistoryNodeId" in value) value["selectedHistoryNodeId"]?.jsonPrimitive?.contentOrNull else base.selectedHistoryNodeId,
        selectedLayerId = if ("selectedLayerId" in value) value["selectedLayerId"]?.jsonPrimitive?.contentOrNull else base.selectedLayerId,
        selectedLayerIds = value["selectedLayerIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            ?: setOfNotNull(value["selectedLayerId"]?.jsonPrimitive?.contentOrNull),
        selectedDeformerId = if ("selectedDeformerId" in value) value["selectedDeformerId"]?.jsonPrimitive?.contentOrNull else base.selectedDeformerId,
        isolatedLayerId = if ("isolatedLayerId" in value) value["isolatedLayerId"]?.jsonPrimitive?.contentOrNull else base.isolatedLayerId,
        parameterSearchQuery = value["parameterSearchQuery"]?.jsonPrimitive?.content ?: base.parameterSearchQuery,
        animationEnabled = value["animationEnabled"]?.jsonPrimitive?.boolean ?: base.animationEnabled,
        mouseTrackingEnabled = value["mouseTrackingEnabled"]?.jsonPrimitive?.boolean ?: base.mouseTrackingEnabled,
        activeInspectorTab = value["activeInspectorTab"]?.jsonPrimitive?.content?.let { runCatching { InspectorTab.valueOf(it) }.getOrNull() } ?: base.activeInspectorTab,
        isolationSnapshot = value["isolationSnapshot"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean },
        parameterValues = value["parameterValues"]?.jsonObject?.map { (id, v) -> ParameterId(id) to v.jsonPrimitive.float }?.toMap() ?: base.parameterValues,
        lockedParameters = value["lockedParameters"]?.jsonArray?.map { ParameterId(it.jsonPrimitive.content) }?.toSet() ?: base.lockedParameters,
        drawOrderOverrides = value["drawOrderOverrides"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float.coerceIn(0f, 1000f) } ?: base.drawOrderOverrides,
        historyAnnotations = value["historyAnnotations"]?.jsonObject?.mapValues { (_, v) ->
            val a = v.jsonObject; HistoryAnnotation(a.getValue("title").jsonPrimitive.content, a.getValue("note").jsonPrimitive.content, a.getValue("hidden").jsonPrimitive.boolean)
        } ?: base.historyAnnotations,
        logEntries = value["logEntries"]?.jsonArray?.map { v -> val l = v.jsonObject
            AppLogEntry(id = l.getValue("id").jsonPrimitive.content, timestamp = java.time.Instant.parse(l.getValue("timestamp").jsonPrimitive.content),
                source = LogSource.valueOf(l.getValue("source").jsonPrimitive.content), level = LogLevel.valueOf(l.getValue("level").jsonPrimitive.content),
                tag = l.getValue("tag").jsonPrimitive.content, message = l.getValue("message").jsonPrimitive.content,
                detail = l["detail"]?.jsonPrimitive?.contentOrNull, imageLabel = l["imageLabel"]?.jsonPrimitive?.contentOrNull,
                imageBytes = l["image"]?.jsonPrimitive?.content?.let { java.util.Base64.getDecoder().decode(it) })
        } ?: base.logEntries,
        ).let { decoded ->
            val savedActive = value["workspaces"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["id"]?.jsonPrimitive?.content == decoded.activeWorkspace.id }
                ?.get("canvases")?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["id"]?.jsonPrimitive?.content == decoded.activeCanvas.id }
            val activeSessionKey = if (decoded.activeCanvas.mode == CanvasMode.EDIT) "editSession" else "previewSession"
            val hasPresentation = savedActive?.containsKey("presentation") == true ||
                (savedActive?.get(activeSessionKey) as? JsonObject)?.containsKey("presentation") == true
            if (hasPresentation) decoded.activeCanvas.presentation.applyTo(decoded)
            else decoded.updateCanvas(decoded.activeCanvas.id) { canvas ->
                canvas.updateSession { it.copy(presentation = CanvasPresentation.capture(decoded)) }
            }
        }.let { decoded ->
            if ("workspaces" in value) decoded
            else decoded.updateActiveWorkspace { workspace ->
                val hidden = workspace.hiddenModules.toMutableSet()
                if (decoded.hierarchyCollapsed) hidden += "hierarchy" else hidden -= "hierarchy"
                if (!decoded.logPanelExpanded) hidden += "log" else hidden -= "log"
                if (decoded.inspectorCollapsed) hidden += INSPECTOR_DOCK_MODULES else hidden -= INSPECTOR_DOCK_MODULES
                workspace.copy(hiddenModules = hidden)
            }
        }
    }
}
