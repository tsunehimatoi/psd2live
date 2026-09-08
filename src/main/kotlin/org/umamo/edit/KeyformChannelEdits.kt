package org.umamo.edit

import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.flagAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.keyform.ChannelValueInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.keyform.withAxisSeeded
import org.umamo.runtime.keyform.withFormCaptured
import org.umamo.runtime.keyform.withKeyMoved
import org.umamo.runtime.keyform.withKeyRemoved
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ChannelValueKind
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.channelGridsOf
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Keyform authoring for the SCALAR and COLOR channels - opacity, the multiply / screen tints, draw order,
 * glue intensity.  Geometry keyframing is deliberately not here: it needs the deformer-chain inverse under
 * a pose, which is a separate problem, while these channels need no inverse at all.  That is exactly why
 * they come first - the whole authoring loop (bind, capture, remove, undo, and the sheet reflecting it)
 * gets proven on the easy case before the hard one raises its own questions.
 *
 * Two rules from the grid algebra shape everything below, both forced by how the evaluator brackets a pose:
 * a newly bound axis SPANS the parameter's range (an axis that does not reach the ends leaves the channel
 * falling back to its static there), and removal COLLAPSES below two keys (a one-key axis resolves only
 * within EPS_KEY of that key).
 */

/**
 * This owner's STATIC value for [channel] - what the channel reads when it has no track.
 *
 * The seed value for a first bind: binding replicates the entity's present look across the new axis, so
 * that binding alone never changes the render, only the capture that follows does.
 */
private fun PuppetModel.staticValueOf(owner: KeyformOwner, channel: FormChannel): ChannelValue? =
	when (owner) {
		is KeyformOwner.Drawable ->
			drawables.firstOrNull { it.id == owner.id }?.let { drawable ->
				when (channel) {
					FormChannel.DRAW_ORDER -> ChannelValue.Scalar(drawable.drawOrder)
					FormChannel.OPACITY -> ChannelValue.Scalar(drawable.opacity)
					FormChannel.MULTIPLY_COLOR -> ChannelValue.Color(drawable.multiplyColor)
					FormChannel.SCREEN_COLOR -> ChannelValue.Color(drawable.screenColor)
					else -> null
				}
			}

		is KeyformOwner.Part ->
			parts.firstOrNull { it.id == owner.id }?.let { part ->
				when (channel) {
					FormChannel.DRAW_ORDER -> ChannelValue.Scalar(part.drawOrder.toFloat())
					FormChannel.OPACITY -> ChannelValue.Scalar(part.composite.opacity)
					FormChannel.MULTIPLY_COLOR -> ChannelValue.Color(part.composite.multiplyColor)
					FormChannel.SCREEN_COLOR -> ChannelValue.Color(part.composite.screenColor)
					else -> null
				}
			}

		is KeyformOwner.Deformer ->
			deformers.firstOrNull { it.id == owner.id }?.let { deformer ->
				when (deformer) {
					is Deformer.Warp ->
						when (channel) {
							FormChannel.OPACITY -> ChannelValue.Scalar(deformer.opacity)
							FormChannel.MULTIPLY_COLOR -> ChannelValue.Color(deformer.multiplyColor)
							FormChannel.SCREEN_COLOR -> ChannelValue.Color(deformer.screenColor)
							else -> null
						}

					is Deformer.Rotation ->
						when (channel) {
							FormChannel.OPACITY -> ChannelValue.Scalar(deformer.opacity)
							FormChannel.MULTIPLY_COLOR -> ChannelValue.Color(deformer.multiplyColor)
							FormChannel.SCREEN_COLOR -> ChannelValue.Color(deformer.screenColor)
							FormChannel.FLIP_X -> ChannelValue.Flag(deformer.flipX)
							FormChannel.FLIP_Y -> ChannelValue.Flag(deformer.flipY)
							else -> null
						}
				}
			}

		is KeyformOwner.Glue ->
			glues.firstOrNull { it.meshA == owner.meshA && it.meshB == owner.meshB }?.let { glue ->
				if (channel == FormChannel.GLUE_INTENSITY) ChannelValue.Scalar(glue.intensity) else null
			}
	}

