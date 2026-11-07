package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ModifierObjectUpdate
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reuses the source head projection on the verified modern body and face providers. */
class EyeFittedV5Algorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val head = context.values.getValue("fitted_head").jsonObject
        val scaffold = context.upstream.single { it.capability == "rig.scaffold" }
        val face = context.upstream.single { it.capability == "face/fitted" }
        val body = context.upstream.single { it.capability == "body/legacy" }
        require(body.type == "body/legacy" && body.version in 1..2 &&
            face.type == "face/fitted" && face.version in setOf(1, 3) &&
            body.config["scaffold"]?.jsonPrimitive?.content == scaffold.instanceId &&
            face.config["scaffold"]?.jsonPrimitive?.content == scaffold.instanceId &&
            body.instanceId == head.getValue("body_provider").jsonPrimitive.content) {
            "Fitted eye v5 requires the captured legacy body and fitted face providers"
        }
        // The existing head projection consumes the same measurements for these providers.
        val projection = context.withUpstream(context.upstream.map {
            when (it) {
                face -> it.copy(capability = "face/legacy", version = 1)
                body -> it.copy(version = 1)
                else -> it
            }
        })
        val eye = EyeFittedV4Algorithm().changes(input, instance.copy(version = EyeFittedV4Algorithm.VERSION), projection, checkpoint)
        val bodyBefore = projectBody(head.getValue("before").jsonObject, checkpoint)
        val bodyAfter = projectBody(head.getValue("after").jsonObject, checkpoint)
        val scaffoldInstance = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type,
            scaffold.version, scaffold.targets, config = scaffold.config, outputAliases = scaffold.outputAliases,
            plugin = scaffold.plugin)
        val representative = instance.targets.first().id
        val eyeFrames = eye.deformers.updated.associateBy { it.before.id }
        val bodyChanges = bodyBefore.mapNotNull { (canonical, prior) ->
            checkpoint()
            val next = bodyAfter.getValue(canonical)
            if (prior.lattice == next.lattice) return@mapNotNull null
            val id = ScaffoldBinding.deformerId(scaffoldInstance, representative, canonical)
            val live = input.deformers.single { it.id == id } as Deformer.Warp
            require(live.lattice == prior.lattice && live.shapes.isEmpty()) {
                "Fitted eye v5 cannot migrate authored body frame $id"
            }
            val projected = eyeFrames[id]?.after as? Deformer.Warp ?: live
            ModifierObjectUpdate<Deformer>(live, projected.copy(lattice = next.lattice))
        }
        val changedIds = bodyChanges.mapTo(hashSetOf()) { it.before.id }
        val meshes = eye.meshes.updated.map { update ->
            val mesh = update.after
            val offsets = mesh.offsets
            // The legacy fitted eyes enumerate the eye key fastest inside sampled head turns.
            val parameters = offsets?.axes.orEmpty().map { it.parameter }
            if (offsets == null || parameters.none { it.startsWith("ParamEye") } ||
                parameters.none { it == LegacyFaceBinding.angleX.raw || it == LegacyFaceBinding.angleY.raw }) update
            else {
                val cells = offsets.cells.sortedWith { left, right ->
                    offsets.axes.indices.reversed().firstNotNullOfOrNull { axis ->
                        left.coordinate[axis].compareTo(right.coordinate[axis]).takeIf { it != 0 }
                    } ?: 0
                }
                ModifierObjectUpdate(update.before, mesh.copy(offsets = offsets.copy(cells = cells)))
            }
        }
        return eye.copy(
            deformers = ModifierObjectChanges(eye.deformers.added,
                eye.deformers.updated.filterNot { it.before.id in changedIds } + bodyChanges, eye.deformers.removed, eye.deformers.reused),
            meshes = ModifierObjectChanges(eye.meshes.added, meshes, eye.meshes.removed, eye.meshes.reused),
        )
    }

    private fun projectBody(phase: JsonObject, checkpoint: () -> Unit): Map<String, Deformer.Warp> {
        val context = ModifierAlgorithmContext(phase.getValue("base").jsonObject)
        val source = LegacyFaceInputs.scaffold(context).values
        val frameData = source.getValue("frames").jsonObject
        val frames = ScaffoldFramesContext.decode(frameData, checkpoint)
        val base = ScaffoldFramesContext.build(frameData, checkpoint)
        val branches = ScaffoldBranchesContext.build(source.getValue("branches").jsonObject, base, checkpoint)
        val body = phase.getValue("body").jsonObject
        val standing = frames.standing
        val legs = standing?.legs.orEmpty().map {
            BodyStance.Leg(Side.NONE, it.hip.x, it.hip.y, it.knee.x, it.knee.y, it.ankle.x, it.ankle.y, it.floorY)
        }
        val strength = LegacyBodyInputs.finite(body.getValue("strength"))
        val tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
        val stance = BodyStance(frames.body, LegacyBodyInputs.torso(body.getValue("torso")), legs, standing?.frame, strength, tuning)
        val ids = setOf(ScaffoldBody.bodyId, ScaffoldBody.leanId, ScaffoldBody.legsId, ScaffoldHead.breathId) + branches.armFrames.keys
        return LegacyBodyMotion.deformers(base + branches.deformers, frames.body, stance, strength,
            frames.head.canvasPivot.y, branches.armFrames, checkpoint)
            .filter { it.id in ids }
            .map { PuppetIr.deformerToIr(it) as Deformer.Warp }
            .associateBy { it.id }
    }

    companion object {
        val REQUIRES = setOf("rig.scaffold", "body/legacy", "face/fitted")
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.6579652f666974746564.v5/fitted"
    }
}

object EyeFittedV5Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (EyeFittedV5Algorithm.ENTRYPOINT to EyeFittedV5Algorithm.VERSION) to EyeFittedV5Algorithm(),
    ))
}
