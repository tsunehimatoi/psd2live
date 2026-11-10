package io.github.psd2live.project

import kotlinx.serialization.json.*
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

    private val CONTENT_ADDRESSED = Regex("document/.+/([0-9a-f]{64})\\.(json|bin)")

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
            // Size and time alone collide when two different sources share both, and a STORED write then
            // fails its CRC check. The file key separates them; a copy without one is keyed by its path.
            val identity = attributes.fileKey()?.toString() ?: file.toAbsolutePath().normalize().toString()
            return "$name|${attributes.size()}|${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}|$identity"
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

    /** Threads for packing, reading back and unpacking: entries are independent. */
    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    private fun pool(threads: Int, name: String): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(threads) { task -> Thread(task, name).apply { isDaemon = true } }

    /** The value of [future], rethrowing what its task threw. */
    private fun <T> result(future: java.util.concurrent.Future<T>): T = try { future.get() } catch (failure: java.util.concurrent.ExecutionException) {
        throw failure.cause ?: failure
    }

    /** Entries up to this long are read and compressed ahead on several threads; longer ones stream as they are written. */
    private const val PARALLEL_BYTES = 4L shl 20
    /** At most this many bytes of entries read ahead and not yet written. */
    private const val AHEAD_BYTES = 64L shl 20

    /** [bytes] as a raw DEFLATE stream at [level], with their digest. */
    private class Packed(val digest: Digest, val data: ByteArray)

    private fun pack(bytes: ByteArray, level: Int): Packed {
        val digest = measure(bytes.inputStream(), null)
        val deflater = java.util.zip.Deflater(level, true)
        try {
            deflater.setInput(bytes); deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + 64)
            val buffer = ByteArray(1 shl 16)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return Packed(digest, out.toByteArray())
        } finally { deflater.end() }
    }

    /**
     * Packs [directory] into [target]. Each file is read once (a STORED entry whose digest is known is only
     * copied), the manifest is built from the digests taken while writing, and the finished archive is read back
     * (entry set, lengths, CRCs and manifest) before it atomically replaces [target]. Smaller entries are compressed
     * on several threads ahead of the one writing them in order. [progress] sees the share of bytes written and read
     * back, from 0 to 1, on the calling thread.
     */
    fun write(directory: Path, target: Path, projectId: String, version: Int = ProjectFormatV2.VERSION,
              progress: (Double) -> Unit = {}, beforeReplace: () -> Unit = {}) {
        val files = Files.walk(directory).use { paths -> paths.filter(Files::isRegularFile).toList() }
            .map { directory.relativize(it).toString().replace('\\', '/') to it }
            .filter { it.first != MANIFEST }.sortedBy { it.first }
        // Writing hashes and compresses, reading back only inflates: the read takes the smaller share.
        val total = files.sumOf { Files.size(it.second) }.coerceAtLeast(1L).toDouble()
        var done = 0L
        val destination = target.toAbsolutePath().normalize()
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".psd2live-", ".tmp")
        val threads = threads()
        val workers = pool(threads, "psd2live-archive-pack")
        try {
            class Plan(val name: String, val file: Path, val size: Long, val stored: Boolean, val level: Int) {
                val ahead get() = !stored && size <= PARALLEL_BYTES
            }
            val plans = files.map { (name, file) ->
                val size = Files.size(file)
                val large = size >= SAMPLED_BYTES && !name.endsWith(".json")
                // A large binary that does compress takes the fastest level; JSON keeps the default.
                Plan(name, file, size, isStored(name) || large && !compresses(file, size),
                    if (large) java.util.zip.Deflater.BEST_SPEED else java.util.zip.Deflater.DEFAULT_COMPRESSION)
            }
            val written = LinkedHashMap<String, Digest>()
            lateinit var manifest: ByteArray
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val zip = ZipWriter(channel)
                val packing = arrayOfNulls<java.util.concurrent.Future<Packed>>(plans.size)
                var next = 0
                var ahead = 0L
                fun readAhead() {
                    while (next < plans.size && (ahead < AHEAD_BYTES || ahead == 0L)) {
                        val plan = plans[next]
                        if (plan.ahead) { packing[next] = workers.submit<Packed> { pack(Files.readAllBytes(plan.file), plan.level) }; ahead += plan.size }
                        next++
                    }
                }
                for ((i, plan) in plans.withIndex()) {
                    readAhead()
                    val before = done
                    val counted = { length: Long -> done = before + length; progress(0.8 * done / total) }
                    val digest = packing[i]?.let { future ->
                        packing[i] = null
                        val packed = result(future)
                        ahead -= plan.size
                        zip.add(plan.name, ZipWriter.DEFLATED, packed.digest.crc, packed.digest.size, java.nio.ByteBuffer.wrap(packed.data))
                        counted(plan.size)
                        packed.digest
                    } ?: if (plan.stored) {
                        val digest = Digests.of(plan.name, plan.file)
                        zip.stream(plan.name, ZipWriter.STORED, digest.size) { out ->
                            // The bytes copied must be the ones the digest was taken of.
                            val copied = Files.newInputStream(plan.file).use { measureCrc(it, Channels.newOutputStream(out), counted) }
                            require(copied.first == digest.crc && copied.second == digest.size) { "Project entry changed while written: ${plan.name}" }
                            digest.crc
                        }
                        digest
                    } else {
                        lateinit var digest: Digest
                        zip.stream(plan.name, ZipWriter.DEFLATED, plan.size) { out ->
                            val deflater = java.util.zip.Deflater(plan.level, true)
                            try {
                                val stream = java.util.zip.DeflaterOutputStream(Channels.newOutputStream(out), deflater, 1 shl 16)
                                digest = Files.newInputStream(plan.file).use { measure(it, stream, counted) }
                                stream.finish(); stream.flush()
                            } finally { deflater.end() }
                            digest.crc
                        }
                        digest
                    }
                    written[plan.name] = digest
                    // Document files are named by their hash; a save links them from the working store's mirror.
                    CONTENT_ADDRESSED.matchEntire(plan.name)?.let { match ->
                        require(digest.sha256 == match.groupValues[1]) { "Project entry does not match its name: ${plan.name}" }
                    }
                }
                manifest = json.encodeToString(JsonElement.serializer(), buildJsonObject {
                    put("format", "PSD2Live"); put("version", version); put("projectId", projectId)
                    putJsonObject("files") { written.forEach { (name, digest) -> put(name, digest.sha256) } }
                }).encodeToByteArray()
                pack(manifest, java.util.zip.Deflater.DEFAULT_COMPRESSION).let { packed ->
                    zip.add(MANIFEST, ZipWriter.DEFLATED, packed.digest.crc, packed.digest.size, java.nio.ByteBuffer.wrap(packed.data))
                }
                zip.finish()
                channel.force(true)
            }
            // Verify the actual completed archive before replacing the previous saved project.
            verify(temporary, written, manifest) { read -> progress(0.8 + 0.2 * (read / total).coerceAtMost(1.0)) }
            beforeReplace()
            // On Windows a scanner or previewer briefly holding the old project refuses the replace; wait it out.
            var attempt = 0
            while (true) {
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); break }
                catch (denied: java.nio.file.AccessDeniedException) { if (++attempt >= 5) throw denied; Thread.sleep(100L * attempt) }
            }
        } finally {
            workers.shutdownNow()
            Files.deleteIfExists(temporary)
        }
    }

    /** Copies [input] to [output] and returns the CRC-32 and length of what was copied. */
    private fun measureCrc(input: InputStream, output: OutputStream, counted: (Long) -> Unit): Pair<Long, Long> {
        val crc = CRC32()
        val buffer = ByteArray(1 shl 16)
        var size = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            crc.update(buffer, 0, n); output.write(buffer, 0, n); size += n; counted(size)
        }
        return crc.value to size
    }

    /**
     * Reads [archive] back: exactly the [expected] entries and [manifest], each with its length and CRC, the entries
     * read on several threads. [counted] sees the running number of bytes read, on the calling thread.
     */
    internal fun verify(archive: Path, expected: Map<String, Digest>, manifest: ByteArray, counted: (Long) -> Unit = {}) {
        val names = ZipFile(archive.toFile()).use { zip ->
            val names = ArrayList<String>()
            val seen = HashSet<String>()
            for (entry in zip.entries()) {
                require(seen.add(entry.name)) { "Duplicate project entry: ${entry.name}" }
                if (entry.name == MANIFEST) continue
                val digest = requireNotNull(expected[entry.name]) { "Unexpected project entry: ${entry.name}" }
                require(entry.size == digest.size && entry.crc == digest.crc) { "Project entry does not match what was written: ${entry.name}" }
                names += entry.name
            }
            require(seen.size == expected.size + 1 && MANIFEST in seen) { "Project archive is missing entries" }
            val read = zip.getInputStream(zip.getEntry(MANIFEST)).use { it.readBytes() }
            require(read.contentEquals(manifest)) { "Project manifest does not match what was written" }
            names
        }
        val threads = threads()
        val readers = pool(threads, "psd2live-archive-verify")
        val zips = java.util.concurrent.ConcurrentLinkedQueue<ZipFile>()
        val local = ThreadLocal.withInitial { ZipFile(archive.toFile()).also(zips::add) }
        try {
            val futures = names.map { name ->
                readers.submit<Long> {
                    val zip = local.get()
                    val crc = CRC32()
                    val buffer = ByteArray(1 shl 16)
                    var size = 0L
                    zip.getInputStream(zip.getEntry(name)).use { input ->
                        while (true) { val n = input.read(buffer); if (n < 0) break; size += n; crc.update(buffer, 0, n) }
                    }
                    val digest = expected.getValue(name)
                    require(size == digest.size && crc.value == digest.crc) { "Project entry does not match what was written: $name" }
                    size
                }
            }
            var read = 0L
            for (future in futures) { read += result(future); counted(read) }
        } finally {
            readers.shutdownNow()
            readers.awaitTermination(10, TimeUnit.SECONDS)
            zips.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Unpacks [file] into a new temporary directory, the entries on several threads; [progress] sees the share of
     * declared entry bytes written, from 0 to 1, on the calling thread.
     */
    fun extract(file: Path, progress: (Double) -> Unit = {}): Path {
        val root = Files.createTempDirectory("psd2live-project-").toAbsolutePath().normalize()
        val zips = java.util.concurrent.ConcurrentLinkedQueue<ZipFile>()
        val local = ThreadLocal.withInitial { ZipFile(file.toFile()).also(zips::add) }
        val writers = pool(threads(), "psd2live-archive-extract")
        try {
            ZipFile(file.toFile()).use { zip ->
                // Declared sizes only pace the progress; the limit below counts the bytes actually written.
                val declared = zip.entries().asSequence().sumOf { it.size.coerceAtLeast(0L) }.coerceAtLeast(1L).toDouble()
                val names = mutableSetOf<String>()
                val destinations = HashSet<String>()
                val files = ArrayList<String>()
                zip.entries().asSequence().forEach { entry ->
                    val name = entry.name
                    require(name.isNotBlank() && !name.contains('\\') && !name.contains(':') &&
                        !name.startsWith('/') && name.split('/').none { it == ".." || it == "." }) { "Invalid project entry: $name" }
                    // Windows drops a trailing dot or space and ignores case, so such names would land on one file
                    // and the last write would replace a digest already checked.
                    require(name.trimEnd('/').split('/').none { it.endsWith('.') || it.endsWith(' ') }) { "Invalid project entry: $name" }
                    require(names.add(name)) { "Duplicate project entry: $name" }
                    require(names.size <= 1_000_000) { "Too many project entries" }
                    val path = root.resolve(name).normalize()
                    require(path.startsWith(root)) { "Project entry escapes archive" }
                    // Normalize before case folding: different ZIP names can target one file
                    // (e.g. assets/pose.png and ASSETS//POSE.PNG on Windows).
                    val destination = root.relativize(path).toString().replace('\\', '/').lowercase(java.util.Locale.ROOT)
                    require(destinations.add(destination)) { "Duplicate normalized project entry: $name" }
                    if (!entry.isDirectory) { Files.createDirectories(path.parent); files += name }
                }
                val total = java.util.concurrent.atomic.AtomicLong()
                val futures = files.map { name ->
                    writers.submit<Digest> {
                        val reader = local.get()
                        // Digested while written, so each entry is read from the archive once.
                        reader.getInputStream(reader.getEntry(name)).use { input -> Files.newOutputStream(root.resolve(name)).use { output ->
                            var counted = 0L
                            measure(input, output) { size ->
                                val added = size - counted; counted = size
                                require(total.addAndGet(added) <= 64L * 1024 * 1024 * 1024) { "Project exceeds 64 GiB unpacked limit" }
                            }
                        } }
                    }
                }
                val digests = HashMap<String, Digest>()
                var written = 0L
                for ((name, future) in files.zip(futures)) {
                    val digest = result(future)
                    digests[name] = digest
                    written += digest.size
                    progress((written / declared).coerceAtMost(1.0))
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
        } catch (failure: Throwable) {
            writers.shutdownNow()
            writers.awaitTermination(10, TimeUnit.SECONDS)
            deleteTemporaryDirectory(root); throw failure
        } finally {
            writers.shutdownNow()
            zips.forEach { runCatching { it.close() } }
        }
    }
    /** Only accepts directories created by this project subsystem in the OS temporary directory. */
    fun deleteTemporaryDirectory(root: Path) {
        val path = root.toAbsolutePath().normalize()
        // The temporary directory may be named through a link or a Windows short name; compare where both lead.
        fun real(of: Path) = runCatching { of.toRealPath() }.getOrElse { of.toAbsolutePath().normalize() }
        require(real(path.parent) == real(Path.of(System.getProperty("java.io.tmpdir"))) && path.fileName.toString().startsWith("psd2live-project-"))
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
