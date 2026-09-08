package org.umamo.runtime.model

/*
 * How a keyform channel is ADDRESSED: an entity plus one of its channels.
 *
 * In :runtime rather than :edit because it is vocabulary about the model, not about editing - the renderer
 * needs it too, to accept per-channel overrides for a value the user has typed but not yet keyed, and
 * :render depends on :runtime but not on :edit.
 */

/**
 * The entity a keyform channel belongs to.
 *
 * Its own taxonomy rather than [SelectionTarget] because a glue is keyable but not selectable, and a glue
 * carries no id - it is addressed by the mesh pair it welds, which is stable across edits in a way a list
 * index is not.
 */
sealed interface KeyformOwner {
	/** A textured drawable mesh. */
	data class Drawable(val id: DrawableId) : KeyformOwner

	/** An organizational tree part. */
	data class Part(val id: PartId) : KeyformOwner

	/** A warp or rotation deformer. */
	data class Deformer(val id: DeformerId) : KeyformOwner

	/** A glue affecter, addressed by the pair of meshes it welds. */
	data class Glue(val meshA: DrawableId, val meshB: DrawableId) : KeyformOwner
}

/**
 * One keyable property: an entity and one of its channels.
 *
 * This is what a keyform insert aims at, and what the properties panel and the keyform sheet both resolve
 * a hover or a click into.
 *
 * @property KeyformOwner owner The entity.
 * @property FormChannel channel The channel on it.
 */
data class KeyableTarget(
	val owner: KeyformOwner,
	val channel: FormChannel,
)

/**
 * One keyform TRACK: an entity's geometry, or one of its channels.
 *
 * The sheet edits both with the same three gestures (drag a key, insert one, remove one), so it addresses
 * them through one type rather than branching per gesture.  They stay distinct cases rather than folding
 * geometry into [FormChannel] because geometry is a separate typed field on every owner precisely so a
 * scalar track can never reach the delta-texture builder - a GEOMETRY channel constant would undo that.
 */
sealed interface KeyformTrackRef {
	/** The entity the track belongs to. */
	val owner: KeyformOwner

	/** A channel track, holding [ChannelValue] cells. */
	data class Channel(val target: KeyableTarget) : KeyformTrackRef {
		override val owner: KeyformOwner get() = target.owner
	}

	/** The owner's geometry track, holding whichever form type that owner deforms with. */
	data class Geometry(override val owner: KeyformOwner) : KeyformTrackRef
}

/**
 * This model's channel tracks for [owner], or null when it has no such entity.
 *
 * Public and here rather than private in an editing file because both the edit ops and the UI's keyed-field
 * tinting need it, and two copies of an owner dispatch is exactly how the two drift.
 *
 * @param KeyformOwner owner The entity to look up.
 * @return ChannelGrids? Its tracks, or null when the entity is absent.
 */
fun PuppetModel.channelGridsOf(owner: KeyformOwner): ChannelGrids? =
	when (owner) {
		is KeyformOwner.Drawable -> drawables.firstOrNull { it.id == owner.id }?.channelGrids
		is KeyformOwner.Part -> parts.firstOrNull { it.id == owner.id }?.channelGrids
		is KeyformOwner.Deformer -> deformers.firstOrNull { it.id == owner.id }?.channelGrids
		is KeyformOwner.Glue -> glues.firstOrNull { it.meshA == owner.meshA && it.meshB == owner.meshB }?.channelGrids
	}