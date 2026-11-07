package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ChainKind
import io.github.psd2live.algorithms.binding.LegacyBodyInputs
import io.github.psd2live.algorithms.binding.LegacyFaceInputs
import io.github.psd2live.algorithms.binding.MouthOutlineHair
import io.github.psd2live.algorithms.binding.MouthOutlineHeadAttachment
import io.github.psd2live.algorithms.binding.MouthOutlineV3Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV5Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV6Algorithm
import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldFramesContext
import io.github.psd2live.algorithms.binding.ScaffoldMesh
import io.github.psd2live.algorithms.binding.ScaffoldMeshContext
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

/** Source observations for the opt-in fitted outline on a shared scaffold. */
internal object RigFittedMouthOutlineDefinition {
    private const val TYPE = "mouth.outline"

    private val plugin = object : ReadingModifierAlgorithmPlugin by MouthOutlineV3Algorithm(),
        ModifierVersionSelection, ModifierOptionalUpstream {
        override val canonicalOptInType = TYPE
        override val optionalUpstream = setOf("face/fitted", "face.depth-turn", "body/legacy", "hair/chains")
    }

    fun definition(source: RigModifierSource?) = RigLegacyBindings.definition(RigLegacyBindings.spec(TYPE), source).copy(
        version = MouthOutlineV3Algorithm.VERSION,
        requires = setOf("rig.scaffold", "mouth/fitted"),
        parameters = { emptyList() },
        upstreamInputs = true,
        plugin = plugin,
        changeWrites = setOf("mesh", "deformer", "part", "hierarchy", "textures", "authoring"),
        composableKinds = setOf("mesh", "deformer", "part", "hierarchy"),
        authorContext = { requireNotNull(source).capture(it) },
    )

    fun closedDefinition(source: RigModifierSource?): RigModifierDefinition {
        val closed = object : ReadingModifierAlgorithmPlugin by MouthOutlineV5Algorithm(), ModifierVersionSelection, ModifierOptionalUpstream {
            override val canonicalOptInType = TYPE
            override val optionalUpstream = plugin.optionalUpstream
        }
        return definition(source).copy(version = MouthOutlineV5Algorithm.VERSION, plugin = closed,
            authorContext = { requireNotNull(source).capture(it, closedMouthVersion = 4) })
    }

