package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.MeshSplitAlgorithm
import io.github.psd2live.core.AtlasPagePng
import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.ExportReport
import io.github.psd2live.format.compile.Feature
import io.github.psd2live.format.compile.Handling
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.Cmo3Target
import io.github.psd2live.bridge.PuppetIr
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class MeshSplitCmo3ExportTest {
    @TempDir lateinit var directory: Path

    private val pageColors = listOf(0xffe03050.toInt(), 0xff2050e0.toInt())

    private data class Readback(val input: RigIR, val puppet: PuppetModel, val report: ExportReport,
                                val pages: List<BufferedImage>, val bindings: Map<String, Int>)

    private fun export(runtime: String = "Cubism53", layerArt: String = "native"): Readback {
        val fixture = meshSplitFixture()
        val textured = fixture.copy(authoring = fixture.authoring.copy(runtime = runtime),
            textures = fixture.textures.copy(pages = fixture.textures.pages.mapIndexed { index, page ->
                val image = BufferedImage(page.width, page.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, pageColors[index])
                page.copy(png = Bytes.wrap(AtlasPagePng.canonical(image)))
            }))
        val split = MeshSplitAlgorithm().evaluate(textured, RigModifierInstance("split", "mesh.split", 1,
            listOf(ModifierTarget("mesh", "islands"))), ModifierAlgorithmContext()) {}
        val report = Compiler.export(Cmo3Target(), split, ExportOptions("split", settings = mapOf("layer_art" to layerArt))) { name, bytes ->
            Files.write(directory.resolve(name), bytes)
        }
        val bytes = Files.readAllBytes(directory.resolve("split.cmo3"))
        assertTrue(bytes.isNotEmpty())
        val parsed = Cmo3.read(bytes)
        val root = parsed.root as CModelSource
        val pages = cmo3AtlasPages(root, parsed::extractLayerPng)
        println(report.toJson())
        return Readback(split, Cmo3Import.fromModelSource(root), report,
            pages.pageBytes.map { ImageIO.read(it.inputStream()) }, pages.atlasIndexByDrawableId)
    }

    @Test fun actualCmo3FileRetainsSplitMeshesGlueMasksPathsAndPosedGeometry() {
        val read = export()
        val expected = PuppetIr.toPuppet(read.input)
        val actual = read.puppet
        assertEquals(expected.drawables.map { it.id }.toSet(), actual.drawables.map { it.id }.toSet())
        for (mesh in expected.drawables) {
            val restored = actual.drawables.single { it.id == mesh.id }
            assertEquals(mesh.maskedBy, restored.maskedBy)
            assertEquals(mesh.invertMask, restored.invertMask)
            assertContentEquals(mesh.mesh!!.indices, restored.mesh!!.indices)
        }
        assertEquals(expected.glues.map { it.id }, actual.glues.map { it.id })
        expected.glues.zip(actual.glues).forEach { (before, after) ->
            assertEquals(before.meshA, after.meshA)
            assertEquals(before.meshB, after.meshB)
            assertEquals(before.pairs.size, after.pairs.size)
            before.pairs.zip(after.pairs).forEach { (a, b) ->
                assertEquals(a.indexA, b.indexA)
                assertEquals(a.indexB, b.indexB)
                assertEquals(a.weightA, b.weightA)
                assertEquals(a.weightB, b.weightB)
            }
        }
        assertEquals(expected.deformPaths, actual.deformPaths)
        val group = actual.parts.single { it.id.raw == "group" }
        assertEquals(expected.parts.single().groupMode, group.groupMode)
        assertEquals(expected.parts.single().composite, group.composite)
        for (value in listOf(0f, .5f, 1f)) {
            val pose = mapOf(ParameterId("blend") to value)
            val a = CpuDeformationEvaluator().evaluate(expected, pose).worldPositions
            val b = CpuDeformationEvaluator().evaluate(actual, pose).worldPositions
            assertEquals(a.keys, b.keys)
            for ((id, points) in a) points.forEachIndexed { index, point ->
                assertEquals(point, b.getValue(id)[index], .00001f, "$id at blend=$value vertex component $index")
            }
        }
    }

    @Test fun textureSourceIsMaterializedIntoTheSameSampledPixelsInTheCmo3File() {
        for (layerArt in listOf("native", "canvas")) assertTextureSamples(export(layerArt = layerArt))
    }

    private fun assertTextureSamples(read: Readback) {
        for (mesh in read.input.meshes) {
            val source = read.input.meshes.single { it.id == (mesh.textureSource ?: mesh.id) }
            val expectedColor = pageColors[read.input.textures.bindings[source.id] ?: source.page]
            val restored = read.puppet.drawables.single { it.id.raw == mesh.id }
            val geometry = assertNotNull(restored.mesh)
            val page = read.pages[read.bindings.getValue(mesh.id)]
            for (triangle in geometry.indices.asList().chunked(3)) {
                val u = triangle.sumOf { geometry.uvs[it * 2].toDouble() }.toFloat() / 3f
                val v = triangle.sumOf { geometry.uvs[it * 2 + 1].toDouble() }.toFloat() / 3f
                assertEquals(expectedColor, page.getRGB((u * page.width).toInt().coerceIn(0, page.width - 1),
                    (v * page.height).toInt().coerceIn(0, page.height - 1)), "texture samples for ${mesh.id}")
            }
        }
    }

    @Test fun unsupportedAuthoringGroupsAndHitAreasHaveExplicitTargetLosses() {
        val read = export()
        assertTrue(read.puppet.vertexGroups.isEmpty(), "CMO3 has no runtime vertex weight group representation")
        for (group in read.input.authoring.vertexGroups) assertTrue(read.report.losses.any {
            it.objectId == group.mesh && it.feature == Feature.STRUCTURE && it.handling == Handling.DROPPED &&
                it.note.contains("vertex", ignoreCase = true) && it.note.contains(group.name)
        }, "Missing loss for vertex group ${group.name} on ${group.mesh}: ${read.report.losses}")
        assertTrue(read.report.losses.any { it.objectId == "head" && it.handling == Handling.DROPPED })
        assertFalse(read.report.losses.any { it.feature == Feature.GLUE || it.feature == Feature.MASK })
    }
}
