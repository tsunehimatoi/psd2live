package org.umamo.interop.moc3.import

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.format.moc3.model.Offscreen
import org.umamo.format.moc3.model.Rgb
import org.umamo.format.moc3.model.RotationDeformer
import org.umamo.format.moc3.model.WarpDeformer
import org.umamo.interop.moc3.MocCanvasMapping
import org.umamo.interop.moc3.PointSpace
import org.umamo.interop.moc3.convertDeltasToRuntime
import org.umamo.interop.moc3.convertPointsToRuntime
import org.umamo.interop.moc3.rotationAncestorFlags
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.format.moc3.model.BlendShape as MocBlendShape

/**
 * Everything the import producers derive from a document before any of them builds a runtime object.
 *
 * Immutable, and with no mutable collaborator alongside it - unlike the export, which passes a keyform
 * pool and a notice sink beside its context because a producer mutates both.  An import reports nothing
 * and interns nothing, so a producer's only input is this.
 *
 * What lives here is what MORE THAN ONE producer needs and what they must not disagree about: the file
 * index to runtime id tables that every cross-reference resolves through, the canvas mapping every
 * coordinate conversion goes through, and the blend records keyed the way each target kind looks them up.
 * Deriving any of it twice is how two producers come to hold different answers.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
internal class Moc3ImportContext(
	val mocDocument: MocDocument,
	val displayInfo: Cdi3Json?,
) {
	/**
	 * The document's canvas record, or null on a canvas-less model.
	 *
	 * MOC3 §5.3 CanvasInfo: pixelsPerUnit + origin place stored model space onto the canvas as a plain
	 * affine, same Y-down orientation: canvasX = originX + ppu·modelX, canvasY = originY + ppu·modelY
	 * (corpus-verified against the CMO3 twin; the Umamo C++ runtime's Y-up presentation happens at eval
	 * time, not in the stored tables).  A canvas-less model keeps the identity mapping so import still
	 * succeeds (degenerate, like CMO3's 0×0 default).
	 */
	val canvas: CanvasInfo? = mocDocument.canvas

	/** The canvas origin's x in canvas space. */
	val canvasOriginX: Float = canvas?.originX ?: 0f

	/** The canvas origin's y in canvas space, before the negation into world space. */
	val canvasOriginY: Float = canvas?.originY ?: 0f

	/** The px↔model mapping every geometry conversion goes through. */
	val canvasMapping: MocCanvasMapping = MocCanvasMapping(canvas?.pixelsPerUnit ?: 1f, canvasOriginX, canvasOriginY)

	/** cdi3 parameter id → display label. */
	val parameterNameById: Map<String, String> = displayInfo?.parameters?.associate { it.id to it.name } ?: emptyMap()

	/** cdi3 part id → display label. */
	val partNameById: Map<String, String> = displayInfo?.parts?.associate { it.id to it.name } ?: emptyMap()

	/** cdi3 drawable id → display label; the Umamo extension, since a moc alone cannot carry these. */
	val drawableNameById: Map<String, String> = displayInfo?.drawables?.associate { it.id to it.name } ?: emptyMap()

	/**
	 * Parameter file index → runtime id, with blank and duplicate slots synthesized.
	 *
	 * This and the three tables below are all in FILE order, because every cross-reference in a MOC3 is
	 * a file-order index - and all four are DE-DUPLICATED, because the runtime addresses by id instead.
	 * A moc names nothing by id, so nothing in the format stops two records from carrying the same one;
	 * Umamo's own export can even mint the pair, since an id too wide for the 64-byte record is written
	 * shortened.  Mapping both records onto one runtime id would merge the two objects - one drawable's
	 * masks, part membership, and keyforms landing on the other - so the first claimant keeps the file's
	 * id and every later one falls back to a synthesized id.
	 */
	val parameterIds: List<ParameterId> =
		distinctIdsOf(mocDocument.parameters.map { parameter -> parameter.id }, "Parameter").map(::ParameterId)

	/** Part file index → runtime id, with blank and duplicate slots synthesized. */
	val partIds: List<PartId> =
		distinctIdsOf(mocDocument.parts.map { part -> part.id }, "Part").map(::PartId)

	/** Drawable file index → runtime id, with blank and duplicate slots synthesized. */
	val drawableIdsByFileIndex: List<DrawableId> =
		distinctIdsOf(mocDocument.artMeshes.map { artMesh -> artMesh.id }, "ArtMesh").map(::DrawableId)

	/**
	 * Deformer file index → runtime id, with blank and duplicate slots synthesized.
	 *
	 * MOC3 §5.6 s11 carries the editor's own identifiers, the same ones the CMO3 side carries, so a
	 * MOC3-origin export writes back the ids the model was authored with.  A blank slot - a hand-built
	 * document, or a MOC3 written without s11 - is the case this table reaches most often; the other
	 * three are blank only in a malformed file.
	 */
	val deformerIds: List<DeformerId> =
		distinctIdsOf(mocDocument.deformers.map { deformer -> deformer.id }, "Deformer").map(::DeformerId)

	/**
	 * Per deformer file index, whether a rotation ancestor already applied the px→model factor.
	 *
	 * The px→model unit seam; see `Moc3SpaceSeam` for why only the first rotation on a path carries it.
	 */
	val hasRotationAncestor: BooleanArray =
		rotationAncestorFlags(
			mocDocument.deformers.size,
			{ index -> mocDocument.deformers[index].parentDeformerIndex },
			{ index -> mocDocument.deformers[index] is RotationDeformer },
		)

	/**
	 * Blend records (MOC3 v4+ §5.6) pre-indexed per target object.
	 *
	 * `targetIndex` is a deformer index for WARP/ROTATION (already remapped by the decoder), a drawable
	 * file index for ART_MESH, and a part file index for PART - which is why the key carries the target
	 * kind alongside the number.
	 */
	val blendRecordsByTarget: Map<Pair<BlendShapeTarget, Int>, List<MocBlendShape>> =
		mocDocument.blendShapes.groupBy { record -> record.target to record.targetIndex }

	// Keyed by the DE-DUPLICATED id, never by the raw file id: on a document carrying the same parameter
	// id twice, a raw-keyed map keeps only the last, so the first parameter's blend records would read
	// the second's default.  Keying by parameterIds makes this lookup and the index-addressed one below
	// two views of the same table rather than two answers.
	private val defaultValueById: Map<ParameterId, Float> =
		parameterIds.withIndex().associate { (parameterIndex, parameterId) ->
			parameterId to mocDocument.parameters[parameterIndex].defaultValue
		}

	/**
	 * The default value of the parameter at [parameterIndex], as a keyform axis names it.
	 *
	 * @param Int parameterIndex The parameter's file index.
	 * @return Float The default, or 0 when the index names nothing.
	 */
	fun defaultValueAt(parameterIndex: Int): Float = mocDocument.parameters.getOrNull(parameterIndex)?.defaultValue ?: 0f

	/**
	 * The default value of [parameterId], as a blend record's driving parameter names it.
	 *
	 * @param ParameterId parameterId The parameter's runtime id.
	 * @return Float The default, or 0 when the id names nothing.
	 */
	fun defaultValueOf(parameterId: ParameterId): Float = defaultValueById[parameterId] ?: 0f

	/**
	 * Offscreen records by their owner part's FILE INDEX.
	 *
	 * MOC3 v6 §5.6 s155: each offscreen names its owner part by index, and both the part import and the
	 * render-order import need to look one up from a part they already hold - so the relation is
	 * inverted once here rather than scanned per part.  Keyed by the index the record itself stores,
	 * not by the owner's id: two parts carrying the same id would otherwise both read whichever
	 * offscreen came last, isolating a part the file never isolated.
	 */
	val offscreenByPartIndex: Map<Int, Offscreen> =
		mocDocument.offscreens
			.filter { offscreen -> offscreen.ownerPartIndex in mocDocument.parts.indices }
			.associateBy { offscreen -> offscreen.ownerPartIndex }

	/**
	 * Resolves the keyform binding for [bindingIndex], or null when the document carries none.
	 *
	 * @param Int bindingIndex A `keyformBindingIndex` from a moc object.
	 * @return KeyformBinding? The binding (a static object resolves to a zero-axis binding).
	 */
	fun bindingOf(bindingIndex: Int): KeyformBinding? = mocDocument.keyformBinding(bindingIndex)

	/**
	 * The coordinate space a child object of [parentDeformerIndex] stores its positions in.  Any
	 * unresolvable index (negative OR out of range) is root space - the same normalization the id
	 * mapping applies via deformerIds.getOrNull, so a malformed parent index cannot leave an object
	 * treated as root-parented but converted as rotation-local.
	 *
	 * @param Int parentDeformerIndex The owning object's parent deformer index (-1 at the root).
	 * @return PointSpace The stored space.
	 */
	fun pointSpaceOf(parentDeformerIndex: Int): PointSpace =
		when (mocDocument.deformers.getOrNull(parentDeformerIndex)) {
			is WarpDeformer -> PointSpace.WarpLattice
			is RotationDeformer -> PointSpace.RotationLocal
			null -> PointSpace.ModelRoot
		}

	/**
	 * Converts interleaved x,y [points] from the moc's stored [space] to the runtime's convention
	 * (CMO3 canvas pixels at the root; parent-local elsewhere).  Warp-lattice and rotation-local
	 * values are stored in the runtime's own convention already (corpus-verified) and pass through
	 * untouched; only root-space values map through CanvasInfo's affine.
	 *
	 * @param PointSpace space  The stored space (from [pointSpaceOf]).
	 * @param FloatArray points Interleaved x,y positions as stored in the moc.
	 * @return FloatArray The converted positions (always a fresh array).
	 */
	fun convertPoints(
		space: PointSpace,
		points: FloatArray,
	): FloatArray = convertPointsToRuntime(space, points, canvasMapping)

	/**
	 * Converts interleaved delta components from the moc's stored [space] to the runtime's
	 * convention.  Unlike [convertPoints] the canvas ORIGIN does not apply - it cancels out of a
	 * difference - so a root-space delta scales by ppu only; the other spaces pass through.
	 *
	 * @param PointSpace space  The stored space (from [pointSpaceOf]).
	 * @param FloatArray deltas Interleaved x,y deltas as stored in the moc.
	 * @return FloatArray The converted deltas (always a fresh array).
	 */
	fun convertDeltas(
		space: PointSpace,
		deltas: FloatArray,
	): FloatArray = convertDeltasToRuntime(space, deltas, canvasMapping)
}

