package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BrowFittedBinding
import io.github.psd2live.algorithms.binding.BrowFittedV3Binding
import io.github.psd2live.algorithms.binding.BrowFittedV4Binding
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.DeformerId

internal object RigBrowFittedModifier {
    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val algorithm = object : ChangingModifierAlgorithmPlugin by BrowFittedBinding(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = setOf("eye/fitted")
            override val canonicalOptInType = "brow/fitted"
        }
        return RigLegacyBindings.definition(RigLegacyBindings.spec("brow/fitted"), source).copy(
            version = BrowFittedBinding.VERSION,
            parameters = { BrowFittedBinding.parameters(
                io.github.psd2live.i18n.tr("model.parameter.browLY"), io.github.psd2live.i18n.tr("model.parameter.browRY")) },
            plugin = algorithm,
            resolveParameter = { _, _, _ -> null },
            changeWrites = setOf("mesh", "deformer", "parameter", "hierarchy"),
            upstreamInputs = true,
            authorContext = { requireNotNull(source).capture(it) },
        )
    }

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition {
        val algorithm = object : ChangingModifierAlgorithmPlugin by BrowFittedV3Binding, ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = setOf("eye/fitted", "face/fitted")
            override val canonicalOptInType = "brow/fitted"
        }
        return definition(source).copy(
            version = BrowFittedV3Binding.VERSION,
            plugin = algorithm,
            authorContext = { requireNotNull(source).capture(it, modern = true) },
        )
    }

    fun sharedFrameDefinition(source: RigModifierSource?): RigModifierDefinition {
        val algorithm = object : ChangingModifierAlgorithmPlugin by BrowFittedV4Binding, ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = setOf("body/legacy", "face/fitted", "eye/fitted")
            override val canonicalOptInType = "brow/fitted"
        }
        return definition(source).copy(version = BrowFittedV4Binding.VERSION, plugin = algorithm,
            changeWrites = setOf("mesh", "deformer", "part", "parameter", "hierarchy"),
            authorContext = { requireNotNull(source).captureSharedFrames(it) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val prior = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains)
        }
        require(scaffold.version == 2 && prior.all { it.targets.containsAll(instance.targets) && it.type in setOf("body/legacy", "face/fitted", "eye/fitted") }) {
            "Fitted brow v4 requires shared source body, head and eye frames"
        }
        val face = prior.single { it.type == "face/fitted" && it.version == 3 }
        val eye = prior.single { it.type == "eye/fitted" && it.version == 5 }
        val frames = measureFrames(this, scaffold, prior, instance)
        val (old, before) = frames.before
        val (next, after) = frames.after
        val scope = frames.scope
        require(scope.isNotEmpty()) { "Fitted brow v4 requires a source brow" }
        val witnessTags = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE)
        fun selected(layers: Map<String, ClassifiedLayer>) =
            layers.filter { (id, layer) -> id in scope || layer.semantic.tag in witnessTags }
        val axes = prior.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters }
        return ModifierAlgorithmContext(buildJsonObject {
            put("face", face.id)
            put("eye", eye.id)
        }, mapOf(
            "before" to outlineShellSource(before, old, selected(frames.priorLayers), axes, includeFaceLean = true, projectedMeshes = scope),
            "after" to outlineShellSource(after, next, selected(frames.nextLayers), axes + RigLegacyBindings.spec(instance.type).parameters,
                includeFaceLean = true, projectedMeshes = scope),
        ))
    }

    fun requiresSharedFrames(source: RigModifierSource, scaffold: RigModifierInstance,
                             prior: List<RigModifierInstance>, instance: RigModifierInstance): Boolean {
        val measured = measureFrames(source, scaffold, prior, instance)
        return measured.scope.any { measured.priorLayers.getValue(it).semantic.tag != SemanticTag.EYEBROW }
    }

    private data class FrameTransition(
        val before: Pair<PipelineConfig, RigBuilder.RigContext>,
        val after: Pair<PipelineConfig, RigBuilder.RigContext>,
        val priorLayers: Map<String, ClassifiedLayer>,
        val nextLayers: Map<String, ClassifiedLayer>,
        val scope: Set<String>,
    )

    private fun measureFrames(source: RigModifierSource, scaffold: RigModifierInstance,
                              prior: List<RigModifierInstance>, instance: RigModifierInstance): FrameTransition {
        val (old, before) = RigFittedMouthModuleSource.context(source, scaffold, prior, null)
        val (next, after) = RigFittedMouthModuleSource.context(source, scaffold, prior + instance, null)
        val priorLayers = source.bindingSourceLayers(instance, before)
        val nextLayers = source.bindingSourceLayers(instance, after)
        require(priorLayers.keys == nextLayers.keys)
        val oldFrames = priorLayers.mapValues { (_, layer) -> before.parentAndFrame(layer, old) }
        val newFrames = nextLayers.mapValues { (_, layer) -> after.parentAndFrame(layer, next) }
        fun branches(context: RigBuilder.RigContext, config: PipelineConfig, frames: Collection<Pair<DeformerId?, Bounds>>) =
            RigBuilder.browTurnSourceBranch(context, config, source.meshCache, frames.mapNotNullTo(hashSetOf()) { it.first }).associateBy { it.id }
        val oldBranches = branches(before, old, oldFrames.values)
        val newBranches = branches(after, next, newFrames.values)
        val changed = (oldBranches.keys + newBranches.keys).filterTo(hashSetOf()) { id ->
            oldBranches[id]?.let(PuppetIr::deformerToIr) != newBranches[id]?.let(PuppetIr::deformerToIr)
        }
        val scope = priorLayers.keys.filterTo(linkedSetOf()) { id ->
            priorLayers.getValue(id).semantic.tag == SemanticTag.EYEBROW || oldFrames[id] != newFrames[id] ||
                before.headSpaceFor(priorLayers.getValue(id), old) != after.headSpaceFor(nextLayers.getValue(id), next) ||
                generateSequence(oldFrames.getValue(id).first) { oldBranches.getValue(it).parent }.any { it in changed }
        }
        return FrameTransition(old to before, next to after, priorLayers, nextLayers, scope)
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, modern: Boolean = false): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Fitted brow v${instance.version} requires a shared v2 scaffold. Use v1 with older scaffolds." }
        val upstream = activeBefore(instance).filter { it.config["scaffold"] == JsonPrimitive(scaffold.id) &&
            it.targets.any(instance.targets::contains) }
        require(upstream.all { previous -> when (previous.type) {
            "body/legacy" -> previous.version in 1..2
            "face/legacy", "face.depth-turn", "eye/legacy" -> previous.version == 1
            "face/fitted" -> previous.version == 1 || modern && previous.version == 3
            "eye/fitted" -> previous.version == 2 || modern && previous.version == 5
            else -> false
        } }) { "Fitted brow v${instance.version} requires compatible body, head and eye bindings before the brow. Use v1 with other preceding bindings." }
        val eye = upstream.lastOrNull { it.type == "eye/fitted" }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val tuning = upstream.mapNotNull { RigLegacyBindings.find(it.type)?.let { spec -> spec to it } }
            .sortedBy { (spec, _) -> RigLegacyBindings.specs.indexOf(spec) }
            .fold(RigTuning()) { tuning, (_, modifier) -> RigLegacyBindings.tuning(modifier, tuning) }
        val bases = RigModules.areas.associate { area ->
            val base = upstream.lastOrNull { RigLegacyBindings.find(it.type)?.let { spec -> spec.isBase && spec.area == area.id } == true }
            area.id to if (base == null || base.type == RigLegacyBindings.FITTED_FACE) BaseChoice(area.legacy.id, area.legacy.version)
                else BaseChoice(base.type, base.version, RigLegacyBindings.values(base))
        }
        val styledFace = upstream.lastOrNull { it.type == "face.depth-turn" || it.type == RigLegacyBindings.FITTED_FACE }
        val head = upstream.lastOrNull { it.type == "face/legacy" || it.type == RigLegacyBindings.FITTED_FACE }
        val modules = RigModules.modules.associate { module ->
            module.id to if (styledFace != null && module == RigModules.FACE_DEPTH_TURN)
                ModuleChoice(true, values = RigLegacyBindings.values(styledFace)) else ModuleChoice(false)
        }
        val native = sourceConfig().copy(
            recipe = RigRecipe(true, bases = bases, modules = modules,
                moves = corrections.moves, eyeParts = corrections.eyeParts),
            rigTuning = tuning,
            headTurnStrength = head?.let(RigLegacyBindings::strength) ?: 1f,
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true),
        )
        val fitted = native.copy(recipe = native.recipe.copy(bases = bases + ("brow" to
            BaseChoice(instance.type, instance.version, RigLegacyBindings.values(instance)))),
            rigTuning = RigLegacyBindings.tuning(instance, tuning))
        val fittedContext = RigBuilder.rigContext(analysis, fitted, meshCache)
        val byId = fittedContext.analysis.layers.associateBy { it.source.id.raw }
        val targets = instance.targets.mapNotNull { target ->
            val id = layersByMesh.getValue(target.id)
            val layer = byId[id]?.takeIf { it.semantic.tag == SemanticTag.EYEBROW && it.opaquePixels > 0 }
                ?: return@mapNotNull null
            if (id !in atlas.placementByLayerId) return@mapNotNull null
            target.id to (primaryByLayer.getValue(id) to layer)
        }.toMap()
        val captured = RigFittedBrowSource.capture(fittedContext, fitted, scaffold.id, targets, eye?.id)
        val ordinary = scaffoldContext(scaffold.copy(plugin = null))
        val canonical = targets.values.associate { (mesh, layer) -> layer.source.id.raw to mesh }
        fun captureStructure(config: PipelineConfig): Pair<JsonObject, ModifierAlgorithmResource> {
            val structure = RigBuilder.scaffoldSourceInputs(analysis, config, meshCache)
            val meshes = RigBuilder.scaffoldMeshInputs(analysis, atlas, config, meshCache, canonical)
            return JsonObject(ordinary.values + mapOf("frames" to structure.frames, "branches" to structure.branches,
                "mesh_inputs" to meshes.values.getValue("mesh_inputs"))) to meshes.resources.getValue("source_meshes")
        }
        val (before, beforeMeshes) = captureStructure(native)
        val (after, afterMeshes) = captureStructure(fitted)
        val measurements = captured.encode(afterMeshes, emptyList())
        val beforeContext = RigBuilder.rigContext(analysis, native, meshCache)
        val styled = styledFace?.let {
            fun capture(context: RigBuilder.RigContext, config: PipelineConfig) = RigFittedFaceSource.capture(
                context, config, context.deformers.mapTo(hashSetOf()) { it.id })?.encode()?.values
            buildJsonObject {
                put("before", capture(beforeContext, native) ?: JsonNull)
                put("after", capture(fittedContext, fitted) ?: JsonNull)
            }
        }
        val motion = head?.takeIf { it.type == "face/legacy" || styledFace != null }?.let {
            val prior = beforeContext.faceRig
            val next = fittedContext.faceRig
            fun coordinates(rig: NinePoseFaceRig) = JsonArray(listOf(rig.centerY, rig.radiusX, rig.radiusY, rig.eyeLineY).map(::JsonPrimitive))
            buildJsonObject {
                put("strength", RigLegacyBindings.strength(it))
                put("before", coordinates(prior))
                put("after", coordinates(next))
                put("tuning", JsonArray(listOf(tuning.yawEase, tuning.pitchEase, tuning.featurePlane, tuning.browFarShrink,
                    tuning.browNearGrow, tuning.browShift, tuning.browTilt, tuning.faceLeanDown, tuning.faceLeanUp).map(::JsonPrimitive)))
            }
        }
        val providers = if (modern) mapOf("fitted_face_provider" to
            (head?.takeIf { it.type == RigLegacyBindings.FITTED_FACE }?.id?.let(::JsonPrimitive) ?: JsonNull)) else emptyMap()
        return ModifierAlgorithmContext(JsonObject(measurements.values + providers + mapOf("before_structure" to before, "structure" to after,
            "legacy_face" to (motion ?: JsonNull), "styled_face" to (styled ?: JsonNull))),
            mapOf("before_meshes" to beforeMeshes, "source_meshes" to afterMeshes))
    }
}
