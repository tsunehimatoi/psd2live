package io.github.psd2live.core

import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.runtime.model.*
import kotlin.math.*

/**
 * Re-express child geometry in a newly created pivot's frame, preserving every existing pose.
 * [x]/[y]/[angle] are in the **same local space** as the remounted children's current coordinates
 * (parent of the new rotation): world at the armature root, UV under a Warp, affine under a Rotation.
 *
 * Under a Warp parent the runtime bakes the rotation into a **world** affine, so children must land in
 * pixel-scale rotation-local space — UV is mapped through the parent warp before the pivot frame is
 * applied.
 */
internal class RotationCreationSpace(private val x: Float, private val y: Float, private val angle: Float) {
    private val c = cos(angle * PI.toFloat() / 180f)
    private val s = sin(angle * PI.toFloat() / 180f)

    /** Parent-local bake (root / rotation parent): rotate about [x],[y] in the same units as [input]. */
    fun points(input: FloatArray, delta: Boolean = false): FloatArray = input.copyOf().also { out ->
        for (i in input.indices step 2) {
            val px = input[i] - if (delta) 0f else x
            val py = input[i + 1] - if (delta) 0f else y
            out[i] = c * px + s * py
            out[i + 1] = -s * px + c * py
        }
    }

    /**
     * Inserts [rotation] above [remountDeformers] and [remountMeshes]. Those targets must currently
     * share [rotation]'s parent; after the wrap they become direct children of [rotation].
     */
    fun wrap(
        model: PuppetModel,
        rotation: Deformer.Rotation,
        remountDeformers: Set<DeformerId>,
        remountMeshes: Set<String>,
    ): PuppetModel {
        val parentId = rotation.parent
        val parentIsWarp = parentId != null && model.deformers.any { it.id == parentId && it is Deformer.Warp }
        val defaults: (ParameterId) -> Float = { id ->
            model.parameters.firstOrNull { it.id == id }?.default ?: 0f
        }

        fun mapPoints(input: FloatArray, paramValue: (ParameterId) -> Float = defaults): FloatArray =
            if (parentIsWarp) throughParent(model, parentId!!, input, paramValue) else points(input)

        val deformers = model.deformers.map { d ->
            if (d.id !in remountDeformers) d else when (d) {
                is Deformer.Warp -> d.copy(
                    parent = rotation.id,
                    geometryGrid = d.geometryGrid?.let { grid ->
                        KeyformGrid(grid.axes, grid.cells.map { cell ->
                            KeyformCell(
                                cell.coordinate,
                                WarpLatticeForm(
                                    mapPoints(cell.form.controlPoints, paramValueAt(grid.axes, cell.coordinate, defaults)),
                                ),
                            )
                        })
                    },
                    blendShapes = d.blendShapes.map { binding ->
                        binding.copy(forms = binding.forms.map { form ->
                            form?.let {
                                WarpForm(mapPoints(it.controlPoints), it.opacity, it.multiplyColor, it.screenColor)
                            }
                        })
                    },
                )
                is Deformer.Rotation -> d.copy(
                    parent = rotation.id,
                    baseAngle = d.baseAngle - angle,
                    geometryGrid = d.geometryGrid?.let { grid ->
                        KeyformGrid(grid.axes, grid.cells.map { cell ->
                            val p = mapPoints(
                                floatArrayOf(cell.form.originX, cell.form.originY),
                                paramValueAt(grid.axes, cell.coordinate, defaults),
                            )
                            KeyformCell(cell.coordinate, RotationPivotForm(p[0], p[1], cell.form.angle, cell.form.scale))
                        })
                    } ?: KeyformGrid(
                        emptyList(),
                        listOf(
                            KeyformCell(
                                intArrayOf(),
                                mapPoints(floatArrayOf(0f, 0f)).let { RotationPivotForm(it[0], it[1], 0f, 1f) },
                            ),
                        ),
                    ),
                    blendShapes = d.blendShapes.map { binding ->
                        binding.copy(forms = binding.forms.map { form ->
                            form?.let {
                                val p = mapPoints(floatArrayOf(it.originX, it.originY))
                                RotationForm(
                                    p[0], p[1], it.angle, it.scale, it.flipX, it.flipY,
                                    it.opacity, it.multiplyColor, it.screenColor,
                                )
                            }
                        })
                    },
                )
            }
        }

        val drawables = model.drawables.map { d ->
            if (d.id.raw !in remountMeshes) return@map d
            val mesh = d.mesh
            if (mesh == null || !parentIsWarp) {
                return@map d.copy(
                    parentDeformerId = rotation.id,
                    mesh = mesh?.let { DrawableMesh(points(it.positions), it.uvs, it.indices) },
                    geometryGrid = d.geometryGrid?.let { grid ->
                        KeyformGrid(grid.axes, grid.cells.map {
                            KeyformCell(it.coordinate, MeshDeltaForm(points(it.form.positionDeltas, delta = true)))
                        })
                    },
                    blendShapes = d.blendShapes.map { binding ->
                        binding.copy(forms = binding.forms.map { form ->
                            form?.let {
                                MeshForm(
                                    points(it.positionDeltas, delta = true),
                                    it.drawOrder, it.opacity, it.multiplyColor, it.screenColor,
                                )
                            }
                        })
                    },
                )
            }

            val base = mesh.positions
            val newBase = throughParent(model, parentId!!, base, defaults)
            val newGrid = d.geometryGrid?.let { grid ->
                KeyformGrid(grid.axes, grid.cells.map { cell ->
                    val abs = FloatArray(base.size) { i -> base[i] + cell.form.positionDeltas[i] }
                    val newAbs = throughParent(
                        model, parentId,
                        abs,
                        paramValueAt(grid.axes, cell.coordinate, defaults),
                    )
                    KeyformCell(
                        cell.coordinate,
                        MeshDeltaForm(FloatArray(base.size) { i -> newAbs[i] - newBase[i] }),
                    )
                })
            }
            val newBlends = d.blendShapes.map { binding ->
                binding.copy(forms = binding.forms.map { form ->
                    form?.let {
                        val abs = FloatArray(base.size) { i -> base[i] + it.positionDeltas[i] }
                        val newAbs = throughParent(model, parentId, abs, defaults)
                        MeshForm(
                            FloatArray(base.size) { i -> newAbs[i] - newBase[i] },
                            it.drawOrder, it.opacity, it.multiplyColor, it.screenColor,
                        )
                    }
                })
            }
            d.copy(
                parentDeformerId = rotation.id,
                mesh = DrawableMesh(newBase, mesh.uvs, mesh.indices),
                geometryGrid = newGrid,
                blendShapes = newBlends,
            )
        }

        return model.copy(deformers = listOf(rotation) + deformers, drawables = drawables).withDerivedRenderRoot()
    }

