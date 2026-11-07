package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer as DeformerIr
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.model.*

object DepthTurnV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(
        mapOf((DepthTurnV2Algorithm.ENTRYPOINT to DepthTurnV2Algorithm.VERSION) to DepthTurnV2Algorithm))
}

object DepthTurnV2Algorithm : ChangingModifierAlgorithmPlugin {
    const val TYPE = "face.depth-turn"
    const val VERSION = 2
    const val ENTRYPOINT = "builtin.666163652e64657074682d7475726e.v2/turn"
    val WRITES = setOf("mesh", "deformer")
    private val lean = ParameterId("ParamBodyLean")

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = requireProvider(context, instance, "rig.scaffold", instance.config.getValue("scaffold"), 2..2)
        val faceId = instance.config.getValue("dependencies").jsonObject.getValue("face/legacy")
        val face = requireProvider(context, instance, "face/legacy", faceId, 1..2)
        val body = context.values["body"]?.let { requireProvider(context, instance, "body/legacy", it, 1..2) }
        require(listOfNotNull(face, body).all { it.config["scaffold"] == instance.config["scaffold"] }) {
            "Depth turn v2 providers must use the same scaffold"
        }
        val inputs = DepthTurnInputs.decode(ModifierAlgorithmContext(context.values.getValue("turn").jsonObject, context.resources))
        val base = LegacyFaceInputs.decode(ModifierAlgorithmContext(context.values.getValue("base").jsonObject), checkpoint)
        val targets = instance.targets.mapTo(linkedSetOf()) { it.id }
        val expectedParents = context.values.getValue("parents").jsonObject
        require(targets == inputs.layers.keys && targets == expectedParents.keys)

        val actualIds = input.deformers.mapTo(hashSetOf()) { it.id }
        val provider = RigModifierInstance(requireNotNull(scaffold.instanceId), scaffold.type, scaffold.version,
            scaffold.targets, outputAliases = scaffold.outputAliases)
        val canonicalIds = input.deformers.mapNotNull { deformer ->
            provider.localOutputName(deformer.id)?.takeIf { it.startsWith("shared/deformer:") }?.let {
                DeformerId(deformer.id) to DeformerId(it.removePrefix("shared/deformer:"))
            }
        }.toMap()
        require(ScaffoldHead.faceId in canonicalIds.values && canonicalIds.values.distinct().size == canonicalIds.size &&
            canonicalIds.none { (live, canonical) -> live != canonical && canonical.raw in actualIds }) {
            "Depth turn v2 requires unambiguous shared scaffold deformer IDs"
        }
        val before = ModelIdRemap(deformers = canonicalIds).model(PuppetIr.toPuppet(input))
        val raw = ScaffoldMeshContext.decodeRawMeshes(requireNotNull(inputs.sourceMeshes), checkpoint)
        require(before.drawables.count { it.id.raw in targets } == targets.size)
        for (drawable in before.drawables) {
            checkpoint()
            val layer = inputs.layers[drawable.id.raw] ?: continue
            if (DepthTurnMotion.shell(layer.tag, layer.role) == null) continue
            val source = raw.getValue(requireNotNull(layer.sourceMesh))
            val geometry = requireNotNull(drawable.mesh)
            val frame = layer.frame
            require(drawable.parentDeformerId?.raw == expectedParents.getValue(drawable.id.raw).jsonPrimitive.content &&
                source.triangles.contentEquals(geometry.indices) && source.atlasUvs.contentEquals(geometry.uvs) &&
                source.rigPositions.size == geometry.positions.size && source.rigPositions.indices.all { i ->
                    val original = if (i % 2 == 0) (source.rigPositions[i] - frame.left) / frame.width.coerceAtLeast(1e-4f)
                        else (source.rigPositions[i] - frame.top) / frame.height.coerceAtLeast(1e-4f)
                    original == geometry.positions[i]
                }) {
                "Depth turn v2 requires unedited source geometry and its original parent on ${drawable.id.raw}"
            }
        }

