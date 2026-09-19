package io.github.psd2live.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.TabViewOptions

// ============================================================================
// DCC Vector Icons for Canvas & View Options
// ============================================================================

/** Vector Texture / Artwork Surface Icon */
@Composable
fun IconTextureView(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		// Frame / picture border
		drawRoundRect(
			color = tint,
			topLeft = Offset(w * 0.12f, h * 0.12f),
			size = Size(w * 0.76f, h * 0.76f),
			cornerRadius = CornerRadius(1.5f, 1.5f),
			style = stroke,
		)
		// Artwork landscape peaks
		val path = Path().apply {
			moveTo(w * 0.20f, h * 0.70f)
			lineTo(w * 0.42f, h * 0.44f)
			lineTo(w * 0.58f, h * 0.60f)
			lineTo(w * 0.70f, h * 0.48f)
			lineTo(w * 0.80f, h * 0.68f)
		}
		drawPath(path, color = tint, style = stroke)
		// Artwork sun
		drawCircle(
			color = tint,
			radius = w * 0.08f,
			center = Offset(w * 0.35f, h * 0.32f),
			style = Fill,
		)
	}
}

/** Vector Mesh Wireframe / Polygonal Tessellation Icon */
@Composable
fun IconMeshWireframe(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val pTop = Offset(w * 0.5f, h * 0.14f)
		val pLeft = Offset(w * 0.14f, h * 0.84f)
		val pRight = Offset(w * 0.86f, h * 0.84f)
		val pCenter = Offset(w * 0.5f, h * 0.56f)

		val outline = Path().apply {
			moveTo(pTop.x, pTop.y)
			lineTo(pRight.x, pRight.y)
			lineTo(pLeft.x, pLeft.y)
			close()
		}
		drawPath(outline, color = tint, style = stroke)
		drawLine(tint, pTop, pCenter, strokeWidth = 1.2f, cap = StrokeCap.Round)
		drawLine(tint, pLeft, pCenter, strokeWidth = 1.2f, cap = StrokeCap.Round)
		drawLine(tint, pRight, pCenter, strokeWidth = 1.2f, cap = StrokeCap.Round)

		val dotR = 1.4f
		drawCircle(tint, dotR, pTop, style = Fill)
		drawCircle(tint, dotR, pLeft, style = Fill)
		drawCircle(tint, dotR, pRight, style = Fill)
		drawCircle(tint, dotR, pCenter, style = Fill)
	}
}

/** Vector Live2D Warp Deformer Curved Lattice Icon */
@Composable
fun IconWarpDeformer(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

		val pTL = Offset(w * 0.18f, h * 0.22f)
		val pTR = Offset(w * 0.82f, h * 0.16f)
		val pBR = Offset(w * 0.84f, h * 0.78f)
		val pBL = Offset(w * 0.16f, h * 0.84f)

		val outerPath = Path().apply {
			moveTo(pTL.x, pTL.y)
			quadraticTo(w * 0.50f, h * 0.12f, pTR.x, pTR.y)
			quadraticTo(w * 0.88f, h * 0.48f, pBR.x, pBR.y)
			quadraticTo(w * 0.50f, h * 0.76f, pBL.x, pBL.y)
			quadraticTo(w * 0.12f, h * 0.52f, pTL.x, pTL.y)
			close()
		}
		drawPath(outerPath, color = tint, style = stroke)

		// Subtle internal dashed curve grid
		val hLine = Path().apply {
			moveTo(w * 0.14f, h * 0.53f)
			quadraticTo(w * 0.50f, h * 0.45f, w * 0.86f, h * 0.47f)
		}
		drawPath(hLine, color = tint, style = Stroke(width = 1.0f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2f))))

		val vLine = Path().apply {
			moveTo(w * 0.50f, h * 0.14f)
			quadraticTo(w * 0.53f, h * 0.46f, w * 0.50f, h * 0.76f)
		}
		drawPath(vLine, color = tint, style = Stroke(width = 1.0f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2f))))

		// Control points at corners
		val dotR = 1.5f
		drawCircle(tint, dotR, pTL, style = Fill)
		drawCircle(tint, dotR, pTR, style = Fill)
		drawCircle(tint, dotR, pBR, style = Fill)
		drawCircle(tint, dotR, pBL, style = Fill)
	}
}

/** Vector Selection Transform Bounding Box with Corner Handles Icon */
@Composable
fun IconSelectionBounds(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val l = w * 0.20f
		val r = w * 0.80f
		val t = h * 0.20f
		val b = h * 0.80f

		val boxPath = Path().apply {
			moveTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l, b); close()
		}
		drawPath(
			boxPath,
			color = tint,
			style = Stroke(width = 1.1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2f))),
		)

		val handleSize = 2.2f
		fun drawHandle(cx: Float, cy: Float) {
			drawRect(
				color = tint,
				topLeft = Offset(cx - handleSize, cy - handleSize),
				size = Size(handleSize * 2f, handleSize * 2f),
				style = Fill,
			)
		}
		drawHandle(l, t)
		drawHandle(r, t)
		drawHandle(r, b)
		drawHandle(l, b)
	}
}

