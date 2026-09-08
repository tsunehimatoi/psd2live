package org.umamo.format

/**
 * The shared read/write contract for one binary container format in the Cubism/Umamo file family.
 *
 * EN: Every format Umamo can both parse and emit as a self-contained byte stream (today `.cmo3` and
 *     `.moc3`; later `.uma`) exposes the same members, so callers and the [FormatRegistry]
 *     dispatcher treat formats uniformly instead of hardcoding one facade's method names. The
 *     in-memory model type differs per format ([org.umamo.format.cmo3.Cmo3Model] vs
 *     [org.umamo.format.moc3.moc.MocModel]) and the two share no supertype - hence the generic
 *     [TModel]; a heterogeneous registry holds `FormatCodec<*>` and callers branch on [kind]. The
 *     dependency runs codec → [FileKind] (a codec names its kind), never the reverse, so [FileKind]
 *     stays pure metadata. Text sidecars (`model3.json` and friends) are `String`-shaped, not
 *     `ByteArray`, so they deliberately fall outside this contract.
 * JA: `.cmo3`/`.moc3` など「バイト列で完結する」形式の共通読み書き契約。モデル型は形式ごとに異なる。
 */
public interface FormatCodec<TModel> {
	/** Which [FileKind] this codec reads and writes - the link to capability/tier metadata. */
	public val kind: FileKind

	/**
	 * Cheap magic-byte sniff: whether [candidateBytes] plausibly belongs to this format.
	 *
	 * Intended to be allocation-light (inspect only the leading magic), so [FormatRegistry] can try
	 * every codec against a file without fully parsing it.
	 *
	 * @param ByteArray candidateBytes The file contents to test.
	 * @return Boolean True if the leading bytes match this format's signature.
	 */
	public fun matches(candidateBytes: ByteArray): Boolean

	/**
	 * Cheap version probe: the format version carried by [bytes], without a full [read].
	 *
	 * Implementations must stay lightweight next to [read] (decode only what the version requires)
	 * and must not throw on malformed or unknown input - null covers both "this codec exposes no
	 * version" (the default, kept by the layered-art readers) and "no version could be determined".
	 *
	 * @param ByteArray bytes The complete file contents.
	 * @return FormatVersion? The probed version, or null when unavailable.
	 */
	public fun getVersion(bytes: ByteArray): FormatVersion? = null

	/**
	 * Parses a complete file into this format's in-memory model.
	 *
	 * @param ByteArray bytes The complete file contents.
	 * @return TModel The parsed model.
	 */
	public fun read(bytes: ByteArray): TModel

	/**
	 * Serializes a model back to a complete file.
	 *
	 * @param TModel model The model to write.
	 * @return ByteArray The complete file bytes.
	 */
	public fun write(model: TModel): ByteArray
}

/**
 * A [FormatCodec] for a format Umamo reads but never emits - the layered-art ingestion sources
 * (PSD, CLIP, KRA), whose [FileKind] already declares `writable = false`.  Inherits a [write] that
 * refuses, so the read-only nature is stated once in the type instead of copy-pasted throws in each
 * reader; the [FormatRegistry] still holds these uniformly as `FormatCodec<*>`.
 *
 * 読み取り専用形式（PSD/CLIP/KRA などの画材取り込み元）用の FormatCodec。write は常に拒否する。
 */
public interface ReadOnlyCodec<TModel> : FormatCodec<TModel> {
	/**
	 * Always refuses: this format is ingestion-only.
	 *
	 * @param TModel model Ignored.
	 * @return ByteArray Never returns.
	 */
	override fun write(model: TModel): ByteArray =
		throw UnsupportedOperationException("${kind.extension.uppercase()} is read-only")
}