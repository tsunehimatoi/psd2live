package io.github.psd2live.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelDownloaderTest {

    @Test
    fun testFormatHelpers() {
        assertEquals("0 B", ModelDownloader.formatBytes(0))
        assertEquals("1.0 KB", ModelDownloader.formatBytes(1024))
        assertEquals("110.5 MB", ModelDownloader.formatBytes(115825363L))

        assertEquals("0 B/s", ModelDownloader.formatSpeed(0))
        assertEquals("512.0 KB/s", ModelDownloader.formatSpeed(524288L))
        assertEquals("3.5 MB/s", ModelDownloader.formatSpeed((3.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testModelDetection(@TempDir tempDir: Path) {
        assertFalse(ModelDownloader.isModelInstalled(tempDir))

        val scale2x = tempDir.resolve("scale2x.pth")
        val noise1 = tempDir.resolve("noise1_scale2x.pth")

        // Missing noise1
        Files.write(scale2x, ByteArray(2_000_000))
        assertFalse(ModelDownloader.isModelInstalled(tempDir))

        // Both present and scale2x > 1MB
        Files.write(noise1, ByteArray(100))
        assertTrue(ModelDownloader.isModelInstalled(tempDir))
    }

    @Test
    fun testExtractModelZip(@TempDir tempDir: Path) {
        val zipFile = tempDir.resolve("model.zip")
        val targetDir = tempDir.resolve("target_model")

        // Create synthetic zip with model structure
        ZipOutputStream(FileOutputStream(zipFile.toFile())).use { zos ->
            zos.putNextEntry(ZipEntry("pretrained_models/swin_unet_v3/art/scale2x.pth"))
            zos.write(ByteArray(1_500_000))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("pretrained_models/swin_unet_v3/art/noise1_scale2x.pth"))
            zos.write(ByteArray(100))
            zos.closeEntry()
        }

        ModelDownloader.extractModelZip(zipFile, targetDir)

        assertTrue(Files.isRegularFile(targetDir.resolve("scale2x.pth")))
        assertTrue(Files.isRegularFile(targetDir.resolve("noise1_scale2x.pth")))
        assertTrue(ModelDownloader.isModelInstalled(targetDir))
    }

    @Test
    fun testExtractNunifZip(@TempDir tempDir: Path) {
        val zipFile = tempDir.resolve("nunif.zip")
        val targetDir = tempDir.resolve("target_nunif")

        // Create synthetic zip with GitHub repo structure (nunif-dev/ prefix)
        ZipOutputStream(FileOutputStream(zipFile.toFile())).use { zos ->
            zos.putNextEntry(ZipEntry("nunif-dev/waifu2x/models.py"))
            zos.write("# waifu2x".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("nunif-dev/nunif/utils.py"))
            zos.write("# nunif".toByteArray())
            zos.closeEntry()
        }

        ModelDownloader.extractNunifZip(zipFile, targetDir)

        assertTrue(Files.isRegularFile(targetDir.resolve("waifu2x/models.py")))
        assertTrue(Files.isRegularFile(targetDir.resolve("nunif/utils.py")))
        assertTrue(ModelDownloader.isNunifInstalled(targetDir))
    }
}

