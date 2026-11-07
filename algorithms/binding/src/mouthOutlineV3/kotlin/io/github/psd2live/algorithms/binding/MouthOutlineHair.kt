package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.modifier.ModifierSourceBounds
import io.github.psd2live.core.modifier.RigModifierInstance
import kotlinx.serialization.Serializable
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

@Serializable
data class MouthOutlineHair(
    val canonical: String,
    val tag: String,
    val chain: String,
    val before: Phase,
    val after: Phase,
) {
    @Serializable
    data class Phase(
        val layer: String, val kind: ChainKind, val index: Int, val frame: ModifierSourceBounds,
        val freeFrom: Float, val regions: Int, val strength: Float, val softness: Float,
        val plane: ModifierSourceBounds,
    ) {
        fun motion() = ChainMotion(layer, kind, index, frame.bounds(), freeFrom, regions, strength, softness)
    }

    internal fun project(
        runtime: PuppetModel,
        actual: Drawable,
        carrier: Deformer.Warp,
        raw: ScaffoldMesh.RawMesh,
        phase: Phase,
        turn: DepthTurnInputs,
        provider: RigModifierInstance,
        checkpoint: () -> Unit,
    ): Pair<Drawable, Deformer.Warp> {
        val chain = phase.motion()
        require("SourceChain:${chain.key}" == this.chain && actual.parentDeformerId == carrier.id)
        val frame = chain.frame
        val plane = phase.plane.bounds()
        val rows = ChainMotion.ROWS
        val columns = ChainMotion.COLUMNS
        val part = requireNotNull(carrier.partId)
        val follow = requireNotNull(carrier.parent)
        // The old sway has no lattice after its only parameter is removed; its child keeps these coordinates.
        val warp = ScaffoldPairs.identityWarp(carrier.id, carrier.name, follow, frame, plane, part, rows, columns)
        val rest = FloatArray((rows + 1) * (columns + 1) * 2)
        var i = 0
        for (row in 0..rows) for (column in 0..columns) {
            rest[i++] = frame.left + frame.width * column / columns
            rest[i++] = frame.top + frame.height * row / rows
        }
        val aliases = chain.aliases(provider)
        val warpShapes = chain.shapes(rest, rows, columns) { points ->
            FloatArray(points.size) { index ->
                if (index % 2 == 0) (points[index] - plane.left) / plane.width.coerceAtLeast(1e-4f)
                else (points[index] - plane.top) / plane.height.coerceAtLeast(1e-4f)
            }
        }.map { it.copy(parameterId = ParameterId(aliases.getValue(it.parameterId.raw))) }
        val (geometry, grid) = ScaffoldMesh.build(raw, ScaffoldArms.Parent(carrier.id, frame), ScaffoldMesh.Geometry.Zero, checkpoint)
        val neutral = actual.copy(mesh = geometry, geometryGrid = grid, blendShapes = emptyList())
        val style = FaceTurn(turn.landmarks, turn.style).takeIf { it.usable }
        val shapes = if (style != null) DepthTurnMotion.styledShapes(tag, raw.rigPositions, frame, turn.head, turn.container, style, checkpoint)
        else {
            val shell = HeadTurn(turn.container.pivotX, turn.centerY, turn.radiusX, turn.radiusY, turn.shellTurn,
                turn.shellDepth, turn.chinY, checkpoint)
            DepthTurnMotion.shell(tag, null)?.let {
                shell.shapes(raw.rigPositions, frame, it, LegacyFaceBinding.angleX, LegacyFaceBinding.angleY)
            }.orEmpty()
        }
        val pose = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val opacity = neutral.channelGrids.scalarAt(FormChannel.OPACITY, neutral.opacity, pose)
        val order = neutral.channelGrids.scalarAt(FormChannel.DRAW_ORDER, neutral.drawOrder, pose)
        val multiply = neutral.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, neutral.multiplyColor, pose)
        val screen = neutral.channelGrids.colorAt(FormChannel.SCREEN_COLOR, neutral.screenColor, pose)
        val meshShapes = shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
            form?.let { MeshForm(it.positionDeltas, order, opacity, multiply, screen) }
        }) }
        val staged = runtime.copy(deformers = runtime.deformers.filter { it.id != carrier.id } + warp.copy(blendShapes = warpShapes),
            drawables = listOf(neutral.copy(blendShapes = meshShapes)))
        val lowered = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(staged)
        val mesh = lowered.drawables.single()
        require(mesh.parentDeformerId == carrier.id) { "Fitted outline cannot replace a private hair turn carrier" }
        return mesh to (lowered.deformers.single { it.id == carrier.id } as Deformer.Warp)
    }

    companion object {
        private fun ModifierSourceBounds.bounds() = Bounds(left, top, right, bottom)
    }
}
