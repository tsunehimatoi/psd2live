package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.PuppetModel

/**
 * How one vertex of a topology-edited mesh derives its keyform deltas (and its glue identity) from the
 * old mesh's vertices.  A topology operation changes the vertex count, and every keyform cell's
 * positionDeltas array is parallel to the positions (stride 2 * vertexCount) - so each NEW vertex must
 * say where its per-cell deltas come from:
 *
 *   - [FromOld] copies one old vertex's deltas verbatim (a kept or duplicated vertex).
 *   - [AverageOf] averages several old vertices' deltas (a merge's surviving vertex).
 *   - [LerpOf] interpolates two old vertices' deltas by t (a split point on an old edge).
 *
 * トポロジ編集後の各頂点が、旧頂点からどのようにキーフォームのデルタを引き継ぐかの指定。
 */
sealed interface VertexSource {
	/**
	 * The new vertex is old vertex [oldIndex], kept or duplicated: its deltas copy verbatim.
	 *
	 * @property Int oldIndex The old vertex the deltas copy from.
	 */
	data class FromOld(val oldIndex: Int) : VertexSource

	/**
	 * The new vertex merges several old vertices: its deltas are their per-cell mean.
	 *
	 * @property List<Int> oldIndices The merged old vertices.
	 */
	data class AverageOf(val oldIndices: List<Int>) : VertexSource

	/**
	 * The new vertex splits the old edge (oldA, oldB) at parameter [t]: its deltas lerp accordingly.
	 *
	 * @property Int oldA The edge's first old endpoint.
	 * @property Int oldB The edge's second old endpoint.
	 * @property Float t The split parameter (0 = at oldA, 1 = at oldB).
	 */
	data class LerpOf(val oldA: Int, val oldB: Int, val t: Float) : VertexSource
}

/**
 * One topology edit of one drawable's mesh, ready to commit: the complete replacement mesh (positions,
 * uvs, and indices all sized to the new vertex count) plus one [VertexSource] per NEW vertex, from
 * which [withMeshTopologyEdit] rebuilds every keyform cell's deltas and remaps the glue pairs.  The op
 * builders in MeshTopologyOps produce these; they never touch the model themselves.
 *
 * 1つの描画メッシュのトポロジ編集。置き換えメッシュと、新頂点ごとのデルタ導出指定を持つ。
 *
 * @property DrawableMesh newMesh The replacement mesh (a freshly built instance).
 * @property List<VertexSource> vertexSources One source per new vertex, in vertex order.
 */
class MeshTopologyEdit(
	val newMesh: DrawableMesh,
	val vertexSources: List<VertexSource>,
)

/**
 * Applies a topology edit to one drawable: swaps in the full replacement mesh, rebuilds EVERY keyform
 * cell's positionDeltas to the new vertex count per the edit's [VertexSource]s (copy / average / lerp -
 * so a parameter scrub after the edit still deforms sanely), and remaps the model's glue pairs through
 * the old-to-new vertex mapping (a pair whose vertex was removed is dropped rather than left dangling).
 * Everything else structurally shares with the receiver (the copy-on-write discipline).  Returns the
 * same instance when the drawable is missing, carries no mesh, or the edit's source list does not match
 * its new mesh (a malformed edit must not corrupt the model).
 *
 * トポロジ編集の適用。メッシュ全体を差し替え、全キーフォームセルのデルタを新頂点数に再構築し、
 * グルー対も旧→新の対応で再マップする（消えた頂点の対は破棄）。
 *
 * @param DrawableId id The drawable whose mesh the edit replaces.
 * @param MeshTopologyEdit edit The edit to apply.
 * @return PuppetModel The edited model, or this instance on a no-op.
 */
