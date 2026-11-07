package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Rebuilds native head lattices before and after a source landmark change. */
object SourceHeadProjection {
    data class Body(val torso: TorsoFrame, val tuning: BodyMotionTuning, val strength: Float)

    data class Phase(
        val frames: JsonObject,
        val face: LegacyFaceInputs.Input,
        val turn: DepthTurnInputs,
        val body: Body?,
    ) {
        companion object {
            fun decode(context: ModifierAlgorithmContext, checkpoint: () -> Unit): Phase {
                val values = context.values
                val face = ModifierAlgorithmContext(values.getValue("base").jsonObject)
                val frames = LegacyFaceInputs.scaffold(face).values.getValue("frames").jsonObject
                val turn = DepthTurnInputs.decode(ModifierAlgorithmContext(values.getValue("turn").jsonObject, context.resources))
                val body = values["body"]?.jsonObject?.let {
                    Body(LegacyBodyInputs.torso(it.getValue("torso")),
                        LegacyBodyInputs.tuning(it.getValue("tuning").jsonObject),
                        LegacyBodyInputs.finite(it.getValue("strength")))
                }
                return Phase(frames, LegacyFaceInputs.decode(face, checkpoint), turn, body)
            }
        }
    }

    fun project(runtime: PuppetModel, phase: Phase, checkpoint: () -> Unit): List<Deformer> {
        val neutral = ScaffoldFramesContext.build(phase.frames, checkpoint)
        val body = phase.body
        val bodyFrames = if (body == null) neutral else {
            val frames = ScaffoldFramesContext.decode(phase.frames, checkpoint)
            val legs = frames.standing?.legs.orEmpty().map { leg ->
                BodyStance.Leg(Side.NONE, leg.hip.x, leg.hip.y, leg.knee.x, leg.knee.y, leg.ankle.x, leg.ankle.y, leg.floorY)
            }
            val stance = BodyStance(frames.body, body.torso, legs, frames.standing?.frame, body.strength, body.tuning)
            LegacyBodyMotion.deformers(neutral, frames.body, stance, body.strength, frames.head.canvasPivot.y, checkpoint = checkpoint)
        }
        val angles = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
        val parameters = runtime.parameters.associateBy { it.id }
        val face = phase.face
        val bound = LegacyFaceBinding.bind(runtime.copy(parameters = emptyList(), deformers = bodyFrames),
            face.measurements, face.tuning, face.strength, angles.map(parameters::getValue), face.folderLabel, checkpoint)
        val turn = phase.turn
        val byId = bound.deformers.associateBy { it.id }
        val underFace = bound.deformers.filter { deformer ->
            generateSequence(deformer.parent) { byId[it]?.parent }.any { it == ScaffoldHead.faceId }
        }.mapTo(hashSetOf()) { it.id }
        val styled = FaceTurn(turn.landmarks, turn.style).takeIf { it.usable }
        val turned = if (styled != null) {
            DepthTurnMotion.deformers(bound.deformers, styled, turn.head, turn.face, turn.container,
                turn.frames, underFace, turn.fittedEyes, turn.fittedBrows, turn.landmarks, checkpoint)
        } else {
            val shell = HeadTurn(turn.container.pivotX, turn.centerY, turn.radiusX, turn.radiusY, turn.shellTurn,
                turn.shellDepth, turn.chinY, checkpoint)
            val mouthWarp = ScaffoldHead.featureId(ScaffoldHead.Feature.MOUTH, Side.NONE)
            bound.deformers.map { deformer ->
                if (deformer.id == mouthWarp && deformer is Deformer.Warp)
                    turn.mouthWarp?.place(deformer, shell, turn.container.pivotX, turn.radiusX, turn.container.strength, checkpoint)
                        ?: deformer
                else deformer
            }
        }
        val leaned = LegacyBodyMotion.withFaceLean(turned, turn.leanDown, turn.leanUp)
        return BindingProjection.retain(runtime.copy(deformers = leaned), parameters.keys, checkpoint = checkpoint).deformers
    }

    /** The ordinary parent receives the styled form before the shape budget decides where to store it. */
    fun styledMesh(
        drawable: Drawable,
        raw: ScaffoldMesh.RawMesh,
        frame: Bounds,
        parent: DeformerId,
        tag: String,
        turn: DepthTurnInputs,
        checkpoint: () -> Unit,
    ): Drawable {
        val field = FaceTurn(turn.landmarks, turn.style)
        require(field.usable)
        val (mesh, offsets) = ScaffoldMesh.build(raw, ScaffoldArms.Parent(parent, frame), ScaffoldMesh.Geometry.Zero, checkpoint)
        val shapes = DepthTurnMotion.styledShapes(tag, raw.rigPositions, frame, turn.head, turn.container, field, checkpoint)
        val pose = { id: ParameterId -> if (id.raw == "ParamEyeLOpen" || id.raw == "ParamEyeROpen") 1f else 0f }
        val channels = drawable.channelGrids
        val order = channels.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, pose)
        val opacity = channels.scalarAt(FormChannel.OPACITY, drawable.opacity, pose)
        val multiply = channels.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, pose)
        val screen = channels.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, pose)
        val neutralized = shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
            form?.let { MeshForm(FloatArray(it.positionDeltas.size) { i -> it.positionDeltas[i] + 0f },
                order, opacity, multiply, screen) }
        }) }
        return drawable.copy(parentDeformerId = parent, mesh = mesh, geometryGrid = offsets, blendShapes = neutralized)
    }
}
