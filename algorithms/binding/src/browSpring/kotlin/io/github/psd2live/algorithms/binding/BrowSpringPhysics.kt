package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithms.physics.PhysicsGroups
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Physics
import io.github.psd2live.format.model.PhysicsGroup
import kotlinx.serialization.json.*

/** The same native slots serve the recipe, legacy stack and isolated worker. */
object BrowSpringPhysics {
    fun rules(available: Set<String>, name: (String) -> String): List<RigPhysicsEdit> = listOf("L", "R").mapNotNull { side ->
        val spring = "ParamBrow${side}Spring"
        if (spring !in available) return@mapNotNull null
        val inputs = listOf(PhysicsInput("ParamBrow${side}Y", 70f, PhysicsSourceType.X),
            PhysicsInput("ParamBrow${side}Angle", 30f, PhysicsSourceType.X)).filter { it.parameter in available }
        if (inputs.isEmpty()) return@mapNotNull null
        RigPhysicsEdit(id = "PhysicsBrow${side}Spring", name = name(side), inputs = inputs,
            outputs = listOf(PhysicsOutput(spring, 2, 1.2f)),
            segments = listOf(PhysicsSegment(1f, 0.92f, 0.55f, 1.6f), PhysicsSegment(1f, 0.85f, 0.45f, 1.8f)),
            normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f))
    }

    internal fun changes(
        before: Physics,
        instance: RigModifierInstance,
        available: Set<String>,
        names: Map<Side, String>,
    ): ModifierObjectChanges<PhysicsGroup> {
        val native = rules(available) { names.getValue(if (it == "L") Side.LEFT else Side.RIGHT) }
        val overrides = instance.config["physics"]?.jsonObject.orEmpty()
        require(overrides.keys.all { key -> native.any { it.id == key } }) { "${instance.id}: unknown or unavailable module physics group" }
        val added = ArrayList<PhysicsGroup>()
        val reused = LinkedHashSet<PhysicsGroup>()
        for (rule in native) {
            val edit = overrides[rule.id]?.jsonObject?.let(RigPhysicsEdit::fromJson) ?: rule
            require(edit.parameters.all { it in available } && edit.inputs.isNotEmpty() && edit.outputs.isNotEmpty() &&
                edit.inputs.none { input -> edit.outputs.any { it.parameter == input.parameter } }) {
                "${instance.id}: invalid physics ${rule.id}"
            }
            val requested = PhysicsGroups.fromSetting(edit.copy(id = instance.outputId("physics:${rule.id}")))
            val existing = before.groups + added
            val compatible = existing.firstOrNull { it.copy(id = requested.id, name = requested.name) == requested }
            if (compatible != null) {
                if (compatible !in added) reused += compatible
            } else {
                PhysicsGroups.validateAppend(Physics(existing), listOf(requested))
                added += requested
            }
        }
        return ModifierObjectChanges(added = added, reused = reused.toList())
    }
}
