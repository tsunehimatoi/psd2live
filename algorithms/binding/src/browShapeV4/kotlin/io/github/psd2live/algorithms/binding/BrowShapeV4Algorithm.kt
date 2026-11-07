package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.MeshForm

object BrowShapeV4Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (BrowShapeV4Algorithm.ENTRYPOINT to BrowShapeV4Algorithm.VERSION) to BrowShapeV4Algorithm(),
    ))
}

/** Applies the fitted brow shape in source space and lowers it with the witnessed head turn. */
class BrowShapeV4Algorithm : ReadingModifierAlgorithmPlugin {
    private val shape = BrowShapeV3Algorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val turn = RigIrBinary.decode(context.resources.getValue("brow_turn").read())
        val nativeIds = context.values.getValue("native_ids").jsonObject
        val turnName = context.values.getValue("turn_name").jsonPrimitive.content
        val fittedContext = ModifierAlgorithmContext(
            JsonObject(context.values - setOf("native_ids", "turn_name")), context.resources - "brow_turn", input.upstream)
        val source = BrowShapeInputs.decode(fittedContext, checkpoint)
        val measured = turn.meshes.associateBy { it.id }
        require(source.targets.keys == measured.keys && measured.keys == nativeIds.keys)
        val parameters = input.parameters
        val brows = source.targets.map { (id, target) ->
            val live = requireNotNull(input.mesh(id))
            val raw = measured.getValue(id)
            live.copy(parent = target.parent, geometry = raw.geometry, offsets = raw.offsets, shapes = emptyList())
        }
        val raw = RigIR(input.canvas, parameters, parameterTree = input.parameterTree, meshes = brows)
        val shapeChanges = shape.changes(raw, instance.copy(version = BrowShapeV3Algorithm.VERSION), fittedContext, checkpoint)
        val afterParameters = parameters + shapeChanges.parameters.added
        val owners = input.upstream.distinctBy { it.instanceId }.mapNotNull { provider ->
            provider.instanceId?.let { id ->
                RigModifierInstance(id, provider.type, provider.version, provider.targets,
                    config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin)
            }
        }
        val scaffold = owners.single { it.type == "rig.scaffold" }
        val added = mutableListOf<Deformer>()
        val updated = mutableListOf<ModifierObjectUpdate<Deformer>>()
        val meshes = shapeChanges.meshes.updated.mapNotNull { (before, after) ->
            checkpoint()
            val live = requireNotNull(input.mesh(before.id))
            val ancestry = input.parentsOfMesh(live.id)
            val native = measured.getValue(live.id)
            val nativeDeformers = turn.deformers.associateBy { it.id }
            val branch = generateSequence(native.parent) { nativeDeformers.getValue(it).parent }.map(nativeDeformers::getValue).toList()
            val canonical = nativeIds.getValue(live.id).jsonPrimitive.content
            fun lower(mesh: Mesh, controls: List<Parameter>): PuppetModel {
                val withTurn = mesh.copy(id = canonical, parent = native.parent, shapes = mesh.shapes + native.shapes)
                val model = PuppetIr.toPuppet(RigIR(input.canvas, controls, deformers = branch, meshes = listOf(withTurn)))
                val drawable = model.drawables.single()
                val neutral = drawable.copy(blendShapes = drawable.blendShapes.map { binding ->
                    binding.copy(forms = binding.forms.map { form ->
                        form?.let { MeshForm(it.positionDeltas, live.drawOrder, live.opacity, drawable.multiplyColor, drawable.screenColor) }
                    })
                })
                val lowered = BlendShapeLowering({ head, name -> require(head); turnName.replace("{meshName}", name) },
                    checkpoint).lawful(model.copy(drawables = listOf(neutral)))
                require(lowered.parameters == model.parameters)
                return lowered.copy(drawables = listOf(lowered.drawables.single().copy(id = org.umamo.runtime.model.DrawableId(live.id))))
            }
            val prior = lower(before, parameters)
            val next = lower(after, afterParameters)
            val target = ModifierTarget("mesh", live.id)
            val existing = ancestry.associateBy { it.id }
            val aliases = linkedMapOf<DeformerId, DeformerId>()
            for (carrier in prior.deformers) {
                val id = owners.asSequence().filter { target in it.targets }
                    .map { ScaffoldBinding.deformerId(it, live.id, carrier.id.raw, shared = true) }.distinct()
                    .singleOrNull { it in existing }
                aliases[carrier.id] = DeformerId(requireNotNull(id) { "Cannot resolve preceding brow frame: ${carrier.id.raw}" })
            }
            for (carrier in next.deformers) {
                aliases.putIfAbsent(carrier.id, DeformerId(ScaffoldBinding.deformerId(instance, live.id, carrier.id.raw, shared = true)))
            }
            require(prior.drawables.single().parentDeformerId?.let(aliases::get)?.raw == live.parent)
            val parts = prior.deformers.mapNotNull { carrier ->
                val livePart = existing[aliases.getValue(carrier.id).raw]?.part ?: return@mapNotNull null
                carrier.partId?.let { it to PartId(livePart) }
            }.toMap()
            val liveParts = parts.values.distinct().map { requireNotNull(input.part(it.raw)) }
            val current = PuppetIr.toPuppet(RigIR(input.canvas, afterParameters, parts = liveParts, deformers = ancestry, meshes = listOf(live),
                textures = input.textures, authoring = input.authoring))
            val transfer = object : BindingMerge.Source {
                override fun provider(instance: RigModifierInstance) = scaffold
                override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = prior to next
                override fun resolveParameter(instance: RigModifierInstance, before: Parameter, declared: Parameter): Parameter? = null
                override fun static(model: PuppetModel) = BindingProjection.retain(model, emptySet(), mapOf(ParameterId("ParamMouthOpenY") to 1f))
                override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) = false
                override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = aliases
                override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = parts
                override fun requiredParts(model: PuppetModel, target: ModifierTarget) = emptySet<PartId>()
                override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) =
                    requireNotNull(parts[PartId(canonical)]).raw
                override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable,
                                           checkpoint: () -> Unit): List<VertexSource> =
                    BodyVertexSources.match(to, original, target, checkpoint)
            }
            val geometryOnly = instance.copy(targets = listOf(target), outputAliases = instance.outputAliases.filterKeys {
                !it.startsWith("parameter-folder:")
            })
            val merged = BindingMerge.apply(current, geometryOnly, transfer, checkpoint)
            require(merged.deformers.map { it.id.raw }.containsAll(existing.keys))
            for (deformer in merged.deformers.map(PuppetIr::deformerToIr)) {
                val original = existing[deformer.id]
                if (original == null) added += deformer
                else if (original != deformer) updated += ModifierObjectUpdate(original, deformer)
            }
            val result = PuppetIr.meshToIr(merged.drawables.single())
            ModifierObjectUpdate(live, result).takeIf { live != result }
        }
        return shapeChanges.copy(meshes = ModifierObjectChanges(updated = meshes),
            deformers = ModifierObjectChanges(added = added, updated = updated))
    }

    companion object {
        const val VERSION = 4
        const val ENTRYPOINT = "builtin.62726f772e7368617065.v4/shape"
    }
}