/**
 * This model with [owner]'s channel tracks replaced.
 *
 * A part edit re-derives the render root, because the render tree carries its own copy of each part's
 * tracks and would otherwise keep evaluating the pre-edit ones.
 */
private fun PuppetModel.withChannelGrids(owner: KeyformOwner, channelGrids: ChannelGrids): PuppetModel =
	when (owner) {
		is KeyformOwner.Drawable ->
			copy(drawables = drawables.map { if (it.id == owner.id) it.copy(channelGrids = channelGrids) else it })

		is KeyformOwner.Part ->
			copy(parts = parts.map { if (it.id == owner.id) it.copy(channelGrids = channelGrids) else it })
				.withDerivedRenderRoot()

		is KeyformOwner.Deformer ->
			copy(
				deformers =
					deformers.map { deformer ->
						if (deformer.id != owner.id) {
							deformer
						} else {
							when (deformer) {
								is Deformer.Warp -> deformer.copy(channelGrids = channelGrids)
								is Deformer.Rotation -> deformer.copy(channelGrids = channelGrids)
							}
						}
					},
			)

		is KeyformOwner.Glue ->
			copy(
				glues =
					glues.map { glue ->
						if (glue.meshA == owner.meshA && glue.meshB == owner.meshB) {
							glue.copy(channelGrids = channelGrids)
						} else {
							glue
						}
					},
			)
	}

/** Whether [value] is the kind [channel] stores - a guard against writing a color into a scalar track. */
private fun FormChannel.accepts(value: ChannelValue): Boolean = valueKind == value.valueKind

/**
 * This model with [value] captured on [target]'s channel at [pose], keyed on [parameter].
 *
 * The whole authoring gesture in one op:
 *
 *  - An UNBOUND channel is bound first - a new axis spanning the parameter's min / default / max holding
 *    the entity's current static value, so binding alone changes nothing on screen.
 *  - A channel already keyed on OTHER parameters gains this one as an additional axis, its existing cells
 *    replicated across it, so the motion it already had is preserved.
 *  - The capture then inserts a key at the pose if one is not already there (filling the new slice by
 *    interpolation, so every other pose is unchanged) and writes [value] into the single cell the pose now
 *    sits exactly on.
 *
 * Because the capture always lands on a key, the write is a plain assignment - there is never a blend to
 * invert, which is why these channels need no math the geometry path is still missing.
 *
 * Returns the same instance when the entity is missing, the channel does not belong to it, [value] is the
 * wrong kind for the channel, or the grid algebra refused the reshape (a degenerate parameter range).
 *
 * @param KeyableTarget target The entity and channel to key.
 * @param Parameter parameter The parameter to key on.
 * @param Pose pose The current pose; the capture lands at its value on [parameter].
 * @param ChannelValue value The value to store.
 * @return PuppetModel The model with the key captured, or this on a refusal.
 */
fun PuppetModel.withChannelKeyCaptured(
	target: KeyableTarget,
	parameter: Parameter,
	pose: Pose,
	value: ChannelValue,
): PuppetModel {
	if (!target.channel.accepts(value)) {
		return this
	}
	val grids = channelGridsOf(target.owner) ?: return this
	val staticValue = staticValueOf(target.owner, target.channel) ?: return this
	val defaults = parameters.associate { it.id to it.default }
	val poseValue: (ParameterId) -> Float = { id -> pose[id] ?: defaults[id] ?: 0f }

	val existing = grids[target.channel]
	val bound: KeyformGrid<ChannelValue> =
		if (existing == null || existing.axisIndexOf(parameter.id) < 0) {
			existing.withAxisSeeded(parameter, staticValue) ?: return this
		} else {
			existing
		}
	// Compared against BOUND, not existing: a capture that refuses after a fresh seed must refuse the whole
	// op - committing the bare axis bind would discard the user's value while the channel reads as keyed.
	val captured = bound.withFormCaptured(poseValue, value, ChannelValueInterpolator)
	if (captured === bound) {
		return this
	}
	return withChannelGrids(target.owner, ChannelGrids(grids.gridsByChannel + (target.channel to captured)))
}

