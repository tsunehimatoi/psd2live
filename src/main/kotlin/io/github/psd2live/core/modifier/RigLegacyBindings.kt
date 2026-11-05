package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.RigModule
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RecipeKnob
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId

/** Descriptions of the existing binding algorithms; all adjustable values belong to the captured instance. */
internal object RigLegacyBindings {
    data class Spec(val type: String, val parameters: Set<ParameterId>, val fields: List<RigTuning.Field>, val strength: Boolean = false,
                    val version: Int = 1, val knobs: List<RecipeKnob> = emptyList(), val module: RigModule? = null, val prerequisites: Set<String> = emptySet()) {
        val area get() = type.substringBefore('/').substringBefore('.')
        val expressionToggles get() = module == RigModules.EXPRESSION_TOGGLES
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
    val specs = legacy + listOf(RigModules.EYE_FITTED, RigModules.BROW_FITTED, RigModules.MOUTH_FITTED, RigModules.HAIR_CHAINS).map { base ->
        val original = legacy.single { it.area == base.area }
        Spec(base.id, if (base == RigModules.HAIR_CHAINS) emptySet() else original.parameters, original.fields.filter { RigTuning.replacedBy[it.id] != base.id },
            version = base.version, knobs = base.knobs, prerequisites = if (base == RigModules.HAIR_CHAINS) setOf("body/legacy", "face/legacy") else emptySet())
    } + listOf(RigModules.EYE_SMILE, RigModules.EYE_SQUASH, RigModules.EYE_WIDE, RigModules.EYE_LID_FADE, RigModules.EYE_HIGHLIGHT, RigModules.EYE_AFTERSHOCK, RigModules.BROW_SHAPE, RigModules.BROW_SPRING, RigModules.EYE_EMOTION, RigModules.MOUTH_VOWEL, RigModules.EYE_GAZE_LID, RigModules.BROW_BLINK, RigModules.BROW_LID, RigModules.MOUTH_CHEEK_LID, RigModules.MOUTH_CAPTURE, RigModules.MOUTH_TONGUE, RigModules.MOUTH_JAW,
        RigModules.MOUTH_DEPTH, RigModules.MOUTH_SPRING, RigModules.EXPRESSION_CHEEK, RigModules.EXPRESSION_GLOOM, RigModules.EXPRESSION_TOGGLES, RigModules.FACE_SKIN_CLIP, RigModules.FACE_NECK, RigModules.FACE_DEPTH_TURN, RigModules.BODY_BUST, RigModules.BODY_ARMS, RigModules.OUTFIT_SKIRT, RigModules.OUTFIT_RIBBONS, RigModules.OUTFIT_CREATURE, RigModules.EYE_SEE_THROUGH, RigModules.BROW_SEE_THROUGH).map { module ->
        Spec(module.id, module.defines.mapTo(linkedSetOf()) { ParameterId(it.id) }, emptyList(),
            version = module.version, knobs = if (module == RigModules.EYE_SEE_THROUGH || module == RigModules.BROW_SEE_THROUGH)
                listOf(RecipeKnob("opacity", RigTuning.Unit.PERCENT, 0f..100f, 50f)) else module.knobs, module = module,
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
        require(it.isFinite() && it in 0f..2f) { "Binding strength must be within 0..2" }
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

    fun providers(instance: RigModifierInstance): Map<String, String> = buildMap {
        put(RigSourceModifiers.SCAFFOLD, instance.config.getValue("scaffold").jsonPrimitive.content)
        val spec = spec(instance.type)
        spec.module?.base?.let { put(it, instance.config.getValue("base").jsonPrimitive.content) }
        val dependencies = instance.config["dependencies"]?.jsonObject.orEmpty()
        require(dependencies.keys == spec.related) { "${instance.id}: specify exactly the related providers ${spec.related}" }
        spec.related.forEach { put(it, dependencies.getValue(it).jsonPrimitive.content) }
    }

    fun definition(spec: Spec, source: RigModifierSource?) = RigModifierDefinition(spec.type, spec.version,
        requires = spec.requires, exclusiveFamily = if (spec.isBase) "base:${spec.area}" else if (spec.chainKinds.isNotEmpty()) "chain:${spec.type}" else if (spec.expressionToggles) spec.type else null,
        providers = { instance -> providers(instance) },
        appliedConfig = { it.config }, resolveParameter = { instance, before, declared -> requireNotNull(source).resolveParameter(instance, before, declared) },
        references = RigModifierPhysics::references, parameters = { instance -> when {
            spec.seeThrough -> emptyList()
            spec.expressionToggles -> requireNotNull(source).expressionParameters(instance)
            else -> requireNotNull(source).parameters(instance)
        } },
        validate = { _, instance ->
            val allowed = setOf("scaffold", "tuning") + (if (spec.hasPhysics) setOf("physics") else emptySet()) + (if (spec.related.isNotEmpty()) setOf("dependencies") else emptySet()) + (if (spec.module?.base != null) setOf("base") else emptySet()) + (if (spec.strength) setOf("strength") else emptySet()) +
                (if (spec.knobs.isNotEmpty()) setOf("values") else emptySet())
            require(instance.config.keys.all { it in allowed } && "scaffold" in instance.config) { "Specify ${spec.type}'s scaffold and editable values" }
            providers(instance); strength(instance); tuning(instance, RigTuning()); values(instance)
            require(instance.targets.all { it.kind == "mesh" && source?.hasMesh(it.id) == true }) { "Binding targets must have captured source layers" }
            requireNotNull(source).provider(instance)
        }, composableKinds = setOf("mesh", "part", "deformer", "hierarchy", "relations", "parameter") +
            if (spec.expressionToggles || spec.chainKinds.isNotEmpty()) setOf("physics") else emptySet(),
        evaluate = { rig, instance, checkpoint ->
            if (spec.seeThrough) requireNotNull(source).seeThrough(rig, instance, checkpoint)
            else if (spec.expressionToggles) requireNotNull(source).expression(rig, instance, checkpoint)
            else RigSourceBinding.apply(rig, instance, requireNotNull(source), checkpoint)
        })
}
