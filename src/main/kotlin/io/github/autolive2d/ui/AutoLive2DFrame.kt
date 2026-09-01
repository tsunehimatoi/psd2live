package io.github.autolive2d.ui

import io.github.autolive2d.core.AutoLive2DPipeline
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.ProgressListener
import io.github.autolive2d.core.RigPreviewModel
import io.github.autolive2d.core.SemanticTag
import io.github.autolive2d.core.Side
import io.github.autolive2d.i18n.I18n
import io.github.autolive2d.i18n.tr
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.DefaultCellEditor
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.DefaultTableCellRenderer

class AutoLive2DFrame : JFrame() {
	private val pipeline = AutoLive2DPipeline()
	private val inputField = JTextField()
	private val outputField = JTextField()
	private val inputLabel = JLabel()
	private val outputLabel = JLabel()
	private val chooseInputButton = JButton()
	private val chooseOutputButton = JButton()
	private val analyzeButton = JButton()
	private val runButton = JButton()
	private val openOutputButton = JButton()
	private val progressBar = JProgressBar(0, 100)
	private val statusLabel = JLabel()
	private val selectionModel = ComponentSelectionModel()
	private val layerTableModel = LayerTableModel()
	private val layerTable = JTable(layerTableModel)
	private val hierarchyPanel = HierarchyPanel(selectionModel)
	private val topologyPanel = TopologyPanel(selectionModel)
	private val previewPanel = ModelPreviewPanel()
	private val logArea = JTextArea()
	private val settingsPanel = RigSettingsPanel(onSettingsChanged = {
		if (currentPreviewModel != null) previewRefreshTimer.restart()
	})
	private var activeWorker: SwingWorker<*, *>? = null
	private var previewWorker: SwingWorker<RigPreviewModel, Void>? = null
	private var previewGeneration = 0L
	private var currentPreviewModel: RigPreviewModel? = null
	private var currentInputPath: Path? = null
	private val projectBorder = BorderFactory.createTitledBorder("")
	private val layerTableBorder = BorderFactory.createTitledBorder("")
	private val workspaceTabs = JTabbedPane()
	private val layerPopup = JPopupMenu()
	private val previewRefreshTimer = Timer(260) { rebuildPreviewFromEdits() }.apply { isRepeats = false }

	init {
		defaultCloseOperation = EXIT_ON_CLOSE
		minimumSize = Dimension(980, 680)
		preferredSize = Dimension(1280, 820)
		jMenuBar = buildMenuBar()
		contentPane = buildContent()
		installActions()
		installDropTarget()
		refreshTexts()
		pack()
		setLocationRelativeTo(null)
	}

	private fun buildMenuBar(): JMenuBar = JMenuBar().apply {
		add(JMenu(tr("menu.language")).apply {
			val group = ButtonGroup()
			for (language in I18n.supportedLanguages) {
				add(JRadioButtonMenuItem(tr(language.displayNameKey), language == I18n.currentLanguage).apply {
					group.add(this)
					addActionListener {
						I18n.setLanguage(language)
						refreshTexts()
						if (currentPreviewModel != null) previewRefreshTimer.restart()
					}
				})
			}
		})
		add(JMenu(tr("menu.help")).apply {
			add(JMenuItem(tr("menu.about")).apply {
				addActionListener {
					JOptionPane.showMessageDialog(
						this@AutoLive2DFrame,
						tr("dialog.about.message"),
						tr("dialog.about.title"),
						JOptionPane.INFORMATION_MESSAGE,
					)
				}
			})
		})
	}

	private fun buildContent(): JPanel = JPanel(BorderLayout(8, 8)).apply {
		border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
		add(buildFileBar(), BorderLayout.NORTH)
		add(buildWorkspace(), BorderLayout.CENTER)
		add(buildStatusBar(), BorderLayout.SOUTH)
	}

