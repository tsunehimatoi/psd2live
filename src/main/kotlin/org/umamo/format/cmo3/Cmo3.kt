package org.umamo.format.cmo3

import org.umamo.format.FileKind
import org.umamo.format.FormatCodec
import org.umamo.format.FormatVersion
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.caff.CaffEntry
import org.umamo.format.cmo3.caff.CompressOption
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.serialize.ModelGraph
import org.umamo.format.cmo3.serialize.cubismEngine
import org.umamo.format.cmo3.xml.XmlCodec
import java.io.File

/**
 * A loaded CMO3: the CAFF container (all entries, incl. layer/atlas/icon PNGs) and the typed model
 * graph (the CModelSource root plus the shared pool). Mutate the graph or layer images, then write.
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md</a>
 */
public class Cmo3Model internal constructor(
	/** The container; replaced in place when layer images change. */
	public var archive: CaffArchive,
	internal val graph: ModelGraph,
) {
	/** The model root (a org.umamo.format.cmo3.model.custom.CModelSource), or null. */
	public val root: Any? get() = graph.root

	/** The model's raw authored SDK target version, or null when the document carries none. */
	public val targetVersionNo: Int?
		// CMO3: CModelSource field targetVersionNo.
		get() = (root as? CModelSource)?.targetVersionNo as? Int

	/**
	 * Sets the model's authored SDK target version so it survives a re-emit even when the source
	 * document carried no targetVersionNo element.  The writer replays the child slots recorded at
	 * read time, so a bare field assignment on such a document would be dropped silently; this also
	 * records the missing slot, positioned where the editor writes the field (immediately before
	 * latestVersionOfLastModelerNo).
	 *
	 * @param Int versionNo The raw targetVersionNo value to persist.
	 */
	public fun setTargetVersionNo(versionNo: Int) {
		// CMO3: CModelSource field targetVersionNo.
		val modelSource = root as? CModelSource ?: error("model root is not a CModelSource")
		modelSource.targetVersionNo = versionNo
		graph.ensureKnownChildSlot(
			owner = modelSource,
			tag = "CModelSource",
			propertyName = "targetVersionNo",
			beforePropertyName = "latestVersionOfLastModelerNo",
		)
	}

	/** All [CImageResource]s reachable in the model (shared pool + inline), each linking an embedded PNG. */
	public fun imageResources(): List<CImageResource> {
		val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
		val out = ArrayList<CImageResource>()
		val stack = ArrayDeque<Any?>()
		graph.root?.let(stack::addLast)
		graph.sharedOrder.forEach(stack::addLast)
		while (stack.isNotEmpty()) {
			val obj = stack.removeLast() ?: continue
			if (obj is CharSequence || obj is Number || obj is Boolean || obj is Char || obj is Enum<*>) continue
			if (!seen.add(obj)) continue
			if (obj is CImageResource) out.add(obj)
			when (obj) {
				is Iterable<*> -> obj.forEach(stack::addLast)
				is Map<*, *> -> obj.values.forEach(stack::addLast)
				is Array<*> -> obj.forEach(stack::addLast)
				else ->
					if (obj::class.java.name.startsWith(MODEL_PACKAGE)) {
						var cls: Class<*>? = obj::class.java
						while (cls != null && cls != Any::class.java) {
							for (field in cls.declaredFields) {
								if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
								field.isAccessible = true
								stack.addLast(field.get(obj))
							}
							cls = cls.superclass
						}
					}
			}
		}
		return out
	}

	private companion object {
		const val MODEL_PACKAGE = "org.umamo.format.cmo3.model"
	}

	/**
	 * Returns the decoded PNG bytes backing [resource], or null if it has no embedded file.
	 *
	 * @param CImageResource resource A model image resource.
	 * @return ByteArray? The PNG bytes, or null.
	 */
	public fun extractLayerPng(resource: CImageResource): ByteArray? {
		val path = resource.imageFileBuf?.archivePath ?: return null
		return archive.byPath(path)?.content
	}

	/**
	 * Replaces [resource]'s embedded PNG with [png] (same dimensions expected) and updates its size.
	 *
	 * @param CImageResource resource The image resource to update.
	 * @param ByteArray      png      The new PNG bytes.
	 */
	public fun replaceLayerPng(resource: CImageResource, png: ByteArray) {
		val path = resource.imageFileBuf?.archivePath ?: error("resource has no embedded imageFileBuf")
		replacePng(path, png)
		resource.imageFileBuf_size = png.size // keep main.xml's size attribute consistent
	}

	/**
	 * Replaces the embedded PNG at [path] with [png], keeping the entry's place, tag, and options.
	 *
	 * The graph node that references the path is the caller's to keep consistent; an icon's
	 * CWritableImage carries no size field, a layer's CImageResource does ([replaceLayerPng]).
	 *
	 * @param String    path The archive path of an existing entry.
	 * @param ByteArray png  The new PNG bytes.
	 */
	public fun replacePng(path: String, png: ByteArray) {
		require(archive.byPath(path) != null) { "archive holds no entry at '$path'" }
		archive =
			archive.withEntries(
				archive.entries.map { entry ->
					if (entry.path == path) CaffEntry(entry.path, entry.tag, png, entry.compression, entry.obfuscated) else entry
				},
			)
	}

	/**
	 * Embeds a NEW PNG for [resource], whose `imageFileBuf` must already name an archive path no
	 * existing entry uses (mint one with [nextImageFileBufPath]).
	 *
	 * The entry takes the corpus conventions for pixel data - an empty tag, stored raw (a PNG is
	 * already compressed), and obfuscated like every corpus entry - and is inserted BEFORE the
	 * `main_xml` entry, which every corpus file keeps last in the table.
	 *
	 * @param CImageResource resource The image resource naming the new entry's path.
	 * @param ByteArray      png      The PNG bytes to embed.
	 */
	public fun addLayerPng(resource: CImageResource, png: ByteArray) {
		val path = resource.imageFileBuf?.archivePath ?: error("resource has no imageFileBuf path to add under")
		addPng(path, png)
		resource.imageFileBuf_size = png.size // keep main.xml's size attribute consistent
	}

	/**
	 * Embeds a NEW PNG at [path], which no existing entry may use (mint one with
	 * [nextImageFileBufPath] for a layer or page buffer, [nextImagePath] for an icon).
	 *
	 * The entry takes the corpus conventions for pixel data - an empty tag, stored raw (a PNG is
	 * already compressed), and obfuscated like every corpus entry - and is inserted BEFORE the
	 * `main_xml` entry, which every corpus file keeps last in the table.  The graph node that
	 * references the path is the caller's to wire; an icon's CWritableImage carries no size field, so
	 * there is nothing here to keep consistent.
	 *
	 * @param String    path The archive path the entry is stored under.
	 * @param ByteArray png  The PNG bytes to embed.
	 */
	public fun addPng(path: String, png: ByteArray) {
		require(archive.byPath(path) == null) { "archive already holds an entry at '$path'" }
		val mainXmlIndex = archive.entries.indexOfFirst { entry -> entry.tag == CaffArchive.TAG_MAIN_XML }
		val insertAt = if (mainXmlIndex >= 0) mainXmlIndex else archive.entries.size
		val entries = archive.entries.toMutableList()
		entries.add(insertAt, CaffEntry(path, "", png, CompressOption.RAW, obfuscated = true))
		archive = archive.withEntries(entries)
	}

	/**
	 * Removes [resource]'s embedded PNG from the archive.
	 *
	 * The caller owns unlinking the graph: an archive entry a live resource still references is a
	 * load-time hazard, so remove the entry only together with dropping the resource itself.
	 *
	 * @param CImageResource resource The image resource whose entry to remove.
	 */
	public fun removeLayerPng(resource: CImageResource) {
		val path = resource.imageFileBuf?.archivePath ?: error("resource has no embedded imageFileBuf")
		archive = archive.withEntries(archive.entries.filterNot { entry -> entry.path == path })
	}

	/**
	 * Mints the next unused `imageFileBuf` archive path, continuing past the retained archive's
	 * highest suffix.
	 *
	 * The editor's convention is `imageFileBuf.png`, then `imageFileBuf_0.png` onward; resolution is
	 * by exact name, so continuing past the maximum is what keeps a minted page from colliding with
	 * any retained blob.
	 *
	 * @return String The unused archive path.
	 */
	public fun nextImageFileBufPath(): String = nextPathInSequence("imageFileBuf")

	/**
	 * Mints the next unused `image` archive path - the editor's icon-thumbnail sequence (`image.png`,
	 * then `image_0.png` onward), continuing past the retained archive's highest suffix, by the same
	 * rule as [nextImageFileBufPath].
	 *
	 * @return String The unused archive path.
	 */
	public fun nextImagePath(): String = nextPathInSequence("image")

	/**
	 * The next unused path in one of the editor's de-dupe sequences: `<stem>.png`, then
	 * `<stem>_0.png` onward.  Resolution is by exact name, so continuing past the maximum is what
	 * keeps a minted entry from colliding with any retained blob.
	 *
	 * @param String stem The sequence's stem.
	 * @return String The unused archive path.
	 */
	private fun nextPathInSequence(stem: String): String {
		var highestUsed = -2
		for (entry in archive.entries) {
			if (entry.path == "$stem.png") {
				highestUsed = maxOf(highestUsed, -1)
			} else {
				val suffix =
					entry.path
						.removePrefix("${stem}_")
						.removeSuffix(".png")
						.takeIf { middle -> "${stem}_$middle.png" == entry.path }
						?.toIntOrNull()
				if (suffix != null) {
					highestUsed = maxOf(highestUsed, suffix)
				}
			}
		}
		val next = highestUsed + 1
		return if (next == -1) "$stem.png" else "${stem}_$next.png"
	}
}

