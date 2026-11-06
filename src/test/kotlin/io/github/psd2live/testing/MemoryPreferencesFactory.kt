package io.github.psd2live.testing

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/**
 * Process-local preferences for test JVMs (set by the Gradle test tasks).
 *
 * The platform store is shared by every process of the user: tests that change app settings would leak
 * into parallel test forks and into the user's real settings.
 */
class MemoryPreferencesFactory : PreferencesFactory {
	override fun systemRoot(): Preferences = system
	override fun userRoot(): Preferences = user

	private class Node(parent: Node?, name: String) : AbstractPreferences(parent, name) {
		private val values = HashMap<String, String>()
		private val children = HashMap<String, Node>()
		override fun putSpi(key: String, value: String) { values[key] = value }
		override fun getSpi(key: String): String? = values[key] ?: if (key == "directory" && absolutePath() == "/io.github.psd2live.settings/algorithm-plugins") pluginDirectory else null
		override fun removeSpi(key: String) { values.remove(key) }
		override fun removeNodeSpi() { (parent() as Node?)?.children?.remove(name()) }
		override fun keysSpi(): Array<String> = values.keys.toTypedArray()
		override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
		override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { Node(this, name) }
		override fun syncSpi() {}
		override fun flushSpi() {}
	}

	private companion object {
		val pluginDirectory: String by lazy {
			val directory = java.nio.file.Files.createTempDirectory("psd2live-test-plugins-")
			Runtime.getRuntime().addShutdownHook(Thread {
				runCatching { java.nio.file.Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists) } }
			})
			directory.toString()
		}
		val system = Node(null, "")
		val user = Node(null, "")
	}
}
