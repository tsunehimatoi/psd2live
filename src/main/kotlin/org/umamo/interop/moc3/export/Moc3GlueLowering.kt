package org.umamo.interop.moc3.export

import org.umamo.format.moc3.model.GlueVertexPair
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.PuppetModel
import org.umamo.format.moc3.model.Glue as MocGlue

/**
 * Lowers every glue whose two meshes both survived into its MOC3 record.
 *
 * Interns LAST of the four object kinds, and a glue naming a dropped mesh returns before it would have
 * interned - so restructuring the filter to intern first would shift every binding index after it.
 *
 * @param PuppetModel       puppet     The stripped rig, for its glue list.
 * @param Moc3ExportContext context    The export's derived state.
 * @param Moc3KeyformPool   pool       Interned into: every surviving glue claims a binding index here.
 * @param Moc3ExportIds     ids        Claimed from: each surviving glue's written id.
 * @param Moc3ExportNotices noticeSink Appended to: demotions and unknown mesh references.
 * @return List<MocGlue> The records, in model order.
 */
internal fun lowerGlues(
	puppet: PuppetModel,
	context: Moc3ExportContext,
	pool: Moc3KeyformPool,
	ids: Moc3ExportIds,
	noticeSink: Moc3ExportNotices,
): List<MocGlue> {
	val plan = context.plan
	return puppet.glues.mapIndexedNotNull { glueIndex, glue ->
		val meshA = plan.drawableIndex(glue.meshA)
		val meshB = plan.drawableIndex(glue.meshB)
		if (meshA < 0 || meshB < 0) {
			noticeSink.unsupported(
				ExportEntityCategory.Glue,
				glue.id ?: "Glue$glueIndex",
				ExportNoticeReason.GlueNamesAnUnknownDrawable,
			)
			return@mapIndexedNotNull null
		}
		val keyforms =
			lowerObjectKeyforms(
				pool,
				null as KeyformGrid<Unit>?,
				UnitInterpolator,
				glue.channelGrids.onlyChannels(FormChannel.GLUE_INTENSITY),
				mapOf(FormChannel.GLUE_INTENSITY to ChannelValue.Scalar(glue.intensity)),
				requireGeometry = false,
			)
		// ONE fallback, computed once: a notice naming a subject the file does not contain sends the
		// reader searching the export for an object that was never written under that name.  The
		// drop notice above is the exception on purpose - a dropped glue has no written id to cite,
		// so its ordinal is the only handle left.
		val glueId = glue.id ?: "Glue_${meshA}_${meshB}_"
		noticeSink.reportDemotions(ExportEntityCategory.Glue, glueId, keyforms)
		val bundle = keyforms?.bundle
		val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
		MocGlue(
			id = ids.glueId(glueId),
			meshAIndex = meshA,
			meshBIndex = meshB,
			keyformBindingIndex = keyforms?.bindingIndex ?: 0,
			pairs =
				glue.pairs.map { pair ->
					GlueVertexPair(pair.indexA, pair.indexB, pair.weightA, pair.weightB)
				},
			intensityKeyforms =
				FloatArray(cellCount) { cellIndex ->
					bundle?.let { scalarOf(it, cellIndex, FormChannel.GLUE_INTENSITY, glue.intensity) }
						?: glue.intensity
				},
		)
	}
}