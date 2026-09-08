package org.umamo.edit

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.RuntimeTarget

/*
 * Scalar property edits on an EditorSession, driven by the Properties panel's editable controls.  Each
 * applies one field change as a single undo step via mutate, dispatching the typed Change plus its
 * PuppetModelEdits transform, and short-circuits to nothing on a no-op (the builder returns the same
 * model instance).  These are the write half of the Properties panel: a checkbox / dropdown / numeric
 * field commit calls exactly one of these, and a registry command flipping the same document field
 * (document.toggleSourceArtworkDisplay) goes through the same entry point rather than mutating in
 * parallel, so both routes produce the one identical undo step.  Continuous numeric scrubbing previews
 * in the widget and commits one of these on release, so there is no per-frame mutation and no
 * history-side coalescing (the same single-commit-per-gesture granularity the parameter scrub documents
 * in ParameterChange.SetValue).
 */

/**
 * Sets drawable [id]'s color blend mode as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param BlendMode mode The new blend mode.
 */
fun EditorSession.setDrawableBlendMode(id: DrawableId, mode: BlendMode) {
	mutate(DrawableChange.SetBlendMode(id, mode)) { model -> model.withDrawableBlendMode(id, mode) }
}

/**
 * Sets drawable [id]'s alpha blend mode as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param AlphaBlendMode mode The new alpha blend mode.
 */
fun EditorSession.setDrawableAlphaBlendMode(id: DrawableId, mode: AlphaBlendMode) {
	mutate(DrawableChange.SetAlphaBlendMode(id, mode)) { model -> model.withDrawableAlphaBlendMode(id, mode) }
}

/**
 * Sets drawable [id]'s back-face culling as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean culling The new culling state.
 */
fun EditorSession.setDrawableCulling(id: DrawableId, culling: Boolean) {
	mutate(DrawableChange.SetCulling(id, culling)) { model -> model.withDrawableCulling(id, culling) }
}

/**
 * Sets drawable [id]'s mask-inversion flag as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Boolean invert The new inverted-mask state.
 */
fun EditorSession.setDrawableInvertMask(id: DrawableId, invert: Boolean) {
	mutate(DrawableChange.SetInvertMask(id, invert)) { model -> model.withDrawableInvertMask(id, invert) }
}

/**
 * Sets drawable [id]'s static opacity as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Float opacity The new opacity.
 */
fun EditorSession.setDrawableOpacity(id: DrawableId, opacity: Float) {
	mutate(DrawableChange.SetOpacity(id, opacity)) { model -> model.withDrawableOpacity(id, opacity) }
}

/**
 * Sets drawable [id]'s static draw order as one undo step.
 *
 * @param DrawableId id The drawable to retarget.
 * @param Float drawOrder The new draw order.
 */
fun EditorSession.setDrawableDrawOrder(id: DrawableId, drawOrder: Float) {
	mutate(DrawableChange.SetDrawOrder(id, drawOrder)) { model -> model.withDrawableDrawOrder(id, drawOrder) }
}

/**
 * Sets deformer [id]'s static opacity as one undo step.  Cascades onto every drawable beneath it.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Float opacity The new opacity.
 */
fun EditorSession.setDeformerOpacity(id: DeformerId, opacity: Float) {
	mutate(DeformerChange.SetOpacity(id, opacity)) { model -> model.withDeformerOpacity(id, opacity) }
}

/**
 * Sets deformer [id]'s static multiply color as one undo step.  Cascades onto every drawable beneath it.
 *
 * @param DeformerId id The deformer to retint.
 * @param ColorRgb color The new multiply color.
 */
fun EditorSession.setDeformerMultiplyColor(id: DeformerId, color: ColorRgb) {
	mutate(DeformerChange.SetMultiplyColor(id, color)) { model -> model.withDeformerMultiplyColor(id, color) }
}

/**
 * Sets deformer [id]'s static screen color as one undo step.  Cascades onto every drawable beneath it.
 *
 * @param DeformerId id The deformer to retint.
 * @param ColorRgb color The new screen color.
 */
fun EditorSession.setDeformerScreenColor(id: DeformerId, color: ColorRgb) {
	mutate(DeformerChange.SetScreenColor(id, color)) { model -> model.withDeformerScreenColor(id, color) }
}

/**
 * Sets rotation deformer [id]'s static horizontal reflection as one undo step.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean flip The new reflection state.
 */
fun EditorSession.setDeformerFlipX(id: DeformerId, flip: Boolean) {
	mutate(DeformerChange.SetFlipX(id, flip)) { model -> model.withDeformerFlipX(id, flip) }
}

/**
 * Sets rotation deformer [id]'s static vertical reflection as one undo step.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean flip The new reflection state.
 */
fun EditorSession.setDeformerFlipY(id: DeformerId, flip: Boolean) {
	mutate(DeformerChange.SetFlipY(id, flip)) { model -> model.withDeformerFlipY(id, flip) }
}

/**
 * Sets drawable [id]'s multiply color (the static value) as one undo step.
 *
 * @param DrawableId id The drawable to retint.
 * @param ColorRgb color The new multiply color.
 */
fun EditorSession.setDrawableMultiplyColor(id: DrawableId, color: ColorRgb) {
	mutate(DrawableChange.SetMultiplyColor(id, color)) { model -> model.withDrawableMultiplyColor(id, color) }
}

/**
 * Sets drawable [id]'s screen color (the static value) as one undo step.
 *
 * @param DrawableId id The drawable to retint.
 * @param ColorRgb color The new screen color.
 */
fun EditorSession.setDrawableScreenColor(id: DrawableId, color: ColorRgb) {
	mutate(DrawableChange.SetScreenColor(id, color)) { model -> model.withDrawableScreenColor(id, color) }
}

