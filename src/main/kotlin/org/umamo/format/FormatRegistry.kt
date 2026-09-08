package org.umamo.format

import org.umamo.format.bmp.BmpCodec
import org.umamo.format.clip.ClipReader
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.jpeg.JpegReader
import org.umamo.format.kra.KraReader
import org.umamo.format.moc3.Moc3
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdReader
import org.umamo.format.tiff.TiffReader
import org.umamo.format.webp.WebPReader

/**
 * The registry of binary container codecs Umamo can read/write, and the entry point for dispatching
 * an unknown file to the right one.
 *
 * EN: Lives in `jvmAndroidMain` because it references [Cmo3], whose JDOM/reflection serializer is
 *     JVM-only (it still sees the commonMain [Moc3]). Holds heterogeneous `FormatCodec<*>` since the
 *     models share no supertype; a caller does `detect(bytes)?.read(bytes)` and then branches on the
 *     returned model type or the codec's [FormatCodec.kind]. Text sidecars (`model3.json` etc.) are
 *     not registered here - they are `String`-shaped and live as helpers on [Moc3].
 * JA: バイナリ形式コーデックの一覧と判定窓口。Cmo3 が JVM 専用のためこのソースセットに置く。
 */
public object FormatRegistry {
	/**
	 * Every registered codec, in priority order (first magic match wins in [detect]). All of them work
	 * on every target: the model codecs (CMO3/MOC3), the layered art readers (CLIP, KRA, PSD), and the
	 * flat raster codecs (PNG, BMP, plus the JPEG/WebP/TIFF read placeholders) all live in
	 * jvmAndroidMain and need only java.nio / java.util.zip / JDOM.  The magics do not collide, so the
	 * raster codecs sit last; BMP's short 2-byte "BM" magic is checked after the longer signatures.
	 */
	private val codecs: List<FormatCodec<*>> =
		listOf(Cmo3, Moc3, ClipReader, KraReader, PsdReader, PngCodec, BmpCodec, JpegReader, WebPReader, TiffReader)

	/**
	 * Identifies the codec for [bytes], preferring a reliable magic-byte match and falling back to the
	 * file extension when no codec recognises the leading bytes.
	 *
	 * Magic comes first because it is content-truthful - a mislabelled file still routes correctly.
	 * The extension fallback exists for container formats whose magic is not self-identifying (e.g. a
	 * ZIP-based .kra whose mimetype marker a third-party tool stripped), where the name is the only
	 * remaining signal. [fileName] may be a bare name or a full path; only its extension is read.
	 *
	 * @param ByteArray bytes The file contents to identify.
	 * @param String? fileName The file name or path, used only for the extension fallback.
	 * @return FormatCodec<*>? The matching codec, or null when unrecognised.
	 */
	public fun detect(bytes: ByteArray, fileName: String? = null): FormatCodec<*>? {
		codecs.firstOrNull { codec -> codec.matches(bytes) }?.let { return it }
		val extension = fileName?.substringAfterLast('.', "")?.lowercase()
		if (extension.isNullOrEmpty()) {
			return null
		}
		return codecs.firstOrNull { codec -> codec.kind.extension == extension }
	}

	/**
	 * Returns the registered codec for [kind], or null when no codec is wired for it yet (e.g. the
	 * JSON kind, which has no registered codec).
	 *
	 * @param FileKind kind The file kind to look up.
	 * @return FormatCodec<*>? The codec for that kind, or null.
	 */
	public fun forKind(kind: FileKind): FormatCodec<*>? = codecs.firstOrNull { codec -> codec.kind == kind }

	/**
	 * The file extensions Umamo can read, derived from the registered codecs - the single source of
	 * truth for an open-file picker's filter list (so it tracks the registry instead of a frozen list).
	 *
	 * @return List the distinct readable extensions, without the leading dot.
	 */
	public fun readableExtensions(): List<String> =
		codecs.filter { codec -> codec.kind.readable }.map { codec -> codec.kind.extension }.distinct()
}