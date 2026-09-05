package io.github.psd2live.project

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable, unencrypted project envelope. The manifest inventories every payload byte. */
internal object ProjectArchive {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    fun writeJson(path: Path, value: JsonElement) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(JsonElement.serializer(), value))
    }
    fun readJson(path: Path): JsonObject = json.parseToJsonElement(Files.readString(path)).jsonObject
    private fun digest(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun write(directory: Path, target: Path, projectId: String) {
        val files = Files.walk(directory).use { paths -> paths.filter(Files::isRegularFile).sorted().toList() }
        writeJson(directory.resolve("manifest.json"), buildJsonObject {
            put("format", "PSD2Live"); put("version", 1); put("projectId", projectId)
            putJsonObject("files") { files.forEach { file ->
                val name = directory.relativize(file).toString().replace('\\', '/')
                if (name != "manifest.json") put(name, digest(file))
            } }
        })
        val destination = target.toAbsolutePath().normalize()
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".psd2live-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                Files.walk(directory).use { paths -> paths.filter(Files::isRegularFile).sorted().forEach { file ->
                    zip.putNextEntry(ZipEntry(directory.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, zip); zip.closeEntry()
                } }
            }
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            // Verify the actual completed archive before replacing the previous saved project.
            val verification = extract(temporary)
            deleteTemporaryDirectory(verification)
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun extract(file: Path): Path {
        val root = Files.createTempDirectory("psd2live-project-").toAbsolutePath().normalize()
        try {
            ZipFile(file.toFile()).use { zip ->
                val names = mutableSetOf<String>()
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
                        zip.getInputStream(entry).use { input -> Files.newOutputStream(path).use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                total += n; require(total <= 64L * 1024 * 1024 * 1024) { "Project exceeds 64 GiB unpacked limit" }
                                output.write(buffer, 0, n)
                            }
                        } }
                    }
                }
                val manifest = readJson(root.resolve("manifest.json"))
                require(manifest["format"]?.jsonPrimitive?.content == "PSD2Live" && manifest["version"]?.jsonPrimitive?.int == 1) { "Unsupported project format/version" }
                val inventory = manifest.getValue("files").jsonObject
                require(names.filterNot { it.endsWith('/') || it == "manifest.json" }.toSet() == inventory.keys) { "Project inventory mismatch" }
                inventory.forEach { (name, hash) -> require(digest(root.resolve(name)) == hash.jsonPrimitive.content) { "Project resource checksum mismatch: $name" } }
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
