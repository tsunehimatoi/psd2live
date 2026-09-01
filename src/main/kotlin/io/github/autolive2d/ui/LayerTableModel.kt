package io.github.autolive2d.ui

import io.github.autolive2d.core.ClassifiedLayer
import io.github.autolive2d.core.LayerClassificationOverride
import io.github.autolive2d.core.PipelineAnalysis
import io.github.autolive2d.core.SemanticTag
import io.github.autolive2d.core.Side
import io.github.autolive2d.i18n.tr
import javax.swing.table.AbstractTableModel

class LayerTableModel : AbstractTableModel() {
	private data class Row(val sourceIndex: Int, val layer: ClassifiedLayer)

	private val columnKeys = arrayOf(
		"layers.header.visibility",
		"layers.header.number",
		"layers.header.name",
		"layers.header.semantic",
		"layers.header.side",
		"layers.header.confidence",
		"layers.header.pixels",
	)
	private var rows = emptyList<Row>()
	private val manualOverrides = linkedMapOf<String, LayerClassificationOverride>()
	private val manualVisibility = linkedMapOf<String, Boolean>()
	private var isolationSnapshot: Map<String, Boolean>? = null
	private var isolatedLayerId: String? = null

	var analysis: PipelineAnalysis? = null
		private set

	var onClassificationChanged: ((String, LayerClassificationOverride) -> Unit)? = null
	var onVisibilityChanged: ((Map<String, Boolean>) -> Unit)? = null

	val classificationOverrides: Map<String, LayerClassificationOverride>
		get() = manualOverrides.toMap()
	val visibilityOverrides: Map<String, Boolean>
		get() = manualVisibility.toMap()
	val visibleLayerIds: Set<String>
		get() = rows.asSequence().map { it.layer }.filter(::effectiveVisibility).mapTo(linkedSetOf()) { it.source.id.raw }

	fun setAnalysis(value: PipelineAnalysis?) {
		analysis = value
		rows = value?.layers.orEmpty()
			.mapIndexed(::Row)
			.sortedWith(
				compareBy<Row> { effectiveSemantic(it.layer).tag == SemanticTag.UNKNOWN }
					.thenBy { it.sourceIndex },
			)
		fireTableDataChanged()
	}

	fun clearOverrides() {
		manualOverrides.clear()
		manualVisibility.clear()
		isolationSnapshot = null
		isolatedLayerId = null
		rows = emptyList()
		analysis = null
		fireTableDataChanged()
	}

	fun languageChanged() = fireTableStructureChanged()

	fun layerAt(modelRow: Int): ClassifiedLayer? = rows.getOrNull(modelRow)?.layer

	fun modelRowForLayerId(layerId: String): Int = rows.indexOfFirst { it.layer.source.id.raw == layerId }
	fun isVisibleAt(modelRow: Int): Boolean = rows.getOrNull(modelRow)?.layer?.let(::effectiveVisibility) ?: false

	fun toggleVisibility(modelRow: Int) = setVisibilityForRows(intArrayOf(modelRow), !isVisibleAt(modelRow))

	fun setVisibilityForRows(modelRows: IntArray, visible: Boolean) {
		for (row in modelRows) rows.getOrNull(row)?.layer?.source?.id?.raw?.let { manualVisibility[it] = visible }
		clearIsolation()
		notifyVisibilityChanged()
	}

	fun setAllVisible(visible: Boolean) {
		for (row in rows) manualVisibility[row.layer.source.id.raw] = visible
		clearIsolation()
		notifyVisibilityChanged()
	}

	fun invertVisibility(modelRows: IntArray = rows.indices.toList().toIntArray()) {
		for (rowIndex in modelRows) {
			val layer = rows.getOrNull(rowIndex)?.layer ?: continue
			manualVisibility[layer.source.id.raw] = !effectiveVisibility(layer)
		}
		clearIsolation()
		notifyVisibilityChanged()
	}

