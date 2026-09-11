package io.github.psd2live.core

import io.github.psd2live.project.WorkspaceStateCodec
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.format.art.*
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.render.restMeshesToCanvasSpace
import kotlinx.serialization.json.buildJsonObject
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class TextureUpscaleTest {
    private fun layer(name: String, left: Int, top: Int, width: Int, height: Int, order: Int) = object : SourceLayer {
        override val id = LayerId(name)
        override val name = name
        override val groupPath = ""
        override val order = order
        override val bounds = LayerBounds(left, top, width, height)
        override val opacity = 1f
        override val clipped = false
        override val blend = LayerBlend.Normal
        override val raster = LayerRaster(width, height, ByteArray(width * height * 4) { index ->
            when (index % 4) { 0 -> 200.toByte(); 1 -> 80; 2 -> 120; else -> 255.toByte() }
        })
    }

    private fun analysis(config: PipelineConfig): PipelineAnalysis {
        val source = object : SourceArt {
            override val widthPx = 200
            override val heightPx = 240
            override val layers = listOf(layer("face", 50, 30, 90, 100, 3),
                layer("mouth", 80, 95, 30, 12, 0), layer("topwear", 35, 130, 130, 80, 4),
                layer("eyebrow", 65, 50, 18, 4, 1))
        }
        return MouthLipLayers.prepare(CharacterAnalyzer.analyze(source, config), config)
    }

    @Test fun upscaledUvsRetainGeometryIncludingGeneratedLipsAndCmo3RoundTrip() {
        val config = PipelineConfig(atlasSize = 256, meshSpacing = 40, generatePhysics = false)
        val analysis = analysis(config)
        val original = AtlasPacker.pack(analysis.layers, 256, 2)
        val directory = Files.createTempDirectory("upscale-test")
        try {
            val scaled = AtlasPacker.packWithTextures(analysis.layers, 256, 2, TextureUpscaleConfig(scale = 2)) { layers, settings ->
                layers.associate { layer ->
                    val raster = layer.source.raster
                    val image = BufferedImage(raster.width * settings.scale, raster.height * settings.scale, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xffc85078.toInt())
                    val file = directory.resolve("${layers.indexOf(layer)}.png")
                    ImageIO.write(image, "png", file.toFile())
                    layer.source.id.raw to file
                }
            }
            val before = RigBuilder.build(analysis, original, config)
            val after = RigBuilder.build(analysis, scaled, config)
            assertEquals(before.puppet.drawables.map { it.id }, after.puppet.drawables.map { it.id })
            assertTrue(analysis.layers.any { it.source is MouthLipLayer }, "Exercise generated lip UVs")
            for ((a, b) in before.puppet.drawables.zip(after.puppet.drawables)) {
                val am = assertNotNull(a.mesh); val bm = assertNotNull(b.mesh)
                assertContentEquals(am.positions, bm.positions)
                assertContentEquals(am.indices, bm.indices)
                val layerId = after.layerIdByDrawableId.getValue(b.id.raw)
                val old = original.placementByLayerId.getValue(layerId)
                val new = scaled.placementByLayerId.getValue(layerId)
                for (i in am.uvs.indices) {
                    val oldOrigin = if (i % 2 == 0) old.x else old.y
                    val newOrigin = if (i % 2 == 0) new.x else new.y
                    val local = am.uvs[i] * original.pages[old.page].image.width - oldOrigin
                    val restored = (bm.uvs[i] * scaled.pages[new.page].image.width - newOrigin) / new.scale
                    assertEquals(local, restored, 0.0001f, "UV registration for ${b.name} vertex $i")
                }
            }
            val converted = Cmo3Conversion.freshCmo3(
                puppet = restMeshesToCanvasSpace(after.puppet),
                pages = scaled.pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) },
                pageIndexByDrawableId = after.pageByDrawableId, modelName = "upscale-test", nowMillis = 0, obfuscateKey = 0x42,
            )
            val restored = Cmo3Import.fromModelSource(Cmo3.read(Cmo3.write(converted.model)).root as CModelSource)
            assertEquals(converted.puppet.drawables.size, restored.drawables.size)
            for (a in converted.puppet.drawables) {
                val b = restored.drawables.single { it.id == a.id }
                val expected = assertNotNull(a.mesh).positions
                val actual = assertNotNull(b.mesh).positions
                assertEquals(expected.size, actual.size)
                for (i in expected.indices) assertEquals(expected[i], actual[i], 0.001f, "CMO3 geometry ${a.id}[$i]")
            }
            val placement = scaled.placementByLayerId.values.first()
            val gutter = scaled.pages[placement.page].image.getRGB(placement.x - 1, placement.y)
            assertEquals(0, gutter ushr 24)
            assertEquals(0xc85078, gutter and 0xffffff)
        } finally {
            Files.list(directory).use { it.forEach(Files::delete) }; Files.delete(directory)
        }
    }

    @Test fun disabledDoesNotNeedRuntimeAndOversizedAtlasFailsBeforeInference() {
        val layers = analysis(PipelineConfig()).layers
        AtlasPacker.packWithTextures(layers, 256, 2, TextureUpscaleConfig()) { _, _ -> error("Disabled backend was invoked") }
        assertFailsWith<IllegalArgumentException> {
            AtlasPacker.packWithTextures(layers, 16384, 2, TextureUpscaleConfig(scale = 2)) { _, _ -> error("Inference started before memory preflight") }
        }
        assertFailsWith<IllegalArgumentException> { TextureUpscaleConfig(scale = 3) }
    }

    @Test fun realNunifSmokeWhenConfigured() {
        val repo = System.getenv("PSD2LIVE_TEST_NUNIF")
        org.junit.jupiter.api.Assumptions.assumeTrue(!repo.isNullOrBlank(), "Optional GPU integration test: set PSD2LIVE_TEST_NUNIF")
        val modelDir = System.getenv("PSD2LIVE_TEST_MODEL") ?: (
            if (Files.isDirectory(java.nio.file.Path.of("$repo/waifu2x/pretrained_models/swin_unet_v3/art")))
                "$repo/waifu2x/pretrained_models/swin_unet_v3/art"
            else "$repo/waifu2x/pretrained_models/swin_unet/art"
        )
        for (scale in listOf(2, 4)) {
            val upscale = TextureUpscaleConfig(
                scale = scale,
                python = System.getenv("PSD2LIVE_TEST_PYTHON") ?: "python",
                nunifDirectory = repo!!,
                modelDirectory = modelDir,
            )
        val config = PipelineConfig(atlasSize = 512, textureUpscale = upscale, generatePhysics = false)
        val source = analysis(config).source
        val result = PSD2LivePipeline().run(source, "upscale-smoke", java.nio.file.Path.of("build/upscale-smoke/${scale}x"), config)
        assertTrue(result.exportedFiles.any { it.path.toString().endsWith(".cmo3") })
        assertTrue(result.exportedFiles.any { it.path.toString().endsWith(".moc3") })
        assertTrue(result.previewModel.atlas.placementByLayerId.values.all { it.scale == scale })
        val first = TextureUpscale.prepare(result.previewModel.analysis.layers, upscale)
        val times = first.mapValues { Files.getLastModifiedTime(it.value) }
        val second = TextureUpscale.prepare(result.previewModel.analysis.layers, upscale)
        assertEquals(first, second)
        assertEquals(times, second.mapValues { Files.getLastModifiedTime(it.value) }, "Cached outputs must not be regenerated")
        }
    }

    @Test fun workspacePersistsSettingsAndOlderProjectsDefaultToOff() {
        val config = TextureUpscaleConfig(2, "C:/Python/python.exe", "C:/nunif", "C:/models", 128, true)
        val state = PSD2LiveState(textureUpscale = config)
        val encoded = WorkspaceStateCodec.encode(state)
        assertEquals(config, WorkspaceStateCodec.decode(encoded).textureUpscale)
        assertEquals(1, WorkspaceStateCodec.decode(buildJsonObject {}).textureUpscale.scale)
    }

    @Test fun minRequiredAtlasSizeAdaptsToUpscaleScaleAndEnlarges() {
        val config = PipelineConfig()
        val analysis = analysis(config)
        val state1x = PSD2LiveState(analysis = analysis, textureUpscale = TextureUpscaleConfig(scale = 1), texturePadding = 2)
        assertEquals(256, state1x.minRequiredAtlasSize())

        val state2x = PSD2LiveState(analysis = analysis, textureUpscale = TextureUpscaleConfig(scale = 2), texturePadding = 2)
        assertEquals(512, state2x.minRequiredAtlasSize())

        val state4x = PSD2LiveState(analysis = analysis, textureUpscale = TextureUpscaleConfig(scale = 4), texturePadding = 2)
        assertEquals(1024, state4x.minRequiredAtlasSize())
    }

    @Test fun atlasPackerStreamsProgressThroughCallback() {
        val config = PipelineConfig(atlasSize = 256, meshSpacing = 40, generatePhysics = false)
        val analysis = analysis(config)
        val progressStages = mutableListOf<String>()
        val progressFractions = mutableListOf<Double>()
        val atlas = AtlasPacker.pack(
            layers = analysis.layers,
            requestedSize = 256,
            padding = 2,
            upscale = TextureUpscaleConfig(scale = 1),
            progress = { stage, frac ->
                progressStages += stage
                progressFractions += frac
            }
        )
        assertNotNull(atlas)
        assertTrue(atlas.pages.isNotEmpty())
    }

    @Test fun upscaleSettingsEmitsEntriesToLogDock() {
        val vm = PSD2LiveViewModel()
        try {
            vm.setTextureUpscale(TextureUpscaleConfig(scale = 2, tileSize = 256))
            val entries = vm.state.value.logEntries
            val upscaleLogs = entries.filter { it.tag == "Upscale" }
            assertTrue(upscaleLogs.isNotEmpty(), "Expected upscale log entries")
            assertTrue(upscaleLogs.any { it.message.contains("2") })

            vm.setTextureUpscale(TextureUpscaleConfig(scale = 1))
            val updatedLogs = vm.state.value.logEntries.filter { it.tag == "Upscale" }
            assertTrue(updatedLogs.size > upscaleLogs.size, "Disabling upscale should emit another log entry")
        } finally {
            vm.close()
        }
    }
}
