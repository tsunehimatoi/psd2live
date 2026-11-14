package io.github.psd2live.core

import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.core.modifier.ModifierPluginWorkers

/** Recomputable build state has a releasable owner; published rigs and evaluation snapshots do not. */
internal class ModifierBuildCacheOwner(private val store: ModifierPluginStore = ModifierPluginStore.shared,
                                      private val retirementQueue: io.github.psd2live.core.modifier.WorkerRetirementQueue = ModifierPluginWorkers.retirements) : AutoCloseable {
    // The cleanup state does not reference its owner. Abandoned import/candidate models cannot leave a
    // process-global Session alive forever; normal workspace closure remains deterministic.
    private class Resources : Runnable {
        var workers: ModifierPluginWorkers.Session? = null
        @Synchronized override fun run() { workers?.close(); workers = null }
    }
    private val resources = Resources()
    private val cleanup = cleaner.register(this, resources)
    private val pipelines = ArrayList<java.lang.ref.WeakReference<PSD2LivePipeline>>()
    private var meshes: PreviewMeshCache? = null
    @Volatile internal var published = false
    @Volatile var isClosed: Boolean = false
        private set

    @Synchronized fun meshCache(): PreviewMeshCache {
        check(!isClosed) { "Modifier build cache owner is closed" }
        return meshes ?: PreviewMeshCache().also { meshes = it }
    }

    @Synchronized fun workerSession(): ModifierPluginWorkers.Session {
        check(!isClosed) { "Modifier build cache owner is closed" }
        return resources.workers ?: ModifierPluginWorkers.openSession(store, retirementQueue).also { resources.workers = it }
    }

    internal val retainsMeshCache: Boolean @Synchronized get() = meshes != null

    @Synchronized fun attach(pipeline: PSD2LivePipeline) {
        check(!isClosed)
        pipelines.removeAll { it.get() == null }
        if (pipelines.none { it.get() === pipeline }) pipelines.add(java.lang.ref.WeakReference(pipeline))
    }

    override fun close() {
        val attached = synchronized(this) {
            if (isClosed) return
            isClosed = true
            meshes = null
            pipelines.mapNotNull { it.get() }.also { pipelines.clear() }
        }
        attached.forEach { it.releaseModifierInputs(this) }
        cleanup.clean()
    }

    private companion object { val cleaner = java.lang.ref.Cleaner.create() }
}