/**
 * Reads and writes Live2D Cubism `.cmo3` files (the CAFF container + the typed model graph).
 *
 * EN: `read` parses the container and deserializes main.xml into the typed model; `write` re-emits
 *     the model into main.xml and repacks the container. Round-trip is byte-identical for unedited
 *     files. Implements [FormatCodec] for `.cmo3`; the `File`-taking overloads are JVM-only
 *     conveniences on top of the byte-array contract. JVM/Android only (uses reflection + JDOM).
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md</a>
 */
public object Cmo3 : FormatCodec<Cmo3Model> {
	/** This codec handles [FileKind.Cmo3]. */
	override val kind: FileKind get() = FileKind.Cmo3

	/**
	 * True if [candidateBytes] starts with the CAFF magic. Note that every CAFF container sniffs the
	 * same way; a `.cmo3` is distinguished from other CAFF files only on full [read] (the `main_xml`
	 * entry). Good enough for dispatch among the formats Umamo registers.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether this looks like a CAFF container.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = CaffCodec.isCaff(candidateBytes)

	/**
	 * Cheap version probe: decodes only the `main_xml` entry and reads the `<root fileFormatVersion>`
	 * attribute, skipping the embedded layer PNGs and the full XML/object-graph deserialization.
	 *
	 * @param ByteArray bytes The complete file contents.
	 * @return FormatVersion? The CMO3 generation, or null when this is not a readable `.cmo3`.
	 */
	override fun getVersion(bytes: ByteArray): FormatVersion? {
		if (!CaffCodec.isCaff(bytes)) {
			return null
		}
		// A probe never throws: a truncated or corrupt container reports null instead.
		val mainXml =
			runCatching { CaffCodec.readFirstEntryByTag(bytes, CaffArchive.TAG_MAIN_XML)?.content }.getOrNull()
				?: return null
		val rawVersion = extractFileFormatVersion(mainXml) ?: return null
		return Cmo3Version.fromFileFormatVersion(rawVersion)
	}

