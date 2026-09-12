package io.github.psd2live.ui.utils

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

object DesktopUtils {
	const val GITHUB_REPO_URL = "https://github.com/tsunehimatoi/psd2live"
	const val GITHUB_ISSUES_URL = "https://github.com/tsunehimatoi/psd2live/issues"
	const val GITHUB_RELEASES_URL = "https://github.com/tsunehimatoi/psd2live/releases"
	const val GITHUB_DOCS_URL = "https://github.com/tsunehimatoi/psd2live#%E6%96%87%E6%A1%A3%E7%B4%A2%E5%BC%95"

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

