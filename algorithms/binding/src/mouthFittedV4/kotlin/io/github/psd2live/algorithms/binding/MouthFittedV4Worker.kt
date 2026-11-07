package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel

object MouthFittedV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthFittedV4Algorithm.ENTRYPOINT to MouthFittedV4Algorithm.VERSION) to MouthFittedV4Algorithm(),
    ))
}

/** Fitted mouth projection with legacy closed-drawing channels and all affected stationary frames. */
class MouthFittedV4Algorithm : ChangingModifierAlgorithmPlugin {
    private val fitted = MouthFittedV3Algorithm()

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val changes = fitted.changes(input, instance.copy(version = MouthFittedV3Algorithm.VERSION), context, checkpoint)
        val captured = MouthFittedInputs.decode(ModifierAlgorithmContext(context.values.getValue("mouth").jsonObject,
            context.resources - setOf("raw", "rawBefore"), context.upstream))
        val closed = captured.layers.filterValues { it.tag == "MOUTH_CLOSE" && it.preset }.keys
        require(closed.isNotEmpty()) { "Fitted mouth v4 requires a separate closed drawing" }
        val parameters = input.parameters + changes.parameters.added
        val local = changes.meshes.updated.map { update ->
            if (update.before.id !in closed) return@map update
            val source = PuppetIr.toPuppet(RigIR(input.canvas, parameters))
            val actual = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(update.before))).drawables.single()
            val projected = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(update.after))).drawables.single()
            fun ChannelGrids.opacityOnly() = ChannelGrids(gridsByChannel.filterKeys { it == FormChannel.OPACITY })
            val live = actual.copy(channelGrids = actual.channelGrids.opacityOnly())
            // Before the legacy fitted transition, the inactive open axis is projected at full opening.
            // Its closed drawing has zero opacity there, so the preceding live opacity is a residual.
            val before = live.copy(opacity = 0f, channelGrids = ChannelGrids.Empty)
            val after = projected.copy(channelGrids = projected.channelGrids.opacityOnly())
            val opacity = ChannelResidual.channels(source, live, before, after, checkpoint)
            val tracks = projected.channelGrids.gridsByChannel + opacity.gridsByChannel
            val corrected = PuppetIr.meshToIr(projected.copy(channelGrids = ChannelGrids(tracks)))
            ModifierObjectUpdate(update.before, update.after.copy(channels = corrected.channels))
        }
        val scaffold = context.upstream.single { it.type == "rig.scaffold" }
        val structure = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        val stationary = context.values.getValue("stationary").jsonObject
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        require(stationary.keys.all { it in targets && it !in captured.layers }) { "Unknown fitted mouth stationary target" }
        val rawBefore by lazy { ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("rawBefore"), checkpoint) }
        val rawAfter by lazy { ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue("raw"), checkpoint) }
        val meshes = input.meshes.associateBy { it.id }
        val moved = stationary.mapNotNull { (id, observation) ->
            checkpoint()
            val actual = meshes.getValue(id)
            val frames = observation.jsonObject
            fun geometry(key: String, raw: ScaffoldMesh.RawMesh): Pair<String?, MeshGeometry> {
                val frame = frames.getValue(key).jsonObject
                val parent = frame.getValue("parent").jsonPrimitive.contentOrNull
                    ?.let { ScaffoldBinding.deformerId(structure, id, it) }
                val bounds = frame.getValue("bounds").jsonArray.map { it.jsonPrimitive.float }
                val mesh = if (parent == null) DrawableMesh(raw.rigPositions, raw.atlasUvs, raw.triangles)
                else ScaffoldMesh.build(raw, ScaffoldArms.Parent(DeformerId(parent), Bounds(bounds[0], bounds[1], bounds[2], bounds[3])),
                    ScaffoldMesh.Geometry.Zero, checkpoint).first
                return parent to MeshGeometry(Floats.wrap(mesh.positions), Floats.wrap(mesh.uvs), Ints.wrap(mesh.indices))
            }
            val (oldParent, old) = geometry("before", rawBefore.getValue(id))
            val (newParent, next) = geometry("after", rawAfter.getValue(id))
            require(actual.parent == oldParent && oldParent == newParent && actual.geometry == old) {
                "Fitted mouth v4 cannot replace edited stationary geometry: $id"
            }
            actual.copy(geometry = next).takeIf { it != actual }?.let { ModifierObjectUpdate(actual, it) }
        }
        return changes.copy(meshes = ModifierObjectChanges(updated = local + moved))
    }

    companion object {
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6d6f7574682f666974746564.v4/binding"
    }
}
