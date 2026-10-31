package io.github.psd2live.core

import io.github.psd2live.project.*
import io.github.psd2live.scenario.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.psd.PsdWriter
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*
import kotlin.test.*

class LayeredMouthSkinIntegrationTest {
    @TempDir lateinit var temp: Path

    private fun source(): WorkspaceSourceArt {
        fun rim(x: Int, upper: Boolean): Float {
            val u = ((x + 0.5f - 32f) / 13f).coerceIn(-1f, 1f)
            return 34f + (if (upper) -9f else 9f) * sqrt(1f - u * u)
        }
        fun layer(id: String, name: String, order: Int, paint: (Int, Int) -> Boolean): WorkspaceSourceLayer {
            val pixels = ByteArray(64 * 64 * 4)
            for (y in 0..63) for (x in 0..63) if (paint(x, y)) {
                val i = (y * 64 + x) * 4
                pixels[i] = 100; pixels[i + 1] = 60; pixels[i + 2] = 40; pixels[i + 3] = -1
            }
            return WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, order,
                LayerBounds(0, 0, 64, 64), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(64, 64, pixels), null, null, false)
        }
        return WorkspaceSourceArt(64, 64, listOf(
            layer("face", "face", 0) { x, y -> x in 4..59 && y in 3..60 },
            layer("opening", "mouth", 1) { x, y -> x in 19..44 && y + 0.5f >= rim(x, true) && y + 0.5f <= rim(x, false) },
            layer("lower", "下唇", 2) { x, y -> x in 12..51 && y in 23..54 && (x !in 19..44 || y >= rim(x, false) - 1f) },
            layer("upper", "上唇", 3) { x, y -> x in 12..51 && y in 12..45 && (x !in 19..44 || y <= rim(x, true) + 1f) },
        ), emptyList())
    }

    @Test fun layeredMouthCaptureSurvivesHistorySaveAndCubismReadback() = Studio.run(
        temp, checks = setOf(Studio.Check.REPLAY, Studio.Check.UNDO),
    ) {
        val art = source()
        val path = temp.resolve("layered.psd")
        Files.write(path, PsdWriter.write(art.widthPx, art.heightPx, art.layers, art.groups))
        importPsd(path, PipelineConfig(atlasSize = 256, generatePhysics = false, exportMoc3 = false, meshSpacing = 8))
        edit("change capture mouth curve", "settings_update", req("changes" to mapOf("mouthShape" to "flat")))
        val captured = puppet
        reopen()
        Oracles.sameRig("reopen", "layered capture", captured, puppet)
        exportMatchesEditor(this)
    }

    @Test fun theDrawnPoseSurvivesRebuildingAndAllMouthPartsFollowCapture() {
        val model = PSD2LivePipeline().buildPreview(source(), PipelineConfig(atlasSize = 256,
            generatePhysics = false, exportMoc3 = false, meshSpacing = 8))
        val evaluator = CpuDeformationEvaluator()
        fun pose(open: Float, x: Float) = evaluator.evaluate(model.rig.puppet,
            mapOf(ParameterId("ParamMouthOpenY") to open, ParameterId("ParamMouthX") to x)).worldPositions
        val original = pose(1f, 0f)
        for (open in listOf(0f, 0.15f, 0.35f, 0.5f, 1f)) {
            val left = pose(open, -1f); val right = pose(open, 1f)
            for (id in listOf("upper", "lower", "opening")) {
                val drawable = model.rig.layerIdByDrawableId.entries.single { it.value == id }.key
                val mesh = model.rig.puppet.drawables.single { it.id.raw == drawable }
                val a = left.getValue(mesh.id); val b = right.getValue(mesh.id)
                assertTrue(a.indices.step(2).any { abs(b[it] - a[it]) > 0.1f }, "$id does not follow capture at $open")
                assertTrue(a.all { it.isFinite() } && b.all { it.isFinite() })
            }
        }
        val rebuilt = PSD2LivePipeline().buildPreview(model.analysis.source, model.config)
        val restored = evaluator.evaluate(rebuilt.rig.puppet, mapOf(ParameterId("ParamMouthOpenY") to 1f)).worldPositions
        for ((id, p) in original) assertContentEquals(p, restored[id], "rebuilding the drawn pose changes $id")
    }
}