/**
 * This model with the key at [pose] removed from [target]'s channel on [parameter]'s axis.
 *
 * Removing below two keys collapses the axis entirely - a one-key axis would resolve only within EPS_KEY of
 * that key and leave the channel on its static everywhere else, which reads as the key having done
 * something bizarre rather than having been removed.  Collapsing the last axis drops the track, and the
 * channel returns to its owner's static value.
 *
 * Returns the same instance when nothing is keyed there - in particular when the pose is not ON a key,
 * since removing "the nearest key" to a pose between two of them is a guess, not an instruction.
 *
 * @param KeyableTarget target The entity and channel.
 * @param Parameter parameter The parameter whose axis to remove from.
 * @param Pose pose The current pose; the key at its value is the one removed.
 * @return PuppetModel The model with the key removed, or this on a refusal.
 */
fun PuppetModel.withChannelKeyRemoved(target: KeyableTarget, parameter: Parameter, pose: Pose): PuppetModel {
	val track = channelGridsOf(target.owner)?.get(target.channel) ?: return this
	val scrubValue = pose[parameter.id] ?: parameters.firstOrNull { it.id == parameter.id }?.default ?: 0f
	val keyIndex = track.keyIndexAt(parameter.id, scrubValue)
	if (keyIndex < 0) {
		return this
	}
	// One removal body: the pose-addressed remove is the ordinal-addressed one after resolving the key,
	// so the collapse-below-two-keys rule cannot fork between the two paths.
	return withChannelKeyRemovedAt(target, parameter, keyIndex)
}

/**
 * The value [target]'s channel currently evaluates to at [pose], or null when the entity or channel does
 * not exist.
 *
 * What an insert captures when nothing is being edited: pinning the value already on screen, which is what
 * Blender's `I` does and how a rigger anchors a pose before moving on.  Reads through the same channel
 * sampling the renderer uses, so the key lands on exactly what was displayed rather than on a value
 * recomputed by a second code path.
 *
 * @param KeyableTarget target The entity and channel.
 * @param Pose pose The pose to sample at.
 * @return ChannelValue? The current value, or null when the target does not resolve.
 */
fun PuppetModel.channelValueAt(target: KeyableTarget, pose: Pose): ChannelValue? {
	val grids = channelGridsOf(target.owner) ?: return null
	val staticValue = staticValueOf(target.owner, target.channel) ?: return null
	// No defaults map: this runs per keyable row per recomposition, a track has at most a few axes, and
	// the pose usually answers - the per-miss linear scan beats an every-call full-map build.
	val poseValue: (ParameterId) -> Float = { id -> pose[id] ?: parameters.firstOrNull { it.id == id }?.default ?: 0f }
	return when (staticValue) {
		is ChannelValue.Scalar -> ChannelValue.Scalar(grids.scalarAt(target.channel, staticValue.value, poseValue))
		is ChannelValue.Color -> ChannelValue.Color(grids.colorAt(target.channel, staticValue.color, poseValue))
		is ChannelValue.Flag -> ChannelValue.Flag(grids.flagAt(target.channel, staticValue.flag, poseValue))
	}
}

/**
 * This model with the key at [fromValue] on [target]'s channel moved to [toValue].
 *
 * The sheet's drag gesture: the key keeps whatever it holds and only changes where on the parameter it
 * applies.  Identified by ORDINAL, not by its current value - keys a hair apart are legal, and resolving a
 * value back to a key picks whichever is nearer, which silently moves the wrong one.
 *
 * @param KeyableTarget target The entity and channel.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The requested new position.
 * @return PuppetModel The model with the key moved, or this on a refusal.
 */
