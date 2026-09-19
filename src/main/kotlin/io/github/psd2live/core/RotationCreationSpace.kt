package io.github.psd2live.core

import org.umamo.runtime.model.*
import kotlin.math.*

/** Re-express root geometry in a newly created pivot's frame, preserving every existing pose. */
internal class RotationCreationSpace(private val x: Float, private val y: Float, private val angle: Float) {
    private val c = cos(angle * PI.toFloat() / 180f)
    private val s = sin(angle * PI.toFloat() / 180f)

    fun points(input: FloatArray, delta: Boolean = false): FloatArray = input.copyOf().also { out ->
        for (i in input.indices step 2) {
            val px = input[i] - if (delta) 0f else x
            val py = input[i + 1] - if (delta) 0f else y
            out[i] = c * px + s * py
            out[i + 1] = -s * px + c * py
        }
    }

    fun wrap(model: PuppetModel, rotation: Deformer.Rotation, roots: Set<DeformerId>, selected: Set<String>): PuppetModel {
        val deformers = model.deformers.map { d ->
            if (d.id !in roots) d else when (d) {
                is Deformer.Warp -> d.copy(parent = rotation.id,
                    geometryGrid = d.geometryGrid?.let { grid -> KeyformGrid(grid.axes, grid.cells.map {
                        KeyformCell(it.coordinate, WarpLatticeForm(points(it.form.controlPoints)))
                    }) },
                    blendShapes = d.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                        form?.let { WarpForm(points(it.controlPoints), it.opacity, it.multiplyColor, it.screenColor) }
                    }) })
                is Deformer.Rotation -> d.copy(parent = rotation.id, baseAngle = d.baseAngle - angle,
                    geometryGrid = d.geometryGrid?.let { grid -> KeyformGrid(grid.axes, grid.cells.map {
                        val p = points(floatArrayOf(it.form.originX, it.form.originY))
                        KeyformCell(it.coordinate, RotationPivotForm(p[0], p[1], it.form.angle, it.form.scale))
                    }) } ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), points(floatArrayOf(0f, 0f)).let {
                        RotationPivotForm(it[0], it[1], 0f, 1f)
                    }))),
                    blendShapes = d.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                        form?.let {
                            val p = points(floatArrayOf(it.originX, it.originY))
                            RotationForm(p[0], p[1], it.angle, it.scale, it.flipX, it.flipY, it.opacity, it.multiplyColor, it.screenColor)
                        }
                    }) })
            }
        }
        val drawables = model.drawables.map { d ->
            if (d.id.raw !in selected || d.parentDeformerId != null) d else d.copy(parentDeformerId = rotation.id,
                mesh = d.mesh?.let { DrawableMesh(points(it.positions), it.uvs, it.indices) },
                geometryGrid = d.geometryGrid?.let { grid -> KeyformGrid(grid.axes, grid.cells.map {
                    KeyformCell(it.coordinate, MeshDeltaForm(points(it.form.positionDeltas, delta = true)))
                }) },
                blendShapes = d.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                    form?.let { MeshForm(points(it.positionDeltas, delta = true), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
                }) })
        }
        return model.copy(deformers = listOf(rotation) + deformers, drawables = drawables).withDerivedRenderRoot()
    }
}
