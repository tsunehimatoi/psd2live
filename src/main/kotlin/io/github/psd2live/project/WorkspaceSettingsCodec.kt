package io.github.psd2live.project

import kotlinx.serialization.json.*

/** Domain setting codecs; independent of Compose state and the MCP transport. */
internal object WorkspaceSettingsCodec {
    private val fillJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encodeFillParameters(value: io.github.psd2live.core.MeshFillParameters): JsonElement =
        fillJson.encodeToJsonElement(value)

    /** Missing groups or fields keep their defaults; a malformed value falls back to [fallback]. */
    fun decodeFillParameters(
        value: JsonElement?, fallback: io.github.psd2live.core.MeshFillParameters = io.github.psd2live.core.MeshFillParameters(),
    ): io.github.psd2live.core.MeshFillParameters =
        value?.let { runCatching { fillJson.decodeFromJsonElement<io.github.psd2live.core.MeshFillParameters>(it) }.getOrNull() }
            ?: fallback

    /** Applies per-group [changes] onto [base]; omitted groups and fields keep their values. Rejects unknown or out-of-range fields. */
    fun mergeFillParameters(
        base: io.github.psd2live.core.MeshFillParameters, changes: JsonObject,
    ): io.github.psd2live.core.MeshFillParameters {
        val current = encodeFillParameters(base).jsonObject
        val merged = buildJsonObject {
            current.forEach { (group, fields) -> put(group, fields) }
            changes.forEach { (group, fields) ->
                val known = requireNotNull(current[group]?.jsonObject) { "Unknown fill parameter group: $group" }
                val update = runCatching { fields.jsonObject }.getOrNull() ?: error("$group must be an object")
                require(update.keys.all { it in known }) { "Unknown $group parameter: ${update.keys - known.keys}" }
                require(update.values.all { (it as? JsonPrimitive)?.doubleOrNull?.isFinite() == true }) { "$group parameters must be numbers" }
                put(group, JsonObject(known + update))
            }
        }
        val result = Json.decodeFromJsonElement<io.github.psd2live.core.MeshFillParameters>(merged)
        val r = io.github.psd2live.core.MeshFillRanges
        fun check(name: String, value: Float, range: ClosedFloatingPointRange<Float>) =
            require(value in range) { "$name is outside its UI range $range" }
        listOf("poisson" to result.poisson.edgeRatio, "quadtree" to result.quadtree.edgeRatio,
            "fractal" to result.fractal.edgeRatio, "paving" to result.paving.edgeRatio)
            .forEach { (group, value) -> check("$group.edgeRatio", value, r.edgeRatio) }
        listOf("poisson" to result.poisson.gradation, "quadtree" to result.quadtree.gradation,
            "fractal" to result.fractal.gradation, "paving" to result.paving.gradation)
            .forEach { (group, value) -> check("$group.gradation", value, r.gradation) }
        check("poisson.jitter", result.poisson.jitter, r.jitter)
        check("quadtree.angle", result.quadtree.angle, r.angle)
        check("fractal.angle", result.fractal.angle, r.angle)
        require(result.paving.maxRows in r.maxRows) { "paving.maxRows is outside its UI range ${r.maxRows}" }
        return result
    }

    fun encodeRigTuning(value: io.github.psd2live.core.RigTuning): JsonObject = buildJsonObject {
        value.toMap().forEach { (id, v) -> put(id, v) }
        // Only a side that has values of its own is written, so documents without keep their settings.
        if (value.left.isNotEmpty()) putJsonObject("left") { value.left.toSortedMap().forEach { (id, v) -> put(id, v) } }
        if (value.right.isNotEmpty()) putJsonObject("right") { value.right.toSortedMap().forEach { (id, v) -> put(id, v) } }
    }

