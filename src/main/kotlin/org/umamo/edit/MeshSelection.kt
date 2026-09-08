package org.umamo.edit

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh

/**
 * The Edit-mode selection domain, mirroring Blender's vertex / edge / face select modes: which kind of
 * mesh element the pointer picks and the selection stores.  Only the current mode's domain is stored in
 * [MeshSelection.elementsByDrawable]; highlights in the other domains are derived for display, and
 * switching modes converts the stored sets (see [MeshSelectionOps.changeSelectMode]).
 *
 * 編集モードの選択対象の種類。Blender の頂点・辺・面選択モードに対応する。
 */
enum class MeshSelectMode {
	Vertex,
	Edge,
	Face,
}

/**
 * One selectable mesh element: a vertex, an undirected edge, or a face.  Element identity is LOCAL to
 * its drawable's mesh (a vertex index means nothing without knowing which mesh), which is why
 * [MeshSelection] stores elements per drawable and the active element carries its drawable alongside.
 * The mesh topology is immutable while editing (copy-on-write replaces only positions), so vertex
 * indices, canonical (low, high) edge pairs, and triangle indices are stable identities across every
 * gesture.
 *
 * メッシュの選択可能要素。頂点・無向辺・面（三角形）のいずれか。要素の同一性はメッシュ内ローカル。
 */
sealed interface MeshElement {
	/**
	 * A single mesh vertex.
	 *
	 * @property Int index The vertex index (into the mesh's vertex list).
	 */
	data class Vertex(val index: Int) : MeshElement

	/**
	 * An undirected mesh edge, canonicalized so (a, b) and (b, a) are one identity.  Construct via [of].
	 *
	 * @property Int endpointLow The smaller endpoint vertex index.
	 * @property Int endpointHigh The larger endpoint vertex index.
	 */
	data class Edge(val endpointLow: Int, val endpointHigh: Int) : MeshElement {
		companion object {
			/**
			 * Builds the canonical undirected edge for two endpoint vertex indices, given in either order.
			 *
			 * @param Int vertexA One endpoint vertex index.
			 * @param Int vertexB The other endpoint vertex index.
			 * @return Edge The canonical (low, high) edge.
			 */
			fun of(vertexA: Int, vertexB: Int): Edge =
				if (vertexA <= vertexB) {
					Edge(vertexA, vertexB)
				} else {
					Edge(vertexB, vertexA)
				}
		}
	}

	/**
	 * A single mesh face.  The mesh is triangles-only, so a face is one triangle of the index list.
	 *
	 * @property Int triangleIndex The triangle ordinal (three consecutive entries of the index list).
	 */
	data class Face(val triangleIndex: Int) : MeshElement
}

/**
 * The active (primary) element of an Edit session: the last-touched element together with the drawable
 * whose mesh it lives in - element indices are mesh-local, so the pair is the element's full identity.
 *
 * @property DrawableId drawableId The drawable whose mesh holds the element.
 * @property MeshElement element The last-touched element.
 */
data class ActiveMeshElement(val drawableId: DrawableId, val element: MeshElement)

/**
 * An immutable Edit-mode sub-object selection over one or more drawables' meshes: which meshes are in
 * the edit session, the select mode (vertex / edge / face), and the selected elements per mesh with the
 * active (primary) one.  This is the sub-object analog of [Selection] - where object-mode selects whole
 * entities, Edit mode selects elements inside the session meshes - and the multi-mesh shape is what lets
 * a rigger edit several art meshes at once (entering Edit with a multi-selection, and later the glue
 * workflows that need two meshes live together).
 *
 * Only the current [selectMode]'s domain is ever stored in [elementsByDrawable] (a vertex-mode selection
 * holds only [MeshElement.Vertex] members, and so on).  Cross-domain highlights - an edge lighting up
 * because both endpoints are selected in vertex mode - are derived for display via [MeshTopology], never
 * stored, which is exactly Blender's model.
 *
 * [drawableIds] is empty when Edit mode is inert (entered without an editable drawable); the overlay
 * then draws nothing and the transform operators no-op.  [activeDrawableId] is Blender's active object
 * within the session - the mesh a click last landed on, which anchors per-mesh actions (select-mode
 * conversion display, the status bar's denominators).  [activeElement] is the element analog of
 * [Selection.active]: the last-touched element with its drawable; it is always a member of its
 * drawable's element set, or null when nothing is selected.
 *
 * @property List<DrawableId> drawableIds The meshes in the edit session, in selection order.
 * @property DrawableId? activeDrawableId The session's active mesh, or null when the session is inert.
 * @property MeshSelectMode selectMode The element domain clicks select (vertex / edge / face).
 * @property Map<DrawableId, Set<MeshElement>> elementsByDrawable The selected elements per mesh, all of
 *   the current mode's domain; a drawable with no selection may be absent.
 * @property ActiveMeshElement? activeElement The primary element (last touched), or null when none.
 */
