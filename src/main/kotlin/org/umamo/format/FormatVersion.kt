package org.umamo.format

/**
 * A probed format version, the common face over each codec's own version type.
 *
 * [org.umamo.format.moc3.moc.MocVersion] (an enum) and [org.umamo.format.cmo3.Cmo3Version] (a
 * sealed interface) share no other supertype - mirroring how the codec model types share none -
 * so this marker is what lets [FormatCodec.getVersion] report a version uniformly.  Not to be
 * confused with the serialized CMO3 model enum `org.umamo.format.cmo3.model.gen.FormatVersion`,
 * which is format data inside the document, not a probe result.
 */
public interface FormatVersion {
	/** Short human-readable token naming the version (e.g. "cmo3 / Cubism 4.x"). */
	public val label: String

	/**
	 * A human-readable support summary for this version.
	 *
	 * @return String The summary sentence.
	 */
	public fun describe(): String
}