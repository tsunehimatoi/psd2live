package org.umamo.format.moc3.encode

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.moc.ElementType
import org.umamo.format.moc3.moc.MocModel
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section

/**
 * Frames an ordered set of section byte-arrays into a valid `.moc3` blob (header + offset table +
 * sections), the serializer half of the bake.
 *
 * Section k is placed at `table[k]`, its start aligned to 64 bytes - or to 4 for an
 * identifier-valued section, matching what the editor writes.  [bake] carries whatever it cannot
 * synthesize from a reference container; [bakeFresh] synthesizes everything and needs none.
 * Neither reproduces the editor's byte layout exactly, which is not required for a runtime-valid
 * file.  The semantic lowering (model → section arrays) is the other half.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §4, §7</a>
 */
public object MocEncoder {
	private const val HEADER_SIZE: Int = 64
	private val MAGIC: ByteArray = byteArrayOf(0x4D, 0x4F, 0x43, 0x33) // "MOC3"
	private const val SECTION_ALIGN: Int = 64

	/** Identifier-valued sections align to 4 rather than 64; see [sectionAlignments]. */
	private const val ID_SECTION_ALIGN: Int = 4
	private const val BUFFER_ALIGN: Int = 64

	/**
	 * Rounds [value] up to the next multiple of [alignment].
	 *
	 * @param Int value     The value to align.
	 * @param Int alignment The alignment boundary (e.g. 64).
	 * @return Int The smallest multiple of [alignment] that is `>= value`.
	 */
	private fun align(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

	/**
	 * Frames [sections] (in table-index order) into a `.moc3` for the given [versionByte].
	 *
	 * @param Int versionByte The moc version byte (1..6).
	 * @param Boolean isBigEndian The endian flag (false for every shipped file).
	 * @param List sections Section payloads, in table order; section k lands at `table[k]`.
	 * @return ByteArray The complete `.moc3` bytes.
	 */
	public fun encode(versionByte: Int, isBigEndian: Boolean, sections: List<ByteArray>): ByteArray {
		val sectionTotal = sections.size
		// The runtime reads a fixed-size offset table; reserve the editor's region for the version so
		// it never reads past the live offsets into section data (0x7C0 for moc <=5, 0x16C0 for moc 6).
		val headerRegion = if (versionByte >= 6) 0x16C0 else 0x7C0
		require(HEADER_SIZE + sectionTotal * 4 <= headerRegion) {
			"offset table ($sectionTotal entries) overruns the $headerRegion-byte header region"
		}
		val firstOffset = headerRegion
		val alignmentByIndex = sectionAlignments(versionByte, sectionTotal)
		val offsets = IntArray(sectionTotal)
		var cursor = firstOffset
		for (sectionIndex in 0 until sectionTotal) {
			// A section's START is aligned per ITS OWN rule, so the padding belongs to the section about to
			// be placed rather than the one just written.  Every section (even an empty one) takes the
			// running cursor; the runtime indexes by table slot and tolerates equal offsets for empties.
			cursor = align(cursor, alignmentByIndex[sectionIndex])
			offsets[sectionIndex] = cursor
			cursor += sections[sectionIndex].size
		}
		val writer = LittleEndianWriter(align(cursor, BUFFER_ALIGN))
		writer.writeBytes(MAGIC) // MOC3 header: magic "MOC3" @ +0x00
		writer.writeU8(versionByte) // MOC3 header: version @ +0x04
		writer.writeU8(if (isBigEndian) 1 else 0) // MOC3 header: endian flag @ +0x05
		writer.zeroPad(HEADER_SIZE - writer.position) // reserved @ +0x06
		for (offset in offsets) writer.writeInt32(offset) // MOC3 offset table @ +0x40
		writer.zeroPad(firstOffset - writer.position) // pad the table region to the data region
		for (sectionIndex in 0 until sectionTotal) {
			// Pad up to the offset the table already promises for this section.
			writer.zeroPad(offsets[sectionIndex] - writer.position)
			writer.writeBytes(sections[sectionIndex])
		}
		writer.alignTo(BUFFER_ALIGN)
		return writer.toByteArray()
	}

	/**
	 * The start alignment each section index requires, for a given moc version.
	 *
	 * Identifier-valued sections align to 4 bytes and everything else to 64.  Over-aligning would be
	 * legal (64 satisfies 4), so this is not a correctness fix - it matches the shape the editor
	 * writes, which is the standing tie-breaker whenever our convenience and its bytes disagree.
	 *
	 * @param Int versionByte  The moc version byte.
	 * @param Int sectionTotal How many table slots to describe.
	 * @return IntArray The per-index alignment.
	 */
	private fun sectionAlignments(versionByte: Int, sectionTotal: Int): IntArray {
		val alignments = IntArray(sectionTotal) { SECTION_ALIGN }
		// An unrecognized version gets the conservative all-64 layout rather than a wrong guess.
		val version = MocVersion.entries.firstOrNull { it.byteValue == versionByte } ?: return alignments
		for (section in Section.entries) {
			if (section.element != ElementType.ID) {
				continue
			}
			val index = section.indexIn(version)
			if (index in 0 until sectionTotal) {
				alignments[index] = ID_SECTION_ALIGN
			}
		}
		return alignments
	}

	/**
	 * Re-frames an existing [MocModel]'s sections into a compact valid `.moc3` (a "repack"). Useful to
	 * validate the serializer independent of the semantic lowering: the runtime accepts it and reads
	 * the same model, though the byte layout differs from the original (use
	 * [org.umamo.format.moc3.moc.MocCodec.write] for a byte-identical re-emit).
	 *
	 * @param MocModel model A parsed container.
	 * @return ByteArray The repacked `.moc3` bytes.
	 */
	public fun repack(model: MocModel): ByteArray =
		encode(model.versionByte, model.isBigEndian, model.sectionBytesInOrder())

	/**
	 * Bakes a (possibly-edited) [doc] to `.moc3` bytes, synthesizing every section [synthesize]'s
	 * producers cover and carrying anything they do not from [reference].  That carry-through is empty
	 * for every corpus model (`MocBakeFreshCoverageTest` pins the carried set at nothing), so it stands
	 * against a section some later format version adds rather than against one we cannot derive.  The
	 * result is runtime-valid; for an unedited document the synthesized sections are byte-exact, so it
	 * matches the original data.
	 *
	 * @param MocModel reference The decoded source providing the carried sections + version.
	 * @param MocDocument doc The (editable) semantic model to bake.
	 * @return ByteArray The `.moc3` bytes.
	 */
	public fun bake(reference: MocModel, doc: MocDocument): ByteArray {
		val synthesized = synthesize(doc)
		// The reference's table can be SHORTER than the version defines (a stripped or hand-built
		// container); pinning the output to it would silently drop synthesized sections past its end.
		val tableSize = maxOf(reference.sectionCount, sectionCount(reference.version))
		val sections =
			List(tableSize) { index ->
				synthesized[index] ?: (reference.section(index) ?: ByteArray(0))
			}
		return encode(reference.versionByte, reference.isBigEndian, sections)
	}

	/**
	 * Bakes [doc] with NO reference container: every section is synthesized from the object model.
	 *
	 * This is what a document that did not come from a `.moc3` needs - there is nothing to carry from.
	 * Sections the version does not define are emitted empty.
	 *
	 * @param MocVersion  version     The moc version to emit; must match [doc]'s own.
	 * @param MocDocument doc         The semantic model.
	 * @param Boolean     isBigEndian The endian flag (false for every shipped file).
	 * @return ByteArray The `.moc3` bytes.
	 */
	public fun bakeFresh(version: MocVersion, doc: MocDocument, isBigEndian: Boolean = false): ByteArray {
		// Loud rather than subtly wrong: lowering a document at a version other than its own would emit
		// sections gated for one version with values shaped for another.
		require(version == doc.version) { "bakeFresh version $version does not match the document's ${doc.version}" }
		val synthesized = synthesize(doc)
		val sections = List(sectionCount(version)) { index -> synthesized[index] ?: ByteArray(0) }
		return encode(version.byteValue, isBigEndian, sections)
	}

	/**
	 * The section indices [bakeFresh] can produce for [doc] - the coverage a completeness gate checks.
	 *
	 * An index missing here is one a bake could only carry from a reference container, which is exactly
	 * what makes a document un-bakeable from scratch.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Set<Int> The produced section indices.
	 */
	public fun bakeFreshCoverage(doc: MocDocument): Set<Int> = synthesize(doc).keys

	/**
	 * Every section the lowering can synthesize for [doc], keyed by section-table index.
	 *
	 * @param MocDocument doc The semantic model.
	 * @return Map Section index → element-region bytes.
	 */
	private fun synthesize(doc: MocDocument): Map<Int, ByteArray> = MocLowering.lower(doc)

	/**
	 * The number of section-table entries the editor emits for a moc [version]. The runtime indexes the
	 * table by fixed section index and never reads its length, so this only needs to be ≥ the highest
	 * index used; matching the editor keeps the file shape conventional.
	 *
	 * Confirmed against samples for v1 (101), v3 (102), v4 (137), v5 (152), and v6 (167); v2 (102) is
	 * the one unsampled version and follows the editor's version gates (+1 at 3.3, +35 at 4.2, +15 at 5.0).
	 *
	 * Matched exhaustively over [MocVersion] on purpose: with an `else` branch a newly added version
	 * would silently inherit the previous one's table length, which is a wrong file rather than a
	 * build error.
	 *
	 * @param MocVersion version The moc version.
	 * @return Int The section count.
	 */
	public fun sectionCount(version: MocVersion): Int =
		when (version) {
			MocVersion.V30 -> 101
			MocVersion.V33, MocVersion.V40 -> 102
			MocVersion.V42 -> 137
			MocVersion.V50 -> 152
			MocVersion.V53 -> 167
		}
}

/**
 * The section payloads in table-index order (internal accessor for the encoder). Absent sections
 * become empty arrays so the list is dense and index-aligned with the offset table.
 *
 * @return List<ByteArray> Each section's raw bytes, in table order.
 */
internal fun MocModel.sectionBytesInOrder(): List<ByteArray> = List(sectionCount) { section(it) ?: ByteArray(0) }