data class MeshSelection(
	val drawableIds: List<DrawableId> = emptyList(),
	val activeDrawableId: DrawableId? = null,
	val selectMode: MeshSelectMode = MeshSelectMode.Vertex,
	val elementsByDrawable: Map<DrawableId, Set<MeshElement>> = emptyMap(),
	val activeElement: ActiveMeshElement? = null,
) {
	/** True when no elements are selected on any mesh (the session may still hold drawables). */
	val isEmpty: Boolean get() = elementsByDrawable.values.all { elements -> elements.isEmpty() }

	/** The number of selected elements across every session mesh. */
	val size: Int get() = elementsByDrawable.values.sumOf { elements -> elements.size }

	/**
	 * The selected elements of one mesh (empty when the drawable has no selection or is not in the
	 * session).
	 *
	 * @param DrawableId drawableId The mesh to read.
	 * @return Set<MeshElement> That mesh's selected elements.
	 */
	fun elementsOf(drawableId: DrawableId): Set<MeshElement> = elementsByDrawable[drawableId] ?: emptySet()

	/**
	 * Reports whether the given element is selected on the given mesh.
	 *
	 * @param DrawableId drawableId The mesh the element lives in.
	 * @param MeshElement element The element to test.
	 * @return Boolean True when selected.
	 */
	fun contains(drawableId: DrawableId, element: MeshElement): Boolean = element in elementsOf(drawableId)

	companion object {
		/**
		 * An empty Edit session over the given meshes: nothing selected, the first (or given) mesh active.
		 *
		 * @param List<DrawableId> drawableIds The session meshes, in selection order.
		 * @param DrawableId? activeDrawableId The active mesh; defaults to the first.
		 * @return MeshSelection The seeded session selection.
		 */
		fun editing(drawableIds: List<DrawableId>, activeDrawableId: DrawableId? = drawableIds.firstOrNull()): MeshSelection =
			MeshSelection(drawableIds = drawableIds, activeDrawableId = activeDrawableId)
	}
}

/**
 * Pure transformations over a [MeshSelection], one per selection gesture (mirroring [SelectionOps]).
 * Element gestures name the drawable they land on (element identity is mesh-local); whole-session
 * gestures (select all, invert, mode conversion) take a mesh provider so each session mesh converts
 * against its own topology.  Each function returns a new immutable [MeshSelection] over the same
 * session and never mutates its input, so they are unit-testable without Compose.  The active element
 * is maintained per the [MeshSelection] invariant: always a member of its drawable's element set, or
 * null when nothing remains.
 */
object MeshSelectionOps {
	/**
	 * Rebuilds the per-drawable element map with one drawable's set replaced, dropping empty sets so the
	 * map never accumulates stale keys.
	 *
	 * @param MeshSelection selection The selection whose map to rebuild.
	 * @param DrawableId drawableId The drawable whose set changes.
	 * @param Set<MeshElement> elements That drawable's new element set.
	 * @return Map<DrawableId, Set<MeshElement>> The rebuilt map.
	 */
	private fun withElements(
		selection: MeshSelection,
		drawableId: DrawableId,
		elements: Set<MeshElement>,
	): Map<DrawableId, Set<MeshElement>> =
		if (elements.isEmpty()) {
			selection.elementsByDrawable - drawableId
		} else {
			selection.elementsByDrawable + (drawableId to elements)
		}

