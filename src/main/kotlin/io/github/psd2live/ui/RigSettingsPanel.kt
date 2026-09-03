package io.github.psd2live.ui

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.i18n.tr
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.border.TitledBorder
import kotlin.math.roundToInt

class RigSettingsPanel(
	var onSettingsChanged: (() -> Unit)? = null,
) : JPanel(BorderLayout(0, 4)) {

	enum class Preset(
		val titleKey: String,
		val atlasSize: Int,
		val meshSpacing: Int,
		val texturePadding: Int,
		val alphaThreshold: Int,
		val headStrength: Float,
		val bodyStrength: Float,
		val physics: Boolean,
		val cmo3: Boolean,
		val moc3: Boolean,
	) {
		BALANCED("settings.preset.balanced", 4096, 64, 2, 8, 1.0f, 1.0f, true, true, true),
		HIGH_DETAIL("settings.preset.high", 8192, 32, 2, 8, 1.1f, 1.0f, true, true, true),
		PERFORMANCE("settings.preset.fast", 2048, 96, 2, 8, 0.9f, 0.9f, true, true, true),
	}

	private var suppressNotification = false

	// --- Preset & Reset Controls ---
	private val presetLabel = JLabel()
	private val presetBalancedBtn = JButton()
	private val presetHighBtn = JButton()
	private val presetFastBtn = JButton()
	private val resetBtn = JButton()

	// --- Atlas & Mesh Controls ---
	private val atlasSizes = intArrayOf(1024, 2048, 4096, 8192, 16384)
	private val atlasCombo = JComboBox<String>()
	private val atlasSpinner = JSpinner(SpinnerNumberModel(4096, 256, 16384, 256))
	private val atlasLabel = JLabel()

	private val meshLabel = JLabel()
	private val meshSlider = JSlider(16, 128, 64).apply {
		majorTickSpacing = 32
		minorTickSpacing = 8
		snapToTicks = true
	}
	private val meshSpinner = JSpinner(SpinnerNumberModel(64, 16, 256, 8))
	private val meshUnitLabel = JLabel("px")
	private val meshChip32 = JButton("32")
	private val meshChip64 = JButton("64")
	private val meshChip96 = JButton("96")

	// --- Advanced Controls ---
	private val advancedToggleBtn = JButton()
	private var advancedExpanded = false
	private val advancedContainer = JPanel(GridBagLayout())
	private val paddingLabel = JLabel()
	private val paddingSpinner = JSpinner(SpinnerNumberModel(2, 0, 32, 1))
	private val paddingUnitLabel = JLabel("px")
	private val alphaLabel = JLabel()
	private val alphaSpinner = JSpinner(SpinnerNumberModel(8, 0, 128, 1))
	private val alphaUnitLabel = JLabel("/255")

	// --- Motion & Physics Controls ---
	private val headLabel = JLabel()
	private val headSlider = JSlider(0, 400, 100).apply {
		majorTickSpacing = 100
		minorTickSpacing = 25
	}
	private val headSpinner = JSpinner(SpinnerNumberModel(1.0, 0.0, 4.0, 0.05))
	private val headUnitLabel = JLabel("x")

	private val bodyLabel = JLabel()
	private val bodySlider = JSlider(0, 400, 100).apply {
		majorTickSpacing = 100
		minorTickSpacing = 25
	}
	private val bodySpinner = JSpinner(SpinnerNumberModel(1.0, 0.0, 4.0, 0.05))
	private val bodyUnitLabel = JLabel("x")

	private val physicsCheck = JCheckBox("", true)

	// --- Export Target Controls ---
	private val cmo3Check = JCheckBox("", true)
	private val moc3Check = JCheckBox("", true)

	// --- Borders & Containers ---
	private val rootBorder: TitledBorder = BorderFactory.createTitledBorder("")

	val exportCmo3: Boolean get() = cmo3Check.isSelected
	val exportMoc3: Boolean get() = moc3Check.isSelected

	init {
		border = rootBorder
		buildUi()
		bindEvents()
		refreshTexts()
	}

	private fun buildUi() {
		val mainContent = JPanel().apply {
			layout = BoxLayout(this, BoxLayout.Y_AXIS)
			isOpaque = false
		}

		// 1. Toolbar / Presets Header
		val toolbarPanel = JPanel(BorderLayout(4, 0)).apply {
			isOpaque = false
			val leftPresets = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
				isOpaque = false
				add(presetLabel)
				presetBalancedBtn.font = presetBalancedBtn.font.deriveFont(11.5f)
				presetHighBtn.font = presetHighBtn.font.deriveFont(11.5f)
				presetFastBtn.font = presetFastBtn.font.deriveFont(11.5f)
				presetBalancedBtn.margin = Insets(2, 6, 2, 6)
				presetHighBtn.margin = Insets(2, 6, 2, 6)
				presetFastBtn.margin = Insets(2, 6, 2, 6)
				add(presetBalancedBtn)
				add(presetHighBtn)
				add(presetFastBtn)
			}
			val rightReset = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
				isOpaque = false
				resetBtn.font = resetBtn.font.deriveFont(11.5f)
				resetBtn.margin = Insets(2, 8, 2, 8)
				add(resetBtn)
			}
			add(leftPresets, BorderLayout.WEST)
			add(rightReset, BorderLayout.EAST)
		}
		mainContent.add(toolbarPanel)
		mainContent.add(Box.createVerticalStrut(6))

		// 2. Settings Grid (Mesh, Atlas, Motion)
		val formPanel = JPanel(GridBagLayout()).apply {
			isOpaque = false
		}
		val c = GridBagConstraints().apply {
			insets = Insets(2, 4, 2, 4)
			fill = GridBagConstraints.HORIZONTAL
			anchor = GridBagConstraints.CENTER
		}

		var row = 0

		// --- Row: Atlas Size ---
		c.gridy = row
		c.gridx = 0
		c.weightx = 0.0
		c.gridwidth = 1
		atlasLabel.horizontalAlignment = JLabel.RIGHT
		formPanel.add(atlasLabel, c)

		c.gridx = 1
		c.weightx = 1.0
		c.gridwidth = 1
		val atlasBox = JPanel(BorderLayout(4, 0)).apply {
			isOpaque = false
			atlasCombo.preferredSize = Dimension(120, 22)
			atlasSpinner.preferredSize = Dimension(70, 22)
			add(atlasCombo, BorderLayout.CENTER)
			add(atlasSpinner, BorderLayout.EAST)
		}
		formPanel.add(atlasBox, c)

		// --- Row: Mesh Spacing ---
		row++
		c.gridy = row
		c.gridx = 0
		c.weightx = 0.0
		meshLabel.horizontalAlignment = JLabel.RIGHT
		formPanel.add(meshLabel, c)

		c.gridx = 1
		c.weightx = 1.0
		val meshBox = JPanel(BorderLayout(4, 0)).apply {
			isOpaque = false
			meshSlider.preferredSize = Dimension(100, 20)
			val rightVal = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
				isOpaque = false
				meshSpinner.preferredSize = Dimension(52, 22)
				add(meshSpinner)
				meshUnitLabel.foreground = Color(110, 115, 122)
				meshUnitLabel.font = meshUnitLabel.font.deriveFont(11f)
				add(meshUnitLabel)

				fun styleChip(btn: JButton) {
					btn.font = btn.font.deriveFont(10.5f)
					btn.margin = Insets(1, 4, 1, 4)
					btn.preferredSize = Dimension(32, 20)
					btn.isFocusable = false
				}
				styleChip(meshChip32)
				styleChip(meshChip64)
				styleChip(meshChip96)
				add(Box.createHorizontalStrut(2))
				add(meshChip32)
				add(meshChip64)
				add(meshChip96)
			}
			add(meshSlider, BorderLayout.CENTER)
			add(rightVal, BorderLayout.EAST)
		}
		formPanel.add(meshBox, c)

		// --- Row: Head Strength ---
		row++
		c.gridy = row
		c.gridx = 0
		c.weightx = 0.0
		headLabel.horizontalAlignment = JLabel.RIGHT
		formPanel.add(headLabel, c)

		c.gridx = 1
		c.weightx = 1.0
		val headBox = JPanel(BorderLayout(4, 0)).apply {
			isOpaque = false
			headSlider.preferredSize = Dimension(100, 20)
			val rightVal = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
				isOpaque = false
				headSpinner.preferredSize = Dimension(52, 22)
				add(headSpinner)
				headUnitLabel.foreground = Color(110, 115, 122)
				headUnitLabel.font = headUnitLabel.font.deriveFont(11f)
				add(headUnitLabel)
			}
			add(headSlider, BorderLayout.CENTER)
			add(rightVal, BorderLayout.EAST)
		}
		formPanel.add(headBox, c)

		// --- Row: Body Strength ---
		row++
		c.gridy = row
		c.gridx = 0
		c.weightx = 0.0
		bodyLabel.horizontalAlignment = JLabel.RIGHT
		formPanel.add(bodyLabel, c)

		c.gridx = 1
		c.weightx = 1.0
		val bodyBox = JPanel(BorderLayout(4, 0)).apply {
			isOpaque = false
			bodySlider.preferredSize = Dimension(100, 20)
			val rightVal = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
				isOpaque = false
				bodySpinner.preferredSize = Dimension(52, 22)
				add(bodySpinner)
				bodyUnitLabel.foreground = Color(110, 115, 122)
				bodyUnitLabel.font = bodyUnitLabel.font.deriveFont(11f)
				add(bodyUnitLabel)
			}
			add(bodySlider, BorderLayout.CENTER)
			add(rightVal, BorderLayout.EAST)
		}
		formPanel.add(bodyBox, c)

		// --- Row: Advanced toggle & panel ---
		row++
		c.gridy = row
		c.gridx = 0
		c.gridwidth = 2
		c.weightx = 1.0
		val advTogglePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
			isOpaque = false
			advancedToggleBtn.font = advancedToggleBtn.font.deriveFont(11f)
			advancedToggleBtn.margin = Insets(1, 4, 1, 4)
			advancedToggleBtn.isBorderPainted = false
			advancedToggleBtn.isContentAreaFilled = false
			advancedToggleBtn.foreground = Color(52, 101, 164)
			add(advancedToggleBtn)
		}
		formPanel.add(advTogglePanel, c)

		row++
		c.gridy = row
		c.gridx = 0
		c.gridwidth = 2
		c.weightx = 1.0
		buildAdvancedContainer()
		formPanel.add(advancedContainer, c)

		// --- Row: Options & Export Checkboxes ---
		row++
		c.gridy = row
		c.gridx = 0
		c.gridwidth = 2
		c.weightx = 1.0
		val bottomChecks = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
			isOpaque = false
			add(physicsCheck)
			add(cmo3Check)
			add(moc3Check)
		}
		formPanel.add(bottomChecks, c)

		mainContent.add(formPanel)
		add(mainContent, BorderLayout.CENTER)
	}

	private fun buildAdvancedContainer() {
		advancedContainer.removeAll()
		advancedContainer.isOpaque = false
		advancedContainer.isVisible = advancedExpanded
		val ac = GridBagConstraints().apply {
			insets = Insets(2, 4, 2, 4)
			fill = GridBagConstraints.HORIZONTAL
		}

		ac.gridy = 0
		ac.gridx = 0
		ac.weightx = 0.0
		paddingLabel.horizontalAlignment = JLabel.RIGHT
		advancedContainer.add(paddingLabel, ac)

		ac.gridx = 1
		ac.weightx = 1.0
		val padBox = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
			isOpaque = false
			paddingSpinner.preferredSize = Dimension(52, 22)
			add(paddingSpinner)
			paddingUnitLabel.foreground = Color(110, 115, 122)
			paddingUnitLabel.font = paddingUnitLabel.font.deriveFont(11f)
			add(paddingUnitLabel)
		}
		advancedContainer.add(padBox, ac)

		ac.gridx = 2
		ac.weightx = 0.0
		alphaLabel.horizontalAlignment = JLabel.RIGHT
		advancedContainer.add(alphaLabel, ac)

		ac.gridx = 3
		ac.weightx = 1.0
		val alphaBox = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
			isOpaque = false
			alphaSpinner.preferredSize = Dimension(52, 22)
			add(alphaSpinner)
			alphaUnitLabel.foreground = Color(110, 115, 122)
			alphaUnitLabel.font = alphaUnitLabel.font.deriveFont(11f)
			add(alphaUnitLabel)
		}
		advancedContainer.add(alphaBox, ac)
	}

	private fun bindEvents() {
		// Presets
		presetBalancedBtn.addActionListener { applyPreset(Preset.BALANCED) }
		presetHighBtn.addActionListener { applyPreset(Preset.HIGH_DETAIL) }
		presetFastBtn.addActionListener { applyPreset(Preset.PERFORMANCE) }
		resetBtn.addActionListener { resetToDefaults() }

		// Atlas Size Combo & Spinner
		atlasCombo.addActionListener {
			if (suppressNotification) return@addActionListener
			val selectedIdx = atlasCombo.selectedIndex
			if (selectedIdx in atlasSizes.indices) {
				val size = atlasSizes[selectedIdx]
				withSuppression { atlasSpinner.value = size }
				notifyChanged()
			}
		}
		atlasSpinner.addChangeListener {
			if (suppressNotification) return@addChangeListener
			val current = (atlasSpinner.value as Number).toInt()
			val idx = atlasSizes.indexOf(current)
			withSuppression {
				if (idx >= 0) atlasCombo.selectedIndex = idx
				else atlasCombo.selectedIndex = atlasSizes.size // Custom
			}
			notifyChanged()
		}

		// Mesh Spacing Slider & Spinner & Quick Chips
		meshSlider.addChangeListener {
			if (suppressNotification) return@addChangeListener
			val value = meshSlider.value
			withSuppression { meshSpinner.value = value }
			notifyChanged()
		}
		meshSpinner.addChangeListener {
			if (suppressNotification) return@addChangeListener
			val value = (meshSpinner.value as Number).toInt().coerceIn(16, 128)
			withSuppression { meshSlider.value = value }
			notifyChanged()
		}
		meshChip32.addActionListener { setMeshSpacing(32) }
		meshChip64.addActionListener { setMeshSpacing(64) }
		meshChip96.addActionListener { setMeshSpacing(96) }

		// Advanced expand / collapse
		advancedToggleBtn.addActionListener {
			advancedExpanded = !advancedExpanded
			advancedContainer.isVisible = advancedExpanded
			updateAdvancedToggleText()
			revalidate()
			repaint()
		}

		paddingSpinner.addChangeListener { notifyChanged() }
		alphaSpinner.addChangeListener { notifyChanged() }

		// Head strength Slider & Spinner
		headSlider.addChangeListener {
			if (suppressNotification) return@addChangeListener
			val value = headSlider.value / 100.0
			withSuppression { headSpinner.value = value }
			notifyChanged()
		}
		headSpinner.addChangeListener {
			if (suppressNotification) return@addChangeListener
			val value = ((headSpinner.value as Number).toDouble() * 100).roundToInt().coerceIn(0, 400)
			withSuppression { headSlider.value = value }
			notifyChanged()
		}

		// Body strength Slider & Spinner
		bodySlider.addChangeListener {
			if (suppressNotification) return@addChangeListener
			val value = bodySlider.value / 100.0
			withSuppression { bodySpinner.value = value }
			notifyChanged()
		}
		bodySpinner.addChangeListener {
			if (suppressNotification) return@addChangeListener
			val value = ((bodySpinner.value as Number).toDouble() * 100).roundToInt().coerceIn(0, 400)
			withSuppression { bodySlider.value = value }
			notifyChanged()
		}

		// Physics & Export checkboxes
		physicsCheck.addActionListener { notifyChanged() }
		cmo3Check.addActionListener { notifyChanged() }
		moc3Check.addActionListener { notifyChanged() }
	}

	fun applyPreset(preset: Preset) {
		withSuppression {
			val idx = atlasSizes.indexOf(preset.atlasSize)
			if (idx >= 0) atlasCombo.selectedIndex = idx
			else atlasCombo.selectedIndex = atlasSizes.size
			atlasSpinner.value = preset.atlasSize

			meshSlider.value = preset.meshSpacing.coerceIn(16, 128)
			meshSpinner.value = preset.meshSpacing

			paddingSpinner.value = preset.texturePadding
			alphaSpinner.value = preset.alphaThreshold

			headSlider.value = (preset.headStrength * 100).roundToInt()
			headSpinner.value = preset.headStrength.toDouble()

			bodySlider.value = (preset.bodyStrength * 100).roundToInt()
			bodySpinner.value = preset.bodyStrength.toDouble()

			physicsCheck.isSelected = preset.physics
			cmo3Check.isSelected = preset.cmo3
			moc3Check.isSelected = preset.moc3
		}
		notifyChanged()
	}

	fun resetToDefaults() = applyPreset(Preset.BALANCED)

	private fun setMeshSpacing(spacing: Int) {
		withSuppression {
			meshSlider.value = spacing.coerceIn(16, 128)
			meshSpinner.value = spacing
		}
		notifyChanged()
	}

	fun buildConfig(
		layerOverrides: Map<String, LayerClassificationOverride> = emptyMap(),
		layerVisibility: Map<String, Boolean> = emptyMap(),
	): PipelineConfig = PipelineConfig(
		atlasSize = (atlasSpinner.value as Number).toInt(),
		texturePadding = (paddingSpinner.value as Number).toInt(),
		meshSpacing = (meshSpinner.value as Number).toInt(),
		alphaThreshold = (alphaSpinner.value as Number).toInt(),
		headTurnStrength = (headSpinner.value as Number).toFloat(),
		bodyStrength = (bodySpinner.value as Number).toFloat(),
		generatePhysics = physicsCheck.isSelected,
		exportCmo3 = cmo3Check.isSelected,
		exportMoc3 = moc3Check.isSelected,
		layerOverrides = layerOverrides,
		layerVisibility = layerVisibility,
	)

	fun setControlsEnabled(enabled: Boolean) {
		presetBalancedBtn.isEnabled = enabled
		presetHighBtn.isEnabled = enabled
		presetFastBtn.isEnabled = enabled
		resetBtn.isEnabled = enabled
		atlasCombo.isEnabled = enabled
		atlasSpinner.isEnabled = enabled
		meshSlider.isEnabled = enabled
		meshSpinner.isEnabled = enabled
		meshChip32.isEnabled = enabled
		meshChip64.isEnabled = enabled
		meshChip96.isEnabled = enabled
		headSlider.isEnabled = enabled
		headSpinner.isEnabled = enabled
		bodySlider.isEnabled = enabled
		bodySpinner.isEnabled = enabled
		advancedToggleBtn.isEnabled = enabled
		paddingSpinner.isEnabled = enabled
		alphaSpinner.isEnabled = enabled
		physicsCheck.isEnabled = enabled
		cmo3Check.isEnabled = enabled
		moc3Check.isEnabled = enabled
	}

	fun refreshTexts() {
		rootBorder.title = tr("settings.title")

		presetLabel.text = tr("settings.presets")
		presetBalancedBtn.text = tr("settings.preset.balanced")
		presetHighBtn.text = tr("settings.preset.high")
		presetFastBtn.text = tr("settings.preset.fast")
		resetBtn.text = tr("settings.reset")
		resetBtn.toolTipText = tr("settings.reset.tip")

		atlasLabel.text = tr("settings.atlasSize")
		atlasLabel.toolTipText = tr("settings.atlasSize.tip")
		atlasSpinner.toolTipText = tr("settings.atlasSize.tip")

		val atlasItems = atlasSizes.map { "${it} × ${it}" } + tr("settings.atlasCustom")
		val currentIdx = atlasCombo.selectedIndex
		atlasCombo.model = DefaultComboBoxModel(atlasItems.toTypedArray())
		if (currentIdx >= 0 && currentIdx < atlasItems.size) {
			atlasCombo.selectedIndex = currentIdx
		} else {
			val curVal = (atlasSpinner.value as Number).toInt()
			val idx = atlasSizes.indexOf(curVal)
			atlasCombo.selectedIndex = if (idx >= 0) idx else atlasSizes.size
		}

		meshLabel.text = tr("settings.meshSpacing")
		meshLabel.toolTipText = tr("settings.meshSpacing.tip")
		meshSlider.toolTipText = tr("settings.meshSpacing.tip")
		meshSpinner.toolTipText = tr("settings.meshSpacing.tip")
		meshChip32.toolTipText = tr("settings.meshSpacing.fine")
		meshChip64.toolTipText = tr("settings.meshSpacing.standard")
		meshChip96.toolTipText = tr("settings.meshSpacing.fast")

		headLabel.text = tr("settings.headStrength")
		headLabel.toolTipText = tr("settings.headStrength.tip")
		headSlider.toolTipText = tr("settings.headStrength.tip")
		headSpinner.toolTipText = tr("settings.headStrength.tip")

		bodyLabel.text = tr("settings.bodyStrength")
		bodyLabel.toolTipText = tr("settings.bodyStrength.tip")
		bodySlider.toolTipText = tr("settings.bodyStrength.tip")
		bodySpinner.toolTipText = tr("settings.bodyStrength.tip")

		paddingLabel.text = tr("settings.texturePadding")
		paddingLabel.toolTipText = tr("settings.texturePadding.tip")
		paddingSpinner.toolTipText = tr("settings.texturePadding.tip")

		alphaLabel.text = tr("settings.alphaThreshold")
		alphaLabel.toolTipText = tr("settings.alphaThreshold.tip")
		alphaSpinner.toolTipText = tr("settings.alphaThreshold.tip")

		physicsCheck.text = tr("settings.physics")
		physicsCheck.toolTipText = tr("settings.physics.tip")

		cmo3Check.text = tr("settings.cmo3")
		cmo3Check.toolTipText = tr("settings.cmo3.tip")

		moc3Check.text = tr("settings.moc3")
		moc3Check.toolTipText = tr("settings.moc3.tip")

		meshUnitLabel.text = tr("settings.unit.px")
		headUnitLabel.text = tr("settings.unit.x")
		bodyUnitLabel.text = tr("settings.unit.x")
		paddingUnitLabel.text = tr("settings.unit.px")
		alphaUnitLabel.text = tr("settings.unit.byte")

		updateAdvancedToggleText()
		revalidate()
		repaint()
	}

	private fun updateAdvancedToggleText() {
		val arrow = if (advancedExpanded) "▾" else "▸"
		advancedToggleBtn.text = "$arrow ${tr("settings.advanced")}"
	}

	private inline fun withSuppression(block: () -> Unit) {
		val prev = suppressNotification
		suppressNotification = true
		try {
			block()
		} finally {
			suppressNotification = prev
		}
	}

	private fun notifyChanged() {
		if (suppressNotification) return
		onSettingsChanged?.invoke()
	}
}

