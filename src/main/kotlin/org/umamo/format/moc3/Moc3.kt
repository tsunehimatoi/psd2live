package org.umamo.format.moc3

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.umamo.format.FileKind
import org.umamo.format.FormatCodec
import org.umamo.format.FormatVersion
import org.umamo.format.moc3.decode.MocDecoder
import org.umamo.format.moc3.encode.MocEncoder
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.json.Model3Json
import org.umamo.format.moc3.json.Physics3Json
import org.umamo.format.moc3.json.SidecarJson
import org.umamo.format.moc3.json.UserData3Json
import org.umamo.format.moc3.moc.MocCodec
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.MocVersion

/**
 * Reads and writes Live2D Cubism runtime assets: the `.moc3` binary model and its JSON sidecars.
 *
 * Implements [FormatCodec] at the SEMANTIC tier: [read] decodes a file all the way to [MocDocument],
 * and [write] synthesizes every section back from one, so read - edit - write is a real cycle here
 * rather than a byte copy.  Byte-exact container fidelity lives one layer down in [MocCodec], whose
 * own read/write reproduce an unedited file byte-for-byte; reach that tier through [decode] and
 * [bake] when the raw sections are what a caller needs.  This is the same split CMO3 has, where the
 * codec re-emits the model graph and CaffCodec holds the container bytes.
 *
 * The JSON sidecar helpers handle `model3.json` and friends.  Those sidecars are `String`-shaped
 * (not `ByteArray`), so they sit alongside the [FormatCodec] members rather than within them.  The
 * `.moc3` and its sidecars are sibling files (the manifest references the rest by path), so they are
 * decoupled here - pair them yourself.  Pure Kotlin (JVM + Android); no JDOM/reflection.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §8</a>
 */
public object Moc3 : FormatCodec<MocDocument> {
	/** This codec handles [FileKind.Moc3]. */
	override val kind: FileKind get() = FileKind.Moc3

	/**
	 * True if [candidateBytes] starts with the `MOC3` magic and is at least header-sized.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether this looks like a `.moc3`.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = MocCodec.isMoc3(candidateBytes)

	/**
	 * Cheap version probe: reads the version byte without parsing the section table.
	 *
	 * @param ByteArray bytes The complete file contents.
	 * @return FormatVersion? The `.moc3` version, or null when the magic or byte is unrecognized.
	 */
	override fun getVersion(bytes: ByteArray): FormatVersion? {
		if (!MocCodec.isMoc3(bytes)) {
			return null
		}
		// MOC3.md §version gating: version byte @ +0x04.
		val versionByte = bytes[4].toInt() and 0xFF
		return MocVersion.entries.firstOrNull { it.byteValue == versionByte }
	}

	/**
	 * Parses a `.moc3` and decodes it to the full semantic [MocDocument].
	 *
	 * @param ByteArray bytes The file contents.
	 * @return MocDocument The decoded document.
	 */
	override fun read(bytes: ByteArray): MocDocument = MocDecoder.decode(MocCodec.read(bytes))

	/**
	 * Bakes [model] to runtime-valid `.moc3` bytes, synthesizing every section from the object model
	 * with no reference container involved.
	 *
	 * The output is runtime-valid, not byte-identical to whatever file the document was read from: the
	 * section layout is ours rather than the editor's.  Go through [MocCodec] when a byte-exact re-emit
	 * of an unedited container is the point.
	 *
	 * @param MocDocument model The document to bake.
	 * @return ByteArray The complete `.moc3` file bytes.
	 */
	override fun write(model: MocDocument): ByteArray = MocEncoder.bakeFresh(model.version, model)

	/**
	 * Decodes an already-parsed [MocModel] into the full semantic [MocDocument] - the container-tier
	 * entry, paired with [MocCodec.read] by a caller that wants the raw sections too.
	 *
	 * @param MocModel model The parsed model.
	 * @return MocDocument The decoded document.
	 */
	public fun decode(model: MocModel): MocDocument = MocDecoder.decode(model)

	/**
	 * Bakes a (possibly-edited) [doc] onto [reference], synthesizing every section the lowering covers
	 * and carrying anything it does not from the reference container.
	 *
	 * That carry-through is empty for every corpus model (`MocBakeFreshCoverageTest` pins the carried
	 * set at nothing), so it stands against a section some later format version adds rather than against
	 * one we cannot derive.  [write] is the reference-free form, and what an export uses.
	 *
	 * @param MocModel reference The decoded source providing the carried sections and the version.
	 * @param MocDocument doc The document to bake.
	 * @return ByteArray The baked `.moc3` file bytes.
	 */
	public fun bake(reference: MocModel, doc: MocDocument): ByteArray = MocEncoder.bake(reference, doc)

	/**
	 * Parses a `model3.json` manifest.
	 *
	 * @param String text The file contents.
	 * @return Model3Json The parsed manifest.
	 */
	public fun readModel3(text: String): Model3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `model3.json` manifest (matches the editor's formatting).
	 *
	 * @param Model3Json model The manifest to write.
	 * @return String The serialized JSON.
	 */
	public fun writeModel3(model: Model3Json): String = SidecarJson.encodeToString(model)

	/**
	 * Parses a `physics3.json` rig definition.
	 *
	 * @param String text The file contents.
	 * @return Physics3Json The parsed rig definition.
	 */
	public fun readPhysics3(text: String): Physics3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `physics3.json` rig definition.
	 *
	 * @param Physics3Json physics The rig definition to write.
	 * @return String The serialized JSON.
	 */
	public fun writePhysics3(physics: Physics3Json): String = SidecarJson.encodeToString(physics)

	/**
	 * Parses a `cdi3.json` display-info file.
	 *
	 * @param String text The file contents.
	 * @return Cdi3Json The parsed display info.
	 */
	public fun readCdi3(text: String): Cdi3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `cdi3.json` display-info file.
	 *
	 * @param Cdi3Json displayInfo The display info to write.
	 * @return String The serialized JSON.
	 */
	public fun writeCdi3(displayInfo: Cdi3Json): String = SidecarJson.encodeToString(displayInfo)

	/**
	 * Parses a `userdata3.json` file.
	 *
	 * @param String text The file contents.
	 * @return UserData3Json The parsed user data.
	 */
	public fun readUserData3(text: String): UserData3Json = SidecarJson.decodeFromString(text)

	/**
	 * Serializes a `userdata3.json` file.
	 *
	 * @param UserData3Json userData The user data to write.
	 * @return String The serialized JSON.
	 */
	public fun writeUserData3(userData: UserData3Json): String = SidecarJson.encodeToString(userData)
}