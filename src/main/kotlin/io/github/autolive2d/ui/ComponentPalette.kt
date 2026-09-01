package io.github.autolive2d.ui

import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList

/** Stable colors keep one component recognizable across hierarchy, topology and the layer table. */
object ComponentPalette {
	private val colors = arrayOf(
		Color(87, 181, 255),
		Color(255, 153, 111),
		Color(112, 214, 174),
		Color(202, 154, 255),
		Color(255, 207, 102),
		Color(104, 207, 226),
		Color(245, 132, 180),
		Color(157, 205, 104),
		Color(143, 157, 255),
		Color(244, 174, 92),
		Color(116, 220, 211),
		Color(223, 145, 241),
	)

	fun strong(key: String): Color = colors[Math.floorMod(key.hashCode(), colors.size)]

	fun pale(key: String, background: Color = Color.WHITE): Color = mix(background, strong(key), 0.16f)

	fun selected(key: String): Color = mix(Color.WHITE, strong(key), 0.42f)

	private fun mix(base: Color, tint: Color, amount: Float): Color = Color(
		(base.red + (tint.red - base.red) * amount).toInt().coerceIn(0, 255),
		(base.green + (tint.green - base.green) * amount).toInt().coerceIn(0, 255),
		(base.blue + (tint.blue - base.blue) * amount).toInt().coerceIn(0, 255),
	)
}

class ComponentSelectionModel {
	private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()

	var selectedLayerId: String? = null
		private set

	fun select(layerId: String?) {
		if (selectedLayerId == layerId) return
		selectedLayerId = layerId
		listeners.forEach { it(layerId) }
	}

	fun addListener(listener: (String?) -> Unit) {
		listeners += listener
	}
}
