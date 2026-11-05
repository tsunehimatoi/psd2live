package io.github.psd2live.core.modifier

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import java.util.zip.ZipInputStream

internal data class InstalledModifierPlugin(val manifest: ModifierPluginManifest, val reference: ModifierPluginReference,
    val directory: Path, val enabled: Boolean, val gitCommit: String? = null)

/** Installation never executes package code. Payloads are immutable, and references pin their complete contents. */
internal class ModifierPluginStore(val root: Path, private val preferences: Preferences) {
    companion object {
        val shared by lazy {
            val prefs = Preferences.userRoot().node("io.github.psd2live.settings/algorithm-plugins")
            ModifierPluginStore(Path.of(prefs.get("directory", Path.of(System.getProperty("user.home"), ".psd2live", "plugins").toString())), prefs)
        }
        val platform: String get() {
            val os = System.getProperty("os.name").lowercase().let { when { "win" in it -> "windows"; "mac" in it -> "macos"; else -> "linux" } }
            val arch = System.getProperty("os.arch").lowercase().let { when (it) { "amd64", "x86_64" -> "x86_64"; "arm64", "aarch64" -> "aarch64"; else -> it } }
            return "$os-$arch"
        }
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private val token = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
        private val digest = Regex("[a-f0-9]{64}")
        private val codec = Json { encodeDefaults = true }
        private const val MAX_BYTES = 512L * 1024 * 1024
        private fun checkCancelled() { if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Plugin operation cancelled") }
        internal fun inside(root: Path, relative: String): Path {
            require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains(':')) { "Invalid plugin path: $relative" }
            val path = root.resolve(relative).normalize()
            require(!Path.of(relative).isAbsolute && path.startsWith(root) && path != root) { "Plugin path escapes package" }
            return path
        }
        internal fun deleteTree(path: Path) {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return
            Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private val lock = Any()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private var installed: List<InstalledModifierPlugin> = load()

    fun snapshot(): List<InstalledModifierPlugin> = synchronized(lock) { installed.toList() }
    fun builtinsEnabled() = preferences.getBoolean("builtins", true)
    fun setBuiltinsEnabled(enabled: Boolean) = synchronized(lock) {
        preferences.putBoolean("builtins", enabled); preferences.flush(); changes.value++
    }

    fun inspect(source: String, ref: String? = null): InstalledModifierPlugin = stage(source, ref) { directory, commit ->
        inspectDirectory(directory, commit)
    }

    fun install(source: String, ref: String? = null): InstalledModifierPlugin = stage(source, ref) { directory, commit ->
        val checked = inspectDirectory(directory, commit)
        synchronized(lock) {
            checkCancelled()
            installed.firstOrNull { it.reference == checked.reference }?.let { return@synchronized it }
            require(installed.none { it.reference.id == checked.reference.id && it.reference.version == checked.reference.version }) {
                "This plugin version is already installed with different contents; use a new package version"
            }
            require(installed.none { it.reference.id != checked.reference.id && it.manifest.algorithms.any { old -> checked.manifest.algorithms.any { it.type == old.type } } }) {
                "Another plugin owns this algorithm type"
            }
            val destination = root.resolve(checked.reference.digest)
            require(!Files.exists(destination)) { "Plugin destination already exists" }
            Files.move(directory, destination, ATOMIC_MOVE)
            val result = checked.copy(directory = destination)
            installed = installed + result
            changes.value++
            result
        }
    }

    fun setEnabled(reference: ModifierPluginReference, enabled: Boolean) = synchronized(lock) {
        val entry = installed.single { it.reference == reference }
        preferences.putBoolean(reference.digest, enabled); preferences.flush()
        ModifierPluginWorkers.stop(reference)
        installed = installed.map { if (it == entry) it.copy(enabled = enabled) else it }
        changes.value++
    }

    fun uninstall(reference: ModifierPluginReference) = synchronized(lock) {
        val entry = installed.single { it.reference == reference }
        ModifierPluginWorkers.stop(reference)
        val trash = root.resolve(".removed-${reference.digest}-${System.nanoTime()}")
        Files.move(entry.directory, trash, ATOMIC_MOVE)
        installed = installed - entry
        preferences.remove(reference.digest)
        changes.value++
        runCatching { deleteTree(trash) }
    }

    fun requireAvailable(reference: ModifierPluginReference) = synchronized(lock) {
        require(installed.any { it.reference == reference && it.enabled }) { "Missing or disabled algorithm plugin: ${reference.id}@${reference.version}" }
    }

    private fun load(): List<InstalledModifierPlugin> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { paths -> paths.filter { digest.matches(it.fileName.toString()) }.map { path ->
            inspectDirectory(path, path.resolve(".origin").takeIf(Files::exists)?.let(Files::readString)).also {
                require(it.reference.digest == path.fileName.toString()) { "Installed plugin contents changed: ${it.reference.id}" }
            }
        }.toList() }
    }

    private fun inspectDirectory(directory: Path, commit: String?): InstalledModifierPlugin {
        val bytes = Files.readAllBytes(directory.resolve("plugin.json"))
        require(bytes.size <= 1024 * 1024) { "Plugin manifest is too large" }
        val manifest = codec.decodeFromString(ModifierPluginManifest.serializer(), bytes.decodeToString())
        require(manifest.protocol == 1 && token.matches(manifest.id) && token.matches(manifest.version)) { "Unsupported plugin manifest" }
        require(manifest.name.isNotBlank() && manifest.algorithms.size in 1..128) { "A plugin must describe its algorithms" }
        require(manifest.algorithms.map { it.type to it.version }.distinct().size == manifest.algorithms.size) { "Duplicate plugin algorithm" }
        manifest.algorithms.forEach {
            require(it.type.startsWith("${manifest.id}/") && it.version > 0 && it.name.isNotBlank()) { "Algorithm type must belong to its plugin namespace" }
            require(it.targets.isNotEmpty() && it.targets.all { kind -> kind in setOf("mesh", "deformer", "part", "layer") }) { "Invalid algorithm targets" }
            ModifierPluginSchema.check(it.configSchema)
            ModifierPluginSchema.validate(it.defaults, it.configSchema)
        }
        val command = requireNotNull(manifest.commands[platform] ?: manifest.commands["jvm"]) { "Plugin has no runtime for $platform" }
        require(command.isNotEmpty() && command.size <= 64) { "Invalid plugin command" }
        require(manifest.files.size in 1..10000) { "Plugin payload is empty or too large" }
        var size = 0L
        val hashes = StringBuilder(hash(bytes))
        manifest.files.toSortedMap().forEach { (name, expected) ->
            checkCancelled()
            val path = inside(directory, name)
            require(digest.matches(expected) && Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "Missing plugin payload: $name" }
            size += Files.size(path); require(size <= MAX_BYTES) { "Plugin payload exceeds size limit" }
            require(hash(Files.readAllBytes(path)) == expected) { "Plugin checksum mismatch: $name" }
            hashes.append('\n').append(name).append(':').append(expected)
        }
        Files.walk(directory).use { paths -> paths.forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Plugin symlinks are not supported" }
            if (Files.isRegularFile(path)) require(directory.relativize(path).toString().replace('\\', '/') in manifest.files || path.fileName.toString() in setOf("plugin.json", ".origin")) { "Unlisted plugin payload: ${path.fileName}" }
        } }
        require(command.first() == "\$JAVA" || command.first() in manifest.files) { "Plugin executable must be packaged" }
        if (command.first() == "\$JAVA") require(command.size == 3 && command[1] == "-jar" && command[2] in manifest.files) { "JVM plugins must specify \$JAVA -jar payload.jar" }
        val identity = ModifierPluginReference(manifest.id, manifest.version, hash(hashes.toString().encodeToByteArray()))
        return InstalledModifierPlugin(manifest, identity, directory, preferences.getBoolean(identity.digest, true), commit)
    }

