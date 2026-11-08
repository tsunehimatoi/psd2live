package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId

/** Reprojects the source geometry through the newly attached creature chain. */
class CreatureV5Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffoldId = instance.config.getValue("scaffold")
        fun provider(capability: String, type: String, version: Int): ModifierUpstreamCapability =
            requireNotNull(input.providers(capability).singleOrNull {
                it.type == type && it.version == version && it.targets.containsAll(instance.targets) &&
                    if (capability == "rig.scaffold") it.instanceId == scaffoldId.jsonPrimitive.content
                    else it.config["scaffold"] == scaffoldId
            }) { "Creature v5 requires $type@$version for every target" }
        provider("face/legacy", "face/fitted", 3)
        provider("body/legacy", "body/legacy", 2)
        for ((type, version) in PROFILE) provider(type, type, version)
        val skirts = input.providers("outfit.skirt").filter { candidate ->
            candidate.config["scaffold"] == scaffoldId && candidate.targets.any(instance.targets::contains)
        }
        require(skirts.isEmpty() || skirts.singleOrNull()?.let {
            it.type == "outfit.skirt" && it.version == 2 && it.targets.containsAll(instance.targets)
        } == true) { "Creature v5 requires independent skirt v2 when a skirt binding is active" }
        val scaffold = provider("rig.scaffold", "rig.scaffold", 2)
        val structure = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version,
            scaffold.targets, config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val context = input.context
        val attachmentContext = ModifierAlgorithmContext(context.values,
            context.resources.filterKeys { it == "source_meshes" }, input.upstream)
        val attachments = CreatureInputs.decode(attachmentContext).layers.flatMap { it.meshes }
        val frames = attachments.associateWith(input::parentsOfMesh)
        // The frozen attachment kernel reads these objects and its original resource schema.
        val attachmentInput = ModifierEvaluationInput(RigIR(
            input.canvas, input.parameters, meshes = attachments.map { requireNotNull(input.mesh(it)) },
            deformers = frames.values.flatten().distinctBy { it.id },
            parts = input.parts, parameterTree = input.parameterTree, physics = input.physics,
        ), attachmentContext)
        val binding = CreatureAlgorithm().changes(attachmentInput, instance.copy(version = CreatureAlgorithm.VERSION), checkpoint)
        val parameters = input.parameters + binding.parameters.added
        val carriers = binding.deformers.added.associateBy { it.id }
        val updated = binding.meshes.updated.map { change ->
            checkpoint()
            val before = change.before
            val id = DrawableId(before.id)
            fun source(stage: String) = RigIrBinary.decode(input.context.resources.getValue("creature/${id.raw}/$stage").read())
            val prior = source("before")
            val desired = source("after")
            val priorMesh = prior.meshes.single()
            val nextMesh = desired.meshes.single()
            val liveFrames = frames.getValue(id.raw)
            val aliases = prior.deformers.associate { it.id to ScaffoldBinding.deformerId(structure, id.raw, it.id) }
            fun sameFrame(native: Deformer): Boolean {
                val frame = liveFrames.singleOrNull { it.id == aliases.getValue(native.id) } ?: return false
                val parent = native.parent?.let(aliases::getValue)
                return when (native) {
                    is Deformer.Warp -> native.copy(id = frame.id, parent = parent, part = frame.part) == frame
                    is Deformer.Rotation -> native.copy(id = frame.id, parent = parent, part = frame.part) == frame
                }
            }
            val nativeGeometry = before.geometry == priorMesh.geometry && before.offsets == priorMesh.offsets &&
                before.shapes == priorMesh.shapes && before.parent == priorMesh.parent?.let(aliases::getValue) &&
                liveFrames.size == prior.deformers.size && prior.deformers.all(::sameFrame)
            if (nativeGeometry) return@map ModifierObjectUpdate(before, change.after.copy(
                geometry = nextMesh.geometry, offsets = nextMesh.offsets, shapes = nextMesh.shapes))

            val carrier = carriers.getValue(requireNotNull(change.after.parent))
            val live = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(before), deformers = liveFrames))
            val staged = live.copy(deformers = live.deformers + PuppetIr.toPuppet(RigIR(input.canvas, parameters,
                deformers = listOf(carrier))).deformers,
                drawables = live.drawables.map { it.copy(parentDeformerId = DeformerId(carrier.id)) })
            val carried = BindingFrames.carriedToParent(live, staged, id, checkpoint)
            val geometry = requireNotNull(before.geometry)
            require(listOf(priorMesh, nextMesh).all { it.geometry?.let { form ->
                form.uvs == geometry.uvs && form.indices == geometry.indices
            } == true }) { "Creature v5 requires unedited source topology for ${id.raw}" }
            val vertices = List(geometry.positions.size / 2) { VertexSource.FromOld(it) }
            val oldStatic = PuppetIr.toPuppet(prior.copy(parameters = desired.parameters, deformers = source("before-static").deformers))
            val nextStatic = PuppetIr.toPuppet(desired.copy(deformers = source("after-static").deformers))
            val offsets = GeometryResidual.geometry(carried, oldStatic, nextStatic, id, vertices, vertices, checkpoint)
            val projected = carried.copy(drawables = carried.drawables.map { it.copy(geometryGrid = offsets) })
            val shapes = MeshBlendResidual.merge(carried, projected, oldStatic, nextStatic, id, vertices, vertices, checkpoint)
            val mesh = PuppetIr.meshToIr(projected.drawables.single().copy(blendShapes = shapes))
            ModifierObjectUpdate(before, change.after.copy(geometry = mesh.geometry, offsets = mesh.offsets, shapes = mesh.shapes))
        }
        return binding.copy(meshes = ModifierObjectChanges(updated = updated))
    }

    companion object {
        private val PROFILE = mapOf("eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 6)
        val UPSTREAM = PROFILE.keys + "outfit.skirt"
        const val VERSION = 5
        const val ENTRYPOINT = "builtin.6f75746669742e6372656174757265.v5/creature"
    }
}

object CreatureV5Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((CreatureV5Algorithm.ENTRYPOINT to CreatureV5Algorithm.VERSION) to CreatureV5Algorithm()))
    }
}
