package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.SeeThroughAlgorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModule
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RecipeKnob
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/** Descriptions of the existing binding algorithms; all adjustable values belong to the captured instance. */
internal object RigLegacyBindings {
    data class Spec(val type: String, val parameters: Set<ParameterId>, val fields: List<RigTuning.Field>, val strength: Boolean = false,
                    val version: Int = 1, val knobs: List<RecipeKnob> = emptyList(), val module: RigModule? = null, val prerequisites: Set<String> = emptySet()) {
        val area get() = type.substringBefore('/').substringBefore('.')
        val mouthOutline get() = module == RigModules.MOUTH_OUTLINE
        val expressionToggles get() = module == RigModules.EXPRESSION_TOGGLES
        val expressionCheek get() = module == RigModules.EXPRESSION_CHEEK
        val expressionGloom get() = module == RigModules.EXPRESSION_GLOOM
        val eyeLidFade get() = module == RigModules.EYE_LID_FADE
        val eyeSquash get() = module == RigModules.EYE_SQUASH
        val browShape get() = module == RigModules.BROW_SHAPE
        val browSpring get() = module == RigModules.BROW_SPRING
        val skinClip get() = module == RigModules.FACE_SKIN_CLIP
        val seeThrough get() = module == RigModules.EYE_SEE_THROUGH || module == RigModules.BROW_SEE_THROUGH
        val chainKinds get() = RigModifierChains.kinds(type)
        val hasPhysics get() = module?.physicsGroups.orEmpty().isNotEmpty() || chainKinds.isNotEmpty()
        val isBase get() = module == null
        val related get() = module?.requires.orEmpty().toSet() + prerequisites
        val requires get() = setOf(RigSourceModifiers.SCAFFOLD) + listOfNotNull(module?.base) + related
    }

