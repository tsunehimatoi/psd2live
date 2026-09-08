// Inverse DCT for baseline JPEG: the accurate integer ("islow") variant from the Independent JPEG
// Group's jidctint.c, a slow-but-exact 13-bit fixed-point implementation of the AAN-style separable
// IDCT described in T.81 Annex A.3.3.  Implemented faithfully because the fixed-point rounding IS the
// output: libjpeg (and libjpeg-turbo, whose SIMD path is bit-exact with this C code) produce these
// exact samples, so a faithful port decodes byte-identically to every mainstream JPEG reader.

package org.umamo.format.jpeg

/**
 * The 8x8 inverse DCT, integer "islow" variant.
 *
 * Pass 1 transforms columns into a workspace scaled up by 2^PASS1_BITS; pass 2 transforms rows and
 * descales, level-shifts by +128, and clamps to 0..255.
 */
internal object JpegIdct {
	// Fixed-point precision.  CONST_BITS is the multiplier scale; PASS1_BITS the inter-pass headroom.
	private const val CONST_BITS = 13
	private const val PASS1_BITS = 2

	// Cosine multipliers, each round(value * 2^CONST_BITS).  Names carry the value they approximate.
	private const val FIX_0_298631336 = 2446
	private const val FIX_0_390180644 = 3196
	private const val FIX_0_541196100 = 4433
	private const val FIX_0_765366865 = 6270
	private const val FIX_0_899976223 = 7373
	private const val FIX_1_175875602 = 9633
	private const val FIX_1_501321110 = 12299
	private const val FIX_1_847759065 = 15137
	private const val FIX_1_961570560 = 16069
	private const val FIX_2_053119869 = 16819
	private const val FIX_2_562915447 = 20995
	private const val FIX_3_072711026 = 25172

	/**
	 * Rounds a fixed-point value down by [shift] bits, rounding half away from zero-ward as libjpeg does.
	 *
	 * @param Int value The scaled value.
	 * @param Int shift The number of fractional bits to drop.
	 * @return Int The descaled value.
	 */
	private fun descale(value: Int, shift: Int): Int = (value + (1 shl (shift - 1))) shr shift

