// Ported from TwelveMonkeys common-io PackBitsDecoder (BSD-3-Clause, Copyright (c) 2008 Harald Kuhr).
// Apple PackBits run-length decode; see CREDITS.md.

package org.umamo.format.tiff

/**
 * Decodes an Apple PackBits (TIFF compression 32773) run-length stream into [expectedSize] bytes.
 *
 * A control byte n: 0..127 copies the next n+1 bytes literally; -1..-127 repeats the next byte 1-n
 * times; -128 is a no-op.  All reads and writes are bounded so a corrupt run cannot overrun.
 *
 * @param ByteArray input     The compressed strip/tile bytes.
 * @param Int expectedSize     The decompressed byte count for this strip/tile.
 * @return ByteArray The decompressed bytes (zero-padded if the input runs short).
 */
internal fun decodePackBits(input: ByteArray, expectedSize: Int): ByteArray {
	val out = ByteArray(expectedSize)
	var source = 0
	var destination = 0
	while (destination < expectedSize && source < input.size) {
		val control = input[source++].toInt() // signed
		if (control >= 0) {
			val literalCount = control + 1
			var copied = 0
			while (copied < literalCount && destination < expectedSize && source < input.size) {
				out[destination++] = input[source++]
				copied++
			}
		} else if (control != -128) {
			val runCount = 1 - control
			if (source >= input.size) {
				break
			}
			val runValue = input[source++]
			var written = 0
			while (written < runCount && destination < expectedSize) {
				out[destination++] = runValue
				written++
			}
		}
		// control == -128 is a no-op.
	}
	return out
}