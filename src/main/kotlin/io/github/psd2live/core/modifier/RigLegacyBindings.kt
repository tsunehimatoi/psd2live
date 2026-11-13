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
        requires = spec.requires, provides = if (spec.type == FITTED_FACE) setOf("face/legacy") else emptySet(), exclusiveFamily = if (spec.isBase) "base:${spec.area}" else if (spec.chainKinds.isNotEmpty()) "chain:${spec.type}" else if (spec.expressionToggles || spec.mouthOutline) spec.type else null,
        providers = { instance -> providers(instance) },
        appliedConfig = { it.config }, resolveParameter = { instance, before, declared -> requireNotNull(source).resolveParameter(instance, before, declared) },
        references = if (spec.seeThrough) SeeThroughContributions::references else RigModifierPhysics::references,
        parameters = { instance -> when {
            spec.seeThrough || spec.skinClip -> emptyList()
            spec.expressionToggles -> requireNotNull(source).expressionParameters(instance)
            else -> requireNotNull(source).parameters(instance)
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
        changeWrites = when {
            spec.skinClip -> setOf("mesh", "relations")
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
            spec.type == "outfit.skirt" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.SkirtAlgorithm(), io.github.psd2live.algorithms.binding.SkirtAlgorithm.VERSION)
            spec.type == "outfit.ribbons" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.RibbonsAlgorithm(), io.github.psd2live.algorithms.binding.RibbonsAlgorithm.VERSION)
            spec.type == "outfit.creature" -> io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm(
                io.github.psd2live.algorithms.binding.CreatureAlgorithm(), io.github.psd2live.algorithms.binding.CreatureAlgorithm.VERSION)
            else -> object : ContextualModifierAlgorithmPlugin, ModifierOptionalUpstream {
                private val transition = io.github.psd2live.algorithms.binding.BindingTransitionAlgorithm()
                override val optionalUpstream = when {
                    spec.browShape -> setOf("eye/fitted")
                    spec.browSpring -> setOf("eye/fitted", "brow.shape", "psd2live.native.core/brow-shape")
                    else -> emptySet()
                }

                override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                                      checkpoint: () -> Unit): RigIR {
                    if (context != ModifierAlgorithmContext.Empty) {
                        return transition.evaluate(input, instance, context, checkpoint)
                    }
                    val bindings = RigSourceBinding.apply(input, instance, requireNotNull(source), checkpoint)
                    return if (spec.mouthOutline) source.outline(bindings, instance, checkpoint) else bindings
                }
            }
        },
        authorContext = { when {
            spec.expressionToggles -> requireNotNull(source).expressionContext(it)
            spec.expressionCheek -> requireNotNull(source).cheekContext(it)
            spec.expressionGloom -> requireNotNull(source).gloomContext(it)
            spec.eyeLidFade -> requireNotNull(source).lidFadeContext(it)
            spec.seeThrough -> requireNotNull(source).seeThroughContext(it)
            spec.skinClip -> requireNotNull(source).skinClipContext(it)
            spec.type == "outfit.skirt" -> requireNotNull(source).skirtContext(it)
            spec.type == "outfit.ribbons" -> requireNotNull(source).ribbonsContext(it)
            spec.type == "outfit.creature" -> requireNotNull(source).creatureContext(it)
            else -> ModifierAlgorithmContext.Empty
        } },
        workerAuthorContext = { requireNotNull(source).workerBindingContext(it) },
        )
}
