package io.github.psd2live.project

import io.github.psd2live.core.RigEditOverlay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.umamo.format.art.SourceArt
import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * v1 revision identity, shared by GUI and application commands. Raster buffers are immutable.
 *
 * The identity is the SHA-256 of one canonical text ([reference] builds it whole). [of] computes the same hash
 * without rebuilding that text: the rig overlay's text around its authoring journal is cached by the identity of
 * the overlay's other fields, and the digest state after each journal entry is kept, so a document that appends
 * to (or shares a prefix with) a recently hashed journal only hashes its new entries.
 *
 * Fields added after v1 enter the text only when present, so documents without them keep their revision.
 */
internal object WorkspaceRevisions {
	private val rasterDigests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())

	fun of(document: WorkspaceDocument): String = JournalCheckpoints.of(document) ?: reference(document)

	/** The revision from the whole canonical text; [of] must always agree with it. */
	internal fun reference(document: WorkspaceDocument): String {
		val canonical = "|settings:" + document.settings + "|rig:" + document.rigEdits + tail(document)
		return "revision-${sha256(canonical.encodeToByteArray())}"
	}

	/** Everything after the rig overlay in the canonical text. */
	private fun tail(document: WorkspaceDocument): String = buildString {
		document.source.groups.forEach { group ->
			append("|group:").append(group.path).append(':').append(group.name).append(':').append(group.visible)
			append(':').append(group.opacity).append(':').append(group.clipped).append(':').append(group.blend).append(':').append(group.passThrough)
		}
		append("|canvas:").append(document.source.widthPx).append('x').append(document.source.heightPx)
		appendLayers(document.source)
		document.layerVisibility.toSortedMap().forEach { (key, value) -> append("|v:").append(key).append('=').append(value) }
		document.deletedLayerIds.sorted().forEach { append("|d:").append(it) }
		document.layerOverrides.toSortedMap().forEach { (key, value) -> append("|o:").append(key).append('=').append(value) }
		document.parentOverrides.toSortedMap().forEach { (key, value) -> append("|p:").append(key).append('=').append(value) }
		document.meshOverrides.toSortedMap().forEach { (key, value) -> append("|m:").append(key).append('=').append(value) }
		// Absent for documents without texture overrides.
		document.storedTextureOverrides.toSortedMap().forEach { (key, value) ->
			append("|t:").append(key).append('=').append(TextureOverrideCodec.canonical(value))
		}
		document.rigEdits.deletedParameterIds.sorted().forEach { append("|pd:").append(it) }
		document.rigEdits.parameterEdits.forEach { edit -> append("|pe:").append(edit) }
		// Omit the field entirely for v1 documents without a separate generation input.
		listOf("generation" to document.generationSource, "meshSource" to document.meshSource).forEach { (kind, source) ->
			if (source == null) return@forEach
			append("|$kind:").append(source.widthPx).append('x').append(source.heightPx)
			source.groups.forEach { group ->
				append("|group:").append(group.path).append(':').append(group.name).append(':').append(group.visible)
				append(':').append(group.opacity).append(':').append(group.clipped).append(':').append(group.blend).append(':').append(group.passThrough)
			}
			appendLayers(source)
		}
	}

	private fun StringBuilder.appendLayers(source: SourceArt) {
		source.layers.forEachIndexed { index, layer ->
			append("|layer:").append(index).append(':').append(layer.id.raw)
			append(':').append(layer.name).append(':').append(layer.groupPath).append(':').append(layer.kind)
			append(':').append(layer.visible).append(':').append(layer.order).append(':').append(layer.bounds)
			append(':').append(layer.opacity).append(':').append(layer.clipped).append(':').append(layer.blend).append(':').append(layer.channelMask)
			append(':').append(layer.raster.width).append('x').append(layer.raster.height)
			append(':').append(rasterDigest(layer.raster.rgba))
			// Only a float rectangle that differs from the integer bounds is part of the identity.
			layer.storedCanvasRect?.let { rect ->
				append(":rect=").append(rect.left).append(',').append(rect.top).append(',').append(rect.width).append(',').append(rect.height)
			}
			layer.transform.takeUnless { it.isIdentity }?.let { append(":transform=").append(it.toList().joinToString(",")) }
		}
	}

	/** SHA-256 of an immutable raster, cached by array identity. */
	fun rasterDigest(rgba: ByteArray): String = rasterDigests[rgba] ?: sha256(rgba).also { rasterDigests[rgba] = it }

	/** Records [digest] as [rgba]'s SHA-256 when the caller has just verified it (a raster loaded from its digest-named blob). */
	fun seedRasterDigest(rgba: ByteArray, digest: String) { rasterDigests[rgba] = digest }

	internal fun sha256(value: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(value))
	internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

	/**
	 * The canonical text is `prefix + entry0 + ", " + entry1 + ... + suffix + tail`: the overlay's data-class text
	 * with the journal list spliced out. Digest states after each entry are kept for a few recent chains.
	 */
	private object JournalCheckpoints {
		private const val CHAINS = 4

		private class Chain(
			val settings: String,
			val before: String,
			val base: MessageDigest,
			val entries: List<JsonObject>,
			val states: List<MessageDigest>,
		)

		private val chains = ArrayDeque<Chain>()

		fun of(document: WorkspaceDocument): String? {
			val parts = OverlayParts.of(document.rigEdits) ?: return null
			val settings = document.settings.toString()
			val journal = document.rigEdits.authoringJournal
			val found = synchronized(chains) {
				chains.firstOrNull { it.settings == settings && (it.before === parts.before || it.before == parts.before) }
			}
			var shared = 0
			val states = ArrayList<MessageDigest>(journal.size)
			val base: MessageDigest
			if (found != null) {
				base = found.base
				val limit = minOf(found.entries.size, journal.size)
				while (shared < limit && found.entries[shared] === journal[shared]) shared++
				for (i in 0 until shared) states.add(found.states[i])
			} else {
				base = MessageDigest.getInstance("SHA-256")
				base.update("|settings:".encodeToByteArray()); base.update(settings.encodeToByteArray())
				base.update("|rig:".encodeToByteArray()); base.update(parts.before.encodeToByteArray())
			}
			val digest = (if (shared == 0) base else states[shared - 1]).clone() as MessageDigest
			for (i in shared until journal.size) {
				if (i > 0) digest.update(SEPARATOR)
				digest.update(JournalEntryText.bytes(journal[i]))
				states.add(digest.clone() as MessageDigest)
			}
			val chain = Chain(settings, parts.before, base, ArrayList(journal), states)
			synchronized(chains) {
				if (found != null) chains.remove(found)
				chains.addFirst(chain)
				while (chains.size > CHAINS) chains.removeLast()
			}
			digest.update(parts.after.encodeToByteArray())
			digest.update(tail(document).encodeToByteArray())
			return "revision-${hex(digest.digest())}"
		}

		private val SEPARATOR = ", ".encodeToByteArray()
	}

	/** The overlay's text before and after its journal entries, cached by the identity of its other fields. */
	private object OverlayParts {
		class Parts(val values: Array<Any?>, val before: String, val after: String)

		private const val CACHED = 8
		private val fields: List<Field>? = runCatching {
			val all = RigEditOverlay::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
			require(all.count { it.name == "authoringJournal" } == 1)
			all.filter { it.name != "authoringJournal" }.onEach { it.isAccessible = true }
		}.getOrNull()
		private val recent = ArrayDeque<Parts>()

		private fun same(a: Any?, b: Any?): Boolean = a === b || ((a is Number || a is Boolean || a is Char) && a == b)

		fun of(rig: RigEditOverlay): Parts? {
			val fields = fields ?: return null
			val values = Array(fields.size) { fields[it].get(rig) }
			synchronized(recent) {
				recent.firstOrNull { parts -> values.indices.all { same(parts.values[it], values[it]) } }?.let { return it }
			}
			val sentinel = JsonObject(mapOf("psd2live-journal-${UUID.randomUUID()}" to JsonNull))
			val text = rig.copy(authoringJournal = listOf(sentinel)).toString()
			val marker = "[$sentinel]"
			val at = text.indexOf(marker)
			if (at < 0 || text.indexOf(marker, at + 1) >= 0) return null
			val parts = Parts(values, text.substring(0, at + 1), text.substring(at + marker.length - 1))
			synchronized(recent) {
				recent.addFirst(parts)
				while (recent.size > CACHED) recent.removeLast()
			}
			return parts
		}
	}
}

