package io.github.psd2live.ui.state

internal const val RECENT_FILES_MAX = 8

enum class RecentFileKind {
	PROJECT,
	PSD,
}

data class RecentFile(
	val path: String,
	val kind: RecentFileKind,
) {
	val name: String
		get() = path.substringAfterLast('\\').substringAfterLast('/')

	val directory: String
		get() {
			val cut = path.lastIndexOfAny(charArrayOf('\\', '/'))
			return if (cut <= 0) "" else path.substring(0, cut)
		}
}

fun classifyRecentPath(path: String): RecentFileKind? {
	val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
	return when (extension) {
		"psd2live" -> RecentFileKind.PROJECT
		"psd", "psb" -> RecentFileKind.PSD
		else -> null
	}
}

fun rememberRecentPaths(
	existing: List<String>,
	path: String,
	max: Int = RECENT_FILES_MAX,
	keep: (String) -> Boolean = { true },
): List<String> {
	val normalized = path.trim()
	if (normalized.isEmpty() || classifyRecentPath(normalized) == null) {
		return existing.filter(keep).take(max)
	}
	return (listOf(normalized) + existing.filter { !it.equals(normalized, ignoreCase = true) && keep(it) }).take(max)
}

fun forgetRecentPath(existing: List<String>, path: String): List<String> {
	val normalized = path.trim()
	if (normalized.isEmpty()) return existing
	return existing.filter { !it.equals(normalized, ignoreCase = true) }
}

fun recentFilesFrom(paths: List<String>): List<RecentFile> =
	paths.mapNotNull { path -> classifyRecentPath(path)?.let { RecentFile(path, it) } }
