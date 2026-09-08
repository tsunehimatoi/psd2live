package org.umamo.interop.moc3.export

import org.umamo.format.moc3.moc.Sections
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId

/**
 * The id each object is written under, one namespace per moc id space.
 *
 * MOC3 §5.4 makes every id a fixed 64-byte record, so an id whose UTF-8 form does not fit with room
 * for the terminator cannot be written - the writer's own precondition rejects one, and an export that
 * let that reach the caller would throw straight past the report and out of the file write.  CMO3
 * places no width limit on these ids, and 22 CJK characters already exceed the record, so shortening
 * is a reachable path rather than a theoretical one.
 *
 * Shortening is also what MINTS DUPLICATES: two names that differ only past the 63rd byte truncate to
 * the same string.  A MOC3 addresses nothing by id, so the file itself takes the duplicate without
 * complaint and the loss surfaces only on the way back, where a re-import merges the two objects into
 * one - one mesh's masks, part membership, and keyforms landing on the other.  So a shortened id is
 * disambiguated here rather than reported and left colliding.
 *
 * Every id the model can write VERBATIM is claimed up front, at construction, rather than as each
 * object is lowered.  Otherwise the outcome would depend on lowering order: a shortened id could take
 * a name that a later object holds outright, and that object - whose id needed no shortening at all -
 * would be the one renamed.  Claiming first means shortening can only ever yield ground, never take it.
 *
 * Mutable (it accumulates claims), so it is passed to each producer explicitly alongside the keyform
 * pool and the notice sink rather than held on the immutable [Moc3ExportContext].
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.4</a>
 */
internal class Moc3ExportIds(
	plan: Moc3IndexPlan,
	glues: List<Glue>,
	noticeSink: Moc3ExportNotices,
) {
	// One namespace per id space, because a moc's sections are separate id tables: a part and a
	// drawable sharing a name collide nowhere, so disambiguating across the two would rename an object
	// for no reason.
	private val parameterSpace = Moc3IdSpace(ExportEntityCategory.Parameter, plan.parameters.map { parameter -> parameter.id.raw }, noticeSink)
	private val partSpace = Moc3IdSpace(ExportEntityCategory.Part, plan.parts.map { part -> part.id.raw }, noticeSink)
	private val deformerSpace = Moc3IdSpace(ExportEntityCategory.Deformer, plan.deformers.map { deformer -> deformer.id.raw }, noticeSink)
	private val drawableSpace = Moc3IdSpace(ExportEntityCategory.Drawable, plan.drawables.map { drawable -> drawable.id.raw }, noticeSink)

	// A glue with no authored id gets one synthesized at lowering time, so only the authored ones can
	// be claimed here.  That is enough: a shortened id is at least ID_STRIDE - 4 bytes long (dropping
	// one character sheds at most 4 UTF-8 bytes), and the synthesized "Glue_a_b_" form is far shorter
	// than that, so the two can never name the same string.
	private val glueSpace = Moc3IdSpace(ExportEntityCategory.Glue, glues.mapNotNull { glue -> glue.id }, noticeSink)

	/**
	 * The id a parameter is written under.
	 *
	 * @param ParameterId id The parameter's model id.
	 * @return String The id to write.
	 */
	fun parameterId(id: ParameterId): String = parameterSpace.writtenId(id.raw)

	/**
	 * The id a part is written under.
	 *
	 * @param PartId id The part's model id.
	 * @return String The id to write.
	 */
	fun partId(id: PartId): String = partSpace.writtenId(id.raw)

	/**
	 * The id a deformer is written under.
	 *
	 * @param DeformerId id The deformer's model id.
	 * @return String The id to write.
	 */
	fun deformerId(id: DeformerId): String = deformerSpace.writtenId(id.raw)

	/**
	 * The id a drawable is written under.
	 *
	 * @param DrawableId id The drawable's model id.
	 * @return String The id to write.
	 */
	fun drawableId(id: DrawableId): String = drawableSpace.writtenId(id.raw)

	/**
	 * The id a glue is written under.
	 *
	 * Takes a plain [String] because a glue's id is one: nothing references a glue, so the model carries
	 * no typed id for it, and the caller has already resolved the synthesized fallback for a glue that
	 * was authored without one.
	 *
	 * @param String id The glue's id, authored or synthesized.
	 * @return String The id to write.
	 */
	fun glueId(id: String): String = glueSpace.writtenId(id)

	/**
	 * What every object asked about so far was written as, frozen for the sidecars to read.
	 *
	 * Called after the lowering, when each producer has asked once per record it wrote - so the result
	 * covers exactly the objects the file contains.
	 *
	 * @return Moc3WrittenIds The model-id → written-id mapping, by space.
	 */
	fun writtenIds(): Moc3WrittenIds =
		Moc3WrittenIds(
			parameters = parameterSpace.writtenIds(),
			parts = partSpace.writtenIds(),
			deformers = deformerSpace.writtenIds(),
			drawables = drawableSpace.writtenIds(),
		)
}

