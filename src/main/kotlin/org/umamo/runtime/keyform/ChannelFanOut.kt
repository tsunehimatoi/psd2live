package org.umamo.runtime.keyform

import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm

/*
 * The import fan-out: splitting one bundled keyform grid into a geometry grid plus per-channel tracks.
 *
 * CMO3 and MOC3 both store an entity's keyforms as a single grid of all-or-nothing cells, so both
 * importers keep building exactly that, and the split happens here afterwards.  Every fanned track SHARES
 * the source grid's axes list by reference and holds one cell per source cell, which makes the split a
 * pure re-shape: gridCorners over a fanned track returns the same corners in the same order as over the
 * source, and each channel's weighted sum accumulates in the same order the bundled blend used.  The
 * evaluated result is therefore bit-identical, which is what lets the whole migration be gated on a
 * zero-diff oracle run rather than a tolerance.
 *
 * Compaction is deliberately NOT part of this - it runs as a separate model pass so the split can land
 * with the oracle green by construction and compaction can be turned on (and off) independently.
 */

/**
 * This grid re-shaped into a channel track, each cell's form mapped through [valueOf].
 *
 * The axes list is shared by reference, not copied - that identity is what guarantees the track brackets
 * every pose exactly as the source grid does.
 *
 * @param Function valueOf Maps one source form to the channel value it carries.
 * @return KeyformGrid The same shape, carrying channel values.
 */
fun <TForm> KeyformGrid<TForm>.asChannelTrack(valueOf: (TForm) -> ChannelValue): KeyformGrid<ChannelValue> =
	KeyformGrid(axes, cells.map { cell -> KeyformCell(cell.coordinate, valueOf(cell.form)) })

/**
 * This grid re-shaped into a geometry grid, each cell's form mapped through [geometryOf].
 *
 * @param Function geometryOf Maps one source form to its geometry payload.
 * @return KeyformGrid The same shape, carrying geometry forms.
 */
fun <TForm, TGeometry> KeyformGrid<TForm>.asGeometryGrid(geometryOf: (TForm) -> TGeometry): KeyformGrid<TGeometry> =
	KeyformGrid(axes, cells.map { cell -> KeyformCell(cell.coordinate, geometryOf(cell.form)) })

/**
 * A [ChannelGrids] holding the given tracks, dropping any that is null.
 *
 * A null track means the owner does not key that channel and reads its static value instead, so an
 * all-null call yields the shared empty instance and allocates nothing.
 *
 * @param Pair tracks The channel-to-track pairs; null tracks are omitted.
 * @return ChannelGrids The tracks, or ChannelGrids.Empty when none survive.
 */
fun channelGridsOf(vararg tracks: Pair<FormChannel, KeyformGrid<ChannelValue>?>): ChannelGrids {
	val present = LinkedHashMap<FormChannel, KeyformGrid<ChannelValue>>(tracks.size)
	for ((channel, grid) in tracks) {
		if (grid != null) {
			present[channel] = grid
		}
	}
	return if (present.isEmpty()) ChannelGrids.Empty else ChannelGrids(present)
}

/**
 * One deformer's bundled keyform grid, split into its geometry grid and its channel tracks.
 *
 * @property KeyformGrid geometry The lattice / pivot geometry.
 * @property ChannelGrids channels The render (and, for a rotation, reflection) tracks.
 */
class FannedDeformer<TGeometry>(
	val geometry: KeyformGrid<TGeometry>,
	val channels: ChannelGrids,
)

/**
 * Splits a bundled warp grid into its lattice geometry and its render channels.
 *
 * Shared by both importers rather than written twice, so the two paths cannot drift in which channel a
 * value lands on - the failure mode a per-importer copy invites.
 *
 * @return FannedDeformer The lattice grid plus the opacity / multiply / screen tracks.
 */
fun KeyformGrid<WarpForm>.fanOutWarp(): FannedDeformer<WarpLatticeForm> =
	FannedDeformer(
		asGeometryGrid { form -> WarpLatticeForm(form.controlPoints) },
		channelGridsOf(
			FormChannel.OPACITY to asChannelTrack { form -> ChannelValue.Scalar(form.opacity) },
			FormChannel.MULTIPLY_COLOR to asChannelTrack { form -> ChannelValue.Color(form.multiplyColor) },
			FormChannel.SCREEN_COLOR to asChannelTrack { form -> ChannelValue.Color(form.screenColor) },
		),
	)

/**
 * Splits a bundled rotation grid into its pivot geometry and its channels.
 *
 * The reflection flags become FLAG tracks here: they snap to the floor cell rather than blending, so they
 * cannot ride the pivot form beside values that interpolate.
 *
 * @return FannedDeformer The pivot grid plus the render and reflection tracks.
 */
fun KeyformGrid<RotationForm>.fanOutRotation(): FannedDeformer<RotationPivotForm> =
	FannedDeformer(
		asGeometryGrid { form -> RotationPivotForm(form.originX, form.originY, form.angle, form.scale) },
		channelGridsOf(
			FormChannel.OPACITY to asChannelTrack { form -> ChannelValue.Scalar(form.opacity) },
			FormChannel.MULTIPLY_COLOR to asChannelTrack { form -> ChannelValue.Color(form.multiplyColor) },
			FormChannel.SCREEN_COLOR to asChannelTrack { form -> ChannelValue.Color(form.screenColor) },
			FormChannel.FLIP_X to asChannelTrack { form -> ChannelValue.Flag(form.flipX) },
			FormChannel.FLIP_Y to asChannelTrack { form -> ChannelValue.Flag(form.flipY) },
		),
	)

/**
 * Splits a bundled art-mesh grid into its per-vertex deltas and its render channels.
 *
 * The geometry half is the only channel the GPU consumes, and its cell linear indices are the delta
 * texture's column layout - which is why it stays a separate typed grid rather than another map entry.
 *
 * @return FannedDeformer The delta grid plus the draw-order / opacity / multiply / screen tracks.
 */
fun KeyformGrid<MeshForm>.fanOutMesh(): FannedDeformer<MeshDeltaForm> =
	FannedDeformer(
		asGeometryGrid { form -> MeshDeltaForm(form.positionDeltas) },
		channelGridsOf(
			FormChannel.DRAW_ORDER to asChannelTrack { form -> ChannelValue.Scalar(form.drawOrder) },
			FormChannel.OPACITY to asChannelTrack { form -> ChannelValue.Scalar(form.opacity) },
			FormChannel.MULTIPLY_COLOR to asChannelTrack { form -> ChannelValue.Color(form.multiplyColor) },
			FormChannel.SCREEN_COLOR to asChannelTrack { form -> ChannelValue.Color(form.screenColor) },
		),
	)