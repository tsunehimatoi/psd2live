package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * The session's remembered-selection memory: which drawable was last active (so entering Edit mode
 * with an empty selection lands somewhere sensible - Blender's remembered selection), and each
 * mesh's stashed element selection (stashed when Edit mode is left or the session switches meshes,
 * restored per drawable on re-entry when the elements still fit - Blender remembers the mesh
 * selection across mode switches per mesh).  Transient UI convenience, deliberately NOT part of
 * [EditorSnapshot]: undo restores whatever the snapshot holds, this only seeds fresh entries.
 *
 * セッションの選択記憶。最後にアクティブだった描画対象と、メッシュごとの要素選択スタッシュを保持
 * する（Blender のモード切替をまたぐ選択記憶に相当）。スナップショットには含めない。
 */
internal class MeshElementMemory {
	/** The last drawable that was active, or null before any; Edit-mode entry falls back to it. */
	var lastActiveDrawableId: DrawableId? = null

	// Per-mesh element-selection memory, keyed by drawable, plus the select mode remembered alongside.
	private val rememberedElementsByDrawable = HashMap<DrawableId, Set<MeshElement>>()
	private val rememberedActiveElementByDrawable = HashMap<DrawableId, MeshElement>()
	private var rememberedSelectMode: MeshSelectMode? = null

	/**
	 * Stashes each session drawable's element selection into the per-mesh memory, so re-entering Edit
	 * mode - or switching back to the mesh with Alt+Q - restores it.  An emptied mesh clears its slot;
	 * the select mode is remembered alongside.
	 *
	 * @param MeshSelection current The element selection to stash.
	 */
	fun stash(current: MeshSelection) {
		if (current.drawableIds.isEmpty()) {
			return
		}
		rememberedSelectMode = current.selectMode
		for (drawableId in current.drawableIds) {
			val elements = current.elementsOf(drawableId)
			if (elements.isEmpty()) {
				rememberedElementsByDrawable.remove(drawableId)
				rememberedActiveElementByDrawable.remove(drawableId)
			} else {
				rememberedElementsByDrawable[drawableId] = elements
			}
		}
		current.activeElement?.let { active -> rememberedActiveElementByDrawable[active.drawableId] = active.element }
	}

	/**
	 * Restores the per-mesh remembered elements into [seeded]: each seeded drawable whose stored
	 * elements still fit its current mesh gets them back (validated per drawable, so one stale mesh
	 * never discards the others' memory); the remembered select mode returns when anything restored.
	 *
	 * @param MeshSelection seeded The freshly seeded session selection.
	 * @param PuppetModel model The current model (element fit is validated against its meshes).
	 * @return MeshSelection The seeded selection with the remembered elements folded in.
	 */
	fun restore(seeded: MeshSelection, model: PuppetModel): MeshSelection {
		val restored =
			seeded.drawableIds.mapNotNull { drawableId ->
				val stored = rememberedElementsByDrawable[drawableId]?.takeIf { elements -> elements.isNotEmpty() } ?: return@mapNotNull null
				val probe = seeded.copy(selectMode = rememberedSelectMode ?: seeded.selectMode, elementsByDrawable = mapOf(drawableId to stored))
				val fits = MeshSelectionOps.fitsWithinMeshes(probe) { candidateId -> model.drawables.firstOrNull { drawable -> drawable.id == candidateId }?.mesh }
				if (fits) drawableId to stored else null
			}.toMap()
		if (restored.isEmpty()) {
			return seeded
		}
		// The active mesh's remembered active element returns with it (when it is still selected there).
		val restoredActive =
			seeded.activeDrawableId?.let { activeId ->
				rememberedActiveElementByDrawable[activeId]
					?.takeIf { element -> restored[activeId]?.contains(element) == true }
					?.let { element -> ActiveMeshElement(activeId, element) }
			}
		return seeded.copy(
			selectMode = rememberedSelectMode ?: seeded.selectMode,
			elementsByDrawable = restored,
			activeElement = restoredActive,
		)
	}
}