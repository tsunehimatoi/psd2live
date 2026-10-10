package io.github.psd2live.core.legacy

import io.github.psd2live.core.*
import kotlinx.serialization.json.*

/**
 * A journal entry before a version 2 split that failed only on drawables the split supersedes, so replay skipped it
 * ([StubTolerance]): its [index] in the journal, its [op], the superseded [targets] it names and the failure [detail].
 */
data class SupersededEntryNote(val index: Int, val op: String, val targets: List<String>, val detail: String) {
	fun describe(): String = "Skipped $op: it addresses only ${targets.joinToString()}, superseded by a later split ($detail)"
}


/**
 * Journal entries before a version 2 split that address only drawables the split supersedes. The base builds those
 * as stubs - meshed from frozen pixels, outside every aggregate stage - so such an entry may no longer apply as it
 * did. Replay then treats it as a no-op and notes it ([GeneratedOverrides.Outcome.notes]); any entry that touches
 * a non-stub, or fails anywhere else, still fails the replay. Only journals from builds before splits checkpointed
 * their merge have entries before a version 2 record that replay.
 */
internal object StubTolerance {
	/** Per entry (by identity) before at least one v2 record: every drawable those later records supersede. */
	fun of(journal: List<JsonObject>): java.util.IdentityHashMap<JsonObject, Set<String>> {
		val result = java.util.IdentityHashMap<JsonObject, Set<String>>()
		var later = emptySet<String>()
		for (index in journal.indices.reversed()) {
			val entry = journal[index]
			if (later.isNotEmpty()) result[entry] = later
			if (ArtPrimitiveV2.isV2(entry)) later = later + ((entry[ArtPrimitiveV2.SUPERSEDES] as? JsonArray)
				?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty())
		}
		return result
	}

	/** The drawables and deformers of [model] and the [stubs] that [entry] names, alone or as `kind:id` (a Glue as `glue:a:b`). */
	fun targets(model: org.umamo.runtime.model.PuppetModel, entry: JsonObject, stubs: Set<String>): Set<String> {
		val known = HashSet<String>(stubs)
		model.drawables.forEach { known += it.id.raw }; model.deformers.forEach { known += it.id.raw }
		val found = LinkedHashSet<String>()
		fun visit(value: JsonElement) {
			when (value) {
				is JsonObject -> value.values.forEach(::visit)
				is JsonArray -> value.forEach(::visit)
				is JsonPrimitive -> if (value.isString) {
					val text = value.content
					for (candidate in listOf(text) + text.split(':').drop(1)) if (candidate in known) found += candidate
				}
				else -> Unit
			}
		}
		visit(entry)
		return found
	}

	/** Whether every drawable or deformer [entry] names is one of [stubs] (and it names at least one). */
	fun onlyStubs(model: org.umamo.runtime.model.PuppetModel, entry: JsonObject, stubs: Set<String>): Boolean {
		val targets = targets(model, entry, stubs)
		return targets.isNotEmpty() && targets.all { it in stubs }
	}
}

