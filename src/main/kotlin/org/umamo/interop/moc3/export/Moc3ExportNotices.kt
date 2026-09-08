package org.umamo.interop.moc3.export

import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportFormat
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.ExportReport
import org.umamo.runtime.model.DrawableId

/**
 * The advisory findings a lowering accumulates, in the order a rigger will read them.
 *
 * An export ALWAYS writes; anything it cannot express lands here instead of being silently dropped.
 * The order is user-visible - the editor logs the report as a list and shows it in the export panel -
 * so the two positional decisions are stated rather than inherited from whatever order the producers
 * happen to run in: the version strip's own notices come FIRST (they describe the rig the rest of the
 * lowering then worked on), and the dropped-drawable flush comes LAST.
 *
 * Mutable on purpose, and passed to each producer explicitly rather than hidden inside the export
 * context, so a producer's signature says whether it can report.
 */
internal class Moc3ExportNotices {
	private val collected = ArrayList<ExportNotice>()

	/** Everything reported so far, in report order. */
	val notices: List<ExportNotice> get() = collected

	/**
	 * The report to hand back with the document.
	 *
	 * @return ExportReport The advisory findings; empty for a fully-lowered export.
	 */
	fun report(): ExportReport = ExportReport(ExportFormat.Moc3, collected.toList())

	/**
	 * Adopts the version strip's notices, which describe losses taken before any lowering ran.
	 *
	 * @param List<ExportNotice> stripNotices The strip's findings.
	 */
	fun addStripNotices(stripNotices: List<ExportNotice>) {
		collected.addAll(stripNotices)
	}

	/**
	 * Records a notice for something the lowering could not express.
	 *
	 * @param ExportEntityCategory category The entity category.
	 * @param String               subject  The entity's id.
	 * @param ExportNoticeReason   reason   Why it was not lowered.
	 */
	fun unsupported(category: ExportEntityCategory, subject: String, reason: ExportNoticeReason) {
		collected.add(ExportNotice.UnsupportedChange(category, subject, reason))
	}

	/**
	 * Reports every channel a bundle had to drop to its static.
	 *
	 * @param ExportEntityCategory category The entity category.
	 * @param String               subject  The entity's id.
	 * @param Moc3ObjectKeyforms?  keyforms The lowered keyforms, or null when unrepresentable.
	 */
	fun reportDemotions(
		category: ExportEntityCategory,
		subject: String,
		keyforms: Moc3ObjectKeyforms?,
	) {
		for (channel in keyforms?.demotedChannels.orEmpty()) {
			unsupported(category, subject, ExportNoticeReason.ChannelDemotedToStatic(channel))
		}
	}

	/**
	 * Reports every drawable the eligibility pass left out, with the reason it gave.
	 *
	 * Called LAST by the orchestrator, so drop notices trail the per-object findings rather than
	 * leading them - the eligibility pass runs first, but its findings read better after the rest.
	 *
	 * @param Map<DrawableId, ExportNoticeReason> droppedDrawables Each omitted drawable and why.
	 */
	fun reportDroppedDrawables(droppedDrawables: Map<DrawableId, ExportNoticeReason>) {
		for ((drawableId, reason) in droppedDrawables) {
			unsupported(ExportEntityCategory.Drawable, drawableId.raw, reason)
		}
	}
}