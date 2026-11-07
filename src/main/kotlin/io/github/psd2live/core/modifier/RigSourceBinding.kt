package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.BindingMerge
import io.github.psd2live.core.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*

/** Host adapter: captured binding inputs, parameter policy and physical outputs around the independent merge. */
internal object RigSourceBinding {
    fun apply(rig: RigIR, instance: RigModifierInstance, source: RigModifierSource, checkpoint: () -> Unit): RigIR {
        val inputs = object : BindingMerge.Source {
            override fun provider(instance: RigModifierInstance) = source.provider(instance)
            override fun transition(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = source.transition(instance, target, checkpoint)
            override fun resolveParameter(instance: RigModifierInstance, before: io.github.psd2live.format.model.Parameter,
                                          declared: io.github.psd2live.format.model.Parameter) = source.resolveParameter(instance, before, declared)
            override fun static(model: PuppetModel) = source.static(model)
            override fun onlyMasksChange(before: PuppetModel, after: PuppetModel, target: ModifierTarget, branch: Set<DeformerId>) = source.onlyMasksChange(before, after, target, branch)
            override fun deformerIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = source.deformerIds(instance, target, checkpoint)
            override fun partIds(instance: RigModifierInstance, target: ModifierTarget, checkpoint: () -> Unit) = source.partIds(instance, target, checkpoint)
            override fun requiredParts(model: PuppetModel, target: ModifierTarget) = source.requiredParts(model, target)
            override fun partId(instance: RigModifierInstance, mesh: String, canonical: String, shared: Boolean) = RigSourceModifiers.partId(instance, mesh, canonical, shared)
            override fun vertexSources(from: PuppetModel, original: Drawable, to: PuppetModel, target: Drawable, checkpoint: () -> Unit) =
                RasterMeshJournal.prepare(canvasMesh(from, original), canvasMesh(to, target), checkpoint).sources
        }
        val current = BindingMerge.apply(PuppetIr.toPuppet(rig), instance, inputs, checkpoint)
        val chains = if (RigModifierChains.kinds(instance.type).isEmpty()) emptyList() else source.chains(instance, checkpoint)
        val spec = RigLegacyBindings.spec(instance.type)
        val bound = rig.withPuppet(orderNativeChainParameters(current, instance, chains))
        val posed = if (spec.isBase && spec.area == "mouth" && bound.parameters.any { it.id == StandardParameters.MOUTH_OPEN.raw })
            bound.copy(restPose = bound.restPose + (StandardParameters.MOUTH_OPEN.raw to 1f)) else bound
        val output = RigModifierPhysics.add(posed, instance, chains)
        return if (chains.isEmpty()) output else source.addUpstreamExpressionInputs(output, instance)
    }

    private fun orderNativeChainParameters(model: PuppetModel, instance: RigModifierInstance,
                                           chains: List<RigModifierChains.Owned>): PuppetModel {
        if (chains.size < 2 || instance.outputAliases.keys.none { it.startsWith("chain-parameter:") }) return model
        // A legacy recipe orders chain axes by source ordinal. Target traversal visits them in mesh order.
        val rank = chains.sortedBy { it.binding.native.index }.flatMap { it.aliases.values }
            .mapIndexed { index, parameter -> ParameterId(parameter) to index }.toMap()
        fun ordered(nodes: List<ParameterNode>): List<ParameterNode> {
            val owned = nodes.filterIsInstance<ParameterNode.Param>().filter { it.id in rank }
                .sortedBy { rank.getValue(it.id) }.iterator()
            return nodes.map { node -> when (node) {
                is ParameterNode.Param -> if (node.id in rank) owned.next() else node
                is ParameterNode.Group -> node.copy(children = ordered(node.children))
            } }
        }
        return model.copy(parameterTree = ordered(model.parameterTree))
    }

    private fun canvasMesh(model: PuppetModel, drawable: Drawable): DrawableMesh {
        val mesh = requireNotNull(drawable.mesh)
        val uv = RasterMeshJournal.TextureCoordinates(model, drawable).toCanvas(mesh.uvs)
        return DrawableMesh(uv, uv, mesh.indices)
    }
}
