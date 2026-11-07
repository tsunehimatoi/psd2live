package io.github.psd2live.core.modifier

import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Carries the source's static head angle between transient RAW bindings whose workers transfer the keyed pivot. */
internal object RawSourceRotationTransfer {
    private const val HEAD = "DeformHeadRotation"

    fun apply(before: RigIR, after: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext): RigIR {
        val fittedEye = instance.type == "eye/fitted" && instance.version == 5
        val fittedBrow = instance.type == "brow/fitted" && instance.version == 4
        if (!fittedEye && !fittedBrow || !BuiltinModifierPlugins.matches(instance)) return after
        val scaffold = context.upstream.single { it.type == "rig.scaffold" }
        val id = scaffold.outputAliases["shared/deformer:$HEAD"] ?: return after
        fun RigIR.rotation(id: String) = deformers.singleOrNull { it.id == id } as? Deformer.Rotation
        val live = before.rotation(id) ?: return after
        val output = after.rotation(id) ?: return after
        if (output.copy(pivot = live.pivot) != live || live.shapes.isNotEmpty()) return after
        val target = if (fittedEye) {
            val phases = context.values.getValue("fitted_head").jsonObject
            fun angle(phase: String) = phases.getValue(phase).jsonObject.getValue("base").jsonObject
                .getValue("scaffold").jsonObject.getValue("frames").jsonObject.getValue("head").jsonObject
                .getValue("initial_angle").jsonPrimitive.float
            // The eye worker has already checked the original parent and pivot against its source projection.
            if (live.baseAngle != angle("before")) return after
            angle("after")
        } else {
            fun rotation(phase: String) =
                RigIrBinary.decode(context.resources.getValue(phase).read()).rotation(HEAD)
            val prior = rotation("before") ?: return after
            val next = rotation("after") ?: return after
            val parent = prior.parent?.let { scaffold.outputAliases["shared/deformer:$it"] ?: it }
            if (live.parent != parent || next.parent != prior.parent ||
                live.copy(id = prior.id, parent = prior.parent, part = prior.part) != prior ||
                output.pivot?.axes != next.pivot?.axes) return after
            next.baseAngle
        }
        return if (target == live.baseAngle) after else after.copy(deformers = after.deformers.map {
            if (it === output) output.copy(baseAngle = target) else it
        })
    }
}