	/**
	 * Replaces the whole selection with a single element (a plain click): every session mesh clears and
	 * only [element] on [drawableId] remains, active, with its mesh becoming the session's active mesh.
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param DrawableId drawableId The mesh the click landed on.
	 * @param MeshElement element The sole element to select.
	 * @return MeshSelection A selection holding only [element], with it active.
	 */
	fun replace(selection: MeshSelection, drawableId: DrawableId, element: MeshElement): MeshSelection =
		selection.copy(
			activeDrawableId = drawableId,
			elementsByDrawable = mapOf(drawableId to setOf(element)),
			activeElement = ActiveMeshElement(drawableId, element),
		)

	/**
	 * Toggles an element's membership on its mesh (a Shift or Ctrl / Cmd click, Blender-style): removes
	 * it when present, adds it otherwise.  When added it becomes active (and its mesh the active mesh);
	 * when removed the active element falls back to another member of the same mesh, or null.
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param DrawableId drawableId The mesh the click landed on.
	 * @param MeshElement element The element to toggle.
	 * @return MeshSelection The resulting selection.
	 */
	fun toggle(selection: MeshSelection, drawableId: DrawableId, element: MeshElement): MeshSelection {
		val current = selection.elementsOf(drawableId)
		return if (element in current) {
			val remaining = current - element
			val fallback = remaining.lastOrNull()?.let { survivor -> ActiveMeshElement(drawableId, survivor) }
			selection.copy(
				activeDrawableId = drawableId,
				elementsByDrawable = withElements(selection, drawableId, remaining),
				activeElement = if (selection.activeElement?.drawableId == drawableId && selection.activeElement.element == element) fallback else selection.activeElement,
			)
		} else {
			selection.copy(
				activeDrawableId = drawableId,
				elementsByDrawable = withElements(selection, drawableId, current + element),
				activeElement = ActiveMeshElement(drawableId, element),
			)
		}
	}

	/**
	 * Adds an element without removing anything (an additive gesture), making it active and its mesh the
	 * session's active mesh.
	 *
	 * @param MeshSelection selection The selection to extend.
	 * @param DrawableId drawableId The mesh the element lives in.
	 * @param MeshElement element The element to add.
	 * @return MeshSelection The extended selection.
	 */
	fun add(selection: MeshSelection, drawableId: DrawableId, element: MeshElement): MeshSelection =
		selection.copy(
			activeDrawableId = drawableId,
			elementsByDrawable = withElements(selection, drawableId, selection.elementsOf(drawableId) + element),
			activeElement = ActiveMeshElement(drawableId, element),
		)

	/**
	 * Applies a box (rubber-band) selection across the session meshes: replaces every mesh's element set
	 * with its entry in [elementsByDrawable], or unions them in when [additive].  The active element is
	 * kept when it remains selected, otherwise it drops to null (a box has no single "last touched"
	 * element).
	 *
	 * @param MeshSelection selection The selection to modify.
	 * @param Map<DrawableId, Set<MeshElement>> elementsByDrawable The enclosed elements per session mesh.
	 * @param Boolean additive True to union with the current sets (Shift box), false to replace them.
	 * @return MeshSelection The resulting selection.
	 */
	fun box(selection: MeshSelection, elementsByDrawable: Map<DrawableId, Set<MeshElement>>, additive: Boolean): MeshSelection {
		val resulting = HashMap<DrawableId, Set<MeshElement>>()
		if (additive) {
			selection.elementsByDrawable.forEach { (drawableId, elements) -> resulting[drawableId] = elements }
			elementsByDrawable.forEach { (drawableId, elements) ->
				resulting[drawableId] = (resulting[drawableId] ?: emptySet()) + elements
			}
		} else {
			elementsByDrawable.forEach { (drawableId, elements) ->
				if (elements.isNotEmpty()) {
					resulting[drawableId] = elements
				}
			}
		}
		resulting.values.removeAll { elements -> elements.isEmpty() }
		val active = selection.activeElement?.takeIf { active -> active.element in (resulting[active.drawableId] ?: emptySet()) }
		return selection.copy(elementsByDrawable = resulting, activeElement = active)
	}

