package org.umamo.interop.moc3.export

import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.model.Offscreen
import org.umamo.format.moc3.model.OffscreenKeyform
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.exactLegacyBlendFlagOf
import org.umamo.interop.packedBlendOf
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.drawablesByPartSubtree
import org.umamo.runtime.model.flattenedMasks

/**
 * Lowers every isolated part into a MOC3 v6 offscreen record.
 *
 * An offscreen is a part rendered into its own buffer and composited back as one layer, which is what
 * gives a part an opacity and a blend mode of its own.  The MOC3 models it as a separate object list
 * keyed by owner part index rather than as fields on the part, so this walks the parts once and emits a
 * record per [org.umamo.runtime.model.PartGroupMode.Isolated] one, in ascending part-index order (the
 * corpus-confirmed ordering invariant).
 *
 * @param PuppetModel       puppet           The rig being exported.
 * @param Moc3IndexPlan     plan             The file's addressing scheme.
 * @param Map               keyformsByPartId The part bundles the part lowering already built.
 * @param Boolean           colorsEnabled    Whether the target version has color tables.
 * @param Moc3ExportNotices noticeSink       Where anything unrepresentable is reported.
 * @return List<Offscreen> The records, owner-index ascending.
 */
internal fun lowerOffscreens(
	puppet: PuppetModel,
	plan: Moc3IndexPlan,
	keyformsByPartId: Map<PartId, Moc3ObjectKeyforms?>,
	colorsEnabled: Boolean,
	noticeSink: Moc3ExportNotices,
): List<Offscreen> {
	val isolated = plan.parts.filter { part -> part.isIsolated }
	if (isolated.isEmpty()) {
		return emptyList()
	}

	// One index for the whole batch - flattenedMasks' docblock requires it, and a per-composite rebuild
	// would walk the whole part tree once per isolated part.
	val subtrees = puppet.drawablesByPartSubtree()
	return isolated
		.map { part ->
			val composite = part.composite
			// The SHARED mask expansion: resolving part-masks a second way here would let the shipped
			// file clip differently from the viewport that authored it.
			val maskDrawables = flattenedMasks(composite, subtrees)
			val maskIndices =
				maskDrawables
					.map { drawableId -> plan.drawableIndex(drawableId) }
					.filter { index -> index >= 0 }
			if (maskIndices.size < maskDrawables.size) {
				noticeSink.unsupported(
					ExportEntityCategory.Part,
					part.id.raw,
					ExportNoticeReason.OffscreenMaskNotInExport,
				)
			}
			val bundle = keyformsByPartId[part.id]?.bundle
			// Exactly the owner part's grid size: Σ over the owner bindings is CountInfo 36, so a row count
			// that disagrees with the part's own keyform count desynchronizes every later offscreen.
			val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
			Offscreen(
				ownerPartIndex = plan.partIndex(part.id),
				// MOC3 v6 §5.6 s156: the drawable flag byte's shape, reused.  Bit 2 (double-sided) is set on
				// every corpus offscreen - a composited layer is a quad with no back face to cull - and the
				// legacy pair restates the mode when, and only when, it can name it exactly, which is the
				// opposite of what the same version does for a drawable (there the pair stays clear).  Both
				// are what the editor writes.
				constantFlags =
					ConstantFlag.IS_DOUBLE_SIDED or
						exactLegacyBlendFlagOf(composite.blendMode) or
						(if (composite.invertMask) ConstantFlag.IS_INVERTED_MASK else 0),
				// MOC3 v6 §5.6 s157: packed colorMode | (alphaMode shl 8).
				blendMode = packedBlendOf(composite.blendMode, composite.alphaBlendMode),
				maskCount = maskIndices.size,
				keyforms =
					List(cellCount) { cellIndex ->
						OffscreenKeyform(
							opacity =
								bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, composite.opacity) }
									?: composite.opacity,
							multiplyColor =
								colorOf(bundle, cellIndex, FormChannel.MULTIPLY_COLOR, composite.multiplyColor, colorsEnabled),
							screenColor =
								colorOf(bundle, cellIndex, FormChannel.SCREEN_COLOR, composite.screenColor, colorsEnabled),
						)
					},
				maskIndices = maskIndices.toIntArray(),
			)
		}
		.sortedBy { offscreen -> offscreen.ownerPartIndex }
}