package org.umamo.edit

import org.umamo.runtime.keyform.FormInterpolator
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.withKeyInserted
import org.umamo.runtime.keyform.withKeyMoved
import org.umamo.runtime.keyform.withKeyRemoved
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/*
 * Editing the key POSITIONS of a geometry track.
 *
 * Deliberately separate from KeyformChannelEdits: a channel is addressed by (owner, channel) and its cells
 * hold a ChannelValue, while geometry is a privileged per-owner field whose cell type differs by owner
 * (MeshDeltaForm / WarpLatticeForm / RotationPivotForm).  One generic op cannot span both without erasing
 * that distinction, and erasing it is exactly what would let a scalar track reach buildDeltaTexels.
 *
 * These ops move, insert, and remove KEYS; they never author a form.  Authoring geometry needs the posed
 * deformer-chain inverse and stays deferred - but key positions are ordinary grid algebra, and leaving
 * them uneditable made every geometry row in the keyform sheet silently swallow every gesture, which on a
 * corpus rig is nearly every row on screen.
 */

/**
 * Whether [owner] has a geometry track keyed on [parameterId] at all.
 *
 * @param KeyformOwner owner The entity.
 * @param ParameterId parameterId The parameter to look for.
 * @return Boolean True when a geometry grid exists and keys on that parameter.
 */
fun PuppetModel.isGeometryKeyedOn(owner: KeyformOwner, parameterId: ParameterId): Boolean =
	// Through geometryGridOf, the one owner dispatch - a second copy of the when let the keyed indicator
	// and the actual edit ops disagree about a new owner kind.
	(geometryGridOf(owner)?.axisIndexOf(parameterId) ?: -1) >= 0

/**
 * [owner]'s geometry grid, or null when that kind of entity carries none.
 *
 * The grid rather than one axis, so callers can use the grid's own tolerance-aware lookups instead of
 * re-deriving the evaluator's epsilon beside them.
 *
 * @param KeyformOwner owner The entity.
 * @return KeyformGrid? Its geometry grid, or null.
 */
fun PuppetModel.geometryGridOf(owner: KeyformOwner): KeyformGrid<*>? =
	when (owner) {
		is KeyformOwner.Drawable -> drawableGeometry(owner)
		is KeyformOwner.Deformer ->
			when (val deformer = deformers.firstOrNull { candidate -> candidate.id == owner.id }) {
				is Deformer.Warp -> deformer.geometryGrid
				is Deformer.Rotation -> deformer.geometryGrid
				null -> null
			}
		// A part is organizational and a glue welds two meshes; neither carries geometry of its own.
		is KeyformOwner.Part, is KeyformOwner.Glue -> null
	}

/**
 * This model with [owner]'s geometry key at [fromValue] moved to [toValue] on [parameter]'s axis.
 *
 * Moving a key changes only WHERE on the parameter its form applies - the cells are untouched - so it is
 * safe for every owner, including a deformer with no rest lattice.  The grid clamps at the neighbouring
 * keys, so the stored destination can differ from the requested one.
 *
 * @param KeyformOwner owner The entity whose geometry track to edit.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The requested new position.
 * @return PuppetModel The model with the key moved, or this on a refusal.
 */
fun PuppetModel.withGeometryKeyMoved(
	owner: KeyformOwner,
	parameter: Parameter,
	keyIndex: Int,
	toValue: Float,
): PuppetModel = rewriteGeometry(owner) { grid -> grid.withKeyMoved(parameter.id, keyIndex, toValue) }

/**
 * This model with a key inserted at [position] on [owner]'s geometry track, holding the interpolated form.
 *
 * Shape-preserving: the new slice is the blend of its neighbours, the same operation the export refinement
 * performs, so the deformation through the new key is the deformation that was already there.
 *
 * @param KeyformOwner owner The entity whose geometry track to edit.
 * @param Parameter parameter The parameter whose axis to insert on.
 * @param Float position The new key's parameter value.
 * @return PuppetModel The model with the key inserted, or this on a refusal.
 */
fun PuppetModel.withGeometryKeyInserted(owner: KeyformOwner, parameter: Parameter, position: Float): PuppetModel =
	rewriteGeometry(owner) { grid, interpolator -> grid.withKeyInserted(parameter.id, position, interpolator) }

/**
 * This model with [owner]'s geometry key at [position] removed from [parameter]'s axis.
 *
 * REFUSED for a deformer when the removal would collapse its last axis.  A warp's control points live only
 * in its keyform cells - there is no rest lattice - so an unkeyed deformer is hidden, and silently making a
 * deformer and everything under it vanish is not an acceptable outcome of deleting one key.  A drawable
 * has a rest mesh, so the same removal there is safe and simply returns it to its rest shape.
 *
 * @param KeyformOwner owner The entity whose geometry track to edit.
 * @param Parameter parameter The parameter whose axis to remove from.
 * @param Int keyIndex The key's ordinal on that axis.
 * @return PuppetModel The model with the key removed, or this on a refusal.
 */
