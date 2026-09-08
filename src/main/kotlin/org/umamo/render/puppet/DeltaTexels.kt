package org.umamo.render.puppet

import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.keyform.cellCount
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId

/**
 * The linear cell extent of a keyform grid - the product of each axis's key count.
 *
 * This is the delta texture's width: one column per keyform cell, addressed by the cell's linear index
 * (the same index [org.umamo.runtime.eval.WeightedCell.linearIndex] carries, which is how the vertex
 * shader's active corners find their column).
 *
 * A null grid is an UNKEYED drawable and gets a single column, which the texel build fills with zeros -
 * so the shader fetches column 0 at full weight and reads no offset, leaving the mesh at rest.  That is
 * what lets an unkeyed drawable render with no shader, uniform-layout, or RenderDevice change at all.
 *
 * @param KeyformGrid? grid The mesh's keyform grid, or null when the drawable is unkeyed.
 * @return Int The cell count, floored at 1 so an axis-less or absent grid still gets its single rest cell.
 */
internal fun keyformCellCount(grid: KeyformGrid<MeshDeltaForm>?): Int = maxOf(1, grid?.cellCount ?: 1)

/**
 * Builds a mesh's per-keyform-cell vertex deltas as RG texels: row = vertex id, column = cell linear
 * index, RG = (Δx, Δy).  This is the `p = base + Σ wᵢ·Δᵢ` morph's Δ table, which the vertex shader
 * texel-fetches per active corner.
 *
 * Backend-neutral by construction: it returns plain texels, so each backend uploads them its own way
 * (an RG32F 2D texture on the GL family; whatever Metal prefers).  Emitting a backend's buffer type here
 * would put a texture-upload decision in shared code.
 *
 * A cell with no keyform, or one whose delta array is too short for this vertex, contributes (0, 0) -
 * i.e. that cell leaves the vertex at its rest position.  Both cases are reachable in real models, so
 * they are a normal absence rather than an error.
 *
 * @param KeyformGrid? grid       The mesh's keyform grid, or null when the drawable is unkeyed (which
 *   yields an all-zero single column - the rest mesh).
 * @param Int         vertexCount The mesh's vertex count (the texture height).
 * @param Int         cellCount   The grid's linear cell extent from [keyformCellCount] (the width).
 * @param Map         cells       The grid's cells by linear index; defaults to [cellsByLinearIndex] of
 *   [grid], but a caller that already built the map (the drawable upload also needs it for the composite
 *   bounds walk) can pass it in to avoid rebuilding it.
 * @return FloatArray The texels, row-major, length `vertexCount * cellCount * 2`.
 */
internal fun buildDeltaTexels(
	grid: KeyformGrid<MeshDeltaForm>?,
	vertexCount: Int,
	cellCount: Int,
	cells: Map<Int, KeyformCell<MeshDeltaForm>> = if (grid != null) cellsByLinearIndex(grid) else emptyMap(),
): FloatArray {
	val texels = FloatArray(vertexCount * cellCount * 2)
	var writeIndex = 0
	for (vertexIndex in 0 until vertexCount) {
		for (cellIndex in 0 until cellCount) {
			val deltas = cells[cellIndex]?.form?.positionDeltas
			if (deltas != null && vertexIndex * 2 + 1 < deltas.size) {
				texels[writeIndex] = deltas[vertexIndex * 2]
				texels[writeIndex + 1] = deltas[vertexIndex * 2 + 1]
			}
			// else: leave (0, 0) - a missing cell or a short delta array means no offset from rest.
			writeIndex += 2
		}
	}
	return texels
}

/**
 * The static column assignment of a drawable's blend-shape delta forms in its delta texture:
 * blend columns are appended after the grid's [cellCount] columns, in (binding order, key order),
 * skipping neutral/absent forms (their delta is zero and never fetched). CPU pose prep, the texel
 * bake, and the per-frame uniform fill all agree through this one mapping.
 *
 * @property Int cellCount        The grid's own column count (blend columns start here).
 * @property Int blendColumnCount Number of appended blend columns.
 */