    private fun fields(area: String) = RigTuning.fields.filter { field ->
        if (area == "body") field.group.area == area || field.id in setOf("faceLeanDown", "faceLeanUp")
        else field.group.area == area && field.id !in setOf("faceLeanDown", "faceLeanUp")
    }
    private val legacy = listOf(
        Spec("body/legacy", setOf(StandardParameters.BODY_X, StandardParameters.BODY_Y, StandardParameters.BODY_Z,
            StandardParameters.BODY_LEAN, StandardParameters.PROPORTION, StandardParameters.BREATH), fields("body"), true),
        Spec("face/legacy", setOf(StandardParameters.ANGLE_X, StandardParameters.ANGLE_Y, StandardParameters.ANGLE_Z), fields("face"), true),
        Spec("eye/legacy", setOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN, StandardParameters.EYE_BALL_X,
            StandardParameters.EYE_BALL_Y, StandardParameters.EYE_BALL_FORM), fields("eye")),
        Spec("brow/legacy", setOf(StandardParameters.BROW_L_Y, StandardParameters.BROW_R_Y), fields("brow")),
        Spec("mouth/legacy", setOf(StandardParameters.MOUTH_FORM, StandardParameters.MOUTH_OPEN), fields("mouth")),
        Spec("hair/legacy", setOf(StandardParameters.HAIR_FRONT, StandardParameters.HAIR_BACK), fields("hair")),
    )
    const val FITTED_FACE = "face/fitted"
    val specs = legacy + Spec(FITTED_FACE, legacy.single { it.area == "face" }.parameters,
        fields("face").filter { RigTuning.replacedBy[it.id] != RigModules.FACE_DEPTH_TURN.id }, strength = true,
        knobs = RigModules.FACE_DEPTH_TURN.knobs) + listOf(RigModules.EYE_FITTED, RigModules.BROW_FITTED, RigModules.MOUTH_FITTED, RigModules.HAIR_CHAINS).map { base ->
        val original = legacy.single { it.area == base.area }
        Spec(base.id, original.parameters, original.fields.filter { RigTuning.replacedBy[it.id] != base.id },
            version = 1, knobs = base.knobs, prerequisites = if (base == RigModules.HAIR_CHAINS) setOf("body/legacy", "face/legacy") else emptySet())
    } + listOf(RigModules.EYE_SMILE, RigModules.EYE_SQUASH, RigModules.EYE_WIDE, RigModules.EYE_LID_FADE, RigModules.EYE_HIGHLIGHT, RigModules.EYE_AFTERSHOCK, RigModules.BROW_SHAPE, RigModules.BROW_SPRING, RigModules.EYE_EMOTION, RigModules.MOUTH_VOWEL, RigModules.EYE_GAZE_LID, RigModules.BROW_BLINK, RigModules.BROW_LID, RigModules.MOUTH_CHEEK_LID, RigModules.MOUTH_CAPTURE, RigModules.MOUTH_TONGUE, RigModules.MOUTH_JAW,
        RigModules.MOUTH_DEPTH, RigModules.MOUTH_SPRING, RigModules.MOUTH_OUTLINE, RigModules.EXPRESSION_CHEEK, RigModules.EXPRESSION_GLOOM, RigModules.EXPRESSION_TOGGLES, RigModules.FACE_SKIN_CLIP, RigModules.FACE_NECK, RigModules.FACE_DEPTH_TURN, RigModules.BODY_BUST, RigModules.BODY_ARMS, RigModules.OUTFIT_SKIRT, RigModules.OUTFIT_RIBBONS, RigModules.OUTFIT_CREATURE, RigModules.EYE_SEE_THROUGH, RigModules.BROW_SEE_THROUGH).map { module ->
        Spec(module.id, module.defines.mapTo(linkedSetOf()) { ParameterId(it.id) }, emptyList(),
            version = 1, knobs = if (module == RigModules.EYE_SEE_THROUGH || module == RigModules.BROW_SEE_THROUGH)
                listOf(RecipeKnob("opacity", RigTuning.Unit.PERCENT, 0f..100f, 50f))
            else if (module == RigModules.MOUTH_OUTLINE) listOf(RecipeKnob("thickness", RigTuning.Unit.PIXELS, .5f..8f, 1.5f))
            else module.knobs, module = module,
            prerequisites = when (module) {
                RigModules.BROW_LID, RigModules.MOUTH_CHEEK_LID -> setOf("eye/fitted")
                RigModules.EYE_HIGHLIGHT, RigModules.MOUTH_DEPTH, RigModules.FACE_NECK, RigModules.FACE_DEPTH_TURN -> setOf("face/legacy")
                RigModules.BODY_BUST, RigModules.BODY_ARMS, RigModules.OUTFIT_SKIRT, RigModules.OUTFIT_RIBBONS, RigModules.OUTFIT_CREATURE -> setOf("body/legacy", "face/legacy")
                else -> emptySet()
            })
    }

    fun find(type: String) = specs.singleOrNull { it.type == type }
    fun spec(type: String) = requireNotNull(find(type)) { "Unknown source binding: $type" }

    fun strength(instance: RigModifierInstance): Float = (instance.config["strength"]?.jsonPrimitive?.float ?: 1f).also {
        require(it.isFinite() && it in 0f..4f) { "Binding strength must be within 0..4" }
    }

    fun tuning(instance: RigModifierInstance, base: RigTuning): RigTuning {
        var result = base
        for ((id, encoded) in instance.config["tuning"]?.jsonObject.orEmpty()) {
            val field = requireNotNull(spec(instance.type).fields.singleOrNull { it.id == id }) { "Unknown ${instance.type} value: $id" }
            val value = encoded.jsonPrimitive.float
            require(value.isFinite() && value in field.range) { "Binding value $id must be within ${field.range}" }
            result = field.set(result, value)
        }
        return result
    }

    fun values(instance: RigModifierInstance): Map<String, Float> = instance.config["values"]?.jsonObject.orEmpty().mapValues { (id, encoded) ->
        val knob = requireNotNull(spec(instance.type).knobs.singleOrNull { it.id == id }) { "Unknown ${instance.type} value: $id" }
        encoded.jsonPrimitive.float.also { require(it.isFinite() && it in knob.range) { "Binding value $id must be within ${knob.range}" } }
    }

    fun outlineColor(instance: RigModifierInstance): Int? = instance.config["color"]?.jsonPrimitive?.int?.also {
        require(it in 0..0xffffff) { "Lip color must be a 24-bit RGB value" }
    }

    fun providers(instance: RigModifierInstance): Map<String, String> = buildMap {
        put(RigSourceModifiers.SCAFFOLD, instance.config.getValue("scaffold").jsonPrimitive.content)
        val spec = spec(instance.type)
        spec.module?.base?.let { put(it, instance.config.getValue("base").jsonPrimitive.content) }
        val dependencies = instance.config["dependencies"]?.jsonObject.orEmpty()
        require(dependencies.keys == spec.related) { "${instance.id}: specify exactly the related providers ${spec.related}" }
        spec.related.forEach { put(it, dependencies.getValue(it).jsonPrimitive.content) }
    }

    private val seeThrough = object : ContextualModifierAlgorithmPlugin {
        private val algorithm = SeeThroughAlgorithm()

        override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                              checkpoint: () -> Unit): RigIR {
            val evaluated = algorithm.evaluate(input, instance, context, checkpoint)
            if (evaluated === input || instance.outputAliases.isEmpty()) return evaluated
            val earlier = Json.decodeFromJsonElement(ListSerializer(RigModifierInstance.serializer()),
                context.values.getValue("earlier_owners"))
            val native = buildMap {
                for (owner in earlier + instance) for ((local, id) in owner.outputAliases) {
                    if (!local.startsWith("see-through:")) continue
                    val original = local.removePrefix("see-through:")
                    if (id == original + SeeThroughLayers.SUFFIX) put(id, original)
                }
            }
            val order = evaluated.meshes.withIndex().associateBy { it.value.id }
            val copies = evaluated.meshes.filter { it.textureSource != null && native[it.id]?.let(order::containsKey) == true }
            if (copies.isEmpty()) return evaluated
            fun original(copy: Mesh) = order.getValue(native.getValue(copy.id))
            // The combined legacy pass appended feature copies in source order, then the windows in the bangs.
            val (windows, features) = copies.partition { copy ->
                val owner = original(copy).value
                owner.invertMask && owner.maskedBy == copy.maskedBy
            }
            val sorted = (features.sortedBy { original(it).index } + windows).iterator()
            val copiedIds = copies.mapTo(HashSet()) { it.id }
            return evaluated.copy(meshes = evaluated.meshes.map { mesh ->
                if (mesh.id in copiedIds) sorted.next().copy(selectable = false) else mesh
            })
        }
    }

    fun definition(spec: Spec, source: RigModifierSource?): RigModifierDefinition =
        RigModifierDefinition(spec.type, spec.version,
        requires = spec.requires, provides = when (spec.type) {
            FITTED_FACE -> setOf("face/legacy")
            "eye/fitted" -> setOf("eye/legacy")
            "brow/fitted" -> setOf("brow/legacy")
            "mouth/fitted" -> setOf("mouth/legacy")
            "hair/chains" -> setOf("hair/legacy")
            else -> emptySet()
        }, exclusiveFamily = if (spec.isBase) "base:${spec.area}" else if (spec.chainKinds.isNotEmpty()) "chain:${spec.type}" else if (spec.expressionToggles || spec.mouthOutline) spec.type else null,
        providers = { instance -> providers(instance) },
        appliedConfig = { it.config }, resolveParameter = { instance, before, declared -> requireNotNull(source).resolveParameter(instance, before, declared) },
        references = if (spec.seeThrough) SeeThroughContributions::references else RigModifierPhysics::references,
        parameters = { instance -> when {
            spec.type == "hair/legacy" -> requireNotNull(source).hairLegacyParameters(instance)
            spec.module == RigModules.MOUTH_VOWEL -> io.github.psd2live.algorithms.binding.MouthVowelAlgorithm.parameters(
                requireNotNull(source).mouthModuleContext(instance, RigModules.MOUTH_VOWEL))
            spec.module == RigModules.MOUTH_CAPTURE -> io.github.psd2live.algorithms.binding.MouthCaptureAlgorithm.parameters(
                requireNotNull(source).mouthModuleContext(instance, RigModules.MOUTH_CAPTURE))
            spec.module == RigModules.MOUTH_TONGUE -> listOf(io.github.psd2live.algorithms.binding.MouthTongueAlgorithm.parameter(
                requireNotNull(source).mouthModuleContext(instance, RigModules.MOUTH_TONGUE)))
            spec.module == RigModules.MOUTH_SPRING -> io.github.psd2live.algorithms.binding.MouthSpringAlgorithm.parameters(
                requireNotNull(source).mouthModuleContext(instance, RigModules.MOUTH_SPRING))
            spec.expressionToggles -> requireNotNull(source).expressionParameters(instance)
            spec.browShape -> io.github.psd2live.algorithms.binding.BrowShapeAlgorithm.parameters(
                io.github.psd2live.algorithms.binding.BrowShapeInputs.decode(requireNotNull(source).browShapeContext(instance)) {})
            spec.browSpring -> io.github.psd2live.algorithms.binding.BrowSpringAlgorithm.parameters(
                io.github.psd2live.algorithms.binding.BrowSpringInputs.decode(requireNotNull(source).browSpringContext(instance)) {})
            spec.type == "eye.wide" -> {
                val captured = io.github.psd2live.algorithms.binding.EyeWideInputs.decode(requireNotNull(source).eyeWideContext(instance)) {}
                val align = { x: Float, y: Float -> captured.measurements.alignment?.point(x, y) ?: (x to y) }
                val hasFits = io.github.psd2live.algorithms.binding.EyeFitting.fit(captured.art, captured.measurements.faceCenterX, align).fits.isNotEmpty() &&
                    captured.art.layers.any { it.tag == "EYELASH" && it.opaquePixels > 0 }
                if (!hasFits) emptyList()
                else listOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN).mapNotNull { id ->
                    StandardParameters.all.firstOrNull { it.id == id }?.let {
                        io.github.psd2live.format.model.Parameter(it.id.raw, it.name, it.min, 2f, it.default)
                    }
                }
            }
            spec.type in setOf("eye/fitted", "eye/legacy", "brow/fitted", "brow/legacy", "face/legacy", FITTED_FACE, "body/legacy", "mouth/fitted", "mouth/legacy") ->
                StandardParameters.all.filter { it.id in spec.parameters }.map {
                    io.github.psd2live.format.model.Parameter(it.id.raw, it.name, it.min, it.max, it.default)
                }
            spec.expressionCheek || spec.expressionGloom || spec.eyeSquash || spec.type.startsWith("mouth.") ->
                spec.module?.defines.orEmpty().map {
                    io.github.psd2live.format.model.Parameter(it.id, io.github.psd2live.i18n.tr(it.nameKey), it.min, it.max, it.default)
                }
            else -> emptyList()
        } },
        validate = { _, instance ->
            val allowed = setOf("scaffold", "tuning") + (if (spec.hasPhysics) setOf("physics", "physicsEnabled") else emptySet()) + (if (spec.related.isNotEmpty()) setOf("dependencies") else emptySet()) + (if (spec.module?.base != null) setOf("base") else emptySet()) + (if (spec.strength) setOf("strength") else emptySet()) +
                (if (spec.knobs.isNotEmpty()) setOf("values") else emptySet()) + (if (spec.mouthOutline) setOf("color") else emptySet())
            require(instance.config.keys.all { it in allowed } && "scaffold" in instance.config) { "Specify ${spec.type}'s scaffold and editable values" }
            instance.config["physicsEnabled"]?.jsonPrimitive?.boolean
            providers(instance); strength(instance); tuning(instance, RigTuning()); values(instance); outlineColor(instance)
            require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) { "Binding targets must have captured source layers" }
            requireNotNull(source).provider(instance)
        }, composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter") +
            if (spec.expressionToggles || spec.hasPhysics) setOf("physics") else emptySet(),
        upstreamInputs = spec.type in setOf(
            "outfit.skirt", "outfit.ribbons", "outfit.creature",
            "eye/fitted", "eye/legacy", "eye.wide", "eye.smile", "eye.emotion",
            "eye.gaze-lid", "eye.highlight", "eye.aftershock", "eye.squash",
            "brow/fitted", "brow/legacy", "brow.shape", "brow.spring", "brow.lid-coupling", "brow.blink",
            "body/legacy", "body.bust", "body.arms",
            "face/legacy", FITTED_FACE, "face.depth-turn", "face.neck",
            "hair/legacy", "hair/chains",
            "mouth/fitted", "mouth/legacy", "mouth.outline", "mouth.vowel", "mouth.capture",
            "mouth.tongue", "mouth.jaw", "mouth.cheek-lid", "mouth.interior-depth", "mouth.spring",
        ),
        changeWrites = when {
            spec.type == "face/legacy" -> setOf("mesh", "deformer", "parameter", "hierarchy", "relations")
            spec.type == "hair/legacy" -> setOf("mesh", "deformer", "parameter", "hierarchy")
            spec.type == "hair/chains" -> io.github.psd2live.algorithms.binding.HairChainsAlgorithm.WRITES
            spec.skinClip -> setOf("mesh", "relations")
            spec.mouthOutline -> setOf("mesh", "part", "deformer", "parameter", "hierarchy", "relations", "textures", "authoring")
            spec.expressionCheek || spec.expressionGloom -> setOf("mesh", "parameter", "hierarchy")
            spec.eyeLidFade -> setOf("mesh")
            spec.browShape -> setOf("mesh", "parameter", "hierarchy")
            spec.browSpring -> setOf("mesh", "parameter", "hierarchy", "physics")
            spec.type == "outfit.skirt" -> io.github.psd2live.algorithms.binding.SkirtAlgorithm.WRITES
            spec.type == "outfit.ribbons" || spec.type == "outfit.creature" ->
                setOf("mesh", "part", "deformer", "parameter", "hierarchy", "physics")
            else -> null
        },
        plugin = when {
            spec.expressionToggles -> io.github.psd2live.algorithms.binding.ExpressionAlgorithm()
            spec.expressionCheek -> io.github.psd2live.algorithms.binding.CheekAlgorithm()
            spec.expressionGloom -> io.github.psd2live.algorithms.binding.GloomAlgorithm()
            spec.eyeLidFade -> object : ReadingModifierAlgorithmPlugin {
                private val lidFade = io.github.psd2live.algorithms.binding.LidFadeAlgorithm()
                override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit) =
                    lidFade.changes(input, instance.copy(version = 2), checkpoint)
            }
            spec.seeThrough -> seeThrough
            spec.skinClip -> io.github.psd2live.algorithms.binding.SkinClipAlgorithm()
            spec.type == "eye/fitted" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeFittedSourceAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeFittedAlgorithm.VERSION)
            spec.type == "eye/legacy" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeLegacySourceAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeLegacySourceAlgorithm.VERSION)
            spec.type == "eye.wide" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeWideAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeWideAlgorithm.VERSION)
            spec.type == "eye.smile" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeSmileAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeSmileAlgorithm.VERSION)
            spec.type == "eye.emotion" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeEmotionAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeEmotionAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = setOf(
                    "eye.wide", "psd2live.native.core/eye-wide",
                    "eye.smile", "psd2live.native.core/eye-smile",
                    "eye.aftershock", "psd2live.native.core/eye-aftershock",
                    "eye.gaze-lid", "psd2live.native.core/eye-gaze-lid",
                    "brow.lid-coupling", "psd2live.native.core/brow-lid-coupling")
            }
            spec.type == "eye.gaze-lid" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeGazeLidAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeGazeLidAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = setOf(
                    "eye.wide", "psd2live.native.core/eye-wide",
                    "eye.smile", "psd2live.native.core/eye-smile",
                    "eye.emotion", "psd2live.native.core/eye-emotion",
                    "eye.aftershock", "psd2live.native.core/eye-aftershock",
                    "brow.lid-coupling", "psd2live.native.core/brow-lid-coupling")
            }
            spec.type == "eye.highlight" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeHighlightAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeHighlightAlgorithm.VERSION)
            spec.type == "eye.aftershock" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeAftershockAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeAftershockAlgorithm.VERSION)
            spec.type == "brow/fitted" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.BrowFittedAlgorithm(), 1), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("eye/fitted", "eye")
            }
            spec.type == "brow/legacy" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.BrowLegacyAlgorithm(), 1)
            spec.type == "brow.shape" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.BrowShapeAlgorithm(),
                io.github.psd2live.algorithms.binding.BrowShapeAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("eye/fitted")
            }
            spec.type == "brow.spring" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.BrowSpringAlgorithm(),
                io.github.psd2live.algorithms.binding.BrowSpringAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("eye/fitted", "brow.shape", "psd2live.native.core/brow-shape")
            }
            spec.type == "brow.lid-coupling" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.BrowLidAlgorithm(),
                io.github.psd2live.algorithms.binding.BrowLidAlgorithm.VERSION)
            spec.type == "brow.blink" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.BrowBlinkAlgorithm(),
                io.github.psd2live.algorithms.binding.BrowBlinkAlgorithm.VERSION)
            spec.type == "body/legacy" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.LegacyBodyAlgorithm(), 1)
            spec.type == "body.bust" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.BustBindingAlgorithm(),
                io.github.psd2live.algorithms.binding.BustBindingAlgorithm.VERSION)
            spec.type == "body.arms" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.ArmSwingAlgorithm(),
                io.github.psd2live.algorithms.binding.ArmSwingAlgorithm.VERSION)
            spec.type == "face/legacy" -> io.github.psd2live.algorithms.binding.LegacyFaceAlgorithm()
            spec.type == FITTED_FACE -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.FittedFaceBindingAlgorithm)
            spec.type == "face.depth-turn" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.DepthTurnAlgorithm(), 1)
            spec.type == "face.neck" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.NeckAlgorithm(),
                io.github.psd2live.algorithms.binding.NeckAlgorithm.VERSION)
            spec.type == "outfit.skirt" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.SkirtAlgorithm(), io.github.psd2live.algorithms.binding.SkirtAlgorithm.VERSION)
            spec.type == "outfit.ribbons" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.RibbonsAlgorithm(), io.github.psd2live.algorithms.binding.RibbonsAlgorithm.VERSION)
            spec.type == "outfit.creature" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.CreatureAlgorithm(), io.github.psd2live.algorithms.binding.CreatureAlgorithm.VERSION)
            spec.type == "hair/legacy" -> io.github.psd2live.algorithms.binding.HairLegacyAlgorithm()
            spec.type == "hair/chains" -> io.github.psd2live.algorithms.binding.HairChainsAlgorithm()
            spec.type == "mouth/fitted" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthFittedSourceAlgorithm(), 1), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("face/fitted", "face.depth-turn", "face/legacy", "body/legacy")
            }
            spec.type == "mouth/legacy" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthLegacyAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthLegacyAlgorithm.VERSION)
            spec.type == "mouth.outline" -> object : ReadingModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.MouthOutlineAlgorithm(), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("mouth/fitted", "face/fitted", "face.depth-turn")
            }
            spec.type == "mouth.vowel" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthVowelAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthVowelAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = io.github.psd2live.algorithms.binding.MouthVowelAlgorithm.optionalUpstream
            }
            spec.type == "mouth.capture" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthCaptureAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthCaptureAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = io.github.psd2live.algorithms.binding.MouthCaptureAlgorithm.optionalUpstream
            }
            spec.type == "mouth.tongue" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthTongueAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthTongueAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = io.github.psd2live.algorithms.binding.MouthTongueAlgorithm.optionalUpstream
            }
            spec.type == "mouth.jaw" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthJawAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthJawAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = io.github.psd2live.algorithms.binding.MouthJawAlgorithm.optionalUpstream
            }
            spec.type == "mouth.cheek-lid" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthCheekLidAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthCheekLidAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = io.github.psd2live.algorithms.binding.MouthCheekLidAlgorithm.optionalUpstream
            }
            spec.type == "mouth.interior-depth" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthDepthAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthDepthAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = io.github.psd2live.algorithms.binding.MouthDepthAlgorithm.optionalUpstream
            }
            spec.type == "mouth.spring" -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.MouthSpringAlgorithm(),
                io.github.psd2live.algorithms.binding.MouthSpringAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = io.github.psd2live.algorithms.binding.MouthSpringAlgorithm.optionalUpstream
            }
            spec.eyeSquash -> object : ContextualModifierAlgorithmPlugin by io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.EyeSquashAlgorithm(),
                io.github.psd2live.algorithms.binding.EyeSquashAlgorithm.VERSION), ModifierOptionalUpstream {
                override val optionalUpstream = setOf("eye.wide", "psd2live.native.core/eye-wide")
            }
            else -> error("Unmigrated binding type without worker plugin: ${spec.type}")
        },
        authorContext = { when {
            spec.expressionToggles -> requireNotNull(source).expressionContext(it)
            spec.expressionCheek -> requireNotNull(source).cheekContext(it)
            spec.expressionGloom -> requireNotNull(source).gloomContext(it)
            spec.type == "eye/fitted" -> requireNotNull(source).eyeFittedContext(it)
            spec.type == "eye/legacy" -> requireNotNull(source).eyeLegacyContext(it)
            spec.type == "eye.wide" -> requireNotNull(source).eyeWideContext(it)
            spec.type == "eye.smile" -> requireNotNull(source).eyeSmileContext(it)
            spec.type == "eye.emotion" -> requireNotNull(source).eyeEmotionContext(it)
            spec.type == "eye.gaze-lid" -> requireNotNull(source).eyeGazeLidContext(it)
            spec.type == "eye.highlight" -> requireNotNull(source).eyeHighlightContext(it)
            spec.type == "eye.aftershock" -> requireNotNull(source).eyeAftershockContext(it)
            spec.type == "brow/fitted" -> requireNotNull(source).browFittedContext(it)
            spec.type == "brow/legacy" -> requireNotNull(source).browLegacyContext(it)
            spec.browShape -> requireNotNull(source).browShapeContext(it)
            spec.browSpring -> requireNotNull(source).browSpringContext(it)
            spec.type == "brow.lid-coupling" -> requireNotNull(source).browLidContext(it)
            spec.type == "brow.blink" -> requireNotNull(source).browBlinkContext(it)
            spec.type == "body/legacy" -> requireNotNull(source).bodyContext(it)
            spec.type == "body.bust" -> requireNotNull(source).bustContext(it)
            spec.type == "body.arms" -> requireNotNull(source).armSwingContext(it)
            spec.type == "face/legacy" -> requireNotNull(source).faceLegacyContext(it)
            spec.type == FITTED_FACE -> requireNotNull(source).faceFittedContext(it)
            spec.type == "face.depth-turn" -> requireNotNull(source).depthTurnContext(it)
            spec.type == "face.neck" -> requireNotNull(source).neckContext(it)
            spec.eyeLidFade -> requireNotNull(source).lidFadeContext(it)
            spec.eyeSquash -> requireNotNull(source).eyeSquashContext(it)
            spec.seeThrough -> requireNotNull(source).seeThroughContext(it)
            spec.skinClip -> requireNotNull(source).skinClipContext(it)
            spec.type == "outfit.skirt" -> requireNotNull(source).skirtContext(it)
            spec.type == "outfit.ribbons" -> requireNotNull(source).ribbonsContext(it)
            spec.type == "outfit.creature" -> requireNotNull(source).creatureContext(it)
            spec.type == "hair/legacy" -> requireNotNull(source).hairLegacyContext(it)
            spec.type == "hair/chains" -> requireNotNull(source).hairChainsContext(it)
            spec.type == "mouth/fitted" -> requireNotNull(source).mouthFittedContext(it)
            spec.type == "mouth/legacy" -> requireNotNull(source).mouthLegacyContext(it)
            spec.type == "mouth.outline" -> requireNotNull(source).mouthOutlineContext(it)
            spec.type == "mouth.vowel" -> requireNotNull(source).mouthModuleContext(it, RigModules.MOUTH_VOWEL)
            spec.type == "mouth.capture" -> requireNotNull(source).mouthModuleContext(it, RigModules.MOUTH_CAPTURE)
            spec.type == "mouth.tongue" -> requireNotNull(source).mouthModuleContext(it, RigModules.MOUTH_TONGUE)
            spec.type == "mouth.jaw" -> requireNotNull(source).mouthJawContext(it)
            spec.type == "mouth.cheek-lid" -> requireNotNull(source).mouthCheekLidContext(it)
            spec.type == "mouth.interior-depth" -> requireNotNull(source).mouthModuleContext(it, RigModules.MOUTH_DEPTH)
            spec.type == "mouth.spring" -> requireNotNull(source).mouthModuleContext(it, RigModules.MOUTH_SPRING)
            else -> ModifierAlgorithmContext.Empty
        } },
        workerAuthorContext = { requireNotNull(source).workerBindingContext(it) },
        )
}
