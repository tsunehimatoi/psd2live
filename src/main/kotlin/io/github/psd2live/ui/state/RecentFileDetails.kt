package io.github.psd2live.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import io.github.psd2live.project.ProjectThumbnail
import io.github.psd2live.ui.utils.rgbaImageBitmap
import io.github.psd2live.ui.utils.toImageBitmapFast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.umamo.format.psd.PsdThumbnail
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** What the start page shows of a recent file beyond its name: when it last changed, its size and its picture. */
internal data class RecentFileDetails(
	val modifiedMillis: Long,
	val sizeBytes: Long,
	/** The project's saved thumbnail or the PSD's own; null when the file has none. */
	val thumbnail: ImageBitmap?,
	/** The canvas in pixels, from the PSD's header or a project's source PSD; null when neither is readable. */
	val canvasWidth: Int? = null,
	val canvasHeight: Int? = null,
)

/**
 * Reads [RecentFileDetails] off the UI thread and keeps them for the session, keyed by path and checked against the
 * file's modification time, so a file saved again shows its new picture and an unchanged one is not read twice.
 */
internal object RecentFileDetailsCache {
	private val cache = ConcurrentHashMap<String, RecentFileDetails>()

	fun cached(path: String): RecentFileDetails? = cache[path]

	fun load(file: RecentFile): RecentFileDetails? = runCatching {
		val path = Path.of(file.path)
		val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
		val modified = attributes.lastModifiedTime().toMillis()
		cache[file.path]?.takeIf { it.modifiedMillis == modified && it.sizeBytes == attributes.size() }?.let { return it }
		val thumbnail = runCatching {
			when (file.kind) {
				// A project saved before thumbnails were kept shows its source PSD instead.
				RecentFileKind.PROJECT -> ProjectThumbnail.read(path)
					?.let { javax.imageio.ImageIO.read(it.inputStream()) }?.toImageBitmapFast()
					?: ProjectThumbnail.readSourcePsd(path) { PsdThumbnail.read(it, preserveAlpha = true) }?.let { rgbaImageBitmap(it.width, it.height, it.rgba) }
				RecentFileKind.PSD -> Files.newInputStream(path).buffered(1 shl 16).use { PsdThumbnail.read(it, preserveAlpha = true) }
					?.let { rgbaImageBitmap(it.width, it.height, it.rgba) }
			}
		}.getOrNull()
		val canvas = runCatching {
			when (file.kind) {
				RecentFileKind.PROJECT -> ProjectThumbnail.readSourcePsd(path, ::psdCanvas)
				RecentFileKind.PSD -> Files.newInputStream(path).use(::psdCanvas)
			}
		}.getOrNull()
		RecentFileDetails(modified, attributes.size(), thumbnail, canvas?.first, canvas?.second).also { cache[file.path] = it }
	}.getOrNull()

	/** Width and height from a PSD or PSB header: rows at byte 14, columns at 18, both big-endian. */
	private fun psdCanvas(input: InputStream): Pair<Int, Int>? {
		val header = input.readNBytes(26)
		if (header.size < 26 || String(header, 0, 4, Charsets.US_ASCII) != "8BPS") return null
		fun u32(at: Int) = (0 until 4).fold(0L) { value, index -> (value shl 8) or (header[at + index].toLong() and 0xFF) }.toInt()
		return u32(18) to u32(14)
	}
}

/** [file]'s details: the last known ones at once, then read again in the background. */
@Composable
internal fun rememberRecentFileDetails(file: RecentFile): RecentFileDetails? {
	val details by produceState(RecentFileDetailsCache.cached(file.path), file.path) {
		value = withContext(Dispatchers.IO) { RecentFileDetailsCache.load(file) } ?: value
	}
	return details
}
