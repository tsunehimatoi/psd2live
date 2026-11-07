package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.modifier.ModifierSourceBounds
import kotlinx.serialization.Serializable
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Headwear and neck shapes also depend on the fitted outline's head landmarks. */
@Serializable
data class MouthOutlineHeadAttachment(
    val canonical: String,
    val tag: String,
    val role: String?,
    val before: Phase,
    val after: Phase,
) {
    @Serializable
    data class Phase(val frame: ModifierSourceBounds, val neck: Neck? = null)

    @Serializable
    data class Neck(val bounds: ModifierSourceBounds, val pivotX: Float, val pivotY: Float)

    internal fun project(
        runtime: PuppetModel,
        actual: Drawable,
        raw: ScaffoldMesh.RawMesh,
        phase: Phase,
        turn: DepthTurnInputs,
        checkpoint: () -> Unit,
    ): Drawable {
        val parent = requireNotNull(actual.parentDeformerId)
        val frame = phase.frame.bounds()
        val (geometry, grid) = ScaffoldMesh.build(raw, ScaffoldArms.Parent(parent, frame), ScaffoldMesh.Geometry.Zero, checkpoint)
        val neutral = actual.copy(mesh = geometry, geometryGrid = grid, blendShapes = emptyList())
        val style = FaceTurn(turn.landmarks, turn.style).takeIf { it.usable }
        val styled = tag == "EARWEAR" || tag == "HEADWEAR" && role !in setOf("RIBBON", "PENDANT", "ANIMAL")
        val shapes = if (style != null && styled)
            DepthTurnMotion.styledShapes(tag, raw.rigPositions, frame, turn.head, turn.container, style, checkpoint)
        else {
            val shell = HeadTurn(turn.container.pivotX, turn.centerY, turn.radiusX, turn.radiusY, turn.shellTurn,
                turn.shellDepth, turn.chinY, checkpoint)
            DepthTurnMotion.shell(tag, role)?.let {
                shell.shapes(raw.rigPositions, frame, it, LegacyFaceBinding.angleX, LegacyFaceBinding.angleY)
            }.orEmpty()
        }
        val neck = phase.neck
        val allShapes = if (neck == null) shapes else {
            fun form(degrees: Float) = MeshForm(NeckAlgorithm.deltas(raw.rigPositions, frame, neck.bounds.bounds(),
                neck.pivotX, neck.pivotY, role == "SHADOW", degrees, checkpoint), opacity = Float.NaN)
            shapes + BlendShapeBinding(LegacyFaceBinding.angleZ, floatArrayOf(-30f, 0f, 30f), 1,
                listOf(form(-30f), null, form(30f)))
        }
        val pose = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val opacity = neutral.channelGrids.scalarAt(FormChannel.OPACITY, neutral.opacity, pose)
        val order = neutral.channelGrids.scalarAt(FormChannel.DRAW_ORDER, neutral.drawOrder, pose)
        val multiply = neutral.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, neutral.multiplyColor, pose)
        val screen = neutral.channelGrids.colorAt(FormChannel.SCREEN_COLOR, neutral.screenColor, pose)
        val bindings = allShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
            form?.let { MeshForm(FloatArray(it.positionDeltas.size) { index -> it.positionDeltas[index] + 0f },
                order, opacity, multiply, screen) }
        }) }
        val staged = runtime.copy(drawables = listOf(neutral.copy(blendShapes = bindings)))
        val result = BlendShapeLowering({ _, name -> name }, checkpoint).lawful(staged).drawables.single()
        require(result.parentDeformerId == parent) { "Fitted outline cannot replace a private attachment turn carrier" }
        return result
    }

    companion object {
        private fun ModifierSourceBounds.bounds() = Bounds(left, top, right, bottom)
    }
}
