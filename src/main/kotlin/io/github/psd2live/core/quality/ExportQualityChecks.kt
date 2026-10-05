package io.github.psd2live.core.quality

import io.github.psd2live.i18n.tr
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.ExportEntityCategory
import org.umamo.runtime.model.PuppetModel

data class ExportIdentityInput(val format: String, val expected: PuppetModel, val actual: PuppetModel,
                               val notices: List<ExportNotice> = emptyList())

/** Format identity is checked separately from visual/model measurements. */
object ExportIdentityCheck : QualityCheck<ExportIdentityInput> {
    override fun inspect(context: ExportIdentityInput): QualityCheckResult {
        val findings = mutableListOf<QualityFinding>()
        fun check(category: ExportEntityCategory, sourceIds: Set<String>, actual: Set<String>) {
            val relevant = context.notices.filterIsInstance<ExportNotice.UnsupportedChange>().filter { it.category == category }
            val dropped = relevant.filter { category == ExportEntityCategory.Drawable && it.reason in setOf(
                ExportNoticeReason.HiddenPartOmittedByExportOption, ExportNoticeReason.HiddenDrawableOmittedByExportOption,
                ExportNoticeReason.SketchPartIsNotRuntimeContent, ExportNoticeReason.DrawableHasNoMesh,
                ExportNoticeReason.UnkeyedDrawableUnderDeformerHasNoParentGeometry) }.mapNotNull { it.subject }.toSet()
            val renamed = relevant.mapNotNull { notice -> notice.subject?.let { subject -> when (val reason = notice.reason) {
                is ExportNoticeReason.IdTruncated -> subject to reason.writtenId
                is ExportNoticeReason.IdTruncatedAndDisambiguated -> subject to reason.writtenId
                else -> null
            } } }.toMap()
            val expected = (sourceIds - dropped).map { renamed[it] ?: it }.toSet()
            if (expected != actual) findings += QualityFinding(QualityRule.EXPORT_IDENTITY_MISMATCH,
                "export:${context.format}", QualityEvidence.metrics(emptyMap(), mapOf("kind" to category.name,
                    "missing" to (expected - actual).sorted().joinToString(","), "unexpected" to (actual - expected).sorted().joinToString(",")),
                    tr("error.rigShape", context.format, category.name, expected - actual, actual - expected)))
        }
        check(ExportEntityCategory.Parameter, context.expected.parameters.map { it.id.raw }.toSet(), context.actual.parameters.map { it.id.raw }.toSet())
        check(ExportEntityCategory.Deformer, context.expected.deformers.map { it.id.raw }.toSet(), context.actual.deformers.map { it.id.raw }.toSet())
        check(ExportEntityCategory.Drawable, context.expected.drawables.map { it.id.raw }.toSet(), context.actual.drawables.map { it.id.raw }.toSet())
        return QualityCheckResult("export.identity:${context.format}", "Round-trip object identity sets", findings)
    }
}

data class ExportConversionInput(val format: String, val notices: List<ExportNotice>)
object ExportConversionCheck : QualityCheck<ExportConversionInput> {
    override fun inspect(context: ExportConversionInput): QualityCheckResult = QualityCheckResult(
        "export.notices:${context.format}", "Target-format lowering notices", context.notices.map { notice ->
            val rule = when {
                notice is ExportNotice.MissingSourceArt -> QualityRule.EXPORT_SOURCE_ART_REBUILT
                notice is ExportNotice.UnsupportedChange && notice.reason in setOf(ExportNoticeReason.HiddenPartOmittedByExportOption,
                    ExportNoticeReason.HiddenDrawableOmittedByExportOption, ExportNoticeReason.SketchPartIsNotRuntimeContent) -> QualityRule.EXPORT_INTENTIONAL_OMISSION
                else -> QualityRule.EXPORT_CONVERSION_NOTICE
            }
            val detail = if (notice is ExportNotice.MissingSourceArt) tr("warning.sourceArtRebuilt", context.format, notice.pageCount)
                else tr("warning.exportNotice", context.format, notice)
            QualityFinding.message(rule, "export:${context.format}", detail)
        })
}
