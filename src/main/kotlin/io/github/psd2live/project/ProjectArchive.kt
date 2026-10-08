package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Portable, unencrypted project envelope. The manifest inventories every payload byte.
 *
 * Payloads that are compressed already (PNG rasters, a CMO3 source) are written `STORED`, everything else is
 * DEFLATEd, and the manifest is the last entry. Reading accepts either method and any entry order, as earlier
 * builds wrote every entry DEFLATEd with the manifest among them.
 */
internal object ProjectArchive {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    const val MANIFEST = "manifest.json"
    fun writeJson(path: Path, value: JsonElement) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(JsonElement.serializer(), value))
    }
    fun readJson(path: Path): JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject
    /** Versions [extract] opens: v1 archives migrate on open, saves write [ProjectFormatV2.VERSION]. */
    val readableVersions = setOf(1, ProjectFormatV2.VERSION)

    /** Entries always written without compression: their content is compressed already, so DEFLATE would only cost time. */
    fun isStored(name: String): Boolean = name.endsWith(".png", ignoreCase = true) || name.endsWith(".cmo3", ignoreCase = true)

    /** Other files at least this long (other than JSON) are sampled ([compresses]) before they are DEFLATEd. */
    private const val SAMPLED_BYTES = 1L shl 20
    private const val SAMPLE_WINDOWS = 16
    private const val SAMPLE_WINDOW = 1 shl 16

    /**
     * Whether [file] is worth DEFLATE: [SAMPLE_WINDOWS] windows spread over it shrink to under half at the fastest
     * level. A source PSD whose layer data is RLE-compressed barely shrinks, and DEFLATE over it would be most of
     * a save's time; one with raw channel data shrinks a lot, and quickly.
     */
    private fun compresses(file: Path, size: Long): Boolean = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED, true)
        try {
            val buffer = java.nio.ByteBuffer.allocate(SAMPLE_WINDOW)
            val output = ByteArray(SAMPLE_WINDOW + 1024)
            var read = 0L
            var written = 0L
            for (window in 0 until SAMPLE_WINDOWS) {
                buffer.clear()
                val position = (size - SAMPLE_WINDOW).coerceAtLeast(0) * window / (SAMPLE_WINDOWS - 1)
                while (buffer.hasRemaining() && channel.read(buffer, position + buffer.position()) > 0) {}
                deflater.reset()
                deflater.setInput(buffer.array(), 0, buffer.position())
                deflater.finish()
                while (!deflater.finished()) written += deflater.deflate(output)
                read += buffer.position()
            }
            written < read / 2
        } finally { deflater.end() }
    }

    /** The SHA-256, CRC-32 and length of one entry's bytes. */
    internal data class Digest(val sha256: String, val crc: Long, val size: Long)

    /**
     * Digests of files already read, by archive entry name, length and modification time. A save stages rasters
     * by linking or copying (with their attributes) the working store's immutable, content-addressed files, and
     * opening records each extracted file, so an unchanged raster is not hashed again on every save. A stale hit
     * cannot pass unnoticed: a STORED entry's bytes are checked against its CRC and length as they are written,
     * and the written archive is read back against every CRC before it replaces the target.
     */
    internal object Digests {
        private const val LIMIT = 200_000
        private val cache = object : LinkedHashMap<String, Digest>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Digest>?) = size > LIMIT
        }
        private fun key(name: String, file: Path): String {
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            return "$name|${attributes.size()}|${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}"
        }
        fun of(name: String, file: Path): Digest {
            val key = key(name, file)
            synchronized(cache) { cache[key] }?.let { return it }
            return Files.newInputStream(file).use { measure(it, null) }.also { remember(name, file, it) }
        }
        fun remember(name: String, file: Path, digest: Digest) {
            val key = key(name, file)
            synchronized(cache) { cache[key] = digest }
        }
        internal fun clear() = synchronized(cache) { cache.clear() }
    }

    /**
     * Copies [source] to [target] keeping its modification time (and nothing else: a read-only source would leave
     * a staging file that cannot be deleted), so [Digests] knows the copy.
     */
    fun copyKeepingTime(source: Path, target: Path, vararg options: StandardCopyOption) {
        Files.copy(source, target, *options)
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source))
    }

    /** Reads [input] to its end, copying it to [output] when given, and returns its digest; [counted] sees the running length. */
    private fun measure(input: InputStream, output: OutputStream?, counted: (Long) -> Unit = {}): Digest {
        val hash = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(1 shl 16)
        var size = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            size += n; counted(size)
            hash.update(buffer, 0, n); crc.update(buffer, 0, n)
            output?.write(buffer, 0, n)
        }
        return Digest(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, crc.value, size)
    }

    /**
     * Packs [directory] into [target]. Each file is read once (a STORED entry whose digest is known is only
     * copied), the manifest is built from the digests taken while writing, and the finished archive is read back
     * (entry set, lengths, CRCs and manifest) before it atomically replaces [target].
     */
    fun write(directory: Path, target: Path, projectId: String, version: Int = ProjectFormatV2.VERSION, beforeReplace: () -> Unit = {}) {
        val files = Files.walk(directory).use { paths -> paths.filter(Files::isRegularFile).toList() }
            .map { directory.relativize(it).toString().replace('\\', '/') to it }
            .filter { it.first != MANIFEST }.sortedBy { it.first }
        val destination = target.toAbsolutePath().normalize()
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".psd2live-", ".tmp")
        try {
            val written = LinkedHashMap<String, Digest>()
            lateinit var manifest: ByteArray
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val zip = ZipOutputStream(BufferedOutputStream(Channels.newOutputStream(channel), 1 shl 16))
                for ((name, file) in files) {
                    val entry = ZipEntry(name)
                    val size = Files.size(file)
                    val large = size >= SAMPLED_BYTES && !name.endsWith(".json")
                    if (isStored(name) || large && !compresses(file, size)) {
                        val digest = Digests.of(name, file)
                        entry.method = ZipEntry.STORED
                        entry.size = digest.size; entry.compressedSize = digest.size; entry.crc = digest.crc
                        // ZipOutputStream checks the bytes it is given against this CRC and length.
                        zip.putNextEntry(entry); Files.copy(file, zip); zip.closeEntry()
                        written[name] = digest
                    } else {
                        // A large binary that does compress takes the fastest level; JSON keeps the default.
                        zip.setLevel(if (large) java.util.zip.Deflater.BEST_SPEED else java.util.zip.Deflater.DEFAULT_COMPRESSION)
                        zip.putNextEntry(entry)
                        written[name] = Files.newInputStream(file).use { measure(it, zip) }
                        zip.closeEntry()
                    }
                }
                manifest = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                    put("format", "PSD2Live"); put("version", version); put("projectId", projectId)
                    putJsonObject("files") { written.forEach { (name, digest) -> put(name, digest.sha256) } }
                }).encodeToByteArray()
                zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest); zip.closeEntry()
                zip.finish(); zip.flush()
                channel.force(true)
                zip.close()
            }
            // Verify the actual completed archive before replacing the previous saved project.
            verify(temporary, written, manifest)
            beforeReplace()
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Reads [archive] back as a stream: exactly the [expected] entries and [manifest], each with its length and CRC. */
    internal fun verify(archive: Path, expected: Map<String, Digest>, manifest: ByteArray) {
        ZipFile(archive.toFile()).use { zip ->
            val names = HashSet<String>()
            val buffer = ByteArray(1 shl 16)
            for (entry in zip.entries()) {
                val name = entry.name
                require(names.add(name)) { "Duplicate project entry: $name" }
                val digest = if (name == MANIFEST) null else requireNotNull(expected[name]) { "Unexpected project entry: $name" }
                val crc = CRC32()
                var size = 0L
                val bytes = if (digest == null) ByteArrayOutputStream(manifest.size) else null
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        size += n; crc.update(buffer, 0, n); bytes?.write(buffer, 0, n)
                    }
                }
                if (digest != null) require(size == digest.size && entry.size == digest.size && crc.value == digest.crc && entry.crc == digest.crc) {
                    "Project entry does not match what was written: $name"
                } else require(bytes!!.toByteArray().contentEquals(manifest)) { "Project manifest does not match what was written" }
            }
            require(names.size == expected.size + 1 && MANIFEST in names) { "Project archive is missing entries" }
        }
    }

    fun extract(file: Path): Path {
        val root = Files.createTempDirectory("psd2live-project-").toAbsolutePath().normalize()
        try {
            ZipFile(file.toFile()).use { zip ->
                val names = mutableSetOf<String>()
                val digests = HashMap<String, Digest>()
                var total = 0L
                zip.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    require(name.isNotBlank() && !name.contains('\\') && !name.contains(':') &&
                        !name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) { "Invalid project entry: $name" }
                    require(names.add(name)) { "Duplicate project entry: $name" }
                    require(names.size <= 1_000_000) { "Too many project entries" }
                    val path = root.resolve(name).normalize()
                    require(path.startsWith(root)) { "Project entry escapes archive" }
                    if (!entry.isDirectory) {
                        Files.createDirectories(path.parent)
                        val before = total
                        // Digested while written, so each entry is read from the archive once.
                        digests[name] = zip.getInputStream(entry).use { input -> Files.newOutputStream(path).use { output ->
                            measure(input, output) { size ->
                                total = before + size
                                require(total <= 64L * 1024 * 1024 * 1024) { "Project exceeds 64 GiB unpacked limit" }
                            }
                        } }
                    }
                }
                val manifest = readJson(root.resolve(MANIFEST))
                require(manifest["format"]?.jsonPrimitive?.content == "PSD2Live" && manifest["version"]?.jsonPrimitive?.intOrNull in readableVersions) {
                    "Unsupported project format/version"
                }
                val inventory = manifest.getValue("files").jsonObject
                require(names.filterNot { it.endsWith('/') || it == MANIFEST }.toSet() == inventory.keys) { "Project inventory mismatch" }
                inventory.forEach { (name, hash) ->
                    val digest = digests.getValue(name)
                    require(digest.sha256 == hash.jsonPrimitive.content) { "Project resource checksum mismatch: $name" }
                    // The next save of this project copies the file without hashing it again.
                    if (zip.getEntry(name).method == ZipEntry.STORED || isStored(name)) Digests.remember(name, root.resolve(name), digest)
                }
            }
            return root
        } catch (failure: Throwable) { deleteTemporaryDirectory(root); throw failure }
    }
    /** Only accepts directories created by this project subsystem in the OS temporary directory. */
    fun deleteTemporaryDirectory(root: Path) {
        val path = root.toAbsolutePath().normalize()
        require(path.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize() && path.fileName.toString().startsWith("psd2live-project-"))
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
    fun installationProjectsDirectory(): Path {
        System.getProperty("compose.application.resources.dir")?.let { return Path.of(it).toAbsolutePath().parent.resolve("projects") }
        val location = Path.of(ProjectArchive::class.java.protectionDomain.codeSource.location.toURI())
        return if (Files.isRegularFile(location)) location.parent.parent.resolve("projects") else Path.of(System.getProperty("user.dir"), "projects").toAbsolutePath()
    }
}
