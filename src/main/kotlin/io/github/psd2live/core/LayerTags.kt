package io.github.psd2live.core

import java.text.Normalizer
import java.util.Locale

/**
 * A layer's semantic tags: a set of words the generators read part of. Part and side words form the vocabulary
 * ([vocabulary]); the combinations the generators know map to one [SemanticTag] and [Side] each ([resolve]), so the
 * generators still read [LayerSemantic] and the mesh IDs they derive from it stay what they were. Every other word is
 * the user's own mark: it is kept with the layer for filtering and never reaches the generators.
 *
 * A part's layer can also hold one role word ([PartRole]: `highlight`, `lower`, `shadow`...) saying what it is within the
 * part; the analysis guesses roles ([PartRoles]) and the words the user sets win.
 *
 * The toggle and switch differentials are not semantics: they stay a layer's variant ([LayerVariant]).
 */
object LayerTags {
	const val LEFT = "left"
	const val RIGHT = "right"

	/** The word set each generator part stands for. Sets are distinct, and no set is a subset of a different one of equal size. */
	val parts: Map<SemanticTag, Set<String>> = linkedMapOf(
		SemanticTag.BACK_HAIR to setOf("hair", "back"),
		SemanticTag.FRONT_HAIR to setOf("hair", "front"),
		SemanticTag.HEADWEAR to setOf("headwear"),
		SemanticTag.FACE to setOf("face"),
		SemanticTag.FACE_DETAIL to setOf("face", "detail"),
		SemanticTag.IRIDES to setOf("eye", "iris"),
		SemanticTag.EYEBROW to setOf("brow"),
		SemanticTag.EYEWHITE to setOf("eye", "white"),
		SemanticTag.EYELASH to setOf("eye", "lash"),
		SemanticTag.EYE_CLOSE to setOf("eye", "closed"),
		SemanticTag.EYEWEAR to setOf("eyewear"),
		SemanticTag.EARS to setOf("ear"),
		SemanticTag.EARWEAR to setOf("ear", "accessory"),
		SemanticTag.NOSE to setOf("nose"),
		SemanticTag.MOUTH to setOf("mouth"),
		SemanticTag.MOUTH_OPEN to setOf("mouth", "open"),
		SemanticTag.MOUTH_CLOSE to setOf("mouth", "closed"),
		SemanticTag.TOOTH_T to setOf("teeth", "upper"),
		SemanticTag.TOOTH_B to setOf("teeth", "lower"),
		SemanticTag.TONGUE to setOf("tongue"),
		SemanticTag.NECK to setOf("neck"),
		SemanticTag.NECKWEAR to setOf("neck", "accessory"),
		SemanticTag.TOPWEAR to setOf("top"),
		SemanticTag.HANDWEAR to setOf("arm"),
		SemanticTag.BOTTOMWEAR to setOf("bottom"),
		SemanticTag.LEGWEAR to setOf("leg"),
		SemanticTag.FOOTWEAR to setOf("foot"),
		SemanticTag.TAIL to setOf("tail"),
		SemanticTag.WINGS to setOf("wings"),
		SemanticTag.OBJECTS to setOf("object"),
	)

	/** Every word the generators read: part words, the two sides and the role words, in a stable display order. */
	val vocabulary: List<String> = (parts.values.flatten() + listOf(LEFT, RIGHT) + PartRole.worded.map { it.word }).distinct()
	private val known = vocabulary.toHashSet()

	/** Whether [tag] is a vocabulary word rather than a user's mark. */
	fun isSemantic(tag: String): Boolean = tag in known

	/** The tags of a classification: its part's words, its side and its role. An unrecognised layer has none. */
	fun of(tag: SemanticTag, side: Side, role: PartRole? = null): Set<String> = buildSet {
		parts[tag]?.let(::addAll)
		when (side) { Side.LEFT -> add(LEFT); Side.RIGHT -> add(RIGHT); Side.NONE -> Unit }
		role?.takeIf { it != PartRole.PLAIN && parts.containsKey(tag) }?.let { add(it.word) }
	}

