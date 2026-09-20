package io.github.psd2live.project

import io.github.psd2live.core.MeshSettings

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

    /**
     * Decodes the browser-style tab list. A missing `workspaceTabs` key keeps [base]'s tabs (these
     * decode calls are also used to rebuild a config from saved settings); an old
     * `activeWorkspaceTab` name is migrated onto the tab it names among the defaults.
     */
    private fun decodeWorkspaceTabs(value: JsonObject, base: PSD2LiveState): Pair<List<WorkspaceTabState>, String> {
        val array = value["workspaceTabs"]?.jsonArray
        val legacyViewOptions = (value["viewOptionsRevision"]?.jsonPrimitive?.intOrNull ?: 0) < VIEW_OPTIONS_REVISION
        val legacyCamera = if ("canvasZoom" in value || "canvasPanX" in value || "canvasPanY" in value) {
            TabCamera(
                zoom = value["canvasZoom"]?.jsonPrimitive?.floatOrNull ?: 1f,
                panX = value["canvasPanX"]?.jsonPrimitive?.floatOrNull ?: 0f,
                panY = value["canvasPanY"]?.jsonPrimitive?.floatOrNull ?: 0f,
            )
        } else null

        if (array == null) {
            val legacyName = value["activeWorkspaceTab"]?.jsonPrimitive?.contentOrNull ?: return base.workspaceTabs to base.activeWorkspaceTabId
            val kind = when (legacyName) {
                "HIERARCHY", "TOPOLOGY" -> WorkspaceTabKind.EDIT
                "HISTORY" -> WorkspaceTabKind.HISTORY
                else -> WorkspaceTabKind.PREVIEW
            }
            val defaults = defaultWorkspaceTabs().map { tab ->
                if (legacyCamera != null && tab.kind.canvasMode != null) tab.copy(camera = legacyCamera) else tab
            }
            // History is one of the defaults now, so the legacy name needs no extra tab.
            val active = defaults.firstOrNull { it.kind == kind } ?: defaults.first()
            return defaults to active.id
        }

        val parsed = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                ?.let { name -> WorkspaceTabKind.entries.firstOrNull { it.name == name } }
                ?: return@mapNotNull null
            WorkspaceTabState(
                id = id,
                kind = kind,
                ordinal = (obj["ordinal"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1),
                pinned = obj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                view = decodeViewOptions(obj["view"], kind.defaultViewOptions(), legacyViewOptions),
                camera = if (obj["camera"] != null) decodeCamera(obj["camera"]) else (legacyCamera ?: TabCamera()),
            )
        }.distinctBy { it.id }

        // A saved file contributes the pinned tabs' identity -- id, view options, camera -- but not
        // their order or pinning: History, Edit and Preview always lead the strip in that order.
        // Extra history tabs an older build allowed are dropped, the view being a singleton now.
        val defaults = defaultWorkspaceTabs()
        val pinned = listOf(WorkspaceTabKind.HISTORY, WorkspaceTabKind.EDIT, WorkspaceTabKind.PREVIEW).map { kind ->
            (parsed.firstOrNull { it.kind == kind } ?: defaults.first { it.kind == kind })
                .copy(pinned = true, ordinal = 1)
        }
        val pinnedIds = pinned.map { it.id }.toSet()
        val tabs = pinned + parsed.filter { it.id !in pinnedIds && it.kind != WorkspaceTabKind.HISTORY }
        val requested = value["activeWorkspaceTabId"]?.jsonPrimitive?.contentOrNull
        // An id the file no longer carries (a tab closed since the save) lands on the Edit canvas --
        // the tab the app itself opens on -- not on whichever pinned tab now happens to lead.
        val activeId = tabs.firstOrNull { it.id == requested }?.id
            ?: tabs.first { it.kind == WorkspaceTabKind.EDIT }.id
        return tabs to activeId
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
        putJsonObject("meshOverrides") {
            state.meshOverrides.toSortedMap().forEach { (k, v) ->
                put(k, buildJsonObject {
                    put("outerMargin", v.outerMargin)
                    put("innerMarginEnabled", v.innerMarginEnabled)
                    put("innerMargin", v.innerMargin)
                    put("maxEdgeDistance", v.maxEdgeDistance)
                    put("interiorDensity", v.interiorDensity)
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
        putJsonArray("workspaceTabs") { state.workspaceTabs.forEach { tab -> add(buildJsonObject {
            put("id", tab.id)
            put("kind", tab.kind.name)
            put("ordinal", tab.ordinal)
            put("pinned", tab.pinned)
            putJsonObject("view") {
                put("showTexture", tab.view.showTexture)
                put("showMesh", tab.view.showMesh)
                put("showWarp", tab.view.showWarp)
                put("showRotation", tab.view.showRotation)
                put("showDeformPaths", tab.view.showDeformPaths)
                put("warpShowNames", tab.view.warpShowNames)
                put("warpShowIndices", tab.view.warpShowIndices)
                put("pathShowWidth", tab.view.pathShowWidth)
                put("pathShowHardness", tab.view.pathShowHardness)
                put("filterSelectedOnly", tab.view.filterSelectedOnly)
                put("dimUnselected", tab.view.dimUnselected)
                put("contextualWarp", tab.view.contextualWarp)
                put("showSelectionBounds", tab.view.showSelectionBounds)
            }
            putJsonObject("camera") {
                put("zoom", tab.camera.zoom)
                put("panX", tab.camera.panX)
                put("panY", tab.camera.panY)
            }
        }) } }
        put("activeWorkspaceTabId", state.activeWorkspaceTabId)
        put("outputPath", state.outputPath)
        put("atlasSize", state.atlasSize)
        put("textureUpscale", Json.encodeToJsonElement(state.textureUpscale))
        put("meshSpacing", state.meshSpacing)
        put("meshOuterMargin", state.meshOuterMargin)
        put("meshInnerMargin", state.meshInnerMargin)
        put("meshMaxEdgeDistance", state.meshMaxEdgeDistance)
        put("meshInteriorDensity", state.meshInteriorDensity)
        putJsonObject("meshOverrides") {
            state.meshOverrides.toSortedMap().forEach { (k, v) ->
                put(k, buildJsonObject {
                    put("outerMargin", v.outerMargin)
                    put("innerMarginEnabled", v.innerMarginEnabled)
                    put("innerMargin", v.innerMargin)
                    put("maxEdgeDistance", v.maxEdgeDistance)
                    put("interiorDensity", v.interiorDensity)
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
        val (workspaceTabs, activeWorkspaceTabId) = decodeWorkspaceTabs(value, base)
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
        workspaceTabs = workspaceTabs,
        activeWorkspaceTabId = activeWorkspaceTabId,
        outputPath = value["outputPath"]?.jsonPrimitive?.content ?: base.outputPath,
        atlasSize = value["atlasSize"]?.jsonPrimitive?.int ?: base.atlasSize,
        textureUpscale = value["textureUpscale"]?.let { Json.decodeFromJsonElement<io.github.psd2live.core.TextureUpscaleConfig>(it) } ?: base.textureUpscale,
        meshSpacing = value["meshSpacing"]?.jsonPrimitive?.int ?: base.meshSpacing,
        meshOuterMargin = value["meshOuterMargin"]?.jsonPrimitive?.float ?: base.meshOuterMargin,
        meshInnerMargin = value["meshInnerMargin"]?.jsonPrimitive?.float ?: base.meshInnerMargin,
        meshMaxEdgeDistance = value["meshMaxEdgeDistance"]?.jsonPrimitive?.float ?: base.meshMaxEdgeDistance,
        meshInteriorDensity = value["meshInteriorDensity"]?.jsonPrimitive?.float ?: base.meshInteriorDensity,
        meshOverrides = value["meshOverrides"]?.jsonObject?.mapNotNull { (k, v) ->
            val obj = v.jsonObject
            val outerMargin = obj["outerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f
            val innerMarginEnabled = obj["innerMarginEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
            val innerMargin = obj["innerMargin"]?.jsonPrimitive?.floatOrNull ?: 2.0f
            val maxEdgeDistance = obj["maxEdgeDistance"]?.jsonPrimitive?.floatOrNull ?: 48.0f
            val interiorDensity = obj["interiorDensity"]?.jsonPrimitive?.floatOrNull ?: 48.0f
            k to MeshSettings(outerMargin, innerMarginEnabled, innerMargin, maxEdgeDistance, interiorDensity)
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
        )
    }
}