	/**
	 * Scans decoded main.xml bytes for the ASCII `fileFormatVersion="..."` root attribute and returns
	 * its value, without decoding the multi-megabyte document to a String or parsing the XML.
	 *
	 * @param ByteArray mainXml The decoded main.xml bytes (UTF-8; the attribute is plain ASCII).
	 * @return String? The attribute text, or null when absent.
	 */
	private fun extractFileFormatVersion(mainXml: ByteArray): String? {
		// CMO3.md §3 Document shape: <root fileFormatVersion="...">.
		val needle = "fileFormatVersion=\"".encodeToByteArray()
		val quoteByte = '"'.code.toByte()
		val lastNeedleStart = mainXml.size - needle.size
		var scanIndex = 0
		scan@ while (scanIndex <= lastNeedleStart) {
			for (needleIndex in needle.indices) {
				if (mainXml[scanIndex + needleIndex] != needle[needleIndex]) {
					scanIndex++
					continue@scan
				}
			}
			val valueStart = scanIndex + needle.size
			var valueEnd = valueStart
			while (valueEnd < mainXml.size && mainXml[valueEnd] != quoteByte) {
				valueEnd++
			}
			if (valueEnd >= mainXml.size) {
				return null
			}
			return mainXml.decodeToString(valueStart, valueEnd)
		}
		return null
	}

	/**
	 * Parses a `.cmo3` from raw bytes.
	 *
	 * @param ByteArray bytes The file contents.
	 * @return Cmo3Model The loaded model.
	 */
	override fun read(bytes: ByteArray): Cmo3Model {
		val archive = CaffCodec.read(bytes)
		val mainXml =
			archive.firstByTag(CaffArchive.TAG_MAIN_XML)?.content
				?: error("not a CMO3: no main_xml entry")
		val graph = cubismEngine().readModel(XmlCodec.parse(mainXml))
		return Cmo3Model(archive, graph)
	}

	/** Parses a `.cmo3` from a file. */
	public fun read(file: File): Cmo3Model = read(file.readBytes())

	/**
	 * Serializes a model back to `.cmo3` bytes.
	 *
	 * @param Cmo3Model model The model to write.
	 * @return ByteArray The complete `.cmo3` file bytes.
	 */
	override fun write(model: Cmo3Model): ByteArray {
		val document = cubismEngine().writeModel(model.graph)
		// Classes first introduced after the read (created entities) are missing from the replayed
		// <?import?>/<?version?> PIs, and the official reader cannot resolve their tags without them.
		Cmo3Author.completePrologue(document)
		val mainXml = XmlCodec.write(document)
		val entries =
			model.archive.entries.map { entry ->
				if (entry.tag == CaffArchive.TAG_MAIN_XML) {
					CaffEntry(entry.path, entry.tag, mainXml, entry.compression, entry.obfuscated)
				} else {
					entry
				}
			}
		return CaffCodec.write(model.archive.withEntries(entries))
	}

	/** Writes a model to a `.cmo3` file. */
	public fun write(model: Cmo3Model, file: File): Unit = file.writeBytes(write(model))
}