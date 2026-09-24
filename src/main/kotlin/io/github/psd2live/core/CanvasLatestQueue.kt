package io.github.psd2live.core

/** Bounded by view count: replace stale work within a view without starving other views. */
internal class CanvasLatestQueue<T> {
    private val pending = linkedMapOf<String, T>()

    @Synchronized fun put(viewId: String, value: T) { pending[viewId] = value }
    @Synchronized fun poll(): T? {
        val key = pending.keys.firstOrNull() ?: return null
        return pending.remove(key)
    }
    @Synchronized fun remove(viewId: String) { pending.remove(viewId) }
    @Synchronized fun clear() { pending.clear() }
    @Synchronized fun isEmpty(): Boolean = pending.isEmpty()
}