	/**
	 * Removes elements per mesh (the erase half of a Circle-select stroke), keeping the active element
	 * only when it survives.  The mirror of [box]'s additive union.
	 *
	 * @param MeshSelection selection The selection to shrink.
	 * @param Map<DrawableId, Set<MeshElement>> elementsByDrawable The elements to deselect per mesh.
	 * @return MeshSelection The selection with those elements removed.
	 */
	fun remove(selection: MeshSelection, elementsByDrawable: Map<DrawableId, Set<MeshElement>>): MeshSelection {
		val resulting = HashMap<DrawableId, Set<MeshElement>>(selection.elementsByDrawable)
		elementsByDrawable.forEach { (drawableId, elements) ->
			val remaining = (resulting[drawableId] ?: emptySet()) - elements
			if (remaining.isEmpty()) {
				resulting.remove(drawableId)
			} else {
				resulting[drawableId] = remaining
			}
		}
		val active = selection.activeElement?.takeIf { active -> active.element in (resulting[active.drawableId] ?: emptySet()) }
		return selection.copy(elementsByDrawable = resulting, activeElement = active)
	}

	/**
	 * Clears the selection, keeping the session meshes and mode (the Edit session stays open, nothing
	 * selected).
	 *
	 * @param MeshSelection selection The selection to clear.
	 * @return MeshSelection The selection with empty element sets.
	 */
	fun clear(selection: MeshSelection): MeshSelection =
		selection.copy(elementsByDrawable = emptyMap(), activeElement = null)

	/**
	 * Switches the select mode, converting every session mesh's stored elements into the new domain with
	 * Blender's flush-down / derive-up rules.  Flushing down (to vertex) expands every element to the
	 * vertices it covers, so selection is never lost.  Deriving up is strict per Blender: an edge
	 * qualifies only when both endpoints were selected, and a face only when all of its own vertices
	 * (from vertex mode) or all three of its own edges (from edge mode) were selected.  The active
	 * element drops to null on conversion (no single "last touched" element survives a domain change);
	 * a same-mode call returns the selection unchanged.  A session mesh whose triangles the provider
	 * cannot resolve converts to empty.
	 *
	 * @param MeshSelection selection The selection to convert.
	 * @param MeshSelectMode newMode The target select mode.
	 * @param Function triangleIndicesOf Resolves a session mesh's triangle indices, or null when absent.
	 * @return MeshSelection The selection in [newMode] with the converted element sets.
	 */
	fun changeSelectMode(
		selection: MeshSelection,
		newMode: MeshSelectMode,
		triangleIndicesOf: (DrawableId) -> IntArray?,
	): MeshSelection {
		if (newMode == selection.selectMode) {
			return selection
		}
		val converted = HashMap<DrawableId, Set<MeshElement>>()
		for ((drawableId, elements) in selection.elementsByDrawable) {
			val triangleIndices = triangleIndicesOf(drawableId) ?: continue
			val convertedElements = convertElements(elements, selection.selectMode, newMode, triangleIndices)
			if (convertedElements.isNotEmpty()) {
				converted[drawableId] = convertedElements
			}
		}
		return selection.copy(selectMode = newMode, elementsByDrawable = converted, activeElement = null)
	}

