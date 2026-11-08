package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.ArmSwingV3Algorithm
import io.github.psd2live.algorithms.binding.ArmSwingV4Algorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV3Algorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV4Algorithm
import io.github.psd2live.algorithms.binding.BrowBlinkV5Algorithm
import io.github.psd2live.algorithms.binding.BrowFittedV3Binding
import io.github.psd2live.algorithms.binding.BrowFittedV4Binding
import io.github.psd2live.algorithms.binding.BrowLegacyV2Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV5Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV6Algorithm
import io.github.psd2live.algorithms.binding.BrowLidV7Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeV3Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeV4Algorithm
import io.github.psd2live.algorithms.binding.BrowShapeV5Algorithm
import io.github.psd2live.algorithms.binding.BrowSpringV4Algorithm
import io.github.psd2live.algorithms.binding.BrowSpringV5Algorithm
import io.github.psd2live.algorithms.binding.BrowSpringV6Algorithm
import io.github.psd2live.algorithms.binding.BustBindingV3Algorithm
import io.github.psd2live.algorithms.binding.CheekCarrierAlgorithm
import io.github.psd2live.algorithms.binding.CreatureAlgorithm
import io.github.psd2live.algorithms.binding.CreatureResidualAlgorithm
import io.github.psd2live.algorithms.binding.EyeAftershockV4Algorithm
import io.github.psd2live.algorithms.binding.EyeAftershockV5Algorithm
import io.github.psd2live.algorithms.binding.EyeEmotionV3Algorithm
import io.github.psd2live.algorithms.binding.EyeEmotionV4Algorithm
import io.github.psd2live.algorithms.binding.EyeFittedV5Algorithm
import io.github.psd2live.algorithms.binding.EyeGazeLidV3Algorithm
import io.github.psd2live.algorithms.binding.EyeGazeLidV4Algorithm
import io.github.psd2live.algorithms.binding.EyeHighlightV3Algorithm
import io.github.psd2live.algorithms.binding.EyeHighlightV4Algorithm
import io.github.psd2live.algorithms.binding.EyeLegacySourceAlgorithm
import io.github.psd2live.algorithms.binding.EyeSmileV3Algorithm
import io.github.psd2live.algorithms.binding.EyeSmileV4Algorithm
import io.github.psd2live.algorithms.binding.EyeSquashV4Algorithm
import io.github.psd2live.algorithms.binding.EyeSquashV5Algorithm
import io.github.psd2live.algorithms.binding.EyeWideV3Algorithm
import io.github.psd2live.algorithms.binding.EyeWideV4Algorithm
import io.github.psd2live.algorithms.binding.FittedFaceV3Algorithm
import io.github.psd2live.algorithms.binding.GloomCarrierAlgorithm
import io.github.psd2live.algorithms.binding.LegacyBodyV2Algorithm
import io.github.psd2live.algorithms.binding.LegacyFaceV2Algorithm
import io.github.psd2live.algorithms.binding.LidFadeV3Algorithm
import io.github.psd2live.algorithms.binding.MouthCaptureV4Algorithm
import io.github.psd2live.algorithms.binding.MouthCaptureV5Algorithm
import io.github.psd2live.algorithms.binding.MouthCaptureV6Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV5Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV6Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV7Algorithm
import io.github.psd2live.algorithms.binding.MouthCheekLidV8Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV3Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV4Algorithm
import io.github.psd2live.algorithms.binding.MouthDepthV5Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV3Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV4Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV5Algorithm
import io.github.psd2live.algorithms.binding.MouthFittedV6Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV3Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV5Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV6Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV7Algorithm
import io.github.psd2live.algorithms.binding.MouthJawV8Algorithm
import io.github.psd2live.algorithms.binding.MouthLegacyAlgorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV4Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV5Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV6Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV7Algorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV8Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV4Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV6Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV7Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV8Algorithm
import io.github.psd2live.algorithms.binding.MouthSpringV9Algorithm
import io.github.psd2live.algorithms.binding.MouthTongueV4Algorithm
import io.github.psd2live.algorithms.binding.MouthTongueV5Algorithm
import io.github.psd2live.algorithms.binding.MouthVowelV5Algorithm
import io.github.psd2live.algorithms.binding.MouthVowelV6Algorithm
import io.github.psd2live.algorithms.binding.MouthVowelV7Algorithm
import io.github.psd2live.algorithms.binding.NeckV3Algorithm
import io.github.psd2live.algorithms.binding.RibbonsResidualAlgorithm
import io.github.psd2live.algorithms.binding.RibbonsV4Algorithm
import io.github.psd2live.algorithms.binding.RibbonsV5Algorithm
import io.github.psd2live.algorithms.binding.SkirtAlgorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.HairKind
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
        prepare(document, work, choices).first

    fun prepare(document: WorkspaceDocument, work: WorkspaceRasterWork,
                choices: WorkspaceInitialPreset = WorkspaceInitialPreset(),
                requestedVersions: Map<String, Int> = emptyMap(),
                cliBindings: CliPresetBindings? = null,
                restoreSourceRotations: Boolean = false): Pair<WorkspaceDocument, RigPreviewModel> {
        val config = document.config()
        var stack = RigModifierStack.fromJson(requireNotNull(document.rigEdits.modifierStack))
        require(stack.instances.isEmpty()) { "Initialize presets only into an empty stack" }
        val pipeline = PSD2LivePipeline().apply {
            deferRuntimeBundles = true
            rawSourceRotations = restoreSourceRotations && cliBindings != null && freshSource(config)
        }
        fun current() = document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
        fun build(stage: String): RigPreviewModel = RigBuildProfile.stage("source preset: $stage") {
            work.checkpoint()
            val candidate = current()
            pipeline.buildPreview(candidate.source, candidate.config(), ProgressListener { _, _ -> work.checkpoint() })
        }
        // Planning needs the package graph, but making the ordinary source meshes only needs the empty stack.
        val catalog = FutureTask { RigModifierAlgorithms.registry }.also {
            Thread.ofVirtual().name("psd2live-preset-catalog").start(it)
        }
        var model: RigPreviewModel
        val targets = try {
            model = build("author preview")
            model.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in model.rig.ownLayerIdByDrawableId }
                .map { ModifierTarget("mesh", it.id.raw) }.also {
                    if (it.isNotEmpty()) RigBuildProfile.stage("source preset: wait for modifier catalog") {
                        try { catalog.get() }
                        catch (failure: ExecutionException) { throw failure.cause ?: failure }
                    }
                }
        } finally {
            catalog.cancel(true)
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
            entry.copy(config = settings, presetGroup = group)
        })
        val lockedVersions = requestedVersions.mapValues { (type, version) ->
            require(stack.instances.any { it.type == type }) { "Preset has no modifier of type $type" }
            val reference = requireNotNull(BuiltinModifierPlugins.reference(type, version)) {
                "Unknown bundled modifier $type@$version"
            }
            require(ModifierPluginCatalog.available(type, version, reference)) { "Bundled modifier $type@$version is unavailable" }
            version to reference
        }
        val scaffold = stack.instances.singleOrNull { it.type == RigSourceModifiers.SCAFFOLD && it.version == 2 }
        val eyeWhiteSides = subject.sides(SemanticTag.EYEWHITE)
        val lashSides = subject.sides(SemanticTag.EYELASH)
        val completeEyePair = eyeWhiteSides.size == 2 && lashSides.size == 2
        val singleEyeClosedMouth = eyeWhiteSides.size == 1 && lashSides == eyeWhiteSides &&
            subject.sides(SemanticTag.EYEBROW) == eyeWhiteSides && subject.has(SemanticTag.MOUTH_CLOSE)
        val fittedSources = if (scaffold != null && config.recipe == RigRecipe.Modern && fittedFace &&
            !config.featureDisplacementEnabled && config.rigTuning == RigTuning() && requestedVersions.isEmpty() && freshSource(config) &&
            (!subject.has(SemanticTag.EYEBROW) || completeEyePair || singleEyeClosedMouth)) {
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
            val headVersions = mapOf(RigLegacyBindings.FITTED_FACE to FittedFaceV3Algorithm.VERSION,
                RigModules.EYE_FITTED.id to EyeFittedV5Algorithm.VERSION)
            val lidVersions = mapOf(RigModules.EYE_SMILE.id to EyeSmileV3Algorithm.VERSION,
                RigModules.EYE_WIDE.id to EyeWideV3Algorithm.VERSION, RigModules.EYE_GAZE_LID.id to EyeGazeLidV3Algorithm.VERSION,
                RigModules.EYE_EMOTION.id to EyeEmotionV3Algorithm.VERSION,
                RigModules.EYE_HIGHLIGHT.id to EyeHighlightV3Algorithm.VERSION,
                RigModules.EYE_AFTERSHOCK.id to EyeAftershockV4Algorithm.VERSION,
                RigModules.EYE_SQUASH.id to EyeSquashV4Algorithm.VERSION)
            val plannedTypes = stack.instances.mapTo(HashSet()) { it.type }
            val plannedLids = lidVersions.filterKeys { it in plannedTypes }
            val moodVersions = mapOf(RigModules.EXPRESSION_CHEEK.id to CheekCarrierAlgorithm.VERSION,
                RigModules.EXPRESSION_GLOOM.id to GloomCarrierAlgorithm.VERSION).filterKeys { it in plannedTypes }
            val headAndEye = packages(headVersions)
            val brow = stack.instances.singleOrNull { it.type == RigModules.BROW_FITTED.id }
            val source by lazy { RigModifierSource.of(model) }
            val sharedBrow = completeEyePair && headAndEye.isNotEmpty() && brow != null &&
                brow.targets.toSet() == scaffold.targets.toSet() &&
                RigBrowFittedModifier.requiresSharedFrames(source, scaffold,
                    stack.instances.take(stack.index(brow.id)).filter { it.config["scaffold"] == JsonPrimitive(scaffold.id) }, brow)
            if (headAndEye.isEmpty()) headAndEye else if (sharedBrow) {
                val eyeShape = setOf(RigModules.EYE_SMILE.id, RigModules.EYE_WIDE.id, RigModules.EYE_GAZE_LID.id, RigModules.EYE_EMOTION.id)
                if (plannedTypes.containsAll(eyeShape)) {
                    val sharedVersions = mapOf(
                        RigModules.BROW_FITTED.id to BrowFittedV4Binding.VERSION,
                        RigModules.BROW_SHAPE.id to BrowShapeV5Algorithm.VERSION,
                        RigModules.BROW_LID.id to BrowLidV7Algorithm.VERSION,
                        RigModules.BROW_BLINK.id to BrowBlinkV5Algorithm.VERSION,
                        RigModules.BROW_SPRING.id to BrowSpringV6Algorithm.VERSION,
                        RigModules.EYE_SMILE.id to EyeSmileV4Algorithm.VERSION,
                        RigModules.EYE_WIDE.id to EyeWideV4Algorithm.VERSION,
                        RigModules.EYE_GAZE_LID.id to EyeGazeLidV4Algorithm.VERSION,
                        RigModules.EYE_EMOTION.id to EyeEmotionV4Algorithm.VERSION,
                        RigModules.EYE_LID_FADE.id to LidFadeV3Algorithm.VERSION,
                        RigModules.EYE_HIGHLIGHT.id to EyeHighlightV4Algorithm.VERSION,
                        RigModules.EYE_AFTERSHOCK.id to EyeAftershockV5Algorithm.VERSION,
                        RigModules.EYE_SQUASH.id to EyeSquashV5Algorithm.VERSION,
                    )
                    val frameBindings = packages(headVersions + sharedVersions.filterKeys { it in plannedTypes })
                    if (frameBindings.isEmpty()) frameBindings else {
                        val moods = if (RigCheekModifier.sourceMoodParametersAvailable(source, scaffold))
                            packages(moodVersions) else emptyMap()
                        val mouth = packages(sourceMouthVersions(model.analysis, config, types, sharedBrow = true))
                        val arms = if (RigModules.MOUTH_FITTED.id in mouth) sourceArmVersion(model.analysis, closedMouth = false)
                            ?.let { packages(mapOf(RigModules.BODY_ARMS.id to it)) }.orEmpty() else emptyMap()
                        val attachments = buildMap {
                            if (RigModules.FACE_NECK.id in plannedTypes) put(RigModules.FACE_NECK.id, NeckV3Algorithm.VERSION)
                            if (RigModules.OUTFIT_CREATURE.id in plannedTypes) {
                                val kinds = model.analysis.layers.filter { it.opaquePixels > 0 }.mapNotNull { HairKind.partOf(it) }.toSet()
                                if (kinds == setOf(HairKind.ANIMAL_TAIL)) put(RigModules.OUTFIT_CREATURE.id, CreatureAlgorithm.VERSION)
                            }
                        }
                        frameBindings + mouth + moods + arms + packages(attachments)
                    }
                } else emptyMap()
            } else if (singleEyeClosedMouth) {
                val mouth = sourceMouthVersions(model.analysis, config, types, singleEye = true)
                if (mouth.isEmpty()) emptyMap() else {
                    val brows = mapOf(RigModules.BROW_FITTED.id to BrowFittedV3Binding.VERSION,
                        RigModules.BROW_SHAPE.id to BrowShapeV4Algorithm.VERSION,
                        RigModules.BROW_LID.id to BrowLidV6Algorithm.VERSION,
                        RigModules.BROW_BLINK.id to BrowBlinkV4Algorithm.VERSION,
                        RigModules.BROW_SPRING.id to BrowSpringV5Algorithm.VERSION,
                        RigModules.EYE_LID_FADE.id to LidFadeV3Algorithm.VERSION).filterKeys { it in plannedTypes }
                    packages(headVersions + plannedLids + brows + mouth + moodVersions)
                }
            } else {
                val lids = packages(plannedLids)
                val brows = if (lids.isNotEmpty() && subject.has(SemanticTag.EYEBROW)) {
                    val browVersions = mapOf(RigModules.BROW_FITTED.id to BrowFittedV3Binding.VERSION,
                        RigModules.BROW_SHAPE.id to BrowShapeV3Algorithm.VERSION,
                        RigModules.BROW_LID.id to BrowLidV5Algorithm.VERSION,
                        RigModules.BROW_BLINK.id to BrowBlinkV3Algorithm.VERSION,
                        RigModules.BROW_SPRING.id to BrowSpringV4Algorithm.VERSION)
                    packages(browVersions.filterKeys { type -> stack.instances.any { it.type == type } })
                } else emptyMap()
                val extensions = if (lids.size == plannedLids.size && (!subject.has(SemanticTag.EYEBROW) || brows.isNotEmpty())) {
                    val mouth = if (completeEyePair && (subject.has(SemanticTag.MOUTH) || subject.has(SemanticTag.MOUTH_OPEN)))
                        packages(sourceMouthVersions(model.analysis, config, types)) else emptyMap()
                    val armVersion = sourceArmVersion(model.analysis,
                        closedMouth = mouth[RigModules.MOUTH_FITTED.id]?.first == MouthFittedV4Algorithm.VERSION)
                    val arms = armVersion?.let { packages(mapOf(RigModules.BODY_ARMS.id to it)) }.orEmpty()
                    val neck = if (completeEyePair) packages(mapOf(RigModules.FACE_NECK.id to NeckV3Algorithm.VERSION)) else emptyMap()
                    mouth + arms + neck + packages(moodVersions)
                } else emptyMap()
                headAndEye + lids + brows + extensions
            }
        } else emptyMap()
        val sourceVersions = if (scaffold != null) buildMap {
            put(LegacyBodyV2Algorithm.TYPE, LegacyBodyV2Algorithm.VERSION)
            if (!config.featureDisplacementEnabled) {
                put(LegacyFaceV2Algorithm.TYPE, LegacyFaceV2Algorithm.VERSION)
                if (SkirtAlgorithm.TYPE in types && config.recipe == RigRecipe.Modern &&
                    config.rigTuning == RigTuning() && requestedVersions.isEmpty() &&
                    freshSource(config) && model.analysis.layers.none {
                        it.opaquePixels > 0 && it.semantic.role == PartRole.SKIRT &&
                            it.semantic.tag !in setOf(SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR)
                    }) put(SkirtAlgorithm.TYPE, SkirtAlgorithm.VERSION)
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
            (lockedVersions[entry.type] ?: fittedSources[entry.type])?.let { (version, plugin) ->
                return@map entry.copy(version = version, plugin = plugin)
            }
            val sourceVersion = sourceVersions[entry.type]
            if (sourceVersion != null && scaffold != null && entry.version == 1 && entry.config["scaffold"] == JsonPrimitive(scaffold.id) &&
                entry.targets.toSet() == scaffold.targets.toSet()) {
                val plugin = BuiltinModifierPlugins.reference(entry.type, sourceVersion) ?: return@map entry
                val independent = entry.copy(version = sourceVersion, plugin = plugin)
                independent.takeIf(ModifierPluginCatalog::available) ?: entry
            } else entry
        })
        if (fittedSources.isNotEmpty()) {
            val fittedBody = mapOf(RigSourceModifiers.SCAFFOLD to 2, LegacyBodyV2Algorithm.TYPE to LegacyBodyV2Algorithm.VERSION,
                RigLegacyBindings.FITTED_FACE to FittedFaceV3Algorithm.VERSION)
            stack = stack.copy(instances = stack.instances.mapIndexed { index, entry ->
                val upstream = stack.instances.take(index)
                val (version, profile) = when {
                    entry.type == RigModules.OUTFIT_RIBBONS.id && entry.version == 1 -> {
                        val mouth = upstream.singleOrNull { it.type == RigModules.MOUTH_FITTED.id }?.version
                        val skirt = upstream.singleOrNull { it.type == RigModules.OUTFIT_SKIRT.id }?.version
                        when {
                            mouth == MouthFittedV6Algorithm.VERSION && (skirt == null || skirt == SkirtAlgorithm.VERSION) ->
                                RibbonsV5Algorithm.VERSION to buildMap {
                                    put(RigModules.EYE_FITTED.id, EyeFittedV5Algorithm.VERSION)
                                    put(RigModules.BROW_FITTED.id, BrowFittedV4Binding.VERSION)
                                    put(RigModules.MOUTH_FITTED.id, mouth)
                                    if (skirt != null) put(RigModules.OUTFIT_SKIRT.id, skirt)
                                }
                            mouth == MouthFittedV3Algorithm.VERSION && skirt == SkirtAlgorithm.VERSION ->
                                RibbonsV4Algorithm.VERSION to (RibbonsResidualAlgorithm.PROFILE + (RigModules.MOUTH_FITTED.id to mouth))
                            mouth == MouthFittedV4Algorithm.VERSION && skirt == null ->
                                RibbonsV4Algorithm.VERSION to (RibbonsResidualAlgorithm.PROFILE - RigModules.OUTFIT_SKIRT.id)
                            else -> RibbonsResidualAlgorithm.VERSION to RibbonsResidualAlgorithm.PROFILE
                        }
                    }
                    entry.type == RigModules.OUTFIT_CREATURE.id && entry.version == 1 ->
                        CreatureResidualAlgorithm.VERSION to CreatureResidualAlgorithm.PROFILE
                    entry.type == RigModules.BODY_BUST.id && entry.version == 2 && completeEyePair -> {
                        val mouth = upstream.singleOrNull { it.type == RigModules.MOUTH_FITTED.id }?.version ?: return@mapIndexed entry
                        if (mouth !in setOf(MouthFittedV3Algorithm.VERSION, MouthFittedV4Algorithm.VERSION, MouthFittedV6Algorithm.VERSION))
                            return@mapIndexed entry
                        BustBindingV3Algorithm.VERSION to mapOf(RigModules.EYE_FITTED.id to EyeFittedV5Algorithm.VERSION,
                            RigModules.MOUTH_FITTED.id to mouth)
                    }
                    else -> return@mapIndexed entry
                }
                val compatible = (fittedBody + profile).all { (type, required) -> upstream.any {
                    it.type == type && it.version == required && it.targets.containsAll(entry.targets)
                } }
                if (!compatible) return@mapIndexed entry
                val plugin = BuiltinModifierPlugins.reference(entry.type, version) ?: return@mapIndexed entry
                if (ModifierPluginCatalog.available(entry.type, version, plugin)) entry.copy(version = version, plugin = plugin) else entry
            })
        }
        if (cliBindings != null) stack = cliBindings.bindings(model, stack)
        model = build("binding preview")
        val parameters = model.rig.puppet.parameters.associateBy { it.id.raw }
        val available = parameters.keys
        val claimed = model.rig.modifierOutput!!.physics.groups.flatMapTo(HashSet()) { it.outputs.map { output -> output.parameter } }
        if (config.generatePhysics) for (rule in PhysicsGenerator.presetRules(
            PhysicsGenerator.Presets(config.physicsFrontHair, config.physicsBackHair, config.physicsEyeJelly), available)) {
            if (PhysicsCatalog.issueOf(rule, available) != null || rule.outputs.any { it.parameter in claimed }) continue
            stack = stack.add("physics", 1, targets, rule.toJson(), group,
                outputAliases = if (cliBindings == null) emptyMap() else mapOf("physics" to rule.id))
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
                if (name == "Idle" && config.exportIncludePhysics) claimed else emptySet())
            val curves = tracks.filter { it.parameterId in available }.distinctBy { it.parameterId }
            if (curves.isEmpty()) continue
            // Speech uses an intentional jump where two syllables share a timestamp.
            val ranges = curves.associate { curve ->
                val parameter = parameters.getValue(curve.parameterId)
                curve.parameterId to MotionParameterRange(parameter.min, parameter.max, parameter.repeat)
            }
            val clip = MotionClip("template", name, loop = MotionPresets.loops(name),
                duration = MotionPresets.duration(name, tracks, settings).takeIf { it > 0f } ?: 2f,
                curves = curves, capturedRanges = ranges)
            val named = cliBindings?.motion(clip) ?: clip
            val roles = if (name == "Blink") mapOf("EyeBlink" to listOf("ParamEyeLOpen", "ParamEyeROpen").filter { it in available }) else emptyMap()
            stack = stack.add("motion", 1, targets, MotionClips.toJson(named.copy(parameterRoles = roles)), group,
                outputAliases = if (cliBindings == null) emptyMap() else mapOf("motion" to name))
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

    fun freshSource(config: PipelineConfig): Boolean =
        config.rigEdits.copy(modifierStack = null) == RigEditOverlay.Empty &&
            config.meshOverrides.isEmpty() && config.parentOverrides.isEmpty() &&
            !config.deferRig && config.rigPendingLayerIds.isEmpty()

    private fun sourceArmVersion(analysis: PipelineAnalysis, closedMouth: Boolean): Int? {
        val sides = analysis.layers.filter { it.opaquePixels > 0 && it.semantic.tag == SemanticTag.HANDWEAR }
            .map { it.semantic.side }
        val lateral = setOf(Side.LEFT, Side.RIGHT)
        return when {
            sides.size == 2 && sides.toSet() == lateral -> ArmSwingV3Algorithm.VERSION
            closedMouth && sides.singleOrNull() in lateral -> ArmSwingV4Algorithm.VERSION
            else -> null
        }
    }

    private fun closedMouthVersions(types: List<String>): Map<String, Int> = mapOf(
        RigModules.MOUTH_FITTED.id to MouthFittedV4Algorithm.VERSION,
        RigModules.MOUTH_OUTLINE.id to MouthOutlineV5Algorithm.VERSION,
        RigModules.MOUTH_VOWEL.id to MouthVowelV5Algorithm.VERSION,
        RigModules.MOUTH_CAPTURE.id to MouthCaptureV4Algorithm.VERSION,
        RigModules.MOUTH_TONGUE.id to MouthTongueV4Algorithm.VERSION,
        RigModules.MOUTH_JAW.id to if (RigModules.MOUTH_TONGUE.id in types) MouthJawV6Algorithm.VERSION else MouthJawV5Algorithm.VERSION,
        RigModules.MOUTH_CHEEK_LID.id to MouthCheekLidV6Algorithm.VERSION,
        RigModules.MOUTH_DEPTH.id to MouthDepthV4Algorithm.VERSION,
        RigModules.MOUTH_SPRING.id to if (RigModules.MOUTH_DEPTH.id in types) MouthSpringV7Algorithm.VERSION else MouthSpringV6Algorithm.VERSION,
    ).filterKeys { it in types }

    private fun singleEyeMouthVersions(types: List<String>): Map<String, Int> = mapOf(
        RigModules.MOUTH_FITTED.id to MouthFittedV5Algorithm.VERSION,
        RigModules.MOUTH_OUTLINE.id to MouthOutlineV6Algorithm.VERSION,
        RigModules.MOUTH_VOWEL.id to MouthVowelV6Algorithm.VERSION,
        RigModules.MOUTH_CAPTURE.id to MouthCaptureV5Algorithm.VERSION,
        RigModules.MOUTH_TONGUE.id to MouthTongueV5Algorithm.VERSION,
        RigModules.MOUTH_JAW.id to MouthJawV7Algorithm.VERSION,
        RigModules.MOUTH_CHEEK_LID.id to MouthCheekLidV7Algorithm.VERSION,
        RigModules.MOUTH_DEPTH.id to MouthDepthV5Algorithm.VERSION,
        RigModules.MOUTH_SPRING.id to MouthSpringV8Algorithm.VERSION,
    ).filterKeys { it in types }

    private fun sourceMouthVersions(analysis: PipelineAnalysis, config: PipelineConfig, types: List<String>,
                                    singleEye: Boolean = false, sharedBrow: Boolean = false): Map<String, Int> {
        if (config.mouthOutlineEnabled && !supportsClassicOutline(analysis, config)) return emptyMap()
        val measurement = config.copy(generateDeformers = false)
        val context = RigBuilder.rigContext(analysis, measurement)
        val mouth = context.fittedMouth(measurement) ?: return emptyMap()
        if (mouth.source == null || mouth.layered || mouth.rig?.fit?.layered != null ||
            !singleEye && context.faceTurn(measurement) == null)
            return emptyMap()
        if (mouth.closedLine) return when {
            sharedBrow -> emptyMap()
            singleEye -> singleEyeMouthVersions(types)
            else -> closedMouthVersions(types)
        }
        if (singleEye) return emptyMap()
        if (analysis.layers.any { it.semantic.tag == SemanticTag.MOUTH_CLOSE }) return emptyMap()
        if (sharedBrow) return buildMap {
            if (RigModules.MOUTH_FITTED.id !in types) return@buildMap
            put(RigModules.MOUTH_FITTED.id, MouthFittedV6Algorithm.VERSION)
            if (RigModules.MOUTH_OUTLINE.id !in types) return@buildMap
            put(RigModules.MOUTH_OUTLINE.id, MouthOutlineV8Algorithm.VERSION)
            if (RigModules.MOUTH_VOWEL.id !in types) return@buildMap
            put(RigModules.MOUTH_VOWEL.id, MouthVowelV7Algorithm.VERSION)
            if (RigModules.MOUTH_CAPTURE.id !in types) return@buildMap
            put(RigModules.MOUTH_CAPTURE.id, MouthCaptureV6Algorithm.VERSION)
            if (RigModules.MOUTH_JAW.id !in types || RigModules.MOUTH_TONGUE.id in types) return@buildMap
            put(RigModules.MOUTH_JAW.id, MouthJawV8Algorithm.VERSION)
            if (RigModules.MOUTH_CHEEK_LID.id !in types) return@buildMap
            put(RigModules.MOUTH_CHEEK_LID.id, MouthCheekLidV8Algorithm.VERSION)
            if (RigModules.MOUTH_SPRING.id in types && RigModules.MOUTH_DEPTH.id !in types)
                put(RigModules.MOUTH_SPRING.id, MouthSpringV9Algorithm.VERSION)
        }
        val withInterior = RigModules.MOUTH_TONGUE.id in types || RigModules.MOUTH_DEPTH.id in types
        return mapOf(
            RigModules.MOUTH_FITTED.id to MouthFittedV3Algorithm.VERSION,
            RigModules.MOUTH_OUTLINE.id to MouthOutlineV7Algorithm.VERSION,
            RigModules.MOUTH_VOWEL.id to MouthVowelV6Algorithm.VERSION,
            RigModules.MOUTH_CAPTURE.id to MouthCaptureV5Algorithm.VERSION,
            RigModules.MOUTH_TONGUE.id to MouthTongueV5Algorithm.VERSION,
            RigModules.MOUTH_JAW.id to if (withInterior) MouthJawV7Algorithm.VERSION else MouthJawV3Algorithm.VERSION,
            RigModules.MOUTH_CHEEK_LID.id to MouthCheekLidV5Algorithm.VERSION,
            RigModules.MOUTH_DEPTH.id to MouthDepthV3Algorithm.VERSION,
            RigModules.MOUTH_SPRING.id to if (withInterior) MouthSpringV8Algorithm.VERSION else MouthSpringV4Algorithm.VERSION,
        ).filterKeys { it in types }
    }

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
