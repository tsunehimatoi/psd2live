package org.umamo.edit

import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * A selectable rig entity, addressed by its typed id. A closed taxonomy (part, drawable, or deformer)
 * so a `when` over a selection is exhaustive; adding a fourth selectable kind becomes a compile error
 * until handled everywhere. This is object-mode selection — whole entities, not their interior mesh
 * vertices or deformer control points (those belong to Edit mode, see [EditorMode]).
 */
sealed interface SelectionTarget {
	/** An organizational tree part. */
	data class Part(val id: PartId) : SelectionTarget

	/** A textured drawable mesh. */
	data class Drawable(val id: DrawableId) : SelectionTarget

	/** A warp or rotation deformer. */
	data class Deformer(val id: DeformerId) : SelectionTarget
}

/**
 * An immutable object-mode selection: the set of selected targets plus the active (primary) one — the
 * last target the user added, which the Inspector features when several are selected and which anchors
 * future range operations. The active target is always a member of [targets], or null when the
 * selection is empty.
 */
data class Selection(
	/** The selected targets, empty when nothing is selected. */
	val targets: Set<SelectionTarget> = emptySet(),
	/** The primary target (last added), or null when [targets] is empty. */
	val active: SelectionTarget? = null,
) {
	/** True when nothing is selected. */
	val isEmpty: Boolean get() = targets.isEmpty()

	/** The number of selected targets. */
	val size: Int get() = targets.size

	/**
	 * Reports whether the given target is part of this selection.
	 *
	 * @param SelectionTarget target The target to test.
	 * @return Boolean True when [target] is selected.
	 */
	operator fun contains(target: SelectionTarget): Boolean = target in targets
}

/**
 * The parameters currently targeted for keyform authoring: the selected set plus the active (primary) one.
 *
 * Separate from [Selection] because it addresses a different axis of the document - you pick WHICH OBJECT
 * to key from the object selection, and WHICH PARAMETER to key it on from here, and the two move
 * independently.  Held on the session rather than a panel's view state because it is shared: a keyform
 * sheet in one area follows the parameter chosen in a parameters panel in another.
 */
data class ParameterSelection(
	/** The selected parameters, empty when none is targeted. */
	val ids: Set<ParameterId> = emptySet(),
	/** The primary parameter (last added), or null when [ids] is empty. */
	val active: ParameterId? = null,
) {
	/** True when no parameter is targeted. */
	val isEmpty: Boolean get() = ids.isEmpty()

	/**
	 * Reports whether the given parameter is selected.
	 *
	 * @param ParameterId id The parameter to test.
	 * @return Boolean True when [id] is selected.
	 */
	operator fun contains(id: ParameterId): Boolean = id in ids

	companion object {
		/**
		 * A selection of just [id], the plain-click result.
		 *
		 * @param ParameterId id The sole parameter to select.
		 * @return ParameterSelection A selection holding only [id], with it active.
		 */
		fun of(id: ParameterId): ParameterSelection = ParameterSelection(setOf(id), id)
	}

	/**
	 * This selection with [id] toggled (a Shift or primary-modifier click, matching [SelectionOps.toggle]).
	 *
	 * @param ParameterId id The parameter to toggle.
	 * @return ParameterSelection The resulting selection.
	 */
	fun toggled(id: ParameterId): ParameterSelection =
		if (id in ids) {
			val remaining = ids - id
			ParameterSelection(remaining, remaining.lastOrNull())
		} else {
			ParameterSelection(ids + id, id)
		}

	/**
	 * This selection with every parameter not in [surviving] dropped - applied after a parameter is
	 * deleted, so the target can never dangle on an id the model no longer has.
	 *
	 * @param Set<ParameterId> surviving The parameter ids still present in the model.
	 * @return ParameterSelection The pruned selection, or this when nothing was dropped.
	 */
	fun prunedTo(surviving: Set<ParameterId>): ParameterSelection {
		val kept = ids intersect surviving
		return if (kept.size == ids.size) this else ParameterSelection(kept, active?.takeIf { it in kept } ?: kept.lastOrNull())
	}
}

/**
 * Pure transformations over a [Selection], one per selection gesture. Each returns a new immutable
 * [Selection] and never mutates its input, so they are trivially unit-testable without Compose or a
 * render context. The active target is maintained per the [Selection] invariant: it is always a member
 * of the result's targets, or null when the result is empty.
 */
object SelectionOps {
	/**
	 * Replaces the whole selection with a single target (a plain click).
	 *
	 * @param SelectionTarget target The sole target to select.
	 * @return Selection A selection holding only [target], with it active.
	 */
	fun replace(target: SelectionTarget): Selection = Selection(setOf(target), target)

