package io.github.psd2live.core.legacy

import kotlinx.serialization.json.JsonObject
import org.umamo.runtime.model.PuppetModel
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import io.github.psd2live.core.RigBuildProfile

/**
 * Intermediate models of the journal replay of a document without a checkpoint ([io.github.psd2live.core.RigEditOverlay.replayAuthored]),
 * so a document that appends to, undoes or branches from a recently replayed journal replays only the entries after
 * the longest replayed prefix. A journal with a checkpoint replays at most the entries after it, without these: only
 * journals older builds wrote, and a new document's before its first edit, come here.
 *
 * A checkpoint is the model after the legacy static edits and the first `i` journal entries. It is found by
 * content, never by position: the base model must be the same instance, the legacy part ([Legacy]) equal, and
 * every one of the first `i` entries equal to the entry the checkpoint replayed (`===`, else structural
 * equality). A command that rewrites an earlier entry - drag coalescing, a generated override capture, mesh
 * normalization - therefore misses every checkpoint at or after that entry. Replay is a pure function of the
 * base model and the entries (the interface language is part of [Legacy]); models are immutable and shared.
 *
 * Each chain keeps the states at index 0, every [INTERVAL]th entry and the last [RECENT] entries, as soft
 * references; at most [CHAINS] chains (most recently used first) are kept, so undo and redo across a branch
 * both find a near checkpoint.
 */
object ReplayCheckpoints {
	const val INTERVAL = 16
	const val RECENT = 3
	const val CHAINS = 4

	/** Off: every replay starts from the base model (tests compare against it; `-Dpsd2live.replayCheckpoints=false`). */
	@Volatile var enabled: Boolean = System.getProperty("psd2live.replayCheckpoints") != "false"

	/** The overlay part replayed before the journal, and what decides how the journal's structure edits split. */
	data class Legacy(val parts: List<Any?>)

	/** How many journal entries the last replay on this thread ran, and from which index (tests read it). */
	data class Replayed(val from: Int, val entries: Int)
	private val last = ThreadLocal<Replayed>()
	fun lastReplayed(): Replayed? = last.get()

	private class Chain(
		val base: WeakReference<PuppetModel>,
		val legacy: Legacy,
		val entries: List<JsonObject>,
		/** Sorted by index; a cleared reference is skipped. A state is whatever the caller's [replay] steps carry. */
		val states: Map<Int, SoftReference<Any>>,
	)

	private val chains = ArrayList<Chain>()

	fun clear() = synchronized(chains) { chains.clear() }

	/**
	 * The state after [legacy] and every entry of [journal], replayed from the nearest checkpoint: [start] runs
	 * the legacy edits on [base], [step] replays one entry. The state is the model with whatever a step reports
	 * (the replay's notes), so a checkpoint hit carries what the entries before it reported; one caller, one type.
	 */
	fun <S : Any> replay(
		base: PuppetModel,
		legacy: Legacy,
		journal: List<JsonObject>,
		start: () -> S,
		step: (S, JsonObject) -> S,
	): S {
		if (!enabled) {
			var model = start()
			for (entry in journal) model = step(model, entry)
			last.set(Replayed(0, journal.size))
			return model
		}
		val candidates = synchronized(chains) { chains.filter { it.base.get() === base && it.legacy == legacy } }
		var found: Chain? = null
		var foundShared = 0
		var from = -1
		var model: S? = null
		for (chain in candidates) {
			val limit = minOf(chain.entries.size, journal.size)
			val best = chain.states.keys.filter { it <= limit && it > from }.sortedDescending()
			if (best.isEmpty()) continue
			// Only the prefix up to the best usable checkpoint needs comparing.
			val shared = sharedPrefix(chain.entries, journal, best.first())
			for (index in best) {
				if (index > shared) continue
				@Suppress("UNCHECKED_CAST")
				val state = chain.states.getValue(index).get() as S? ?: continue
				if (index > from) { from = index; model = state; found = chain; foundShared = sharedPrefix(chain.entries, journal, limit) }
				break
			}
		}
		val recorded = HashMap<Int, Any>()
		var current = model ?: start().also { recorded[0] = it; from = 0 }
		val replayStart = System.nanoTime()
		for (i in from until journal.size) {
			current = step(current, journal[i])
			val index = i + 1
			if (index % INTERVAL == 0 || index > journal.size - RECENT) recorded[index] = current
		}
		if (RigBuildProfile.recording) {
			RigBuildProfile.add("replay: journal", System.nanoTime() - replayStart)
			repeat(journal.size - from) { RigBuildProfile.count("replay: entries replayed") }
		}
		last.set(Replayed(from, journal.size - from))
		remember(base, legacy, journal, found, foundShared, recorded)
		return current
	}

	/** How many leading entries [a] and [b] share, up to [limit]. */
	private fun sharedPrefix(a: List<JsonObject>, b: List<JsonObject>, limit: Int): Int {
		val end = minOf(limit, a.size, b.size)
		var i = 0
		while (i < end && (a[i] === b[i] || a[i] == b[i])) i++
		return i
	}

	private fun keep(index: Int, size: Int) = index == 0 || index % INTERVAL == 0 || index > size - RECENT

	private fun remember(base: PuppetModel, legacy: Legacy, journal: List<JsonObject>, found: Chain?, shared: Int,
						 recorded: Map<Int, Any>) {
		synchronized(chains) {
			val fresh = recorded.mapValues { SoftReference<Any>(it.value) }
			val next = if (found != null && shared == journal.size && found.entries.size >= journal.size) {
				// An undo (or a replay of a journal the chain already holds): its states fit the chain as is.
				Chain(found.base, found.legacy, found.entries, (found.states + fresh).toSortedMap())
			} else {
				val inherited = found?.states?.filterKeys { it <= shared }.orEmpty()
				val states = (inherited + fresh).filterKeys { keep(it, journal.size) }.toSortedMap()
				Chain(WeakReference(base), legacy, ArrayList(journal), states)
			}
			if (found != null) {
				chains.remove(found)
				// A branch keeps the old chain for redo; an append makes it redundant.
				if (next.entries !== found.entries && found.entries.size > shared) chains.add(0, found)
			}
			chains.add(0, next)
			chains.removeAll { it.base.get() == null }
			while (chains.size > CHAINS) chains.removeAt(chains.size - 1)
		}
	}
}