	/**
	 * Performs the inverse DCT of one block, writing level-shifted, clamped 8-bit samples.
	 *
	 * @param IntArray coefficients   64 dequantized coefficients in natural (row-major) order.
	 * @param IntArray workspace      A scratch buffer of at least 64 ints, reused across calls.
	 * @param ByteArray output        The destination plane.
	 * @param Int outputOffset        Index in [output] of the block's top-left sample.
	 * @param Int outputStride        Distance in [output] between vertically adjacent samples.
	 */
	fun inverse(coefficients: IntArray, workspace: IntArray, output: ByteArray, outputOffset: Int, outputStride: Int) {
		// Pass 1: columns.  Results are scaled up by sqrt(8) * 2^PASS1_BITS relative to a true IDCT.
		for (column in 0 until 8) {
			// Most blocks have all-zero AC terms in a column; that column is then a flat DC value.
			if (coefficients[column + 8] == 0 &&
				coefficients[column + 16] == 0 &&
				coefficients[column + 24] == 0 &&
				coefficients[column + 32] == 0 &&
				coefficients[column + 40] == 0 &&
				coefficients[column + 48] == 0 &&
				coefficients[column + 56] == 0
			) {
				val dcValue = coefficients[column] shl PASS1_BITS
				for (row in 0 until 8) {
					workspace[column + row * 8] = dcValue
				}
				continue
			}

			// Even part: the rotator is sqrt(2) * c(-6).
			var z2 = coefficients[column + 16]
			var z3 = coefficients[column + 48]
			var z1 = (z2 + z3) * FIX_0_541196100
			var tmp2 = z1 + z3 * -FIX_1_847759065
			var tmp3 = z1 + z2 * FIX_0_765366865

			z2 = coefficients[column]
			z3 = coefficients[column + 32]
			var tmp0 = (z2 + z3) shl CONST_BITS
			var tmp1 = (z2 - z3) shl CONST_BITS

			val tmp10 = tmp0 + tmp3
			val tmp13 = tmp0 - tmp3
			val tmp11 = tmp1 + tmp2
			val tmp12 = tmp1 - tmp2

			// Odd part: i0..i3 are coefficients 7, 5, 3, 1.
			tmp0 = coefficients[column + 56]
			tmp1 = coefficients[column + 40]
			tmp2 = coefficients[column + 24]
			tmp3 = coefficients[column + 8]

			z1 = tmp0 + tmp3
			z2 = tmp1 + tmp2
			z3 = tmp0 + tmp2
			var z4 = tmp1 + tmp3
			val z5 = (z3 + z4) * FIX_1_175875602 // sqrt(2) * c3

			tmp0 *= FIX_0_298631336 // sqrt(2) * (-c1+c3+c5-c7)
			tmp1 *= FIX_2_053119869 // sqrt(2) * ( c1+c3-c5+c7)
			tmp2 *= FIX_3_072711026 // sqrt(2) * ( c1+c3+c5-c7)
			tmp3 *= FIX_1_501321110 // sqrt(2) * ( c1+c3-c5-c7)
			z1 *= -FIX_0_899976223 // sqrt(2) * (c7-c3)
			z2 *= -FIX_2_562915447 // sqrt(2) * (-c1-c3)
			z3 *= -FIX_1_961570560 // sqrt(2) * (-c3-c5)
			z4 *= -FIX_0_390180644 // sqrt(2) * (c5-c3)

			z3 += z5
			z4 += z5

			tmp0 += z1 + z3
			tmp1 += z2 + z4
			tmp2 += z2 + z3
			tmp3 += z1 + z4

			workspace[column] = descale(tmp10 + tmp3, CONST_BITS - PASS1_BITS)
			workspace[column + 56] = descale(tmp10 - tmp3, CONST_BITS - PASS1_BITS)
			workspace[column + 8] = descale(tmp11 + tmp2, CONST_BITS - PASS1_BITS)
			workspace[column + 48] = descale(tmp11 - tmp2, CONST_BITS - PASS1_BITS)
			workspace[column + 16] = descale(tmp12 + tmp1, CONST_BITS - PASS1_BITS)
			workspace[column + 40] = descale(tmp12 - tmp1, CONST_BITS - PASS1_BITS)
			workspace[column + 24] = descale(tmp13 + tmp0, CONST_BITS - PASS1_BITS)
			workspace[column + 32] = descale(tmp13 - tmp0, CONST_BITS - PASS1_BITS)
		}

		// Pass 2: rows.  Descale by the pass-1 scale plus the IDCT's own factor of 8 (2^3).
		for (row in 0 until 8) {
			val rowBase = row * 8
			val outputRow = outputOffset + row * outputStride

			// Pass 1 fills in AC terms, so the all-zero shortcut hits far less often here, but it is cheap.
			if (workspace[rowBase + 1] == 0 &&
				workspace[rowBase + 2] == 0 &&
				workspace[rowBase + 3] == 0 &&
				workspace[rowBase + 4] == 0 &&
				workspace[rowBase + 5] == 0 &&
				workspace[rowBase + 6] == 0 &&
				workspace[rowBase + 7] == 0
			) {
				val dcSample = clampToSample(descale(workspace[rowBase], PASS1_BITS + 3))
				for (column in 0 until 8) {
					output[outputRow + column] = dcSample
				}
				continue
			}

			// Even part: the rotator is sqrt(2) * c(-6).
			var z2 = workspace[rowBase + 2]
			var z3 = workspace[rowBase + 6]
			var z1 = (z2 + z3) * FIX_0_541196100
			var tmp2 = z1 + z3 * -FIX_1_847759065
			var tmp3 = z1 + z2 * FIX_0_765366865

			var tmp0 = (workspace[rowBase] + workspace[rowBase + 4]) shl CONST_BITS
			var tmp1 = (workspace[rowBase] - workspace[rowBase + 4]) shl CONST_BITS

			val tmp10 = tmp0 + tmp3
			val tmp13 = tmp0 - tmp3
			val tmp11 = tmp1 + tmp2
			val tmp12 = tmp1 - tmp2

			// Odd part: i0..i3 are coefficients 7, 5, 3, 1.
			tmp0 = workspace[rowBase + 7]
			tmp1 = workspace[rowBase + 5]
			tmp2 = workspace[rowBase + 3]
			tmp3 = workspace[rowBase + 1]

			z1 = tmp0 + tmp3
			z2 = tmp1 + tmp2
			z3 = tmp0 + tmp2
			var z4 = tmp1 + tmp3
			val z5 = (z3 + z4) * FIX_1_175875602 // sqrt(2) * c3

			tmp0 *= FIX_0_298631336
			tmp1 *= FIX_2_053119869
			tmp2 *= FIX_3_072711026
			tmp3 *= FIX_1_501321110
			z1 *= -FIX_0_899976223
			z2 *= -FIX_2_562915447
			z3 *= -FIX_1_961570560
			z4 *= -FIX_0_390180644

			z3 += z5
			z4 += z5

			tmp0 += z1 + z3
			tmp1 += z2 + z4
			tmp2 += z2 + z3
			tmp3 += z1 + z4

			output[outputRow] = clampToSample(descale(tmp10 + tmp3, CONST_BITS + PASS1_BITS + 3))
			output[outputRow + 7] = clampToSample(descale(tmp10 - tmp3, CONST_BITS + PASS1_BITS + 3))
			output[outputRow + 1] = clampToSample(descale(tmp11 + tmp2, CONST_BITS + PASS1_BITS + 3))
			output[outputRow + 6] = clampToSample(descale(tmp11 - tmp2, CONST_BITS + PASS1_BITS + 3))
			output[outputRow + 2] = clampToSample(descale(tmp12 + tmp1, CONST_BITS + PASS1_BITS + 3))
			output[outputRow + 5] = clampToSample(descale(tmp12 - tmp1, CONST_BITS + PASS1_BITS + 3))
			output[outputRow + 3] = clampToSample(descale(tmp13 + tmp0, CONST_BITS + PASS1_BITS + 3))
			output[outputRow + 4] = clampToSample(descale(tmp13 - tmp0, CONST_BITS + PASS1_BITS + 3))
		}
	}

	/**
	 * Applies the JPEG level shift (+128) and clamps to an 8-bit sample.
	 *
	 * libjpeg reads a precomputed range-limit table here; for in-range coefficients that table is
	 * exactly this clamp, and out-of-range values only arise from corrupt data.
	 *
	 * @param Int value The descaled, zero-centered IDCT output.
	 * @return Byte The 0..255 sample.
	 */
	private fun clampToSample(value: Int): Byte {
		val shifted = value + 128
		return when {
			shifted < 0 -> 0
			shifted > 255 -> 255.toByte()
			else -> shifted.toByte()
		}
	}
}