package org.umamo.interop.moc3.export

import org.umamo.interop.ExportEntityCategory
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.PartId
import org.umamo.format.moc3.model.Part as MocPart

/**
 * The lowered parts, plus the keyform bundles the offscreen lowering has to reuse.
 *
 * @property List<MocPart> records          The part records, in file order.
 * @property Map           keyformsByPartId Each part's bundle, for the offscreen lowering.
 */
internal class Moc3LoweredParts(
	val records: List<MocPart>,
	val keyformsByPartId: Map<PartId, Moc3ObjectKeyforms?>,
)

/**
 * Lowers every exportable part into a MOC3 part record.
 *
 * An offscreen's keyforms ride its OWNER PART'S grid - Σ of the owner grid sizes is CountInfo 36 - so
 * the part's bundle is built once here and handed back for the offscreen lowering to read.  Building it
 * twice would let the two disagree about the grid an offscreen is indexed against; RETURNING it rather
 * than writing it into shared state is what makes lowering offscreens first a compile error instead of
 * a silently empty map.
 *
 * @param Moc3ExportContext context    The export's derived state.
 * @param Moc3KeyformPool   pool       Interned into: every part claims a binding index here.
 * @param Moc3ExportIds     ids        Claimed from: each part's written id.
 * @param Moc3ExportNotices noticeSink Appended to: channel demotions.
 * @return Moc3LoweredParts The records and their bundles.
 */
internal fun lowerParts(
	context: Moc3ExportContext,
	pool: Moc3KeyformPool,
	ids: Moc3ExportIds,
	noticeSink: Moc3ExportNotices,
): Moc3LoweredParts {
	val plan = context.plan
	val partKeyformsById = HashMap<PartId, Moc3ObjectKeyforms?>()
	val records =
		plan.parts.map { part ->
			// An isolated part's composite channels ride the same cells as its draw order, so they are
			// bundled together; a non-isolated part has no composite to key.
			val compositeChannels =
				if (context.offscreensEnabled && part.isIsolated) {
					renderChannels(context.colorsEnabled)
				} else {
					emptyArray()
				}
			val compositeStatics =
				if (context.offscreensEnabled && part.isIsolated) {
					renderStatics(
						part.composite.opacity,
						part.composite.multiplyColor,
						part.composite.screenColor,
						context.colorsEnabled,
					)
				} else {
					emptyMap()
				}
			val keyforms =
				lowerObjectKeyforms(
					pool,
					null as KeyformGrid<Unit>?,
					UnitInterpolator,
					part.channelGrids.onlyChannels(*(compositeChannels + arrayOf(FormChannel.DRAW_ORDER))),
					compositeStatics + mapOf(FormChannel.DRAW_ORDER to ChannelValue.Scalar(part.drawOrder.toFloat())),
					requireGeometry = false,
				)
			noticeSink.reportDemotions(ExportEntityCategory.Part, part.id.raw, keyforms)
			partKeyformsById[part.id] = keyforms
			val bundle = keyforms?.bundle
			val cellCount = bundle?.cells?.size ?: 0
			MocPart(
				id = ids.partId(part.id),
				parentPartIndex = plan.partIndex(context.partParentById[part.id]),
				// A static part points at binding 0, which is what the import's `> 0` static test expects.
				keyformBindingIndex = if (cellCount > 1) keyforms!!.bindingIndex else 0,
				drawOrderKeyforms =
					FloatArray(maxOf(cellCount, 1)) { cellIndex ->
						bundle?.let { scalarOf(it, cellIndex, FormChannel.DRAW_ORDER, part.drawOrder.toFloat()) }
							?: part.drawOrder.toFloat()
					},
				isVisible = part.isVisible,
			)
		}
	return Moc3LoweredParts(records, partKeyformsById)
}