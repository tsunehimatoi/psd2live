package io.github.psd2live.application

import io.github.psd2live.algorithms.shadow.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*

/** Pure candidate edits for world lighting and cast/form shadows backed by the modifier stack. */
internal object WorkspaceShadowEdits {
    val supported = setOf("shadow_world_set", "shadow_auto_generate", "shadow_put", "shadow_delete", "shadow_apply")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel): WorkspaceDocument {
        val request = operation.request
        return when (operation.operation) {
            "shadow_world_set" -> setWorld(document, model, request)
            "shadow_auto_generate" -> autoGenerate(document, model, request)
            "shadow_put" -> putShadow(document, model, request)
            "shadow_delete" -> deleteShadow(document, request)
            "shadow_apply" -> WorkspaceModifierEdits.apply(
                WorkspaceDocumentOperation("modifier_apply", buildJsonObject {
                    put("id", request.text("id"))
                    put("scope", "selected")
                }),
                document,
                model,
            )
            else -> throw IllegalArgumentException("Unsupported shadow operation: ${operation.operation}")
        }
    }

    fun readStack(document: WorkspaceDocument): RigModifierStack =
        document.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: RigModifierStack()

    fun findWorld(stack: RigModifierStack): RigModifierInstance? =
        stack.instances.lastOrNull { it.type == ShadowAlgorithmPlugin.TYPE_WORLD }

    private fun ensureWorld(stack: RigModifierStack, model: RigPreviewModel, config: ShadowWorldConfig): RigModifierStack {
        val existing = findWorld(stack)
        return if (existing != null) {
            stack.update(existing.id) { it.copy(config = config.toJson(), enabled = true) }
        } else {
            val anchor = requireNotNull(model.rig.puppet.drawables.firstOrNull()?.id?.raw) { "Model has no meshes for world lighting" }
            val added = stack.add(
                type = ShadowAlgorithmPlugin.TYPE_WORLD,
                version = 1,
                targets = listOf(ModifierTarget("mesh", anchor)),
                config = config.toJson(),
                presetGroup = "shadow",
            )
            // Place world anchor before any shadow entries so dependencies/UI stay clean
            val worldId = added.instances.last().id
            val firstShadowIdx = added.instances.indexOfFirst {
                it.type == ShadowAlgorithmPlugin.TYPE_CAST || it.type == ShadowAlgorithmPlugin.TYPE_FORM
            }
            if (firstShadowIdx in 0 until added.instances.lastIndex) added.move(worldId, firstShadowIdx) else added
        }
    }

    private fun setWorld(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        val stack = readStack(document)
        val current = findWorld(stack)?.let { ShadowWorldConfig.fromJson(it.config) } ?: ShadowWorldConfig.Default
        val updated = current.copy(
            azimuth = request["azimuth"]?.jsonPrimitive?.float ?: current.azimuth,
            elevation = request["elevation"]?.jsonPrimitive?.float ?: current.elevation,
            style = request["style"]?.jsonPrimitive?.contentOrNull?.let { ShadowStyle.valueOf(it.uppercase()) } ?: current.style,
            colorMode = request["color_mode"]?.jsonPrimitive?.contentOrNull?.let { ShadowColorMode.valueOf(it.uppercase()) } ?: current.colorMode,
            customColor = request["custom_color"]?.jsonPrimitive?.contentOrNull ?: current.customColor,
            intensity = request["intensity"]?.jsonPrimitive?.float ?: current.intensity,
            compositeMode = request["composite_mode"]?.jsonPrimitive?.contentOrNull?.let { ShadowCompositeMode.valueOf(it.uppercase()) } ?: current.compositeMode,
            previewEnabled = request["preview_enabled"]?.jsonPrimitive?.booleanOrNull ?: current.previewEnabled,
        )
        return writeStack(document, ensureWorld(stack, model, updated))
    }

    private fun autoGenerate(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        var stack = readStack(document)
        if (findWorld(stack) == null) {
            stack = ensureWorld(stack, model, ShadowWorldConfig.Default)
        }
        val includeCovered = request["include_covered"]?.jsonPrimitive?.booleanOrNull ?: false
        val layers = model.analysis.layers.filter {
            it.source.id.raw !in document.deletedLayerIds && it.opaquePixels > 0 && it.source.visible && it.source.opacity > 0f
        }
        val byId = layers.associateBy { it.source.id.raw }
        val hiddenGroups = model.analysis.source.groups.filter { !it.visible || it.opacity <= 0f }.mapTo(HashSet()) { it.path }
        val coveredCasters = if (includeCovered) emptySet() else RigDepthTurnSource.shadowCarriers(byId, hiddenGroups).values.toSet()

        val meshByLayerId = model.rig.layerIdByDrawableId.entries.associate { (meshId, layerId) -> layerId to meshId }
        val meshIds = model.rig.puppet.drawables.mapTo(HashSet()) { it.id.raw }
        fun existingMeshes(predicate: (ClassifiedLayer) -> Boolean): List<String> =
            layers.filter(predicate).mapNotNull { layer ->
                val raw = layer.source.id.raw
                val meshId = meshByLayerId[raw] ?: raw
                meshId.takeIf { it in meshIds }
            }

        val frontHairCasters = existingMeshes {
            it.semantic.tag == SemanticTag.FRONT_HAIR &&
                it.semantic.role != PartRole.SHADOW &&
                it.semantic.role != PartRole.HIGHLIGHT &&
                it.source.id.raw !in coveredCasters
        }
        val faceReceivers = existingMeshes {
            it.semantic.tag == SemanticTag.FACE &&
                it.semantic.role != PartRole.SHADOW &&
                it.semantic.role != PartRole.HIGHLIGHT
        }
        val neckReceivers = existingMeshes {
            it.semantic.tag == SemanticTag.NECK &&
                it.semantic.role != PartRole.SHADOW &&
                it.semantic.role != PartRole.HIGHLIGHT
        }
        val hasAuthoredNeckShadow = layers.any {
            it.semantic.tag == SemanticTag.NECK && it.semantic.role == PartRole.SHADOW
        }

        // 1. Bangs -> Face cast shadow
        if (frontHairCasters.isNotEmpty() && faceReceivers.isNotEmpty() &&
            stack.instances.none { it.type == ShadowAlgorithmPlugin.TYPE_CAST && it.presetGroup == "shadow:auto_bangs" }
        ) {
            val cfg = CastShadowConfig(
                name = "刘海阴影",
                casters = frontHairCasters,
                receivers = faceReceivers,
                distance = 18f,
                placement = ShadowPlacement.ABOVE_FACE,
                skipCoveredCasters = !includeCovered,
            )
            stack = stack.add(
                type = ShadowAlgorithmPlugin.TYPE_CAST,
                version = 1,
                targets = (frontHairCasters + faceReceivers).distinct().map { ModifierTarget("mesh", it) },
                config = cfg.toJson(),
                presetGroup = "shadow:auto_bangs",
            )
        }

        // 2. Face side crescent form shadow
        if (faceReceivers.isNotEmpty() &&
            stack.instances.none { it.type == ShadowAlgorithmPlugin.TYPE_FORM && it.presetGroup == "shadow:auto_face_side" }
        ) {
            val cfg = FormShadowConfig(
                name = "脸侧阴影",
                targets = faceReceivers,
                proxy = FormProxyKind.FACE_ELLIPSOID,
                coverage = 0.28f,
            )
            stack = stack.add(
                type = ShadowAlgorithmPlugin.TYPE_FORM,
                version = 1,
                targets = faceReceivers.map { ModifierTarget("mesh", it) },
                config = cfg.toJson(),
                presetGroup = "shadow:auto_face_side",
            )
        }

        // 3. Face/chin -> Neck cast shadow (only if no hand-drawn neck shadow exists)
        if (faceReceivers.isNotEmpty() && neckReceivers.isNotEmpty() && !hasAuthoredNeckShadow &&
            stack.instances.none { it.type == ShadowAlgorithmPlugin.TYPE_CAST && it.presetGroup == "shadow:auto_neck" }
        ) {
            val cfg = CastShadowConfig(
                name = "颈部阴影",
                casters = faceReceivers,
                receivers = neckReceivers,
                distance = 22f,
                rootPinning = 0.2f,
                placement = ShadowPlacement.ABOVE_FACE,
            )
            stack = stack.add(
                type = ShadowAlgorithmPlugin.TYPE_CAST,
                version = 1,
                targets = (faceReceivers + neckReceivers).distinct().map { ModifierTarget("mesh", it) },
                config = cfg.toJson(),
                presetGroup = "shadow:auto_neck",
            )
        }

        return writeStack(document, stack)
    }

    private fun putShadow(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        var stack = readStack(document)
        if (findWorld(stack) == null) {
            stack = ensureWorld(stack, model, ShadowWorldConfig.Default)
        }
        val id = request["id"]?.jsonPrimitive?.contentOrNull
        val enabled = request["enabled"]?.jsonPrimitive?.booleanOrNull

        if (id != null) {
            val existing = stack.instances.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Shadow instance not found: $id")
            val nextEnabled = enabled ?: existing.enabled
            val updatedStack = when (existing.type) {
                ShadowAlgorithmPlugin.TYPE_CAST -> {
                    val prev = CastShadowConfig.fromJson(existing.config)
                    val next = patchCast(prev, request)
                    val targets = (next.casters + next.receivers).distinct().map { ModifierTarget("mesh", it) }
                    stack.update(id) { it.copy(targets = targets, config = next.toJson(), enabled = nextEnabled) }
                }
                ShadowAlgorithmPlugin.TYPE_FORM -> {
                    val prev = FormShadowConfig.fromJson(existing.config)
                    val next = patchForm(prev, request)
                    val targets = next.targets.distinct().map { ModifierTarget("mesh", it) }
                    stack.update(id) { it.copy(targets = targets, config = next.toJson(), enabled = nextEnabled) }
                }
                else -> throw IllegalArgumentException("Not a shadow item: $id")
            }
            return writeStack(document, updatedStack)
        }

        val kind = request["kind"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "cast"
        val updatedStack = when (kind) {
            "cast" -> {
                val cfg = patchCast(CastShadowConfig(), request)
                require(cfg.casters.isNotEmpty() && cfg.receivers.isNotEmpty()) { "Cast shadow requires casters and receivers" }
                val targets = (cfg.casters + cfg.receivers).distinct().map { ModifierTarget("mesh", it) }
                stack.add(ShadowAlgorithmPlugin.TYPE_CAST, 1, targets, cfg.toJson(), presetGroup = "shadow:manual")
            }
            "form" -> {
                val cfg = patchForm(FormShadowConfig(), request)
                require(cfg.targets.isNotEmpty()) { "Form shadow requires targets" }
                val targets = cfg.targets.distinct().map { ModifierTarget("mesh", it) }
                stack.add(ShadowAlgorithmPlugin.TYPE_FORM, 1, targets, cfg.toJson(), presetGroup = "shadow:manual")
            }
            else -> throw IllegalArgumentException("Unknown shadow kind: $kind")
        }
        return writeStack(document, updatedStack)
    }

    private fun deleteShadow(document: WorkspaceDocument, request: JsonObject): WorkspaceDocument {
        val id = request.text("id")
        val stack = readStack(document)
        val target = stack.instances.firstOrNull { it.id == id } ?: return document
        require(target.type == ShadowAlgorithmPlugin.TYPE_CAST || target.type == ShadowAlgorithmPlugin.TYPE_FORM) {
            "Not a shadow instance: $id"
        }
        return writeStack(document, stack.remove(id))
    }

    private fun patchCast(prev: CastShadowConfig, req: JsonObject): CastShadowConfig = prev.copy(
        name = req["name"]?.jsonPrimitive?.contentOrNull ?: prev.name,
        casters = req["casters"]?.jsonArray?.map { it.jsonPrimitive.content } ?: prev.casters,
        receivers = req["receivers"]?.jsonArray?.map { it.jsonPrimitive.content } ?: prev.receivers,
        distance = req["distance"]?.jsonPrimitive?.float ?: prev.distance,
        opacityOverride = if ("opacity_override" in req) req["opacity_override"]?.jsonPrimitive?.floatOrNull else prev.opacityOverride,
        softnessOverride = if ("softness_override" in req) req["softness_override"]?.jsonPrimitive?.floatOrNull else prev.softnessOverride,
        colorOverride = if ("color_override" in req) req["color_override"]?.jsonPrimitive?.contentOrNull else prev.colorOverride,
        rootPinning = req["root_pinning"]?.jsonPrimitive?.float ?: prev.rootPinning,
        parallax = req["parallax"]?.jsonPrimitive?.float ?: prev.parallax,
        placement = req["placement"]?.jsonPrimitive?.contentOrNull?.let { ShadowPlacement.valueOf(it.uppercase()) } ?: prev.placement,
        skipCoveredCasters = req["skip_covered_casters"]?.jsonPrimitive?.booleanOrNull ?: prev.skipCoveredCasters,
    )

    private fun patchForm(prev: FormShadowConfig, req: JsonObject): FormShadowConfig = prev.copy(
        name = req["name"]?.jsonPrimitive?.contentOrNull ?: prev.name,
        targets = req["targets"]?.jsonArray?.map { it.jsonPrimitive.content } ?: prev.targets,
        proxy = req["proxy"]?.jsonPrimitive?.contentOrNull?.let { FormProxyKind.valueOf(it.uppercase()) } ?: prev.proxy,
        coverage = req["coverage"]?.jsonPrimitive?.float ?: prev.coverage,
        opacityOverride = if ("opacity_override" in req) req["opacity_override"]?.jsonPrimitive?.floatOrNull else prev.opacityOverride,
        softnessOverride = if ("softness_override" in req) req["softness_override"]?.jsonPrimitive?.floatOrNull else prev.softnessOverride,
        colorOverride = if ("color_override" in req) req["color_override"]?.jsonPrimitive?.contentOrNull else prev.colorOverride,
        cutterExpandPx = req["cutter_expand_px"]?.jsonPrimitive?.float ?: prev.cutterExpandPx,
    )

    private fun writeStack(document: WorkspaceDocument, stack: RigModifierStack): WorkspaceDocument =
        document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
}
