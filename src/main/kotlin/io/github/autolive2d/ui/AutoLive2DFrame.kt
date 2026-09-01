package io.github.autolive2d.ui

import io.github.autolive2d.core.AutoLive2DPipeline
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.ProgressListener
import io.github.autolive2d.core.RigPreviewModel
import io.github.autolive2d.core.SemanticTag
import io.github.autolive2d.core.Side
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

class AutoLive2DFrame : JFrame("AutoLive2D — PSD 全自动建模与绑定") {
	private val pipeline = AutoLive2DPipeline()
	private val inputField = JTextField()
	private val outputField = JTextField()
	private val analyzeButton = JButton("分析 PSD")
	private val runButton = JButton("生成并导出")
	private val openOutputButton = JButton("打开输出目录")
	private val progressBar = JProgressBar(0, 100)
	private val statusLabel = JLabel("就绪。可把 PSD 直接拖进窗口。")
	private val selectionModel = ComponentSelectionModel()
	private val layerTableModel = LayerTableModel()
	private val layerTable = JTable(layerTableModel)
	private val hierarchyPanel = HierarchyPanel(selectionModel)
	private val topologyPanel = TopologyPanel(selectionModel)
	private val previewPanel = ModelPreviewPanel()
	private val logArea = JTextArea()
	private val atlasSpinner = JSpinner(SpinnerNumberModel(4096, 256, 16384, 256))
	private val meshSpinner = JSpinner(SpinnerNumberModel(64, 16, 256, 8))
	private val headSpinner = JSpinner(SpinnerNumberModel(1.0, 0.0, 2.0, 0.1))
	private val bodySpinner = JSpinner(SpinnerNumberModel(1.0, 0.0, 2.0, 0.1))
	private val physicsCheck = JCheckBox("头发物理", true)
	private val cmo3Check = JCheckBox("CMO3 可编辑工程", true)
	private val moc3Check = JCheckBox("MOC3 运行时文件族", true)
	private var activeWorker: SwingWorker<*, *>? = null
	private var previewWorker: SwingWorker<RigPreviewModel, Void>? = null
	private var previewGeneration = 0L
	private var currentPreviewModel: RigPreviewModel? = null
	private var currentInputPath: Path? = null
	private val layerTableBorder = BorderFactory.createTitledBorder("See-Through 图层识别")
	private val previewRefreshTimer = Timer(260) { rebuildPreviewFromEdits() }.apply { isRepeats = false }

	init {
		defaultCloseOperation = EXIT_ON_CLOSE
		minimumSize = Dimension(980, 680)
		preferredSize = Dimension(1280, 820)
		jMenuBar = buildMenuBar()
		contentPane = buildContent()
		installActions()
		installDropTarget()
		pack()
		setLocationRelativeTo(null)
	}