/** Per-entry digests of authoring-journal commands, cached by entry identity (entries are immutable). */
internal object JournalEntryDigests {
	class Digest(val sha256: String, val length: Int)

	private val cache = IdentityWeakCache<JsonObject, Digest>()

	fun of(entry: JsonObject): Digest = cache.getOrPut(entry) {
		val text = JournalEntryText.bytes(entry)
		Digest(WorkspaceRevisions.sha256(text), utf16Length(text))
	}

	/** The length of the string [utf8] encodes, in UTF-16 units: a four-byte sequence is a surrogate pair. */
	private fun utf16Length(utf8: ByteArray): Int {
		var length = 0
		for (byte in utf8) {
			val b = byte.toInt() and 0xff
			if (b and 0xc0 != 0x80) length += if (b and 0xf8 == 0xf0) 2 else 1
		}
		return length
	}
}

/**
 * The UTF-8 text of authoring-journal commands, cached by entry identity. Writing an entry's numbers out costs
 * several times what hashing the text does, and a revision whose overlay or settings changed hashes the whole
 * journal again. Held softly: the text comes back from the entry when memory runs low.
 */
internal object JournalEntryText {
	private val cache = IdentityWeakCache<JsonObject, SoftReference<ByteArray>>()

	fun bytes(entry: JsonObject): ByteArray {
		cache.getOrPut(entry) { SoftReference(entry.toString().encodeToByteArray()) }.get()?.let { return it }
		return entry.toString().encodeToByteArray().also { cache.put(entry, SoftReference(it)) }
	}
}

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