/** Vector Contextual / Parent-Child Hierarchy Warp Icon */
@Composable
fun IconContextualWarp(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

		// Parent node (top-left) - dashed deformer box
		val pBoxW = w * 0.36f
		val pBoxH = h * 0.32f
		val pLeft = w * 0.14f
		val pTop = h * 0.14f
		drawRoundRect(
			color = tint,
			topLeft = Offset(pLeft, pTop),
			size = Size(pBoxW, pBoxH),
			cornerRadius = CornerRadius(1.5f, 1.5f),
			style = Stroke(width = 1.1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 1.5f))),
		)

		// Child node (bottom-right) - solid selected element box
		val cBoxW = w * 0.36f
		val cBoxH = h * 0.32f
		val cLeft = w * 0.50f
		val cTop = h * 0.54f
		drawRoundRect(
			color = tint,
			topLeft = Offset(cLeft, cTop),
			size = Size(cBoxW, cBoxH),
			cornerRadius = CornerRadius(1.5f, 1.5f),
			style = stroke,
		)
		drawCircle(tint, radius = 1.2f, center = Offset(cLeft + cBoxW * 0.5f, cTop + cBoxH * 0.5f), style = Fill)

		// Hierarchy connector link
		val linkPath = Path().apply {
			moveTo(pLeft + pBoxW * 0.5f, pTop + pBoxH)
			lineTo(pLeft + pBoxW * 0.5f, cTop + cBoxH * 0.5f)
			lineTo(cLeft, cTop + cBoxH * 0.5f)
		}
		drawPath(linkPath, color = tint, style = stroke)
	}
}

/** Vector Isolate / Selected Only Focus Brackets Icon */
@Composable
fun IconSelectedOnly(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val bracketLen = w * 0.22f

		// 4 corner focus brackets
		val tlPath = Path().apply {
			moveTo(w * 0.14f, h * 0.14f + bracketLen)
			lineTo(w * 0.14f, h * 0.14f)
			lineTo(w * 0.14f + bracketLen, h * 0.14f)
		}
		drawPath(tlPath, color = tint, style = stroke)

		val trPath = Path().apply {
			moveTo(w * 0.86f - bracketLen, h * 0.14f)
			lineTo(w * 0.86f, h * 0.14f)
			lineTo(w * 0.86f, h * 0.14f + bracketLen)
		}
		drawPath(trPath, color = tint, style = stroke)

		val blPath = Path().apply {
			moveTo(w * 0.14f, h * 0.86f - bracketLen)
			lineTo(w * 0.14f, h * 0.86f)
			lineTo(w * 0.14f + bracketLen, h * 0.86f)
		}
		drawPath(blPath, color = tint, style = stroke)

		val brPath = Path().apply {
			moveTo(w * 0.86f - bracketLen, h * 0.86f)
			lineTo(w * 0.86f, h * 0.86f)
			lineTo(w * 0.86f, h * 0.86f - bracketLen)
		}
		drawPath(brPath, color = tint, style = stroke)

		// Center isolated element
		drawRoundRect(
			color = tint,
			topLeft = Offset(w * 0.36f, h * 0.36f),
			size = Size(w * 0.28f, h * 0.28f),
			cornerRadius = CornerRadius(1.2f, 1.2f),
			style = Fill,
		)
	}
}

/** Vector Dim Unselected / Ghosting Layers Icon */
@Composable
fun IconDimUnselected(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

		// Background unselected layer: dimmed/dashed
		val bgPath = Path().apply {
			moveTo(w * 0.44f, h * 0.14f)
			lineTo(w * 0.86f, h * 0.14f)
			lineTo(w * 0.86f, h * 0.56f)
			lineTo(w * 0.60f, h * 0.56f)
		}
		drawPath(
			bgPath,
			color = tint.copy(alpha = 0.4f),
			style = Stroke(width = 1.1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 2f))),
		)

		// Foreground selected layer: solid
		drawRoundRect(
			color = tint,
			topLeft = Offset(w * 0.14f, h * 0.40f),
			size = Size(w * 0.46f, h * 0.46f),
			cornerRadius = CornerRadius(1.5f, 1.5f),
			style = stroke,
		)
		drawCircle(
			color = tint,
			radius = 1.4f,
			center = Offset(w * 0.37f, h * 0.63f),
			style = Fill,
		)
	}
}

