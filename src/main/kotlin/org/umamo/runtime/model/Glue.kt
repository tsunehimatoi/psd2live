package org.umamo.runtime.model

/**
 * One glued vertex pair: vertex [indexA] of the glue's mesh A welded to vertex [indexB] of mesh B, each
 * pulled toward the other by its per-side weight. Indices are into the meshes' deformed position arrays,
 * so each importer has to land them in that space: a CMO3 import resolves that format's stable vertex
 * UIDs, while MOC3 (§5.6 s99) already stores them mesh-local.
 */
class GluePair(
	val indexA: Int,
	val indexB: Int,
	val weightA: Float,
	val weightB: Float,
)

/**
 * A glue keyform: the weld intensity (0 = no weld, 1 = full) at one grid cell.
 *
 * Retained as the CMO3 / MOC3 import intermediate; the model itself keys weld strength through
 * [FormChannel.GLUE_INTENSITY] on [Glue.channelGrids].
 */
class GlueForm(val intensity: Float)

/**
 * A glue affecter - seam-welds two art meshes' shared-edge vertices so they move together (a tail's
 * two skinned strips, a sleeve seam, …). It runs after both meshes are deformed: for each [GluePair]
 * each side slides toward the other by `weight · intensity` from the pre-blend positions -
 * `A' = A + (B−A)·wA·i`, `B' = B + (A−B)·wB·i`. Without it the welded strips drift apart into "two
 * copies".
 *
 * Weld strength is the [FormChannel.GLUE_INTENSITY] track when one is keyed, else the static
 * [intensity] - which is 1 (full weld) for a glue that was never animated.
 */
class Glue(
	val meshA: DrawableId,
	val meshB: DrawableId,
	val pairs: List<GluePair>,
	val channelGrids: ChannelGrids = ChannelGrids.Empty,
	val intensity: Float = 1f,
	/**
	 * The source's authored identifier (MOC3 §5.6 s90, e.g. "Glue__ArtMesh48__ArtMesh49"), or null
	 * when the source carried none.
	 *
	 * A plain string rather than a typed id because nothing REFERENCES a glue - it is addressed only by
	 * list position, so there is no lookup for an id type to make safe.  It is carried purely so a
	 * round trip writes back the name the model was authored with instead of a synthesized one.
	 */
	val id: String? = null,
) {
	/**
	 * This glue with the given fields replaced, every other one carried over.
	 *
	 * Every rewrite goes through here rather than the constructor, so changing one field cannot silently
	 * drop another - a positional rebuild that stops early resets the rest to their defaults, and [id]
	 * is the one whose default is indistinguishable from a source that carried none.
	 *
	 * Hand-written rather than a `data class` conversion: the generated `equals`/`hashCode` would
	 * compare [pairs] and the channel grids by reference anyway, so that would buy this one method at
	 * the cost of a misleading equality contract.
	 *
	 * @param DrawableId     meshA         The first welded mesh.
	 * @param DrawableId     meshB         The second welded mesh.
	 * @param List<GluePair> pairs         The welded vertex pairs.
	 * @param ChannelGrids   channelGrids  The keyed channel tracks.
	 * @param Float          intensity     The static weld strength.
	 * @param String?        id            The source's authored identifier.
	 * @return Glue The rewritten glue.
	 */
	fun copy(
		meshA: DrawableId = this.meshA,
		meshB: DrawableId = this.meshB,
		pairs: List<GluePair> = this.pairs,
		channelGrids: ChannelGrids = this.channelGrids,
		intensity: Float = this.intensity,
		id: String? = this.id,
	): Glue = Glue(meshA, meshB, pairs, channelGrids, intensity, id)
}