fun PuppetModel.withGeometryKeyRemoved(owner: KeyformOwner, parameter: Parameter, keyIndex: Int): PuppetModel =
	rewriteGeometryNullable(owner) { grid ->
		val reduced = grid.withKeyRemoved(parameter.id, keyIndex)
		if (reduced == null && owner is KeyformOwner.Deformer) grid else reduced
	}

/**
 * Applies a same-shaped rewrite to whichever geometry grid [owner] has, keeping the form type.
 *
 * The generic form, for ops whose expression does not depend on what a cell holds.  Returning the receiver
 * means "no change", matching the algebra's own refusal convention.
 *
 * @param KeyformOwner owner The entity.
 * @param Function rewrite Produces the new grid from the current one.
 * @return PuppetModel The rewritten model, or this.
 */
private fun PuppetModel.rewriteGeometry(
	owner: KeyformOwner,
	rewrite: (KeyformGrid<Nothing>) -> KeyformGrid<Nothing>,
): PuppetModel = rewriteGeometryNullable(owner) { grid -> rewrite(grid) }

/**
 * Applies a rewrite that needs the grid's matching [FormInterpolator].
 *
 * A second overload rather than a parameter on the first, because the interpolator's type is tied to the
 * grid's and only the per-owner dispatch below knows both at once.
 *
 * @param KeyformOwner owner The entity.
 * @param Function rewrite Produces the new grid from the current one and its interpolator.
 * @return PuppetModel The rewritten model, or this.
 */
private fun PuppetModel.rewriteGeometry(
	owner: KeyformOwner,
	rewrite: (KeyformGrid<Nothing>, FormInterpolator<Nothing>) -> KeyformGrid<Nothing>,
): PuppetModel =
	when (owner) {
		is KeyformOwner.Drawable ->
			replaceDrawableGeometry(owner) { grid -> rewrite(grid.asNothing(), MeshDeltaInterpolator.asNothing()) }

		is KeyformOwner.Deformer ->
			replaceDeformerGeometry(
				owner = owner,
				onWarp = { grid -> rewrite(grid.asNothing(), WarpLatticeInterpolator.asNothing()) },
				onRotation = { grid -> rewrite(grid.asNothing(), RotationPivotInterpolator.asNothing()) },
			)

		is KeyformOwner.Part, is KeyformOwner.Glue -> this
	}

/**
 * The shared owner dispatch: reads [owner]'s geometry grid, asks [rewrite] for the new one, writes it back.
 *
 * One place that knows which entity kinds carry geometry, so adding one is a compile error here rather
 * than a silently-ignored owner at three call sites.  A null result drops the grid entirely (the entity
 * becomes unkeyed); returning the receiver leaves the model untouched.
 *
 * @param KeyformOwner owner The entity.
 * @param Function rewrite Produces the new grid, the same grid to refuse, or null to drop it.
 * @return PuppetModel The rewritten model, or this.
 */
private fun PuppetModel.rewriteGeometryNullable(
	owner: KeyformOwner,
	rewrite: (KeyformGrid<Nothing>) -> KeyformGrid<Nothing>?,
): PuppetModel =
	when (owner) {
		is KeyformOwner.Drawable -> replaceDrawableGeometry(owner) { grid -> rewrite(grid.asNothing()) }
		is KeyformOwner.Deformer ->
			replaceDeformerGeometry(
				owner = owner,
				onWarp = { grid -> rewrite(grid.asNothing()) },
				onRotation = { grid -> rewrite(grid.asNothing()) },
			)

		is KeyformOwner.Part, is KeyformOwner.Glue -> this
	}

/** This drawable's geometry grid, or null when the id is unknown or it has none. */
private fun PuppetModel.drawableGeometry(owner: KeyformOwner.Drawable) =
	drawables.firstOrNull { candidate -> candidate.id == owner.id }?.geometryGrid

/**
 * Runs [rewrite] over a drawable's mesh-delta grid and writes the result back.
 *
 * @param KeyformOwner.Drawable owner The drawable.
 * @param Function rewrite Produces the new grid, the same one to refuse, or null to drop it.
 * @return PuppetModel The rewritten model, or this.
 */
