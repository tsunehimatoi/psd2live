package io.github.psd2live.core

import io.github.psd2live.format.compile.Compiler
import io.github.psd2live.format.compile.ExportOptions
import io.github.psd2live.format.compile.PngEncoder
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class ExportTexturePagesTest {
    private val pages = listOf(0xffff0000.toInt(), 0xff0000ff.toInt()).map { color ->
        TexturePage(1, 1, Bytes.of(PngEncoder.encode(1, 1, intArrayOf(color))))
    }
    private val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
        Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.values(0, 1, 2))
    private val rig = RigIR(Canvas(2f, 2f), emptyList(),
        meshes = pages.indices.map { Mesh("m$it", "m$it", null, geometry = geometry, offsets = null, page = it) },
        textures = Textures(pages = pages))

    private fun export(id: String): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        Compiler.export(ExportService.registry(PipelineConfig())[id], rig, ExportOptions("model")) { path, bytes ->
            assertNull(files.put(path, bytes), "Duplicate output: $path")
        }
        return files
    }

    @Test fun externalAtlasesReferenceTheCorrectTextureForEachPage() {
        val spine = export("spine")
        val atlasPages = spine.getValue("model.atlas").decodeToString().lineSequence().filter { it.endsWith(".png") }.toList()
        assertEquals(pages.size, atlasPages.size)
        val dragon = export("dragonbones")
        for ((index, page) in pages.withIndex()) {
            assertContentEquals(page.png.shared(), spine.getValue(atlasPages[index]))
            val atlas = Json.parseToJsonElement(dragon.getValue("model_tex_$index.json").decodeToString()).jsonObject
            assertContentEquals(page.png.shared(), dragon.getValue(atlas.getValue("imagePath").jsonPrimitive.content))
        }
    }

    @Test fun glbImageViewsAddressTheCorrectBytesInsideTheBinaryChunk() {
        val bytes = export("gltf").getValue("model.glb")
        val glb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546c67, glb.int)
        assertEquals(2, glb.int)
        assertEquals(bytes.size, glb.int)
        val json = ByteArray(glb.int)
        assertEquals(0x4e4f534a, glb.int)
        glb.get(json)
        val document = Json.parseToJsonElement(json.decodeToString()).jsonObject
        val length = glb.int
        assertEquals(0x004e4942, glb.int)
        val start = glb.position()
        assertEquals(length, bytes.size - start)
        val views = document.getValue("bufferViews").jsonArray
        val images = document.getValue("images").jsonArray
        assertEquals(pages.size, images.size)
        for ((index, image) in images.withIndex()) {
            val view = views[image.jsonObject.getValue("bufferView").jsonPrimitive.int].jsonObject
            val offset = view.getValue("byteOffset").jsonPrimitive.int
            assertEquals(0, offset % 4)
            val size = view.getValue("byteLength").jsonPrimitive.int
            assertContentEquals(pages[index].png.shared(), bytes.copyOfRange(start + offset, start + offset + size))
        }
    }
}
