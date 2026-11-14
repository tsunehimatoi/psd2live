package io.github.psd2live.util

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** A thread-safe map from object identity to a value that does not keep its keys alive. */
internal class IdentityWeakCache<K : Any, V : Any> {
	private class Key(referent: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(referent, queue) {
		private val hash = System.identityHashCode(referent)
		override fun hashCode(): Int = hash
		override fun equals(other: Any?): Boolean {
			if (other === this) return true
			if (other !is Key || other.hash != hash) return false
			val referent = get()
			return referent != null && referent === other.get()
		}
	}

	private val queue = ReferenceQueue<Any>()
	private val map = HashMap<Key, V>()

	fun put(key: K, value: V) {
		synchronized(this) { purge(); map[Key(key, queue)] = value }
	}

	fun getOrPut(key: K, compute: () -> V): V {
		synchronized(this) { purge(); map[Key(key, null)]?.let { return it } }
		val value = compute()
		synchronized(this) { map.putIfAbsent(Key(key, queue), value)?.let { return it } }
		return value
	}

	private fun purge() {
		while (true) map.remove(queue.poll() as? Key ?: return)
	}
}