    /** Missing or unknown values keep [fallback]'s; every value is clamped to its range. */
    fun decodeRigTuning(
        value: JsonElement?, fallback: io.github.psd2live.core.RigTuning = io.github.psd2live.core.RigTuning(),
    ): io.github.psd2live.core.RigTuning {
        val obj = (value as? JsonObject) ?: return fallback
        fun side(key: String): Map<String, Float> = (obj[key] as? JsonObject)?.mapNotNull { (k, v) ->
            val field = io.github.psd2live.core.RigTuning.fieldById[k]?.takeIf { k in io.github.psd2live.core.RigTuning.SIDED } ?: return@mapNotNull null
            (v as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }?.let { k to it.coerceIn(field.range) }
        }?.toMap().orEmpty()
        return io.github.psd2live.core.RigTuning.fromMap(
            obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.floatOrNull?.let { k to it } }.toMap(), fallback)
            .copy(left = side("left").ifEmpty { if ("left" in obj) emptyMap() else fallback.left },
                right = side("right").ifEmpty { if ("right" in obj) emptyMap() else fallback.right })
    }

    /** Applies [changes] onto [base]; omitted values keep theirs. Rejects unknown, non-numeric or out-of-range values. */
    fun mergeRigTuning(
        base: io.github.psd2live.core.RigTuning, changes: JsonObject,
    ): io.github.psd2live.core.RigTuning = changes.entries.fold(base) { tuning, (id, value) ->
        if (id == "left" || id == "right") {
            // A side's own values: a number sets one, null clears it and the side takes the shared value again.
            val own = (if (id == "left") tuning.left else tuning.right).toMutableMap()
            for ((key, v) in (value as? JsonObject) ?: error("rigTuning.$id is an object of values")) {
                val field = requireNotNull(io.github.psd2live.core.RigTuning.fieldById[key]?.takeIf { key in io.github.psd2live.core.RigTuning.SIDED }) {
                    "rigTuning.$id.$key does not take a value per side"
                }
                if (v is JsonNull) { own -= key; continue }
                val number = (v as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } ?: error("rigTuning.$id.$key must be a number")
                require(number in field.range) { "rigTuning.$id.$key is outside its UI range ${field.range}" }
                own[key] = number
            }
            return@fold if (id == "left") tuning.copy(left = own) else tuning.copy(right = own)
        }
        val field = requireNotNull(io.github.psd2live.core.RigTuning.fieldById[id]) { "Unknown rigTuning value: $id" }
        val number = (value as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } ?: error("rigTuning.$id must be a number")
        require(number in field.range) { "rigTuning.$id is outside its UI range ${field.range}" }
        field.set(tuning, number)
    }

    fun decodeMouthCurve(value: JsonElement?): io.github.psd2live.core.MouthCurve? = runCatching {
        io.github.psd2live.core.MouthCurve(value!!.jsonArray.map { p ->
            io.github.psd2live.core.MouthCurvePoint(p.jsonObject.getValue("x").jsonPrimitive.float,
                p.jsonObject.getValue("y").jsonPrimitive.float)
        })
    }.getOrNull()

    /** Settings key of the optional atlas budget; absent keeps the legacy atlasSize/texturePadding behaviour. */
    const val ATLAS = "atlas"

    fun encodeAtlasBudget(value: io.github.psd2live.core.AtlasBudget): JsonObject = buildJsonObject {
        put("pageSize", value.pageSize); put("maxPages", value.maxPages); put("padding", value.padding)
    }

    /** The stored budget, or null when [settings] has none. Missing fields fall back to the legacy settings. */
    fun decodeAtlasBudget(settings: JsonObject): io.github.psd2live.core.AtlasBudget? {
        val value = settings[ATLAS] as? JsonObject ?: return null
        fun int(name: String) = value[name]?.let { it.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("Invalid atlas $name") }
        val legacy = legacyAtlasBudget(settings)
        return io.github.psd2live.core.AtlasBudget(int("pageSize") ?: legacy.pageSize, int("maxPages") ?: legacy.maxPages, int("padding") ?: legacy.padding)
    }

    /** The budget the packer works within: the stored one, or the legacy page size and padding with the default page count. */
    fun atlasBudget(settings: JsonObject): io.github.psd2live.core.AtlasBudget = decodeAtlasBudget(settings) ?: legacyAtlasBudget(settings)

    private fun legacyAtlasBudget(settings: JsonObject): io.github.psd2live.core.AtlasBudget {
        val defaults = io.github.psd2live.core.PipelineConfig()
        return io.github.psd2live.core.AtlasBudget(
            pageSize = settings["atlasSize"]?.jsonPrimitive?.intOrNull ?: defaults.atlasSize,
            padding = settings["texturePadding"]?.jsonPrimitive?.intOrNull ?: defaults.texturePadding,
        )
    }

    fun encode(config: io.github.psd2live.core.PipelineConfig): JsonObject = buildJsonObject {
        put("atlasSize", config.atlasSize)
        put("textureUpscale", Json.encodeToJsonElement(config.textureUpscale))
        put("meshSpacing", config.meshSpacing)
        put("meshUnits", config.meshUnits.name)
        // Absent means the canvas trace, so the settings of projects saved before it keep their exact text.
        if (config.meshTrace != io.github.psd2live.core.MeshTrace.CANVAS) put("meshTrace", config.meshTrace.name)
        // Absent means no wrap, likewise.
        if (config.meshWrap != 0f) put("meshWrap", config.meshWrap)
        put("meshOuterMargin", config.meshOuterMargin)
        put("meshEdgeMode", config.meshEdgeMode.name)
        put("meshEdgeWidth", config.meshEdgeWidth)
        put("meshMaxEdgeDistance", config.meshMaxEdgeDistance)
        put("meshInteriorDensity", config.meshInteriorDensity)
        put("meshFillAlgorithm", config.meshFillAlgorithm.name)
        put("meshSuppressBoundaryDiagonals", config.meshSuppressBoundaryDiagonals)
        put("meshFillParameters", encodeFillParameters(config.meshFillParameters))
        putJsonObject("meshOverrides") {
            config.meshOverrides.toSortedMap().forEach { (k, v) ->
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
        putJsonObject("drawOrderOverrides") { config.drawOrderOverrides.toSortedMap().forEach { (k, v) -> put(k, v) } }
        put("drawOrderStep", config.drawOrderStep)
        put("texturePadding", config.texturePadding)
        put("alphaThreshold", config.alphaThreshold)
        put("headStrength", config.headTurnStrength)
        put("bodyStrength", config.bodyStrength)
        put("rigTuning", encodeRigTuning(config.rigTuning))
        // Written only when a component is off, so documents without keep their settings.
        if (config.disabledComponents.isNotEmpty()) putJsonArray("disabledComponents") { config.disabledComponents.sorted().forEach { add(it) } }
        put("meshOnly", config.meshOnly)
        put("generateDeformers", config.generateDeformers)
        put("featureDisplacementEnabled", config.featureDisplacementEnabled)
        put("mouthOutlineEnabled", config.mouthOutlineEnabled)
        put("mouthShape", config.mouthShape)
        putJsonArray("mouthCurve") { config.mouthCurve.points.forEach { p ->
            add(buildJsonObject { put("x", p.x); put("y", p.y) })
        } }
        put("mouthColor", config.mouthColor?.let(::JsonPrimitive) ?: JsonNull)
        put("mouthThickness", config.mouthThickness)
        put("seeThroughBrows", config.seeThroughBrows)
        put("seeThroughEyes", config.seeThroughEyes)
        put("seeThroughOpacity", config.seeThroughOpacity)
        put("exportMotions", config.exportMotions)
        put("motionBasic", config.motionBasic)
        put("motionIdle", config.motionIdle)
        put("motionBlink", config.motionBlink)
        put("motionNod", config.motionNod)
        put("motionShake", config.motionShake)
        put("motionSkeleton", config.motionSkeleton)
        put("generatePhysics", config.generatePhysics)
        put("physicsFrontHair", config.physicsFrontHair)
        put("physicsBackHair", config.physicsBackHair)
        put("physicsEyeJelly", config.physicsEyeJelly)
        put("hairSimulationFront", config.hairSimulationFront)
        put("hairSimulationBack", config.hairSimulationBack)
        put("exportCmo3", config.exportCmo3)
        put("exportMoc3", config.exportMoc3)
        put("exportJson", config.exportJson)
        put("exportCan3", config.exportCan3)
        put("runtimeTarget", config.runtimeTarget.name)
        put("exportHiddenParts", config.exportHiddenParts)
        put("exportHiddenDrawables", config.exportHiddenDrawables)
        put("exportGuideImageParts", config.exportGuideImageParts)
        put("exportIncludePhysics", config.exportIncludePhysics)
        put("exportIncludeUserData", config.exportIncludeUserData)
        put("exportIncludeDisplayInfo", config.exportIncludeDisplayInfo)
        put("exportPixelsPerUnit", config.exportPixelsPerUnit?.let(::JsonPrimitive) ?: JsonNull)
    }
    /** A stored [io.github.psd2live.core.MeshSettings.wrap]: absent or invalid is no wrap. */
    fun decodeWrap(value: JsonElement?): Float = (value as? JsonPrimitive)?.floatOrNull
        ?.takeIf { it.isFinite() }?.coerceIn(io.github.psd2live.core.MeshWrap.range) ?: 0f

    /** Decode the existing v1 setting names without constructing UI state. */
    fun decode(value: JsonObject, base: io.github.psd2live.core.PipelineConfig = io.github.psd2live.core.PipelineConfig()): io.github.psd2live.core.PipelineConfig = base.copy(
        atlasSize = value["atlasSize"]?.jsonPrimitive?.intOrNull ?: base.atlasSize,
        textureUpscale = value["textureUpscale"]?.let { Json.decodeFromJsonElement<io.github.psd2live.core.TextureUpscaleConfig>(it) } ?: base.textureUpscale,
        drawOrderOverrides = value["drawOrderOverrides"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float.coerceIn(0f, 1000f) } ?: base.drawOrderOverrides,
        // A settings payload without a step is a document generated with consecutive orders, whose authored orders assume them.
        drawOrderStep = value["drawOrderStep"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100)
            ?: if ("atlasSize" in value) io.github.psd2live.core.LEGACY_DRAW_ORDER_STEP else base.drawOrderStep,
        texturePadding = value["texturePadding"]?.jsonPrimitive?.intOrNull ?: base.texturePadding,
        meshSpacing = value["meshSpacing"]?.jsonPrimitive?.intOrNull ?: base.meshSpacing,
        // v1 settings written before units measured every length in source pixels.
        meshUnits = value["meshUnits"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { io.github.psd2live.core.MeshUnits.valueOf(it) }.getOrNull() }
            ?: if ("meshSpacing" in value) io.github.psd2live.core.MeshUnits.PIXELS else base.meshUnits,
        // Complete settings without a trace were saved before the texture trace, or chose the canvas one.
        meshTrace = value["meshTrace"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { io.github.psd2live.core.MeshTrace.valueOf(it) }.getOrNull() }
            ?: if ("meshSpacing" in value) io.github.psd2live.core.MeshTrace.CANVAS else base.meshTrace,
        // Complete settings without a wrap wrap nothing.
        meshWrap = value["meshWrap"]?.let(::decodeWrap) ?: if ("meshSpacing" in value) 0f else base.meshWrap,
        meshOuterMargin = value["meshOuterMargin"]?.jsonPrimitive?.floatOrNull ?: base.meshOuterMargin,
        meshEdgeMode = value["meshEdgeMode"]?.jsonPrimitive?.contentOrNull?.let { runCatching { io.github.psd2live.core.MeshEdgeMode.valueOf(it) }.getOrNull() } ?: base.meshEdgeMode,
        meshEdgeWidth = value["meshEdgeWidth"]?.jsonPrimitive?.floatOrNull ?: value["meshInnerMargin"]?.jsonPrimitive?.floatOrNull?.let { (value["meshOuterMargin"]?.jsonPrimitive?.floatOrNull ?: base.meshOuterMargin) + it } ?: base.meshEdgeWidth,
        meshMaxEdgeDistance = value["meshMaxEdgeDistance"]?.jsonPrimitive?.floatOrNull ?: base.meshMaxEdgeDistance,
        meshInteriorDensity = value["meshInteriorDensity"]?.jsonPrimitive?.floatOrNull ?: base.meshInteriorDensity,
        meshFillAlgorithm = value["meshFillAlgorithm"]?.jsonPrimitive?.contentOrNull?.let { runCatching { io.github.psd2live.core.MeshFillAlgorithm.valueOf(it) }.getOrNull() } ?: base.meshFillAlgorithm,
        meshSuppressBoundaryDiagonals = value["meshSuppressBoundaryDiagonals"]?.jsonPrimitive?.booleanOrNull ?: base.meshSuppressBoundaryDiagonals,
        meshFillParameters = decodeFillParameters(value["meshFillParameters"], base.meshFillParameters),
        alphaThreshold = value["alphaThreshold"]?.jsonPrimitive?.intOrNull ?: base.alphaThreshold,
        headTurnStrength = value["headStrength"]?.jsonPrimitive?.floatOrNull ?: base.headTurnStrength,
        bodyStrength = value["bodyStrength"]?.jsonPrimitive?.floatOrNull ?: base.bodyStrength,
        rigTuning = decodeRigTuning(value["rigTuning"] ?: value["bodyTuning"], base.rigTuning),
        disabledComponents = value["disabledComponents"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: base.disabledComponents,
        meshOnly = value["meshOnly"]?.jsonPrimitive?.booleanOrNull ?: base.meshOnly,
        generateDeformers = value["generateDeformers"]?.jsonPrimitive?.booleanOrNull ?: base.generateDeformers,
        featureDisplacementEnabled = value["featureDisplacementEnabled"]?.jsonPrimitive?.booleanOrNull ?: base.featureDisplacementEnabled,
        mouthOutlineEnabled = value["mouthOutlineEnabled"]?.jsonPrimitive?.booleanOrNull ?: base.mouthOutlineEnabled,
        mouthShape = value["mouthShape"]?.jsonPrimitive?.contentOrNull?.takeIf { it in listOf("flat", "smile", "w", "custom") } ?: base.mouthShape,
        mouthCurve = decodeMouthCurve(value["mouthCurve"]) ?: base.mouthCurve,
        mouthColor = if ("mouthColor" in value) value["mouthColor"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..0xFFFFFF } else base.mouthColor,
        mouthThickness = value["mouthThickness"]?.jsonPrimitive?.floatOrNull ?: base.mouthThickness,
        // Complete settings saved before the copies over the bangs make none.
        seeThroughBrows = value["seeThroughBrows"]?.jsonPrimitive?.booleanOrNull ?: if ("atlasSize" in value) false else base.seeThroughBrows,
        seeThroughEyes = value["seeThroughEyes"]?.jsonPrimitive?.booleanOrNull ?: if ("atlasSize" in value) false else base.seeThroughEyes,
        seeThroughOpacity = value["seeThroughOpacity"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() }
            ?.coerceIn(io.github.psd2live.core.SeeThroughLayers.OPACITY_RANGE) ?: base.seeThroughOpacity,
        exportMotions = value["exportMotions"]?.jsonPrimitive?.booleanOrNull ?: base.exportMotions,
        motionBasic = value["motionBasic"]?.jsonPrimitive?.booleanOrNull ?: base.motionBasic,
        motionIdle = value["motionIdle"]?.jsonPrimitive?.booleanOrNull ?: base.motionIdle,
        motionBlink = value["motionBlink"]?.jsonPrimitive?.booleanOrNull ?: base.motionBlink,
        motionNod = value["motionNod"]?.jsonPrimitive?.booleanOrNull ?: base.motionNod,
        motionShake = value["motionShake"]?.jsonPrimitive?.booleanOrNull ?: base.motionShake,
        motionSkeleton = value["motionSkeleton"]?.jsonPrimitive?.booleanOrNull ?: base.motionSkeleton,
        generatePhysics = value["generatePhysics"]?.jsonPrimitive?.booleanOrNull ?: base.generatePhysics,
        physicsFrontHair = value["physicsFrontHair"]?.jsonPrimitive?.booleanOrNull ?: base.physicsFrontHair,
        physicsBackHair = value["physicsBackHair"]?.jsonPrimitive?.booleanOrNull ?: base.physicsBackHair,
        physicsEyeJelly = value["physicsEyeJelly"]?.jsonPrimitive?.booleanOrNull ?: base.physicsEyeJelly,
        hairSimulationFront = value["hairSimulationFront"]?.jsonPrimitive?.booleanOrNull ?: base.hairSimulationFront,
        hairSimulationBack = value["hairSimulationBack"]?.jsonPrimitive?.booleanOrNull ?: base.hairSimulationBack,
        exportCmo3 = value["exportCmo3"]?.jsonPrimitive?.booleanOrNull ?: base.exportCmo3,
        exportMoc3 = value["exportMoc3"]?.jsonPrimitive?.booleanOrNull ?: base.exportMoc3,
        exportJson = value["exportJson"]?.jsonPrimitive?.booleanOrNull ?: base.exportJson,
        exportCan3 = value["exportCan3"]?.jsonPrimitive?.booleanOrNull ?: base.exportCan3,
        runtimeTarget = value["runtimeTarget"]?.jsonPrimitive?.contentOrNull?.let { runCatching { org.umamo.runtime.model.RuntimeTarget.valueOf(it) }.getOrNull() } ?: base.runtimeTarget,
        exportHiddenParts = value["exportHiddenParts"]?.jsonPrimitive?.booleanOrNull ?: base.exportHiddenParts,
        exportHiddenDrawables = value["exportHiddenDrawables"]?.jsonPrimitive?.booleanOrNull ?: base.exportHiddenDrawables,
        exportGuideImageParts = value["exportGuideImageParts"]?.jsonPrimitive?.booleanOrNull ?: base.exportGuideImageParts,
        exportIncludePhysics = value["exportIncludePhysics"]?.jsonPrimitive?.booleanOrNull ?: base.exportIncludePhysics,
        exportIncludeUserData = value["exportIncludeUserData"]?.jsonPrimitive?.booleanOrNull ?: base.exportIncludeUserData,
        exportIncludeDisplayInfo = value["exportIncludeDisplayInfo"]?.jsonPrimitive?.booleanOrNull ?: base.exportIncludeDisplayInfo,
        exportPixelsPerUnit = if ("exportPixelsPerUnit" in value) value["exportPixelsPerUnit"]?.jsonPrimitive?.floatOrNull?.takeIf { it > 0f } else base.exportPixelsPerUnit,
    )
}
