package org.umamo.interop.cmo3

import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * CMO3 stores draw orders as whole numbers, while the ruler and the inspector take fractions (38.5 to slip a
 * mesh between 38 and 39). The keyform lowering refuses a fractional order and leaves the owner as it found
 * it, which in a fresh file is a source with no keyform at all. So the export copy renumbers every order
 * (drawable and part, static, keyed and blend-shape) through one increasing map onto whole numbers: the
 * stacking stays exactly as the editor shows it, whole orders keep their number where a gap allows, and a
 * fraction takes the nearest free whole number.
 */
internal object Cmo3DrawOrders {
	class Result(val puppet: PuppetModel, val renumbered: List<String>)

	fun integral(puppet: PuppetModel): Result {
		val values = sortedSetOf<Float>()
		for (drawable in puppet.drawables) {
			values.add(drawable.drawOrder)
			values.addAll(keyed(drawable.channelGrids))
			drawable.blendShapes.forEach { binding -> binding.forms.forEach { form -> form?.let { values.add(it.drawOrder) } } }
		}
		for (part in puppet.parts) {
			values.add(part.drawOrder.toFloat())
			values.addAll(keyed(part.channelGrids))
			part.blendShapes.forEach { binding -> binding.forms.forEach { form -> form?.let { values.add(it.drawOrder) } } }
		}
		if (values.all { it == floor(it) }) return Result(puppet, emptyList())
		val map = renumbering(values.toList())

		val renumbered = ArrayList<String>()
		val drawables = puppet.drawables.map { drawable ->
			val order = map.getValue(drawable.drawOrder)
			val grids = remap(drawable.channelGrids, map)
			val shapes = drawable.blendShapes.map { binding -> remapForms(binding, map) { form, to ->
				if (form.drawOrder == to(form.drawOrder)) form
				else MeshForm(form.positionDeltas, to(form.drawOrder), form.opacity, form.multiplyColor, form.screenColor)
			} }
			if (order == drawable.drawOrder && grids === drawable.channelGrids && shapes == drawable.blendShapes) drawable
			else drawable.copy(drawOrder = order, channelGrids = grids, blendShapes = shapes).also { renumbered.add(drawable.name) }
		}
		val parts = puppet.parts.map { part ->
			val order = map.getValue(part.drawOrder.toFloat()).toInt()
			val grids = remap(part.channelGrids, map)
			val shapes = part.blendShapes.map { binding -> remapForms(binding, map) { form, to ->
				if (form.drawOrder == to(form.drawOrder)) form
				else PartForm(to(form.drawOrder), form.opacity, form.multiplyColor, form.screenColor)
			} }
			if (order == part.drawOrder && grids === part.channelGrids && shapes == part.blendShapes) part
			else part.copy(drawOrder = order, channelGrids = grids, blendShapes = shapes).also { renumbered.add(part.name) }
		}
		return Result(puppet.copy(drawables = drawables, parts = parts).withDerivedRenderRoot(), renumbered)
	}

	/**
	 * An increasing map of [sorted] (distinct, ascending) onto 0..1000. A whole order keeps its number unless
	 * the one below took it; a fraction takes its rounding, or its other neighbour when the rounding is the
	 * next order's own number. When that runs past 1000 the orders are ranked instead, and with more than
	 * 1001 of them rounding is all that is left.
	 */
	internal fun renumbering(sorted: List<Float>): Map<Float, Float> {
		fun preferred(value: Float) = if (value == floor(value)) value.toInt() else value.roundToInt()
		val mapped = IntArray(sorted.size)
		for ((index, value) in sorted.withIndex()) {
			val low = if (index == 0) 0 else mapped[index - 1] + 1
			val next = sorted.getOrNull(index + 1)?.let(::preferred)
			mapped[index] = if (value == floor(value)) maxOf(value.toInt(), low) else
				listOf(value.roundToInt(), floor(value).toInt(), ceil(value).toInt())
					.firstOrNull { it >= low && (next == null || it < next) } ?: maxOf(value.roundToInt(), low)
		}
		val whole = when {
			mapped.last() <= 1000 -> mapped.toList()
			sorted.size <= 1001 -> sorted.indices.toList()
			else -> sorted.map { it.roundToInt().coerceIn(0, 1000) }
		}
		return sorted.indices.associate { sorted[it] to whole[it].toFloat() }
	}

	private fun keyed(grids: ChannelGrids): List<Float> =
		grids[FormChannel.DRAW_ORDER]?.cells?.mapNotNull { (it.form as? ChannelValue.Scalar)?.value }.orEmpty()

	private fun remap(grids: ChannelGrids, map: Map<Float, Float>): ChannelGrids {
		val track = grids[FormChannel.DRAW_ORDER] ?: return grids
		if (track.cells.all { cell -> (cell.form as? ChannelValue.Scalar)?.let { map.getValue(it.value) == it.value } != false }) return grids
		val cells = track.cells.map { cell ->
			val scalar = cell.form as? ChannelValue.Scalar ?: return@map cell
			KeyformCell<ChannelValue>(cell.coordinate, ChannelValue.Scalar(map.getValue(scalar.value)))
		}
		return ChannelGrids(grids.gridsByChannel + (FormChannel.DRAW_ORDER to KeyformGrid(track.axes, cells)))
	}

	private fun <T : Any> remapForms(binding: BlendShapeBinding<T>, map: Map<Float, Float>,
	                                 rewrite: (T, (Float) -> Float) -> T): BlendShapeBinding<T> {
		val forms = binding.forms.map { form -> form?.let { rewrite(it, map::getValue) } }
		return if (forms.indices.all { forms[it] === binding.forms[it] }) binding else binding.copy(forms = forms)
	}
}