fun PuppetModel.withMeshTopologyEdit(id: DrawableId, edit: MeshTopologyEdit): PuppetModel {
	val drawable = drawables.firstOrNull { candidate -> candidate.id == id } ?: return this
	val oldMesh = drawable.mesh ?: return this
	val newVertexCount = edit.newMesh.vertexCount
	if (edit.vertexSources.size != newVertexCount) {
		return this
	}

	// Rebuild each geometry cell's deltas at the new stride, deriving per vertex from the old deltas.
	// The channel tracks (multiply / screen tints, opacity, draw order, ...) carry no per-vertex data, so a
	// topology edit leaves them untouched entirely - only the per-vertex geometry cells need this remap.
	val newGeometry =
		drawable.geometryGrid?.let { grid ->
			KeyformGrid(
				axes = grid.axes,
				cells =
					grid.cells.map { cell ->
						KeyformCell(cell.coordinate, remapMeshDeltas(cell.form, edit.vertexSources, oldMesh.vertexCount))
					},
			)
		}
	// Blend-shape forms are per-vertex too, so they need the same remap to the new vertex count; leaving
	// them at the old count would silently zero every blend contribution after a topology edit.
	val newBlendShapes =
		drawable.blendShapes.map { binding ->
			binding.copy(
				forms =
					binding.forms.map { form ->
						form?.let {
							MeshForm(
								remapMeshDeltas(MeshDeltaForm(it.positionDeltas), edit.vertexSources, oldMesh.vertexCount).positionDeltas,
								it.drawOrder,
								it.opacity,
								it.multiplyColor,
								it.screenColor,
							)
						}
					},
			)
		}

	// The old-to-new vertex mapping glue remapping resolves through: a kept / duplicated / merged old
	// vertex maps to the FIRST new vertex that claims it; an unclaimed old vertex maps to -1 (removed).
	// First-claim is deliberate for duplicates: the kept originals (enumerated before the copies) claim
	// their old indices, so a glue weld stays on the original and the copy floats free.
	val oldToNewIndex = IntArray(oldMesh.vertexCount) { -1 }
	edit.vertexSources.forEachIndexed { newIndex, source ->
		when (source) {
			is VertexSource.FromOld ->
				if (source.oldIndex in 0 until oldMesh.vertexCount && oldToNewIndex[source.oldIndex] == -1) {
					oldToNewIndex[source.oldIndex] = newIndex
				}

			is VertexSource.AverageOf ->
				for (oldIndex in source.oldIndices) {
					if (oldIndex in 0 until oldMesh.vertexCount && oldToNewIndex[oldIndex] == -1) {
						oldToNewIndex[oldIndex] = newIndex
					}
				}

			// A split point is a brand-new vertex; it claims no old identity.
			is VertexSource.LerpOf -> {}
		}
	}

	val newDrawables =
		drawables.map { candidate ->
			if (candidate.id == id) {
				candidate.copy(mesh = edit.newMesh, geometryGrid = newGeometry, blendShapes = newBlendShapes)
			} else {
				candidate
			}
		}
	val newGlues =
		glues.map { glue ->
			if (glue.meshA != id && glue.meshB != id) {
				glue
			} else {
				val remappedPairs =
					glue.pairs.mapNotNull { pair ->
						val newIndexA = if (glue.meshA == id) oldToNewIndex.getOrElse(pair.indexA) { -1 } else pair.indexA
						val newIndexB = if (glue.meshB == id) oldToNewIndex.getOrElse(pair.indexB) { -1 } else pair.indexB
						if (newIndexA < 0 || newIndexB < 0) {
							// The welded vertex no longer exists: the pair is dropped, never left dangling.
							null
						} else {
							GluePair(newIndexA, newIndexB, pair.weightA, pair.weightB)
						}
					}
				glue.copy(pairs = remappedPairs)
			}
		}
	return copy(drawables = newDrawables, glues = newGlues)
}

/**
 * Rebuilds one keyform cell's [MeshForm] at the new vertex count: each new vertex's delta pair copies,
 * averages, or lerps from the old form's deltas per its [VertexSource].  An old index beyond the old
 * form's array (a malformed grid) contributes zero, keeping the rebuild total.
 *
 * @param MeshForm form The old cell form (deltas at the old stride).
 * @param List<VertexSource> vertexSources One source per new vertex.
 * @param Int oldVertexCount The old mesh's vertex count (bounds the old delta reads).
 * @return MeshForm The rebuilt form (deltas at the new stride; drawOrder / opacity carried).
 */
private fun remapMeshDeltas(form: MeshDeltaForm, vertexSources: List<VertexSource>, oldVertexCount: Int): MeshDeltaForm {
	val oldDeltas = form.positionDeltas

	fun oldDeltaX(oldIndex: Int): Float = if (oldIndex in 0 until oldVertexCount && oldIndex * 2 < oldDeltas.size) oldDeltas[oldIndex * 2] else 0f

	fun oldDeltaY(oldIndex: Int): Float = if (oldIndex in 0 until oldVertexCount && oldIndex * 2 + 1 < oldDeltas.size) oldDeltas[oldIndex * 2 + 1] else 0f
	val newDeltas = FloatArray(vertexSources.size * 2)
	vertexSources.forEachIndexed { newIndex, source ->
		when (source) {
			is VertexSource.FromOld -> {
				newDeltas[newIndex * 2] = oldDeltaX(source.oldIndex)
				newDeltas[newIndex * 2 + 1] = oldDeltaY(source.oldIndex)
			}

			is VertexSource.AverageOf -> {
				if (source.oldIndices.isNotEmpty()) {
					var sumX = 0f
					var sumY = 0f
					for (oldIndex in source.oldIndices) {
						sumX += oldDeltaX(oldIndex)
						sumY += oldDeltaY(oldIndex)
					}
					newDeltas[newIndex * 2] = sumX / source.oldIndices.size
					newDeltas[newIndex * 2 + 1] = sumY / source.oldIndices.size
				}
			}

			is VertexSource.LerpOf -> {
				newDeltas[newIndex * 2] = oldDeltaX(source.oldA) + (oldDeltaX(source.oldB) - oldDeltaX(source.oldA)) * source.t
				newDeltas[newIndex * 2 + 1] = oldDeltaY(source.oldA) + (oldDeltaY(source.oldB) - oldDeltaY(source.oldA)) * source.t
			}
		}
	}
	return MeshDeltaForm(newDeltas)
}