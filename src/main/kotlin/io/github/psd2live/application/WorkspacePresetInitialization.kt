package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.BrowBlinkV3Algorithm
import io.github.psd2live.algorithms.binding.BrowFittedV3Binding
import io.github.psd2live.algorithms.binding.BrowLegacyV2Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV5Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeV3Algorithm
import io.github.psd2live.algorithms.binding.BrowSpringV4Algorithm
import io.github.psd2live.algorithms.binding.EyeAftershockV4Algorithm
import io.github.psd2live.algorithms.binding.EyeEmotionV3Algorithm
import io.github.psd2live.algorithms.binding.EyeFittedV5Algorithm
import io.github.psd2live.algorithms.binding.EyeGazeLidV3Algorithm
import io.github.psd2live.algorithms.binding.EyeHighlightV3Algorithm
import io.github.psd2live.algorithms.binding.EyeLegacySourceAlgorithm
import io.github.psd2live.algorithms.binding.EyeSmileV3Algorithm
import io.github.psd2live.algorithms.binding.EyeSquashV4Algorithm
import io.github.psd2live.algorithms.binding.EyeWideV3Algorithm
import io.github.psd2live.algorithms.binding.FittedFaceV3Algorithm
import io.github.psd2live.algorithms.binding.LegacyBodyV2Algorithm
import io.github.psd2live.algorithms.binding.LegacyFaceV2Algorithm
import io.github.psd2live.algorithms.binding.MouthLegacyAlgorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV4Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.*
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** A preset is a recipe for explicit instances, never a second model-generation pipeline. */
internal object WorkspacePresetInitialization {
    fun create(document: WorkspaceDocument, work: WorkspaceRasterWork,
               choices: WorkspaceInitialPreset = WorkspaceInitialPreset()): WorkspaceDocument =
        prepare(document, work, choices, preferSourceBindings = true).first

