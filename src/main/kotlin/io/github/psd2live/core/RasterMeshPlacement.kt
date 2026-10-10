package io.github.psd2live.core

import io.github.psd2live.project.LayerTransform
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.*

/**
 * Converts unbound canvas meshes through their actual parent's neutral transform.
 *
 * A mesh made from a layer's pixels is in the layer's frame; a layer the user moved as a whole shows that frame
 * through its [LayerTransform], so a mesh rebuilt or recreated for it is placed there first ([shown]) - a rebuild
 * never undoes a move.
 */
internal object RasterMeshPlacement {
    fun underParents(generated: BuiltRig, current: PuppetModel, parent: (DrawableId) -> DeformerId?,
                     transform: (DrawableId) -> LayerTransform = { LayerTransform.IDENTITY },
                     checkCancelled: () -> Unit = {}): BuiltRig {
        val puppet = generated.puppet.copy(deformers = current.deformers, parameters = current.parameters,
            drawables = generated.puppet.drawables.map { it.copy(parentDeformerId = parent(it.id)) })
        val converted = puppet.copy(drawables = puppet.drawables.map { drawable ->
            checkCancelled()
            val mesh = requireNotNull(drawable.mesh)
            drawable.copy(mesh = DrawableMesh(underParent(puppet, drawable.id, shown(mesh.positions, transform(drawable.id))), mesh.uvs, mesh.indices))
        })
        return generated.copy(puppet = converted)
    }

    /** Canvas points of a layer's frame (x, y pairs) where the canvas shows them under the layer's [transform]. */
    fun shown(frame: FloatArray, transform: LayerTransform): FloatArray {
        if (transform.isIdentity) return frame
        return FloatArray(frame.size).also { out ->
            for (i in 0 until frame.size / 2) {
                val x = frame[i * 2]; val y = frame[i * 2 + 1]
                out[i * 2] = transform.x(x, y); out[i * 2 + 1] = transform.y(x, y)
            }
        }
    }

    /** [canvas] positions of [id] in the neutral space of the deformer it hangs under in [model]. */
    fun underParent(model: PuppetModel, id: DrawableId, canvas: FloatArray): FloatArray =
        requireNotNull(placed(model, id, canvas)) { "Parent transform cannot place the imported mesh" }

    /**
     * [canvas] positions of [drawable] in the neutral space of its parent among [model]'s deformers, or null
     * when that parent is missing or cannot reproduce them. [drawable] need not be in [model] yet.
     */
    fun underParent(model: PuppetModel, drawable: Drawable, canvas: FloatArray): FloatArray? =
        placed(model.copy(drawables = model.drawables.filter { it.id != drawable.id } + drawable), drawable.id, canvas)

    private fun placed(model: PuppetModel, id: DrawableId, canvas: FloatArray): FloatArray? {
        val world = canvas.copyOf().also { values -> for (i in 1 until values.size step 2) values[i] = -values[i] }
        val mapping = drawableSpaceMapping(model, emptyMap(), id) ?: return null
        val local = mapping.worldToLocalLinearized(world, FloatArray(world.size) { 0.5f }, world, (0 until world.size / 2).toSet())
        val actual = mapping.localToWorld(local)
        return local.takeIf { local.all(Float::isFinite) && actual.indices.all { kotlin.math.abs(actual[it] - world[it]) < 0.05f } }
    }
}
