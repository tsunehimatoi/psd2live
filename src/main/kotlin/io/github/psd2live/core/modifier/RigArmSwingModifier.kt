package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.ArmSwingAlgorithm
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*

internal object RigArmSwingModifier {
    private val algorithm = object : ReadingModifierAlgorithmPlugin by ArmSwingAlgorithm(), ModifierVersionSelection {
        override val canonicalOptInType = ArmSwingAlgorithm.TYPE
    }

    fun definition(source: RigModifierSource?): RigModifierDefinition {
        val legacy = RigLegacyBindings.definition(RigLegacyBindings.spec(ArmSwingAlgorithm.TYPE), source)
        val contexts = HashMap<RigModifierInstance, ModifierAlgorithmContext>()
        fun captured(instance: RigModifierInstance) = contexts.getOrPut(instance) { requireNotNull(source).capture(instance) }
        return legacy.copy(
            version = ArmSwingAlgorithm.VERSION,
            parameters = { ArmSwingAlgorithm.parameters(captured(it)) },
            resolveParameter = { _, _, _ -> null },
            evaluate = { _, _, _ -> error("Arm swing requires captured source inputs") },
            plugin = algorithm,
            changeWrites = ArmSwingAlgorithm.WRITES,
            composableKinds = legacy.composableKinds + "physics",
            upstreamInputs = true,
            authorContext = { captured(it) },
        )
    }

    private fun RigModifierSource.capture(instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = provider(instance)
        val packageVersion = scaffold.plugin?.let { ModifierPluginVersion.parse(it.version, legacy = true) }
        require(scaffold.version == 2 && (packageVersion == null || packageVersion >= ModifierPluginVersion.parse("1.0.41"))) {
            "Arm swing v2 requires the shared source scaffold v2"
        }
        val bodyId = RigLegacyBindings.providers(instance).getValue("body/legacy")
        val body = requireNotNull(activeBefore(instance).singleOrNull {
            it.id == bodyId && it.type == "body/legacy" && it.config["scaffold"] == JsonPrimitive(scaffold.id)
        }) { "Arm swing v2 requires a body binding on the same scaffold" }
        // Use the same source measurements as the body evaluator, without its raster resources or pitch grids.
        val measured = bodyContext(body, null).values
        val structural = measured.getValue("scaffold").jsonObject
        return ModifierAlgorithmContext(buildJsonObject {
            put("schema", ArmSwingAlgorithm.SCHEMA)
            put("frames", structural.getValue("frames"))
            put("branches", structural.getValue("branches"))
            put("torso", measured.getValue("torso"))
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
}
