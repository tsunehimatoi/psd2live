package io.github.autolive2d.ui

import io.github.autolive2d.core.AutoLive2DPipeline
import io.github.autolive2d.core.PipelineAnalysis
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.ProgressListener
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingWorker
import javax.swing.filechooser.FileNameExtensionFilter

class AutoLive2DFrame : JFrame("AutoLive2D — PSD 全自动建模与绑定") {
	private val pipeline = AutoLive2DPipeline()
	private val inputField = JTextField()
	private val outputField = JTextField()
	private val analyzeButton = JButton("分析 PSD")
	private val runButton = JButton("生成并导出")
	private val openOutputButton = JButton("打开输出目录")
	private val progressBar = JProgressBar(0, 100)
	private val statusLabel = JLabel("就绪。可把 PSD 直接拖进窗口。")
	private val layerTableModel = LayerTableModel()
	private val layerTable = JTable(layerTableModel)
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
			addTab("角色预览", JScrollPane(previewPanel).apply { border = null })
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
			layerTable.columnModel.getColumn(0).preferredWidth = 28
			layerTable.columnModel.getColumn(1).preferredWidth = 170
			add(JScrollPane(layerTable).apply { border = BorderFactory.createTitledBorder("See-Through 图层识别") }, BorderLayout.CENTER)
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
		openOutputButton.addActionListener {
			val directory = outputPathOrNull()
			if (directory != null && Files.isDirectory(directory) && Desktop.isDesktopSupported()) Desktop.getDesktop().open(directory.toFile())
		}
		inputField.addActionListener { analyze() }
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
		inputField.text = path.toAbsolutePath().normalize().toString()
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
		startWorker("正在分析 PSD…", object : SwingWorker<PipelineAnalysis, String>() {
			override fun doInBackground(): PipelineAnalysis = pipeline.inspect(input, readConfig())
			override fun done() {
				finishWorker()
				try { showAnalysis(get()) } catch (failure: Exception) { showFailure(unwrap(failure)) }
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
					showAnalysis(result.analysis)
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

	private fun showAnalysis(analysis: PipelineAnalysis) {
		layerTableModel.analysis = analysis
		previewPanel.analysis = analysis
		val recognized = analysis.layers.count { it.semantic.tag.canonicalName != "unknown" }
		statusLabel.text = "${analysis.source.widthPx}×${analysis.source.heightPx}，${analysis.layers.size} 个图层，识别 $recognized 个"
		logArea.append("分析：${analysis.layers.size} 个图层；角色 ${analysis.anchors.character.width.toInt()}×${analysis.anchors.character.height.toInt()}\n")
		analysis.warnings.forEach { logArea.append("警告：$it\n") }
	}

	private fun readConfig() = PipelineConfig(
		atlasSize = (atlasSpinner.value as Number).toInt(),
		meshSpacing = (meshSpinner.value as Number).toInt(),
		headTurnStrength = (headSpinner.value as Number).toFloat(),
		bodyStrength = (bodySpinner.value as Number).toFloat(),
		generatePhysics = physicsCheck.isSelected,
		exportCmo3 = cmo3Check.isSelected,
		exportMoc3 = moc3Check.isSelected,
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
}
