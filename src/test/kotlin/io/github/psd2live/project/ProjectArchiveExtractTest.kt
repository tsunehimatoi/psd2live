package io.github.psd2live.project

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Malformed archives must not make distinct entries overwrite the same extracted file. */
class ProjectArchiveExtractTest {
    @TempDir lateinit var temp: Path

    @Test fun rejectsAliasedFileEntriesBeforeParallelExtraction() {
        // Both names are distinct in the ZIP and in the manifest, but resolve to one disk path.
        val archive = archive(
            "assets/pose.png" to "first".encodeToByteArray(),
            "assets//pose.png" to "second".encodeToByteArray(),
        )

        val failure = assertFailsWith<IllegalArgumentException> { ProjectArchive.extract(archive) }
        assertContains(failure.message.orEmpty(), "Duplicate normalized project entry")
    }

    @Test fun rejectsNormalizedAliasesEvenWhenCaseDiffers() {
        // The old case-folded name set misses the extra slash; Windows would also
        // consider these paths identical, regardless of their original casing.
        val archive = archive(
            "assets/pose.png" to "first".encodeToByteArray(),
            "ASSETS//POSE.PNG" to "second".encodeToByteArray(),
        )

        val failure = assertFailsWith<IllegalArgumentException> { ProjectArchive.extract(archive) }
        assertContains(failure.message.orEmpty(), "Duplicate normalized project entry")
    }

    @Test fun rejectsAliasedDirectoryEntries() {
        val archive = archive(
            "assets/sub/" to byteArrayOf(),
            "assets//sub/" to byteArrayOf(),
        )

        val failure = assertFailsWith<IllegalArgumentException> { ProjectArchive.extract(archive) }
        assertContains(failure.message.orEmpty(), "Duplicate normalized project entry")
    }

    @Test fun extractsDistinctEntriesWithVerifiedContent() {
        val first = "first".encodeToByteArray()
        val second = "second".encodeToByteArray()
        val archive = archive("assets/pose.png" to first, "assets/other.png" to second)

        val extracted = ProjectArchive.extract(archive)
        try {
            assertContentEquals(first, Files.readAllBytes(extracted.resolve("assets/pose.png")))
            assertContentEquals(second, Files.readAllBytes(extracted.resolve("assets/other.png")))
        } finally {
            ProjectArchive.deleteTemporaryDirectory(extracted)
        }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): Path {
        val path = temp.resolve("model.psd2live")
        val manifest = buildJsonObject {
            put("format", "PSD2Live")
            put("version", ProjectFormatV2.VERSION)
            put("projectId", "archive-path-test")
            putJsonObject("files") {
                entries.filterNot { it.first.endsWith('/') }.forEach { (name, bytes) ->
                    put(name, sha256(bytes))
                }
            }
        }.toString().encodeToByteArray()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entries.forEach { (name, bytes) -> add(name, bytes) }
            add(ProjectArchive.MANIFEST, manifest)
        }
        return path
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
