package org.umamo.format.moc3.moc

import org.umamo.format.moc3.io.LittleEndianReader
import org.umamo.format.moc3.io.LittleEndianWriter

/**
 * Typed read access to every [Section] of a [MocModel] (the "Layer-1" view): each section as a flat
 * array of its element type, sized per its [Sizing] rule.
 *
 * Per-object sections (`PER_*`) are read for their CountInfo count, capped at what the slice holds
 * (see [elementCount]); `TABLE` sections are read across the whole section slice (lossless -
 * trailing padding becomes trailing zero elements the semantic layer never dereferences).  Reading
 * is non-destructive; the container's byte-identical write is unaffected.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public class MocSections internal constructor(private val model: MocModel) {
	private val version: MocVersion = model.version

	/**
	 * The element count for a section with the given [sizing] rule (its CountInfo field), or -1 for a
	 * [Sizing.TABLE] section (whose count comes from the slice size, not CountInfo).
	 *
	 * @param Sizing sizing The section's sizing rule.
	 * @return Int The per-object count, or -1 for table sections.
	 */
	private fun count(sizing: Sizing): Int =
		when (sizing) {
			Sizing.PER_PART -> ci(Sections.CI_PARTS)
			Sizing.PER_DEFORMER -> ci(Sections.CI_DEFORMERS)
			Sizing.PER_WARP -> ci(Sections.CI_WARPS)
			Sizing.PER_ROTATION -> ci(Sections.CI_ROTATIONS)
			Sizing.PER_DRAWABLE -> ci(Sections.CI_DRAWABLES)
			Sizing.PER_PARAMETER -> ci(Sections.CI_PARAMETERS)
			Sizing.PER_GLUE -> ci(Sections.CI_GLUES)
			Sizing.PER_RENDER_ORDER_GROUP -> ci(Sections.CI_RENDER_ORDER_GROUPS)
			Sizing.PER_RENDER_ORDER_CHILD -> ci(Sections.CI_RENDER_ORDER_CHILDREN)
			Sizing.PER_OFFSCREEN -> ci(Sections.CI_OFFSCREENS)
			Sizing.PER_BLENDSHAPE_WARP -> ci(Sections.CI_BLENDSHAPE_WARPS)
			Sizing.PER_BLENDSHAPE_MESH -> ci(Sections.CI_BLENDSHAPE_MESHES)
			Sizing.PER_BLENDSHAPE_ROTATION -> ci(Sections.CI_BLENDSHAPE_ROTATIONS)
			Sizing.PER_BLENDSHAPE_PART -> ci(Sections.CI_BLENDSHAPE_PARTS)
			Sizing.PER_BLENDSHAPE_GLUE -> ci(Sections.CI_BLENDSHAPE_GLUES)
			Sizing.PER_PART_FORM -> ci(Sections.CI_PART_FORMS)
			Sizing.PER_WARP_FORM -> ci(Sections.CI_WARP_FORMS)
			Sizing.PER_ROTATION_FORM -> ci(Sections.CI_ROTATION_FORMS)
			Sizing.PER_ARTMESH_FORM -> ci(Sections.CI_ARTMESH_FORMS)
			Sizing.PER_OFFSCREEN_FORM -> ci(Sections.CI_OFFSCREEN_KEYFORMS)
			Sizing.TABLE -> -1
		}

	/**
	 * Reads CountInfo field [index], defaulting to 0 when absent.
	 *
	 * @param Int index A `Sections.CI_*` CountInfo field index.
	 * @return Int The count, or 0 when out of range.
	 */
	private fun ci(index: Int): Int = model.countInfo.getOrElse(index) { 0 }

	/**
	 * The raw section slice backing [section] for this model's version, or null when the section is
	 * absent (version-gated out) or empty.
	 *
	 * @param Section section The section to locate.
	 * @return ByteArray? The section bytes, or null.
	 */
	private fun rawSlice(section: Section): ByteArray? {
		val index = section.indexIn(version)
		if (index < 0) {
			return null
		}
		// A zero-length section carries no data; treat it as absent so PER_* reads don't run past it
		// (a bake emits empty slots for sections it omits, e.g. blend shapes on a stripped model).
		return model.section(index)?.takeIf { it.isNotEmpty() }
	}

	/** Whether [section] is present in this model's version and has a section slice. */
	public fun isPresent(section: Section): Boolean = rawSlice(section) != null

	/**
	 * Number of elements [section] decodes to (CountInfo count, or whole-slice count for tables).
	 *
	 * A per-object count is clamped to what the slice can actually hold.  CountInfo and the
	 * section bytes are independent on disk, so a stripped or truncated file can claim more
	 * objects than it stores; reading the claim would run off the end of the slice and fail the
	 * whole decode, which [org.umamo.format.moc3.decode.MocDecoder] promises not to do for an
	 * absent or partial id/flag section.  Clamping yields a short array instead, which every
	 * caller already tolerates (they index through `getOrElse`).
	 *
	 * @param Section section The section to size.
	 * @return Int The element count, 0 when the section is absent.
	 */
	public fun elementCount(section: Section): Int {
		val slice = rawSlice(section) ?: return 0
		val sliceCapacity = slice.size / section.element.size
		return if (section.sizing == Sizing.TABLE) sliceCapacity else minOf(count(section.sizing), sliceCapacity)
	}

	/**
	 * Reads [section] as an [IntArray] (for I32/U32 sections).
	 *
	 * @param Section section A section whose element type is I32 or U32.
	 * @return IntArray The decoded values (empty if the section is absent).
	 */
	public fun intArray(section: Section): IntArray {
		require(section.element == ElementType.I32 || section.element == ElementType.U32) { "$section is not an int section" }
		val slice = rawSlice(section) ?: return IntArray(0)
		val reader = LittleEndianReader(slice)
		return IntArray(elementCount(section)) { reader.readInt32() }
	}

	/**
	 * Reads [section] as a [FloatArray] (for F32 sections).
	 *
	 * @param Section section A section whose element type is F32.
	 * @return FloatArray The decoded values (empty if the section is absent).
	 */
	public fun floatArray(section: Section): FloatArray {
		require(section.element == ElementType.F32) { "$section is not a float section" }
		val slice = rawSlice(section) ?: return FloatArray(0)
		val reader = LittleEndianReader(slice)
		return FloatArray(elementCount(section)) { reader.readFloat32() }
	}

	/**
	 * Reads [section] as a [ShortArray] (for I16 sections, e.g. glue vertex indices).
	 *
	 * @param Section section A section whose element type is I16.
	 * @return ShortArray The decoded values (empty if the section is absent).
	 */
	public fun shortArray(section: Section): ShortArray {
		require(section.element == ElementType.I16) { "$section is not an i16 section" }
		val slice = rawSlice(section) ?: return ShortArray(0)
		val reader = LittleEndianReader(slice)
		return ShortArray(elementCount(section)) { reader.readU16().toShort() }
	}

	/**
	 * Reads [section] as a [ByteArray] (for U8 sections, e.g. flag bytes).
	 *
	 * @param Section section A section whose element type is U8.
	 * @return ByteArray The decoded bytes (empty if the section is absent).
	 */
	public fun byteArray(section: Section): ByteArray {
		require(section.element == ElementType.U8) { "$section is not a u8 section" }
		val slice = rawSlice(section) ?: return ByteArray(0)
		return slice.copyOf(elementCount(section))
	}

	/**
	 * Reads [section] as fixed-width ID records (for ID sections, e.g. deformer ids).
	 *
	 * @param Section section A section whose element type is ID.
	 * @return List<String> The decoded identifiers (empty if the section is absent).
	 */
	public fun idArray(section: Section): List<String> {
		require(section.element == ElementType.ID) { "$section is not an id section" }
		val slice = rawSlice(section) ?: return emptyList()
		val reader = LittleEndianReader(slice)
		// One source of truth for the record width: ElementType.ID.size IS the id stride, and sizing the
		// walk from anything else lets the two drift and silently misread every id after the first.
		return List(elementCount(section)) { reader.readFixedString(section.element.size) }
	}

	/**
	 * Re-serializes [section]'s element region (the real elements, excluding trailing padding).
	 * Used to validate that the typed decode is lossless against the original bytes.
	 *
	 * @param Section section The section to re-encode.
	 * @return ByteArray The element-region bytes.
	 */
	public fun reencodeElementRegion(section: Section): ByteArray {
		val elementCount = elementCount(section)
		val writer = LittleEndianWriter(elementCount * section.element.size + 8)
		when (section.element) {
			ElementType.I32, ElementType.U32 -> intArray(section).forEach(writer::writeInt32)
			ElementType.F32 -> floatArray(section).forEach(writer::writeFloat32)
			ElementType.I16 -> shortArray(section).forEach { shortValue -> writer.writeU16(shortValue.toInt()) }
			ElementType.U8 -> writer.writeBytes(byteArray(section))
			// Re-encoded from the decoded strings, so this genuinely exercises the id-record walk and the
			// NUL-terminate-then-zero-pad convention rather than comparing raw bytes to themselves.  Every
			// id record in the corpus pads cleanly with zeros after its terminator, which is what makes the
			// round trip byte-exact; a file that padded with garbage would fail here, and should.
			ElementType.ID ->
				idArray(section).forEach { identifier ->
					writer.writeFixedString(identifier, section.element.size)
				}
			// A runtime slot is opaque (zero on disk, filled after the memory-cast), so there is no typed
			// decode to re-serialize from and its element region round-trips as raw bytes.
			ElementType.U64 -> writer.writeBytes(rawElementRegion(section))
		}
		return writer.toByteArray()
	}

	/** The element-region bytes straight from the file (first `elementCount × element.size` bytes). */
	public fun rawElementRegion(section: Section): ByteArray =
		rawSlice(section)?.copyOf(elementCount(section) * section.element.size) ?: ByteArray(0)
}