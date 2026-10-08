package io.github.psd2live.project

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import kotlin.test.*

/** The archive envelope: what a save writes, how it is checked before replacing a project, and what opens. */
class ProjectArchiveTest {
	@TempDir lateinit var temporary: Path

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

	/** A staging directory as a save leaves it: JSON, a PNG raster, a large binary of each kind. */
	private fun staging(): Path {
		val directory = Files.createTempDirectory("psd2live-project-")
		fun put(name: String, bytes: ByteArray) = directory.resolve(name).also { Files.createDirectories(it.parent); Files.write(it, bytes) }
		put("history/HEAD.json", """{"headNodeId":"a"}""".encodeToByteArray())
		put("assets/raster.png", Random(1).nextBytes(4096))
		put("source/original.psd", Random(2).nextBytes(3 shl 20))
		put("auxiliary/flat.bin", ByteArray(3 shl 20))
		return directory
	}

	private fun read(archive: Path): Map<String, Pair<Int, ByteArray>> = ZipFile(archive.toFile()).use { zip ->
		zip.entries().asSequence().associate { it.name to (it.method to zip.getInputStream(it).readBytes()) }
	}

	@Test fun writeStoresCompressedPayloadsListsEveryDigestAndExtractsTheSameBytes() {
		val directory = staging()
		try {
			val target = temporary.resolve("project.psd2live")
			ProjectArchive.write(directory, target, "project")
			val entries = read(target)
			val names = ZipFile(target.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
			assertEquals(ProjectArchive.MANIFEST, names.last(), "the manifest is written last, from the digests taken while writing")
			assertEquals(ZipEntry.STORED, entries.getValue("assets/raster.png").first)
			assertEquals(ZipEntry.STORED, entries.getValue("source/original.psd").first, "a large binary that does not compress is stored")
			assertEquals(ZipEntry.DEFLATED, entries.getValue("auxiliary/flat.bin").first, "a large binary that compresses is deflated")
			assertEquals(ZipEntry.DEFLATED, entries.getValue("history/HEAD.json").first)
			val manifest = Json.parseToJsonElement(entries.getValue(ProjectArchive.MANIFEST).second.decodeToString()).jsonObject
			assertEquals(2, manifest.getValue("version").jsonPrimitive.int)
			val files = manifest.getValue("files").jsonObject
			assertEquals(entries.keys - ProjectArchive.MANIFEST, files.keys)
			for ((name, hash) in files) assertEquals(sha256(entries.getValue(name).second), hash.jsonPrimitive.content, name)
			val extracted = ProjectArchive.extract(target)
			try {
				for (name in files.keys) assertContentEquals(Files.readAllBytes(directory.resolve(name)), Files.readAllBytes(extracted.resolve(name)), name)
			} finally { ProjectArchive.deleteTemporaryDirectory(extracted) }
		} finally { ProjectArchive.deleteTemporaryDirectory(directory) }
	}

	@Test fun archivesWrittenEntirelyDeflatedWithTheManifestFirstStillOpen() {
		val payload = mapOf("history/HEAD.json" to "{}".encodeToByteArray(), "assets/raster.png" to Random(3).nextBytes(512))
		val target = temporary.resolve("earlier.psd2live")
		ZipOutputStream(Files.newOutputStream(target)).use { zip ->
			val manifest = buildJsonObject {
				put("format", "PSD2Live"); put("version", 2); put("projectId", "project")
				putJsonObject("files") { payload.forEach { (name, bytes) -> put(name, sha256(bytes)) } }
			}
			zip.putNextEntry(ZipEntry(ProjectArchive.MANIFEST)); zip.write(manifest.toString().encodeToByteArray()); zip.closeEntry()
			payload.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
		}
		val extracted = ProjectArchive.extract(target)
		try {
			payload.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(extracted.resolve(name))) }
		} finally { ProjectArchive.deleteTemporaryDirectory(extracted) }
	}

	@Test fun aStaleDigestFailsTheWriteAndKeepsTheExistingProject() {
		val directory = staging()
		try {
			val target = temporary.resolve("kept.psd2live")
			Files.write(target, byteArrayOf(1, 2, 3))
			val raster = directory.resolve("assets/raster.png")
			// A digest that does not describe the file's bytes, as a stale cache entry would.
			ProjectArchive.Digests.remember("assets/raster.png", raster, ProjectArchive.Digest("0".repeat(64), 1L, Files.size(raster)))
			assertFails { ProjectArchive.write(directory, target, "project") }
			assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(target))
			Files.list(temporary).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".tmp") }) }
		} finally { ProjectArchive.Digests.clear(); ProjectArchive.deleteTemporaryDirectory(directory) }
	}

	@Test fun verificationRejectsAnArchiveThatDiffersFromWhatWasWritten() {
		val directory = staging()
		try {
			val target = temporary.resolve("verified.psd2live")
			ProjectArchive.write(directory, target, "project")
			val manifest = read(target).getValue(ProjectArchive.MANIFEST).second
			val digests = ZipFile(target.toFile()).use { zip -> zip.entries().asSequence().filter { it.name != ProjectArchive.MANIFEST }
				.associate { it.name to ProjectArchive.Digest("", it.crc, it.size) } }
			ProjectArchive.verify(target, digests, manifest)
			val (name, digest) = digests.entries.first()
			assertFailsWith<IllegalArgumentException> { ProjectArchive.verify(target, digests + (name to digest.copy(crc = digest.crc xor 1)), manifest) }
			assertFailsWith<IllegalArgumentException> { ProjectArchive.verify(target, digests + ("missing.json" to digest), manifest) }
			assertFailsWith<IllegalArgumentException> { ProjectArchive.verify(target, digests - name, manifest) }
			assertFailsWith<IllegalArgumentException> { ProjectArchive.verify(target, digests, manifest + 32) }
		} finally { ProjectArchive.deleteTemporaryDirectory(directory) }
	}

	/**
	 * A save takes every raster's PNG from the live working store instead of encoding the pixels again: a live
	 * PNG encoded differently from what this build's encoder writes is what the archive holds, and the project
	 * saved twice and reopened has every revision, pixel for pixel.
	 */
	@Test fun savesTakeRastersFromTheLiveStoreAndReopenEveryRevision() = runBlocking {
		val project = SyntheticProject(temporary.resolve("project"), layers = 6, size = 24, revisions = 9)
		val blobs = project.store.projectRoot(SyntheticProject.PROJECT).resolve("blobs")
		val blob = Files.list(blobs).use { paths -> paths.filter { it.fileName.toString().endsWith(".png") }.sorted().findFirst().get() }
		val recoded = java.io.ByteArrayOutputStream().also { out ->
			val writer = ImageIO.getImageWritersByFormatName("png").next()
			ImageIO.createImageOutputStream(out).use { stream ->
				writer.output = stream
				val parameters = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 1f }
				writer.write(null, IIOImage(ImageIO.read(blob.toFile()), null, null), parameters)
			}
			writer.dispose()
		}.toByteArray()
		assertFalse(recoded.contentEquals(Files.readAllBytes(blob)), "the fixture needs a different encoding of the same pixels")
		Files.write(blob, recoded)
		val repository = ProjectRepository(writeHeadCache = false)
		val target = temporary.resolve("saved.psd2live")
		repeat(2) { repository.save(project.capture(), target) }
		assertContentEquals(recoded, read(target).getValue("assets/${blob.fileName}").second)
		assertTrue(read(target).filterKeys { it.endsWith(".png") }.values.all { it.first == ZipEntry.STORED })
		repository.open(target).use { opened ->
			val saved = project.tree.state().selections
			val reopened = opened.history.state().selections
			assertEquals(saved.map { it.node }, reopened.map { it.node })
			for ((before, after) in saved.zip(reopened)) {
				assertEquals(before.node.revisionId, WorkspaceRevisions.of(after.snapshot))
				for ((a, b) in before.snapshot.source.layers.zip(after.snapshot.source.layers)) assertContentEquals(a.raster.rgba, b.raster.rgba)
			}
			// Saving the reopened project takes its rasters from the extracted store the same way.
			val again = temporary.resolve("again.psd2live")
			repository.save(ProjectSaveCapture(opened.projectId, opened.history.state(), JsonObject(emptyMap()), opened.source, opened.store), again)
			assertEquals(read(target).filterKeys { it.endsWith(".png") }.mapValues { it.value.second.toList() },
				read(again).filterKeys { it.endsWith(".png") }.mapValues { it.value.second.toList() })
		}
		assertTrue(Files.isRegularFile(blob), "the live store keeps its files")
	}
}