private fun <TForm> PuppetModel.replaceDrawableGeometry(
	owner: KeyformOwner.Drawable,
	rewrite: (KeyformGrid<TForm>) -> KeyformGrid<TForm>?,
): PuppetModel {
	@Suppress("UNCHECKED_CAST")
	val grid = (drawableGeometry(owner) as KeyformGrid<TForm>?) ?: return this
	val rewritten = rewrite(grid)
	if (rewritten === grid) {
		return this
	}
	@Suppress("UNCHECKED_CAST")
	return copy(
		drawables =
			drawables.map { candidate ->
				if (candidate.id == owner.id) {
					candidate.copy(geometryGrid = rewritten as KeyformGrid<org.umamo.runtime.model.MeshDeltaForm>?)
				} else {
					candidate
				}
			},
	)
}

/**
 * Runs the matching rewrite over a deformer's lattice or pivot grid and writes the result back.
 *
 * @param KeyformOwner.Deformer owner The deformer.
 * @param Function onWarp The rewrite for a warp's lattice grid.
 * @param Function onRotation The rewrite for a rotation's pivot grid.
 * @return PuppetModel The rewritten model, or this.
 */
private fun <TForm> PuppetModel.replaceDeformerGeometry(
	owner: KeyformOwner.Deformer,
	onWarp: (KeyformGrid<TForm>) -> KeyformGrid<TForm>?,
	onRotation: (KeyformGrid<TForm>) -> KeyformGrid<TForm>?,
): PuppetModel {
	val deformer = deformers.firstOrNull { candidate -> candidate.id == owner.id } ?: return this

	@Suppress("UNCHECKED_CAST")
	val grid =
		when (deformer) {
			is Deformer.Warp -> deformer.geometryGrid as KeyformGrid<TForm>?
			is Deformer.Rotation -> deformer.geometryGrid as KeyformGrid<TForm>?
		} ?: return this
	val rewritten =
		when (deformer) {
			is Deformer.Warp -> onWarp(grid)
			is Deformer.Rotation -> onRotation(grid)
		}
	if (rewritten === grid) {
		return this
	}
	@Suppress("UNCHECKED_CAST")
	return copy(
		deformers =
			deformers.map { candidate ->
				when {
					candidate.id != owner.id -> candidate
					candidate is Deformer.Warp ->
						candidate.copy(
							geometryGrid = rewritten as KeyformGrid<org.umamo.runtime.model.WarpLatticeForm>?,
						)

					candidate is Deformer.Rotation ->
						candidate.copy(
							geometryGrid = rewritten as KeyformGrid<org.umamo.runtime.model.RotationPivotForm>?,
						)

					else -> candidate
				}
			},
	)
}

/**
 * Views a grid as one over an unconstrained form type.
 *
 * The ops here move and remove KEYS and never touch a cell, so the form type is genuinely irrelevant to
 * them - but Kotlin has no way to say "any grid" without a type argument.  Confined to this file, and
 * sound because [KeyformGrid] is immutable and no cell is ever read or written through the view.
 */
@Suppress("UNCHECKED_CAST")
private fun <TForm> KeyformGrid<TForm>.asNothing(): KeyformGrid<Nothing> = this as KeyformGrid<Nothing>

/** The [asNothing] counterpart for an interpolator, for the same reason and with the same confinement. */
@Suppress("UNCHECKED_CAST")
private fun <TForm> FormInterpolator<TForm>.asNothing(): FormInterpolator<Nothing> = this as FormInterpolator<Nothing>

/**
 * Moves [owner]'s geometry key at [fromValue] to [toValue], as one undo step.
 *
 * @param KeyformOwner owner The entity.
 * @param Parameter parameter The parameter whose axis the key sits on.
 * @param Int keyIndex The key's ordinal on that axis.
 * @param Float toValue The new position.
 */
fun EditorSession.moveGeometryKey(owner: KeyformOwner, parameter: Parameter, keyIndex: Int, toValue: Float) {
	mutate(KeyformChange.MoveKey(null)) { model -> model.withGeometryKeyMoved(owner, parameter, keyIndex, toValue) }
}

/**
 * Inserts a shape-preserving key at [position] on [owner]'s geometry track, as one undo step.
 *
 * @param KeyformOwner owner The entity.
 * @param Parameter parameter The parameter whose axis to insert on.
 * @param Float position The new key's parameter value.
 */
fun EditorSession.insertGeometryKeyAt(owner: KeyformOwner, parameter: Parameter, position: Float) {
	mutate(KeyformChange.InsertKey(null)) { model -> model.withGeometryKeyInserted(owner, parameter, position) }
}

/**
 * Removes [owner]'s geometry key at [position], as one undo step.
 *
 * @param KeyformOwner owner The entity.
 * @param Parameter parameter The parameter whose axis to remove from.
 * @param Int keyIndex The key's ordinal on that axis.
 */
fun EditorSession.removeGeometryKeyAt(owner: KeyformOwner, parameter: Parameter, keyIndex: Int) {
	mutate(KeyformChange.DeleteKey(null)) { model -> model.withGeometryKeyRemoved(owner, parameter, keyIndex) }
}