	private fun buildMenuBar(): JMenuBar = JMenuBar().apply {
		add(JMenu("帮助").apply {
			add(JMenuItem("关于 AutoLive2D").apply {
				addActionListener {
					JOptionPane.showMessageDialog(
						this@AutoLive2DFrame,
						"AutoLive2D 0.1.0\nPSD → 自动建模/绑定/动作 → CMO3 + MOC3\n\nGPL-3.0；本程序不提供任何担保。\n不包含 Live2D 官方 SDK。",
						"关于 AutoLive2D",
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
		border = BorderFactory.createTitledBorder("项目")
		val constraints = GridBagConstraints().apply {
			insets = Insets(3, 5, 3, 5)
			fill = GridBagConstraints.HORIZONTAL
		}
		fun addRow(row: Int, label: String, field: JTextField, buttonText: String, action: () -> Unit) {
			constraints.gridy = row
			constraints.gridx = 0
			constraints.weightx = 0.0
			add(JLabel(label), constraints)
			constraints.gridx = 1
			constraints.weightx = 1.0
			add(field, constraints)
			constraints.gridx = 2
			constraints.weightx = 0.0
			add(JButton(buttonText).apply { addActionListener { action() } }, constraints)
		}
		addRow(0, "PSD", inputField, "选择…", ::chooseInput)
		addRow(1, "输出", outputField, "选择…", ::chooseOutput)
	}

	private fun buildWorkspace(): JSplitPane {
		val leftTabs = JTabbedPane().apply {
			addTab("层级", hierarchyPanel)
			addTab("拓扑", topologyPanel)
			addTab("预览", previewPanel)
			addTab("日志", JScrollPane(logArea.apply {
				isEditable = false
				font = Font(Font.MONOSPACED, Font.PLAIN, 12)
				lineWrap = true
				wrapStyleWord = true
			}))
		}
		val right = JPanel(BorderLayout(6, 6)).apply {
			add(buildSettings(), BorderLayout.NORTH)
			layerTable.autoCreateRowSorter = true
			layerTable.fillsViewportHeight = true
			layerTable.selectionModel.selectionMode = javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
			layerTable.rowHeight = 24
			layerTable.background = Color.WHITE
			layerTable.foreground = Color(35, 38, 43)
			layerTable.gridColor = Color(226, 228, 232)
			layerTable.putClientProperty("terminateEditOnFocusLost", true)
			layerTable.tableHeader.toolTipText = "眼睛：单击切换，Alt+单击仅显示/恢复，Shift+单击切换全部"
			layerTable.columnModel.getColumn(0).apply {
				minWidth = 32
				maxWidth = 32
				preferredWidth = 32
				cellRenderer = VisibilityCellRenderer()
			}
			layerTable.columnModel.getColumn(1).preferredWidth = 28
			layerTable.columnModel.getColumn(2).preferredWidth = 180
			layerTable.columnModel.getColumn(3).preferredWidth = 115
			layerTable.columnModel.getColumn(3).cellEditor = DefaultCellEditor(
				JComboBox(SemanticTag.entries.map { it.canonicalName }.toTypedArray()),
			)
			layerTable.columnModel.getColumn(4).cellEditor = DefaultCellEditor(
				JComboBox(Side.entries.map { it.name }.toTypedArray()),
			)
			val renderer = LayerRowRenderer()
			layerTable.setDefaultRenderer(String::class.java, renderer)
			layerTable.setDefaultRenderer(Int::class.javaObjectType, renderer)
			layerTable.setDefaultRenderer(Float::class.javaObjectType, renderer)
			add(JScrollPane(layerTable).apply { border = layerTableBorder }, BorderLayout.CENTER)
			add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
				add(analyzeButton)
				add(runButton)
				add(openOutputButton)
			}, BorderLayout.SOUTH)
		}
		return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabs, right).apply {
			resizeWeight = 0.55
			dividerLocation = 650
		}
	}

	private fun buildSettings(): JPanel = JPanel(GridBagLayout()).apply {
		border = BorderFactory.createTitledBorder("自动绑定设置")
		val c = GridBagConstraints().apply {
			insets = Insets(3, 5, 3, 5)
			anchor = GridBagConstraints.WEST
		}
		fun addSetting(column: Int, row: Int, label: String, component: java.awt.Component) {
			c.gridx = column * 2
			c.gridy = row
			add(JLabel(label), c)
			c.gridx++
			add(component, c)
		}
		addSetting(0, 0, "贴图页", atlasSpinner)
		addSetting(1, 0, "网格间距", meshSpinner)
		addSetting(0, 1, "头部幅度", headSpinner)
		addSetting(1, 1, "身体幅度", bodySpinner)
		c.gridx = 0
		c.gridy = 2
		c.gridwidth = 4
		add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
			add(physicsCheck)
			add(cmo3Check)
			add(moc3Check)
		}, c)
	}

	private fun buildStatusBar(): JPanel = JPanel(BorderLayout(8, 0)).apply {
		progressBar.isStringPainted = true
		progressBar.preferredSize = Dimension(230, 22)
		add(statusLabel, BorderLayout.CENTER)
		add(progressBar, BorderLayout.EAST)
	}

	private fun installActions() {
		analyzeButton.addActionListener { analyze() }
		runButton.addActionListener { runPipeline() }
		layerTableModel.onClassificationChanged = { _, _ ->
			statusLabel.text = "识别已修改，正在更新层级、拓扑和预览…"
			previewRefreshTimer.restart()
		}
		layerTableModel.onVisibilityChanged = {
			applyLayerVisibility()
			statusLabel.text = "图层可见性已更新"
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
		val popup = JPopupMenu().apply {
			add(JMenuItem("仅显示选中图层").apply { addActionListener { layerTableModel.showOnly(selectedModelRows()) } })
			add(JMenuItem("显示全部图层").apply { addActionListener { layerTableModel.setAllVisible(true) } })
			add(JMenuItem("隐藏全部图层").apply { addActionListener { layerTableModel.setAllVisible(false) } })
			addSeparator()
			add(JMenuItem("反转可见性").apply { addActionListener { layerTableModel.invertVisibility() } })
			add(JMenuItem("反选图层    Ctrl+Shift+I").apply { addActionListener { invertTableSelection() } })
		}
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
				popup.show(layerTable, event.x, event.y)
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
			dialogTitle = "选择 See-Through PSD"
			fileFilter = FileNameExtensionFilter("Photoshop 文档 (*.psd)", "psd")
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
			dialogTitle = "选择输出目录"
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
		startWorker("正在分析 PSD 并生成可视化 Rig…", object : SwingWorker<RigPreviewModel, String>() {
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
		val output = outputPathOrNull() ?: return showMessage("请选择输出目录。")
		val config = try { readConfig() } catch (failure: Exception) { return showFailure(failure) }
		if (!config.exportCmo3 && !config.exportMoc3) return showMessage("至少选择一种导出格式。")
		logArea.text = ""
		startWorker("开始生成…", object : SwingWorker<io.github.autolive2d.core.PipelineResult, String>() {
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
					logArea.append("\n输出文件：\n")
					result.exportedFiles.forEach { logArea.append("• ${it.path}  (${it.bytes} bytes)\n") }
					if (result.warnings.isNotEmpty()) {
						logArea.append("\n警告：\n")
						result.warnings.forEach { logArea.append("• $it\n") }
					}
					statusLabel.text = "完成：${result.exportedFiles.size} 个文件，${result.warnings.size} 条提示"
					progressBar.value = 100
					JOptionPane.showMessageDialog(this@AutoLive2DFrame, "导出并回读校验完成。\n${result.exportedFiles.size} 个文件已写入：\n$output", "AutoLive2D", JOptionPane.INFORMATION_MESSAGE)
				} catch (failure: Exception) { showFailure(unwrap(failure)) }
			}
		})
	}

	private fun startWorker(message: String, worker: SwingWorker<*, *>, indeterminate: Boolean = false) {
		if (activeWorker != null) return
		activeWorker = worker
		analyzeButton.isEnabled = false
		runButton.isEnabled = false
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
		val recognized = analysis.layers.count { it.semantic.tag.canonicalName != "unknown" }
		layerTable.revalidate()
		layerTable.repaint()
		statusLabel.text = "${analysis.source.widthPx}×${analysis.source.heightPx}，${analysis.layers.size} 个图层，识别 $recognized 个"
		if (appendLog) {
			logArea.append("分析：${analysis.layers.size} 个图层；角色 ${analysis.anchors.character.width.toInt()}×${analysis.anchors.character.height.toInt()}\n")
			analysis.warnings.forEach { logArea.append("警告：$it\n") }
		}
	}

	private fun applyLayerVisibility() {
		val visible = layerTableModel.visibleLayerIds
		hierarchyPanel.visibleLayerIds = visible
		topologyPanel.visibleLayerIds = visible
		previewPanel.visibleLayerIds = visible
		layerTableModel.analysis?.let { analysis ->
			val recognized = analysis.layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
			val unknown = analysis.layers.size - recognized
			layerTableBorder.title = "See-Through 图层识别  ·  可见 ${visible.size}/${analysis.layers.size}  ·  已识别 $recognized  ·  未识别 $unknown（置后）"
			layerTable.repaint()
		}
	}

	private fun rebuildPreviewFromEdits() {
		val previous = currentPreviewModel ?: return
		if (activeWorker != null) return
		val config = try { readConfig() } catch (failure: Exception) {
			statusLabel.text = "无法更新预览：${failure.message ?: failure.javaClass.simpleName}"
			return
		}
		val generation = ++previewGeneration
		previewWorker?.cancel(true)
		statusLabel.text = "正在应用图层修改…"
		runButton.isEnabled = false
		previewWorker = object : SwingWorker<RigPreviewModel, Void>() {
			override fun doInBackground(): RigPreviewModel = pipeline.buildPreview(previous.analysis.source, config)

			override fun done() {
				if (generation != previewGeneration || isCancelled) return
				previewWorker = null
				runButton.isEnabled = activeWorker == null
				try {
					showPreviewModel(get(), appendLog = false)
					statusLabel.text = "图层修改已实时应用到层级、拓扑和动态预览"
				} catch (failure: Exception) {
					val detail = unwrap(failure).message ?: failure.javaClass.simpleName
					statusLabel.text = "预览更新失败：$detail"
					logArea.append("预览更新失败：$detail\n")
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
		layerTableBorder.title = "See-Through 图层识别"
		layerTable.repaint()
		runButton.isEnabled = activeWorker == null
	}

	private fun readConfig() = PipelineConfig(
		atlasSize = (atlasSpinner.value as Number).toInt(),
		meshSpacing = (meshSpinner.value as Number).toInt(),
		headTurnStrength = (headSpinner.value as Number).toFloat(),
		bodyStrength = (bodySpinner.value as Number).toFloat(),
		generatePhysics = physicsCheck.isSelected,
		exportCmo3 = cmo3Check.isSelected,
		exportMoc3 = moc3Check.isSelected,
		layerOverrides = layerTableModel.classificationOverrides,
		layerVisibility = layerTableModel.visibilityOverrides,
	)

	private fun inputPathOrShowError(): Path? {
		val raw = inputField.text.trim()
		if (raw.isEmpty()) { showMessage("请选择 PSD 文件。"); return null }
		val path = Path.of(raw)
		if (!Files.isRegularFile(path) || !path.fileName.toString().endsWith(".psd", true)) { showMessage("PSD 文件不存在或扩展名不正确：\n$path"); return null }
		return path
	}

	private fun outputPathOrNull(): Path? = outputField.text.trim().takeIf(String::isNotEmpty)?.let(Path::of)
	private fun showMessage(message: String) = JOptionPane.showMessageDialog(this, message, "AutoLive2D", JOptionPane.WARNING_MESSAGE)
	private fun showFailure(failure: Throwable) {
		finishWorker()
		val detail = failure.message ?: failure.javaClass.simpleName
		statusLabel.text = "失败：$detail"
		logArea.append("失败：$detail\n")
		JOptionPane.showMessageDialog(this, detail, "处理失败", JOptionPane.ERROR_MESSAGE)
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
			component.toolTipText = "单击显示/隐藏；Alt+单击仅显示此层/恢复；Shift+单击显示或隐藏全部"
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