fun PuppetModel.withChannelKeyMoved(
	target: KeyableTarget,
	parameter: Parameter,
	keyIndex: Int,
	toValue: Float,
): PuppetModel {
	val grids = channelGridsOf(target.owner) ?: return this
	val track = grids[target.channel] ?: return this
	val moved = track.withKeyMoved(parameter.id, keyIndex, toValue)
	if (moved === track) {
		return this
	}
	return withChannelGrids(target.owner, ChannelGrids(grids.gridsByChannel + (target.channel to moved)))
}

/**
 * This model with key [keyIndex] removed from [target]'s channel on [parameter]'s axis.
 *
 * By ordinal, for the same reason [withChannelKeyMoved] is: the sheet knows exactly which mark was
 * clicked and must not re-derive it from a value two keys could answer to.
 *
 * @param KeyableTarget target The entity and channel.
 * @param Parameter parameter The parameter whose axis to remove from.
 * @param Int keyIndex The key's ordinal on that axis.
 * @return PuppetModel The model with the key removed, or this on a refusal.
 */
fun PuppetModel.withChannelKeyRemovedAt(target: KeyableTarget, parameter: Parameter, keyIndex: Int): PuppetModel {
	val grids = channelGridsOf(target.owner) ?: return this
	val track = grids[target.channel] ?: return this
	val reduced = track.withKeyRemoved(parameter.id, keyIndex)
	if (reduced === track) {
		return this
	}
	val remaining =
		if (reduced == null) {
			grids.gridsByChannel - target.channel
		} else {
			grids.gridsByChannel + (target.channel to reduced)
		}
	return withChannelGrids(target.owner, ChannelGrids(remaining))
}

/**
 * Whether [target]'s channel is keyed on [parameter] at all - what the properties panel's keyed indicator
 * and the `I` / `Alt+I` availability read.
 *
 * @param KeyableTarget target The entity and channel.
 * @param ParameterId parameterId The parameter to look for.
 * @return Boolean True when a track exists and keys on that parameter.
 */
fun PuppetModel.isChannelKeyedOn(target: KeyableTarget, parameterId: ParameterId): Boolean {
	val track = channelGridsOf(target.owner)?.get(target.channel) ?: return false
	return track.axisIndexOf(parameterId) >= 0
}

/**
 * Captures [value] on [target] at the current pose, keyed on [parameter], as one undo step.
 *
 * @param KeyableTarget target The entity and channel to key.
 * @param Parameter parameter The parameter to key on.
 * @param ChannelValue value The value to store.
 */
fun EditorSession.captureChannelKey(target: KeyableTarget, parameter: Parameter, value: ChannelValue) {
	mutate(KeyformChange.InsertKey(target.channel)) { model ->
		model.withChannelKeyCaptured(target, parameter, pose.value, value)
	}
}

/**
 * Removes the key at the current pose from [target]'s channel on [parameter], as one undo step.
 *
 * @param KeyableTarget target The entity and channel.
 * @param Parameter parameter The parameter whose axis to remove from.
 */
fun EditorSession.removeChannelKey(target: KeyableTarget, parameter: Parameter) {
	mutate(KeyformChange.DeleteKey(target.channel)) { model ->
		model.withChannelKeyRemoved(target, parameter, pose.value)
	}
}

/** The kind guard's counterpart for callers building a value: the neutral value of [channel]'s kind. */
internal fun FormChannel.neutralValue(): ChannelValue =
	when (valueKind) {
		ChannelValueKind.SCALAR -> ChannelValue.Scalar(0f)
		ChannelValueKind.COLOR -> ChannelValue.Color(org.umamo.runtime.model.ColorRgb(0f, 0f, 0f))
		ChannelValueKind.FLAG -> ChannelValue.Flag(false)
	}