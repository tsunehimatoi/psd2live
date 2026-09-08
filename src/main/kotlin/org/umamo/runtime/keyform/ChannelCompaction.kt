package org.umamo.runtime.keyform

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderGroup

/*
 * The post-import compaction pass: reduce every channel track to the axes and keys it actually needs, and
 * lift a track that never varies into its owner's static field.
 *
 * Cubism keys every channel on every cell of one bundled grid, so straight after the fan-out a typical
 * drawable's opacity track is the same value repeated across the whole grid.  Compacting retires those
 * tracks entirely, which is what turns an imported Cubism rig into a sparse Umamo one - and it is where
 * the split pays for itself: the per-frame scalar blends collapse into field reads.
 *
 * Two rules keep this safe:
 *
 *   - GEOMETRY IS NEVER COMPACTED.  A toggle-part drawable can carry bit-identical geometry across an axis
 *     whose keys deliberately do not bracket the parameter's default; dropping that axis would make the
 *     drawable permanently visible, which is a change to someone's rig, not a change in precision.  It
 *     would also move the delta texture's column layout for no benefit.
 *   - EXACT ONLY.  A key is dropped only when the stored value is bit-equal to the blend of its
 *     neighbours; see KeyformGridCompaction.  A near-miss is a value the rigger chose.
 *
 * Run as a separate model pass rather than inside the importers so it is independently testable and can be
 * switched off wholesale when a result needs bisecting against the un-compacted import.
 *
 * DEFERRED: three importer special cases are now redundant in principle - Cmo3Import's
 * `takeIf { it.axes.isNotEmpty() }` on part tracks, its PartComposite seed from the first form, and
 * Moc3Import's deliberate zero-axis single-cell grid.  They are left in place because removing them is a
 * BEHAVIOUR change, not a cleanup: each decides whether a static part's authored composite or its baked
 * keyform wins, and the compaction-on/off equivalence test cannot see the difference (both sides carry the
 * special case).  Only the corpus parity and C-core oracles would catch a mistake, so they should come out
 * one at a time under those gates rather than as a batch.
 */

/**
 * One owner's channel tracks after compaction: the tracks that still vary, and the constants lifted out.
 *
 * @property ChannelGrids channelGrids The surviving tracks.
 * @property Map constants The channels that collapsed, and the value each holds everywhere.
 */
private class CompactedChannels(
	val channelGrids: ChannelGrids,
	val constants: Map<FormChannel, ChannelValue>,
)

/**
 * Compacts every track, separating the ones that collapsed to a constant from the ones that still vary.
 *
 * @param Function axisSpansRange Whether an axis's keys bracket its parameter's whole range - the
 *   out-of-span gate compaction must preserve (see KeyformGridCompaction's file note).
 * @return CompactedChannels The surviving tracks plus the lifted constants.
 */
private fun ChannelGrids.compactedTracks(axisSpansRange: (KeyformAxis) -> Boolean): CompactedChannels {
	if (isEmpty) {
		return CompactedChannels(this, emptyMap())
	}
	val surviving = LinkedHashMap<FormChannel, KeyformGrid<ChannelValue>>(gridsByChannel.size)
	val constants = LinkedHashMap<FormChannel, ChannelValue>()
	for ((channel, grid) in gridsByChannel) {
		when (val result = grid.compacted(ChannelValueInterpolator, channel.valueKind, axisSpansRange)) {
			is CompactionResult.Constant -> constants[channel] = result.form
			is CompactionResult.Reduced -> surviving[channel] = result.grid
		}
	}
	val newGrids = if (surviving.isEmpty()) ChannelGrids.Empty else ChannelGrids(surviving)
	return CompactedChannels(newGrids, constants)
}

/**
 * A lifted DRAW_ORDER constant resolved against an Int draw-order slot: the integral static to write (or
 * null to keep the owner's), and the channel grids to keep.
 *
 * @property Int? staticDrawOrder The exactly-integral lifted draw order, or null.
 * @property ChannelGrids channelGrids The grids to keep - the compacted set, or the compacted set with
 *   [originalGrids]' DRAW_ORDER track restored when the constant was fractional.
 */
private class LiftedDrawOrder(
	val staticDrawOrder: Int?,
	val channelGrids: ChannelGrids,
)

/**
 * Resolves a lifted DRAW_ORDER constant against an Int draw-order slot.
 *
 * A part-like owner's static slot is an Int, so a fractional constant would have to round, and rounding
 * could reorder two siblings the track kept distinct.  Such a track goes back into the kept grids from
 * [originalGrids] instead - correct, just not free.
 *
 * @param Map constants The lifted constants.
 * @param ChannelGrids compactedGrids The surviving tracks after compaction.
 * @param ChannelGrids originalGrids The owner's pre-compaction tracks (the fractional track's source).
 * @return LiftedDrawOrder The static to write (or null) and the grids to keep.
 */
private fun liftedDrawOrder(
	constants: Map<FormChannel, ChannelValue>,
	compactedGrids: ChannelGrids,
	originalGrids: ChannelGrids,
): LiftedDrawOrder {
	if (constants.hasFractionalDrawOrder()) {
		val restored = compactedGrids.gridsByChannel + (FormChannel.DRAW_ORDER to (originalGrids[FormChannel.DRAW_ORDER]!!))
		return LiftedDrawOrder(staticDrawOrder = null, channelGrids = ChannelGrids(restored))
	}
	return LiftedDrawOrder(constants.integralDrawOrderOrNull(), compactedGrids)
}

