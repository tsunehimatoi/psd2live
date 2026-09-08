package org.umamo.format.moc3.moc

import org.umamo.format.moc3.io.LittleEndianReader

/** Canvas dimensions and origin (section [Section.CANVAS], `f32[6]`). Pixel units. */
public data class CanvasInfo(
	val pixelsPerUnit: Float,
	val originX: Float,
	val originY: Float,
	val width: Float,
	val height: Float,
)

/**
 * A model parameter (slider). [type] is null on MOC3 versions without `Parameter.Types` (< 4).
 * [repeats] is the Cubism 5.3+ "Parameter repeat flags" (section 54): true wraps the value into
 * `[minimumValue, maximumValue)` instead of clamping.
 */
public data class MocParameter(
	val id: String,
	val minimumValue: Float,
	val maximumValue: Float,
	val defaultValue: Float,
	val type: ParameterType?,
	val repeats: Boolean = false,
)

/** A part (visibility/draw-order group). [parentPartIndex] is -1 when the part is at the root. */
public data class MocPart(
	val id: String,
	val parentPartIndex: Int,
)

/** A drawable (ArtMesh). [constantFlags] is the [ConstantFlag] bitmask. */
public data class MocDrawable(
	val id: String,
	val textureIndex: Int,
	val constantFlags: Int,
	val vertexCount: Int,
	val indexCount: Int,
	val maskCount: Int,
	val parentPartIndex: Int,
)

/**
 * A parsed `.moc3`: the 64-byte header, the section-offset table, and each section's raw bytes.
 *
 * The model retains every section verbatim (the slice `[offset[k], offset[k+1])`, last → EOF,
 * including padding) so [org.umamo.format.moc3.moc.MocCodec.write] reproduces an unedited file
 * byte identical.  A handful of structural sections (counts, canvas, IDs, parameters, parts,
 * drawables) are exposed here as typed accessors for callers that need only the shape of a model;
 * every section, deformation payload included, is reachable typed through [sections].
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md</a>
 */
