package org.umamo.interop.moc3.import

import org.umamo.format.moc3.moc.ConstantFlag
import org.umamo.format.moc3.model.BlendShapeTarget
import org.umamo.interop.alphaBlendOfPacked
import org.umamo.interop.colorBlendOfPacked
import org.umamo.runtime.keyform.fanOutMesh
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.MeshForm

/**
 * Imports every art mesh, in file order.
 *
 * File order is preserved here and re-sorted into panel order only once the render tree has been read,
 * because a MOC3 addresses drawables positionally - masks, glues, and render-order leaves are all file
 * indices into this list.
 *
 * The rest mesh comes from the DEFAULT-POSE cell of the drawable's keyform grid, and every cell then
 * re-expresses as a delta against it.  Which cell is chosen changes `mesh.positions` and every delta
 * while leaving evaluated geometry bit-identical - the multilinear blend is base-independent - so no
 * evaluation oracle can see this go wrong.  `Moc3Cmo3ParityTest` (rest vertices against the CMO3 twin)
 * and the export round trip's MESH_POSITIONS are what actually pin it.
 *
 * @param Moc3ImportContext context The import's derived state.
 * @return List<Drawable> The runtime drawables, in file order.
 */
internal fun importDrawables(context: Moc3ImportContext): List<Drawable> =
	context.mocDocument.artMeshes.mapIndexed { drawableIndex, source ->
		val space = context.pointSpaceOf(source.parentDeformerIndex)
		val binding = context.bindingOf(source.keyformBindingIndex)
		// MOC3 keyforms are absolute; the default-pose cell serves as the rest mesh and every cell
		// re-expresses as a delta against it (the multilinear blend is base-independent, so evaluated
		// geometry is unaffected by the choice).
		val basePositions =
			source.keyforms.getOrNull(defaultCellIndexOf(context, binding))?.let { keyform ->
				context.convertPoints(space, keyform.vertexPositions)
			}
		val mesh =
			basePositions?.let { positions ->
				DrawableMesh(
					positions = positions,
					uvs = source.vertexUvs.copyOf(),
					// MOC3 §5.6 INDEX_DATA is u16; widen unsigned so meshes past 32767 vertices survive.
					indices = IntArray(source.triangleIndices.size) { indexIndex -> source.triangleIndices[indexIndex].toInt() and 0xFFFF },
				)
			}
		// One bundled grid, then split into per-vertex deltas and the render channels.
		val fannedMesh =
			gridOf(context, binding) { gridIndex ->
				source.keyforms.getOrNull(gridIndex)?.let { keyform ->
					MeshForm(
						positionDeltas =
							deltaVsBase(
								basePositions,
								context.convertPoints(space, keyform.vertexPositions),
							),
						drawOrder = keyform.drawOrder,
						opacity = keyform.opacity,
						// MOC3 color-table rows 108-113: the 5.3 per-art-mesh multiply/screen color; null
						// (pre-5.3, no color table) falls back to the tint identities.
						multiplyColor = colorRgbOf(keyform.multiplyColor) ?: ColorRgb.MultiplyIdentity,
						screenColor = colorRgbOf(keyform.screenColor) ?: ColorRgb.ScreenIdentity,
					)
				}
			}?.fanOutMesh()
		val drawable =
			Drawable(
				// Through the context's table, not from source.id: a file carrying the same id twice
				// resolves the repeat to a synthesized id there, and building it here instead would
				// re-merge the two - masks, part membership, and keyforms landing on one drawable.
				id = context.drawableIdsByFileIndex[drawableIndex],
				// The MOC3 itself carries no drawable names; only the cdi3 Meshes extension does, so a
				// file the official editor wrote falls back to the format id.
				name = context.drawableNameById[source.id] ?: source.id,
				parentDeformerId = context.deformerIds.getOrNull(source.parentDeformerIndex),
				// MOC3 v6 §5.6 s153: a nonzero packed extended blend overrides the legacy 2-bit
				// constant-flags field (which then only carries the old-runtime approximation).
				blendMode =
					if (source.extendedBlend != 0) {
						colorBlendOfPacked(source.extendedBlend)
					} else {
						blendModeOf(source.constantFlags)
					},
				alphaBlendMode = alphaBlendOfPacked(source.extendedBlend),
				// MOC3 §5.6 MASK_INDEX_DATA: mask sources are drawable file indices.
				maskedBy =
					source.maskDrawableIndices.toList()
						.mapNotNull { maskIndex -> context.drawableIdsByFileIndex.getOrNull(maskIndex) },
				invertMask = source.constantFlags and ConstantFlag.IS_INVERTED_MASK != 0,
				// MOC3 §5.5: constant-flags bit 2 is IS_DOUBLE_SIDED; culling is its inverse.
				culling = source.constantFlags and ConstantFlag.IS_DOUBLE_SIDED == 0,
				// MOC3 §5.6 s37: the editor's eye toggle.  A bake normally deletes what is hidden, so
				// this is true for almost every imported drawable - but a file exported with hidden
				// meshes kept carries the flag, and Umamo's own export always does.
				isVisible = source.isVisible,
				// Lock IS editor-only authoring state the bake drops, so everything imports unlocked.
				isSelectable = true,
				// MOC3 §5.6 s41: the atlas page this mesh samples, so a detached model can still say.
				texturePage = source.textureIndex,
				mesh = mesh,
				geometryGrid = fannedMesh?.geometry,
				channelGrids = fannedMesh?.channels ?: ChannelGrids.Empty,
			)
		val meshRecords = context.blendRecordsByTarget[BlendShapeTarget.ART_MESH to drawableIndex].orEmpty()
		if (meshRecords.isEmpty()) {
			drawable
		} else {
			drawable.copy(blendShapes = meshBlendShapesOf(context, drawable, space, meshRecords))
		}
	}

/**
 * Maps a moc drawable's constant-flag bitmask to the runtime [BlendMode].
 *
 * @param Int constantFlags The [ConstantFlag] bitmask (MOC3 §5.5).
 * @return BlendMode The runtime blend mode (defaults to Normal).
 */
private fun blendModeOf(constantFlags: Int): BlendMode =
	when {
		constantFlags and ConstantFlag.BLEND_ADDITIVE != 0 -> BlendMode.AdditivePremultiplied
		constantFlags and ConstantFlag.BLEND_MULTIPLICATIVE != 0 -> BlendMode.MultiplyPremultiplied
		else -> BlendMode.Normal
	}

/**
 * Per-vertex deltas of [positions] vs [base] (`positions − base`), or a copy of positions when
 * there is no size-matching base, so the form is kept absolute rather than dropped (matching
 * `Cmo3Import`'s convention).
 *
 * @param FloatArray? base      The rest-mesh positions.
 * @param FloatArray  positions The keyform's absolute positions.
 * @return FloatArray The deltas, or a copy of positions.
 */
private fun deltaVsBase(
	base: FloatArray?,
	positions: FloatArray,
): FloatArray {
	if (base == null || base.size != positions.size) {
		return positions.copyOf()
	}
	return FloatArray(positions.size) { coordIndex -> positions[coordIndex] - base[coordIndex] }
}