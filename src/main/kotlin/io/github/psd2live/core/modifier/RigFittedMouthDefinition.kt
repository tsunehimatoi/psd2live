package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.MouthFittedV2Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV3Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV4Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV5Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV6Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV7Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV8Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.FittedMouth
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.serialization.json.*

/** Explicit source-only entry. Shared fitted head fields still use the composed v1 path. */
internal object RigFittedMouthDefinition {
    const val TYPE = MouthFittedV2Algorithm.TYPE

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(TYPE), source)
        val plugin = object : ChangingModifierAlgorithmPlugin by MouthFittedV2Algorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = MouthFittedV2Algorithm.HEAD_TURN
            override val canonicalOptInType = TYPE
        }
        return legacy.copy(version = MouthFittedV2Algorithm.VERSION, parameters = { parameters(it) }, plugin = plugin, upstreamInputs = true,
            changeWrites = MouthFittedV2Algorithm.WRITES, composableKinds = MouthFittedV2Algorithm.COMPOSABLE, resolveParameter = { _, _, _ -> null },
            authorContext = { requireNotNull(source).capture(it) })
    }

    private fun reach(instance: RigModifierInstance) = (RigLegacyBindings.values(instance)["reach"] ?: 100f) / 100f

    fun modernDefinition(source: RigModifierSource?): RigModifierDefinition {
        val plugin = object : ChangingModifierAlgorithmPlugin by MouthFittedV3Algorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = setOf("body/legacy", "eye/fitted", "brow/fitted")
            override val canonicalOptInType = TYPE
        }
        return definition(source).copy(version = MouthFittedV3Algorithm.VERSION, plugin = plugin,
            requires = setOf("rig.scaffold", "face/fitted"),
            parameters = { instance -> MouthFittedV2Algorithm.parameters(
                tr("model.parameter.mouthForm"), tr("model.parameter.mouthOpen"), reach(instance).takeIf { it > 1.001f } ?: 1f) },
            changeWrites = MouthFittedV3Algorithm.WRITES, composableKinds = MouthFittedV3Algorithm.WRITES - "restPose" + "relations",
            authorContext = { RigFittedMouthModernSource.capture(requireNotNull(source), it) })
    }

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition {
        val modern = modernDefinition(source)
        val plugin = object : ChangingModifierAlgorithmPlugin by MouthFittedV4Algorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = modern.optionalUpstream
            override val canonicalOptInType = TYPE
        }
        return modern.copy(version = MouthFittedV4Algorithm.VERSION, plugin = plugin,
            authorContext = { RigFittedMouthModernSource.capture(requireNotNull(source), it, closedDrawing = true) })
    }

    fun closedShellDefinition(source: RigModifierSource?): RigModifierDefinition {
        val modern = modernDefinition(source)
        val plugin = object : ChangingModifierAlgorithmPlugin by MouthFittedV5Algorithm(), ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = modern.optionalUpstream
            override val canonicalOptInType = TYPE
        }
        return modern.copy(version = MouthFittedV5Algorithm.VERSION, plugin = plugin,
            authorContext = { requireNotNull(source).closedMouthShellContext(it) })
    }

    private enum class SourceFrame(val version: Int, val algorithm: ChangingModifierAlgorithmPlugin, val writes: Set<String>) {
        OPEN(MouthFittedV6Algorithm.VERSION, MouthFittedV6Algorithm, MouthFittedV6Algorithm.WRITES),
        FALLBACK(MouthFittedV7Algorithm.VERSION, MouthFittedV7Algorithm, MouthFittedV7Algorithm.WRITES),
        LAYERED(MouthFittedV8Algorithm.VERSION, MouthFittedV8Algorithm, MouthFittedV8Algorithm.WRITES),
    }

    fun sharedFrameDefinition(source: RigModifierSource?, layered: Boolean = false): RigModifierDefinition =
        frameDefinition(source, if (layered) SourceFrame.LAYERED else SourceFrame.OPEN)

    fun fallbackFrameDefinition(source: RigModifierSource?): RigModifierDefinition = frameDefinition(source, SourceFrame.FALLBACK)

    fun supportsFallbackFrames(source: RigModifierSource, scaffold: RigModifierInstance,
                               preceding: List<RigModifierInstance>, instance: RigModifierInstance): Boolean {
        val (old, before) = RigFittedMouthModuleSource.context(source, scaffold, preceding, null)
        if (before.faceTurn(old) != null) return false
        val (next, after) = RigFittedMouthModuleSource.context(source, scaffold, preceding + instance, null)
        return after.faceTurn(next) == null && matchesSourceMouth(after.fittedMouth(next), layered = false)
    }

    private fun matchesSourceMouth(mouth: FittedMouth?, layered: Boolean): Boolean =
        mouth?.source != null && !mouth.closedLine && mouth.layered == layered && (mouth.rig?.fit?.layered != null) == layered

    private fun frameDefinition(source: RigModifierSource?, frame: SourceFrame): RigModifierDefinition {
        val modern = modernDefinition(source)
        val plugin = object : ChangingModifierAlgorithmPlugin by frame.algorithm, ModifierOptionalUpstream, ModifierVersionSelection {
            override val optionalUpstream = modern.optionalUpstream
            override val canonicalOptInType = TYPE
        }
        return modern.copy(version = frame.version, plugin = plugin,
            changeWrites = frame.writes, composableKinds = frame.writes - "restPose" + "relations",
            authorContext = { requireNotNull(source).captureSharedFrames(it, frame) })
    }

    private fun RigModifierSource.captureSharedFrames(instance: RigModifierInstance, frame: SourceFrame): ModifierAlgorithmContext {
        val fallback = frame == SourceFrame.FALLBACK
        val layered = frame == SourceFrame.LAYERED
        val scaffold = provider(instance)
        val preceding = bindingPredecessors(instance, scaffold)
        require(scaffold.version == 2 && preceding.all { entry ->
            entry.targets.toSet() == instance.targets.toSet() && entry.type in setOf("body/legacy", "face/fitted", "eye/fitted", "brow/fitted")
        }) { "Fitted mouth v${instance.version} requires a shared source head" }
        val head = preceding.single { it.type == "face/fitted" && it.version == 3 }
        val eye = preceding.single { it.type == "eye/fitted" && it.version == 5 }
        val browVersion = if (fallback) 3 else 4
        val brow = preceding.single { it.type == "brow/fitted" && it.version == browVersion }
        val (old, before) = RigFittedMouthModuleSource.context(this, scaffold, preceding, null)
        val (next, after) = RigFittedMouthModuleSource.context(this, scaffold, preceding + instance, null)
        if (fallback) {
            val subject = RecipeSubject.of(after.analysis)
            val eye = subject.sides(SemanticTag.EYEWHITE)
            require(eye.size == 1 && subject.sides(SemanticTag.EYELASH) == eye && subject.sides(SemanticTag.EYEBROW) == eye) {
                "Fitted mouth v7 requires one source eye with its lash and brow"
            }
        }
        require(matchesSourceMouth(after.fittedMouth(next), layered) &&
            (before.faceTurn(old) != null) != fallback && (after.faceTurn(next) != null) != fallback) {
            when (frame) {
                SourceFrame.OPEN -> "Fitted mouth v6 requires an open source drawing and fitted head frames"
                SourceFrame.FALLBACK -> "Fitted mouth v7 requires an open source drawing and fallback head frames"
                SourceFrame.LAYERED -> "Fitted mouth v8 requires an open layered source drawing and fitted head frames"
            }
        }
        fun headLayers(context: RigBuilder.RigContext, config: PipelineConfig): Map<String, ClassifiedLayer> {
            val parents = context.deformers.associate { it.id to it.parent }
            return bindingSourceLayers(instance, context) { layer ->
                layer.semantic.tag.group == LayerGroup.HEAD || generateSequence(context.parentAndFrame(layer, config).first) {
                    parents[it]
                }.any { it.raw == "DeformHeadRotation" }
            }
        }
        val oldLayers = headLayers(before, old)
        val nextLayers = headLayers(after, next)
        require(oldLayers.isNotEmpty() && oldLayers.keys == nextLayers.keys) { "Fitted mouth v${instance.version} requires stable head targets" }
        val axes = preceding.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters }
        return ModifierAlgorithmContext(buildJsonObject {
            put("face", head.id)
            put("eye", eye.id)
            put("brow", brow.id)
        }, mapOf(
            "before" to outlineShellSource(before, old, oldLayers, axes, includeFaceLean = true),
            "after" to outlineShellSource(after, next, nextLayers, axes + RigLegacyBindings.spec(TYPE).parameters,
                includeFaceLean = true),
        ))
    }

    private fun parameters(instance: RigModifierInstance) = MouthFittedV2Algorithm.parameters(
        tr("model.parameter.mouthForm"), tr("model.parameter.mouthOpen"), reach(instance))

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val structure = provider(instance)
        require(activeBefore(instance).none { previous -> previous.id != structure.id &&
            previous.targets.any { it in instance.targets } }) {
            "Fitted mouth v2 requires neutral scaffold targets. Use v1 with preceding bindings."
        }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(structure.config["corrections"])
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { area ->
            area.id to if (area.id == "mouth") BaseChoice(TYPE, values = RigLegacyBindings.values(instance))
            else BaseChoice(area.legacy.id, area.legacy.version)
        }, modules = RigModules.modules.associate { it.id to ModuleChoice(false, it.version) },
            moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe, rigTuning = RigLegacyBindings.tuning(instance, RigTuning()),
            featureDisplacementEnabled = structure.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false)
        val measured = RigBuilder.rigContext(analysis, native, meshCache)
        require(measured.fittedMouth(native)?.rig != null) { "Fitted mouth v2 requires a readable mouth opening" }
        val byLayer = measured.analysis.layers.associateBy { it.source.id.raw }
        val layers = instance.targets.mapNotNull { target ->
            val layer = byLayer[layersByMesh[target.id]] ?: return@mapNotNull null
            if (layer.semantic.tag !in CharacterAnalyzer.MOUTH_TAGS) return@mapNotNull null
            target.id to layer
        }.toMap()
        require(layers.isNotEmpty()) { "Fitted mouth v2 needs a mouth target" }
        val parentFrames = layers.mapValues { (_, layer) -> measured.parentAndFrame(layer, native) }
        val parents = parentFrames.mapValues { (id, parent) -> parent.first?.let { ScaffoldBinding.deformerId(structure, id, it.raw) } }
        val captured = RigFittedMouthSource.capture(measured, native, parents, layers, parentFrames.mapValues { it.value.second },
            layers.keys.toList(), headProvider = null)
        val scaffoldMeshes = scaffoldContext(structure).resources.getValue("source_meshes")
        val vertices = layers.mapValues { (id, layer) ->
            MouthFittedInputs.SourceVertexTarget(primaryByLayer.getValue(layer.source.id.raw), parents.getValue(id))
        }
        val materials = layers.values.distinctBy { it.source.id }.map { layer ->
            val mesh = RigBuilder.fittedMouthSourceMesh(layer, measured, native, atlas, meshCache)
            val raw = ScaffoldMesh.RawMesh(mesh.rigPositions, mesh.mesh.uvs, mesh.mesh.indices)
            ScaffoldMeshContext.Material(primaryByLayer.getValue(layer.source.id.raw), layer.source.id.raw, raw, ScaffoldMesh.Geometry.Zero)
        }
        val fitted = ScaffoldMeshContext.capture(materials).resources.getValue("source_meshes")
        return MouthFittedV2Algorithm.capture(captured.copy(sourceVertices = MouthFittedInputs.SourceVertices(fitted, vertices)),
            scaffoldMeshes, tr("model.parameter.mouthForm"), tr("model.parameter.mouthOpen"), reach(instance), tr("model.group.mouth"))
    }
}
