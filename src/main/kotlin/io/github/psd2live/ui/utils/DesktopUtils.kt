package io.github.psd2live.ui.utils

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

object DesktopUtils {
	const val GITHUB_REPO_URL = "https://github.com/tsunehimatoi/psd2live"
	const val GITHUB_ISSUES_URL = "https://github.com/tsunehimatoi/psd2live/issues"
	const val GITHUB_RELEASES_URL = "https://github.com/tsunehimatoi/psd2live/releases"
	const val GITHUB_DOCS_URL = "https://github.com/tsunehimatoi/psd2live#%E6%96%87%E6%A1%A3%E7%B4%A2%E5%BC%95"

	private val lock = Any()
	private var lastOpenTime: Long = 0L
	private var lastOpenDirectory: String? = null

	/**
	 * Opens a URL in the user's default system web browser.
	 * Falls back gracefully to operating system-specific commands if Desktop API is not supported.
	 */
	fun openBrowser(url: String): Boolean {
		return runCatching {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI(url))
				true
			} else {
				val os = System.getProperty("os.name").orEmpty().lowercase()
				when {
					os.contains("win") -> {
						ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
						true
					}
					os.contains("mac") -> {
						ProcessBuilder("open", url).start()
						true
					}
					else -> {
						ProcessBuilder("xdg-open", url).start()
						true
					}
				}
			}
		}.getOrElse { false }
	}

	/**
	 * Opens a directory in the operating system's native file manager (Explorer, Finder, etc.).
	 * Includes debouncing (1000ms window) to prevent duplicate windows when clicked repeatedly.
	 */
	fun openDirectory(path: Path): Boolean = openDirectory(path.toAbsolutePath().normalize().toString())

	/**
	 * Opens a directory path in the operating system's native file manager.
	 * Includes debouncing (1000ms window) to prevent duplicate windows when clicked repeatedly.
	 */
	fun openDirectory(pathString: String): Boolean {
		val raw = pathString.trim()
		if (raw.isEmpty()) return false

		val dir = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() ?: return false
		if (!Files.isDirectory(dir)) return false

		val normalized = dir.toString()
		val now = System.currentTimeMillis()
		synchronized(lock) {
			if (normalized.equals(lastOpenDirectory, ignoreCase = true) && (now - lastOpenTime) < 1000L) {
				return true
			}
			lastOpenDirectory = normalized
			lastOpenTime = now
		}

		return runCatching {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
				Desktop.getDesktop().open(dir.toFile())
				true
			} else {
				val os = System.getProperty("os.name").orEmpty().lowercase()
				when {
					os.contains("win") -> {
						ProcessBuilder("explorer.exe", dir.toString()).start()
						true
					}
					os.contains("mac") -> {
						ProcessBuilder("open", dir.toString()).start()
						true
					}
					else -> {
						ProcessBuilder("xdg-open", dir.toString()).start()
						true
					}
				}
			}
		}.getOrElse { false }
	}

	/**
	 * Resets directory open debouncing tracking, primarily for unit tests.
	 */
	internal fun resetOpenDebounce() {
		synchronized(lock) {
			lastOpenTime = 0L
			lastOpenDirectory = null
		}
	}

	/**
	 * Copies plain text to the system clipboard.
	 */
	fun copyToClipboard(text: String): Boolean {
		return runCatching {
			val selection = StringSelection(text)
			Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
			true
		}.getOrElse { false }
	}
}


