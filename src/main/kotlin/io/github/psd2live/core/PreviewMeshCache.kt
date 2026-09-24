package io.github.psd2live.core

import java.security.MessageDigest

/** Per-pipeline, bounded cache of raster-local geometry; never caches parent coordinates. */
class PreviewMeshCache(private val capacity: Int = 128) {
    init { require(capacity > 0) }

    private data class Key(
        val width: Int, val height: Int, val digest: String,
        val threshold: Int, val settings: MeshSettings,
    )

    private val entries = LinkedHashMap<Key, AdaptiveMeshGenerator.Result?>(16, 0.75f, true)

    @Synchronized
    internal fun generate(
        width: Int, height: Int, rgba: ByteArray, alphaThreshold: Int, settings: MeshSettings,
    ): AdaptiveMeshGenerator.Result? {
        // Content addressing also invalidates in-place pixel edits, not just replaced rasters.
        val digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba))
        val key = Key(width, height, digest, alphaThreshold, settings)
        if (entries.containsKey(key)) return entries[key]?.detached()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val result = AdaptiveMeshGenerator.generate(width, height, rgba, alphaThreshold, settings)
        entries[key] = result
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return result?.detached()
    }

    private fun AdaptiveMeshGenerator.Result.detached() = copy(
        positions = positions.copyOf(), indices = indices.copyOf(),
        boundaryLoops = boundaryLoops.map { it.copyOf() },
        middleLoops = middleLoops.map { it.copyOf() },
        innerLoops = innerLoops.map { it.copyOf() },
        spinePaths = spinePaths.map { it.copyOf() },
    )
}
