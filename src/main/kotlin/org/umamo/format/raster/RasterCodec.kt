package org.umamo.format.raster

import org.umamo.format.FormatCodec
import org.umamo.format.ReadOnlyCodec

/**
 * A [FormatCodec] whose in-memory model is a single flat [RasterImage] - one implementation per flat
 * image format (PNG, BMP, JPEG, WebP, TIFF).
 *
 * The flat-image analogue of [org.umamo.format.art.ArtReader] (which reads a layered layer tree
 * into a SourceArt): a [RasterCodec] reads and, where the format supports it, writes one whole
 * image.  Folding the raster codecs into [FormatCodec] lets [org.umamo.format.FormatRegistry]
 * detect and resolve them exactly like the layered readers and the binary model codecs, rather
 * than through a separate facade (art-sourcing roadmap invariant #5).  A caller that wants a flat
 * decode does `FormatRegistry.detect(bytes) as? RasterCodec` to know the file yields a
 * [RasterImage], then optionally wraps it as source art via [rasterToSourceArt].
 */
public interface RasterCodec : FormatCodec<RasterImage>

/**
 * A [RasterCodec] for a format Umamo decodes but does not encode (JPEG lossy, WebP, TIFF).  Inherits
 * a refusing [write] from [ReadOnlyCodec], so the read-only nature is stated once in the type; the
 * corresponding [org.umamo.format.FileKind] declares writable = false to advertise it before a call.
 *
 * 読み取り専用のラスタコーデック(JPEG/WebP/TIFF)。write は常に拒否する。
 */
public interface ReadOnlyRasterCodec : RasterCodec, ReadOnlyCodec<RasterImage>