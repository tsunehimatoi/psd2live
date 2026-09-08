package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3Author
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.caff.CaffEntry
import org.umamo.format.cmo3.caff.CompressOption

/**
 * Assembles a complete .cmo3 byte stream for a fresh (never-read) model root, following the
 * corpus-wide container conventions (docs/format/CMO3.md §2 invariants): every entry obfuscated,
 * PNG entries RAW with an empty tag, main.xml FAST-compressed and LAST in the file table.
 *
 * The result is meant to be fed straight back through `Cmo3.read`, which returns a [Cmo3Model]
 * with normal read-side metadata - the reconcile exporter then treats the fresh graph exactly
 * like a read document.
 */
internal object Cmo3FreshFile {
	/** One embedded PNG entry: the unique archive path plus the raw PNG bytes. */
	internal class PngEntry(val path: String, val pngBytes: ByteArray)

	/**
	 * Serializes [root] via [Cmo3Author] and wraps it with the given PNGs into a CAFF container.
	 *
	 * @param Any  root         The fresh model root (a CModelSource web built in memory).
	 * @param List pngEntries   The embedded PNGs, in allocation order (icons, then pages).
	 * @param Int  obfuscateKey The container XOR key; the editor mints one per save.
	 * @return ByteArray The complete .cmo3 file bytes.
	 */
	internal fun assemble(root: Any, pngEntries: List<PngEntry>, obfuscateKey: Int): ByteArray {
		val entries = ArrayList<CaffEntry>(pngEntries.size + 1)
		for (pngEntry in pngEntries) {
			// CMO3: PNG entries - tag "", RAW compression, obfuscated (corpus invariant).
			entries.add(CaffEntry(pngEntry.path, "", pngEntry.pngBytes, CompressOption.RAW, obfuscated = true))
		}
		// CMO3: the main_xml entry - FAST compression, obfuscated, LAST in the table (corpus invariant).
		entries.add(CaffEntry("main.xml", CaffArchive.TAG_MAIN_XML, Cmo3Author.writeFreshMainXml(root), CompressOption.FAST, obfuscated = true))
		return CaffCodec.write(CaffArchive(obfuscateKey = obfuscateKey, entries = entries))
	}
}