/** Vector Text Label / Typography 'T' Icon */
@Composable
fun IconWarpShowNames(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		drawLine(tint, Offset(w * 0.20f, h * 0.22f), Offset(w * 0.80f, h * 0.22f), strokeWidth = 1.4f, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.20f, h * 0.22f), Offset(w * 0.20f, h * 0.32f), strokeWidth = 1.2f, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.80f, h * 0.22f), Offset(w * 0.80f, h * 0.32f), strokeWidth = 1.2f, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.50f, h * 0.22f), Offset(w * 0.50f, h * 0.78f), strokeWidth = 1.4f, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.35f, h * 0.78f), Offset(w * 0.65f, h * 0.78f), strokeWidth = 1.2f, cap = StrokeCap.Round)
	}
}

/** Vector Point Indices / Vertex Number '#' Icon */
@Composable
fun IconWarpShowIndices(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = 1.2f
		drawLine(tint, Offset(w * 0.16f, h * 0.38f), Offset(w * 0.84f, h * 0.38f), strokeWidth = stroke, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.16f, h * 0.64f), Offset(w * 0.84f, h * 0.64f), strokeWidth = stroke, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.40f, h * 0.18f), Offset(w * 0.34f, h * 0.84f), strokeWidth = stroke, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.66f, h * 0.18f), Offset(w * 0.60f, h * 0.84f), strokeWidth = stroke, cap = StrokeCap.Round)
	}
}

/** Vector Path Width / Measurement Dimension '<->' Icon */
@Composable
fun IconPathWidth(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = 1.2f

		drawLine(tint, Offset(w * 0.14f, h * 0.20f), Offset(w * 0.14f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)
		drawLine(tint, Offset(w * 0.86f, h * 0.20f), Offset(w * 0.86f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)

		val midY = h * 0.50f
		drawLine(tint, Offset(w * 0.18f, midY), Offset(w * 0.82f, midY), strokeWidth = stroke)

		val arrowSize = w * 0.16f
		val leftArrow = Path().apply {
			moveTo(w * 0.18f + arrowSize, midY - arrowSize * 0.8f)
			lineTo(w * 0.18f, midY)
			lineTo(w * 0.18f + arrowSize, midY + arrowSize * 0.8f)
		}
		drawPath(leftArrow, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))

		val rightArrow = Path().apply {
			moveTo(w * 0.82f - arrowSize, midY - arrowSize * 0.8f)
			lineTo(w * 0.82f, midY)
			lineTo(w * 0.82f - arrowSize, midY + arrowSize * 0.8f)
		}
		drawPath(rightArrow, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
	}
}

/** Vector Path Hardness / Falloff Curve Profile Icon */
@Composable
fun IconPathHardness(
	tint: Color,
	modifier: Modifier = Modifier.size(14.dp),
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

		val axisPath = Path().apply {
			moveTo(w * 0.16f, h * 0.18f)
			lineTo(w * 0.16f, h * 0.82f)
			lineTo(w * 0.84f, h * 0.82f)
		}
		drawPath(axisPath, color = tint.copy(alpha = 0.5f), style = Stroke(width = 1.0f, cap = StrokeCap.Round))

		val curvePath = Path().apply {
			moveTo(w * 0.16f, h * 0.28f)
			lineTo(w * 0.46f, h * 0.28f)
			cubicTo(w * 0.60f, h * 0.28f, w * 0.62f, h * 0.82f, w * 0.78f, h * 0.82f)
		}
		drawPath(curvePath, color = tint, style = stroke)
		drawCircle(color = tint, radius = 1.4f, center = Offset(w * 0.46f, h * 0.28f), style = Fill)
	}
}

// ============================================================================
// View Options Menu Items
// ============================================================================

/**
 * The single source of truth for the per-tab canvas and annotation toggles, rendered by the tab
 * strip's "view options" dropdown.
 *
 * Items are categorized into three distinct DCC sections:
 * 1. Entities / Canvas & Model (纹理、网格线框、弯曲变形器、旋转变形器、变形路径 + 子选项 路径宽度/路径硬度)
 * 2. Selection & Focus (选中边框、关联变形器、仅显示选中项、淡化未选中)
 * 3. Annotations & Guides (名称、点编号)
 *
 * Deform path entries are `showPathGuides`-gated because path guides are an Edit-tab overlay:
 * offering the toggle on a Preview tab would advertise a switch that cannot change anything.
 */