/**
 * This model with every channel track compacted and every collapsed track lifted into its owner's static.
 *
 * Geometry grids are untouched (see the file note).  Evaluation is unchanged to within the tolerance the
 * fidelity contract already allows for geometry, and is EXACT wherever a track collapsed to a constant -
 * a field read cannot drift the way a weighted sum over corners can.  An axis whose keys do not bracket
 * its parameter's range is left alone entirely: out-of-span poses fall back to the owner's static, and a
 * lift would overwrite exactly the static they fall back to.
 *
 * @return PuppetModel The compacted model.
 */
fun PuppetModel.withChannelsCompacted(): PuppetModel {
	val rangeByParameterId =
		parameters.associate { parameter ->
			parameter.id to (minOf(parameter.min, parameter.max) to maxOf(parameter.min, parameter.max))
		}
	// EPS_KEY on both ends: a key the evaluator cannot tell apart from the range end brackets it.  An axis
	// on a parameter the model does not list is never spanning - a dangling id must stay conservative.
	val axisSpansRange: (KeyformAxis) -> Boolean = { axis ->
		val range = rangeByParameterId[axis.parameterId]
		range != null &&
			axis.keys.isNotEmpty() &&
			axis.keys.first() <= range.first + EPS_KEY &&
			axis.keys.last() >= range.second - EPS_KEY
	}
	return copy(
		drawables =
			drawables.map { drawable ->
				val compacted = drawable.channelGrids.compactedTracks(axisSpansRange)
				if (compacted.constants.isEmpty() && compacted.channelGrids === drawable.channelGrids) {
					drawable
				} else {
					drawable.copy(
						channelGrids = compacted.channelGrids,
						drawOrder = compacted.constants.scalarOr(FormChannel.DRAW_ORDER, drawable.drawOrder),
						opacity = compacted.constants.scalarOr(FormChannel.OPACITY, drawable.opacity),
						multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor),
						screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, drawable.screenColor),
					)
				}
			},
		deformers = deformers.map { deformer -> deformer.withChannelsCompacted(axisSpansRange) },
		parts = parts.map { part -> part.withChannelsCompacted(axisSpansRange) },
		glues =
			glues.map { glue ->
				val compacted = glue.channelGrids.compactedTracks(axisSpansRange)
				if (compacted.constants.isEmpty() && compacted.channelGrids === glue.channelGrids) {
					glue
				} else {
					glue.copy(
						channelGrids = compacted.channelGrids,
						intensity = compacted.constants.scalarOr(FormChannel.GLUE_INTENSITY, glue.intensity),
					)
				}
			},
		renderRoot = renderRoot.withChannelsCompacted(axisSpansRange),
	)
}

/** This deformer with its channel tracks compacted and any constants lifted into its statics. */
private fun Deformer.withChannelsCompacted(axisSpansRange: (KeyformAxis) -> Boolean): Deformer {
	val compacted = channelGrids.compactedTracks(axisSpansRange)
	if (compacted.constants.isEmpty() && compacted.channelGrids === channelGrids) {
		return this
	}
	return when (this) {
		is Deformer.Warp ->
			copy(
				channelGrids = compacted.channelGrids,
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, screenColor),
			)

		is Deformer.Rotation ->
			copy(
				channelGrids = compacted.channelGrids,
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, screenColor),
				flipX = compacted.constants.flagOr(FormChannel.FLIP_X, flipX),
				flipY = compacted.constants.flagOr(FormChannel.FLIP_Y, flipY),
			)
	}
}

/**
 * This part with its channel tracks compacted, constants lifted into [Part.drawOrder] and the composite.
 *
 * A collapsed DRAW_ORDER is lifted only when its value is exactly an integer: the part's static slot is an
 * Int, so a fractional constant would have to round, and rounding could reorder two parts that the track
 * kept distinct.  Such a track keeps its (reduced) grid instead - correct, just not free.
 */
private fun Part.withChannelsCompacted(axisSpansRange: (KeyformAxis) -> Boolean): Part {
	val compacted = channelGrids.compactedTracks(axisSpansRange)
	if (compacted.constants.isEmpty() && compacted.channelGrids === channelGrids) {
		return this
	}
	val lifted = liftedDrawOrder(compacted.constants, compacted.channelGrids, channelGrids)
	return copy(
		channelGrids = lifted.channelGrids,
		drawOrder = lifted.staticDrawOrder ?: drawOrder,
		composite =
			composite.copy(
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, composite.opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, composite.multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, composite.screenColor),
			),
	)
}

/**
 * This render group (and its subtree) with its channel tracks compacted.
 *
 * The render tree carries its own copy of each part's tracks, and for a MOC3 import it is the baked
 * authority rather than something derived from the parts - so it is compacted in place instead of being
 * re-derived, which would discard the baked order.
 */
private fun RenderGroup.withChannelsCompacted(axisSpansRange: (KeyformAxis) -> Boolean): RenderGroup {
	val compacted = channelGrids.compactedTracks(axisSpansRange)
	val lifted = liftedDrawOrder(compacted.constants, compacted.channelGrids, channelGrids)
	return copy(
		drawOrder = lifted.staticDrawOrder ?: drawOrder,
		children = children.map { child -> if (child is RenderGroup) child.withChannelsCompacted(axisSpansRange) else child },
		channelGrids = lifted.channelGrids,
		composite =
			composite?.copy(
				opacity = compacted.constants.scalarOr(FormChannel.OPACITY, composite.opacity),
				multiplyColor = compacted.constants.colorOr(FormChannel.MULTIPLY_COLOR, composite.multiplyColor),
				screenColor = compacted.constants.colorOr(FormChannel.SCREEN_COLOR, composite.screenColor),
			),
	)
}