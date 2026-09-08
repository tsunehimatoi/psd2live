package org.umamo.runtime.model

/**
 * A deformer in the rig hierarchy.
 *
 * Cubism deformation is parameter-driven morph blending, not skeletal - there are exactly two
 * deformer kinds, so a `sealed interface` captures the closed taxonomy and forces `when`s over it to
 * be exhaustive (adding a third kind becomes a compile error until handled everywhere). Deformers nest
 * via [parent] (the transform hierarchy a drawable inherits); each also belongs to a part via [partId]
 * (the organizational tree) - two independent hierarchies, mirroring CMO3's `targetDeformerGuid` vs
 * `parentGuid`.
 *
 * リグ階層の変形器。Cubism は2種(ワープ／回転)のみ。変形系統([parent])と組織系統([partId])は別。
 */
sealed interface Deformer {
	val id: DeformerId

	/** The user-facing display name (CMO3 localName), e.g. "Warp Deformer of Bag front12"; the id when unnamed. */
	val name: String

	/** Parent deformer in the nesting (transform) hierarchy, or null at the root. */
	val parent: DeformerId?

	/** The part this deformer belongs to (organizational tree), or null at the root. */
	val partId: PartId?

	/**
	 * The deformer's per-channel keyform tracks.
	 *
	 * On the interface rather than only the subtypes because every generic walk over a rig - the
	 * parameter-delete scrub, the panel's effective-parameter set, the clamped-pose axis collection -
	 * needs the tracks without caring which kind of deformer carries them.
	 */
	val channelGrids: ChannelGrids

	/**
	 * Blender-style selectable toggle: an unselectable deformer cannot be picked in the viewport.
	 * Maps inverted to CMO3's isLocked (Cubism lock = not selectable), which the CMO3 export
	 * lowering emits as isLocked = !isSelectable.
	 */
	val isSelectable: Boolean

	/**
	 * The editor's eye toggle - a hidden deformer and everything under it does not render.
	 *
	 * MOC3 §5.6 s13; CMO3 ACParameterControllableSource.isVisible.  A bake normally DELETES what the
	 * editor hides, so almost every imported deformer is visible; Azxiana is the one corpus model that
	 * keeps 7 hidden ones.  Umamo's own export always carries hidden deformers (the export options
	 * offer no deformer toggle), which is what makes this round-trip rather than decay to true.
	 */
	val isVisible: Boolean

	/**
	 * The deformer's second MOC3 flag (§5.6 s14), carried so a bake reproduces it.
	 *
	 * It is 1 on every deformer of every corpus model, so its meaning is unpinned - it is NOT the
	 * visibility toggle ([isVisible] is, by analogy with the art-mesh pair that a CMO3 join proved).
	 * Nothing reads it; it exists so the value survives a round trip instead of being invented.
	 *
	 * ONLY MOC3 HAS THIS.  "Enabled" is just a guess as to what it is since it seems to behave like an enabled toggle.
	 */
	val isEnabled: Boolean

	/**
	 * Warp deformer - a free-form-deformation (FFD) lattice: a [rows] × [columns] grid of control
	 * points whose displacement bends the bound geometry. The control-point positions are animated
	 * per keyform; only the lattice dimensions are static.
	 */
	data class Warp(
		override val id: DeformerId,
		override val name: String,
		override val parent: DeformerId?,
		override val partId: PartId?,
		val rows: Int,
		val columns: Int,
		/** FFD interpolation mode: true = bilinear (quad), false = triangle split (the Umamo C++ Runtime's warp mode). */
		val isQuadTransform: Boolean,
		/**
		 * Per-parameter lattice control-point forms, or null if unkeyed.
		 *
		 * A warp carries no separate rest lattice, so this is the ONLY source of its geometry - an unkeyed
		 * warp therefore still cannot render, unlike an unkeyed drawable which falls back to its rest mesh.
		 */
		val geometryGrid: KeyformGrid<WarpLatticeForm>?,
		/**
		 * The deformer's per-channel render tracks (opacity, multiply / screen color), each falling back to
		 * the statics below.  These CASCADE onto every drawable under the deformer.
		 */
		override val channelGrids: ChannelGrids = ChannelGrids.Empty,
		/** Static opacity used when [channelGrids] has no opacity track. */
		val opacity: Float = 1f,
		/** Static multiply color used when [channelGrids] has no multiply track. */
		val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
		/** Static screen color used when [channelGrids] has no screen track. */
		val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
		override val isSelectable: Boolean = true,
		override val isVisible: Boolean = true,
		override val isEnabled: Boolean = true,
		/**
		 * Additive blend-shape bindings on the lattice control points, applied on top of the
		 * [geometryGrid] result; empty when the deformer has none. (CMO3 keyformMorphTargetSet.)
		 */
		val blendShapes: List<BlendShapeBinding<WarpForm>> = emptyList(),
	) : Deformer

