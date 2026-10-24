package io.github.psd2live.ui.state

import io.github.psd2live.core.modifier.ModifierPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * The user's modifier presets, one JSON file each under `~/.psd2live/modifier-presets`: they belong to the user, not
 * to a project, so a stack saved in one project can be put on meshes of another. A file that cannot be read is left
 * out of the list.
 */
internal class ModifierPresetStore(private val root: Path = Path.of(System.getProperty("user.home"), ".psd2live", "modifier-presets")) {
	fun list(): List<ModifierPreset> = runCatching {
		if (!Files.isDirectory(root)) return emptyList()
		Files.list(root).use { files ->
			files.filter { it.fileName.toString().endsWith(".json") }.sorted().toList().mapNotNull { file ->
				runCatching { ModifierPreset.fromJson(Json.parseToJsonElement(Files.readString(file)).jsonObject) }.getOrNull()
			}
		}.sortedBy { it.name.lowercase() }
	}.getOrDefault(emptyList())

	/** Saves [preset], replacing one of the same name. */
	fun save(preset: ModifierPreset) {
		Files.createDirectories(root)
		Files.writeString(root.resolve(fileName(preset.name)), preset.toJson().toString())
	}

	fun delete(name: String) {
		Files.deleteIfExists(root.resolve(fileName(name)))
	}

	/** A file name for [name] that any file system takes, the same for the same name. */
	private fun fileName(name: String): String {
		val stem = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(48)
		val hash = java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).take(4).joinToString("") { "%02x".format(it.toInt() and 255) }
		return "$stem-$hash.json"
	}
}
