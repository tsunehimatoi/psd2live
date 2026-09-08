package org.umamo.interop.moc3.export

import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.model.ArtMesh
import org.umamo.format.moc3.model.ArtMeshKeyform
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.legacyBlendFlagOf
import org.umamo.interop.moc3.convertPointsToMoc
import org.umamo.interop.packedBlendOf
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.MeshDeltaForm

/**
 * Lowers every exportable drawable into its MOC3 art-mesh record.
 *
 * @param Moc3ExportContext context    The export's derived state.
 * @param Moc3KeyformPool   pool       Interned into: every art mesh claims a binding index here.
 * @param Moc3ExportIds     ids        Claimed from: each drawable's written id.
 * @param Moc3ExportNotices noticeSink Appended to: demotions and unresolvable masks.
 * @return List<ArtMesh> The records, in plan order.
 */
internal fun lowerArtMeshes(
	context: Moc3ExportContext,
	pool: Moc3KeyformPool,
	ids: Moc3ExportIds,
	noticeSink: Moc3ExportNotices,
): List<ArtMesh> {
	val plan = context.plan
	val canvasToParentSpace = context.canvasToParentSpace
	return plan.drawables.map { drawable ->
		val mesh = drawable.mesh!!
		val space = context.spaceOfParent(drawable.parentDeformerId)
		// An unkeyed drawable under a deformer stores its rest mesh in CANVAS space, so the base
		// every keyform is written relative to has to be inverted through the chain first.  A keyed
		// one is already parent-local (the import's rest-mesh pass guarantees base + delta is the
		// absolute parent-space position), so the seam is asked only where it is needed.
		val basePositions =
			if (drawable.geometryGrid == null && drawable.parentDeformerId != null) {
				canvasToParentSpace?.invoke(drawable.id, mesh.positions)?.also { converted ->
					if (converted.size != mesh.positions.size) {
						noticeSink.unsupported(
							ExportEntityCategory.Drawable,
							drawable.id.raw,
							ExportNoticeReason.RestMeshConversionSizeMismatch(
								converted.size,
								mesh.positions.size,
							),
						)
					}
				}?.takeIf { converted -> converted.size == mesh.positions.size } ?: mesh.positions
			} else {
				mesh.positions
			}
		val keyforms =
			lowerObjectKeyforms(
				pool,
				drawable.geometryGrid,
				MeshDeltaInterpolator,
				drawable.channelGrids.onlyChannels(
					*(renderChannels(context.colorsEnabled) + arrayOf(FormChannel.DRAW_ORDER)),
				),
				renderStatics(drawable.opacity, drawable.multiplyColor, drawable.screenColor, context.colorsEnabled) +
					mapOf(FormChannel.DRAW_ORDER to ChannelValue.Scalar(drawable.drawOrder)),
				requireGeometry = false,
			)
		noticeSink.reportDemotions(ExportEntityCategory.Drawable, drawable.id.raw, keyforms)
		val bundle = keyforms?.bundle
		val cellCount = maxOf(bundle?.cells?.size ?: 0, 1)
		val triangleIndices =
			ShortArray(mesh.indices.size) { index -> mesh.indices[index].toShort() }
		// -1 is the "no atlas page bound" sentinel and a moc has no way to spell it, so the write
		// clamps to page 0.  On a multi-page rig that is the WRONG texture rather than a near miss,
		// which is worth a notice even though the file it produces is structurally valid.
		if (drawable.texturePage < 0) {
			noticeSink.unsupported(
				ExportEntityCategory.Drawable,
				drawable.id.raw,
				ExportNoticeReason.NoAtlasPageBound,
			)
		}
		// A mask naming a drawable this export dropped has no file index to reference.  Filtering it
		// is the only option, but doing so silently would leave the mesh rendering unclipped with
		// nothing in the report to explain why.
		val maskIndices = ArrayList<Int>(drawable.maskedBy.size)
		val unresolvedMasks = ArrayList<String>()
		for (maskId in drawable.maskedBy) {
			val maskIndex = plan.drawableIndex(maskId)
			if (maskIndex >= 0) {
				maskIndices.add(maskIndex)
			} else {
				unresolvedMasks.add(maskId.raw)
			}
		}
		if (unresolvedMasks.isNotEmpty()) {
			noticeSink.unsupported(
				ExportEntityCategory.Drawable,
				drawable.id.raw,
				ExportNoticeReason.ClippingMaskNotInExport(unresolvedMasks.toList()),
			)
		}
		ArtMesh(
			id = ids.drawableId(drawable.id),
			textureIndex = maxOf(drawable.texturePage, 0),
			constantFlags = constantFlagsOf(drawable, context.extendedBlendEnabled),
			// The 5.3 blend surface; below v6 the mode falls back to the legacy constant-flag bits.
			extendedBlend =
				if (context.extendedBlendEnabled) packedBlendOf(drawable.blendMode, drawable.alphaBlendMode) else 0,
			isVisible = drawable.isVisible,
			isEnabled = true,
			parentPartIndex = plan.partIndex(context.partByDrawable[drawable.id]),
			parentDeformerIndex = plan.deformerIndex(drawable.parentDeformerId),
			vertexUvs = mesh.uvs.copyOf(),
			triangleIndices = triangleIndices,
			maskDrawableIndices = maskIndices.toIntArray(),
			keyformBindingIndex = keyforms?.bindingIndex ?: 0,
			keyforms =
				(0 until cellCount).map { cellIndex ->
					// THE load-bearing invariant: base + delta is the absolute parent-space position.
					val deltas =
						(bundle?.cells?.getOrNull(cellIndex)?.geometry as? MeshDeltaForm)
							?.positionDeltas
					val absolute =
						FloatArray(basePositions.size) { coordinate ->
							basePositions[coordinate] + (deltas?.getOrNull(coordinate) ?: 0f)
						}
					ArtMeshKeyform(
						vertexPositions = convertPointsToMoc(space, absolute, context.canvas),
						opacity =
							bundle?.let { scalarOf(it, cellIndex, FormChannel.OPACITY, drawable.opacity) }
								?: drawable.opacity,
						drawOrder =
							bundle?.let { scalarOf(it, cellIndex, FormChannel.DRAW_ORDER, drawable.drawOrder) }
								?: drawable.drawOrder,
						multiplyColor =
							colorOf(bundle, cellIndex, FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, context.colorsEnabled),
						screenColor =
							colorOf(bundle, cellIndex, FormChannel.SCREEN_COLOR, drawable.screenColor, context.colorsEnabled),
					)
				},
		)
	}
}

/**
 * The drawable's constant-flag byte.
 *
 * Note bit 2 is the INVERSE of culling: the flag means "double sided", so a culled drawable clears
 * it.  Getting that backwards silently double-draws every back face.
 *
 * @param Drawable drawable             The drawable.
 * @param Boolean  extendedBlendEnabled Whether the target version carries the 5.3 extended-blend
 *                                      section, which then states the blend mode instead of the
 *                                      legacy bits.
 * @return Int The flag bits.
 */
private fun constantFlagsOf(drawable: Drawable, extendedBlendEnabled: Boolean): Int {
	// On moc 6 the extended-blend section is authoritative and the editor leaves the legacy 2-bit pair
	// CLEAR even for an additive or multiply mesh - writing both would state the mode twice, and the
	// legacy pair cannot express the other sixteen modes anyway.
	var flags = if (extendedBlendEnabled) 0 else legacyBlendFlagOf(drawable.blendMode)
	if (!drawable.culling) {
		flags = flags or ConstantFlag.IS_DOUBLE_SIDED
	}
	if (drawable.invertMask) {
		flags = flags or ConstantFlag.IS_INVERTED_MASK
	}
	return flags
}