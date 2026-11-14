package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*
import org.umamo.render.eval.drawableSpaceMapping
import kotlin.math.abs

/** Coordinate-frame transport shared by independent bindings and ordinary editor rebinding. */
object BindingFrames {
    /**
     * [after] - [before] with mesh [id] hung under another deformer - with the mesh's rest positions, keyforms and
     * blend shapes carried into the new parent's space, so it shows where it did at the default pose. Each shape is
     * moved through the old parent onto the canvas and back through the new one; a keyform keeps the canvas offset it
     * had from the rest shape there. A parent that cannot reproduce the rest shape (a hidden ancestor, a folded
     * lattice) refuses the bind rather than place the mesh somewhere else.
     */
    fun carriedToParent(before: PuppetModel, after: PuppetModel, id: DrawableId, checkpoint: () -> Unit = {}): PuppetModel {
        val drawable = after.drawables.single { it.id == id }
        val mesh = requireNotNull(drawable.mesh) { "Mesh has no geometry: ${id.raw}" }
        val from = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(before, emptyMap(), id)) { "The current parent cannot place the mesh" }
        val to = requireNotNull(org.umamo.render.eval.drawableSpaceMapping(after, emptyMap(), id)) { "The new parent cannot place the mesh" }
        val all = (0 until mesh.vertexCount).toSet()
        fun carried(local: FloatArray, seed: FloatArray): FloatArray {
            checkpoint()
            val world = from.localToWorld(local)
            if (world.contentEquals(to.localToWorld(local))) return local.copyOf()
            return to.worldToLocalLinearized(world, seed, world, all).also { result ->
                require(result.all(Float::isFinite)) { "The new parent cannot place the mesh" }
            }
        }
        val rest = carried(mesh.positions, FloatArray(mesh.positions.size) { 0.5f })
        val reached = to.localToWorld(rest); val shown = from.localToWorld(mesh.positions)
        require(reached.indices.all { abs(reached[it] - shown[it]) < 0.05f }) { "The new parent cannot hold the mesh where it shows" }
        fun delta(deltas: FloatArray): FloatArray {
            if (deltas.all { it == 0f }) return FloatArray(deltas.size)
            val local = FloatArray(deltas.size) { mesh.positions[it] + deltas[it] }
            if (rest.contentEquals(mesh.positions) && from.localToWorld(local).contentEquals(to.localToWorld(local))) {
                return deltas.copyOf()
            }
            val moved = carried(local, rest)
            return FloatArray(deltas.size) { moved[it] - rest[it] }
        }
        val carriedDrawable = drawable.copy(
            mesh = DrawableMesh(rest, mesh.uvs, mesh.indices),
            geometryGrid = drawable.geometryGrid?.let { grid ->
                KeyformGrid(grid.axes, grid.cells.map { cell -> KeyformCell(cell.coordinate, MeshDeltaForm(delta(cell.form.positionDeltas))) })
            },
            blendShapes = drawable.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let { MeshForm(delta(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
            }) },
        )
        return after.copy(drawables = after.drawables.map { if (it.id == id) carriedDrawable else it })
    }

    /** Use the same canvas-space transfer as ordinary DCC rebinding, including non-affine Warp parents. */
    fun pointsInParent(model: PuppetModel, sample: Drawable, points: FloatArray, parent: DeformerId, scratch: String, checkpoint: () -> Unit = {}): FloatArray {
        val id = DrawableId(scratch)
        require(model.drawables.none { it.id == id }) { "Binding frame ID collision" }
        val drawable = sample.copy(id = id, parentDeformerId = null,
            mesh = DrawableMesh(points, FloatArray(points.size), IntArray(0)), geometryGrid = null,
            channelGrids = ChannelGrids.Empty, blendShapes = emptyList())
        val before = model.copy(drawables = model.drawables + drawable)
        val after = before.copy(drawables = before.drawables.map { if (it.id == id) it.copy(parentDeformerId = parent) else it })
        return carriedToParent(before, after, id, checkpoint).drawables.single { it.id == id }.mesh!!.positions
    }
    /** Only invert the authored path below a shared anchor, avoiding ambiguous inverses outside fitted ancestors. */
    fun reparent(model: PuppetModel, sample: Drawable, warp: Deformer.Warp, anchor: DeformerId?, destination: DeformerId?,
                         scratch: String, checkpoint: () -> Unit): Deformer.Warp {
        var top = destination
        val visited = HashSet<DeformerId>()
        var relative: PuppetModel? = null
        while (top != null) {
            require(visited.add(top)) { "Cyclic authored carrier parent" }
            val node = model.deformers.single { it.id == top }
            if (node.parent == anchor) {
                val detached = when (node) {
                    is Deformer.Warp -> node.copy(parent = null)
                    is Deformer.Rotation -> node.copy(parent = null)
                }
                relative = model.copy(deformers = model.deformers.map { if (it.id == node.id) detached else it })
                break
            }
            top = node.parent
        }
        val frame = model.copy(drawables = model.drawables.map { if (it.id == sample.id) it.copy(parentDeformerId = anchor) else it })
        val mapping = if (relative == null) requireNotNull(drawableSpaceMapping(frame, emptyMap(), sample.id)) else null
        fun points(values: FloatArray): FloatArray {
            checkpoint()
            val canvas = mapping?.localToWorld(values) ?: values
            return if (destination == null) canvas else pointsInParent(relative ?: model, sample, canvas, destination, scratch, checkpoint)
        }
        return warp.copy(parent = destination, geometryGrid = warp.geometryGrid?.let { grid -> KeyformGrid(grid.axes, grid.cells.map {
            KeyformCell(it.coordinate, WarpLatticeForm(points(it.form.controlPoints)))
        }) }, blendShapes = warp.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form -> form?.let {
            WarpForm(points(it.controlPoints), it.opacity, it.multiplyColor, it.screenColor)
        } }) })
    }

}