	/** [of] the classification's part, side and role together with the user's own [user] tags. */
	fun of(semantic: LayerSemantic, user: Set<String> = emptySet()): Set<String> = of(semantic.tag, semantic.side, semantic.role) + user

	/**
	 * What a tag set means to the generators. [candidates] lists every part the set matched equally well when more than
	 * one did. [role] is the role word the set holds that fits [tag] (null when it holds none).
	 */
	data class Resolution(val tag: SemanticTag, val side: Side, val user: Set<String>, val candidates: List<SemanticTag>, val role: PartRole? = null) {
		val ambiguous: Boolean get() = candidates.size > 1
	}

	/**
	 * The part [tags] stand for: the part whose whole word set the tags hold, the one with most words when several
	 * do, the first in [parts] among equally specific ones. Tags matching no part are unrecognised ([SemanticTag.UNKNOWN]).
	 */
	fun resolve(tags: Set<String>): Resolution {
		val words = tags.mapTo(HashSet(), ::normalize)
		val matching = parts.filterValues { words.containsAll(it) }
		val best = matching.values.maxOfOrNull { it.size }
		val candidates = matching.filterValues { it.size == best }.keys.toList()
		val tag = candidates.firstOrNull() ?: SemanticTag.UNKNOWN
		val left = LEFT in words; val right = RIGHT in words
		val side = when { left && !right -> Side.LEFT; right && !left -> Side.RIGHT; else -> Side.NONE }
		val role = PartRole.worded.firstOrNull { it.word in words && it.fits(tag) }
		return Resolution(tag, side, tags.filterTo(LinkedHashSet()) { !isSemantic(normalize(it)) }, candidates, role)
	}

	/**
	 * A tag as stored: NFKC, trimmed, inner whitespace collapsed; vocabulary words also lower case. A user's
	 * tag keeps its case, so `外套` and `Coat` stay as typed.
	 */
	fun normalize(raw: String): String {
		val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ")
		val lower = text.lowercase(Locale.ROOT)
		return if (lower in known) lower else text
	}

	/** A user tag must be short, printable text; vocabulary words are always valid. */
	fun requireValid(tag: String) {
		require(tag.isNotEmpty() && tag.length <= MAX_LENGTH) { "A tag must be 1-$MAX_LENGTH characters" }
		require(tag.none { it.isISOControl() || it == ',' }) { "A tag cannot hold control characters or commas" }
	}

	/**
	 * The vocabulary tags a typed word stands for, through the layer-name aliases ([LayerClassifier]): `前发` gives
	 * `hair, front`, `eye-l` gives `eye, iris, left`. Empty when the word names no part.
	 */
	fun fromName(name: String): Set<String> = LayerClassifier.classify(name).let { of(it.tag, it.side) }

	const val MAX_LENGTH = 48
}

/** A layer's differential: none, a toggle on [parameter], or one choice [switchId] of a switch on [parameter]. */
data class LayerVariant(val type: LayerType = LayerType.PRESET, val parameter: String = "", val switchId: Int = 0) {
	init {
		require(switchId >= 0) { "switch_id must be nonnegative" }
	}

	companion object {
		val NONE = LayerVariant()
	}
}

/** The variant part of a classification. */
val LayerSemantic.layerVariant: LayerVariant get() = LayerVariant(type, parameter, switchId)

/** The variant part of an override. */
val LayerClassificationOverride.layerVariant: LayerVariant get() = LayerVariant(type, parameter, switchId)

/**
 * A classification override holding [tags]' part, side and role and [variant]; the user words in [tags] are not part of
 * it. Tags without a role word keep the analysis' guess ([PartRoles]) unless [cleared]: the user took a guessed role away.
 */
fun LayerClassificationOverride.Companion.of(tags: Set<String>, variant: LayerVariant, cleared: Boolean = false): LayerClassificationOverride =
	LayerTags.resolve(tags).let {
		LayerClassificationOverride(variant.type, it.tag, it.side, variant.parameter, variant.switchId, it.role ?: PartRole.PLAIN.takeIf { cleared })
	}