        val turned = DepthTurnAlgorithm().project(before, targets, inputs, checkpoint)
        val previous = before.deformers.associateBy { it.id }
        val originalIds = canonicalIds.entries.associate { (live, canonical) -> canonical to live }
        val parameterIds = before.parameters.mapTo(hashSetOf()) { it.id }
        val hasLean = lean in parameterIds
        require(hasLean == (body != null)) { "Depth turn v2 requires the captured body lean provider" }
        val expectedHead by lazy(LazyThreadSafetyMode.NONE) {
            val sourceHead = ScaffoldHead.build(base.measurements.head, { it }, checkpoint).deformers
            val angleIds = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
            val angles = angleIds.map { id -> before.parameters.single { it.id == id } }
            LegacyFaceBinding.bind(before.copy(deformers = sourceHead), base.measurements, base.tuning, base.strength,
                angles, base.folderLabel, checkpoint).deformers.associateBy { it.id }
        }
        val carriers = turned.deformers.filter { it.id !in previous }
        val carrierAliases = carriers.associate { it.id to DeformerId(
            ScaffoldBinding.deformerId(instance, instance.targets.first().id, it.id.raw, shared = true)) }
        require(carrierAliases.values.distinct().size == carrierAliases.size &&
            carrierAliases.values.none { it.raw in actualIds }) { "Depth turn v2 carrier ID collides with an existing deformer" }
        val remap = ModelIdRemap(deformers = originalIds + carrierAliases)
        val meshes = turned.drawables.associateBy { it.id.raw }
        val beforeMeshes = before.drawables.associateBy { it.id.raw }
        val liveDeformers = input.deformers.associateBy { it.id }
        val updates = turned.deformers.mapNotNull { deformer ->
            val old = previous[deformer.id] ?: return@mapNotNull null
            if (old === deformer) return@mapNotNull null
            val warp = deformer as? Deformer.Warp ?: return@mapNotNull null
            val grid = if (hasLean) warp.geometryGrid else BindingProjection.restrict(warp.geometryGrid, parameterIds,
                emptyMap(), WarpLatticeInterpolator, checkpoint)
            val after = PuppetIr.deformerToIr(remap.deformer(warp.copy(geometryGrid = grid))) as DeformerIr.Warp
            val live = liveDeformers.getValue(after.id) as DeformerIr.Warp
            val updated = live.copy(rows = after.rows, columns = after.columns, lattice = after.lattice)
            if (updated == live) return@mapNotNull null
            val expected = expectedHead[warp.id]?.let { PuppetIr.deformerToIr(remap.deformer(it)) } as? DeformerIr.Warp
            require(expected != null && live.rows == expected.rows && live.columns == expected.columns &&
                live.parent == expected.parent && live.bilinear == expected.bilinear && live.lattice == expected.lattice) {
                "Depth turn v2 cannot replace edited or unsupported head warp ${warp.id.raw}"
            }
            require((live.rows == after.rows && live.columns == after.columns) || live.shapes.isEmpty()) {
                "Depth turn v2 cannot resize a head warp with authored blend shapes"
            }
            ModifierObjectUpdate<DeformerIr>(live, updated)
        }
        val parameters = PuppetIr.parametersToIr(turned).associateBy { it.id }
        require(input.parameters.all { it == parameters[it.id] }) { "Depth turn v2 cannot change preceding parameter definitions" }
        return ModifierModelChanges(
            meshes = ModifierObjectChanges(updated = input.meshes.filter { it.id in targets }.mapNotNull { mesh ->
                val turnedMesh = meshes.getValue(mesh.id)
                if (turnedMesh === beforeMeshes.getValue(mesh.id)) return@mapNotNull null
                val after = PuppetIr.meshToIr(remap.drawable(turnedMesh))
                update(mesh, mesh.copy(parent = after.parent, geometry = after.geometry, offsets = after.offsets,
                    channels = after.channels, shapes = after.shapes))
            }),
            deformers = ModifierObjectChanges(added = carriers.map { PuppetIr.deformerToIr(remap.deformer(it)) }, updated = updates),
        )
    }

    private fun requireProvider(context: ModifierAlgorithmContext, instance: RigModifierInstance,
                                type: String, id: JsonElement, versions: IntRange): ModifierUpstreamCapability =
        requireNotNull(context.upstream.singleOrNull {
            it.capability == type && it.type == type && it.version in versions &&
                it.instanceId == id.jsonPrimitive.content && it.targets.toSet() == instance.targets.toSet()
        }) { "Depth turn v2 requires the captured $type provider" }

    private fun <T> update(before: T, after: T) = if (before == after) null else ModifierObjectUpdate(before, after)
}