	/**
	 * Rotation deformer - a nesting pivot transform. Its origin/angle/scale are animated per keyform
	 * [baseAngle] is the static editor reference angle.
	 */
	data class Rotation(
		override val id: DeformerId,
		override val name: String,
		override val parent: DeformerId?,
		override val partId: PartId?,
		val baseAngle: Float,
		/** Per-parameter pivot-transform forms (origin / angle / scale), or null if unkeyed. */
		val geometryGrid: KeyformGrid<RotationPivotForm>?,
		/**
		 * The deformer's per-channel tracks: the render channels (opacity, multiply / screen color) that
		 * cascade onto every drawable underneath, plus the two reflection FLAGS.  The flags live here rather
		 * than on the pivot form because they snap to the floor cell instead of blending, so they do not
		 * belong beside values that interpolate.
		 */
		override val channelGrids: ChannelGrids = ChannelGrids.Empty,
		/** Static opacity used when [channelGrids] has no opacity track. */
		val opacity: Float = 1f,
		/** Static multiply color used when [channelGrids] has no multiply track. */
		val multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
		/** Static screen color used when [channelGrids] has no screen track. */
		val screenColor: ColorRgb = ColorRgb.ScreenIdentity,
		/** Static horizontal reflection used when [channelGrids] has no flip-X track. */
		val flipX: Boolean = false,
		/** Static vertical reflection used when [channelGrids] has no flip-Y track. */
		val flipY: Boolean = false,
		override val isSelectable: Boolean = true,
		override val isVisible: Boolean = true,
		override val isEnabled: Boolean = true,
		/**
		 * Additive blend-shape bindings on the pivot transform, applied on top of the [geometryGrid]
		 * result; empty when the deformer has none. (CMO3 keyformMorphTargetSet.)
		 */
		val blendShapes: List<BlendShapeBinding<RotationForm>> = emptyList(),
	) : Deformer
}

/*
 * The statics every deformer subtype carries, read through the sealed supertype.
 *
 * Deformer is a sealed interface whose statics live on the subtypes, so anything wanting "this deformer's
 * opacity" had to write the two-branch when itself - and three separate copies of each had accumulated
 * (the Properties row, the edit op, and their tests), so adding a third subtype meant fixing them in
 * lockstep and any divergence would silently make the panel disagree with the edit.  One definition here,
 * exhaustive over the sealed type, so a new subtype is a compile error in exactly one place.
 */

/** This deformer's static opacity, whichever subtype it is. */
val Deformer.opacity: Float
	get() =
		when (this) {
			is Deformer.Warp -> opacity
			is Deformer.Rotation -> opacity
		}

/** This deformer's static multiply color, whichever subtype it is. */
val Deformer.multiplyColor: ColorRgb
	get() =
		when (this) {
			is Deformer.Warp -> multiplyColor
			is Deformer.Rotation -> multiplyColor
		}

/** This deformer's static screen color, whichever subtype it is. */
val Deformer.screenColor: ColorRgb
	get() =
		when (this) {
			is Deformer.Warp -> screenColor
			is Deformer.Rotation -> screenColor
		}