/**
 * Converts a decoded moc3 [Rgb] to the runtime [ColorRgb].
 *
 * Lives beside the context rather than with any one producer because the deformer, drawable, and part
 * imports all read the same two color rows off their keyforms, and a second conversion is how one of
 * them would come to disagree about what an absent row means.
 *
 * @param Rgb? color The decoded color row (MOC3 color tables 108-113), or null when absent.
 * @return ColorRgb? The runtime color, or null so the caller can apply its own channel identity.
 */
internal fun colorRgbOf(color: Rgb?): ColorRgb? = color?.let { ColorRgb(it.r, it.g, it.b) }

/**
 * Maps one section's file ids onto distinct runtime ids, in file order.
 *
 * The first record to carry a given id keeps it verbatim; a blank or repeated one falls back to a
 * synthesized id.  A file id always outranks a synthesized one, even when the synthesizer would have
 * wanted it first - every file id is claimed before the walk starts - so what the file says survives
 * and only what it leaves ambiguous is invented.
 *
 * @param List<String> fileIds        The section's ids, in file order.
 * @param String       fallbackPrefix The stem a synthesized id is built from ("Deformer", "Part", …).
 * @return List<String> One distinct id per file index.
 */
private fun distinctIdsOf(
	fileIds: List<String>,
	fallbackPrefix: String,
): List<String> {
	val claimedIds = fileIds.filterTo(HashSet()) { fileId -> fileId.isNotEmpty() }
	val usedIds = HashSet<String>()
	return fileIds.mapIndexed { fileIndex, fileId ->
		if (fileId.isNotEmpty() && usedIds.add(fileId)) {
			fileId
		} else {
			synthesizedId(fallbackPrefix, fileIndex, claimedIds)
		}
	}
}

/**
 * Synthesizes an id for a slot the file leaves blank (or duplicates).
 *
 * The result joins [claimedIds], so it collides neither with an id the file already uses nor with
 * another synthesized one.
 *
 * @param String     fallbackPrefix The stem to build from.
 * @param Int        fileIndex      The record's file index.
 * @param MutableSet claimedIds     Every id already spoken for; the returned id is added to it.
 * @return String The synthesized id.
 */
private fun synthesizedId(
	fallbackPrefix: String,
	fileIndex: Int,
	claimedIds: MutableSet<String>,
): String {
	var candidate = "$fallbackPrefix$fileIndex"
	var disambiguator = 2
	while (!claimedIds.add(candidate)) {
		candidate = "$fallbackPrefix$fileIndex-$disambiguator"
		disambiguator++
	}
	return candidate
}