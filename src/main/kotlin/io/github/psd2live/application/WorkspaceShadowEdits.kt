package io.github.psd2live.application

import io.github.psd2live.algorithms.shadow.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.*
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.visibleDrawableIds

/** Pure candidate edits for world lighting and cast/form shadows backed by the modifier stack. */
internal object WorkspaceShadowEdits {
    val supported = setOf("shadow_world_set", "shadow_auto_generate", "shadow_put", "shadow_delete", "shadow_apply")

    fun apply(operation: WorkspaceDocumentOperation, document: WorkspaceDocument, model: RigPreviewModel,
              work: WorkspaceRasterWork = WorkspaceRasterWork.Direct): WorkspaceDocument {
        val request = operation.request
        val candidate = when (operation.operation) {
            "shadow_world_set" -> setWorld(document, model, request)
            "shadow_auto_generate" -> autoGenerate(document, model, request, work)
            "shadow_put" -> putShadow(document, model, request)
            "shadow_delete" -> deleteShadow(document, request)
            "shadow_apply" -> applyShadow(document, model, request, work)
            else -> throw IllegalArgumentException("Unsupported shadow operation: ${operation.operation}")
        }
        if (document.rigEdits.modifierStack == null && candidate.rigEdits.modifierStack != null) {
            // Installing an empty stack would remove the implicit legacy bindings. Convert inside the edit transaction.
            val converted = WorkspaceModifierConversion.convert(document, model, work)
            return apply(operation, converted, model, work)
        }
        return candidate
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
            customColor = request["custom_color"]?.jsonPrimitive?.contentOrNull?.let(::shadowColor) ?: current.customColor,
            intensity = request["intensity"]?.jsonPrimitive?.float ?: current.intensity,
            compositeMode = request["composite_mode"]?.jsonPrimitive?.contentOrNull?.let { ShadowCompositeMode.valueOf(it.uppercase()) } ?: current.compositeMode,
            previewEnabled = request["preview_enabled"]?.jsonPrimitive?.booleanOrNull ?: current.previewEnabled,
        )
        return writeStack(document, ensureWorld(stack, model, updated))
    }

    private fun autoGenerate(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject,
                             work: WorkspaceRasterWork): WorkspaceDocument {
        val original = readStack(document)
        var stack = original
        val rig = model.rig
        val puppet = rig.puppet
        val shown = puppet.visibleDrawableIds()
        val ownLayerIds = rig.ownLayerIdByDrawableId
        val byLayer = puppet.drawables.asSequence()
            .filter { it.id in shown && it.mesh != null && it.opacity > 0f && it.id.raw !in rig.seeThroughCopies }
            .groupBy { ownLayerIds[it.id.raw] ?: it.id.raw }
        val layers = model.analysis.layers.mapNotNull { layer ->
            val id = layer.source.id.raw
            val meshes = byLayer[id].orEmpty()
            if (layer.opaquePixels == 0 || id in document.deletedLayerIds || id in document.layout.referenceLayerIds || meshes.isEmpty()) null
            else AutoLayer(layer, meshes)
        }
        val anchor = layers.firstOrNull()?.meshes?.firstOrNull()?.id?.raw ?: return document
        val clips = RigOrdinaryMaterials.clipping(model.analysis.source, ownLayerIds)
        val parts = puppet.parts.associateBy { it.id }
        val parentParts = puppet.parts.flatMap { part ->
            part.children.filterIsInstance<OrgChild.Part>().map { it.id to part.id }
        }.toMap()
        val owners = puppet.partByDrawable()
        fun clipsTo(paint: AutoLayer, receiver: AutoLayer): Boolean {
            val ids = receiver.meshes.mapTo(HashSet()) { it.id }
            return clips[paint.id] in ids || paint.meshes.any { mesh ->
                (!mesh.invertMask && mesh.maskedBy.any { it in ids }) ||
                    generateSequence(owners[mesh.id], parentParts::get).any { part ->
                        parts[part]?.takeIf { it.groupMode is PartGroupMode.Isolated }?.composite?.let {
                            !it.invertMask && it.maskedBy.any { id -> id in ids }
                        } == true
                    }
            }
        }
        val painted = layers.filter { it.role == PartRole.SHADOW }
        val faces = layers.filter { it.tag == SemanticTag.FACE && it.role == null }
        val necks = layers.filter { it.tag == SemanticTag.NECK && it.role == null }
        val fringe = layers.filter { it.tag == SemanticTag.FRONT_HAIR && it.role !in setOf(PartRole.SHADOW, PartRole.HIGHLIGHT, PartRole.AHOGE) }
        val paired = RigDepthTurnSource.shadowCarriers(layers.associate { it.id to it.layer }, emptySet())
        val includeCovered = request["include_covered"]?.jsonPrimitive?.booleanOrNull

        fun syncCast(group: String, defaults: CastShadowConfig, candidates: List<AutoLayer>, skin: List<AutoLayer>,
                     distanceFraction: Float, covered: (AutoLayer, AutoLayer) -> Boolean) {
            val existing = stack.instances.firstOrNull { it.presetGroup == group && it.type == ShadowAlgorithmPlugin.TYPE_CAST }
            if (wasApplied(stack, existing, group, ShadowAlgorithmPlugin.TYPE_CAST)) return
            val old = existing?.let { CastShadowConfig.fromJson(it.config) }
            val skipCovered = includeCovered?.not() ?: old?.skipCoveredCasters ?: true
            val pairs = candidates.flatMap { caster ->
                work.checkpoint()
                skin.mapNotNull { receiver ->
                    if (overlaps(caster.layer.bounds, receiver.layer.bounds.expanded(.2f)) && (!skipCovered || !covered(caster, receiver)))
                        caster to receiver else null
                }
            }
            val casters = meshIds(pairs.map { it.first })
            val receivers = meshIds(pairs.map { it.second })
            if (existing == null && pairs.isEmpty()) return
            val receiversByCaster = pairs.flatMap { (caster, receiver) ->
                caster.meshes.map { it.id.raw to receiver }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, matched) -> meshIds(matched) }
            val extent = pairs.maxOfOrNull { (_, receiver) -> receiver.layer.bounds.height }
            // Automatically chosen distances use the recipient's size in canvas pixels. Stored custom distances stay in pixels.
            val distance = extent?.let { (it * distanceFraction).coerceIn(1f, 200f) } ?: defaults.distance
            val config = (old ?: defaults).copy(casters = casters, receivers = receivers,
                distance = old?.distance?.takeUnless { it == defaults.distance } ?: distance, skipCoveredCasters = skipCovered,
                receiversByCaster = receiversByCaster)
            stack = syncAutoItem(stack, existing, ShadowAlgorithmPlugin.TYPE_CAST, group, casters + receivers, config.toJson(), anchor)
        }

        syncCast("shadow:auto_bangs", CastShadowConfig(name = tr("shadow.auto.bangs"), distance = 18f), fringe, faces, .07f) { caster, receiver ->
            painted.any { paint ->
                // Hair's own paint is separate from a projection on skin, even if it follows the same carrier.
                clipsTo(paint, receiver) && (paired[paint.id] == caster.id ||
                    paired[paint.id] == null && paint.tag == SemanticTag.FRONT_HAIR && overlaps(paint.layer.bounds, caster.layer.bounds))
            }
        }

        val formGroup = "shadow:auto_face_side"
        val form = stack.instances.firstOrNull { it.type == ShadowAlgorithmPlugin.TYPE_FORM && it.presetGroup == formGroup }
        if (!wasApplied(stack, form, formGroup, ShadowAlgorithmPlugin.TYPE_FORM)) {
            val targets = meshIds(faces)
            if (form != null || targets.isNotEmpty()) {
                val config = (form?.let { FormShadowConfig.fromJson(it.config) }
                    ?: FormShadowConfig(name = tr("shadow.auto.faceSide"), coverage = .18f)).copy(targets = targets)
                stack = syncAutoItem(stack, form, ShadowAlgorithmPlugin.TYPE_FORM, formGroup, targets, config.toJson(), anchor)
            }
        }

        syncCast("shadow:auto_neck", CastShadowConfig(name = tr("shadow.auto.neck"), distance = 22f, rootPinning = .2f), faces, necks, .2f) { _, receiver ->
            painted.any { paint -> paint.tag == SemanticTag.NECK && (clipsTo(paint, receiver) ||
                overlaps(paint.layer.bounds, receiver.layer.bounds) &&
                paint.meshes.maxOf { it.drawOrder } > receiver.meshes.minOf { it.drawOrder }) }
        }

        if (stack == original) return document
        if (findWorld(stack) == null) stack = ensureWorld(stack, model, ShadowWorldConfig.Default)
        return writeStack(document, stack)
    }

    private data class AutoLayer(val layer: ClassifiedLayer, val meshes: List<Drawable>) {
        val id get() = layer.source.id.raw
        val tag get() = layer.semantic.tag
        val role get() = layer.semantic.role ?: if (tag == SemanticTag.FACE)
            PartRoles.fromName(layer.source.name, "", SemanticTag.FACE_DETAIL)?.takeIf { it == PartRole.SHADOW || it == PartRole.HIGHLIGHT }
        else null
    }

    private fun overlaps(a: Bounds, b: Bounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun meshIds(layers: List<AutoLayer>): List<String> = layers.flatMap { it.meshes.map { mesh -> mesh.id.raw } }.distinct()

    private fun wasApplied(stack: RigModifierStack, item: RigModifierInstance?, group: String, type: String): Boolean {
        if (item != null && item.id in stack.appliedNodes) return true
        val originalName = when (group) {
            "shadow:auto_bangs" -> "刘海阴影"
            "shadow:auto_face_side" -> "脸侧阴影"
            "shadow:auto_neck" -> "颈部阴影"
            else -> null
        }
        return stack.appliedCapabilities.any {
            it.type == type && (it.presetGroup == group ||
                it.presetGroup == null && originalName != null && it.config["name"]?.jsonPrimitive?.contentOrNull == originalName)
        }
    }

    private fun syncAutoItem(stack: RigModifierStack, existing: RigModifierInstance?, type: String, group: String,
                             ids: List<String>, config: JsonObject, anchor: String): RigModifierStack {
        // Keep item settings when its automatic selection is temporarily empty. The harmless anchor satisfies the stack contract.
        val targets = ids.distinct().ifEmpty { listOf(anchor) }.map { ModifierTarget("mesh", it) }
        return if (existing == null) stack.add(type, 1, targets, config, presetGroup = group)
        else stack.update(existing.id) { it.copy(targets = targets, config = config) }
    }

    private fun applyShadow(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject,
                            work: WorkspaceRasterWork): WorkspaceDocument {
        val id = request.text("id")
        val stack = readStack(document)
        val item = requireNotNull(stack.instances.firstOrNull { it.id == id }) { "Shadow instance not found: $id" }
        require(isShadowItem(item)) { "Not a shadow item: $id" }
        require(findWorld(stack)?.let { ShadowWorldConfig.fromJson(it.config).previewEnabled } != false) {
            "Enable shadow preview before applying a shadow"
        }
        return WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_apply", buildJsonObject {
            put("id", id)
            put("scope", "selected")
        }), document, model, work)
    }

    private fun isShadowItem(item: RigModifierInstance): Boolean =
        item.type == ShadowAlgorithmPlugin.TYPE_CAST || item.type == ShadowAlgorithmPlugin.TYPE_FORM

    private fun putShadow(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        var stack = readStack(document)
        val id = request["id"]?.jsonPrimitive?.contentOrNull
        val enabled = request["enabled"]?.jsonPrimitive?.booleanOrNull
        val resolve = { targets: List<String> -> resolveMeshIds(targets, model) }

        val existing = id?.let { raw ->
            requireNotNull(stack.instances.firstOrNull { it.id == raw }) { "Shadow instance not found: $raw" }
                .also { require(isShadowItem(it)) { "Not a shadow item: $raw" } }
        }
        val existingKind = existing?.type?.substringAfterLast('.')
        val kind = request["kind"]?.jsonPrimitive?.contentOrNull ?: existingKind ?: "cast"
        require(existingKind == null || kind == existingKind) { "Cannot change a shadow item's kind" }
        val type: String
        val config: JsonObject
        val targetIds: List<String>

        when (kind) {
            "cast" -> {
                require(request.keys.none { it in setOf("targets", "coverage", "proxy", "cutter_expand_px") }) { "A cast shadow needs cast settings" }
                val old = existing?.let { CastShadowConfig.fromJson(it.config) } ?: CastShadowConfig()
                val patched = patchCast(old, request)
                val casters = if ("casters" in request) resolve(patched.casters) else patched.casters
                val receivers = if ("receivers" in request) resolve(patched.receivers) else patched.receivers
                if (existing == null || "casters" in request || "receivers" in request) {
                    require(casters.isNotEmpty() && receivers.isNotEmpty()) { "Cast shadow requires casters and receivers" }
                    require(receivers.none { it in casters }) { "A cast shadow needs distinct casters and receivers" }
                }
                type = ShadowAlgorithmPlugin.TYPE_CAST
                config = patched.copy(casters = casters, receivers = receivers,
                    receiversByCaster = if (casters == old.casters && receivers == old.receivers) old.receiversByCaster else null).toJson()
                targetIds = casters + receivers
            }
            "form" -> {
                require(request.keys.none { it in setOf("casters", "receivers", "distance", "root_pinning", "parallax", "placement", "skip_covered_casters") }) {
                    "A form shadow needs form settings"
                }
                val old = existing?.let { FormShadowConfig.fromJson(it.config) } ?: FormShadowConfig()
                val patched = patchForm(old, request)
                val targets = if ("targets" in request) resolve(patched.targets) else patched.targets
                if (existing == null || "targets" in request) require(targets.isNotEmpty()) { "Form shadow requires targets" }
                type = ShadowAlgorithmPlugin.TYPE_FORM
                config = patched.copy(targets = targets).toJson()
                targetIds = targets
            }
            else -> throw IllegalArgumentException("Unknown shadow kind: $kind")
        }
        if (findWorld(stack) == null) stack = ensureWorld(stack, model, ShadowWorldConfig.Default)
        val targets = targetIds.distinct().map { ModifierTarget("mesh", it) }.ifEmpty { existing?.targets.orEmpty() }
        val updated = if (existing != null) {
            stack.update(existing.id) { it.copy(targets = targets, config = config, enabled = enabled ?: it.enabled) }
        } else {
            val added = stack.add(type, 1, targets, config, presetGroup = "shadow:manual")
            if (enabled == false) added.update(added.instances.last().id) { it.copy(enabled = false) } else added
        }
        return writeStack(document, updated)
    }

    private fun resolveMeshIds(targets: List<String>, model: RigPreviewModel): List<String> {
        val rig = model.rig
        val meshes = rig.puppet.drawables.filter { it.mesh != null }
        val meshIds = meshes.mapTo(HashSet()) { it.id.raw }
        val byLayer = meshes.mapNotNull { mesh -> rig.ownLayerIdByDrawableId[mesh.id.raw]?.let { it to mesh.id.raw } }
            .groupBy({ it.first }, { it.second })
        return targets.flatMap { target ->
            when {
                target in meshIds -> listOf(target)
                target.startsWith("mesh:") && target.removePrefix("mesh:") in meshIds -> listOf(target.removePrefix("mesh:"))
                target.startsWith("layer:") -> byLayer[target.removePrefix("layer:")]
                else -> byLayer[target]
            } ?: throw IllegalArgumentException("Shadow target is not an existing mesh or source layer: $target")
        }.distinct()
    }

    private fun deleteShadow(document: WorkspaceDocument, request: JsonObject): WorkspaceDocument {
        val id = request.text("id")
        val stack = readStack(document)
        val target = stack.instances.firstOrNull { it.id == id } ?: return document
        require(isShadowItem(target)) { "Not a shadow instance: $id" }
        return writeStack(document, stack.remove(id))
    }

    private fun patchCast(prev: CastShadowConfig, req: JsonObject): CastShadowConfig = prev.copy(
        name = req["name"]?.jsonPrimitive?.contentOrNull ?: prev.name,
        casters = req["casters"]?.jsonArray?.map { it.jsonPrimitive.content } ?: prev.casters,
        receivers = req["receivers"]?.jsonArray?.map { it.jsonPrimitive.content } ?: prev.receivers,
        distance = req["distance"]?.jsonPrimitive?.float ?: prev.distance,
        opacityOverride = if ("opacity_override" in req) req["opacity_override"]?.jsonPrimitive?.floatOrNull else prev.opacityOverride,
        softnessOverride = if ("softness_override" in req) req["softness_override"]?.jsonPrimitive?.floatOrNull else prev.softnessOverride,
        colorOverride = if ("color_override" in req) req["color_override"]?.jsonPrimitive?.contentOrNull?.let(::shadowColor) else prev.colorOverride,
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
        colorOverride = if ("color_override" in req) req["color_override"]?.jsonPrimitive?.contentOrNull?.let(::shadowColor) else prev.colorOverride,
        cutterExpandPx = req["cutter_expand_px"]?.jsonPrimitive?.float ?: prev.cutterExpandPx,
    )

    private val rgbHex = Regex("#?[0-9a-fA-F]{6}")

    private fun shadowColor(value: String): String = value.trim().also {
        require(rgbHex.matches(it)) { "Shadow color must be a six-digit RGB hex value" }
    }

    private fun writeStack(document: WorkspaceDocument, stack: RigModifierStack): WorkspaceDocument =
        document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
}
