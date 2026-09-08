package org.umamo.interop.moc3.import

import org.umamo.format.moc3.model.KeyformBinding
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/*
 * The keyform grid seam, shared by every object kind the import builds: deformers, drawables, glues, and
 * parts all resolve a binding index into a grid the same way, and a second implementation of the stride
 * folding would put two objects on the same axes at different cell coordinates.
 */

/**
 * Builds a runtime keyform grid over [binding], one cell per grid index.
 *
 * A static object (a zero-axis binding) becomes a zero-axis single-cell grid, which the evaluator
 * resolves to a single full-weight corner - keeping the baked draw order/opacity that a null grid would
 * lose.
 *
 * @param Moc3ImportContext context The import's derived state, for the parameter id table.
 * @param KeyformBinding?   binding The object's keyform binding.
 * @param Function          formAt  The typed form payload at a grid index, or null to skip the cell.
 * @return KeyformGrid<TForm>? The grid, or null when there is no binding.
 */
internal fun <TForm : Any> gridOf(
	context: Moc3ImportContext,
	binding: KeyformBinding?,
	formAt: (gridIndex: Int) -> TForm?,
): KeyformGrid<TForm>? {
	if (binding == null) {
		return null
	}
	// MOC3 §5.6: axes are in stride order (first = fastest varying), matching the runtime grid's
	// stride folding, so keyIndices(gridIndex) is the cell coordinate as-is.
	val axes =
		binding.axes.map { axis ->
			KeyformAxis(
				parameterId = context.parameterIds.getOrElse(axis.parameterIndex) { ParameterId("") },
				keys = axis.keyPositions.copyOf(),
			)
		}
	val cells =
		(0 until binding.gridSize).mapNotNull { gridIndex ->
			formAt(gridIndex)?.let { form -> KeyformCell(binding.keyIndices(gridIndex), form) }
		}
	return KeyformGrid(axes, cells)
}

/**
 * The grid index of the default-pose cell of [binding]: per axis, the key nearest the driving
 * parameter's default value, stride-folded.
 *
 * This cell's baked values serve as the editor's rest state (rest mesh, static draw order); the
 * multilinear blend is base-independent, so the choice never changes evaluated output - which is also
 * why no evaluation oracle can see it go wrong.
 *
 * Resolves the default by parameter INDEX, not by id.  [Moc3ImportContext.defaultValueOf] answers the
 * same question keyed by id for the blend-shape pass, and on a document carrying one parameter id twice
 * the two disagree; an axis names its parameter positionally, so this is the accessor that matches what
 * the file said.
 *
 * @param Moc3ImportContext context The import's derived state, for the parameter defaults.
 * @param KeyformBinding?   binding The object's keyform binding, or null for a static object.
 * @return Int The default-pose grid index (0 when static or axis data is degenerate).
 */
internal fun defaultCellIndexOf(
	context: Moc3ImportContext,
	binding: KeyformBinding?,
): Int {
	if (binding == null) {
		return 0
	}
	var linearIndex = 0
	var stride = 1
	for (axis in binding.axes) {
		val defaultValue = context.defaultValueAt(axis.parameterIndex)
		var nearestKey = 0
		for (keyIndex in axis.keyPositions.indices) {
			if (abs(axis.keyPositions[keyIndex] - defaultValue) < abs(axis.keyPositions[nearestKey] - defaultValue)) {
				nearestKey = keyIndex
			}
		}
		linearIndex += nearestKey * stride
		stride *= axis.keyCount
	}
	return linearIndex
}