@Composable
fun ViewOptionsMenuItems(
	options: TabViewOptions,
	onOptionsChange: (TabViewOptions) -> Unit,
	onDismiss: () -> Unit,
	showHeaders: Boolean = true,
	showPathGuides: Boolean = true,
	onHover: (() -> Unit)? = null,
	onReset: (() -> Unit)? = null,
) {
	fun apply(updated: TabViewOptions) {
		onOptionsChange(updated.normalized())
		onDismiss()
	}

	// 1. 画布要素 (Entities / Canvas & Model)
	if (showHeaders) AppMenuHeader(tr("menu.view.category.canvas"))

	AppMenuItem(
		text = tr("canvas.visibility.texture"),
		icon = { IconTextureView(tint = it) },
		isChecked = options.showTexture,
		onHover = onHover,
		onClick = { apply(options.copy(showTexture = !options.showTexture)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.mesh"),
		icon = { IconMeshWireframe(tint = it) },
		isChecked = options.showMesh,
		onHover = onHover,
		onClick = { apply(options.copy(showMesh = !options.showMesh)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.warp"),
		icon = { IconWarpDeformer(tint = it) },
		isChecked = options.showWarp,
		onHover = onHover,
		onClick = { apply(options.copy(showWarp = !options.showWarp)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.rotation"),
		icon = { IconRotationDeformer(tint = it, modifier = Modifier.size(14.dp)) },
		isChecked = options.showRotation,
		onHover = onHover,
		onClick = { apply(options.copy(showRotation = !options.showRotation)) },
	)
	if (showPathGuides) {
		AppMenuItem(
			text = tr("canvas.visibility.paths"),
			icon = { IconDeformPath(tint = it, modifier = Modifier.size(14.dp)) },
			isChecked = options.showDeformPaths,
			onHover = onHover,
			onClick = { apply(options.copy(showDeformPaths = !options.showDeformPaths)) },
		)
		// 变形路径的子选项: width and hardness are annotations *of a path*, so they hang off the
		// entry above rather than sitting in the overlays group as independent toggles. They are
		// disabled -- not hidden -- while the parent is off, so the pair never jumps out of the
		// menu and their remembered state stays discoverable.
		AppMenuItem(
			text = tr("canvas.information.pathWidth"),
			icon = { IconPathWidth(tint = it) },
			isChecked = options.pathShowWidth,
			enabled = options.showDeformPaths,
			isChild = true,
			onHover = onHover,
			onClick = { apply(options.copy(pathShowWidth = !options.pathShowWidth)) },
		)
		AppMenuItem(
			text = tr("canvas.information.pathHardness"),
			icon = { IconPathHardness(tint = it) },
			isChecked = options.pathShowHardness,
			enabled = options.showDeformPaths,
			isChild = true,
			isLastChild = true,
			onHover = onHover,
			onClick = { apply(options.copy(pathShowHardness = !options.pathShowHardness)) },
		)
	}

	AppMenuSeparator()

	// 2. 选区与聚焦 (Selection & Focus)
	if (showHeaders) AppMenuHeader(tr("menu.view.category.selection"))

	AppMenuItem(
		text = tr("canvas.information.selectionBounds"),
		icon = { IconSelectionBounds(tint = it) },
		isChecked = options.showSelectionBounds,
		onHover = onHover,
		onClick = { apply(options.copy(showSelectionBounds = !options.showSelectionBounds)) },
	)
	AppMenuItem(
		text = tr("canvas.information.contextualWarp"),
		icon = { IconContextualWarp(tint = it) },
		isChecked = options.contextualWarp,
		onHover = onHover,
		onClick = { apply(options.copy(contextualWarp = !options.contextualWarp)) },
	)
	AppMenuItem(
		text = tr("canvas.information.selectedOnly"),
		icon = { IconSelectedOnly(tint = it) },
		isChecked = options.filterSelectedOnly,
		onHover = onHover,
		onClick = { apply(options.copy(filterSelectedOnly = !options.filterSelectedOnly)) },
	)
	AppMenuItem(
		text = tr("canvas.visibility.dimUnselected"),
		icon = { IconDimUnselected(tint = it) },
		isChecked = options.dimUnselected,
		onHover = onHover,
		onClick = { apply(options.copy(dimUnselected = !options.dimUnselected)) },
	)

	AppMenuSeparator()

	// 3. 辅助标注 (Annotations & Guides)
	if (showHeaders) AppMenuHeader(tr("menu.view.category.overlays"))

	AppMenuItem(
		text = tr("canvas.information.names"),
		icon = { IconWarpShowNames(tint = it) },
		isChecked = options.warpShowNames,
		onHover = onHover,
		onClick = { apply(options.copy(warpShowNames = !options.warpShowNames)) },
	)
	AppMenuItem(
		text = tr("canvas.information.indices"),
		icon = { IconWarpShowIndices(tint = it) },
		isChecked = options.warpShowIndices,
		onHover = onHover,
		onClick = { apply(options.copy(warpShowIndices = !options.warpShowIndices)) },
	)

	// 4. 底部动作 (Footer Actions)
	if (onReset != null) {
		AppMenuSeparator()
		AppMenuItem(
			text = tr("tab.resetView"),
			icon = { IconReset(tint = it, modifier = Modifier.size(14.dp)) },
			indentCheckSpace = true,
			onHover = onHover,
			onClick = onReset,
		)
	}
}
