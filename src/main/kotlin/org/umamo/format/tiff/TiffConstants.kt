// Constant values from TwelveMonkeys imageio-tiff TIFF / TIFFBaseline / TIFFExtension (BSD-3-Clause,
// Copyright (c) 2009-2013 Harald Kuhr).  See CREDITS.md.

package org.umamo.format.tiff

/**
 * TIFF tag ids, field type codes, and the enumerated values (compression, photometric, predictor,
 * planar config, extra samples, sample format) the reader dispatches on.
 */
internal object TiffConstants {
	const val TIFF_MAGIC = 42
	const val BIGTIFF_MAGIC = 43

	// Field type codes and their byte lengths (index = type code; -1 = unused/variable).
	const val TYPE_BYTE = 1
	const val TYPE_ASCII = 2
	const val TYPE_SHORT = 3
	const val TYPE_LONG = 4
	const val TYPE_SBYTE = 6
	const val TYPE_UNDEFINED = 7
	const val TYPE_SSHORT = 8
	const val TYPE_SLONG = 9
	val TYPE_LENGTHS = intArrayOf(-1, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4, -1, -1, 8, 8, 8)

	// Tags the baseline reader consumes.
	const val TAG_IMAGE_WIDTH = 256
	const val TAG_IMAGE_HEIGHT = 257
	const val TAG_BITS_PER_SAMPLE = 258
	const val TAG_COMPRESSION = 259
	const val TAG_PHOTOMETRIC_INTERPRETATION = 262
	const val TAG_FILL_ORDER = 266
	const val TAG_STRIP_OFFSETS = 273
	const val TAG_SAMPLES_PER_PIXEL = 277
	const val TAG_ROWS_PER_STRIP = 278
	const val TAG_STRIP_BYTE_COUNTS = 279
	const val TAG_PLANAR_CONFIGURATION = 284
	const val TAG_GROUP3OPTIONS = 292
	const val TAG_GROUP4OPTIONS = 293
	const val TAG_PREDICTOR = 317
	const val TAG_COLOR_MAP = 320
	const val TAG_TILE_WIDTH = 322
	const val TAG_TILE_HEIGHT = 323
	const val TAG_TILE_OFFSETS = 324
	const val TAG_TILE_BYTE_COUNTS = 325
	const val TAG_EXTRA_SAMPLES = 338
	const val TAG_SAMPLE_FORMAT = 339
	const val TAG_JPEG_TABLES = 347
	const val TAG_YCBCR_SUB_SAMPLING = 530
	const val TAG_REFERENCE_BLACK_WHITE = 532

	// Compression (TIFFBaseline / TIFFExtension).
	const val COMPRESSION_NONE = 1
	const val COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE = 2
	const val COMPRESSION_CCITT_T4 = 3 // CCITT T.4/Group 3 Fax compression.
	const val COMPRESSION_CCITT_T6 = 4 // CCITT T.6/Group 4 Fax compression.
	const val COMPRESSION_LZW = 5
	const val COMPRESSION_OLD_JPEG = 6 // Deprecated. For backwards compatibility only ("Old-style" JPEG).
	const val COMPRESSION_JPEG = 7 // JPEG Compression (lossy).
	const val COMPRESSION_ZLIB = 8 // Adobe-style Deflate.
	const val COMPRESSION_PACKBITS = 32773
	const val COMPRESSION_DEFLATE = 32946 // Custom: PKZIP-style Deflate.

	// T4Options (tag 292) / T6Options (tag 293) bit flags.  TIFF 6.0 defines bits 0..2 of T4Options and
	// bit 1 of T6Options; there is no byte-alignment flag in either.  Row alignment is a property of the
	// compression type (Modified Huffman RLE rows start on a byte boundary, T.4/T.6 rows do not), which
	// is why TiffReader derives it from the type rather than from these options.  FILLBITS needs no
	// handling: the fill zeros precede an EOL, and the decoder's EOL search absorbs them.
	const val GROUP3OPT_2DENCODING = 1
	const val GROUP3OPT_UNCOMPRESSED = 2
	const val GROUP3OPT_FILLBITS = 4
	const val GROUP4OPT_UNCOMPRESSED = 2

	// Photometric interpretation.
	const val PHOTOMETRIC_WHITE_IS_ZERO = 0
	const val PHOTOMETRIC_BLACK_IS_ZERO = 1
	const val PHOTOMETRIC_RGB = 2
	const val PHOTOMETRIC_PALETTE = 3
	const val PHOTOMETRIC_MASK = 4
	const val PHOTOMETRIC_YCBCR = 6

	// Planar configuration.
	const val PLANARCONFIG_CHUNKY = 1
	const val PLANARCONFIG_PLANAR = 2

	// Predictor.
	const val PREDICTOR_NONE = 1
	const val PREDICTOR_HORIZONTAL_DIFFERENCING = 2
	const val PREDICTOR_HORIZONTAL_FLOATINGPOINT = 3

	// Extra samples (alpha kind).
	const val EXTRASAMPLE_UNSPECIFIED = 0
	const val EXTRASAMPLE_ASSOCIATED_ALPHA = 1 // premultiplied
	const val EXTRASAMPLE_UNASSOCIATED_ALPHA = 2 // straight

	// Sample format.
	const val SAMPLEFORMAT_UINT = 1
	const val SAMPLEFORMAT_INT = 2
	const val SAMPLEFORMAT_FP = 3
	const val SAMPLEFORMAT_UNDEFINED = 4
	const val SAMPLEFORMAT_COMPLEXINT = 5
	const val SAMPLEFORMAT_COMPLEXIEEEFP = 6

	// Fill order.
	const val FILL_LEFT_TO_RIGHT = 1
	const val FILL_RIGHT_TO_LEFT = 2
}