/**
 * What an export actually wrote each object's id as.
 *
 * The moc is not the only file in a bake: the `cdi3.json` beside it names parameters, parts, and art
 * meshes BY ID to carry their display names, and the runtime joins the two on that string.  So a
 * sidecar built from the model's own ids silently unpairs itself from the moc for every id the record
 * width forced short - the name, the parameter's group placement, and its combined-parameter pairing
 * all address an object the moc does not contain under that name.  Handing this back with the document
 * is what lets the family agree with itself.
 *
 * An id the export never wrote (a dropped drawable) maps to itself, which is the only honest answer:
 * there is no written form to report.  Whether an object was written at all is a separate question, and
 * one a sidecar has to ask before naming it - see [wrotePart] / [wroteDrawable].
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.4</a>
 */
class Moc3WrittenIds internal constructor(
	private val parameters: Map<String, String>,
	private val parts: Map<String, String>,
	private val deformers: Map<String, String>,
	private val drawables: Map<String, String>,
) {
	/**
	 * Whether the moc contains a record for [id].
	 *
	 * A part in a sketch subtree is left out of the bake entirely, so a sidecar entry for it names
	 * nothing the file contains.
	 *
	 * @param PartId id The part's model id.
	 * @return Boolean True when the export wrote it.
	 */
	fun wrotePart(id: PartId): Boolean = id.raw in parts

	/**
	 * Whether the moc contains a record for [id].
	 *
	 * A drawable can be dropped for several reasons (a sketch subtree, a missing mesh, an uninvertible
	 * deformer chain), and a dropped one holds no claim on its id - so an over-long id can be shortened
	 * onto exactly that string.  A sidecar that named the dropped object anyway would then carry two
	 * entries under one id, and a join on that string would pick between them arbitrarily.
	 *
	 * @param DrawableId id The drawable's model id.
	 * @return Boolean True when the export wrote it.
	 */
	fun wroteDrawable(id: DrawableId): Boolean = id.raw in drawables

	/**
	 * The id [id] was written under.
	 *
	 * @param ParameterId id The parameter's model id.
	 * @return String The written id.
	 */
	fun parameterId(id: ParameterId): String = parameters[id.raw] ?: id.raw

	/**
	 * The id [id] was written under.
	 *
	 * @param PartId id The part's model id.
	 * @return String The written id.
	 */
	fun partId(id: PartId): String = parts[id.raw] ?: id.raw

	/**
	 * The id [id] was written under.
	 *
	 * Carried for completeness rather than for a reader that exists today: a moc names its deformers
	 * and nothing else does, since cdi3 has no deformer entries.
	 *
	 * @param DeformerId id The deformer's model id.
	 * @return String The written id.
	 */
	fun deformerId(id: DeformerId): String = deformers[id.raw] ?: id.raw

	/**
	 * The id [id] was written under.
	 *
	 * @param DrawableId id The drawable's model id.
	 * @return String The written id.
	 */
	fun drawableId(id: DrawableId): String = drawables[id.raw] ?: id.raw
}

