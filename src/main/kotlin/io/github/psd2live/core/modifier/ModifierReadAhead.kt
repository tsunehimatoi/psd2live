package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.RigIR
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Compute an independent successor's changes without publishing or reordering the stack. */
internal class ModifierReadAhead : AutoCloseable {
    private data class Pending(val index: Int, val task: Future<ModifierInputCache.ReadEntry>, val cancelled: AtomicBoolean) {
        fun cancel() {
            cancelled.set(true)
            task.cancel(true)
        }
    }
    private var pending: Pending? = null

    fun canOverlap(current: RigModifierDefinition, next: RigModifierDefinition): Boolean {
        if (ForkJoinPool.getCommonPoolParallelism() < 2) return false
        val (reads, writes) = domains(current) ?: return false
        val (nextReads, nextWrites) = domains(next) ?: return false
        return writes.none { it in nextReads || it in nextWrites } && nextWrites.none { it in reads }
    }

    private fun domains(definition: RigModifierDefinition): Pair<Set<String>, Set<String>>? {
        if (definition.pluginReference != null || definition.plugin !is ReadingModifierAlgorithmPlugin) return null
        val artifact = BundledAlgorithmArtifacts.find(definition.type, definition.version) ?: return null
        val algorithm = artifact.manifest.algorithms.singleOrNull { definition.type in it.aliases && it.version == definition.version }
            ?: return null
        val writes = definition.changeWrites ?: return null
        if (writes.isEmpty() || (algorithm.reads + algorithm.writes + writes).any { it !in domains }) return null
        return algorithm.reads to (algorithm.writes + writes)
    }

    fun start(index: Int, instance: RigModifierInstance, plugin: ReadingModifierAlgorithmPlugin,
              input: RigIR, context: ModifierAlgorithmContext) {
        close()
        val owner = Thread.currentThread()
        val cancelled = AtomicBoolean()
        val task = ForkJoinPool.commonPool().submit<ModifierInputCache.ReadEntry> {
            if (cancelled.get()) throw CancellationException("Modifier evaluation cancelled")
            val view = ModifierEvaluationInput(input, context)
            val changes = plugin.changes(view, instance) {
                if (cancelled.get() || owner.isInterrupted || Thread.currentThread().isInterrupted)
                    throw CancellationException("Modifier evaluation cancelled")
            }
            ModifierInputCache.ReadEntry(instance, plugin, context, view.reads(), changes)
        }
        pending = Pending(index, task, cancelled)
    }

    fun take(index: Int): ModifierInputCache.ReadEntry? {
        val work = pending ?: return null
        if (work.index != index) return null
        return try { work.task.get() }
        catch (failure: ExecutionException) {
            val cause = failure.cause
            if (cause is Error) throw cause
            null // Report failures from the authoritative input at the serial position.
        }
        finally {
            if (!work.task.isDone) work.cancel()
            pending = null
        }
    }

    override fun close() {
        pending?.cancel()
        pending = null
    }

    private companion object {
        val domains = setOf("mesh", "deformer", "part", "parameter", "physics", "motion", "glue",
            "textures", "hierarchy", "relations", "canvas", "restPose", "authoring")
    }
}
