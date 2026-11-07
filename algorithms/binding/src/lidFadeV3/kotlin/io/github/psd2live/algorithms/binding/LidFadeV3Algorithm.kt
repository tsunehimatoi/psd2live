package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Channel
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.FormChannel

/** Fades the lid while preserving each existing blend form's opacity relative to its rest value. */
class LidFadeV3Algorithm : ReadingModifierAlgorithmPlugin {
    override fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val changes = LidFadeAlgorithm().changes(input, instance.copy(version = LidFadeAlgorithm.VERSION), checkpoint)
        val preceding = input.providers(ENTRYPOINT).filter { it.config["scaffold"] == instance.config["scaffold"] }
            .flatMapTo(hashSetOf()) { it.targets }
        val updates = changes.meshes.updated.filterNot { ModifierTarget("mesh", it.before.id) in preceding }.map { update ->
            checkpoint()
            val (before, after) = update
            if (after.shapes.isEmpty()) return@map update
            val axes = listOf(before, after).flatMap { mesh -> mesh.channels[Channel.OPACITY]?.axes.orEmpty() }
            val parameters = axes.map { it.parameter }.distinct().mapNotNull(input::parameter)
            val defaults = parameters.associate { it.id to it.default }
            val samples = listOf(before, after).mapIndexed { index, mesh ->
                mesh.copy(id = "sample-$index", parent = null, maskedBy = emptyList(),
                    geometry = null, offsets = null, channels = mesh.channels.filterKeys { it == Channel.OPACITY }, shapes = emptyList())
            }
            val model = PuppetIr.toPuppet(RigIR(Canvas(1f, 1f), parameters, meshes = samples))
            val (previous, current) = model.drawables.map { drawable ->
                drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, paramValue = { defaults[it.raw] ?: 0f })
            }
            if (current == previous) return@map update
            update.copy(after = after.copy(shapes = after.shapes.map { binding ->
                binding.copy(shapes = binding.shapes.map { shape ->
                    shape?.copy(opacity = current + if (shape.opacity.isFinite()) shape.opacity - previous else 0f)
                })
            }))
        }
        return ModifierModelChanges(meshes = ModifierObjectChanges(updated = updates))
    }

    companion object {
        const val VERSION = 3
        const val ENTRYPOINT = "builtin.6579652e6c69642d66616465.v3/fade"
    }
}

object LidFadeV3Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(mapOf(
        (LidFadeV3Algorithm.ENTRYPOINT to LidFadeV3Algorithm.VERSION) to LidFadeV3Algorithm(),
    ))
}