    fun closedShellDefinition(source: RigModifierSource?): RigModifierDefinition {
        val closed = object : ReadingModifierAlgorithmPlugin by MouthOutlineV6Algorithm(), ModifierVersionSelection, ModifierOptionalUpstream {
            override val canonicalOptInType = TYPE
            override val optionalUpstream = RigLegacyBindings.specs.filter { !it.expressionToggles && !it.seeThrough }
                .mapTo(linkedSetOf()) { it.type } - setOf(TYPE, "mouth/fitted")
        }
        return definition(source).copy(version = MouthOutlineV6Algorithm.VERSION, plugin = closed,
            authorContext = { requireNotNull(source).capture(it, closedMouthVersion = 5) })
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance, closedMouthVersion: Int? = null): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        require(scaffold.version == 2) { "Fitted mouth outline requires a shared v2 scaffold" }
        val prefix = activeBefore(instance).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) &&
                entry.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val mouth = requireNotNull(prefix.singleOrNull { it.type == "mouth/fitted" && instance.targets.all(it.targets::contains) }) {
            "Fitted mouth outline requires one fitted mouth on the same scaffold"
        }
        val closedDrawing = closedMouthVersion != null
        require(!closedDrawing || mouth.version == closedMouthVersion) { "Closed-drawing outline requires fitted mouth v$closedMouthVersion" }
        val head = prefix.lastOrNull { it.type == "face/fitted" || it.type == "face.depth-turn" }
        val body = prefix.singleOrNull { it.type == "body/legacy" }
        val (previous, before) = RigFittedMouthModuleSource.context(this, scaffold, prefix, null)
        val values = RigLegacyBindings.values(instance)
        val recipe = previous.recipe.copy(modules = previous.recipe.modules + (TYPE to ModuleChoice(true, instance.version, values)))
        val desired = previous.copy(recipe = recipe, mouthOutlineEnabled = true, mouthThickness = values["thickness"] ?: 1.5f,
            mouthColor = RigLegacyBindings.outlineColor(instance))
        val after = RigBuilder.rigContext(analysis, desired, meshCache)
        val previousByLayer = before.analysis.layers.associateBy { it.source.id.raw }
        val desiredByLayer = after.analysis.layers.associateBy { it.source.id.raw }
        val owners = instance.targets.associate { target ->
            val layer = layersByMesh.getValue(target.id)
            val parent = after.parentAndFrame(desiredByLayer.getValue(layer), desired).first
            target.id to RigMouthOutlineSource.Owner(layer, primaryByLayer.getValue(layer), mouth.id,
                headOnMesh = head != null && parent?.raw?.startsWith("DeformMouthShape") != true)
        }
        fun mouthInputs(context: RigBuilder.RigContext, settings: PipelineConfig, byLayer: Map<String, ClassifiedLayer>,
                        selected: Set<String>) = owners.filterKeys { it in selected }.mapValues { byLayer.getValue(it.value.layer) }.let { layers ->
            val parents = layers.mapValues { context.parentAndFrame(it.value, settings) }
            RigFittedMouthSource.capture(context, settings, parents.mapValues { it.value.first?.raw },
                layers, parents.mapValues { it.value.second }, layers.keys.filter { !layers.getValue(it).source.clipped }, head?.id)
        }
        val headInput = mouthInputs(after, desired, desiredByLayer, emptySet()).head
        val outline = RigMouthOutlineSource.capture(analysis, desired, owners, headInput, meshCache)
        val selected = outline.targets.keys
        val components = owners.keys.filterTo(linkedSetOf()) { id ->
            val tag = previousByLayer.getValue(owners.getValue(id).layer).semantic.tag
            tag in CharacterAnalyzer.MOUTH_COMPONENT_TAGS || closedDrawing && tag == SemanticTag.MOUTH_CLOSE
        }
        val stationary = owners.keys.filterTo(linkedSetOf()) { id ->
            val owner = owners.getValue(id)
            val oldLayer = previousByLayer.getValue(owner.layer)
            val newLayer = desiredByLayer.getValue(owner.layer)
            oldLayer.semantic.tag == SemanticTag.TOPWEAR &&
                before.parentAndFrame(oldLayer, previous) != after.parentAndFrame(newLayer, desired)
        }
        fun Bounds.observed() = ModifierSourceBounds(left, top, right, bottom)
        fun hair(context: RigBuilder.RigContext, settings: PipelineConfig, byLayer: Map<String, ClassifiedLayer>) =
            RigBuilder.hairChains(context.analysis, context.faceRig, settings).filter { it.kind.hair }.associateBy { it.warpId }.let { chains ->
                owners.mapNotNull { (id, owner) ->
                    val layer = byLayer.getValue(owner.layer)
                    val parent = context.parentAndFrame(layer, settings).first ?: return@mapNotNull null
                    val chain = chains[parent.raw] ?: return@mapNotNull null
                    val front = layer.semantic.tag == SemanticTag.FRONT_HAIR
                    val frame = requireNotNull(if (front) context.frontHair else context.backHair)
                    id to MouthOutlineHair.Phase(chain.layerId, ChainKind.valueOf(chain.kind.name), chain.index,
                        chain.frame.observed(), chain.freeFrom, chain.regions, chain.strength, chain.softness, frame.observed())
                }.toMap()
            }
        val previousHair = if (head == null) emptyMap() else hair(before, previous, previousByLayer)
        val desiredHair = if (head == null) emptyMap() else hair(after, desired, desiredByLayer)
        require(previousHair.keys == desiredHair.keys) { "Fitted outline cannot replace source hair chains" }
        val hairSources = previousHair.mapValues { (id, phase) ->
            val owner = owners.getValue(id)
            val chain = "SourceChain:${phase.kind.word}:${phase.layer.length}:${phase.layer}"
            MouthOutlineHair(owner.canonical, previousByLayer.getValue(owner.layer).semantic.tag.name, chain,
                phase, desiredHair.getValue(id))
        }
        fun attachmentPhase(context: RigBuilder.RigContext, settings: PipelineConfig, layer: ClassifiedLayer): MouthOutlineHeadAttachment.Phase {
            val frame = context.parentAndFrame(layer, settings).second.observed()
            val neck = if (layer.semantic.tag == SemanticTag.NECK &&
                RigModules.builds(RigModules.FACE_NECK, settings, RecipeSubject.of(context.analysis))) {
                val face = context.faceRig
                val (x, y) = face.coordinateSpace.toCanvas(face.centerX, face.mouthLineY)
                val bounds = context.analysis.layers.filter {
                    it.semantic.tag == SemanticTag.NECK && it.semantic.role == null && it.opaquePixels > 0
                }.map { it.bounds }.reduceOrNull(Bounds::union) ?: context.rigLayer(layer).bounds
                MouthOutlineHeadAttachment.Neck(bounds.observed(), x, y)
            } else null
            return MouthOutlineHeadAttachment.Phase(frame, neck)
        }
        val attachments = if (head == null) emptyMap() else owners.mapNotNull { (id, owner) ->
            val layer = previousByLayer.getValue(owner.layer)
            if (layer.semantic.tag !in setOf(SemanticTag.HEADWEAR, SemanticTag.EYEWEAR, SemanticTag.EARWEAR, SemanticTag.NECK))
                return@mapNotNull null
            id to MouthOutlineHeadAttachment(owner.canonical, layer.semantic.tag.name, layer.semantic.role?.name,
                attachmentPhase(before, previous, layer), attachmentPhase(after, desired, desiredByLayer.getValue(owner.layer)))
        }.toMap()
        val fitted = selected + components
        val oldInputs = mouthInputs(before, previous, previousByLayer, fitted)
        val newInputs = mouthInputs(after, desired, desiredByLayer, fitted)
        fun sourceMeshes(ids: Set<String>, context: RigBuilder.RigContext, settings: PipelineConfig,
                         byLayer: Map<String, ClassifiedLayer>) = ScaffoldMeshContext.capture(ids.map(owners::getValue)
            .distinctBy { it.canonical }.map { owner ->
                val layer = byLayer.getValue(owner.layer)
                val data = RigBuilder.fittedMouthSourceMesh(layer, context, settings, atlas, meshCache)
                ScaffoldMeshContext.Material(owner.canonical, owner.layer,
                    ScaffoldMesh.RawMesh(data.rigPositions, data.mesh.uvs, data.mesh.indices), ScaffoldMesh.Geometry.Zero)
            }).resources.getValue("source_meshes")
        val neutral = sourceMeshes(fitted + stationary + hairSources.keys + attachments.keys, before, previous, previousByLayer)
        val componentMeshes = sourceMeshes(components + stationary + hairSources.keys + attachments.keys, after, desired, desiredByLayer)
        val componentSources = components.associateWith { owners.getValue(it).canonical }
        val stationarySources = stationary.associateWith { id ->
            val owner = owners.getValue(id)
            val old = before.parentAndFrame(previousByLayer.getValue(owner.layer), previous).second
            val next = after.parentAndFrame(desiredByLayer.getValue(owner.layer), desired).second
            MouthOutlineV3Algorithm.Stationary(owner.canonical, old.observed(), next.observed())
        }
        val textures = selected.associateWith { id ->
            val layer = previousByLayer.getValue(owners.getValue(id).layer)
            val texture = LayerTexture.packed(layer, atlas.placementByLayerId.getValue(layer.source.id.raw), atlas)
            val placement = requireNotNull(texture.placement)
            MouthOutlineV3Algorithm.Texture(placement.pageIndex, placement.positionX, placement.positionY, placement.scaleX,
                placement.scaleY, placement.rotationDegrees, texture.pageWidth, texture.pageHeight, texture.offsetOriginX,
                texture.offsetOriginY, texture.space.scaleX, texture.space.scaleY)
        }
        val headFrames = if (head == null || selected.isEmpty()) null else {
            val frames = RigBuilder.scaffoldFrameInputs(analysis, previous, meshCache)
            val branch = ScaffoldFramesContext.build(frames).associateBy { it.id }
            val ids = owners.keys.flatMapTo(linkedSetOf()) { id ->
                val layer = previousByLayer.getValue(owners.getValue(id).layer)
                val parent = if (id in hairSources) org.umamo.runtime.model.DeformerId(
                    if (layer.semantic.tag == SemanticTag.FRONT_HAIR) "DeformHairFrontFollow" else "DeformHairBackFollow")
                else before.parentAndFrame(layer, previous).first
                generateSequence(parent) { branch[it]?.parent }.filter { it in branch }.toList()
            }
            fun phase(context: RigBuilder.RigContext, settings: PipelineConfig, byLayer: Map<String, ClassifiedLayer>) =
                buildJsonObject {
                    val structure = RigBuilder.scaffoldSourceInputs(analysis, settings, meshCache)
                    val neutralContext = ModifierAlgorithmContext(buildJsonObject {
                        put("schema", "scaffold/5")
                        put("frames", structure.frames)
                    })
                    val face = context.faceRig
                    val angleParameters = RigLegacyBindings.spec("face/fitted").parameters
                    put("base", LegacyFaceInputs.capture(neutralContext, face.centerY, face.radiusX, face.radiusY,
                        face.eyeLineY, settings.rigTuning.legacyFaceTuning(), settings.headTurnStrength,
                        StandardParameters.all.filter { it.id in angleParameters }.map { it.name }, tr("model.group.face")).values)
                    val layers = selected.associateWith { byLayer.getValue(owners.getValue(it).layer) }
                    val parents = layers.mapValues { context.parentAndFrame(it.value, settings).first }
                    val turn = RigDepthTurnSource.capture(context, settings, layers, parents,
                        ids.associateWith { requireNotNull(context.resolveFrame(it, settings)) }).copy(layers = emptyMap(), mouth = null)
                    put("turn", turn.encode().values)
                    if (body != null) {
                        val source = LegacyBodyInputs.capture(neutralContext, structure.torso, settings.rigTuning.bodyMotion(),
                            emptyMap(), emptyMap()).values
                        put("body", JsonObject(source.filterKeys { it == "torso" || it == "tuning" } +
                            ("strength" to JsonPrimitive(settings.bodyStrength))))
                    }
                }
            val representative = instance.targets.first().id
            buildJsonObject {
                put("before", phase(before, previous, previousByLayer))
                put("after", phase(after, desired, desiredByLayer))
                putJsonObject("aliases") {
                    ids.forEach { id -> put(id.raw, ScaffoldBinding.deformerId(scaffold, representative, id.raw)) }
                }
            }
        }
        val captured = MouthOutlineV3Algorithm.capture(outline.encode(), oldInputs, newInputs, neutral, textures, headFrames,
            componentSources, componentMeshes, stationarySources, hairSources, attachments)
        if (closedMouthVersion != 5 || before.faceTurn(previous) != null || after.faceTurn(desired) != null) return captured
        val shell = owners.filterKeys { it !in fitted && it !in hairSources }.mapValues { (_, owner) ->
            desiredByLayer.getValue(owner.layer)
        }
        val parameters = prefix.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters }
        return ModifierAlgorithmContext(captured.values, captured.resources + mapOf(
            "outlineShellBefore" to outlineShellSource(before, previous, shell, parameters),
            "outlineShellAfter" to outlineShellSource(after, desired, shell, parameters),
        ))
    }
}