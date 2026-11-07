package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.DeformPath
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.PathPoint
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.*

/** Independent legacy outline transition, including the opening and its attached ribbons. */
class MouthOutlineLegacyCandidate {
    /** Unbound source vertices and their correspondence to the preceding scaffold. */
    data class NeutralOwner(
        val parent: DeformerId?,
        val mesh: DrawableMesh,
        val sourcePoints: FloatArray,
        val frame: ModifierSourceBounds,
        val aperture: ModifierSourceBounds,
        val texture: TextureFrame,
    ) {
        init {
            require(sourcePoints.size == mesh.positions.size && sourcePoints.all(Float::isFinite))
        }
    }

    data class TextureFrame(
        val placement: AtlasPlacement?,
        val width: Int,
        val height: Int,
        val offsetX: Float,
        val offsetY: Float,
        val scaleX: Float,
        val scaleY: Float,
    ) {
        fun bind(offsets: FloatArray): FloatArray = FloatArray(offsets.size).also { uvs ->
            for (index in offsets.indices step 2) {
                val x = (offsets[index] - offsetX) * scaleX
                val y = (offsets[index + 1] - offsetY) * scaleY
                val pixel = placement?.let { atlasPixelOf(it, x, y) }
                uvs[index] = (pixel?.get(0) ?: x) / width
                uvs[index + 1] = (pixel?.get(1) ?: y) / height
            }
        }
    }

    fun changes(
        input: ModifierEvaluationInput,
        instance: RigModifierInstance,
        neutralOwners: Map<String, NeutralOwner>,
        checkpoint: () -> Unit = {},
    ): ModifierModelChanges {
        val captured = MouthOutlineInputs.decode(input.context)
        require(captured.targets.keys == neutralOwners.keys)
        require(captured.targets.values.all { it.fittedProvider == null && !it.headOnMesh }) {
            "Legacy outline owner requires a legacy mouth without mesh-local head motion"
        }
        if (captured.targets.isEmpty()) return ModifierModelChanges()
        val changes = MouthOutlineAlgorithm().changes(input, instance, checkpoint)
        val before = captured.targets.keys.associateWith { requireNotNull(input.mesh(it)) }
        val live = PuppetIr.toPuppet(RigIR(
            input.canvas, input.parameters, meshes = before.values.toList()))
        val authoring = input.authoring
        val glues = input.glues
        val updates = mutableListOf<ModifierObjectUpdate<Mesh>>()
        val paths = mutableListOf<DeformPath>()
        for (actual in live.drawables) {
            checkpoint()
            val id = actual.id.raw
            val target = captured.targets.getValue(id)
            if (!target.contourOwner || target.rasterColumns.isEmpty() || target.sides.isEmpty()) continue
            val topology = captured.topology(target, checkpoint)
            val contour = MouthContour.ownerContour(topology.positions, topology.triangles) ?: continue
            val source = captured.artwork.layerForTarget(id)
            val neutral = neutralOwners.getValue(id)
            require(actual.parentDeformerId == neutral.parent) { "Outline owner frame changed: $id" }
            val unbound = MouthOutlineOwner.mesh(contour, target.frame, source.left, source.top,
                source.width.toFloat(), source.height.toFloat(), target::toCanvas)
            val mesh = DrawableMesh(if (neutral.parent == null) contour.positions else unbound.positions,
                neutral.texture.bind(unbound.uvs), unbound.indices)
            val curve = captured.curve.points.map { it.x to it.y }
            val previousMotion = MouthOutlineMotion(neutral.aperture, curve, captured.tuning)
            val desiredMotion = MouthOutlineMotion(target.aperture, curve, captured.tuning)
            val previous = actual.copy(mesh = neutral.mesh, blendShapes = emptyList(),
                geometryGrid = MouthOutlineLegacyOwner.grid(neutral.sourcePoints, neutral.frame, previousMotion, false, checkpoint))
            val desired = previous.copy(mesh = mesh,
                geometryGrid = MouthOutlineLegacyOwner.grid(contour.positions, target.frame, desiredMotion, true, checkpoint))
            val hasSidecars = authoring.paths.any { it.mesh == id } || authoring.vertexGroups.any { it.mesh == id } ||
                glues.any { it.meshA == id || it.meshB == id }
            val updated = MouthOutlineLegacyOwner.merge(live, actual, previous, desired, hasSidecars, checkpoint)
            val original = before.getValue(id)
            val converted = PuppetIr.meshToIr(updated)
            val output = original.copy(geometry = converted.geometry, offsets = converted.offsets)
            if (output != original) updates += ModifierObjectUpdate(original, output)
            val ownerMesh = requireNotNull(updated.mesh)
            paths += MouthOutlineOwner.paths(DrawableId(source.id), unbound).map { path ->
                val points = path.points.map { point ->
                    val bound = if (ownerMesh === mesh) point else {
                        val uv = mesh.uvs
                        fun at(axis: Int) = uv[point.a * 2 + axis] * point.wa +
                            uv[point.b * 2 + axis] * point.wb + uv[point.c * 2 + axis] * point.wc
                        BindingPaths.bind(ownerMesh.uvs, ownerMesh.indices, at(0), at(1), point.corner)
                    }
                    PathPoint(bound.a, bound.b, bound.c, bound.wa, bound.wb, bound.wc, bound.corner)
                }
                val pathId = if (id == target.canonical) path.id else instance.outputId("mouth-owner-path:$id:${path.id}")
                DeformPath(pathId, id, points, path.width, path.hardness, path.closed, path.editLevel)
            }.filter { path ->
                val existing = authoring.paths.singleOrNull { it.id == path.id }
                require(existing == null || existing.mesh == id) { "Outline owner path ID collides with another mesh: ${path.id}" }
                existing == null
            }
        }
        val owners = updates.associate { it.after.id to it.after }
        return changes.copy(
            meshes = ModifierObjectChanges(updated = updates),
            authoring = if (paths.isEmpty()) null else ModifierFieldChange(authoring, authoring.copy(paths = authoring.paths + paths)),
            rasters = changes.rasters.map { raster ->
                val owner = owners[raster.owner.id] ?: return@map raster
                ModifierRasterOutput(owner, raster.mesh, raster.layer, raster.pixels, raster.paths, raster.uvSpace)
            },
        )
    }
}
