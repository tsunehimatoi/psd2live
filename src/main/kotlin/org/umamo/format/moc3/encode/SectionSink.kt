package org.umamo.format.moc3.encode

import org.umamo.format.moc3.io.LittleEndianWriter
import org.umamo.format.moc3.moc.ElementType
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import org.umamo.format.moc3.moc.Sections

/**
 * Collects a lowering producer's output as section index → element-region bytes.
 *
 * Owns the two things every producer would otherwise repeat: resolving a [Section] to its table index
 * for the target version, and skipping a section the version does not define (a negative index).  The
 * typed putters encode as they store, so a call site names the section and the values and nothing else.
 *
 * Each typed putter checks the section's declared [ElementType], which is what stops an `i32[]` column
 * from being written into an `f32[]` section - the section table is the only record of a section's
 * element width, and a mismatch there produces a file the runtime reads at the wrong stride.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
internal class SectionSink(private val version: MocVersion) {
	private val sections = LinkedHashMap<Int, ByteArray>()

	/**
	 * The collected sections, keyed by section-table index.
	 *
	 * @return Map Section index → element-region bytes.
	 */
	fun toMap(): Map<Int, ByteArray> = sections

	/**
	 * Stores [bytes] verbatim under [section], if the version defines it.
	 *
	 * The unchecked escape hatch: it carries pre-packed blocks (the keyform position values are already
	 * a little-endian float run) as readily as genuine `u8[]` columns, so it deliberately does not
	 * check the element type.  Prefer a typed putter wherever the values are still typed.
	 *
	 * @param Section   section The section to write.
	 * @param ByteArray bytes   The element-region bytes.
	 */
	fun putBytes(section: Section, bytes: ByteArray) {
		val index = section.indexIn(version)
		if (index >= 0) {
			sections[index] = bytes
		}
	}

	/**
	 * Stores a zero-filled region of [byteCount] bytes under [section].
	 *
	 * @param Section section   The section to write.
	 * @param Int     byteCount How many zero bytes the region spans.
	 */
	fun putZeros(section: Section, byteCount: Int): Unit = putBytes(section, ByteArray(byteCount))

	/**
	 * Encodes [values] as a packed little-endian `i32[]` under [section].
	 *
	 * @param Section   section The section to write.
	 * @param List<Int> values  The integers, in element order.
	 */
	fun putInts(section: Section, values: List<Int>) {
		requireElement(section, ElementType.I32, ElementType.U32)
		val writer = LittleEndianWriter(values.size * 4)
		values.forEach(writer::writeInt32)
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Encodes [values] as a packed little-endian `i32[]` under [section].
	 *
	 * @param Section  section The section to write.
	 * @param IntArray values  The integers, in element order.
	 */
	fun putInts(section: Section, values: IntArray) {
		requireElement(section, ElementType.I32, ElementType.U32)
		val writer = LittleEndianWriter(values.size * 4)
		values.forEach(writer::writeInt32)
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Encodes [values] as a packed little-endian `f32[]` under [section].
	 *
	 * @param Section     section The section to write.
	 * @param List<Float> values  The floats, in element order.
	 */
	fun putFloats(section: Section, values: List<Float>) {
		requireElement(section, ElementType.F32)
		val writer = LittleEndianWriter(values.size * 4)
		values.forEach(writer::writeFloat32)
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Encodes [values] as a packed little-endian `f32[]` under [section].
	 *
	 * @param Section section The section to write.
	 * @param Float   values  The floats, in element order.
	 */
	fun putFloats(section: Section, vararg values: Float) {
		requireElement(section, ElementType.F32)
		val writer = LittleEndianWriter(values.size * 4)
		values.forEach(writer::writeFloat32)
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Encodes [values] as a packed little-endian `i16[]` under [section].
	 *
	 * @param Section     section The section to write.
	 * @param List<Short> values  The shorts, in element order.
	 */
	fun putShorts(section: Section, values: List<Short>) {
		requireElement(section, ElementType.I16)
		val writer = LittleEndianWriter(values.size * 2)
		values.forEach { shortValue -> writer.writeU16(shortValue.toInt()) }
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Encodes [identifiers] as fixed [Sections.ID_STRIDE]-byte NUL-terminated, zero-padded id records.
	 *
	 * @param Section      section     The section to write.
	 * @param List<String> identifiers The identifiers, in element order.
	 */
	fun putIds(section: Section, identifiers: List<String>) {
		requireElement(section, ElementType.ID)
		val writer = LittleEndianWriter(identifiers.size * Sections.ID_STRIDE)
		for (identifier in identifiers) {
			writer.writeFixedString(identifier, Sections.ID_STRIDE)
		}
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Concatenates each item's selected `f32[]` into one packed block under [section].
	 *
	 * @param Section            section The section to write.
	 * @param List<T>            items   The items to draw arrays from.
	 * @param (T) -> FloatArray  select  The per-item float array selector.
	 */
	fun <T> putFloatConcat(section: Section, items: List<T>, select: (T) -> FloatArray) {
		requireElement(section, ElementType.F32)
		val writer = LittleEndianWriter(items.sumOf { select(it).size } * 4)
		for (item in items) {
			select(item).forEach(writer::writeFloat32)
		}
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Concatenates each item's selected `i16[]` into one packed block under [section].
	 *
	 * @param Section            section The section to write.
	 * @param List<T>            items   The items to draw arrays from.
	 * @param (T) -> ShortArray  select  The per-item short array selector.
	 */
	fun <T> putShortConcat(section: Section, items: List<T>, select: (T) -> ShortArray) {
		requireElement(section, ElementType.I16)
		val writer = LittleEndianWriter(items.sumOf { select(it).size } * 2)
		for (item in items) {
			select(item).forEach { shortValue -> writer.writeU16(shortValue.toInt()) }
		}
		putBytes(section, writer.toByteArray())
	}

	/**
	 * Fails when [section] does not declare one of [allowed] as its element type.
	 *
	 * @param Section     section The section being written.
	 * @param ElementType allowed The element types this putter encodes.
	 */
	private fun requireElement(section: Section, vararg allowed: ElementType) {
		require(section.element in allowed) {
			"$section is a ${section.element} section; cannot write it as ${allowed.joinToString("/")}"
		}
	}
}