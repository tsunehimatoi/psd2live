package io.github.autolive2d.ui

import io.github.autolive2d.core.PipelineAnalysis
import javax.swing.table.AbstractTableModel

class LayerTableModel : AbstractTableModel() {
	var analysis: PipelineAnalysis? = null
		set(value) {
			field = value
			fireTableDataChanged()
		}

	private val columns = arrayOf("#", "PSD 图层", "语义", "侧别", "置信度", "像素")
	override fun getRowCount(): Int = analysis?.layers?.size ?: 0
	override fun getColumnCount(): Int = columns.size
	override fun getColumnName(column: Int): String = columns[column]
	override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
		0, 5 -> Int::class.javaObjectType
		4 -> Float::class.javaObjectType
		else -> String::class.java
	}

	override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
		val layer = checkNotNull(analysis).layers[rowIndex]
		return when (columnIndex) {
			0 -> rowIndex + 1
			1 -> layer.source.name
			2 -> layer.semantic.tag.canonicalName
			3 -> layer.semantic.side.name
			4 -> layer.semantic.confidence
			5 -> layer.opaquePixels
			else -> ""
		}
	}
}
