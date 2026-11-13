package io.github.psd2live.application

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
        prepare(document, work, choices).first

    fun prepare(document: WorkspaceDocument, work: WorkspaceRasterWork,
                choices: WorkspaceInitialPreset = WorkspaceInitialPreset(),
                requestedVersions: Map<String, Int> = emptyMap(),
                cliBindings: CliPresetBindings? = null,
                restoreSourceRotations: Boolean = false,
                pluginOverrides: Map<String, Pair<String, ModifierPluginReference>> = emptyMap()): Pair<WorkspaceDocument, RigPreviewModel> {
        val rawConfig = cliBindings?.config?.takeIf { restoreSourceRotations }
        fun configOf(candidate: WorkspaceDocument) = rawConfig?.copy(rigEdits = candidate.rigEdits) ?: candidate.config()
        val config = configOf(document)
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
            pipeline.buildPreview(candidate.source, configOf(candidate), ProgressListener { _, _ -> work.checkpoint() })
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
            if (rawConfig == null) model.runtimeBundle.materialize()
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
        if (lockedVersions.isNotEmpty()) {
            stack = stack.copy(instances = stack.instances.map { entry ->
                lockedVersions[entry.type]?.let { (version, plugin) -> entry.copy(version = version, plugin = plugin) } ?: entry
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
            val captured = WorkspaceModifierEdits.captureParameterProviders(it.copy(presetGroup = group), model, stopAtConsumer = true)
            pluginOverrides[captured.type]?.let { (overrideType, overridePlugin) ->
                captured.copy(type = overrideType, version = 1, plugin = overridePlugin)
            } ?: captured
        })
        RigModifierAlgorithms.evaluator().validateOrder(stack)
        stack = RigBuildProfile.stage("source preset: simulation preparation") {
            WorkspaceModifierSimulation.prepare(current(), stack, work, pipeline = pipeline)
        }
        return current() to build("final preview").also {
            work.checkpoint()
            if (rawConfig == null) it.runtimeBundle.materialize()
            work.checkpoint()
        }
    }

    internal fun freshSource(config: PipelineConfig): Boolean =
        config.rigEdits.copy(modifierStack = null) == RigEditOverlay.Empty &&
            config.meshOverrides.isEmpty() && config.parentOverrides.isEmpty() &&
            !config.deferRig && config.rigPendingLayerIds.isEmpty()
}
