package org.umamo.interop

import org.umamo.runtime.model.RuntimeFeature

/**
 * One advisory finding from an export, CMO3 or MOC3.
 *
 * An export ALWAYS writes; notices only tell the user what the written file does not carry or what
 * a Cubism-side edit could do to it.  Nothing is ever silently dropped: every edit a lowering cannot
 * yet (or can never) express in the target format surfaces as an [UnsupportedChange], and every
 * feature the chosen target version predates surfaces as a [FeatureStripped].
 */
sealed interface ExportNotice {
	/**
	 * An edit the export lowering did not persist into the written file.
	 *
	 * @property ExportEntityCategory category The kind of thing the finding is about.
	 * @property String?              subject  The affected entity's id, or null for a document-level
	 *                                         finding, whose [reason] names the field itself.
	 * @property ExportNoticeReason   reason   Why the lowering could not carry the edit.
	 */
	data class UnsupportedChange(
		val category: ExportEntityCategory,
		val subject: String?,
		val reason: ExportNoticeReason,
	) : ExportNotice

	/**
	 * Drawables whose base geometry or UVs diverged from the imported geometry/UV weld.  The export
	 * writes them as authored (the official editor renders the same displaced result Umamo shows),
	 * but Cubism couples geometry and UVs at the neutral form, so its own mesh-edit / re-atlas
	 * operations may re-derive and corrupt the mapping.
	 *
	 * @property List drawableNames The affected drawables' display names.
	 */
	data class WeldDivergence(val drawableNames: List<String>) : ExportNotice

	/**
	 * A feature the export target's runtime cannot load, removed from the written file.
	 *
	 * Distinct from [UnsupportedChange], which reports what the lowering could not express: this one
	 * reports what the lowering deliberately took OUT because the chosen version predates it.  The
	 * document itself keeps the feature - the strip runs on a copy - so re-exporting at a higher
	 * version brings it back.
	 *
	 * @property RuntimeFeature feature  The stripped feature.
	 * @property List           subjects The affected entities' display names, in document order.
	 */
	data class FeatureStripped(
		val feature: RuntimeFeature,
		val subjects: List<String>,
	) : ExportNotice

	/**
	 * The document has no source artwork, so the CMO3 was built around a fabricated one.
	 *
	 * A CMO3 is organised around the layered art it was imported from; a MOC3 carries only packed
	 * atlas pages, so the export reconstructs a stand-in source document by slicing each drawable's
	 * patch back out of the atlas.  The result opens and renders in the official Cubism Editor, and
	 * switches between its layered-art and texture-atlas display modes - but every layer in it is a
	 * slice of a baked page, not the artwork the rig was drawn from.  What that costs is editable
	 * art: the layers cannot be redrawn upstream and refreshed, and their quality is capped by the
	 * page they were cut from.  Reconciling the ORIGINAL layered art (PSD/CLIP/KRA) into the
	 * document is what replaces them with the real thing.
	 *
	 * @property Int pageCount The number of atlas pages the stand-in source was built from.
	 */
	data class MissingSourceArt(val pageCount: Int) : ExportNotice
}

/**
 * The kind of thing an [ExportNotice.UnsupportedChange] is about, naming the panel a rigger would
 * look in to find it.
 *
 * An enum rather than the free text it replaces: the vocabulary was previously documented in a
 * comment and enforced by nothing, so a typo produced a category no reader recognized, and the label
 * could not be localized.  [Keyform] is the odd member - a keyform is not an entity in its own right -
 * but the notice's subject there is the owning drawable or part, so the rendered line still reads as
 * a place to look.
 */
enum class ExportEntityCategory {
	Parameter,
	ParameterGroup,
	Part,
	Deformer,
	Drawable,
	Glue,
	Document,
	Keyform,
}

/**
 * The format one export wrote, naming which file family a report's findings are about.
 *
 * Deliberately narrower than [org.umamo.format.FileKind], which spans every family Umamo reads
 * (art sources, sidecars, raster pages) - only the ones an export path writes belong here, so a
 * `when` over an [ExportReport]'s format stays exhaustive without an `else` that would silently
 * swallow a new export target.
 */
enum class ExportFormat {
	Cmo3,
	Moc3,
}

/**
 * The advisory outcome of one export: what the written file does not carry.
 *
 * The notice kinds are format-neutral because the contract is: an export ALWAYS writes, and notices
 * only say what the written file does not carry.  That holds identically for CMO3 and MOC3, so both
 * report through this rather than through parallel types the UI would have to handle twice.  The
 * [format] is what keeps the report attributable: a reader (the alert's header, a log line) has to
 * name the file family the findings are about, and no notice carries that on its own.
 *
 * @property ExportFormat format  The format the export wrote.
 * @property List         notices The findings, empty for a fully-lowered export.
 */
data class ExportReport(val format: ExportFormat, val notices: List<ExportNotice>) {
	/** True when the export lowered everything with nothing to warn about. */
	val isEmpty: Boolean get() = notices.isEmpty()
}