	/**
	 * Converts one mesh's element set between select-mode domains (the single-mesh core of
	 * [changeSelectMode]; see its docblock for the flush / derive rules).
	 *
	 * @param Set<MeshElement> elements The stored elements, all of [fromMode]'s domain.
	 * @param MeshSelectMode fromMode The current domain.
	 * @param MeshSelectMode newMode The target domain.
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @return Set<MeshElement> The converted set in [newMode]'s domain.
	 */
	private fun convertElements(
		elements: Set<MeshElement>,
		fromMode: MeshSelectMode,
		newMode: MeshSelectMode,
		triangleIndices: IntArray,
	): Set<MeshElement> =
		when (newMode) {
			MeshSelectMode.Vertex ->
				MeshTopology.coveredVertexIndices(elements, triangleIndices)
					.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }
					.toSet()

			MeshSelectMode.Edge ->
				when (fromMode) {
					MeshSelectMode.Vertex ->
						MeshTopology.edgesWithBothEndpointsSelected(
							triangleIndices,
							MeshTopology.coveredVertexIndices(elements, triangleIndices),
						)

					MeshSelectMode.Face ->
						elements
							.filterIsInstance<MeshElement.Face>()
							.flatMap { face -> MeshTopology.edgesOfTriangle(triangleIndices, face.triangleIndex) }
							.toSet()

					// Unreachable: the same-mode case returned above; kept for exhaustiveness.
					MeshSelectMode.Edge -> elements
				}

