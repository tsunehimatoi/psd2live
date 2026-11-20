package io.github.psd2live.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-pipeline, bounded cache of raster-local geometry; never caches parent coordinates.
 *
 * Meshes are keyed by content, and each key is generated once: a caller asking for a mesh another thread is
 * generating waits for that one instead of generating it again, while different keys generate in parallel
 * ([prefetch]). The generator is a pure function of the key, so the order meshes are made in never shows.
 */
class PreviewMeshCache(private val capacity: Int = 1024) {
    init { require(capacity > 0) }

    /** The rig builder's stage outputs for this pipeline ([RigStageCache]). */
    internal val stages = RigStageCache()

    private data class Key(
        val width: Int, val height: Int, val digest: String,
        val threshold: Int, val settings: MeshSettings, val unitScale: Float, val detail: Float, val coverAllAlpha: Boolean,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<Key, AdaptiveMeshGenerator.Result?>(16, 0.75f, true)
    private val pending = HashMap<Key, CompletableFuture<AdaptiveMeshGenerator.Result?>>()

    internal fun generate(
        width: Int, height: Int, rgba: ByteArray, alphaThreshold: Int, settings: MeshSettings,
        unitScale: Float = 1f, detail: Float = 1f, coverAllAlpha: Boolean = false,
    ): AdaptiveMeshGenerator.Result? {
        val key = key(width, height, rgba, alphaThreshold, settings, unitScale, detail, coverAllAlpha)
        while (true) {
            var owned: CompletableFuture<AdaptiveMeshGenerator.Result?>? = null
            val waiting = synchronized(lock) {
                if (entries.containsKey(key)) return entries[key]?.detached()
                pending[key] ?: run { owned = CompletableFuture(); pending[key] = owned!!; null }
            }
            if (waiting != null) {
                try {
                    return waiting.get()?.detached()
                } catch (_: ExecutionException) {
                    // The generating caller failed or was cancelled; this one tries for itself.
                    continue
                }
            }
            val future = owned!!
            try {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val result = RigBuildProfile.stage("mesh cache: adaptive mesh (miss)") {
                    AdaptiveMeshGenerator.generate(width, height, rgba, alphaThreshold, settings, unitScale, detail, coverAllAlpha)
                }
                synchronized(lock) {
                    entries[key] = result
                    while (entries.size > capacity) entries.remove(entries.keys.first())
                    pending.remove(key)
                }
                future.complete(result)
                return result?.detached()
            } catch (failure: Throwable) {
                synchronized(lock) { pending.remove(key) }
                future.completeExceptionally(failure)
                throw failure
            }
        }
    }

    /**
     * Generates the meshes of [inputs] not cached yet, in parallel, so the callers that read them one by one
     * find them cached. Returns once all are made; a failure is left for that caller to meet. Interrupting the
     * calling thread stops the meshes not started yet and rethrows.
     */
    internal fun prefetch(inputs: List<Pair<MeshResolution.Input, MeshSettings>>, alphaThreshold: Int) {
        val missing = LinkedHashMap<Key, Pair<MeshResolution.Input, MeshSettings>>()
        for ((input, settings) in inputs) {
            val key = key(input.width, input.height, input.rgba, alphaThreshold, settings, input.unitScale, input.detail, input.coverAllAlpha)
            if (synchronized(lock) { entries.containsKey(key) || key in pending }) continue
            missing.putIfAbsent(key, input to settings)
        }
        if (missing.size < 2) return
        val cancelled = AtomicBoolean()
        val tasks = missing.values.map { (input, settings) ->
            CompletableFuture.runAsync({
                if (!cancelled.get()) runCatching {
                    generate(input.width, input.height, input.rgba, alphaThreshold, settings, input.unitScale, input.detail, input.coverAllAlpha)
                }
            }, workers)
        }
        try {
            CompletableFuture.allOf(*tasks.toTypedArray()).get()
        } catch (interrupted: InterruptedException) {
            cancelled.set(true)
            throw interrupted
        }
    }

    private companion object {
        /** Mesh generation threads shared by every pipeline; idle ones expire. */
        val workers = java.util.concurrent.ThreadPoolExecutor(WORKERS, WORKERS, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue()) { task -> Thread(task, "psd2live-mesh").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
        val WORKERS get() = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
    }

    private fun key(
        width: Int, height: Int, rgba: ByteArray, alphaThreshold: Int, settings: MeshSettings, unitScale: Float, detail: Float, coverAllAlpha: Boolean,
    ): Key {
        // Content addressed; rasters are never edited in place (every producer fills a new array), so each
        // array is hashed once ([RasterDigest]).
        val digest = RigBuildProfile.stage("mesh cache: raster digest") {
            if (RigStageCache.enabled) RasterDigest.of(rgba) else RasterDigest.compute(rgba)
        }
        return Key(width, height, digest, alphaThreshold, settings, unitScale, detail, coverAllAlpha)
    }

    private fun AdaptiveMeshGenerator.Result.detached() = copy(
        positions = positions.copyOf(), indices = indices.copyOf(),
        boundaryLoops = boundaryLoops.map { it.copyOf() },
        middleLoops = middleLoops.map { it.copyOf() },
        innerLoops = innerLoops.map { it.copyOf() },
        spinePaths = spinePaths.map { it.copyOf() },
    )
}