    private fun <T> stage(source: String, ref: String?, action: (Path, String?) -> T): T {
        Files.createDirectories(root)
        val temporary = Files.createTempDirectory(root, ".install-")
        val packageDir = Files.createDirectory(temporary.resolve("package"))
        try {
            var commit: String? = null
            if (source.startsWith("https://") || source.startsWith("ssh://")) {
                require(ref == null || ref.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9/._-]{0,200}"))) { "Invalid Git ref" }
                val repository = temporary.resolve("repository")
                git(listOf("clone", "--depth", "1", "--no-checkout") + (ref?.let { listOf("--branch", it) } ?: emptyList()) + listOf("--", source, repository.toString()), temporary)
                git(listOf("-c", "core.hooksPath=", "checkout", "--detach", "HEAD"), repository)
                commit = git(listOf("rev-parse", "HEAD"), repository).trim()
                copyPackage(repository, packageDir, skipGit = true)
            } else {
                require(ref == null) { "A ref is only valid for Git sources" }
                val path = Path.of(source)
                require(path.isAbsolute) { "Provide an absolute package path" }
                if (Files.isDirectory(path)) copyPackage(path, packageDir) else unzip(path, packageDir)
            }
            if (commit != null) Files.writeString(packageDir.resolve(".origin"), commit)
            checkCancelled()
            return action(packageDir, commit)
        } finally { deleteTree(temporary) }
    }

    private fun copyPackage(source: Path, destination: Path, skipGit: Boolean = false) {
        var size = 0L
        Files.walk(source).use { paths -> paths.forEach { path ->
            checkCancelled()
            val name = source.relativize(path).toString().replace('\\', '/')
            if (name.isEmpty() || skipGit && (name == ".git" || name.startsWith(".git/"))) return@forEach
            require(!Files.isSymbolicLink(path)) { "Plugin symlinks are not supported" }
            val target = inside(destination, name)
            if (Files.isDirectory(path)) Files.createDirectories(target) else {
                size += Files.size(path); require(size <= MAX_BYTES) { "Plugin payload exceeds size limit" }
                Files.createDirectories(target.parent); Files.copy(path, target)
                if (Files.isExecutable(path)) target.toFile().setExecutable(true)
            }
        } }
    }

    private fun unzip(source: Path, destination: Path) {
        var total = 0L; var count = 0
        ZipInputStream(Files.newInputStream(source)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= 10000) { "Too many plugin files" }
                val target = inside(destination, entry.name.trimEnd('/'))
                if (entry.isDirectory) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    require(!Files.exists(target)) { "Duplicate plugin archive entry" }
                    Files.newOutputStream(target).use { out ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            checkCancelled(); val length = zip.read(buffer); if (length < 0) break
                            total += length; require(total <= MAX_BYTES) { "Plugin archive exceeds size limit" }
                            out.write(buffer, 0, length)
                        }
                    }
                    target.toFile().setExecutable(true, true)
                }
            }
        }
    }

    private fun git(arguments: List<String>, directory: Path): String {
        val output = Files.createTempFile(root, ".git-log-", ".txt")
        val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=", "-c", "protocol.file.allow=never") + arguments)
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0"
            }.start()
        try {
            require(process.waitFor(120, TimeUnit.SECONDS)) { "Git plugin download timed out" }
            require(process.exitValue() == 0) { "Git plugin download failed (exit ${process.exitValue()})" }
            return Files.readString(output)
        } finally { if (process.isAlive) process.destroyForcibly(); Files.deleteIfExists(output) }
    }
}
