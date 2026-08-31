package io.github.autolive2d.core

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.moc3.Moc3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class PipelineIntegrationTest {
	@Test
	fun `reference PSD exports a self-contained validated family`() {
		val sample = Path.of("..", "Anime2.5DRig", "sample.psd").toAbsolutePath().normalize()
		if (!Files.isRegularFile(sample)) return
		val output = createTempDirectory("autolive2d-e2e-")
		val result = AutoLive2DPipeline().run(
			sample,
			output,
			PipelineConfig(atlasSize = 2048, meshSpacing = 128),
		)
		val byExtension = result.exportedFiles.associateBy { it.path.fileName.toString().substringAfter('.') }
		assertTrue(result.exportedFiles.any { it.path.fileName.toString().endsWith(".idle.motion3.json") })
		Moc3.read(Files.readAllBytes(checkNotNull(byExtension["moc3"]).path))
		Cmo3.read(Files.readAllBytes(checkNotNull(byExtension["cmo3"]).path))
		assertTrue(result.warnings.none { "FractionalDrawOrder" in it })
	}
}
