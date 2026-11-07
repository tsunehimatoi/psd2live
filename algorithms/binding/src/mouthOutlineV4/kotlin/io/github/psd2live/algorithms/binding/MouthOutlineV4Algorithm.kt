package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Deformer as IrDeformer
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.MeshGeometry
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Parameter

object MouthOutlineV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthOutlineV4Algorithm.ENTRYPOINT to MouthOutlineV4Algorithm.VERSION) to MouthOutlineV4Algorithm(),
    ))
}

/** Rebuilds the local outline and the source frames that its active Classic head turn uses. */
class MouthOutlineV4Algorithm : ReadingModifierAlgorithmPlugin {
    @Serializable
    data class Stationary(val canonical: String, val parent: String, val before: ModifierSourceBounds, val after: ModifierSourceBounds)

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val values = context.values
        require(values["schema"] == JsonPrimitive("classic-outline/1"))
        val scaffoldId = instance.config.getValue("scaffold").jsonPrimitive.content
        val scaffold = input.providers("rig.scaffold").single { it.instanceId == scaffoldId && it.version == 2 }
        require(scaffold.targets.toSet() == instance.targets.toSet())
        val structure = RigModifierInstance(scaffoldId, scaffold.type, scaffold.version, scaffold.targets,
            config = scaffold.config, outputAliases = scaffold.outputAliases, plugin = scaffold.plugin)
        for (type in listOf("mouth/legacy", "face/legacy")) {
            require(input.providers(type).any {
                it.type == type && it.version == 2 && it.config["scaffold"] == JsonPrimitive(scaffoldId) &&
                    it.targets.containsAll(instance.targets)
            }) { "Classic outline requires $type v2 on the same scaffold" }
        }
        val owners = Json.decodeFromJsonElement<Map<String, MouthOutlineV2Algorithm.Owner>>(values.getValue("owners"))
        if (owners.isEmpty()) return ModifierModelChanges()
        val rawKey = values.getValue("raw").jsonPrimitive.content
        val raw = ScaffoldMeshContext.decodeRawMeshes(context.resources.getValue(rawKey), checkpoint)
        val neutral = owners.mapValues { (_, owner) ->
            val source = raw.getValue(owner.canonical)
            val parent = DeformerId(owner.parent)
            val mesh = sourceMesh(source, parent, owner.frame, checkpoint)
            MouthOutlineLegacyCandidate.NeutralOwner(parent, mesh, source.rigPositions, owner.motionFrame, owner.aperture, owner.texture.frame())
        }
        val outline = ModifierAlgorithmContext(values.getValue("outline").jsonObject, context.resources - rawKey, context.upstream)
        val changes = MouthOutlineLegacyCandidate().changes(input, instance, neutral, checkpoint, outline)
        val moved = Json.decodeFromJsonElement<Map<String, Stationary>>(values.getValue("stationary")).mapNotNull { (id, measured) ->
            val mesh = requireNotNull(input.mesh(id))
            val parent = DeformerId(measured.parent)
            val material = raw.getValue(measured.canonical)
            val before = sourceMesh(material, parent, measured.before, checkpoint)
            val after = sourceMesh(material, parent, measured.after, checkpoint)
            require(mesh.parent == measured.parent && mesh.geometry == geometry(before)) {
                "Classic outline cannot replace edited source geometry: $id"
            }
            val next = mesh.copy(geometry = geometry(after))
            ModifierObjectUpdate(mesh, next).takeIf { next != mesh }
        }
        val parameters = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters)).parameters
        val beforeValues = values.getValue("before").jsonObject
        val afterValues = values.getValue("after").jsonObject
        val body = input.providers("body/legacy")
        require(body.size <= 1 && body.all { it.version in 1..2 && it.config["scaffold"] == JsonPrimitive(scaffoldId) &&
            it.targets.containsAll(instance.targets) } && listOf(beforeValues, afterValues).all { ("body" in it) == body.isNotEmpty() }) {
            "Classic outline requires its measured body on the same scaffold"
        }
        val before = phase(beforeValues, parameters, checkpoint)
        val after = phase(afterValues, parameters, checkpoint)
        val mapped = instance.targets.flatMap { input.parentsOfMesh(it.id) }.associateBy { it.id }
        val deformerIds = before.keys.associateWith { ScaffoldBinding.deformerId(structure, instance.targets.first().id, it.raw) }
        val deformation = before.mapNotNull { (canonical, previous) ->
            val id = deformerIds.getValue(canonical)
            val live = mapped[id] ?: return@mapNotNull null
            val desired = requireNotNull(after[canonical])
            val old = PuppetIr.deformerToIr(previous)
            val next = PuppetIr.deformerToIr(desired)
            if (old == next) return@mapNotNull null
            val changed = when {
                live is IrDeformer.Warp && old is IrDeformer.Warp && next is IrDeformer.Warp -> {
                    require(live.rows == old.rows && live.columns == old.columns &&
                        live.parent == previous.parent?.let(deformerIds::getValue)) { "Classic outline source frame moved: $id" }
                    val lattice = when {
                        live.lattice == old.lattice -> next.lattice
                        old.lattice == next.lattice -> live.lattice
                        else -> {
                            val grids = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters,
                                deformers = listOf(live, old, next))).deformers.filterIsInstance<Deformer.Warp>()
                            val grid = BindingMerge.warpDelta(grids[0].geometryGrid, grids[1].geometryGrid, grids[2].geometryGrid,
                                parameters.associate { it.id to it.default }, checkpoint)
                            (PuppetIr.deformerToIr(grids[0].copy(geometryGrid = grid)) as IrDeformer.Warp).lattice
                        }
                    }
                    require(live.channels == old.channels || old.channels == next.channels) { "Classic outline source frame channels changed: $id" }
                    live.copy(lattice = lattice, channels = if (old.channels == next.channels) live.channels else next.channels)
                }
                live is IrDeformer.Rotation && old is IrDeformer.Rotation && next is IrDeformer.Rotation -> {
                    require(live.pivot == old.pivot) { "Classic outline source pivot changed: $id" }
                    live.copy(pivot = next.pivot)
                }
                else -> error("Classic outline source frame kind changed: $id")
            }
            ModifierObjectUpdate(live, changed).takeIf { changed != live }
        }
        return changes.copy(meshes = ModifierObjectChanges(updated = changes.meshes.updated + moved),
            deformers = ModifierObjectChanges(updated = deformation), authoring = null)
    }

    private fun sourceMesh(source: ScaffoldMesh.RawMesh, parent: DeformerId, frame: ModifierSourceBounds, checkpoint: () -> Unit) =
        ScaffoldMesh.build(source, ScaffoldArms.Parent(parent, Bounds(frame.left, frame.top, frame.right, frame.bottom)),
            ScaffoldMesh.Geometry.Zero, checkpoint).first

    private fun geometry(mesh: DrawableMesh) = MeshGeometry(Floats.wrap(mesh.positions), Floats.wrap(mesh.uvs), Ints.wrap(mesh.indices))

    private fun phase(value: JsonObject, parameters: List<Parameter>, checkpoint: () -> Unit): Map<DeformerId, Deformer> {
        val face = ModifierAlgorithmContext(value.getValue("face").jsonObject)
        val base = LegacyFaceInputs.decode(face, checkpoint)
        val frames = LegacyFaceInputs.scaffold(face).values.getValue("frames").jsonObject
        val neutral = ScaffoldFramesContext.build(frames, checkpoint)
        val branches = ScaffoldBranchesContext.build(value.getValue("branches").jsonObject, neutral, checkpoint)
        val all = neutral + branches.deformers
        val measured = ScaffoldFramesContext.decode(frames, checkpoint)
        val body = value["body"]?.jsonObject
        val carried = if (body == null) all else {
            val legs = measured.standing?.legs.orEmpty().map { leg ->
                BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y, leg.ankle.x, leg.ankle.y, leg.floorY)
            }
            val tuning = LegacyBodyInputs.tuning(body.getValue("tuning").jsonObject)
            val strength = LegacyBodyInputs.finite(body.getValue("strength"))
            val stance = BodyStance(measured.body, LegacyBodyInputs.torso(body.getValue("torso")), legs,
                measured.standing?.frame, strength, tuning)
            LegacyBodyMotion.deformers(all, measured.body, stance, strength, measured.head.canvasPivot.y, branches.armFrames, checkpoint)
        }
        val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
        val byParameter = parameters.associateBy { it.id }
        val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), emptyList()))
            .copy(parameters = parameters.filterNot { it.id in angles }, deformers = carried)
        val posed = LegacyFaceBinding.bind(model,
            base.measurements, base.tuning, base.strength, angles.map(byParameter::getValue), base.folderLabel, checkpoint)
        return BindingProjection.retain(posed, byParameter.keys, checkpoint = checkpoint).deformers.associateBy { it.id }
    }

    companion object {
        const val TYPE = "mouth.outline"
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.6d6f7574682e6f75746c696e65.v4/outline"
    }
}
