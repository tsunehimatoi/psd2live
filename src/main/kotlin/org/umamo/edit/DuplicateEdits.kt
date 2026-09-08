package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/**
 * Duplicates one whole drawable (Object-mode Shift+D): the copy takes a Blender-style ".001"-suffixed
 * unique id and name, a fresh mesh instance (positions copied so the copy edits independently;
 * uvs / indices shared - the copy-on-write discipline), a re-wrapped keyform grid sharing the form
 * payloads by reference (immutable in practice; a later edit copy-on-writes them), and its source's
 * mask references.  It sits immediately after its source in the org tree (or appends at the root when
 * the source was never placed - the import safety net's rule), and carries NO glue membership: glues
 * reference meshes by id, so a fresh id naturally welds nothing.
 *
 * 描画オブジェクトの複製。".001" 形式の一意な id と名前を付け、元の直後にツリー挿入する。
 * グルーには参加しない。
 *
 * @param DrawableId id The drawable to duplicate.
 * @return Pair<PuppetModel, DrawableId>? The edited model and the copy's id, or null when the source
 *   is missing or carries no mesh.
 */
fun PuppetModel.withDrawableDuplicated(id: DrawableId): Pair<PuppetModel, DrawableId>? {
	val source = drawables.firstOrNull { candidate -> candidate.id == id } ?: return null
	val mesh = source.mesh ?: return null
	val copyId = uniqueDuplicateId(id)
	val copy =
		source.copy(
			id = copyId,
			name = uniqueDuplicateName(source.name),
			mesh = DrawableMesh(mesh.positions.copyOf(), mesh.uvs, mesh.indices),
			geometryGrid =
				source.geometryGrid?.let { grid ->
					KeyformGrid(grid.axes, grid.cells.map { cell -> KeyformCell(cell.coordinate, cell.form) })
				},
			// The atlas mapping is keyed by the source format's ids, so the copy resolves its texture
			// through its source (or the original, when duplicating a duplicate).
			textureSourceId = source.textureSourceId ?: source.id,
		)

	// Insert the copy immediately after its source, in whichever child list holds it.
	var placed = false

	fun insertAfterSource(children: List<OrgChild>): List<OrgChild> {
		if (placed) {
			return children
		}
		val sourceIndex = children.indexOfFirst { child -> child is OrgChild.Drawable && child.id == id }
		if (sourceIndex < 0) {
			return children
		}
		placed = true
		return children.toMutableList().apply { add(sourceIndex + 1, OrgChild.Drawable(copyId)) }
	}
	val newRootChildren = insertAfterSource(rootChildren)
	val newParts = parts.map { part -> if (placed) part else part.copy(children = insertAfterSource(part.children)) }
	val finalRootChildren = if (placed) newRootChildren else newRootChildren + OrgChild.Drawable(copyId)
	val edited =
		copy(
			drawables = drawables + copy,
			parts = newParts,
			rootChildren = finalRootChildren,
		)
	return edited.withDerivedRenderRoot().let { derived -> derived to copyId }
}

/**
 * The first free ".NNN"-suffixed id derived from [id] (d -> d.001, d.002, ...), so repeated duplicates
 * never collide.
 *
 * @param DrawableId id The source id.
 * @return DrawableId The unique copy id.
 */
private fun PuppetModel.uniqueDuplicateId(id: DrawableId): DrawableId {
	var counter = 1
	while (true) {
		val candidate = DrawableId("${id.raw}.${counter.toString().padStart(3, '0')}")
		if (drawables.none { drawable -> drawable.id == candidate }) {
			return candidate
		}
		counter++
	}
}

/**
 * The first free ".NNN"-suffixed display name derived from [name], mirroring [uniqueDuplicateId].
 *
 * @param String name The source display name.
 * @return String The unique copy name.
 */
private fun PuppetModel.uniqueDuplicateName(name: String): String {
	var counter = 1
	while (true) {
		val candidate = "$name.${counter.toString().padStart(3, '0')}"
		if (drawables.none { drawable -> drawable.name == candidate }) {
			return candidate
		}
		counter++
	}
}