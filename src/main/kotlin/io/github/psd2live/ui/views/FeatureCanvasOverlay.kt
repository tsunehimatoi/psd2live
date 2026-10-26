package io.github.psd2live.ui.views

import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.ui.FeatureCanvasTool
import io.github.psd2live.ui.theme.ToolColors

/**
 * The feature tool's canvas layer: each fitted feature's handles joined round its outline - an eye's corners through
 * its lid middles, a brow's ends through its arch, the mouth's corners through its lip middles - in [MeshLook]. A
 * handle the recipe moved is square; the held and hovered ones are lit.
 */
internal fun DrawScope.drawFeatureHandles(tool: FeatureCanvasTool, recipe: RigRecipe, viewport: CanvasViewport, colors: ToolColors) {
	tool.refresh()
	val byFeature = tool.handles.groupBy { it.feature }
	for ((feature, handles) in byFeature) {
		val at = handles.associate { it.handle to tool.screen(it, viewport) }
		val ring = when {
			feature.startsWith("eye") -> listOf("inner", "upper", "outer", "lower", "inner")
			feature.startsWith("brow") -> listOf("inner", "arch", "outer")
			else -> listOf("left", "upper", "right", "lower", "left")
		}
		val points = ring.mapNotNull { at[it] }
		drawMeshWires(points.zipWithNext().flatMap { (a, b) -> listOf(a, b) }, MeshLook.Structure)
		val moved = recipe.moves(feature)
		for (handle in handles) {
			val key = FeatureCanvasTool.Key(feature, handle.handle)
			drawMeshHandle(at.getValue(handle.handle), MeshLook.Structure, colors.accent, selected = tool.active == key,
				hovered = tool.hover == key, shape = if (handle.handle in moved) HandleShape.SQUARE else HandleShape.ROUND)
		}
	}
}