	private fun buildFileBar(): JPanel = JPanel(GridBagLayout()).apply {
		border = projectBorder
		val constraints = GridBagConstraints().apply {
			insets = Insets(3, 5, 3, 5)
			fill = GridBagConstraints.HORIZONTAL
		}
		fun addRow(row: Int, label: JLabel, field: JTextField, button: JButton, action: () -> Unit) {
			constraints.gridy = row
			constraints.gridx = 0
			constraints.weightx = 0.0
			add(label, constraints)
			constraints.gridx = 1
			constraints.weightx = 1.0
			add(field, constraints)
			constraints.gridx = 2
			constraints.weightx = 0.0
			add(button.apply { addActionListener { action() } }, constraints)
		}
		addRow(0, inputLabel, inputField, chooseInputButton, ::chooseInput)
		addRow(1, outputLabel, outputField, chooseOutputButton, ::chooseOutput)
	}

	private fun buildWorkspace(): JSplitPane {
		workspaceTabs.apply {
			addTab("", hierarchyPanel)
			addTab("", topologyPanel)
			addTab("", previewPanel)
			addTab("", JScrollPane(logArea.apply {
				isEditable = false
				font = Font(Font.MONOSPACED, Font.PLAIN, 12)
				lineWrap = true
				wrapStyleWord = true
			}))
		}
		val right = JPanel(BorderLayout(6, 6)).apply {
			add(settingsPanel, BorderLayout.NORTH)
			layerTable.autoCreateRowSorter = true
			layerTable.fillsViewportHeight = true
			layerTable.selectionModel.selectionMode = javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
			layerTable.rowHeight = 24
			layerTable.background = Color.WHITE
			layerTable.foreground = Color(35, 38, 43)
			layerTable.gridColor = Color(226, 228, 232)
			layerTable.putClientProperty("terminateEditOnFocusLost", true)
			val renderer = LayerRowRenderer()
			layerTable.setDefaultRenderer(String::class.java, renderer)
			layerTable.setDefaultRenderer(Int::class.javaObjectType, renderer)
			layerTable.setDefaultRenderer(Float::class.javaObjectType, renderer)
			configureLayerTableColumns()
			add(JScrollPane(layerTable).apply { border = layerTableBorder }, BorderLayout.CENTER)
			add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
				add(analyzeButton)
				add(runButton)
				add(openOutputButton)
			}, BorderLayout.SOUTH)
		}
		return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspaceTabs, right).apply {
			resizeWeight = 0.55
			dividerLocation = 650
		}
	}

	private fun buildStatusBar(): JPanel = JPanel(BorderLayout(8, 0)).apply {
		progressBar.isStringPainted = true
		progressBar.preferredSize = Dimension(230, 22)
		add(statusLabel, BorderLayout.CENTER)
		add(progressBar, BorderLayout.EAST)
	}

	private fun configureLayerTableColumns() {
		layerTable.tableHeader.toolTipText = tr("layers.visibility.headerTip")
		layerTable.columnModel.getColumn(0).apply {
			minWidth = 32
			maxWidth = 32
			preferredWidth = 32
			cellRenderer = VisibilityCellRenderer()
		}
		layerTable.columnModel.getColumn(1).preferredWidth = 28
		layerTable.columnModel.getColumn(2).preferredWidth = 180
		layerTable.columnModel.getColumn(3).preferredWidth = 145
		layerTable.columnModel.getColumn(3).cellEditor = DefaultCellEditor(
			JComboBox(SemanticTag.entries.map { it.localizedName() }.toTypedArray()),
		)
		layerTable.columnModel.getColumn(4).cellEditor = DefaultCellEditor(
			JComboBox(Side.entries.map { it.localizedName() }.toTypedArray()),
		)
	}

	private fun refreshTexts() {
		title = tr("app.title")
		jMenuBar = buildMenuBar()
		projectBorder.title = tr("project.title")
		inputLabel.text = tr("project.input")
		outputLabel.text = tr("project.output")
		chooseInputButton.text = tr("action.choose")
		chooseOutputButton.text = tr("action.choose")
		analyzeButton.text = tr("action.analyze")
		runButton.text = tr("action.generate")
		openOutputButton.text = tr("action.openOutput")
		settingsPanel.refreshTexts()
		workspaceTabs.setTitleAt(0, tr("tab.hierarchy"))
		workspaceTabs.setTitleAt(1, tr("tab.topology"))
		workspaceTabs.setTitleAt(2, tr("tab.preview"))
		workspaceTabs.setTitleAt(3, tr("tab.log"))
		layerTableModel.languageChanged()
		configureLayerTableColumns()
		refreshLayerPopup()
		hierarchyPanel.refreshTranslations()
		topologyPanel.repaint()
		previewPanel.repaint()
		currentPreviewModel?.let {
			applyLayerVisibility()
			if (activeWorker == null) showAnalysisStatus(it)
		} ?: run {
			layerTableBorder.title = tr("layers.title")
			if (activeWorker == null) statusLabel.text = tr("status.ready")
		}
		revalidate()
		repaint()
	}

	private fun refreshLayerPopup() {
		layerPopup.removeAll()
		layerPopup.add(JMenuItem(tr("layers.popup.showOnly")).apply { addActionListener { layerTableModel.showOnly(selectedModelRows()) } })
		layerPopup.add(JMenuItem(tr("layers.popup.showAll")).apply { addActionListener { layerTableModel.setAllVisible(true) } })
		layerPopup.add(JMenuItem(tr("layers.popup.hideAll")).apply { addActionListener { layerTableModel.setAllVisible(false) } })
		layerPopup.addSeparator()
		layerPopup.add(JMenuItem(tr("layers.popup.invertVisibility")).apply { addActionListener { layerTableModel.invertVisibility() } })
		layerPopup.add(JMenuItem(tr("layers.popup.invertSelection")).apply { addActionListener { invertTableSelection() } })
	}

	private fun installActions() {
		analyzeButton.addActionListener { analyze() }
		runButton.addActionListener { runPipeline() }
		layerTableModel.onClassificationChanged = { _, _ ->
			statusLabel.text = tr("status.classificationChanged")
			previewRefreshTimer.restart()
		}
		layerTableModel.onVisibilityChanged = {
			applyLayerVisibility()
			statusLabel.text = tr("status.visibilityChanged")
			previewRefreshTimer.restart()
		}
		layerTable.selectionModel.addListSelectionListener { event ->
			if (event.valueIsAdjusting || layerTable.selectedRow < 0) return@addListSelectionListener
			val modelRow = layerTable.convertRowIndexToModel(layerTable.selectedRow)
			layerTableModel.layerAt(modelRow)?.source?.id?.raw?.let(selectionModel::select)
		}
		selectionModel.addListener { layerId ->
			if (layerId == null) {
				layerTable.clearSelection()
				return@addListener
			}
			val modelRow = layerTableModel.modelRowForLayerId(layerId)
			if (modelRow < 0) return@addListener
			val viewRow = layerTable.convertRowIndexToView(modelRow)
			if (viewRow >= 0 && layerTable.selectedRow != viewRow) {
				layerTable.selectionModel.setSelectionInterval(viewRow, viewRow)
				layerTable.scrollRectToVisible(layerTable.getCellRect(viewRow, 0, true))
			}
		}
		installLayerTableVisibilityActions()
		openOutputButton.addActionListener {
			val directory = outputPathOrNull()
			if (directory != null && Files.isDirectory(directory) && Desktop.isDesktopSupported()) Desktop.getDesktop().open(directory.toFile())
		}
		inputField.addActionListener { analyze() }
	}

	private fun installLayerTableVisibilityActions() {
		refreshLayerPopup()
		layerTable.addMouseListener(object : MouseAdapter() {
			override fun mousePressed(event: MouseEvent) = showPopupIfNeeded(event)

			override fun mouseReleased(event: MouseEvent) = showPopupIfNeeded(event)

			override fun mouseClicked(event: MouseEvent) {
				if (!javax.swing.SwingUtilities.isLeftMouseButton(event)) return
				val viewRow = layerTable.rowAtPoint(event.point)
				val viewColumn = layerTable.columnAtPoint(event.point)
				if (viewRow < 0 || viewColumn != 0) return
				val modelRow = layerTable.convertRowIndexToModel(viewRow)
				when {
					event.isAltDown -> layerTableModel.isolateOrRestore(modelRow)
					event.isShiftDown -> layerTableModel.setAllVisible(!layerTableModel.isVisibleAt(modelRow))
					event.isControlDown -> {
						val selected = selectedModelRows().takeIf { it.isNotEmpty() } ?: intArrayOf(modelRow)
						layerTableModel.setVisibilityForRows(selected, !layerTableModel.isVisibleAt(modelRow))
					}
					else -> layerTableModel.toggleVisibility(modelRow)
				}
				event.consume()
			}

			private fun showPopupIfNeeded(event: MouseEvent) {
				if (!event.isPopupTrigger) return
				val row = layerTable.rowAtPoint(event.point)
				if (row >= 0 && !layerTable.isRowSelected(row)) layerTable.setRowSelectionInterval(row, row)
				layerPopup.show(layerTable, event.x, event.y)
			}
		})

		fun bind(name: String, key: KeyStroke, action: () -> Unit) {
			layerTable.getInputMap(JTable.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name)
			layerTable.actionMap.put(name, object : AbstractAction() {
				override fun actionPerformed(event: java.awt.event.ActionEvent) = action()
			})
		}
		bind("select-all-layers", KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)) { layerTable.selectAll() }
		bind(
			"invert-layer-selection",
			KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
			::invertTableSelection,
		)
		bind(
			"show-only-selected-layers",
			KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
		) { layerTableModel.showOnly(selectedModelRows()) }
		bind(
			"invert-layer-visibility",
			KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK),
		) { layerTableModel.invertVisibility() }
		bind("toggle-selected-visibility", KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK)) {
			val selected = selectedModelRows()
			if (selected.isNotEmpty()) layerTableModel.setVisibilityForRows(selected, !layerTableModel.isVisibleAt(selected.first()))
		}
	}

	private fun selectedModelRows(): IntArray = layerTable.selectedRows
		.map(layerTable::convertRowIndexToModel)
		.distinct()
		.toIntArray()

	private fun invertTableSelection() {
		val selected = layerTable.selectedRows.toSet()
		layerTable.clearSelection()
		for (viewRow in 0 until layerTable.rowCount) {
			if (viewRow !in selected) layerTable.addRowSelectionInterval(viewRow, viewRow)
		}
	}

	private fun installDropTarget() {
		DropTarget(this, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
			override fun drop(event: DropTargetDropEvent) {
				try {
					event.acceptDrop(DnDConstants.ACTION_COPY)
					@Suppress("UNCHECKED_CAST")
					val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
					files.firstOrNull { it.extension.equals("psd", true) }?.let {
						setInput(it.toPath())
						analyze()
					}
					event.dropComplete(true)
				} catch (failure: Exception) {
					event.dropComplete(false)
					showFailure(failure)
				}
			}
		}, true)
	}

	private fun chooseInput() {
		val chooser = JFileChooser().apply {
			dialogTitle = tr("dialog.choosePsd")
			fileFilter = FileNameExtensionFilter(tr("dialog.psdFilter"), "psd")
		}
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) setInput(chooser.selectedFile.toPath())
	}

	private fun setInput(path: Path) {
		val normalized = path.toAbsolutePath().normalize()
		if (currentInputPath != null && currentInputPath != normalized) clearWorkbench()
		inputField.text = normalized.toString()
		if (outputField.text.isBlank()) outputField.text = path.toAbsolutePath().parent.resolve("${path.fileName.toString().substringBeforeLast('.')}-autolive2d").toString()
	}

	private fun chooseOutput() {
		val chooser = JFileChooser().apply {
			dialogTitle = tr("dialog.chooseOutput")
			fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
			outputPathOrNull()?.toFile()?.takeIf(File::exists)?.let { currentDirectory = it }
		}
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) outputField.text = chooser.selectedFile.absolutePath
	}

	private fun analyze() {
		val input = inputPathOrShowError() ?: return
		val normalizedInput = input.toAbsolutePath().normalize()
		if (currentInputPath != null && currentInputPath != normalizedInput) clearWorkbench()
		val config = try { readConfig() } catch (failure: Exception) { return showFailure(failure) }
		startWorker(tr("status.analyzing"), object : SwingWorker<RigPreviewModel, String>() {
			override fun doInBackground(): RigPreviewModel = pipeline.buildPreview(input, config)
			override fun done() {
				finishWorker()
				try {
					currentInputPath = normalizedInput
					showPreviewModel(get())
				} catch (failure: Exception) { showFailure(unwrap(failure)) }
			}
		}, indeterminate = true)
	}

	private fun runPipeline() {
		val input = inputPathOrShowError() ?: return
		val output = outputPathOrNull() ?: return showMessage(tr("dialog.outputRequired"))
		val config = try { readConfig() } catch (failure: Exception) { return showFailure(failure) }
		if (!config.exportCmo3 && !config.exportMoc3) return showMessage(tr("dialog.exportFormatRequired"))
		logArea.text = ""
		startWorker(tr("status.generating"), object : SwingWorker<io.github.autolive2d.core.PipelineResult, String>() {
			override fun doInBackground() = pipeline.run(input, output, config, ProgressListener { stage, fraction ->
				setProgress((fraction * 100).toInt().coerceIn(0, 100))
				publish(stage)
			})
			override fun process(chunks: MutableList<String>) {
				val stage = chunks.last()
				statusLabel.text = stage
				logArea.append("${progress}%  $stage\n")
			}
			override fun done() {
				finishWorker()
				try {
					val result = get()
					currentInputPath = input.toAbsolutePath().normalize()
					showPreviewModel(result.previewModel)
					logArea.append("\n${tr("log.outputFiles")}\n")
					result.exportedFiles.forEach { logArea.append("• ${it.path}  (${it.bytes} bytes)\n") }
					if (result.warnings.isNotEmpty()) {
						logArea.append("\n${tr("log.warnings")}\n")
						result.warnings.forEach { logArea.append("• $it\n") }
					}
					statusLabel.text = tr("status.completed", result.exportedFiles.size, result.warnings.size)
					progressBar.value = 100
					JOptionPane.showMessageDialog(
						this@AutoLive2DFrame,
						tr("dialog.exportSuccess", result.exportedFiles.size, output),
						"AutoLive2D",
						JOptionPane.INFORMATION_MESSAGE,
					)
				} catch (failure: Exception) { showFailure(unwrap(failure)) }
			}
		})
	}

	private fun startWorker(message: String, worker: SwingWorker<*, *>, indeterminate: Boolean = false) {
		if (activeWorker != null) return
		activeWorker = worker
		analyzeButton.isEnabled = false
		runButton.isEnabled = false
		settingsPanel.setControlsEnabled(false)
		progressBar.value = 0
		progressBar.isIndeterminate = indeterminate
		statusLabel.text = message
		worker.addPropertyChangeListener { event ->
			if (event.propertyName == "progress") progressBar.value = event.newValue as Int
		}
		worker.execute()
	}

	private fun finishWorker() {
		activeWorker = null
		analyzeButton.isEnabled = true
		runButton.isEnabled = true
		settingsPanel.setControlsEnabled(true)
		progressBar.isIndeterminate = false
	}

	private fun showPreviewModel(model: RigPreviewModel, appendLog: Boolean = true) {
		currentPreviewModel = model
		val analysis = model.analysis
		layerTableModel.setAnalysis(analysis)
		applyLayerVisibility()
		selectionModel.selectedLayerId?.let { layerId ->
			val modelRow = layerTableModel.modelRowForLayerId(layerId)
			val viewRow = if (modelRow >= 0) layerTable.convertRowIndexToView(modelRow) else -1
			if (viewRow >= 0) layerTable.selectionModel.setSelectionInterval(viewRow, viewRow)
		}
		hierarchyPanel.previewModel = model
		topologyPanel.previewModel = model
		previewPanel.previewModel = model
		layerTable.revalidate()
		layerTable.repaint()
		showAnalysisStatus(model)
		if (appendLog) {
			logArea.append("${tr("log.analysis", analysis.layers.size, analysis.anchors.character.width.toInt(), analysis.anchors.character.height.toInt())}\n")
			analysis.warnings.forEach { logArea.append("${tr("log.warning", it)}\n") }
		}
	}

	private fun showAnalysisStatus(model: RigPreviewModel) {
		val analysis = model.analysis
		val recognized = analysis.layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
		statusLabel.text = tr(
			"status.analysisSummary",
			analysis.source.widthPx,
			analysis.source.heightPx,
			analysis.layers.size,
			recognized,
		)
	}

	private fun applyLayerVisibility() {
		val visible = layerTableModel.visibleLayerIds
		hierarchyPanel.visibleLayerIds = visible
		topologyPanel.visibleLayerIds = visible
		previewPanel.visibleLayerIds = visible
		layerTableModel.analysis?.let { analysis ->
			val recognized = analysis.layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
			val unknown = analysis.layers.size - recognized
			layerTableBorder.title = tr("layers.summary", visible.size, analysis.layers.size, recognized, unknown)
			layerTable.repaint()
		}
	}

	private fun rebuildPreviewFromEdits() {
		val previous = currentPreviewModel ?: return
		if (activeWorker != null) return
		val config = try { readConfig() } catch (failure: Exception) {
			statusLabel.text = tr("status.previewUpdateUnavailable", failure.message ?: failure.javaClass.simpleName)
			return
		}
		val generation = ++previewGeneration
		previewWorker?.cancel(true)
		statusLabel.text = tr("status.applyingLayerChanges")
		runButton.isEnabled = false
		previewWorker = object : SwingWorker<RigPreviewModel, Void>() {
			override fun doInBackground(): RigPreviewModel = pipeline.buildPreview(previous.analysis.source, config)

			override fun done() {
				if (generation != previewGeneration || isCancelled) return
				previewWorker = null
				runButton.isEnabled = activeWorker == null
				try {
					showPreviewModel(get(), appendLog = false)
					statusLabel.text = tr("status.layerChangesApplied")
				} catch (failure: Exception) {
					val detail = unwrap(failure).message ?: failure.javaClass.simpleName
					statusLabel.text = tr("status.previewUpdateFailed", detail)
					logArea.append("${tr("log.previewUpdateFailed", detail)}\n")
				}
			}
		}.also { it.execute() }
	}

	private fun clearWorkbench() {
		previewRefreshTimer.stop()
		previewWorker?.cancel(true)
		previewWorker = null
		previewGeneration++
		currentPreviewModel = null
		currentInputPath = null
		layerTableModel.clearOverrides()
		hierarchyPanel.previewModel = null
		hierarchyPanel.visibleLayerIds = null
		topologyPanel.previewModel = null
		topologyPanel.visibleLayerIds = null
		previewPanel.previewModel = null
		previewPanel.visibleLayerIds = null
		selectionModel.select(null)
		layerTableBorder.title = tr("layers.title")
		layerTable.repaint()
		runButton.isEnabled = activeWorker == null
	}

	private fun readConfig() = settingsPanel.buildConfig(
		layerOverrides = layerTableModel.classificationOverrides,
		layerVisibility = layerTableModel.visibilityOverrides,
	)

	private fun inputPathOrShowError(): Path? {
		val raw = inputField.text.trim()
		if (raw.isEmpty()) { showMessage(tr("dialog.inputRequired")); return null }
		val path = Path.of(raw)
		if (!Files.isRegularFile(path) || !path.fileName.toString().endsWith(".psd", true)) {
			showMessage(tr("dialog.inputInvalid", path))
			return null
		}
		return path
	}

	private fun outputPathOrNull(): Path? = outputField.text.trim().takeIf(String::isNotEmpty)?.let(Path::of)
	private fun showMessage(message: String) = JOptionPane.showMessageDialog(this, message, "AutoLive2D", JOptionPane.WARNING_MESSAGE)
	private fun showFailure(failure: Throwable) {
		finishWorker()
		val detail = failure.message ?: failure.javaClass.simpleName
		statusLabel.text = tr("status.failed", detail)
		logArea.append("${tr("log.failed", detail)}\n")
		JOptionPane.showMessageDialog(this, detail, tr("dialog.failure.title"), JOptionPane.ERROR_MESSAGE)
	}
	private fun unwrap(failure: Exception): Throwable = if (failure is ExecutionException) failure.cause ?: failure else failure

	private inner class LayerRowRenderer : DefaultTableCellRenderer() {
		override fun getTableCellRendererComponent(
			table: JTable,
			value: Any?,
			isSelected: Boolean,
			hasFocus: Boolean,
			row: Int,
			column: Int,
		): Component {
			val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
			val modelRow = table.convertRowIndexToModel(row)
			component.background = if (isSelected) table.selectionBackground else Color.WHITE
			component.foreground = when {
				isSelected -> table.selectionForeground
				layerTableModel.isVisibleAt(modelRow) -> table.foreground
				else -> Color(132, 136, 143)
			}
			return component
		}
	}

	private inner class VisibilityCellRenderer : DefaultTableCellRenderer() {
		init {
			horizontalAlignment = CENTER
		}

		override fun getTableCellRendererComponent(
			table: JTable,
			value: Any?,
			isSelected: Boolean,
			hasFocus: Boolean,
			row: Int,
			column: Int,
		): Component {
			val component = super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column) as JLabel
			val visible = value == true
			component.icon = EyeIcon(visible, if (visible) Color(70, 74, 82) else Color(145, 149, 156))
			component.toolTipText = tr("layers.visibility.cellTip")
			component.background = if (isSelected) table.selectionBackground else Color.WHITE
			component.foreground = if (isSelected) table.selectionForeground else table.foreground
			return component
		}
	}

	private class EyeIcon(
		private val visible: Boolean,
		private val color: Color,
	) : Icon {
		override fun getIconWidth(): Int = 18
		override fun getIconHeight(): Int = 16

		override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
			val g = graphics.create() as Graphics2D
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
				g.color = color
				g.stroke = BasicStroke(1.45f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
				val eye = Path2D.Float().apply {
					moveTo(x + 1.5f, y + 8f)
					curveTo(x + 5f, y + 3.2f, x + 13f, y + 3.2f, x + 16.5f, y + 8f)
					curveTo(x + 13f, y + 12.8f, x + 5f, y + 12.8f, x + 1.5f, y + 8f)
				}
				if (visible) {
					g.draw(eye)
					g.fillOval(x + 7, y + 6, 4, 4)
				} else {
					g.drawArc(x + 2, y + 6, 14, 6, 200, 140)
					g.drawLine(x + 3, y + 3, x + 15, y + 13)
				}
			} finally {
				g.dispose()
			}
		}
	}
}