			MeshSelectMode.Face ->
				when (fromMode) {
					MeshSelectMode.Vertex ->
						MeshTopology.facesWithAllVerticesSelected(
							triangleIndices,
							MeshTopology.coveredVertexIndices(elements, triangleIndices),
						)

					MeshSelectMode.Edge ->
						MeshTopology.facesWithAllEdgesSelected(
							triangleIndices,
							elements.filterIsInstance<MeshElement.Edge>().toSet(),
						)

					// Unreachable: the same-mode case returned above; kept for exhaustiveness.
					MeshSelectMode.Face -> elements
				}
		}

	/**
	 * Every element of a select mode's domain over a mesh: all vertices, all unique edges, or all faces.
	 * The full set [selectAll] fills and [invert] complements against.  The vertex count comes from the
	 * mesh because an isolated vertex (one in no triangle) is still a valid element that the triangle
	 * indices alone would miss.
	 *
	 * @param MeshSelectMode mode The domain to enumerate.
	 * @param DrawableMesh mesh The mesh whose domain to enumerate.
	 * @return Set<MeshElement> Every element of [mode]'s domain.
	 */
	private fun allElements(mode: MeshSelectMode, mesh: DrawableMesh): Set<MeshElement> =
		when (mode) {
			MeshSelectMode.Vertex -> (0 until mesh.vertexCount).map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet<MeshElement>()
			MeshSelectMode.Edge -> MeshTopology.uniqueEdges(mesh.indices).toSet<MeshElement>()
			MeshSelectMode.Face -> (0 until mesh.triangleCount).map { triangleIndex -> MeshElement.Face(triangleIndex) }.toSet<MeshElement>()
		}

	/**
	 * Selects every element of the current select mode's domain on every session mesh (Blender's Select
	 * All).  The active element is kept (it is necessarily still selected); from an empty selection it
	 * stays null.  A session mesh the provider cannot resolve contributes nothing.
	 *
	 * @param MeshSelection selection The selection to fill.
	 * @param Function meshOf Resolves a session drawable's mesh, or null when absent.
	 * @return MeshSelection The selection holding every element of its domain on every session mesh.
	 */
	fun selectAll(selection: MeshSelection, meshOf: (DrawableId) -> DrawableMesh?): MeshSelection {
		val filled = HashMap<DrawableId, Set<MeshElement>>()
		for (drawableId in selection.drawableIds) {
			val mesh = meshOf(drawableId) ?: continue
			val all = allElements(selection.selectMode, mesh)
			if (all.isNotEmpty()) {
				filled[drawableId] = all
			}
		}
		val active = selection.activeElement?.takeIf { active -> active.element in (filled[active.drawableId] ?: emptySet()) }
		return selection.copy(elementsByDrawable = filled, activeElement = active)
	}

	/**
	 * Inverts the selection within the current select mode's domain on every session mesh (Blender's
	 * Ctrl+I): every element not currently selected becomes selected and vice versa.  The active element
	 * drops unless it happens to remain selected (in practice it drops - it was selected before).
	 *
	 * @param MeshSelection selection The selection to invert.
	 * @param Function meshOf Resolves a session drawable's mesh, or null when absent.
	 * @return MeshSelection The inverted selection.
	 */
	fun invert(selection: MeshSelection, meshOf: (DrawableId) -> DrawableMesh?): MeshSelection {
		val inverted = HashMap<DrawableId, Set<MeshElement>>()
		for (drawableId in selection.drawableIds) {
			val mesh = meshOf(drawableId) ?: continue
			val complement = allElements(selection.selectMode, mesh) - selection.elementsOf(drawableId)
			if (complement.isNotEmpty()) {
				inverted[drawableId] = complement
			}
		}
		val active = selection.activeElement?.takeIf { active -> active.element in (inverted[active.drawableId] ?: emptySet()) }
		return selection.copy(elementsByDrawable = inverted, activeElement = active)
	}

	/**
	 * Unions a connectivity island's elements into one mesh's selection, in the CURRENT select mode's
	 * domain - Blender's Select Linked (L): every vertex of the island in vertex mode, every edge with
	 * both endpoints in the island in edge mode, every face with all vertices in the island in face
	 * mode.  Additive by design (repeated L grows the selection island by island); the active element
	 * is left untouched (still a member - the union only adds).
	 *
	 * @param MeshSelection selection The selection to extend.
	 * @param DrawableId drawableId The mesh the island lives in.
	 * @param Set<Int> islandVertices The island's vertex indices (a [MeshTopology.connectedVertices] result).
	 * @param IntArray triangleIndices The mesh triangle vertex indices (three per triangle).
	 * @return MeshSelection The selection with the island's elements added.
	 */
	fun selectLinked(
		selection: MeshSelection,
		drawableId: DrawableId,
		islandVertices: Set<Int>,
		triangleIndices: IntArray,
	): MeshSelection {
		val islandElements: Set<MeshElement> =
			when (selection.selectMode) {
				MeshSelectMode.Vertex -> islandVertices.map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet()
				MeshSelectMode.Edge -> MeshTopology.edgesWithBothEndpointsSelected(triangleIndices, islandVertices)
				MeshSelectMode.Face -> MeshTopology.facesWithAllVerticesSelected(triangleIndices, islandVertices)
			}
		if (islandElements.isEmpty()) {
			return selection
		}
		return selection.copy(
			elementsByDrawable = withElements(selection, drawableId, selection.elementsOf(drawableId) + islandElements),
		)
	}

	/**
	 * Reports whether every selected element still exists in its mesh: vertex indices within the vertex
	 * count, edges present in the mesh's edge set, and face ordinals within the triangle count - across
	 * every session mesh.  Used to decide whether a stashed selection may be restored (re-entering Edit
	 * mode) after the meshes may have changed underneath it.  A session mesh the provider cannot resolve
	 * fails the check.
	 *
	 * @param MeshSelection selection The selection to validate.
	 * @param Function meshOf Resolves a session drawable's mesh, or null when absent.
	 * @return Boolean True when every element still fits its mesh.
	 */
	fun fitsWithinMeshes(selection: MeshSelection, meshOf: (DrawableId) -> DrawableMesh?): Boolean {
		for ((drawableId, elements) in selection.elementsByDrawable) {
			val mesh = meshOf(drawableId) ?: return false
			// Built lazily: vertex- and face-only selections never pay for the edge scan.
			val meshEdges by lazy { MeshTopology.uniqueEdges(mesh.indices).toSet() }
			for (element in elements) {
				val fits =
					when (element) {
						is MeshElement.Vertex -> element.index in 0 until mesh.vertexCount
						is MeshElement.Edge -> element in meshEdges
						is MeshElement.Face -> element.triangleIndex in 0 until mesh.triangleCount
					}
				if (!fits) {
					return false
				}
			}
		}
		return true
	}
}