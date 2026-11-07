package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import kotlinx.serialization.json.jsonPrimitive

/** Reprojects the source geometry through the newly attached clothing chain. */
class RibbonsV4Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffoldId = instance.config.getValue("scaffold")
        fun provider(capability: String, type: String, version: Int): ModifierUpstreamCapability =
            requireNotNull(input.providers(capability).singleOrNull {
                it.type == type && it.version == version && it.targets.containsAll(instance.targets) &&
                    if (capability == "rig.scaffold") it.instanceId == scaffoldId.jsonPrimitive.content
                    else it.config["scaffold"] == scaffoldId
            }) { "Ribbons v4 requires $type@$version for every target" }
        provider("face/legacy", "face/fitted", 3)
        provider("body/legacy", "body/legacy", 2)
        provider("eye/fitted", "eye/fitted", 5)
        val scaffold = provider("rig.scaffold", "rig.scaffold", 2)
        fun onScaffold(capability: String) = input.providers(capability).filter {
            it.config["scaffold"] == scaffoldId && it.targets.any(instance.targets::contains)
        }
        val mouth = onScaffold("mouth/fitted").singleOrNull()
        require(mouth != null && mouth.type == "mouth/fitted" && mouth.version in 3..4 &&
            mouth.targets.containsAll(instance.targets)) { "Ribbons v4 requires a fitted open or closed mouth source" }
        val skirts = onScaffold("outfit.skirt")
        require(skirts.isEmpty() || skirts.singleOrNull()?.let {
            it.type == "outfit.skirt" && it.version == 2 && it.targets.containsAll(instance.targets)
        } == true) { "Ribbons v4 requires independent skirt v2 when a skirt binding is active" }
        val structure = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version,
            scaffold.targets, config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val context = input.context
        val attachmentContext = ModifierAlgorithmContext(context.values,
            context.resources.filterKeys { it == "source_meshes" }, input.upstream)
        val measurements = RibbonsInputs.decode(attachmentContext)
        require(measurements.skirtTargets.isEmpty() || skirts.isNotEmpty()) { "Ribbons v4 requires its captured skirt provider" }
        val attachments = measurements.layers.flatMap { it.meshes }
        val frames = attachments.associateWith(input::parentsOfMesh)
        // The frozen attachment kernel reads these objects and its original resource schema.
        val attachmentInput = ModifierEvaluationInput(RigIR(
            input.canvas, input.parameters, meshes = attachments.map { requireNotNull(input.mesh(it)) },
            deformers = frames.values.flatten().distinctBy { it.id },
            parts = input.parts, parameterTree = input.parameterTree, physics = input.physics,
        ), attachmentContext)
        val binding = RibbonsAlgorithm().changes(attachmentInput, instance.copy(version = RibbonsAlgorithm.VERSION), checkpoint)
        val parameters = input.parameters + binding.parameters.added
        val carriers = binding.deformers.added.associateBy { it.id }
        val updated = binding.meshes.updated.map { change ->
            checkpoint()
            val before = change.before
            val id = DrawableId(before.id)
            fun source(stage: String) = RigIrBinary.decode(input.context.resources.getValue("ribbon/${id.raw}/$stage").read())
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
            } == true }) { "Ribbons v4 requires unedited source topology for ${id.raw}" }
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
        val UPSTREAM = setOf("eye/fitted", "mouth/fitted", "outfit.skirt")
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6f75746669742e726962626f6e73.v4/ribbons"
    }
}

object RibbonsV4Worker {
    @JvmStatic fun main(args: Array<String>) {
        AlgorithmWorker.run(mapOf((RibbonsV4Algorithm.ENTRYPOINT to RibbonsV4Algorithm.VERSION) to RibbonsV4Algorithm()))
    }
}