/**
 * Binds drawable [id] to the deformer that deforms it (null unbinds) as one undo step.
 *
 * @param DrawableId id The drawable to rebind.
 * @param DeformerId? parentDeformerId The deformer that deforms it, or null to unbind.
 */
fun EditorSession.setDrawableParentDeformer(id: DrawableId, parentDeformerId: DeformerId?) {
	mutate(DrawableChange.SetParentDeformer(id, parentDeformerId)) { model ->
		model.withDrawableParentDeformer(id, parentDeformerId)
	}
}

/**
 * Replaces drawable [id]'s clip-mask list as one undo step, so adding or removing a single mask is one
 * step.
 *
 * @param DrawableId id The drawable whose masks change.
 * @param List maskedBy The drawables whose alpha now clips it.
 */
fun EditorSession.setDrawableMaskedBy(id: DrawableId, maskedBy: List<DrawableId>) {
	mutate(DrawableChange.SetMaskedBy(id, maskedBy)) { model -> model.withDrawableMaskedBy(id, maskedBy) }
}

/**
 * Binds deformer [id] to the organizational part that owns it (null clears it) as one undo step.
 *
 * @param DeformerId id The deformer to rebind.
 * @param PartId? partId The part that owns it, or null to clear.
 */
fun EditorSession.setDeformerPart(id: DeformerId, partId: PartId?) {
	mutate(DeformerChange.SetPart(id, partId)) { model -> model.withDeformerPart(id, partId) }
}

/**
 * Sets rotation deformer [id]'s base angle as one undo step. A no-op on a warp deformer (it has no base
 * angle), so the commit short-circuits.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Float angle The new base angle in degrees.
 */
fun EditorSession.setDeformerBaseAngle(id: DeformerId, angle: Float) {
	mutate(DeformerChange.SetBaseAngle(id, angle)) { model -> model.withDeformerBaseAngle(id, angle) }
}

/**
 * Sets warp deformer [id]'s FFD interpolation mode as one undo step. A no-op on a rotation deformer (it
 * has no lattice), so the commit short-circuits.
 *
 * @param DeformerId id The deformer to retarget.
 * @param Boolean quad The new quad-transform state.
 */
fun EditorSession.setDeformerQuadTransform(id: DeformerId, quad: Boolean) {
	mutate(DeformerChange.SetQuadTransform(id, quad)) { model -> model.withDeformerQuadTransform(id, quad) }
}

/**
 * Sets part [id]'s guide-image (sketch) flag as one undo step.
 *
 * @param PartId id The part to retarget.
 * @param Boolean sketch The new sketch state.
 */
fun EditorSession.setPartSketch(id: PartId, sketch: Boolean) {
	mutate(PartChange.SetSketch(id, sketch)) { model -> model.withPartSketch(id, sketch) }
}

/**
 * Sets part [id]'s own draw order as one undo step.
 *
 * @param PartId id The part to retarget.
 * @param Int order The new draw order.
 */
fun EditorSession.setPartDrawOrder(id: PartId, order: Int) {
	mutate(PartChange.SetDrawOrder(id, order)) { model -> model.withPartDrawOrder(id, order) }
}

/**
 * Sets part [id]'s rendering group mode as one undo step. The [mode] carries the whole value, so a mode
 * switch and any Isolated-composite sub-field edit both flow through here.
 *
 * @param PartId id The part to retarget.
 * @param PartGroupMode mode The new group mode.
 */
fun EditorSession.setPartGroupMode(id: PartId, mode: PartGroupMode) {
	mutate(PartChange.SetGroupMode(id, mode)) { model -> model.withPartGroupMode(id, mode) }
}

/**
 * Sets part [id]'s latent compositing settings as one undo step.  Stored independent of the group mode,
 * so an isolated part's composite survives leaving and re-entering Isolated; applied only while Isolated.
 *
 * @param PartId id The part to retarget.
 * @param PartComposite composite The new composite settings.
 */
fun EditorSession.setPartComposite(id: PartId, composite: PartComposite) {
	mutate(PartChange.SetComposite(id, composite)) { model -> model.withPartComposite(id, composite) }
}

/**
 * Sets the document canvas size (world units) as one undo step.
 *
 * @param Float width The new canvas width.
 * @param Float height The new canvas height.
 */
fun EditorSession.setCanvasSize(width: Float, height: Float) {
	mutate(DocumentChange.SetCanvasSize(width, height)) { model -> model.withCanvasSize(width, height) }
}

/**
 * Sets the world origin (world space) as one undo step.
 *
 * @param Float x The new world-origin x.
 * @param Float y The new world-origin y.
 */
fun EditorSession.setWorldOrigin(x: Float, y: Float) {
	mutate(DocumentChange.SetWorldOrigin(x, y)) { model -> model.withWorldOrigin(x, y) }
}

/**
 * Sets the document's runtime-compatibility target as one undo step.
 *
 * @param RuntimeTarget target The new runtime target.
 */
fun EditorSession.setRuntimeTarget(target: RuntimeTarget) {
	mutate(DocumentChange.SetRuntimeTarget(target)) { model -> model.withRuntimeTarget(target) }
}

/**
 * Switches the puppet between displaying from its source artwork and from the packed atlas, as one
 * undo step.
 *
 * A display choice that is document content rather than an app preference: the source formats author
 * it, so it round-trips and it marks the document dirty like any other authored value.
 *
 * @param Boolean fromSourceLayers True to display from the source artwork, false from the atlas.
 */
fun EditorSession.setSourceLayerDisplay(fromSourceLayers: Boolean) {
	mutate(DocumentChange.SetSourceLayerDisplay(fromSourceLayers)) { model -> model.withSourceLayerDisplay(fromSourceLayers) }
}