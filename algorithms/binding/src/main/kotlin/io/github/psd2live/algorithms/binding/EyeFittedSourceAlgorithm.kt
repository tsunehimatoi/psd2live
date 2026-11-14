package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Applies an eligible source frame transition and fitted eye motion as one change set. */
class EyeFittedSourceAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(
        input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit,
    ): ModifierModelChanges {
        require(context.upstream.none { it.capability in HEAD_TURN }) {
            "Fitted eye v3 requires head turn to be built from the fitted source scaffold"
        }
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        val upstream = context.upstream.single { it.capability == "rig.scaffold" && it.instanceId == scaffold }
        require(upstream.version == 2) { "Fitted eye v3 requires a version 2 source scaffold" }
        val provider = RigModifierInstance(scaffold, "rig.scaffold", upstream.version, upstream.targets,
            config = upstream.config, outputAliases = upstream.outputAliases, plugin = upstream.plugin)
        val transition = context.values.getValue(TRANSITION).jsonObject
        val materials = context.resources.getValue(MATERIALS)
        fun captured(key: String, meshes: String) = ModifierAlgorithmContext(transition.getValue(key).jsonObject,
            mapOf("materials" to materials, "source_meshes" to context.resources.getValue(meshes)))
        val eyeContext = ModifierAlgorithmContext(JsonObject(context.values - TRANSITION),
            context.resources - setOf(MATERIALS, COARSE_MESHES), listOf(upstream))
        val targets = eyeContext.values.getValue("eyes").jsonObject.getValue("materials").jsonObject.keys
        val selected = instance.targets.filter { it.kind == "mesh" }.mapTo(hashSetOf()) { it.id }
        require(targets.all { it in selected }) { "Fitted eye scaffold inputs exceed the selected targets" }
        val ordinary = captured("ordinary", COARSE_MESHES)
        val fitted = captured("fitted", FINE_MESHES)
        val preserved = linkedSetOf<String>()
        var planned = EyeScaffoldTransition.plan(input, provider, targets, ordinary, fitted, checkpoint, preserved)
        while (planned is EyeScaffoldTransition.Result.Blocked && planned.reason == EyeScaffoldTransition.Reason.AUTHORED_MESH) {
            // Preserve user-authored or pre-split meshes (e.g. source_split_components) while migrating untouched peers.
            preserved += planned.ids
            planned = EyeScaffoldTransition.plan(input, provider, targets, ordinary, fitted, checkpoint, preserved)
        }
        require(planned is EyeScaffoldTransition.Result.Ready) {
            val blocked = planned as EyeScaffoldTransition.Result.Blocked
            "Fitted eye v3 cannot migrate the source scaffold: ${blocked.reason} (${blocked.ids.sorted().joinToString()})"
        }
        val migration = planned.changes
        val eye = EyeFittedAlgorithm().changes(migration.apply(input, checkpoint), instance, eyeContext, checkpoint)
        return eye.copy(meshes = combine(migration.meshes, eye.meshes) { it.id },
            deformers = combine(migration.deformers, eye.deformers) { it.id })
    }

    private fun <T> combine(
        migration: ModifierObjectChanges<T>, binding: ModifierObjectChanges<T>, id: (T) -> String,
    ): ModifierObjectChanges<T> {
        val moved = migration.updated.associateBy { id(it.before) }
        val rebound = binding.updated.associateBy { id(it.before) }
        return ModifierObjectChanges(updated = (moved.keys + rebound.keys).map { key ->
            ModifierObjectUpdate(moved[key]?.before ?: rebound.getValue(key).before,
                rebound[key]?.after ?: moved.getValue(key).after)
        }.filter { it.before != it.after })
    }

    companion object {
        const val TYPE = "eye/fitted"
        const val ENTRYPOINT = "builtin.6579652f666974746564.v3/fitted"
        val HEAD_TURN = setOf("face/legacy", "face.depth-turn")
        val WRITES = setOf("mesh", "deformer", "parameter", "relations", "hierarchy")
        private const val TRANSITION = "scaffold_transition"
        private const val MATERIALS = "scaffold_materials"
        private const val FINE_MESHES = "scaffold_source_meshes"
        private const val COARSE_MESHES = "ordinary_scaffold_meshes"

        fun capture(eye: ModifierAlgorithmContext, ordinary: ModifierAlgorithmContext,
                    fitted: ModifierAlgorithmContext): ModifierAlgorithmContext {
            require(TRANSITION !in eye.values && MATERIALS !in eye.resources && COARSE_MESHES !in eye.resources)
            require(ordinary.resources.getValue("materials") == fitted.resources.getValue("materials") &&
                eye.resources.getValue(FINE_MESHES) == fitted.resources.getValue("source_meshes"))
            return ModifierAlgorithmContext(JsonObject(eye.values + (TRANSITION to JsonObject(mapOf(
                "ordinary" to ordinary.values, "fitted" to fitted.values,
            )))), eye.resources + mapOf(MATERIALS to ordinary.resources.getValue("materials"),
                COARSE_MESHES to ordinary.resources.getValue("source_meshes")), eye.upstream)
        }
    }
}
