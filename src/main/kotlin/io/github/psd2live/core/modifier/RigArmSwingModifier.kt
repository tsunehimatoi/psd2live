package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ArmSwingAlgorithm
import io.github.psd2live.algorithms.binding.ArmSwingV3Algorithm
import io.github.psd2live.algorithms.binding.ArmSwingV4Algorithm
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigArmSwingModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by ArmSwingAlgorithm(), ModifierVersionSelection {
        override val canonicalOptInType = ArmSwingAlgorithm.TYPE
    }

    private val modernAlgorithm = object : ReadingModifierAlgorithmPlugin by ArmSwingV3Algorithm(), ModifierVersionSelection {
        override val canonicalOptInType = ArmSwingAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?, modern: Boolean = false): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(ArmSwingAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun captured(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance, modern) }
        return legacy.copy(
            version = if (modern) ArmSwingV3Algorithm.VERSION else ArmSwingAlgorithm.VERSION,
            parameters = { ArmSwingAlgorithm.parameters(captured(it)) },
            resolveParameter = { _, _, _ -> null },
            plugin = if (modern) modernAlgorithm else algorithm,
            changeWrites = if (modern) ArmSwingV3Algorithm.WRITES else ArmSwingAlgorithm.WRITES,
            composableKinds = legacy.composableKinds + "physics",
            upstreamInputs = true,
            authorContext = { captured(it) },
        )
    }

    fun singleDefinition(source: RigModifierSource?): RigModifierDefinition {
        val implementation = object : ReadingModifierAlgorithmPlugin by ArmSwingV4Algorithm(), ModifierVersionSelection {
            override val canonicalOptInType = ArmSwingAlgorithm.TYPE
        }
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun captured(instance: RigModifierInstance) = contexts.getOrPut(instance) {
            requireNotNull(source).captureSingle(instance)
        }
        return definition(source, modern = true).copy(version = ArmSwingV4Algorithm.VERSION,
            parameters = { ArmSwingAlgorithm.parameters(captured(it)) }, plugin = implementation, authorContext = ::captured)
    }

    private fun RigModifierSource.captureSingle(instance: RigModifierInstance): ModifierAlgorithmContext {
        val ordinary = capture(instance, modern = true)
        val arms = analysis.layers.filter { it.semantic.tag == SemanticTag.HANDWEAR && it.opaquePixels > 0 }
        val source = arms.singleOrNull() ?: return ordinary
        val targets = instance.targets.filter { layersByMesh[it.id] == source.source.id.raw }
        val target = targets.singleOrNull() ?: return ordinary
        val scaffold = provider(instance)
        val prior = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && target in it.targets &&
                RigLegacyBindings.find(it.type)?.let { spec -> !spec.expressionToggles && !spec.seeThrough } == true
        }
        val (previous, before) = RigFittedMouthModuleSource.context(this, scaffold, prior, null)
        val (desired, after) = RigFittedMouthModuleSource.context(this, scaffold, prior + instance, null)
        val oldLayer = before.analysis.layers.single { it.source.id == source.source.id }
        val newLayer = after.analysis.layers.single { it.source.id == source.source.id }
        val parent = before.parentAndFrame(oldLayer, previous).first
        if (parent?.raw?.startsWith("DeformArmHang_") != true) return ordinary
        require(after.parentAndFrame(newLayer, desired).first == parent) { "Arm swing requires the same source arm carrier" }
        val axes = prior.flatMapTo(hashSetOf()) { RigLegacyBindings.spec(it.type).parameters }
        return ModifierAlgorithmContext(JsonObject(ordinary.values + ("singleArm" to JsonPrimitive(target.id))), mapOf(
            "armBefore" to outlineShellSource(before, previous, mapOf(target.id to oldLayer), axes),
            "armAfter" to outlineShellSource(after, desired, mapOf(target.id to newLayer), axes + RigLegacyBindings.spec(instance.type).parameters),
        ))
    }
    private fun RigModifierSource.capture(instance: RigModifierInstance, modern: Boolean): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val packageVersion = scaffold.plugin?.let { ModifierPluginVersion.parse(it.version, legacy = true) }
        require(scaffold.version == 2 && (packageVersion == null || packageVersion >= ModifierPluginVersion.parse("1.0.41"))) {
            "Arm swing v2 requires the shared source scaffold v2"
        }
        val bodyId = RigLegacyBindings.providers(instance).getValue("body/legacy")
        val body = requireNotNull(activeBefore(instance).singleOrNull {
            it.id == bodyId && it.type == "body/legacy" && it.config["scaffold"] == JsonPrimitive(scaffold.id)
        }) { "Arm swing v2 requires a body binding on the same scaffold" }
        // Fitted layer footprints can move the neck pivot used to measure the arm's parent frame.
        val fitted = if (modern) fittedArmMeasurements(instance, scaffold) else null
        val measured = if (fitted == null) bodyContext(body).values else null
        val structural = measured?.getValue("scaffold")?.jsonObject
        val torso = fitted?.torso?.let {
            JsonArray(listOf(it.centerX, it.shoulderY, it.waistY, it.halfWidth).map(::JsonPrimitive))
        } ?: requireNotNull(measured).getValue("torso")
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", ArmSwingAlgorithm.SCHEMA)
            put("frames", fitted?.frames ?: requireNotNull(structural).getValue("frames"))
            put("branches", fitted?.branches ?: requireNotNull(structural).getValue("branches"))
            put("torso", torso)
            putJsonObject("targets") { instance.targets.forEach { put(it.id, layersByMesh.getValue(it.id)) } }
            putJsonObject("parameter_names") {
                put("L", tr("model.parameter.armLSwing"))
                put("R", tr("model.parameter.armRSwing"))
            }
            putJsonObject("physics_names") {
                put("L", tr("model.physics.armSwing", "L"))
                put("R", tr("model.physics.armSwing", "R"))
            }
            put("folder", tr("model.group.physics"))
        })
    }

    private fun RigModifierSource.fittedArmMeasurements(
        instance: RigModifierInstance, scaffold: RigModifierInstance,
    ): RigBuilder.ScaffoldSourceInputs {
        val preceding = activeBefore(instance).filter {
            it.config["scaffold"] == JsonPrimitive(scaffold.id) && it.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(it.type)?.let { spec -> spec.isBase || spec.mouthOutline } == true
        }
        require(preceding.all { it.targets.containsAll(instance.targets) }) {
            "Arm swing v3 requires shared source binding for the arm frame"
        }
        val baseByArea = preceding.filter { RigLegacyBindings.spec(it.type).isBase }
            .associateBy { RigLegacyBindings.spec(it.type).area }
        val corrections = WorkspaceSettingsCodec.decodeRecipe(scaffold.config["corrections"])
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { area ->
            val selected = baseByArea[area.id]
            val base = selected?.type?.takeUnless { it == RigLegacyBindings.FITTED_FACE } ?: area.legacy.id
            area.id to BaseChoice(base, selected?.version ?: area.legacy.version, selected?.let(RigLegacyBindings::values).orEmpty())
        }, moves = corrections.moves, eyeParts = corrections.eyeParts)
        val native = sourceConfig().copy(recipe = recipe,
            mouthOutlineEnabled = preceding.any { it.type == "mouth.outline" },
            featureDisplacementEnabled = scaffold.config["featureDisplacement"]?.jsonPrimitive?.boolean ?: false,
            hairSimulationFront = !(scaffold.config["frontHairSway"]?.jsonPrimitive?.boolean ?: true),
            hairSimulationBack = !(scaffold.config["backHairSway"]?.jsonPrimitive?.boolean ?: true))
        return RigBuilder.scaffoldSourceInputs(analysis, native, meshCache)
    }
}