	/**
	 * Toggles a target's membership (a Shift or Ctrl / Cmd click, Blender-style): removes it when
	 * present, adds it otherwise. When added it becomes active; when removed the active target falls
	 * back to another remaining member, or null if the selection became empty.
	 *
	 * @param Selection selection The selection to modify.
	 * @param SelectionTarget target The target to toggle.
	 * @return Selection The resulting selection.
	 */
	fun toggle(selection: Selection, target: SelectionTarget): Selection =
		if (target in selection.targets) {
			val remaining = selection.targets - target
			Selection(remaining, remaining.lastOrNull())
		} else {
			Selection(selection.targets + target, target)
		}

	/**
	 * Adds a target without removing anything (an additive gesture with no current default binding),
	 * making it active. Adding a target already present simply promotes it to active.
	 *
	 * @param Selection selection The selection to extend.
	 * @param SelectionTarget target The target to add.
	 * @return Selection The extended selection.
	 */
	fun add(selection: Selection, target: SelectionTarget): Selection = Selection(selection.targets + target, target)

	/**
	 * Clears the selection.
	 *
	 * @return Selection An empty selection.
	 */
	fun clear(): Selection = Selection()

	/**
	 * Every selectable entity in the model as a target set, in parts-then-deformers-then-drawables order.
	 * A locked entity (isSelectable == false) is excluded, matching the pointer picker.  The full set
	 * [selectAll] fills and [invert] complements against.
	 *
	 * @param PuppetModel model The rig to enumerate.
	 * @return Set<SelectionTarget> Every selectable target.
	 */
	private fun allSelectableTargets(model: PuppetModel): Set<SelectionTarget> {
		val targets = LinkedHashSet<SelectionTarget>()
		for (part in model.parts) {
			if (part.isSelectable) {
				targets.add(SelectionTarget.Part(part.id))
			}
		}
		for (deformer in model.deformers) {
			if (deformer.isSelectable) {
				targets.add(SelectionTarget.Deformer(deformer.id))
			}
		}
		for (drawable in model.drawables) {
			if (drawable.isSelectable) {
				targets.add(SelectionTarget.Drawable(drawable.id))
			}
		}
		return targets
	}

	/**
	 * Selects every selectable entity in the model (Blender's Select All in object mode).  Keeps the current
	 * active target (it is necessarily still selected); with none, the last enumerated target becomes active.
	 *
	 * @param Selection selection The current selection (its active target is preserved when possible).
	 * @param PuppetModel model The rig whose entities to select.
	 * @return Selection The selection holding every selectable target.
	 */
	fun selectAll(selection: Selection, model: PuppetModel): Selection {
		val allTargets = allSelectableTargets(model)
		val active = selection.active?.takeIf { target -> target in allTargets } ?: allTargets.lastOrNull()
		return Selection(allTargets, active)
	}

	/**
	 * Inverts the object selection (Blender's Ctrl+I): every selectable entity not currently selected
	 * becomes selected and vice versa.  The active target is kept only when it survives the inversion, which
	 * in practice drops it (a selected active target is deselected by the inversion).
	 *
	 * @param Selection selection The selection to invert.
	 * @param PuppetModel model The rig whose entities define the domain.
	 * @return Selection The inverted selection.
	 */
	fun invert(selection: Selection, model: PuppetModel): Selection {
		val inverted = allSelectableTargets(model) - selection.targets
		return Selection(inverted, selection.active?.takeIf { target -> target in inverted })
	}

	/**
	 * Expands every selected target to its outliner subtree (the Select Hierarchy context action): a
	 * part adds its descendant parts and their drawables, a deformer its descendant deformers, a
	 * drawable just itself - exactly the rows the outliner shows beneath each (see
	 * [PuppetModel.subtreeTargets]).  The active target is kept (still selected); an empty selection
	 * stays empty.
	 *
	 * @param Selection selection The selection whose targets to expand.
	 * @param PuppetModel model The rig the subtrees are read from.
	 * @return Selection The selection with every subtree included.
	 */
	fun selectHierarchy(selection: Selection, model: PuppetModel): Selection {
		if (selection.isEmpty) {
			return selection
		}
		val expanded = LinkedHashSet<SelectionTarget>()
		for (target in selection.targets) {
			expanded.addAll(model.subtreeTargets(target))
		}
		return Selection(expanded, selection.active ?: expanded.lastOrNull())
	}
}