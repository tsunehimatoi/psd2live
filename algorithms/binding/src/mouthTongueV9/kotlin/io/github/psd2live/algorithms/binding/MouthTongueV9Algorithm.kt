package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.withDerivedRenderRoot

/** Adds the unmasked tongue tip to the layered tongue motion. */
class MouthTongueV9Algorithm : ChangingModifierAlgorithmPlugin {
    companion object {
        const val VERSION = 9
        const val ENTRYPOINT = "builtin.6d6f7574682e746f6e677565.v9/tongue"
        fun tipOutput(owner: String) = "tongue-tip:$owner"
    }

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val binding = MouthTongueV7Algorithm().changes(input, instance.copy(version = MouthTongueV7Algorithm.VERSION),
            context, checkpoint)
        val layered = binding.apply(input, checkpoint)
        val sourceTips = RigIrBinary.decode(context.resources.getValue("tongue_tips").read()).meshes
        val measurements = context.values.getValue("tip_measurements").jsonObject
        val digest = measurements.getValue("measurement").jsonPrimitive.content
        val mouth = MouthSourceContext.decode(ModifierAlgorithmContext(measurements,
            mapOf(digest to context.resources.getValue(digest)))).rig()
        require(mouth.fit.layered != null && mouth.options.capture && mouth.options.tongue)
        val sourceFrames = context.values.getValue("tip_frames").jsonObject
        require(sourceFrames.keys == sourceTips.mapTo(hashSetOf()) { it.id })
        val order = context.values.getValue("tip_lower_order").jsonPrimitive.float + 1f
        val chin = context.values.getValue("tip_chin").jsonPrimitive.float
        require(order.isFinite() && chin.isFinite())
        val meshes = layered.meshes.associateBy { it.id }
        val frames = layered.deformers.associateBy { it.id }
        val providers = context.upstream.distinctBy { it.instanceId }.mapNotNull { provider ->
            provider.instanceId?.let { RigModifierInstance(it, provider.type, provider.version, provider.targets,
                config = provider.config, outputAliases = provider.outputAliases, plugin = provider.plugin) }
        }
        val copies = sourceTips.associate { source ->
            checkpoint()
            val ownerId = source.id
            val target = ModifierTarget("mesh", ownerId)
            require(target in instance.targets && source.offsets == null && source.shapes.isEmpty() && source.channels.isEmpty())
            val owner = meshes.getValue(ownerId)
            val frame = requireNotNull(source.parent)
            val acceptedParents = (providers + instance).filter { target in it.targets }.mapTo(hashSetOf()) {
                ScaffoldBinding.deformerId(it, ownerId, frame, shared = true)
            }
            val parent = requireNotNull(generateSequence(owner.parent) { frames.getValue(it).parent }
                .firstOrNull { it in acceptedParents }) { "Tongue tip must use the captured mouth frame" }
            val id = instance.outputId(tipOutput(ownerId))
            require(id !in meshes)
            val geometry = requireNotNull(source.geometry)
            require(source.page == owner.page && owner.geometry != null) { "Tongue tip must use the source tongue texture page" }
            val values = sourceFrames.getValue(ownerId).jsonArray.map { it.jsonPrimitive.float }
            require(values.size == 5 && values.all(Float::isFinite) && values[2] > values[0] && values[3] > values[1] && values[4] in 0f..1f)
            val frameBounds = Bounds(values[0], values[1], values[2], values[3])
            val positions = geometry.positions.toArray()
            val rigPositions = FloatArray(positions.size) { index ->
                if (index % 2 == 0) frameBounds.left + positions[index] * frameBounds.width
                else frameBounds.top + positions[index] * frameBounds.height
            }
            val tip = source.copy(id = id, parent = parent, maskedBy = emptyList(), invertMask = false, opacity = 0f,
                drawOrder = order, textureSource = ownerId, page = owner.page, tile = owner.tile)
            val model = PuppetIr.toPuppet(RigIR(input.canvas, layered.parameters, meshes = listOf(tip)))
            val drawable = model.drawables.single()
            val grid = mouth.grid(rigPositions, frameBounds, ParameterId("ParamMouthForm"), ParameterId("ParamMouthOpenY"),
                MouthRig.Follow.PROTRUDING_TONGUE, chin)
            val shapes = mouth.protrudingTongueShapes(rigPositions, frameBounds, values[4], chin = chin).map { binding ->
                binding.copy(forms = binding.forms.map { form -> form?.let {
                    MeshForm(it.positionDeltas, order, if (binding.parameterId == MouthRig.TONGUE_OUT) it.opacity else 0f,
                        drawable.multiplyColor, drawable.screenColor)
                } })
            }
            val lowered = BlendShapeLowering({ _, _ -> error("Tongue tip must use the captured frame") }, checkpoint)
                .lawful(model.copy(drawables = listOf(drawable.copy(geometryGrid = grid, blendShapes = shapes))))
            require(lowered.parameters == model.parameters && lowered.deformers.isEmpty())
            ownerId to PuppetIr.meshToIr(lowered.drawables.single())
        }
        require(copies.isNotEmpty() && copies.size == sourceTips.size && copies.values.map { it.id }.distinct().size == copies.size)
        fun List<ChildRef>.withTips(): List<ChildRef> = flatMap { child ->
            val tip = (child as? ChildRef.MeshRef)?.let { copies[it.id] }
            if (tip == null) listOf(child) else listOf(ChildRef.MeshRef(tip.id), child)
        }
        val parts = layered.parts.map { part -> part.copy(children = part.children.withTips()) }
        val root = layered.rootChildren.withTips()
        val addedMeshes = layered.meshes + copies.values
        val placed = (root + parts.flatMap { it.children }).filterIsInstance<ChildRef.MeshRef>().map { it.id }
        require(copies.values.all { tip -> placed.count { it == tip.id } == 1 }) { "Every tongue tip must have one part owner" }
        val candidate = layered.copy(meshes = addedMeshes, parts = parts, rootChildren = root)
        val render = PuppetIr.toIr(PuppetIr.toPuppet(candidate).withDerivedRenderRoot()).renderRoot
        val inputParts = input.parts.associateBy { it.id }
        val partsChanges = ModifierObjectChanges(
            added = parts.filter { it.id !in inputParts }, removed = binding.parts.removed,
            updated = parts.mapNotNull { part -> inputParts[part.id]?.takeIf { it != part }?.let { ModifierObjectUpdate(it, part) } },
        )
        fun <T> changed(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        return binding.copy(meshes = ModifierObjectChanges(added = binding.meshes.added + copies.values,
            updated = binding.meshes.updated, removed = binding.meshes.removed), parts = partsChanges,
            rootChildren = changed(input.rootChildren, root), renderRoot = changed(input.renderRoot, render))
    }
}

object MouthTongueV9Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (MouthTongueV9Algorithm.ENTRYPOINT to MouthTongueV9Algorithm.VERSION) to MouthTongueV9Algorithm(),
    ))
}
