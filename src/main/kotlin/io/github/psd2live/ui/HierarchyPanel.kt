package io.github.psd2live.ui

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.i18n.tr
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.Deformer
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.event.TreeSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class HierarchyPanel(
	private val selection: ComponentSelectionModel,
) : JPanel(BorderLayout()) {
	private data class HierarchyItem(
		val label: String,
		val key: String,
		val deformerId: String? = null,
		val layerId: String? = null,
	) {
		override fun toString(): String = label
	}

	private val root = DefaultMutableTreeNode(HierarchyItem(tr("canvas.hierarchy.root"), "root"))
	private val treeModel = DefaultTreeModel(root)
	private val tree = JTree(treeModel)
	private val canvas = HierarchyCanvas(selection)
	private val nodeByLayerId = linkedMapOf<String, DefaultMutableTreeNode>()
	private var changingSelection = false

	var previewModel: RigPreviewModel? = null
		set(value) {
			field = value
			canvas.previewModel = value
			rebuildTree(value)
		}

	var visibleLayerIds: Set<String>? = null
		set(value) {
			field = value
			canvas.visibleLayerIds = value
		}

	init {
		tree.isRootVisible = true
		tree.showsRootHandles = true
		tree.rowHeight = 24
		tree.cellRenderer = HierarchyRenderer()
		tree.addTreeSelectionListener(::treeSelectionChanged)
		val treeScroll = JScrollPane(tree).apply {
			border = null
			minimumSize = Dimension(0, 120)
			preferredSize = Dimension(235, 500)
		}
		add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, canvas).apply {
			resizeWeight = 0.0
			dividerLocation = 235
			setOneTouchExpandable(true)
			dividerSize = 9
			border = null
		}, BorderLayout.CENTER)
		selection.addListener { layerId ->
			canvas.repaint()
			if (changingSelection || layerId == null) return@addListener
			val node = nodeByLayerId[layerId] ?: return@addListener
			changingSelection = true
			try {
				val path = TreePath(node.path)
				tree.selectionPath = path
				tree.scrollPathToVisible(path)
			} finally {
				changingSelection = false
			}
		}
	}

	fun refreshTranslations() {
		root.userObject = HierarchyItem(tr("canvas.hierarchy.root"), "root")
		treeModel.nodeChanged(root)
		canvas.repaint()
	}

	private fun rebuildTree(model: RigPreviewModel?) {
		root.removeAllChildren()
		nodeByLayerId.clear()
		if (model == null) {
			treeModel.reload()
			return
		}
		val nodeByDeformerId = linkedMapOf<String, DefaultMutableTreeNode>()
		for (deformer in model.rig.puppet.deformers) {
			val type = if (deformer is Deformer.Warp) "Warp" else "Rotation"
			val node = DefaultMutableTreeNode(
				HierarchyItem("${deformer.name}  [$type]", deformer.id.raw, deformerId = deformer.id.raw),
			)
			val parent = deformer.parent?.raw?.let(nodeByDeformerId::get) ?: root
			parent.add(node)
			nodeByDeformerId[deformer.id.raw] = node
		}
		for (drawable in model.rig.puppet.drawables) {
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: continue
			val node = DefaultMutableTreeNode(
				HierarchyItem(drawable.name, layerId, layerId = layerId),
			)
			val parent = drawable.parentDeformerId?.raw?.let(nodeByDeformerId::get) ?: root
			parent.add(node)
			nodeByLayerId[layerId] = node
		}
		treeModel.reload()
		for (row in 0 until minOf(tree.rowCount, 6)) tree.expandRow(row)
		selection.selectedLayerId?.let { selected ->
			nodeByLayerId[selected]?.let { tree.selectionPath = TreePath(it.path) }
		}
	}

	private fun treeSelectionChanged(event: TreeSelectionEvent) {
		if (changingSelection) return
		val node = event.path?.lastPathComponent as? DefaultMutableTreeNode ?: return
		val item = node.userObject as? HierarchyItem ?: return
		canvas.selectedDeformerId = item.deformerId
		val layerId = item.layerId ?: firstLayerBelow(node)
		if (layerId != null) selection.select(layerId)
	}

	private fun firstLayerBelow(node: DefaultMutableTreeNode): String? {
		val enumeration = node.breadthFirstEnumeration()
		while (enumeration.hasMoreElements()) {
			val child = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
			val item = child.userObject as? HierarchyItem ?: continue
			if (item.layerId != null) return item.layerId
		}
		return null
	}

	private inner class HierarchyRenderer : DefaultTreeCellRenderer() {
		override fun getTreeCellRendererComponent(
			tree: JTree,
			value: Any,
			selected: Boolean,
			expanded: Boolean,
			leaf: Boolean,
			row: Int,
			hasFocus: Boolean,
		): Component {
			val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus) as JLabel
			val node = value as? DefaultMutableTreeNode
			val item = node?.userObject as? HierarchyItem
			val color = item?.let { ComponentPalette.strong(it.key) } ?: Color.GRAY
			component.icon = ColorDotIcon(color, item?.layerId != null)
			return component
		}
	}

	private class ColorDotIcon(
		private val color: Color,
		private val filled: Boolean,
	) : Icon {
		override fun getIconWidth(): Int = 14
		override fun getIconHeight(): Int = 14

		override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
			graphics.color = color
			if (filled) graphics.fillOval(x + 3, y + 3, 8, 8) else graphics.drawRect(x + 3, y + 3, 8, 8)
		}
	}

	private class HierarchyCanvas(
		private val selection: ComponentSelectionModel,
	) : JPanel() {
		private val camera = CanvasCamera(::repaint)

		var visibleLayerIds: Set<String>? = null
			set(value) {
				field = value
				repaint()
			}

		var previewModel: RigPreviewModel? = null
			set(value) {
				val sourceChanged = field?.analysis?.source !== value?.analysis?.source
				field = value
				geometry = value?.let { RigCanvasSupport.evaluate(it) }
				drawableBounds = geometry?.let(RigCanvasSupport::boundsByDrawable).orEmpty()
				deformerBounds = value?.let { RigCanvasSupport.boundsByDeformer(it, drawableBounds) }.orEmpty()
				if (sourceChanged) camera.reset()
				repaint()
			}
		var selectedDeformerId: String? = null
			set(value) {
				field = value
				repaint()
			}

		private var geometry: DeformedGeometry? = null
		private var drawableBounds = emptyMap<String, Bounds>()
		private var deformerBounds = emptyMap<String, Bounds>()

		init {
			background = Color(43, 45, 48)
			minimumSize = Dimension(260, 260)
			camera.install(this, { previewModel }, ::selectAt)
		}

		override fun paintComponent(graphics: Graphics) {
			super.paintComponent(graphics)
			val g = graphics.create() as Graphics2D
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
				RigCanvasSupport.paintChecker(g, width, height)
				val model = previewModel ?: return paintEmpty(g)
				val currentGeometry = geometry ?: return
				val viewport = camera.viewport(model, width, height)
				RigCanvasSupport.paintCanvasBoundary(g, viewport)
				RigCanvasSupport.paintTexturedRig(g, model, currentGeometry, viewport, 0.43f, visibleLayerIds)
				for (deformer in model.rig.puppet.deformers) {
					val bounds = deformerBounds[deformer.id.raw] ?: continue
					val selected = deformer.id.raw == selectedDeformerId
					val color = ComponentPalette.strong(deformer.id.raw)
					RigCanvasSupport.paintBounds(g, bounds, viewport, color, if (selected) 2.8f else 1.15f)
					paintLabel(g, deformer.name, bounds, viewport, color, selected)
				}
				selection.selectedLayerId?.let { layerId ->
					val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
					val bounds = drawableId?.let(drawableBounds::get)
					if (bounds != null) RigCanvasSupport.paintBounds(
						g, bounds, viewport, ComponentPalette.strong(layerId).brighter(), 3.0f,
					)
				}
			} finally {
				g.dispose()
			}
		}

		private fun selectAt(event: MouseEvent) {
			val model = previewModel ?: return
			val viewport = camera.viewport(model, width, height)
			selection.select(
				RigCanvasSupport.hitLayer(
					model = model,
					drawableBounds = drawableBounds,
					canvasX = viewport.canvasX(event.x),
					canvasY = viewport.canvasY(event.y),
					visibleLayerIds = visibleLayerIds,
					currentSelectedLayerId = selection.selectedLayerId,
					geometry = geometry,
				),
			)
		}

		private fun paintLabel(
			g: Graphics2D,
			text: String,
			bounds: Bounds,
			viewport: CanvasViewport,
			color: Color,
			selected: Boolean,
		) {
			g.font = Font(Font.SANS_SERIF, if (selected) Font.BOLD else Font.PLAIN, 11)
			val x = viewport.x(bounds.left).toInt() + 2
			val y = (viewport.offsetY + bounds.top * viewport.scale).toInt() - 3
			val metrics = g.fontMetrics
			val labelY = y.coerceAtLeast(metrics.ascent + 2)
			g.color = Color(24, 26, 30, 205)
			g.fillRoundRect(x - 2, labelY - metrics.ascent, metrics.stringWidth(text) + 7, metrics.height, 5, 5)
			g.color = color.brighter()
			g.drawString(text, x + 1, labelY)
		}

		private fun paintEmpty(graphics: Graphics) {
			graphics.color = Color(180, 184, 190)
			val text = tr("canvas.hierarchy.empty")
			val metrics = graphics.fontMetrics
			graphics.drawString(text, (width - metrics.stringWidth(text)) / 2, height / 2)
		}
	}
}
