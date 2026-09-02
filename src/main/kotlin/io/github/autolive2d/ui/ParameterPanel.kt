package io.github.autolive2d.ui

import io.github.autolive2d.core.RigPreviewModel
import io.github.autolive2d.i18n.tr
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JTextField
import javax.swing.SwingConstants
import kotlin.math.roundToInt

/** Live Cubism parameter monitor with optional slider overrides, modelled after live2dConverter. */
class ParameterPanel(
	private val onOverridesChanged: (Map<ParameterId, Float>) -> Unit,
) : JPanel(BorderLayout(0, 4)) {
	private val searchField = JTextField()
	private val countLabel = JLabel()
	private val resetAllButton = JButton()
	private val rowsPanel = JPanel()
	private val emptyLabel = JLabel("", SwingConstants.CENTER)
	private var parameters: List<Parameter> = emptyList()
	private val rows = linkedMapOf<ParameterId, ParameterRow>()
	private val liveValues = linkedMapOf<ParameterId, Float>()
	private val overrides = linkedMapOf<ParameterId, Float>()
	private var controlsEnabled = true

	val parameterOverrides: Map<ParameterId, Float> get() = overrides.toMap()

	init {
		border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
		rowsPanel.layout = BoxLayout(rowsPanel, BoxLayout.Y_AXIS)
		rowsPanel.background = Color.WHITE
		emptyLabel.foreground = Color(118, 122, 128)
		emptyLabel.border = BorderFactory.createEmptyBorder(20, 6, 20, 6)
		searchField.preferredSize = Dimension(120, 24)
		countLabel.font = countLabel.font.deriveFont(10f)
		resetAllButton.margin = java.awt.Insets(1, 6, 1, 6)

		add(JPanel(BorderLayout(4, 0)).apply {
			isOpaque = false
			add(searchField, BorderLayout.CENTER)
			add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
				isOpaque = false
				add(countLabel)
				add(resetAllButton)
			}, BorderLayout.EAST)
		}, BorderLayout.NORTH)
		add(JScrollPane(rowsPanel).apply {
			border = BorderFactory.createLineBorder(Color(220, 223, 228))
			verticalScrollBar.unitIncrement = 14
			viewport.background = Color.WHITE
		}, BorderLayout.CENTER)

		searchField.addKeyListener(object : KeyAdapter() {
			override fun keyReleased(event: KeyEvent) = applyFilter()
		})
		resetAllButton.addActionListener { resetAll() }
		refreshTexts()
		rebuildRows()
	}

	fun setPreviewModel(model: RigPreviewModel?) {
		setParameters(model?.rig?.puppet?.parameters.orEmpty())
	}

	fun setParameters(next: List<Parameter>) {
		val nextIds = next.mapTo(linkedSetOf()) { it.id }
		overrides.keys.retainAll(nextIds)
		liveValues.keys.retainAll(nextIds)
		parameters = next
		rebuildRows()
		publishOverrides()
	}

	fun updateLiveValues(values: Map<ParameterId, Float>) {
		if (values.isEmpty() || parameters.isEmpty()) return
		for ((id, value) in values) {
			if (rows.containsKey(id)) liveValues[id] = value
		}
		for ((id, row) in rows) row.showValue(overrides[id] ?: liveValues[id] ?: row.parameter.default, id in overrides)
	}

	fun clear() {
		parameters = emptyList()
		rows.clear()
		liveValues.clear()
		overrides.clear()
		rebuildRows()
		publishOverrides()
	}

	fun setControlsEnabled(enabled: Boolean) {
		controlsEnabled = enabled
		searchField.isEnabled = enabled
		resetAllButton.isEnabled = enabled && parameters.isNotEmpty()
		rows.values.forEach { it.setControlsEnabled(enabled) }
	}

	fun refreshTexts() {
		searchField.toolTipText = tr("parameters.search.tip")
		searchField.putClientProperty("JTextField.placeholderText", tr("parameters.search"))
		resetAllButton.text = tr("parameters.resetAll")
		resetAllButton.toolTipText = tr("parameters.resetAll.tip")
		emptyLabel.text = tr("parameters.empty")
		updateCount()
		rows.values.forEach(ParameterRow::refreshTexts)
	}

	private fun rebuildRows() {
		rowsPanel.removeAll()
		rows.clear()
		if (parameters.isEmpty()) {
			rowsPanel.add(emptyLabel)
			rowsPanel.add(Box.createVerticalGlue())
		} else {
			for (parameter in parameters) {
				val row = ParameterRow(parameter)
				rows[parameter.id] = row
				rowsPanel.add(row)
			}
			rowsPanel.add(Box.createVerticalGlue())
		}
		applyFilter()
		rowsPanel.revalidate()
		rowsPanel.repaint()
		updateCount()
	}

	private fun applyFilter() {
		val query = searchField.text.trim().lowercase()
		for ((_, row) in rows) {
			row.isVisible = query.isEmpty() || row.parameter.id.raw.lowercase().contains(query) ||
				row.parameter.name.lowercase().contains(query)
		}
		rowsPanel.revalidate()
		rowsPanel.repaint()
		updateCount()
	}

	private fun updateCount() {
		val shown = rows.values.count { it.isVisible }
		countLabel.text = tr("parameters.count", shown, parameters.size, overrides.size)
		resetAllButton.isEnabled = controlsEnabled && parameters.isNotEmpty()
	}

	fun resetAll() {
		for (parameter in parameters) {
			overrides[parameter.id] = parameter.default
		}
		for ((id, row) in rows) row.showValue(row.parameter.default, true)
		publishOverrides()
	}

	fun reset(parameter: Parameter) {
		overrides[parameter.id] = parameter.default
		rows[parameter.id]?.showValue(parameter.default, true)
		publishOverrides()
	}

	fun setOverride(parameter: Parameter, value: Float) {
		overrides[parameter.id] = value.coerceIn(parameter.min, parameter.max)
		publishOverrides()
	}

	private fun publishOverrides() {
		updateCount()
		onOverridesChanged(overrides.toMap())
	}

	private inner class ParameterRow(val parameter: Parameter) : JPanel(BorderLayout(5, 1)) {
		private val nameLabel = JLabel(parameter.name)
		private val idLabel = JLabel(parameter.id.raw)
		private val valueLabel = JLabel("", SwingConstants.RIGHT)
		private val minLabel = JLabel(format(parameter.min))
		private val maxLabel = JLabel(format(parameter.max), SwingConstants.RIGHT)
		private val slider = JSlider(0, SLIDER_STEPS)
		private val resetButton = JButton("↺")
		private var suppress = false

		init {
			background = Color.WHITE
			border = BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, Color(232, 234, 238)),
				BorderFactory.createEmptyBorder(3, 6, 3, 5),
			)
			maximumSize = Dimension(Int.MAX_VALUE, 48)
			preferredSize = Dimension(340, 46)
			nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 11f)
			idLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 9)
			idLabel.foreground = Color(116, 121, 128)
			valueLabel.font = Font(Font.MONOSPACED, Font.BOLD, 10)
			valueLabel.preferredSize = Dimension(50, 18)
			minLabel.font = minLabel.font.deriveFont(8f)
			maxLabel.font = maxLabel.font.deriveFont(8f)
			minLabel.foreground = Color(132, 136, 142)
			maxLabel.foreground = Color(132, 136, 142)
			resetButton.margin = java.awt.Insets(0, 3, 0, 3)
			resetButton.preferredSize = Dimension(23, 18)
			resetButton.isFocusable = false
			slider.preferredSize = Dimension(120, 18)

			add(JPanel(BorderLayout(4, 0)).apply {
				isOpaque = false
				add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
					isOpaque = false
					add(nameLabel)
					add(idLabel)
				}, BorderLayout.CENTER)
				add(JPanel(FlowLayout(FlowLayout.RIGHT, 3, 0)).apply {
					isOpaque = false
					add(valueLabel)
					add(resetButton)
				}, BorderLayout.EAST)
			}, BorderLayout.NORTH)
			add(JPanel(BorderLayout(3, 0)).apply {
				isOpaque = false
				minLabel.preferredSize = Dimension(28, 14)
				maxLabel.preferredSize = Dimension(28, 14)
				add(minLabel, BorderLayout.WEST)
				add(slider, BorderLayout.CENTER)
				add(maxLabel, BorderLayout.EAST)
			}, BorderLayout.CENTER)

			slider.addChangeListener {
				if (suppress) return@addChangeListener
				val value = sliderToValue(slider.value)
				showValue(value, true, updateSlider = false)
				setOverride(parameter, value)
			}
			resetButton.addActionListener { reset(parameter) }
			showValue(overrides[parameter.id] ?: liveValues[parameter.id] ?: parameter.default, parameter.id in overrides)
			setControlsEnabled(controlsEnabled)
			refreshTexts()
		}

		fun showValue(value: Float, overridden: Boolean, updateSlider: Boolean = true) {
			val clamped = value.coerceIn(parameter.min, parameter.max)
			if (updateSlider) {
				suppress = true
				try {
					slider.value = valueToSlider(clamped)
				} finally {
					suppress = false
				}
			}
			valueLabel.text = format(clamped)
			valueLabel.foreground = if (overridden) Color(42, 102, 184) else Color(43, 46, 51)
			resetButton.isVisible = overridden
		}

		fun setControlsEnabled(enabled: Boolean) {
			slider.isEnabled = enabled
			resetButton.isEnabled = enabled
		}

		fun refreshTexts() {
			slider.toolTipText = tr("parameters.slider.tip", parameter.id.raw)
			resetButton.toolTipText = tr("parameters.reset.tip")
		}

		private fun valueToSlider(value: Float): Int {
			val range = parameter.max - parameter.min
			if (range <= 0f) return 0
			return (((value - parameter.min) / range) * SLIDER_STEPS).roundToInt().coerceIn(0, SLIDER_STEPS)
		}

		private fun sliderToValue(value: Int): Float =
			parameter.min + (parameter.max - parameter.min) * (value.toFloat() / SLIDER_STEPS)
	}

	private fun format(value: Float): String = when {
		kotlin.math.abs(value) >= 10f -> String.format(java.util.Locale.ROOT, "%.1f", value)
		else -> String.format(java.util.Locale.ROOT, "%.2f", value)
	}

	private companion object {
		const val SLIDER_STEPS = 1000
	}
}
