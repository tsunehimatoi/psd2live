package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigBuildProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import java.util.zip.ZipInputStream

internal data class InstalledModifierPlugin(val manifest: ModifierPluginManifest, val reference: ModifierPluginReference,
    val directory: Path, val enabled: Boolean, val gitCommit: String? = null,
    val dependencyLock: List<ModifierPluginReference>? = null)

/** Installation never executes package code. Payloads are immutable, and references pin their complete contents. */
internal class ModifierPluginStore(val root: Path, private val preferences: Preferences,
                                   private val bundledWorkers: Boolean = false,
                                   private val devArtifactResolver: (ModifierPluginReference) -> InstalledModifierPlugin? = PluginDevArtifactStore::resolve) {
    companion object {
        val shared by lazy {
            val prefs = Preferences.userRoot().node("io.github.psd2live.settings/algorithm-plugins")
            ModifierPluginStore(Path.of(prefs.get("directory", Path.of(System.getProperty("user.home"), ".psd2live", "plugins").toString())), prefs,
                bundledWorkers = true)
        }
        val platform: String get() {
            val os = System.getProperty("os.name").lowercase().let { when { "win" in it -> "windows"; "mac" in it -> "macos"; else -> "linux" } }
            val arch = System.getProperty("os.arch").lowercase().let { when (it) { "amd64", "x86_64" -> "x86_64"; "arm64", "aarch64" -> "aarch64"; else -> it } }
            return "$os-$arch"
        }
        fun hash(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        internal fun hash(path: Path): String = io.github.psd2live.core.RigBuildProfile.stage("plugin payload verification") {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            Files.newInputStream(path).use { input ->
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_BYTES) { "Plugin payload exceeds size limit" }
                    digest.update(buffer, 0, count)
                }
            }
            java.util.HexFormat.of().formatHex(digest.digest())
        }
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
            Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { entry ->
                try { Files.delete(entry) }
                catch (failure: java.nio.file.AccessDeniedException) {
                    // Git marks object files read-only on Windows. Never follow links while clearing that attribute.
                    val attributes = if (Files.isSymbolicLink(entry)) null else
                        Files.getFileAttributeView(entry, java.nio.file.attribute.DosFileAttributeView::class.java, NOFOLLOW_LINKS)
                    if (attributes?.readAttributes()?.isReadOnly != true) throw failure
                    attributes.setReadOnly(false)
                    Files.delete(entry)
                }
            } }
        }
        internal fun inspectPackageDirectory(directory: Path, commit: String? = null,
                                             enabledForDigest: (String) -> Boolean = { true }): InstalledModifierPlugin {
            require(Files.size(directory.resolve("plugin.json")) <= 1024 * 1024) { "Plugin manifest is too large" }
            val bytes = Files.readAllBytes(directory.resolve("plugin.json"))
            require(bytes.size <= 1024 * 1024) { "Plugin manifest is too large" }
            val manifest = codec.decodeFromString(ModifierPluginManifest.serializer(), bytes.decodeToString())
            require(manifest.id != "builtin") { "The aggregate bundled identity is reserved" }
            require(manifest.protocol in 1..2 && token.matches(manifest.id) && manifest.version.length <= 128) { "Unsupported plugin manifest" }
            ModifierPluginVersion.parse(manifest.version, legacy = true)
            for (relation in manifest.dependencies + manifest.conflicts) {
                require(token.matches(relation.id) && relation.id != manifest.id) { "Invalid plugin relationship: ${relation.id}" }
                relation.version.validate()
            }
            require(manifest.dependencies.map { it.id }.distinct().size == manifest.dependencies.size) { "Duplicate package dependency" }
            require(manifest.name.isNotBlank() && manifest.algorithms.size in 1..128) { "A plugin must describe its algorithms" }
            require(manifest.algorithms.map { it.type to it.version }.distinct().size == manifest.algorithms.size) { "Duplicate plugin algorithm" }
            val entryKeys = manifest.algorithms.flatMap { algorithm ->
                (algorithm.aliases + algorithm.type).map { it to algorithm.version }
            }
            require(entryKeys.distinct().size == entryKeys.size) { "Duplicate plugin algorithm alias" }
            manifest.algorithms.forEach {
                require(it.contextVersion in 1..2) { "Unsupported algorithm context version" }
                require(it.provides.size <= 128 && it.provides.all { name -> name.isNotBlank() && name.length <= 256 && name.none(Char::isISOControl) }) { "Invalid provided capabilities" }
                it.authorContext?.let { ctx -> require(ctx.isNotBlank() && ctx.length <= 256 && ctx.none(Char::isISOControl)) { "Invalid authorContext contract" } }
                require(it.aliases.all { alias -> manifest.id == "builtin." + java.util.HexFormat.of().formatHex(alias.encodeToByteArray()) + ".v${it.version}" }) { "Only a matching bundled package may declare a legacy type" }
                require(!manifest.id.startsWith("builtin.") || it.aliases.size == 1) { "Bundled archives require a stable algorithm alias" }
                require(it.type.startsWith("${manifest.id}/") && it.version > 0 && it.name.isNotBlank()) { "Algorithm type must belong to its plugin namespace" }
                require(it.targets.isNotEmpty() && it.targets.all { kind -> kind in setOf("mesh", "deformer", "part", "layer") }) { "Invalid algorithm targets" }
                require((it.reads + it.writes + it.composableKinds).all { kind -> kind in ModifierPluginOutput.components }) { "Unknown plugin model component" }
                require("*" !in it.creates && "*" !in it.modifies) { "Wildcard '*' is forbidden in creates/modifies declarations" }
                require((it.creates + it.modifies).all { token ->
                    token.isNotBlank() && token.length <= 128 && token.none(Char::isISOControl)
                }) { "Invalid plugin creates/modifies declaration" }
                require(it.derivedOutputs.size <= 128 && it.derivedOutputs.distinct().size == it.derivedOutputs.size &&
                    it.derivedOutputs.all { rule -> rule.kind in setOf("mesh", "deformer", "part", "parameter", "physics", "motion", "glue") &&
                        (rule.kind in it.writes || "*" in it.writes) }) { "Invalid derived output rules" }
                ModifierPluginSchema.check(it.configSchema)
                ModifierPluginSchema.validate(it.defaults, it.configSchema)
                require(it.parameters.map { p -> p.id }.distinct().size == it.parameters.size && it.parameters.all { p ->
                    p.id.isNotBlank() && p.name.isNotBlank() && p.min.isFinite() && p.max.isFinite() && p.default.isFinite() && p.min < p.max && p.default in p.min..p.max
                }) { "Invalid plugin parameter definitions" }
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
                require(hash(path) == expected) { "Plugin checksum mismatch: $name" }
                hashes.append('\n').append(name).append(':').append(expected)
            }
            Files.walk(directory).use { paths -> paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Plugin symlinks are not supported" }
                val relative = directory.relativize(path).toString().replace('\\', '/')
                if (Files.isRegularFile(path)) require(relative in manifest.files || relative in setOf("plugin.json", ".origin", ".dependencies")) { "Unlisted plugin payload: $relative" }
            } }
            require(command.first() == "\$JAVA" || command.first() in manifest.files) { "Plugin executable must be packaged" }
            if (command.first() == "\$JAVA") require(command.size == 3 && command[1] == "-jar" && command[2] in manifest.files) { "JVM plugins must specify \$JAVA -jar payload.jar" }
            val identity = ModifierPluginReference(manifest.id, manifest.version, hash(hashes.toString().encodeToByteArray()))
            return InstalledModifierPlugin(manifest, identity, directory, enabledForDigest(identity.digest), commit)
        }
    }

    private val lock = Any()
    private val archiveLock = Any()
    @Volatile private var builtinEnabled = preferences.getBoolean("builtins", true)
    @Volatile private var builtinOverrides = preferences.keys().filter { it.startsWith("builtin.") }
        .associateWith { preferences.getBoolean(it, builtinEnabled) }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val loadProblems = ArrayList<String>()
    private var installed: List<InstalledModifierPlugin> = load()
    private data class CatalogSnapshot(val revision: Long, val packages: List<InstalledModifierPlugin>,
                                       val resolutions: MutableMap<ModifierPluginReference, ModifierPluginResolution> = LinkedHashMap())
    private var catalogSnapshot: CatalogSnapshot? = null
    private val shippedWorkers by lazy {
        if (bundledWorkers) BundledAlgorithmArtifacts.artifacts.map { artifact ->
            InstalledModifierPlugin(artifact.manifest, artifact.reference, root.resolve(artifact.reference.digest), enabled = true)
        } else emptyList()
    }

    fun snapshot(): List<InstalledModifierPlugin> = synchronized(lock) { installed.toList() }
    fun runnablePackages(): List<InstalledModifierPlugin> = synchronized(lock) {
        (shippedWorkers + installed).distinctBy { it.reference }
    }
    fun findRunnable(reference: ModifierPluginReference): InstalledModifierPlugin? =
        runnablePackages().firstOrNull { it.reference == reference } ?: devArtifactResolver(reference)

    /** Historical contents remain runnable only by exact reference, never as a new instance's default. */
    fun defaultPackages(): List<InstalledModifierPlugin> = runnablePackages().filter { entry ->
        !entry.reference.id.startsWith("builtin.") || entry.reference == BuiltinModifierPlugins.entry(entry.reference.id)?.reference
    }

    fun retainBundledReferences(references: Collection<ModifierPluginReference>) {
        val artifacts = unretainedBundles(references)
        if (artifacts.isEmpty()) return
        synchronized(archiveLock) { BundledAlgorithmArtifacts.retain(this, artifacts) }
    }

    /** Archive immutable payloads while the candidate evaluates, and join before returning its result. */
    fun <T> withArchivedBundles(references: Collection<ModifierPluginReference>, evaluate: () -> T): T {
        val artifacts = unretainedBundles(references)
        if (artifacts.isEmpty()) return evaluate()
        val archive = FutureTask {
            synchronized(archiveLock) {
                checkCancelled()
                BundledAlgorithmArtifacts.retain(this, artifacts)
            }
        }
        Thread.ofVirtual().name("psd2live-plugin-archive").start(archive)
        try {
            val result = evaluate()
            RigBuildProfile.stage("plugin load: wait for bundled archive") {
                try { archive.get() }
                catch (failure: ExecutionException) { throw failure.cause ?: failure }
            }
            return result
        } finally {
            archive.cancel(true)
        }
    }

    private fun unretainedBundles(references: Collection<ModifierPluginReference>): List<BundledAlgorithmArtifacts.Artifact> {
        if (!bundledWorkers || references.isEmpty()) return emptyList()
        val requested = references.toSet()
        val retained = synchronized(lock) { installed.mapTo(HashSet()) { it.reference } }
        return BundledAlgorithmArtifacts.artifacts.filter { it.reference in requested && it.reference !in retained }
    }
    private fun catalog(): CatalogSnapshot {
        catalogSnapshot?.takeIf { it.revision == changes.value }?.let { return it }
        val packages = (BuiltinModifierPlugins.packages(this) + installed.map { entry ->
            if (entry.reference.id.startsWith("builtin.")) entry.copy(enabled = entry.enabled && builtinEnabled(entry.reference.id)) else entry
        }).distinctBy { it.reference }
        return CatalogSnapshot(changes.value, packages).also { catalogSnapshot = it }
    }
    fun packages(): List<InstalledModifierPlugin> = synchronized(lock) { catalog().packages }
    fun resolution(reference: ModifierPluginReference, overlayPackages: List<InstalledModifierPlugin> = emptyList()): ModifierPluginResolution = synchronized(lock) {
        val snapshot = catalog()
        if (overlayPackages.isEmpty()) {
            snapshot.resolutions[reference]?.let {
                io.github.psd2live.core.RigBuildProfile.count("plugin dependency cache hits")
                return@synchronized it
            }
        }
        val devEntry = if (snapshot.packages.any { it.reference == reference }) null else devArtifactResolver(reference)
        val candidates = if (overlayPackages.isEmpty() && devEntry == null) {
            snapshot.packages
        } else {
            (snapshot.packages + overlayPackages + listOfNotNull(devEntry)).distinctBy { it.reference }
        }
        val result = ModifierPluginDependencies.resolve(reference, candidates)
        if (overlayPackages.isEmpty() && devEntry == null) {
            // Queries may include missing historical references; bound these as well as installed packages.
            if (snapshot.resolutions.size >= 256) snapshot.resolutions.remove(snapshot.resolutions.keys.first())
            snapshot.resolutions[reference] = result
        }
        io.github.psd2live.core.RigBuildProfile.count("plugin dependency resolutions")
        result
    }
    fun problems(): List<String> = synchronized(lock) { loadProblems.toList() }
    fun <T> withRevision(expected: Long, action: () -> T): T = synchronized(lock) {
        require(changes.value == expected) { "Algorithm plugins changed while preparing the model; retry the operation" }
        action()
    }
    fun verify(reference: ModifierPluginReference) = synchronized(lock) {
        val entry = installed.singleOrNull { it.reference == reference && it.enabled }
            ?: devArtifactResolver(reference)
            ?: throw NoSuchElementException("Plugin reference is not installed or enabled: ${reference.id}@${reference.version}")
        require(inspectDirectory(entry.directory, entry.gitCommit).reference.copy(dependencyDigests = reference.dependencyDigests) == reference) { "Plugin contents changed after installation" }
        entry
    }
    fun workerPackage(reference: ModifierPluginReference): InstalledModifierPlugin {
        retainBundledReferences(listOf(reference))
        return verify(reference)
    }
    fun builtinsEnabled() = builtinEnabled
    fun builtinEnabled(id: String): Boolean = builtinOverrides[id] ?: builtinEnabled
    fun setBuiltinEnabled(reference: ModifierPluginReference, enabled: Boolean) = synchronized(lock) {
        require(BuiltinModifierPlugins.entry(reference.id)?.reference == reference) { "Bundled plugin version is unavailable" }
        if (enabled) {
            val resolution = ModifierPluginDependencies.resolve(reference, packages().map { if (it.reference == reference) it.copy(enabled = true) else it })
            require(resolution.available) { resolution.issues.joinToString("; ") }
        } else {
            stopAffected(reference)
            installed.filter { it.reference.id == reference.id && it.reference != reference }.forEach { stopAffected(it.reference) }
        }
        val before = preferences.get(reference.id, null)
        try { preferences.putBoolean(reference.id, enabled); preferences.flush() }
        catch (failure: Exception) {
            if (before == null) preferences.remove(reference.id) else preferences.put(reference.id, before)
            throw failure
        }
        builtinOverrides = builtinOverrides + (reference.id to enabled)
        changes.value++
    }
    fun setBuiltinsEnabled(enabled: Boolean) = synchronized(lock) {
        if (enabled) {
            val candidates = packages().map { if (it.reference.id.startsWith("builtin.")) it.copy(enabled = true) else it }
            BuiltinModifierPlugins.entries.forEach { entry ->
                val resolution = ModifierPluginDependencies.resolve(entry.reference, candidates)
                require(resolution.available) { resolution.issues.joinToString("; ") }
            }
        } else installed.filter { entry -> entry.reference.id.startsWith("builtin.") || resolution(entry.reference).dependencies.any { it.id.startsWith("builtin.") } }
            .forEach { ModifierPluginWorkers.stop(it.reference) }
        val previous = BuiltinModifierPlugins.entries.associate { it.reference.id to preferences.get(it.reference.id, null) }
        try {
            previous.keys.forEach { preferences.remove(it) }
            preferences.putBoolean("builtins", enabled); preferences.flush()
        } catch (failure: Exception) {
            previous.forEach { (id, value) -> if (value == null) preferences.remove(id) else preferences.put(id, value) }
            preferences.putBoolean("builtins", builtinEnabled); throw failure
        }
        builtinEnabled = enabled
        builtinOverrides = emptyMap()
        changes.value++
    }

    fun inspect(source: String, ref: String? = null): InstalledModifierPlugin = stage(source, ref) { directory, commit ->
        inspectDirectory(directory, commit).also { checkDependencies(runnablePackages() + it) }
    }

    fun install(source: String, ref: String? = null): InstalledModifierPlugin = stage(source, ref) { directory, commit ->
        val checked = inspectDirectory(directory, commit)
        synchronized(archiveLock) { publish(checked) }
    }

    /** The bundled copier already wrote into an isolated directory on the store's volume. */
    fun installBundled(directory: Path, expected: ModifierPluginReference): InstalledModifierPlugin {
        require(directory.parent == root && directory.fileName.toString().startsWith(".bundled-"))
        require(listOf(".dependencies", ".origin").none { Files.exists(directory.resolve(it), NOFOLLOW_LINKS) }) {
            "Bundled plugin archives must not contain installation metadata"
        }
        val checked = inspectDirectory(directory, null)
        require(checked.reference == expected) { "Bundled algorithm checksum mismatch: ${expected.id}" }
        return publish(checked, archiveOnly = bundledWorkers)
    }

    private fun publish(checked: InstalledModifierPlugin, archiveOnly: Boolean = false): InstalledModifierPlugin = synchronized(lock) {
        checkCancelled()
        checkDependencies(runnablePackages() + checked)
        installed.firstOrNull { it.reference.digest == checked.reference.digest }?.let { return@synchronized it }
        // Bundled rebuilds retain every immutable digest. Only explicit uninstall may remove history.
        if (!archiveOnly && !checked.reference.id.startsWith("builtin.")) {
            require(installed.none { it.reference.id == checked.reference.id && it.reference.version == checked.reference.version }) {
                "This plugin version is already installed with different contents; use a new package version"
            }
        }
        require(installed.none { it.reference.id != checked.reference.id && it.manifest.algorithms.any { old -> checked.manifest.algorithms.any { it.type == old.type } } }) {
            "Another plugin owns this algorithm type"
        }
        // A shipped package can be archived for a disabled historical instance even while activation would conflict.
        val dependencies = if (archiveOnly && checked.manifest.dependencies.isEmpty()) emptyList() else {
            val resolution = ModifierPluginDependencies.resolve(checked.reference,
                packages().filterNot { it.reference == checked.reference } + checked)
            require(resolution.available) { resolution.issues.joinToString("; ") }
            if (!archiveOnly) retainBundledReferences(resolution.dependencies)
            resolution.dependencies
        }
        Files.writeString(checked.directory.resolve(".dependencies"), codec.encodeToString(kotlinx.serialization.builtins.ListSerializer(ModifierPluginReference.serializer()), dependencies))
        val destination = root.resolve(checked.reference.digest)
        if (Files.exists(destination)) {
            // A damaged installation is not active. Retain it outside the catalog until cleanup succeeds.
            val damaged = root.resolve(".damaged-${checked.reference.digest}-${System.nanoTime()}")
            movePackage(destination, damaged)
            runCatching { deleteTree(damaged) }
        }
        movePackage(checked.directory, destination)
        val result = checked.copy(directory = destination, dependencyLock = dependencies, reference = checked.reference.copy(
            dependencyDigests = dependencies.associate { "${it.id}@${it.version}" to it.digest }))
        installed = installed + result
        catalogSnapshot = null
        loadProblems.removeAll { it.startsWith(checked.reference.digest + ":") }
        // The same exact reference was already in the catalog. Archiving its bytes cannot invalidate a candidate.
        if (!archiveOnly) changes.value++
        result
    }

    fun setEnabled(reference: ModifierPluginReference, enabled: Boolean) = synchronized(lock) {
        val entry = installed.single { it.reference == reference }
        if (enabled) {
            val resolution = ModifierPluginDependencies.resolve(reference, packages().map { if (it.reference == reference) it.copy(enabled = true) else it })
            require(resolution.available) { resolution.issues.joinToString("; ") }
        }
        preferences.putBoolean(reference.digest, enabled); preferences.flush()
        stopAffected(reference)
        installed = installed.map { if (it == entry) it.copy(enabled = enabled) else it }
        changes.value++
    }

    fun uninstall(reference: ModifierPluginReference) = synchronized(lock) {
        val entry = installed.single { it.reference == reference }
        stopAffected(reference)
        val trash = root.resolve(".removed-${reference.digest}-${System.nanoTime()}")
        movePackage(entry.directory, trash)
        installed = installed - entry
        preferences.remove(reference.digest)
        changes.value++
        runCatching { deleteTree(trash) }
    }

    /** Windows scanners can briefly retain a package directory handle. Publish catalog changes only after rename. */
    private fun movePackage(source: Path, destination: Path) {
        for (attempt in 0..6) {
            checkCancelled()
            try { Files.move(source, destination, ATOMIC_MOVE); return }
            catch (failure: java.nio.file.FileSystemException) {
                if (!platform.startsWith("windows-") || attempt == 6) throw failure
                Thread.sleep(10L shl attempt)
            }
        }
    }

    private fun stopAffected(reference: ModifierPluginReference) {
        installed.filter { it.reference == reference || reference in it.dependencyLock.orEmpty() ||
            reference in ModifierPluginDependencies.resolve(it.reference, packages()).dependencies
        }.forEach { ModifierPluginWorkers.stop(it.reference) }
    }

    fun requireAvailable(reference: ModifierPluginReference) = synchronized(lock) {
        val result = resolution(reference)
        require(result.available) { result.issues.joinToString("; ") }
    }

    private fun checkDependencies(packages: List<InstalledModifierPlugin>) {
        val dependencies = packages.flatMap { it.manifest.algorithms }.flatMap { algorithm ->
            (algorithm.aliases + algorithm.type).map { it to algorithm.requires }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, versions) -> versions.flatten().toSet() }
        val visited = HashSet<String>()
        val visiting = HashSet<String>()
        fun visit(type: String) {
            if (type in visited || type !in dependencies) return
            require(visiting.add(type)) { "Plugin dependency cycle: $type" }
            dependencies.getValue(type).forEach(::visit)
            visiting.remove(type); visited += type
        }
        dependencies.keys.forEach(::visit)
    }

    private fun load(): List<InstalledModifierPlugin> = RigBuildProfile.stage("plugin load: installed catalog") {
        if (!Files.isDirectory(root)) return emptyList()
        val paths = Files.list(root).use { paths -> paths.filter { digest.matches(it.fileName.toString()) }.toList() }
        val threads = minOf(paths.size, Runtime.getRuntime().availableProcessors(), 4)
        val results = if (threads < 2) paths.map(::readInstalled) else {
            val pool = Executors.newFixedThreadPool(threads) { task -> Thread(task, "psd2live-plugin-load").apply { isDaemon = true } }
            try {
                pool.invokeAll(paths.map { path -> Callable { readInstalled(path) } }).map { future ->
                    try { future.get() } catch (failure: ExecutionException) { throw failure.cause ?: failure }
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Plugin catalog loading cancelled").apply { initCause(interrupted) }
            } finally {
                pool.shutdownNow()
            }
        }
        paths.zip(results).mapNotNull { (path, result) ->
            result.getOrElse { failure ->
                loadProblems += "${path.fileName}: ${failure.message}"
                null
            }
        }
    }

    private fun readInstalled(path: Path): Result<InstalledModifierPlugin> = try {
        val entry = inspectDirectory(path, path.resolve(".origin").takeIf(Files::exists)?.let(Files::readString))
        val dependencies = path.resolve(".dependencies").takeIf(Files::exists)?.let {
            require(Files.size(it) <= 1024 * 1024) { "Dependency lock is too large" }
            codec.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ModifierPluginReference.serializer()), Files.readString(it))
        }
        val reference = entry.reference.copy(dependencyDigests = dependencies.orEmpty().associate { "${it.id}@${it.version}" to it.digest })
        require(reference.digest == path.fileName.toString()) { "Installed plugin contents changed: ${reference.id}" }
        Result.success(entry.copy(reference = reference, dependencyLock = dependencies))
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        checkCancelled()
        Result.failure(failure)
    }

    private fun inspectDirectory(directory: Path, commit: String?): InstalledModifierPlugin =
        inspectPackageDirectory(directory, commit) { digest -> preferences.getBoolean(digest, true) }

    private fun <T> stage(source: String, ref: String?, action: (Path, String?) -> T): T {
        Files.createDirectories(root)
        val temporary = Files.createTempDirectory(root, ".install-")
        val packageDir = Files.createDirectory(temporary.resolve("package"))
        try {
            var commit: String? = null
            if (source.startsWith("https://") || source.startsWith("ssh://") || source.startsWith("git://")) {
                require(ref == null || ref.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9/._-]{0,200}"))) { "Invalid Git ref" }
                val repository = Files.createDirectory(temporary.resolve("repository"))
                git(listOf("init"), repository)
                git(listOf("remote", "add", "origin", source), repository)
                git(listOf("fetch", "--depth", "1", "origin", ref ?: "HEAD"), repository)
                git(listOf("checkout", "--detach", "FETCH_HEAD"), repository)
                commit = git(listOf("rev-parse", "HEAD"), repository).trim()
                copyPackage(repository, packageDir, skipGit = true)
            } else {
                require(ref == null) { "A ref is only valid for Git sources" }
                val path = Path.of(source)
                require(path.isAbsolute) { "Provide an absolute package path" }
                if (Files.isDirectory(path)) copyPackage(path, packageDir) else unzip(path, packageDir)
            }
            require(!Files.exists(packageDir.resolve(".dependencies"))) { "Dependency locks are generated by the host, not supplied by packages" }
            require(!Files.exists(packageDir.resolve(".origin"))) { "Git provenance is generated by the host, not supplied by packages" }
            if (commit != null) Files.writeString(packageDir.resolve(".origin"), commit)
            checkCancelled()
            return action(packageDir, commit)
        } finally { deleteTree(temporary) }
    }

    private fun copyPackage(source: Path, destination: Path, skipGit: Boolean = false) {
        var size = 0L
        var count = 0
        Files.walk(source).use { paths -> paths.forEach { path ->
            checkCancelled()
            val name = source.relativize(path).toString().replace('\\', '/')
            if (name.isEmpty() || skipGit && (name == ".git" || name.startsWith(".git/"))) return@forEach
            require(++count <= 10000) { "Too many plugin files" }
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
        } finally {
            val interrupted = Thread.interrupted()
            try {
                if (process.isAlive) {
                    val children = process.descendants().toList()
                    children.forEach { it.destroyForcibly() }; process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                    children.forEach { runCatching { it.onExit().get(5, TimeUnit.SECONDS) } }
                }
                Files.deleteIfExists(output)
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }
}