public class MocModel internal constructor(
	/** The 64-byte file header `[0, 0x40)`, preserved verbatim. */
	public val header: ByteArray,
	/** Absolute file offset of each section, in table order (length == section count). */
	public val offsets: IntArray,
	/** Raw bytes of each section, in table order; section k == original `[offsets[k], offsets[k+1])`. */
	internal val rawSections: Array<ByteArray>,
) {
	/** Typed Layer-1 access to the deformation sections (deformers, keyforms, glue, colors, …). */
	public val sections: MocSections by lazy { MocSections(this) }

	/** The raw version byte at offset 0x04. */
	public val versionByte: Int get() = header[4].toInt() and 0xFF // MOC3 header: version @ +0x04

	/** The typed format version. */
	public val version: MocVersion get() = MocVersion.fromByte(versionByte)

	/** Whether the file declares big-endian byte order (offset 0x05); false for every shipped file. */
	public val isBigEndian: Boolean get() = header[5].toInt() != 0 // MOC3 header: endian flag @ +0x05

	/** Number of sections present in the offset table. */
	public val sectionCount: Int get() = offsets.size

	/**
	 * Returns the raw bytes of section [index] (including its trailing padding), or null if absent.
	 *
	 * @param Int index A section-table index (a [Section]'s [Section.indexIn] for this version).
	 * @return ByteArray? The section bytes, or null when the section is not present.
	 */
	public fun section(index: Int): ByteArray? = rawSections.getOrNull(index)

	/**
	 * The CountInfo block (section 0) decoded as a `u32[]` (indexable with `Sections.CI_*`).
	 *
	 * Read by raw index rather than through [sections], because every `PER_*` sizing rule in
	 * [MocSections] reads THIS - routing it back through the typed accessor would be circular.
	 */
	public val countInfo: IntArray by lazy {
		val raw = section(Section.COUNT_INFO.indexIn(version)) ?: return@lazy IntArray(0)
		val reader = LittleEndianReader(raw)
		IntArray(raw.size / 4) { reader.readInt32() }
	}

	/**
	 * Reads one count from the CountInfo block, defaulting to 0 when the index is absent.
	 *
	 * @param Int index A `Sections.CI_*` field index into [countInfo].
	 * @return Int The count, or 0 when out of range.
	 */
	private fun count(index: Int): Int = countInfo.getOrElse(index) { 0 }

	/** Number of parts. */
	public val partCount: Int get() = count(Sections.CI_PARTS)

	/** Number of drawables (ArtMeshes). */
	public val drawableCount: Int get() = count(Sections.CI_DRAWABLES)

	/** Number of parameters. */
	public val parameterCount: Int get() = count(Sections.CI_PARAMETERS)

	/** Number of deformers (warp + rotation). */
	public val deformerCount: Int get() = count(Sections.CI_DEFORMERS)

	/** Number of glue (affecter) entries. */
	public val glueCount: Int get() = count(Sections.CI_GLUES)

	/** The canvas dimensions/origin, or null if the canvas section is absent. */
	public val canvasInfo: CanvasInfo? by lazy {
		// The file stores f32[6]; the sixth is a trailing zero the model does not carry.
		val values = sections.floatArray(Section.CANVAS)
		if (values.size < 5) {
			return@lazy null
		}
		CanvasInfo(
			pixelsPerUnit = values[0],
			originX = values[1],
			originY = values[2],
			width = values[3],
			height = values[4],
		)
	}

	// The three accessors below pad a short or absent section out to [elementCount] rather than
	// returning what the file happens to hold.  The callers index positionally by object, so a
	// stripped or truncated section must read as zeros/blanks instead of throwing - the same
	// defensive contract MocDecoder relies on for the object blocks' common heads.

	/**
	 * Reads [section] as a packed `i32[elementCount]`, zero-filling anything the file omits.
	 *
	 * @param Section section      An I32 section.
	 * @param Int     elementCount Number of 32-bit integers the caller will index.
	 * @return IntArray The decoded values (length == [elementCount]).
	 */
	private fun ints(section: Section, elementCount: Int): IntArray {
		val decoded = sections.intArray(section)
		return if (decoded.size == elementCount) decoded else decoded.copyOf(elementCount)
	}

	/**
	 * Reads [section] as a packed `f32[elementCount]`, zero-filling anything the file omits.
	 *
	 * @param Section section      An F32 section.
	 * @param Int     elementCount Number of 32-bit floats the caller will index.
	 * @return FloatArray The decoded values (length == [elementCount]).
	 */
	private fun floats(section: Section, elementCount: Int): FloatArray {
		val decoded = sections.floatArray(section)
		return if (decoded.size == elementCount) decoded else decoded.copyOf(elementCount)
	}

	/**
	 * Reads [section] as [elementCount] fixed-width ID records, blank-filling anything the file omits.
	 *
	 * @param Section section      An ID section.
	 * @param Int     elementCount Number of ID records the caller will index.
	 * @return List<String> The decoded IDs (length == [elementCount]).
	 */
	private fun idStrings(section: Section, elementCount: Int): List<String> {
		val decoded = sections.idArray(section)
		return if (decoded.size == elementCount) decoded else List(elementCount) { decoded.getOrElse(it) { "" } }
	}

	/** The parameters, in file order. */
	public fun parameters(): List<MocParameter> {
		val parameterCount = parameterCount
		val ids = idStrings(Section.PARAM_ID, parameterCount)
		val maxima = floats(Section.PARAM_MAX, parameterCount)
		val minima = floats(Section.PARAM_MIN, parameterCount)
		val defaults = floats(Section.PARAM_DEFAULT, parameterCount)
		val repeats = ints(Section.PARAM_REPEAT, parameterCount)
		// Absent before moc 4, so a null types array means "every parameter is normal".
		val types = if (sections.isPresent(Section.PARAM_TYPE)) ints(Section.PARAM_TYPE, parameterCount) else null
		return List(parameterCount) { parameterIndex ->
			MocParameter(
				ids[parameterIndex],
				minima[parameterIndex],
				maxima[parameterIndex],
				defaults[parameterIndex],
				types?.let { ParameterType.fromNumber(it[parameterIndex]) },
				repeats[parameterIndex] != 0,
			)
		}
	}

	/** The parts, in file order. */
	public fun parts(): List<MocPart> {
		val partCount = partCount
		val ids = idStrings(Section.PART_ID, partCount)
		val parents = ints(Section.PART_PARENT, partCount)
		return List(partCount) { partIndex -> MocPart(ids[partIndex], parents[partIndex]) }
	}

	/** The drawables (ArtMeshes), in file order. */
	public fun drawables(): List<MocDrawable> {
		val drawableCount = drawableCount
		val ids = idStrings(Section.ARTMESH_ID, drawableCount)
		val textures = ints(Section.ARTMESH_TEXTURE, drawableCount)
		val vertexCounts = ints(Section.ARTMESH_VERTEX_COUNT, drawableCount)
		val indexCounts = ints(Section.ARTMESH_INDEX_COUNT, drawableCount)
		val maskCounts = ints(Section.ARTMESH_MASK_COUNT, drawableCount)
		val parents = ints(Section.ARTMESH_PARENT_PART, drawableCount)
		// One byte per drawable, not an i32; byteArray already pads to the drawable count.
		val flagsRaw = sections.byteArray(Section.ARTMESH_CONSTANT_FLAGS)
		return List(drawableCount) { drawableIndex ->
			MocDrawable(
				id = ids[drawableIndex],
				textureIndex = textures[drawableIndex],
				constantFlags = (flagsRaw.getOrNull(drawableIndex)?.toInt() ?: 0) and 0xFF,
				vertexCount = vertexCounts[drawableIndex],
				indexCount = indexCounts[drawableIndex],
				maskCount = maskCounts[drawableIndex],
				parentPartIndex = parents[drawableIndex],
			)
		}
	}
}