	fun showOnly(modelRows: IntArray) {
		val selected = modelRows.asSequence().mapNotNull { rows.getOrNull(it)?.layer?.source?.id?.raw }.toSet()
		if (selected.isEmpty()) return
		for (row in rows) manualVisibility[row.layer.source.id.raw] = row.layer.source.id.raw in selected
		clearIsolation()
		notifyVisibilityChanged()
	}

	fun isolateOrRestore(modelRow: Int) {
		val layerId = rows.getOrNull(modelRow)?.layer?.source?.id?.raw ?: return
		if (isolatedLayerId == layerId && isolationSnapshot != null) {
			manualVisibility.clear()
			manualVisibility.putAll(checkNotNull(isolationSnapshot))
			clearIsolation()
		} else {
			if (isolationSnapshot == null) isolationSnapshot = manualVisibility.toMap()
			isolatedLayerId = layerId
			for (row in rows) manualVisibility[row.layer.source.id.raw] = row.layer.source.id.raw == layerId
		}
		notifyVisibilityChanged()
	}

	override fun getRowCount(): Int = rows.size
	override fun getColumnCount(): Int = columnKeys.size
	override fun getColumnName(column: Int): String = tr(columnKeys[column])
	override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
		0 -> Boolean::class.javaObjectType
		1, 6 -> Int::class.javaObjectType
		5 -> Float::class.javaObjectType
		else -> String::class.java
	}

	override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 3 || columnIndex == 4

	override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
		val row = rows[rowIndex]
		val layer = row.layer
		val semantic = effectiveSemantic(layer)
		return when (columnIndex) {
			0 -> effectiveVisibility(layer)
			1 -> row.sourceIndex + 1
			2 -> layer.source.name
			3 -> semantic.tag.localizedName()
			4 -> semantic.side.localizedName()
			5 -> if (manualOverrides.containsKey(layer.source.id.raw)) 1f else layer.semantic.confidence
			6 -> layer.opaquePixels
			else -> ""
		}
	}

	override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
		val layer = rows.getOrNull(rowIndex)?.layer ?: return
		val current = effectiveSemantic(layer)
		val updated = when (columnIndex) {
			3 -> {
				val raw = value?.toString() ?: return
				val tag = SemanticTag.entries.firstOrNull {
					it.canonicalName.equals(raw, true) || it.name.equals(raw, true) || it.localizedName() == raw
				} ?: return
				LayerClassificationOverride(tag, current.side)
			}
			4 -> {
				val raw = value?.toString() ?: return
				val side = Side.entries.firstOrNull { it.name.equals(raw, true) || it.localizedName() == raw } ?: return
				LayerClassificationOverride(current.tag, side)
			}
			else -> return
		}
		val layerId = layer.source.id.raw
		manualOverrides[layerId] = updated
		fireTableRowsUpdated(rowIndex, rowIndex)
		onClassificationChanged?.invoke(layerId, updated)
	}

	private fun effectiveSemantic(layer: ClassifiedLayer) = manualOverrides[layer.source.id.raw]?.let { override ->
		layer.semantic.copy(tag = override.tag, side = override.side, confidence = 1f)
	} ?: layer.semantic

	private fun effectiveVisibility(layer: ClassifiedLayer): Boolean {
		val layerId = layer.source.id.raw
		manualVisibility[layerId]?.let { return it }
		val parentId = when {
			layerId.endsWith(":l") || layerId.endsWith(":r") -> layerId.dropLast(2)
			else -> null
		}
		return parentId?.let(manualVisibility::get) ?: layer.source.visible
	}

	private fun notifyVisibilityChanged() {
		if (rows.isNotEmpty()) fireTableRowsUpdated(0, rowCount - 1)
		onVisibilityChanged?.invoke(visibilityOverrides)
	}

	private fun clearIsolation() {
		isolationSnapshot = null
		isolatedLayerId = null
	}
}

internal fun SemanticTag.localizedName(): String = tr("semantic.${name.lowercase()}")

internal fun Side.localizedName(): String = tr("side.${name.lowercase()}")
