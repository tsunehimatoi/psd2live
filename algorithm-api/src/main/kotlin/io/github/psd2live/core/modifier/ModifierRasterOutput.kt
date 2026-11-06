package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.*

/** Generated artwork and its editable geometry, before the host allocates atlas pages. */
class ModifierRasterOutput(
    val owner: Mesh,
    val mesh: Mesh,
    val layer: SourceLayer,
    val pixels: ModifierAlgorithmResource,
    paths: List<DeformPath> = emptyList(),
    /** New algorithms use source raster pixels. PAGE preserves already-bound legacy geometry. */
    val uvSpace: UvSpace = UvSpace.RASTER_PIXELS,
) {
    enum class UvSpace { RASTER_PIXELS, PAGE }
    val paths: List<DeformPath> = java.util.Collections.unmodifiableList(paths.toList())

    init {
        require(owner.id.isNotBlank() && mesh.id.isNotBlank() && owner.id != mesh.id)
        require(layer.key.isNotBlank() && layer.width > 0 && layer.height > 0)
        require(layer.width.toLong() * layer.height * 4 == pixels.size.toLong()) { "Generated raster size mismatch" }
        require(mesh.textureSource == null) { "Generated raster must own its texture" }
        val geometry = requireNotNull(mesh.geometry) { "Generated raster requires mesh geometry" }
        require(geometry.positions.size > 0 && geometry.positions.size % 2 == 0 && geometry.uvs.size == geometry.positions.size)
        require(geometry.positions.shared().all(Float::isFinite) && geometry.uvs.shared().all(Float::isFinite))
        require(geometry.indices.size % 3 == 0 && geometry.indices.shared().all { it in 0 until geometry.positions.size / 2 })
        require(this.paths.map { it.id }.distinct().size == this.paths.size)
        require(this.paths.all { it.id.isNotBlank() && it.mesh == mesh.id }) { "Generated path has a different mesh owner" }
        require(this.paths.all { path -> path.width.isFinite() && path.hardness.isFinite() && path.points.all { point ->
            listOf(point.a, point.b, point.c).all { it in 0 until geometry.positions.size / 2 } &&
                listOf(point.wa, point.wb, point.wc).all(Float::isFinite)
        } }) { "Invalid generated path geometry" }
    }

    companion object {
        /** Validate the entire batch before packing or constructing any candidate model. */
        fun validate(input: RigIR, outputs: List<ModifierRasterOutput>, checkpoint: () -> Unit = {}) {
            require(outputs.size <= 10000)
            val meshes = input.meshes.associateBy { it.id }
            require(meshes.size == input.meshes.size) { "Duplicate input mesh" }
            val newMeshes = HashSet<String>()
            val newLayers = HashSet<String>()
            val paths = input.authoring.paths.mapTo(HashSet()) { it.id }
            var bytes = 0L
            outputs.forEach { output ->
                checkpoint()
                require(meshes[output.owner.id] == output.owner) { "Stale raster owner: ${output.owner.id}" }
                require(output.mesh.id !in meshes && newMeshes.add(output.mesh.id)) { "Generated mesh collision: ${output.mesh.id}" }
                require(newLayers.add(output.layer.key)) { "Generated raster collision: ${output.layer.key}" }
                require(output.paths.all { paths.add(it.id) }) { "Generated path collision" }
                bytes += output.pixels.size
                require(bytes <= 256L * 1024 * 1024) { "Generated raster batch exceeds resource limit" }
            }
        }
    }
}
