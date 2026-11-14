package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.ModifierEvaluationInput
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.ModifierObjectChanges
import io.github.psd2live.core.modifier.ReadingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Channel
import io.github.psd2live.format.model.ChannelValue
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Applies mood channels to the live binding while keeping its already-lowered geometry. */
class GloomBindingAlgorithm : ReadingModifierAlgorithmPlugin {
    private val channels = GloomAlgorithm()

    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val context = input.context
        val source = GloomInputs.decode(context)
        val eyeHighlights = context.values["eye_highlights"]?.jsonArray.orEmpty().mapTo(HashSet()) { it.jsonPrimitive.content }
        val changes = channels.changes(input, instance.copy(version = 1), checkpoint)
        val updates = changes.meshes.updated.mapNotNull { update ->
            val layer = source.layers.getValue(update.before.id)
            val neutral = source.neutral.getValue(update.before.id)
            val eyeOwnsOpacity = update.before.id in eyeHighlights &&
                update.before.channels[Channel.OPACITY]?.axes.orEmpty().any { it.parameter in eyeOpen }
            val owned = buildSet {
                if (layer.skin && Channel.MULTIPLY_COLOR !in neutral.channels) add(Channel.MULTIPLY_COLOR)
                if (layer.highlight && layer.precedingGloomStrength == null && Channel.OPACITY !in neutral.channels && !eyeOwnsOpacity)
                    add(Channel.OPACITY)
            }
            val channels = update.after.channels.filterKeys { it in owned }.mapValues { (channel, grid) ->
                // ChannelResidual walks the first axis fastest, while generated mood grids walk it last.
                if (channel == Channel.MULTIPLY_COLOR && grid.axes.map { it.parameter } == moodAxes &&
                    hasGeneratedMultiply(update.before, neutral, layer))
                    grid.copy(cells = grid.cells.sortedBy { cell -> cell.coordinate[0].toLong() * grid.axes[1].keys.size + cell.coordinate[1] })
                else grid
            }
            val next = update.before.channels - owned + channels
            requireCompatibleLowering(update.before, next)
            if (next == update.before.channels) null else update.copy(after = update.before.copy(channels = next))
        }
        return changes.copy(meshes = ModifierObjectChanges(updated = updates))
    }

    private fun hasGeneratedMultiply(mesh: Mesh, neutral: Mesh, layer: GloomInputs.Layer): Boolean {
        if (mesh.multiply != neutral.multiply) return false
        val baseline = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(neutral))).drawables.single()
        val cheek = layer.precedingCheekStrength
        val gloom = layer.precedingGloomStrength
        val influence = MoodBinding.Influence(layer.cheekWarmth * (cheek ?: 0f) / 100f, (gloom ?: 0f) / 100f, false)
        val generated = MoodBinding.applyChannels(baseline, influence, cheek != null, gloom != null)
        return PuppetIr.meshToIr(generated).channels[Channel.MULTIPLY_COLOR] == mesh.channels[Channel.MULTIPLY_COLOR]
    }

    private fun requireCompatibleLowering(mesh: Mesh, channels: Map<Channel, KeyGrid<ChannelValue>>) {
        val positions = mesh.geometry?.positions ?: return
        val geometryAxes = mesh.offsets?.axes.orEmpty()
        if (geometryAxes.isEmpty()) return
        // The live offsets may contain folded upstream shapes. When the new channel axes
        // outgrow the lowering budget, their original forms are needed to allocate carriers.
        val before = (geometryAxes + mesh.channels.values.flatMap { it.axes }).distinctBy { it.parameter }.associateBy { it.parameter }
        val after = (geometryAxes + channels.values.flatMap { it.axes }).distinctBy { it.parameter }.associateBy { it.parameter }
        if (before == after) return
        val limit = minOf(MAX_MESH_CELLS, MAX_MESH_VALUES / positions.size.coerceAtLeast(1))
        val cells = after.values.fold(1L) { count, axis -> (count * axis.keys.size).coerceAtMost(MAX_MESH_CELLS + 1) }
        require(cells <= limit) {
            "Gloom v2 would exceed the keyform budget for ${mesh.id}; use Gloom v1 for this binding"
        }
    }

    companion object {
        const val TYPE = "expression.gloom"
        const val VERSION = 2
        const val ENTRYPOINT = "builtin.65787072657373696f6e2e676c6f6f6d.v2/gloom"
        private const val MAX_MESH_CELLS = 256L
        private const val MAX_MESH_VALUES = 120_000L
        private val eyeOpen = setOf("ParamEyeLOpen", "ParamEyeROpen")
        private val moodAxes = listOf("ParamCheek", GloomAlgorithm.PARAMETER)
        val WRITES = setOf("mesh", "parameter", "hierarchy")
    }
}