    /**
     * Map parent-local points through the parent deformer to model space, then into the new pivot's
     * rotation-local frame (pixel deltas from the origin's parent image).
     */
    private fun throughParent(
        model: PuppetModel,
        parentId: DeformerId,
        input: FloatArray,
        paramValue: (ParameterId) -> Float,
    ): FloatArray {
        val defaults: (ParameterId) -> Float = { id ->
            model.parameters.firstOrNull { it.id == id }?.default ?: 0f
        }
        val worlds = buildDeformerWorlds(model.deformers, paramValue, defaults)
        val parent: DeformerWorld = worlds[parentId] ?: return points(input)
        val scratch = FloatArray(2)
        parent.apply(x, y, scratch, 0)
        val ox = scratch[0]
        val oy = scratch[1]
        val out = FloatArray(input.size)
        for (i in input.indices step 2) {
            parent.apply(input[i], input[i + 1], scratch, 0)
            val px = scratch[0] - ox
            val py = scratch[1] - oy
            out[i] = c * px + s * py
            out[i + 1] = -s * px + c * py
        }
        return out
    }

    private fun paramValueAt(
        axes: List<KeyformAxis>,
        coordinate: IntArray,
        defaults: (ParameterId) -> Float,
    ): (ParameterId) -> Float = { id ->
        val axisIndex = axes.indexOfFirst { it.parameterId == id }
        if (axisIndex >= 0 && axisIndex < coordinate.size) {
            axes[axisIndex].keys[coordinate[axisIndex]]
        } else {
            defaults(id)
        }
    }
}
