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
    }

    /** Missing or unknown values keep [fallback]'s; every value is clamped to its range. */
    fun decodeRigTuning(
        value: JsonElement?, fallback: io.github.psd2live.core.RigTuning = io.github.psd2live.core.RigTuning(),
    ): io.github.psd2live.core.RigTuning {
        val obj = (value as? JsonObject) ?: return fallback
        return io.github.psd2live.core.RigTuning.fromMap(
            obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.floatOrNull?.let { k to it } }.toMap(), fallback)
    }

    /** Applies [changes] onto [base]; omitted values keep theirs. Rejects unknown, non-numeric or out-of-range values. */
    fun mergeRigTuning(
        base: io.github.psd2live.core.RigTuning, changes: JsonObject,
    ): io.github.psd2live.core.RigTuning = changes.entries.fold(base) { tuning, (id, value) ->
        val field = requireNotNull(io.github.psd2live.core.RigTuning.fieldById[id]) { "Unknown rigTuning value: $id" }
        val number = (value as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } ?: error("rigTuning.$id must be a number")
        require(number in field.range) { "rigTuning.$id is outside its UI range ${field.range}" }
        field.set(tuning, number)
    }

    /** Settings key of the rig recipe ([io.github.psd2live.core.recipe.RigRecipe]); absent in a project saved before it, which reads as legacy. */
    const val RIG = "rig"

    /** The stored form of a modern [value]: the bases and modules it changed. A legacy recipe has none ([encode] leaves the key out). */
    fun encodeRecipe(value: io.github.psd2live.core.recipe.RigRecipe): JsonObject = buildJsonObject {
        val recipe = value.normalized()
        if (recipe.bases.isNotEmpty()) putJsonObject("bases") {
            recipe.bases.forEach { (area, choice) ->
                putJsonObject(area) {
                    put("base", choice.base); put("version", choice.version)
                    if (choice.values.isNotEmpty()) putJsonObject("values") { choice.values.forEach { (id, v) -> put(id, v) } }
                }
            }
        }
        if (recipe.modules.isNotEmpty()) putJsonObject("modules") {
            recipe.modules.forEach { (id, choice) ->
                putJsonObject(id) {
                    choice.on?.let { put("on", it) }
                    put("version", choice.version)
                    if (choice.values.isNotEmpty()) putJsonObject("values") { choice.values.forEach { (knob, v) -> put(knob, v) } }
                }
            }
        }
        if (recipe.moves.isNotEmpty()) putJsonObject("moves") {
            recipe.moves.forEach { (feature, handles) ->
                putJsonObject(feature) { handles.forEach { (handle, move) -> put(handle, buildJsonArray { add(move.dx); add(move.dy) }) } }
            }
        }
    }

    private fun move(value: JsonElement?): io.github.psd2live.core.recipe.FeatureMove? = (value as? JsonArray)?.takeIf { it.size == 2 }?.let { pair ->
        val dx = (pair[0] as? JsonPrimitive)?.floatOrNull; val dy = (pair[1] as? JsonPrimitive)?.floatOrNull
        if (dx == null || dy == null || !dx.isFinite() || !dy.isFinite()) null else io.github.psd2live.core.recipe.FeatureMove(dx, dy)
    }

    private fun numbers(value: JsonElement?): Map<String, Float> =
        (value as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.floatOrNull?.let { k to it } }.toMap()

    /** A stored recipe; areas, bases, modules and values this version does not know are skipped, so a later project still opens. */
    fun decodeRecipe(value: JsonElement?): io.github.psd2live.core.recipe.RigRecipe {
        val obj = value as? JsonObject ?: return io.github.psd2live.core.recipe.RigRecipe.Legacy
        val bases = (obj["bases"] as? JsonObject).orEmpty().mapNotNull { (area, entry) ->
            val choice = entry as? JsonObject ?: return@mapNotNull null
            val base = (choice["base"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            area to io.github.psd2live.core.recipe.BaseChoice(base, (choice["version"] as? JsonPrimitive)?.intOrNull ?: 1, numbers(choice["values"]))
        }.toMap()
        val modules = (obj["modules"] as? JsonObject).orEmpty().mapNotNull { (id, entry) ->
            val choice = entry as? JsonObject ?: return@mapNotNull null
            id to io.github.psd2live.core.recipe.ModuleChoice((choice["on"] as? JsonPrimitive)?.booleanOrNull,
                (choice["version"] as? JsonPrimitive)?.intOrNull ?: 1, numbers(choice["values"]))
        }.toMap()
        val moves = (obj["moves"] as? JsonObject).orEmpty().mapValues { (_, handles) ->
            (handles as? JsonObject).orEmpty().mapNotNull { (handle, v) -> move(v)?.let { handle to it } }.toMap()
        }
        return io.github.psd2live.core.recipe.RigRecipe(true, bases, modules, moves).normalized()
    }

    private fun JsonElement?.objectOrEmpty(what: String): JsonObject = when (this) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> this
        else -> error("$what must be an object")
    }

    /**
     * Applies a `rig` change onto [base]: null returns the project to the legacy recipe; an object upgrades a legacy
     * project and then sets, per area of `bases`, its base and values (null back to the default), and per module of
     * `modules` whether it is `on` and its values (null back to on at the defaults). Values of a kept base or module
     * merge with the ones it had. A module standing for an older setting ([io.github.psd2live.core.recipe.RigModule.setting])
     * is switched through that setting: its `on` comes back in the second value, keyed by the setting. Rejects unknown
     * areas, bases, modules and values, values outside their ranges, and values for a setting's module.
     */
    fun mergeRecipe(
        base: io.github.psd2live.core.recipe.RigRecipe, changes: JsonElement,
    ): Pair<io.github.psd2live.core.recipe.RigRecipe, Map<String, Boolean>> {
        if (changes == JsonNull) return io.github.psd2live.core.recipe.RigRecipe.Legacy to emptyMap()
        val obj = changes as? JsonObject ?: error("rig must be an object or null")
        require(obj.keys.all { it == "bases" || it == "modules" || it == "moves" }) { "rig takes bases, modules and moves" }
        val start = if (base.modern) base else io.github.psd2live.core.recipe.RigRecipe.Modern
        val bases = start.bases.toMutableMap()
        val modules = start.modules.toMutableMap()
        val settings = LinkedHashMap<String, Boolean>()
        fun values(where: String, entry: JsonObject, knob: (String) -> io.github.psd2live.core.recipe.RecipeKnob?) =
            (entry["values"] as? JsonObject).orEmpty().mapValues { (knobId, v) ->
                val descriptor = requireNotNull(knob(knobId)) { "Unknown value of $where: $knobId" }
                val number = (v as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() } ?: error("$where.values.$knobId must be a number")
                require(number in descriptor.range) { "$where.values.$knobId is outside its UI range ${descriptor.range}" }
                number
            }
        obj["bases"].objectOrEmpty("rig.bases").forEach { (areaId, entry) ->
            val area = requireNotNull(io.github.psd2live.core.recipe.RigModules.area(areaId)) { "Unknown rig area: $areaId" }
            if (entry == JsonNull) { bases -= areaId; return@forEach }
            val choice = entry as? JsonObject ?: error("rig.bases.$areaId must be an object or null")
            require(choice.keys.all { it == "base" || it == "version" || it == "values" }) { "rig.bases.$areaId takes base, version and values" }
            val id = (choice["base"] as? JsonPrimitive)?.contentOrNull ?: start.base(areaId).id
            val chosen = requireNotNull(area.bases.firstOrNull { it.id == id }) { "Unknown base for $areaId: $id" }
            val kept = bases[areaId]?.takeIf { it.base == id }?.values.orEmpty()
            bases[areaId] = io.github.psd2live.core.recipe.BaseChoice(id, chosen.version, kept + values("rig.bases.$areaId", choice, chosen::knob))
        }
        obj["modules"].objectOrEmpty("rig.modules").forEach { (id, entry) ->
            val module = requireNotNull(io.github.psd2live.core.recipe.RigModules.module(id)) { "Unknown rig module: $id" }
            if (entry == JsonNull) {
                val setting = module.setting
                if (setting != null) settings[setting] = true else modules.remove(id)
                return@forEach
            }
            val choice = entry as? JsonObject ?: error("rig.modules.$id must be an object or null")
            require(choice.keys.all { it == "on" || it == "version" || it == "values" }) { "rig.modules.$id takes on, version and values" }
            val on = choice["on"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: error("rig.modules.$id.on must be a boolean") }
            val setting = module.setting
            if (setting != null) {
                require("values" !in choice) { "$id has no values" }
                if (on != null) settings[setting] = on
                return@forEach
            }
            val kept = modules[id]
            modules[id] = io.github.psd2live.core.recipe.ModuleChoice(on ?: kept?.on, module.version,
                kept?.values.orEmpty() + values("rig.modules.$id", choice, module::knob))
        }
        // Moved feature handles: [dx, dy] canvas pixels replaces a handle's move, null returns it (or a whole feature) to the fit.
        val moves = start.moves.mapValues { it.value.toMutableMap() }.toMutableMap()
        obj["moves"].objectOrEmpty("rig.moves").forEach { (feature, entry) ->
            require(feature in io.github.psd2live.core.fit.FeatureHandles.features) { "Unknown feature: $feature" }
            if (entry == JsonNull) { moves -= feature; return@forEach }
            val handles = entry as? JsonObject ?: error("rig.moves.$feature must be an object or null")
            val current = moves.getOrPut(feature) { mutableMapOf() }
            handles.forEach { (handle, v) ->
                require(io.github.psd2live.core.fit.FeatureHandles.known(feature, handle)) { "Unknown handle of $feature: $handle" }
                if (v == JsonNull) current -= handle else current[handle] = move(v) ?: error("rig.moves.$feature.$handle must be [dx, dy] or null")
            }
        }
        return io.github.psd2live.core.recipe.RigRecipe(true, bases, modules, moves).normalized() to settings
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
        // Absent means the legacy recipe, so the settings of projects saved before it keep their exact text.
        if (config.recipe.modern) put(RIG, encodeRecipe(config.recipe))
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
        // Complete settings without a recipe were saved before it: they generate as they did.
        recipe = if (RIG in value) decodeRecipe(value[RIG])
            else if ("atlasSize" in value) io.github.psd2live.core.recipe.RigRecipe.Legacy else base.recipe,
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