internal class BlendColumnLayout(
	val cellCount: Int,
	val blendColumnCount: Int,
	private val columnByKey: Map<Long, Int>,
) {
	/**
	 * The texture column of one binding's key form, or null when that key holds no form (neutral).
	 *
	 * @param Int bindingIndex Index into the drawable's blendShapes.
	 * @param Int keyIndex     Key index within that binding.
	 * @return Int? The absolute texture column, or null.
	 */
	fun columnOf(bindingIndex: Int, keyIndex: Int): Int? = columnByKey[packKey(bindingIndex, keyIndex)]

	internal companion object {
		/** Packs a (binding, key) pair into one map key. */
		internal fun packKey(bindingIndex: Int, keyIndex: Int): Long = bindingIndex.toLong() shl 32 or keyIndex.toLong()
	}
}

/**
 * Builds the blend-column layout for [drawable] over a grid of [cellCount] columns.
 *
 * @param Drawable drawable  The drawable (its blendShapes define the columns).
 * @param Int      cellCount The grid's own column count.
 * @return BlendColumnLayout The layout (zero blend columns when binding-free).
 */
internal fun blendColumnLayout(drawable: Drawable, cellCount: Int): BlendColumnLayout {
	val columnByKey = HashMap<Long, Int>()
	var nextColumn = cellCount
	for (bindingIndex in drawable.blendShapes.indices) {
		val binding = drawable.blendShapes[bindingIndex]
		for (keyIndex in binding.forms.indices) {
			if (binding.forms[keyIndex] != null) {
				columnByKey[BlendColumnLayout.packKey(bindingIndex, keyIndex)] = nextColumn
				nextColumn++
			}
		}
	}
	return BlendColumnLayout(cellCount, nextColumn - cellCount, columnByKey)
}

/**
 * Builds a mesh's delta texels INCLUDING its appended blend-shape columns: the grid columns as
 * [buildDeltaTexels], then one column per non-neutral blend form holding the E5-resolved delta
 * (form minus the grid-at-default reference) per vertex. Static per model, like the grid columns.
 *
 * @param KeyformGrid       grid         The mesh's keyform grid.
 * @param Drawable          drawable     The drawable (blend forms + reference resolution).
 * @param Function          defaultValue Default value per parameter id (the reference pose).
 * @param Int               vertexCount  The mesh's vertex count (the texture height).
 * @param BlendColumnLayout layout       The column assignment from [blendColumnLayout].
 * @param Map               cells        The grid's cells by linear index; defaults to [cellsByLinearIndex]
 *   of [grid], but a caller that already built the map can pass it in to avoid rebuilding it.
 * @return FloatArray The texels, row-major, length `vertexCount * (cellCount + blendColumnCount) * 2`.
 */
internal fun buildDeltaTexelsWithBlend(
	grid: KeyformGrid<MeshDeltaForm>?,
	drawable: Drawable,
	defaultValue: (ParameterId) -> Float,
	vertexCount: Int,
	layout: BlendColumnLayout,
	cells: Map<Int, KeyformCell<MeshDeltaForm>> = if (grid != null) cellsByLinearIndex(grid) else emptyMap(),
): FloatArray {
	val width = layout.cellCount + layout.blendColumnCount
	val texels = FloatArray(vertexCount * width * 2)
	val reference = meshGridDefaultDeltas(drawable, defaultValue)
	for (vertexIndex in 0 until vertexCount) {
		val rowBase = vertexIndex * width * 2
		for (cellIndex in 0 until layout.cellCount) {
			val deltas = cells[cellIndex]?.form?.positionDeltas
			if (deltas != null && vertexIndex * 2 + 1 < deltas.size) {
				texels[rowBase + cellIndex * 2] = deltas[vertexIndex * 2]
				texels[rowBase + cellIndex * 2 + 1] = deltas[vertexIndex * 2 + 1]
			}
		}
		for (bindingIndex in drawable.blendShapes.indices) {
			val binding = drawable.blendShapes[bindingIndex]
			for (keyIndex in binding.forms.indices) {
				val form = binding.forms[keyIndex] ?: continue
				val column = layout.columnOf(bindingIndex, keyIndex) ?: continue
				if (vertexIndex * 2 + 1 < form.positionDeltas.size) {
					val referenceX = reference?.getOrNull(vertexIndex * 2) ?: 0f
					val referenceY = reference?.getOrNull(vertexIndex * 2 + 1) ?: 0f
					texels[rowBase + column * 2] = form.positionDeltas[vertexIndex * 2] - referenceX
					texels[rowBase + column * 2 + 1] = form.positionDeltas[vertexIndex * 2 + 1] - referenceY
				}
			}
		}
	}
	return texels
}