/**
 * One moc id table: which names are spoken for, and what each model id ended up written as.
 *
 * @param List<String> modelIds Every id the rig gives this space, claimed if it fits.
 * @property ExportEntityCategory category   The entity category, for the notices it raises.
 * @property Moc3ExportNotices noticeSink Appended to when an id has to be shortened.
 */
private class Moc3IdSpace(
	private val category: ExportEntityCategory,
	modelIds: List<String>,
	private val noticeSink: Moc3ExportNotices,
) {
	// Only the ids that FIT are claimed: an over-long one is not written under its own name, so
	// reserving it would block a shortened id from a string the file never contains.
	private val claimedIds: MutableSet<String> = modelIds.filterTo(HashSet()) { modelId -> fitsIdRecord(modelId) }

	// Memoized so an object asked about twice gets one answer and one notice.  The deformer lowering
	// asks from each of its two type branches, and a second unmemoized call would "collide" the id
	// with the claim its own first call made.
	private val writtenIdByModelId = HashMap<String, String>()

	/**
	 * The id [modelId] is written under, shortened and disambiguated if it cannot be written as-is.
	 *
	 * @param String modelId The id the model carries.
	 * @return String The id to write.
	 */
	fun writtenId(modelId: String): String = writtenIdByModelId.getOrPut(modelId) { claim(modelId) }

	/**
	 * A snapshot of what this space has written so far.
	 *
	 * @return Map<String, String> Model id → written id.
	 */
	fun writtenIds(): Map<String, String> = writtenIdByModelId.toMap()

	/**
	 * Resolves and claims the written form of [modelId], reporting whatever had to be cut.
	 *
	 * @param String modelId The id the model carries.
	 * @return String The id to write.
	 */
	private fun claim(modelId: String): String {
		if (fitsIdRecord(modelId)) {
			claimedIds.add(modelId)
			return modelId
		}
		val truncated = fittedToIdRecord(modelId)
		var written = truncated
		var disambiguator = 2
		// The suffix has to be part of what gets fitted, not appended after it, or disambiguating would
		// push the id back over the record width it was just trimmed to.
		while (!claimedIds.add(written)) {
			written = fittedToIdRecord(modelId, "-$disambiguator")
			disambiguator++
		}
		noticeSink.unsupported(
			category,
			modelId,
			if (written == truncated) {
				ExportNoticeReason.IdTruncated(Sections.ID_STRIDE, written)
			} else {
				ExportNoticeReason.IdTruncatedAndDisambiguated(Sections.ID_STRIDE, written)
			},
		)
		return written
	}
}

/**
 * Whether [id] can be written into a moc id record as-is.
 *
 * @param String id The id to measure.
 * @return Boolean True when its UTF-8 form leaves room for the terminator.
 */
private fun fitsIdRecord(id: String): Boolean = id.encodeToByteArray().size < Sections.ID_STRIDE

/**
 * The longest leading run of [id] that fits a moc id record with [suffix] appended.
 *
 * @param String id     The id to shorten.
 * @param String suffix A disambiguating suffix to keep room for; empty for a plain truncation.
 * @return String The shortened id, suffix included.
 */
private fun fittedToIdRecord(
	id: String,
	suffix: String = "",
): String {
	val suffixBytes = suffix.encodeToByteArray().size
	// Trimmed by CHARACTER so the result stays valid UTF-8; cutting at a byte offset could land
	// mid-sequence and write a broken code point into the record.
	var fitted = id
	while (fitted.isNotEmpty() && fitted.encodeToByteArray().size + suffixBytes >= Sections.ID_STRIDE) {
		fitted = fitted.dropLast(1)
	}
	// A Kotlin Char is a UTF-16 code unit, so an astral character (emoji, rarer CJK) is TWO of them:
	// trimming can leave the leading half behind, which encodes as a replacement byte rather than as
	// the character it was part of.  Dropping it only shortens the result, so it cannot re-overflow.
	if (fitted.isNotEmpty() && fitted.last().isHighSurrogate()) {
		fitted = fitted.dropLast(1)
	}
	return fitted + suffix
}