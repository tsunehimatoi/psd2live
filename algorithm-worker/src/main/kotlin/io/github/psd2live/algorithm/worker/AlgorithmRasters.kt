package io.github.psd2live.algorithm.worker

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Geometry and metadata use the versioned IR carrier; pixels remain a separate hashed binary resource. */
internal object AlgorithmRasters {
    private val empty = RigIR(Canvas(0f, 0f), emptyList())
    private const val LIMIT = 256L * 1024 * 1024

    fun encode(outputs: List<ModifierRasterOutput>, write: (ByteArray) -> JsonObject): JsonArray {
        require(outputs.size in 1..10000)
        var total = 0L
        return JsonArray(outputs.map { output ->
            val model = empty.copy(meshes = listOf(output.owner, output.mesh), authoring = Authoring(
                sources = listOf(SourceFile("raster", "", null, "rgba8", listOf(output.layer))), paths = output.paths))
            val data = RigIrBinary.encode(model)
            total += data.size.toLong() + output.pixels.size
            require(total <= LIMIT) { "Raster result exceeds transfer limit" }
            buildJsonObject {
                put("model", write(data))
                put("pixels", write(output.pixels.read()))
                put("uv_space", output.uvSpace.name)
            }
        })
    }

    fun decode(encoded: JsonArray, directory: Path): List<ModifierRasterOutput> =
        decode(encoded) { AlgorithmObjects.read(directory, it) }

    fun decode(encoded: JsonArray, readBlob: (JsonElement) -> ByteArray): List<ModifierRasterOutput> {
        require(encoded.size in 1..10000)
        var total = 0L
        return encoded.map { value ->
            val item = value.jsonObject
            require(item.keys == setOf("model", "pixels", "uv_space")) { "Invalid raster result envelope" }
            fun read(name: String): ByteArray {
                val data = readBlob(item.getValue(name))
                total += data.size
                require(total <= LIMIT) { "Raster result exceeds transfer limit" }
                return data
            }
            val model = RigIrBinary.decode(read("model"))
            require(model.meshes.size == 2 && model.authoring.sources.size == 1) { "Invalid raster geometry carrier" }
            require(model.copy(meshes = emptyList(), authoring = Authoring()) == empty) { "Unexpected raster model fields" }
            val source = model.authoring.sources.single()
            require(source.layers.size == 1 && source.copy(layers = emptyList()) == SourceFile("raster", "", null, "rgba8"))
            require(model.authoring.copy(sources = emptyList(), paths = emptyList()) == Authoring()) { "Unexpected raster authoring fields" }
            ModifierRasterOutput(model.meshes[0], model.meshes[1], source.layers.single(), ModifierAlgorithmResource(read("pixels")),
                model.authoring.paths, ModifierRasterOutput.UvSpace.valueOf(item.getValue("uv_space").jsonPrimitive.content))
        }
    }
}
