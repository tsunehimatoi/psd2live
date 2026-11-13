package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.*

object NeckV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (NeckV4Algorithm.ENTRYPOINT to NeckV4Algorithm.VERSION) to NeckV4Algorithm(),
    ))
}

/** Reconstructs the source neck transition in the worker, leaving all upstream carriers untouched. */
class NeckV4Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" } &&
            instance.targets.distinct().size == instance.targets.size) { "Invalid source neck invocation" }
        val source = NeckV4Inputs(input.context, checkpoint)
        val captured = source.providers
        require(input.upstream.map { it.copy(capability = it.type) }.distinct().toSet() == captured.toSet()) {
            "Neck v4 requires its exact captured providers, targets, aliases and packages"
        }
        val scaffoldId = instance.config.getValue("scaffold")
        val scaffold = requireNotNull(captured.singleOrNull {
            it.type == "rig.scaffold" && it.instanceId == scaffoldId.jsonPrimitive.content && it.version == 2 &&
                it.targets.containsAll(instance.targets)
        }) { "Neck v4 requires the configured shared scaffold" }
        val faceId = instance.config.getValue("dependencies").jsonObject.getValue("face/legacy").jsonPrimitive.content
        require(input.upstream.singleOrNull { it.capability == "face/legacy" && it.instanceId == faceId }?.let {
            supportedFace(it.type, it.version) && it.config["scaffold"] == scaffoldId &&
                it.targets.containsAll(instance.targets)
        } == true) { "Neck v4 requires the configured face capability owner" }
        require(captured.filterNot { it.type == "rig.scaffold" }.all {
            it.config["scaffold"] == scaffoldId && (it.type == NeckAlgorithm.TYPE || it.targets.containsAll(instance.targets))
        }) { "Neck v4 requires shared source configuration on the same scaffold" }
        val bodies = captured.filter { it.type == "body/legacy" }
        require(bodies.size <= 1 && source.strength == (bodies.singleOrNull()?.config?.get("strength")?.jsonPrimitive?.float ?: 1f)) {
            "Neck v4 requires its captured body strength"
        }
        val already = captured.filter { it.type == NeckAlgorithm.TYPE }.flatMapTo(hashSetOf()) { it.targets }
        require(source.neck.targets.keys.all { io.github.psd2live.core.modifier.ModifierTarget("mesh", it) in instance.targets &&
            io.github.psd2live.core.modifier.ModifierTarget("mesh", it) !in already }) { "Neck v4 cannot apply an earlier neck twice" }
        if (source.neck.targets.isEmpty()) return ModifierModelChanges()
        for ((id, extent) in listOf("ParamAngleX" to 45f, "ParamAngleY" to 30f, "ParamAngleZ" to 30f)) {
            require(input.parameter(id)?.let { !it.blend && it.min == -extent && it.max == extent && it.default == 0f } == true) {
                "Neck v4 requires the ordinary head angle $id"
            }
        }
        val parameters = input.parameters
        val branch = source.branch(checkpoint)
        val owners = captured.map { RigModifierInstance(requireNotNull(it.instanceId), it.type, it.version,
            it.targets, config = it.config, outputAliases = it.outputAliases, plugin = it.plugin) }
        val provider = owners.single { it.id == scaffold.instanceId }
        val updates = source.neck.targets.mapNotNull { (id, target) ->
            checkpoint()
            val live = requireNotNull(input.mesh(id))
            val geometry = requireNotNull(live.geometry) { "Neck v4 requires source mesh geometry" }
            val raw = source.raw.getValue(target.sourceMesh)
            // Authored coordinates and tracks are residuals. A topology edit needs a separately verified migration.
            require(geometry.uvs.shared().contentEquals(raw.atlasUvs) && geometry.indices.shared().contentEquals(raw.triangles) &&
                geometry.positions.size == raw.rigPositions.size && geometry.positions.shared().all(Float::isFinite)) {
                "Neck v4 requires unchanged source UVs and topology"
            }
            val actualDeformers = input.parentsOfMesh(id).associateBy { it.id }
            val reference = ModifierTarget("mesh", id)
            val aliases = branch.associate { canonical ->
                val matches = owners.filter { reference in it.targets }.map {
                    ScaffoldBinding.deformerId(it, id, canonical.id.raw, shared = true)
                }.distinct().filter { it in actualDeformers }
                require(matches.size == 1) { "Neck v4 cannot resolve one preceding owner for ${canonical.id.raw}" }
                canonical.id to DeformerId(matches.single())
            }
            require(aliases.values.map { it.raw }.toSet() == actualDeformers.keys &&
                aliases.values.distinct().size == aliases.size && live.parent == aliases.getValue(ScaffoldHead.breathId).raw) {
                "Neck v4 cannot replace or bypass an authored parent or turn carrier"
            }
            for (canonical in branch) {
                val actual = actualDeformers.getValue(aliases.getValue(canonical.id).raw)
                require(actual is io.github.psd2live.format.model.Deformer.Warp &&
                    actual.parent == canonical.parent?.let { aliases.getValue(it).raw }) {
                    "Neck v4 requires the unchanged body branch structure"
                }
            }
            val parts = branch.mapNotNull { canonical ->
                val actual = actualDeformers.getValue(aliases.getValue(canonical.id).raw).part ?: return@mapNotNull null
                canonical.partId?.let { it to PartId(actual) }
            }.toMap()
            val current = PuppetIr.toPuppet(RigIR(input.canvas, parameters, meshes = listOf(live),
                deformers = actualDeformers.values.toList(),
                parts = parts.values.distinct().map { requireNotNull(input.part(it.raw)) },
                textures = input.textures, authoring = input.authoring))
            val neutral = PuppetIr.toPuppet(RigIR(input.canvas, parameters)).copy(deformers = branch)
            fun phase(tilt: Boolean): PuppetModel {
                val (mesh, grid) = ScaffoldMesh.build(raw, ScaffoldArms.Parent(ScaffoldHead.breathId, target.frame),
                    ScaffoldMesh.Geometry.Zero, checkpoint)
                val material = source.materials.getValue(id)
                val seed = Drawable(DrawableId(id), material.name, ScaffoldHead.breathId, BlendMode.Normal,
                    emptyList(), mesh, grid, opacity = material.opacity)
                val shell = if (target.shadow) source.shell?.shapes(raw.rigPositions, target.frame,
                    requireNotNull(DepthTurnMotion.shell("NECK", "SHADOW")),
                    LegacyFaceBinding.angleX, LegacyFaceBinding.angleY).orEmpty() else emptyList()
                fun form(degrees: Float) = MeshForm(NeckAlgorithm.deltas(raw.rigPositions, target.frame,
                    source.neck.neck ?: target.bounds, source.neck.pivotX, source.neck.pivotY, target.shadow, degrees, checkpoint),
                    opacity = Float.NaN)
                val shapes = shell + if (tilt) listOf(BlendShapeBinding(LegacyFaceBinding.angleZ,
                    floatArrayOf(-30f, 0f, 30f), 1, listOf(form(-30f), null, form(30f)))) else emptyList()
                val bindings = shapes.map { binding -> binding.copy(forms = binding.forms.map { form -> form?.let {
                    // Geometry.Zero's rest deltas are +0f. Preserve legacy neutralization's signed-zero arithmetic.
                    MeshForm(FloatArray(it.positionDeltas.size) { index -> it.positionDeltas[index] + 0f },
                        seed.drawOrder, seed.opacity, seed.multiplyColor, seed.screenColor)
                } }) }
                // The full native rig has ordinary head axes outside this body branch. Witness only axis membership,
                // not head poses; the detached node is generated here and never enters the transition or output.
                val witness = axisWitness()
                val lowered = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(neutral.copy(
                    drawables = listOf(seed.copy(blendShapes = bindings)), deformers = branch + witness))
                require(lowered.parameters == neutral.parameters && lowered.drawables.single().parentDeformerId == seed.parentDeformerId &&
                    lowered.deformers.filterNot { it.id == witness.id } == branch) {
                    "Neck v4 cannot insert a private lowering carrier or change parameter kinds"
                }
                return lowered.copy(deformers = branch)
            }
            val prior = phase(false)
            val next = phase(true)
            val transfer = object : BindingMerge.Source {
                override fun provider(instance: RigModifierInstance) = provider
                override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to next
                override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
                override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(),
                    mapOf(ParameterId("ParamMouthOpenY") to 1f), checkpoint)
                override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) = false
                override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = aliases
                override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = parts
                override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
                override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                    requireNotNull(parts[PartId(canonical)]).raw
                override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                           checkpoint: () -> Unit): List<VertexSource> = BodyVertexSources.match(to, original, target, checkpoint)
            }
            val local = instance.copy(targets = listOf(reference), outputAliases = instance.outputAliases.filterKeys {
                !it.startsWith("parameter-folder:")
            })
            val merged = BindingMerge.apply(current, local, transfer, checkpoint)
            require(merged.deformers.map(PuppetIr::deformerToIr) == current.deformers.map(PuppetIr::deformerToIr) &&
                merged.drawables.single().parentDeformerId == current.drawables.single().parentDeformerId) {
                "Neck v4 cannot change the live parent branch"
            }
            val projected = PuppetIr.meshToIr(merged.drawables.single())
            val masks = if (target.shadow && live.maskedBy.isEmpty() && !live.invertMask && source.neck.masks.isNotEmpty()) {
                require(source.neck.masks.all { input.mesh(it) != null }) { "Missing captured neck mask source" }
                source.neck.masks
            } else live.maskedBy
            val result = live.copy(geometry = projected.geometry, offsets = projected.offsets, shapes = projected.shapes, maskedBy = masks)
            ModifierObjectUpdate(live, result).takeIf { live != result }
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
    }

    private fun axisWitness() = Deformer.Warp(DeformerId("__NeckV4OrdinaryAxes"), "", null, null, 1, 1, true,
        KeyformGrid(listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ).map {
            KeyformAxis(it, floatArrayOf(0f))
        }, listOf(KeyformCell(intArrayOf(0, 0, 0), WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))))))

    companion object {
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.666163652e6e65636b.v4/tilt"
        const val SCHEMA = "neck-source/1"
        fun supportedFace(type: String, version: Int) = when (type) {
            "face/legacy" -> version in 1..2
            "face/fitted" -> version in 1..3
            else -> false
        }
    }
}
