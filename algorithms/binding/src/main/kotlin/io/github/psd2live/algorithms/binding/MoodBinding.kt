package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*

/** Evaluates captured color inputs before Cubism keyform lowering, without application services. */
class MoodBinding(
    private val carrierName: (head: Boolean, meshName: String) -> String,
    private val checkpoint: () -> Unit = {},
) {
    data class Influence(val warmth: Float, val darkness: Float, val highlight: Boolean)
    data class Result(val source: PuppetModel, val model: PuppetModel)

    /**
     * [source] is the binding input before blend shapes are lowered. [definitions] contains the
     * active color parameters; [order] and [dynamic] preserve the captured parameter catalog order.
     * Only meshes in [influences] have generated mood tracks replaced. Authored residuals are merged
     * by the host after evaluation, so this method must never receive an authored preview model.
     */
    fun evaluate(
        source: PuppetModel,
        definitions: List<Parameter>,
        order: Map<ParameterId, Int>,
        dynamic: Set<ParameterId>,
        influences: Map<DrawableId, Influence>,
        cheek: Boolean,
        gloom: Boolean,
    ): Result {
        checkpoint()
        val additions = definitions.filter { definition -> source.parameters.none { it.id == definition.id } }
        val parameters = (source.parameters + additions).sortedBy { order[it.id] ?: if (it.id in dynamic) Int.MAX_VALUE else -1 }
        val tree = source.parameterTree.map { node ->
            if (node is ParameterNode.Group && node.id.raw == "ParamGroupFace") node.copy(children =
                (node.children + additions.map { ParameterNode.Param(it.id) }).sortedBy { child ->
                    if (child is ParameterNode.Param) order[child.id] ?: -1 else -1
                }) else node
        }
        val drawables = source.drawables.map { mesh ->
            checkpoint()
            val influence = influences[mesh.id] ?: return@map mesh
            val tracks = ChannelGrids(mesh.channelGrids.gridsByChannel.filterValues { grid ->
                grid.axes.none { it.parameterId in MoodChannels.parameters }
            })
            applyChannels(mesh.copy(channelGrids = tracks), influence, cheek, gloom)
        }
        val next = source.copy(parameters = parameters, parameterTree = tree, drawables = drawables)
        val lowered = BlendShapeLowering(carrierName, checkpoint).lawful(next)
        checkpoint()
        return Result(next, lowered)
    }

    companion object {
        /** Applies generated mood to a neutral mesh while respecting channels owned by other bindings. */
        fun applyChannels(mesh: Drawable, influence: Influence, cheek: Boolean, gloom: Boolean): Drawable =
            mesh.copy(channelGrids = MoodChannels.apply(mesh.channelGrids, influence.warmth, influence.darkness,
                influence.highlight, cheek, gloom))
    }
}