    fun prepare(document: WorkspaceDocument, work: WorkspaceRasterWork,
                choices: WorkspaceInitialPreset = WorkspaceInitialPreset(),
                preferSourceBindings: Boolean = false,
                requestedVersions: Map<String, Int> = emptyMap()): Pair<WorkspaceDocument, RigPreviewModel> {
        val config = document.config()
        var stack = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
        require(stack.instances.isEmpty()) { "Initialize presets only into an empty stack" }
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        fun current() = document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
        fun build(stage: String): RigPreviewModel = RigBuildProfile.stage("source preset: $stage") {
            work.checkpoint()
            val candidate = current()
            pipeline.buildPreview(candidate.source, candidate.config(), ProgressListener { _, _ -> work.checkpoint() })
        }
        // Planning needs the package graph, but making the ordinary source meshes only needs the empty stack.
        val catalog = if (preferSourceBindings) FutureTask { RigModifierAlgorithms.registry }.also {
            Thread.ofVirtual().name("psd2live-preset-catalog").start(it)
        } else null
        var model: RigPreviewModel
        val targets = try {
            model = build("author preview")
            model.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in model.rig.ownLayerIdByDrawableId }
                .map { ModifierTarget("mesh", it.id.raw) }.also {
                    if (it.isNotEmpty() && catalog != null) RigBuildProfile.stage("source preset: wait for modifier catalog") {
                        try { catalog.get() }
                        catch (failure: ExecutionException) { throw failure.cause ?: failure }
                    }
                }
        } finally {
            catalog?.cancel(true)
        }
        if (targets.isEmpty()) {
            require(requestedVersions.isEmpty()) { "Cannot select preset versions without mesh targets" }
            model.runtimeBundle.materialize()
            return document to model
        }
        val subject = RecipeSubject.of(model.analysis)
        val fittedFace = RigModules.builds(RigModules.FACE_DEPTH_TURN, config, subject)
        val types = buildList {
            for (area in listOf("body", "face", "eye", "brow", "mouth", "hair"))
                add(if (area == "face" && fittedFace) RigLegacyBindings.FITTED_FACE else config.recipe.base(area).id)
            addAll(RigModules.modules.filter { RigModules.builds(it, config, subject) && it != RigModules.FACE_DEPTH_TURN }
                .map { it.id }.filter { RigLegacyBindings.find(it) != null })
        }
        val group = if (config.recipe.modern) "modern" else "legacy"
        stack = WorkspaceModifierPlan.plan(model, stack, buildJsonObject {
            put("group", "$group-standard")
            put("targets", JsonArray(targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
        }, types).first
        val lockedVersions = requestedVersions.mapValues { (type, version) ->
            require(stack.instances.any { it.type == type }) { "Preset has no modifier of type $type" }
            val reference = requireNotNull(BuiltinModifierPlugins.reference(type, version)) {
                "Unknown bundled modifier $type@$version"
            }
            require(ModifierPluginCatalog.available(type, version, reference)) { "Bundled modifier $type@$version is unavailable" }
            version to reference
        }
        val scaffold = stack.instances.singleOrNull { it.type == RigSourceModifiers.SCAFFOLD && it.version == 2 }
        val fittedSources = if (preferSourceBindings && scaffold != null && config.recipe == RigRecipe.Modern && fittedFace &&
            !config.featureDisplacementEnabled && config.rigTuning == RigTuning() && requestedVersions.isEmpty() && freshSource(config)) {
            fun packages(versions: Map<String, Int>): Map<String, Pair<Int, ModifierPluginReference>> {
                val selected = versions.mapNotNull { (type, version) ->
                    val entry = stack.instances.singleOrNull { it.type == type } ?: return@mapNotNull null
                    if (entry.config["scaffold"] != JsonPrimitive(scaffold.id) || entry.targets.toSet() != scaffold.targets.toSet()) return@mapNotNull null
                    val reference = BuiltinModifierPlugins.reference(type, version) ?: return@mapNotNull null
                    if (!ModifierPluginCatalog.available(type, version, reference)) return@mapNotNull null
                    type to (version to reference)
                }
                return if (selected.size == versions.size) selected.toMap() else emptyMap()
            }
            val headAndEye = packages(mapOf(RigLegacyBindings.FITTED_FACE to FittedFaceV3Algorithm.VERSION,
                RigModules.EYE_FITTED.id to EyeFittedV5Algorithm.VERSION))
            if (headAndEye.isEmpty()) headAndEye else {
                // These modules share fitted lid and head-turn inputs. Use a single compatible sequence.
                val lidVersions = mapOf(RigModules.EYE_SMILE.id to EyeSmileV3Algorithm.VERSION,
                    RigModules.EYE_WIDE.id to EyeWideV3Algorithm.VERSION, RigModules.EYE_GAZE_LID.id to EyeGazeLidV3Algorithm.VERSION,
                    RigModules.EYE_EMOTION.id to EyeEmotionV3Algorithm.VERSION,
                    RigModules.EYE_HIGHLIGHT.id to EyeHighlightV3Algorithm.VERSION,
                    RigModules.EYE_AFTERSHOCK.id to EyeAftershockV4Algorithm.VERSION,
                    RigModules.EYE_SQUASH.id to EyeSquashV4Algorithm.VERSION)
                val plannedLids = lidVersions.filterKeys { type -> stack.instances.any { it.type == type } }
                val lids = packages(plannedLids)
                val brows = if (lids.isNotEmpty() && subject.has(SemanticTag.EYEBROW)) {
                    val browVersions = mapOf(RigModules.BROW_FITTED.id to BrowFittedV3Binding.VERSION,
                        RigModules.BROW_SHAPE.id to BrowShapeV3Algorithm.VERSION,
                        RigModules.BROW_LID.id to BrowLidV5Algorithm.VERSION,
                        RigModules.BROW_BLINK.id to BrowBlinkV3Algorithm.VERSION,
                        RigModules.BROW_SPRING.id to BrowSpringV4Algorithm.VERSION)
                    packages(browVersions.filterKeys { type -> stack.instances.any { it.type == type } })
                } else emptyMap()
                headAndEye + lids + brows
            }
        } else emptyMap()
        val sourceVersions = if (preferSourceBindings && scaffold != null) buildMap {
            put(LegacyBodyV2Algorithm.TYPE, LegacyBodyV2Algorithm.VERSION)
            if (!config.featureDisplacementEnabled) {
                put(LegacyFaceV2Algorithm.TYPE, LegacyFaceV2Algorithm.VERSION)
                // These source versions are verified for a fresh legacy-style scaffold and ordinary head turn.
                if (!config.recipe.modern && config.rigTuning == RigTuning() && config.rigEdits.authoringJournal.isEmpty()) {
                    put(EyeLegacySourceAlgorithm.TYPE, EyeLegacySourceAlgorithm.VERSION)
                    put(BrowLegacyV2Algorithm.TYPE, BrowLegacyV2Algorithm.VERSION)
                    val faceVersion = lockedVersions[LegacyFaceV2Algorithm.TYPE]?.first ?: LegacyFaceV2Algorithm.VERSION
                    val mouthVersion = lockedVersions[MouthLegacyAlgorithm.TYPE]?.first
                        ?: stack.instances.singleOrNull { it.type == MouthLegacyAlgorithm.TYPE }?.version
                    if (MouthOutlineV4Algorithm.TYPE !in lockedVersions &&
                        stack.instances.any { it.type == MouthOutlineV4Algorithm.TYPE } &&
                        (lockedVersions[RigSourceModifiers.SCAFFOLD]?.first ?: scaffold.version) == 2 &&
                        faceVersion == LegacyFaceV2Algorithm.VERSION && mouthVersion == MouthLegacyAlgorithm.VERSION &&
                        ModifierPluginCatalog.available(LegacyFaceV2Algorithm.TYPE, LegacyFaceV2Algorithm.VERSION) &&
                        supportsClassicOutline(model.analysis, config)) {
                        put(MouthOutlineV4Algorithm.TYPE, MouthOutlineV4Algorithm.VERSION)
                    }
                }
            }
        } else emptyMap()
        stack = stack.copy(instances = stack.instances.map { entry ->
            val spec = RigLegacyBindings.find(entry.type)
            val settings = buildJsonObject {
                entry.config.forEach { (key, value) -> put(key, value) }
                if (entry.type == RigSourceModifiers.SCAFFOLD) {
                    put("featureDisplacement", config.featureDisplacementEnabled)
                    put("frontHairSway", !config.hairSimulationFront); put("backHairSway", !config.hairSimulationBack)
                }
                if (spec != null) {
                    if (spec.strength) put("strength", if (spec.area == "body") config.bodyStrength else config.headTurnStrength)
                    if (spec.fields.isNotEmpty()) putJsonObject("tuning") { spec.fields.forEach { put(it.id, it.get(config.rigTuning)) } }
                    if (spec.hasPhysics) put("physicsEnabled", config.generatePhysics)
                    if (spec.mouthOutline) config.mouthColor?.let { put("color", it and 0xffffff) }
                    if (spec.knobs.isNotEmpty()) putJsonObject("values") {
                        for (knob in spec.knobs) put(knob.id, when {
                            spec.seeThrough -> config.seeThroughOpacity * 100f
                            spec.mouthOutline -> config.mouthThickness
                            entry.type == RigLegacyBindings.FITTED_FACE -> config.recipe.value(RigModules.FACE_DEPTH_TURN.id, knob.id)
                            spec.module != null -> config.recipe.value(entry.type, knob.id)
                            else -> config.recipe.baseValue(spec.area, knob.id)
                        })
                    }
                }
            }
            val configured = entry.copy(config = settings, presetGroup = group)
            (lockedVersions[entry.type] ?: fittedSources[entry.type])?.let { (version, plugin) ->
                return@map configured.copy(version = version, plugin = plugin)
            }
            val sourceVersion = sourceVersions[entry.type]
            if (sourceVersion != null && scaffold != null && entry.version == 1 && settings["scaffold"] == JsonPrimitive(scaffold.id) &&
                entry.targets.toSet() == scaffold.targets.toSet()) {
                val plugin = BuiltinModifierPlugins.reference(entry.type, sourceVersion) ?: return@map configured
                val independent = configured.copy(version = sourceVersion, plugin = plugin)
                independent.takeIf(ModifierPluginCatalog::available) ?: configured
            } else configured
        })
        model = build("binding preview")
        val available = model.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }
        val claimed = model.rig.modifierOutput!!.physics.groups.flatMapTo(HashSet()) { it.outputs.map { output -> output.parameter } }
        if (config.generatePhysics) for (rule in PhysicsGenerator.presetRules(
            PhysicsGenerator.Presets(config.physicsFrontHair, config.physicsBackHair, config.physicsEyeJelly), available)) {
            if (PhysicsCatalog.issueOf(rule, available) != null || rule.outputs.any { it.parameter in claimed }) continue
            stack = stack.add("physics", 1, targets, rule.toJson(), group)
            claimed += rule.outputs.map { it.parameter }
        }
        if (config.exportMotions) for (name in MotionClips.BUILTIN_NAMES) {
            val enabled = when (name) {
                "Idle" -> config.motionBasic && config.motionIdle
                "Blink" -> config.motionBasic && config.motionBlink
                "Nod" -> config.motionBasic && config.motionNod
                "Shake" -> config.motionBasic && config.motionShake
                "Talk" -> config.motionBasic && MotionPresetSettings.natural(config)
                else -> config.motionSkeleton
            }
            val settings = MotionPresetSettings.of(config, name)
            if (!enabled || settings.deleted || settings.disabled) continue
            val tracks = MotionPresets.tracks(name, config.rigEdits.skeleton, settings,
                if (name == "Idle" && config.exportIncludePhysics) claimed else emptySet()).filter { it.parameterId in available }
            if (tracks.isEmpty()) continue
            val clip = MotionClips.fromTracks("template", name, null, MotionPresets.loops(name), tracks,
                MotionPresets.duration(name, tracks, settings))
            stack = stack.add("motion", 1, targets, MotionClips.toJson(clip.copy(parameterRoles =
                if (name == "Blink") mapOf("EyeBlink" to listOf("ParamEyeLOpen", "ParamEyeROpen").filter { it in available }) else emptyMap())), group)
        }
        // Physics and clips reference the binding parameters without changing the rest geometry.
        // All simulation presets can therefore plan against the same evaluated binding.
        val selected = buildList {
            val present = PhysicsGenerator.Presets.present(model.analysis)
            if (present.frontHair && choices.frontHair == WorkspaceInitialHair.SIMULATION) add("front_hair")
            if (present.backHair && choices.backHair == WorkspaceInitialHair.SIMULATION) add("back_hair")
            if (choices.clothing && model.analysis.layers.any { it.semantic.tag in ModelPresets.CLOTHING_TAGS && it.opaquePixels > 0 }) add("clothing")
        }
        for (preset in selected) {
            stack = WorkspaceModifierPlan.simulationPreset(model, stack, targets, preset, allowNoLooseClothing = true).first
        }
        stack = stack.copy(instances = stack.instances.map {
            WorkspaceModifierEdits.captureParameterProviders(it.copy(presetGroup = group), model, stopAtConsumer = true)
        })
        RigModifierAlgorithms.evaluator().validateOrder(stack)
        stack = RigBuildProfile.stage("source preset: simulation preparation") {
            WorkspaceModifierSimulation.prepare(current(), stack, work, pipeline = pipeline)
        }
        return current() to build("final preview").also { work.checkpoint(); it.runtimeBundle.materialize(); work.checkpoint() }
    }

    private fun freshSource(config: PipelineConfig): Boolean =
        config.rigEdits.copy(modifierStack = null) == RigEditOverlay.Empty &&
            config.meshOverrides.isEmpty() && config.parentOverrides.isEmpty() &&
            !config.deferRig && config.rigPendingLayerIds.isEmpty()

    private fun supportsClassicOutline(analysis: PipelineAnalysis, config: PipelineConfig): Boolean {
        if (!freshSource(config)) return false
        val mouthLayers = analysis.layers.filter {
            it.semantic.tag == SemanticTag.MOUTH || it.semantic.tag == SemanticTag.MOUTH_OPEN
        }
        if (mouthLayers.any { it.semantic.role != null }) return false
        val artwork = mouthLayers.filter { it.opaquePixels > 0 }
        if (artwork.isEmpty()) return false
        val alignment = MouthLipLayers.rasterAlignment(analysis, config, PrimitiveResolution.Inactive)
        val unitScale = MeshResolution.unitScale(config, analysis.source)
        return artwork.all { MouthLipLayers.trace(it, config, unitScale, alignment).size >= 2 }
    }
}
