package org.umamo.runtime.model

/*
 * The geometry half of a split keyform: the only channel whose payload is per-vertex / per-control-point,
 * the only one the GPU consumes, and the only one whose out-of-range pose HIDES its entity.  That is why
 * geometry stays a separate typed field on each owner rather than another FormChannel entry - a scalar
 * track can never be handed to the delta-texture bake by mistake, because it would not type-check.
 *
 * These mirror the bundled MeshForm / WarpForm / RotationForm minus their scalar and color channels, which
 * moved to ChannelGrids.  The bundled classes stay as the import intermediate and the blend-shape payload.
 */

/**
 * A drawable keyform's geometry: per-vertex position deltas (interleaved x,y) relative to the mesh base.
 *
 * Stored as deltas rather than absolute positions to match the GPU vertex-shader morph `p = base + Σ wᵢ·Δᵢ`
 * - the delta table is exactly what the shader texel-fetches per active corner.
 */
class MeshDeltaForm(val positionDeltas: FloatArray)

/**
 * A warp deformer keyform's geometry: the ABSOLUTE FFD lattice control-point positions (interleaved x,y).
 *
 * Absolute, not deltas: a warp source carries no separate rest lattice to be relative to, so these forms
 * are the only place the lattice geometry exists.  That is why an unkeyed warp cannot yet render.
 */
class WarpLatticeForm(val controlPoints: FloatArray)

/**
 * A rotation deformer keyform's geometry: the ABSOLUTE pivot transform captured at one grid cell.
 *
 * The reflection flags that rode the bundled RotationForm are now [FormChannel.FLIP_X] / [FormChannel.FLIP_Y]
 * channels, because they snap to the floor cell rather than blending and so do not belong beside values
 * that interpolate.
 */
class RotationPivotForm(
	val originX: Float,
	val originY: Float,
	val angle: Float,
	val scale: Float,
)