package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ComponentPalette
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.model.Drawable
import java.awt.Cursor
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class DrawableOrderEntry(
	val drawable: Drawable,
	val layerId: String,
	val effectiveOrder: Float,
	val defaultOrder: Float,
	val isOverridden: Boolean,
	val isSelected: Boolean,
	val y: Float,
)

/**
 * Vertical Draw Order Ruler replicating Live2D Cubism Editor's vertical draw order slider.
 * - Auto-scales height to fit active layer orders instead of statically fixing 0..1000.
 * - Mouse Wheel: Zooms ruler scale in/out centered at mouse cursor with adaptive dynamic ticks.
 * - Left Mouse Drag: Adjusts selected layer's draw order value in real-time.
 * - Right Mouse Drag: Pans the ruler up/down along the scale.
 * - Right Click (or Double Click): Opens precision numeric input dialog.
 * - Hover Tooltip: Displays layer name, effective draw order, and default value.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrawOrderRuler(
	model: RigPreviewModel?,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
	onRequestSetOrder: ((targetId: String, name: String, currentOrder: Float, defaultOrder: Float, isOverridden: Boolean) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val textMeasurer = rememberTextMeasurer()

	var rulerHeightPx by remember { mutableStateOf(1f) }
	var mousePos by remember { mutableStateOf<Offset?>(null) }
	var lastMousePos by remember { mutableStateOf<Offset?>(null) }

	var isLeftDragging by remember { mutableStateOf(false) }
	var isRightDragging by remember { mutableStateOf(false) }
	var rightPressPos by remember { mutableStateOf<Offset?>(null) }
	var activeDragTargetId by remember { mutableStateOf<String?>(null) }
	var lastClickTime by remember { mutableStateOf(0L) }

	val padTop = 18f
	val padBottom = 18f

	val drawables = model?.rig?.puppet?.drawables.orEmpty()

	// Compute initial framing based on layer orders
	fun computeFittedRange(): Pair<Float, Float> {
		if (model == null || drawables.isEmpty()) return 500f to 1000f
		val orders = drawables.map { drawable ->
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
			state.getEffectiveDrawOrder(drawable.id.raw, layerId, drawable.drawOrder)
		}
		val minO = orders.minOrNull() ?: 0f
		val maxO = orders.maxOrNull() ?: 1000f
		val span = (maxO - minO).coerceAtLeast(10f)
		val center = (minO + maxO) / 2f
		val paddedSpan = (span * 1.35f).coerceIn(15f, 1500f)
		return center to paddedSpan
	}

	val initialFit = remember(model) { computeFittedRange() }
	var viewCenter by remember(model) { mutableStateOf(initialFit.first) }
	var viewSpan by remember(model) { mutableStateOf(initialFit.second) }

	val visibleMin = viewCenter - viewSpan / 2f
	val visibleMax = viewCenter + viewSpan / 2f

	fun orderToY(order: Float, h: Float): Float {
		val usable = (h - padTop - padBottom).coerceAtLeast(1f)
		val frac = (order - visibleMin) / viewSpan.coerceAtLeast(0.01f)
		return h - padBottom - frac * usable
	}

	fun yToOrder(y: Float, h: Float): Float {
		val usable = (h - padTop - padBottom).coerceAtLeast(1f)
		val frac = (h - padBottom - y) / usable
		return visibleMin + frac * viewSpan
	}

	// Compute order entries with dynamically mapped y coordinates
	val entries = remember(drawables, state.drawOrderOverrides, state.selectedLayerId, rulerHeightPx, visibleMin, visibleMax) {
		drawables.mapNotNull { drawable ->
			val layerId = model?.rig?.layerIdByDrawableId?.get(drawable.id.raw) ?: drawable.id.raw
			val effective = state.getEffectiveDrawOrder(drawable.id.raw, layerId, drawable.drawOrder)
			val isOverridden = state.drawOrderOverrides.containsKey(layerId) || state.drawOrderOverrides.containsKey(drawable.id.raw)
			val isSelected = state.selectedLayerId != null && (state.selectedLayerId == layerId || state.selectedLayerId == drawable.id.raw)
			val y = orderToY(effective, rulerHeightPx)
			DrawableOrderEntry(
				drawable = drawable,
				layerId = layerId,
				effectiveOrder = effective,
				defaultOrder = drawable.drawOrder,
				isOverridden = isOverridden,
				isSelected = isSelected,
				y = y,
			)
		}
	}

	val selectedEntry = entries.firstOrNull { it.isSelected }

	// Closest drawable to mouse pointer within threshold
	val hoveredEntry = remember(mousePos, entries) {
		val pos = mousePos ?: return@remember null
		entries
			.filter { abs(it.y - pos.y) <= 8f }
			.minByOrNull { abs(it.y - pos.y) }
	}

	Box(
		modifier = modifier
			.width(42.dp)
			.fillMaxHeight()
			.background(colors.panelElevated.copy(alpha = 0.5f))
			.border(BorderStroke(1.dp, colors.divider))
			.onGloballyPositioned { rulerHeightPx = it.size.height.toFloat().coerceAtLeast(1f) }
			.pointerHoverIcon(
				PointerIcon(
					when {
						isRightDragging -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
						isLeftDragging -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
						hoveredEntry != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
						else -> Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
					}
				)
			)
			// Mouse Wheel: Zoom scale in/out centered at mouse cursor
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val scrollDelta = change.scrollDelta.y
				if (scrollDelta == 0f) return@onPointerEvent

				val mouseOrder = yToOrder(change.position.y, rulerHeightPx)
				val zoomFactor = if (scrollDelta < 0f) 0.85f else 1.18f
				val newSpan = (viewSpan * zoomFactor).coerceIn(4f, 5000f)
				val actualFactor = newSpan / viewSpan
				viewSpan = newSpan
				viewCenter = mouseOrder + (viewCenter - mouseOrder) * actualFactor
			}
			// Pointer Press: Left (select/drag value or double-click dialog) or Right (pan start)
			.onPointerEvent(PointerEventType.Press) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val pos = change.position
				lastMousePos = pos

				if (event.button == PointerButton.Primary) {
					val now = System.currentTimeMillis()
					val isDoubleClick = (now - lastClickTime) < 350L
					lastClickTime = now

					val hit = entries.filter { abs(it.y - pos.y) <= 8f }.minByOrNull { abs(it.y - pos.y) }
					val targetEntry = hit ?: selectedEntry

					if (isDoubleClick) {
						if (targetEntry != null) {
							onRequestSetOrder?.invoke(
								targetEntry.layerId,
								targetEntry.drawable.name,
								targetEntry.effectiveOrder,
								targetEntry.defaultOrder,
								targetEntry.isOverridden,
							)
						} else {
							// Double click on empty track area: Reset/fit to all drawables
							val (c, s) = computeFittedRange()
							viewCenter = c
							viewSpan = s
						}
						return@onPointerEvent
					}

					if (hit != null && hit.layerId != state.selectedLayerId) {
						viewModel.selectLayer(hit.layerId)
					}

					val targetId = hit?.layerId ?: state.selectedLayerId
					if (targetId != null) {
						isLeftDragging = true
						activeDragTargetId = targetId
						val newOrder = yToOrder(pos.y, rulerHeightPx).roundToInt().toFloat().coerceIn(0f, 1000f)
						viewModel.setLayerDrawOrder(targetId, newOrder)
					}
				} else if (event.button == PointerButton.Secondary) {
					isRightDragging = true
					rightPressPos = pos
				}
			}
			// Pointer Move: Left Drag (adjust order) or Right Drag (pan ruler)
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				val pos = change.position
				val prev = lastMousePos ?: pos
				lastMousePos = pos
				mousePos = pos

				if (isLeftDragging && activeDragTargetId != null) {
					val newOrder = yToOrder(pos.y, rulerHeightPx).roundToInt().toFloat().coerceIn(0f, 1000f)
					viewModel.setLayerDrawOrder(activeDragTargetId!!, newOrder)
				} else if (isRightDragging) {
					val deltaY = pos.y - prev.y
					val usable = (rulerHeightPx - padTop - padBottom).coerceAtLeast(1f)
					val deltaOrder = (deltaY / usable) * viewSpan
					viewCenter += deltaOrder
				}
			}
			// Pointer Release: End Left Drag, or End Right Drag (if moved < 4px, trigger dialog)
			.onPointerEvent(PointerEventType.Release) { event ->
				val change = event.changes.firstOrNull()
				val pos = change?.position ?: lastMousePos ?: Offset.Zero

				if (event.button == PointerButton.Primary) {
					isLeftDragging = false
					activeDragTargetId = null
				} else if (event.button == PointerButton.Secondary) {
					val press = rightPressPos
					isRightDragging = false
					rightPressPos = null
					if (press != null && (pos - press).getDistance() < 5f) {
						val target = entries.filter { abs(it.y - pos.y) <= 12f }.minByOrNull { abs(it.y - pos.y) } ?: selectedEntry
						if (target != null) {
							onRequestSetOrder?.invoke(
								target.layerId,
								target.drawable.name,
								target.effectiveOrder,
								target.defaultOrder,
								target.isOverridden,
							)
						}
					}
				}
			}
			.onPointerEvent(PointerEventType.Exit) {
				if (!isLeftDragging && !isRightDragging) {
					mousePos = null
				}
			}
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val w = size.width
			val h = size.height

			val trackX = w - 6f
			val usable = (h - padTop - padBottom).coerceAtLeast(1f)

			// Vertical track guide line
			drawLine(
				color = colors.divider,
				start = Offset(trackX, padTop),
				end = Offset(trackX, h - padBottom),
				strokeWidth = 1f,
			)

			// Compute dynamic adaptive tick interval based on pixel height
			val pixelsPerUnit = usable / viewSpan.coerceAtLeast(0.01f)
			val idealUnitStep = (46f / pixelsPerUnit).coerceAtLeast(0.01f)

			fun computeNiceStep(raw: Float): Float {
				val exponent = kotlin.math.floor(kotlin.math.log10(raw.toDouble())).toInt()
				val base = 10.0.pow(exponent.toDouble()).toFloat()
				val fraction = raw / base
				val niceFraction = when {
					fraction <= 1.2f -> 1f
					fraction <= 2.5f -> 2f
					fraction <= 6.0f -> 5f
					else -> 10f
				}
				return niceFraction * base
			}

			val majorStep = computeNiceStep(idealUnitStep).coerceAtLeast(1f)
			val minorStep = (majorStep / 2f).coerceAtLeast(0.5f)

			val firstMinor = kotlin.math.floor(visibleMin / minorStep).toInt()
			val lastMinor = kotlin.math.ceil(visibleMax / minorStep).toInt()

			// Draw adaptive ticks and labels
			for (i in firstMinor..lastMinor) {
				val order = i * minorStep
				val y = orderToY(order, h)
				if (y < padTop - 2f || y > h - padBottom + 2f) continue

				val isMajor = abs(order % majorStep) < (minorStep * 0.1f) || abs(order % majorStep - majorStep) < (minorStep * 0.1f)

				if (isMajor) {
					drawLine(
						color = colors.borderHover.copy(alpha = 0.6f),
						start = Offset(trackX - 6f, y),
						end = Offset(trackX, y),
						strokeWidth = 1f,
					)

					val label = if (majorStep >= 1f) order.roundToInt().toString() else "%.1f".format(order)
					val textResult = textMeasurer.measure(
						text = label,
						style = TextStyle(
							fontSize = 7.5.sp,
							fontFamily = FontFamily.Monospace,
							fontWeight = FontWeight.Normal,
							color = colors.textMuted.copy(alpha = 0.55f),
						)
					)
					drawText(
						textLayoutResult = textResult,
						topLeft = Offset((trackX - 8f - textResult.size.width).coerceAtLeast(1f), y - textResult.size.height * 0.5f),
					)
				} else {
					drawLine(
						color = colors.border.copy(alpha = 0.35f),
						start = Offset(trackX - 3f, y),
						end = Offset(trackX, y),
						strokeWidth = 1f,
					)
				}
			}

			// Draw all layers as colored horizontal tick marks
			for (entry in entries) {
				if (entry.isSelected) continue // Selected layer drawn on top
				val y = entry.y
				if (y < padTop - 2f || y > h - padBottom + 2f) continue

				val awtColor = ComponentPalette.strong(entry.layerId)
				val markColor = Color(awtColor.red, awtColor.green, awtColor.blue)

				drawLine(
					color = markColor.copy(alpha = 0.85f),
					start = Offset(trackX - 12f, y),
					end = Offset(trackX + 2f, y),
					strokeWidth = 1.6f,
				)
			}

			// Draw selected layer indicator
			if (selectedEntry != null) {
				val selY = selectedEntry.y
				val isWithinViewport = selY in (padTop - 2f)..(h - padBottom + 2f)

				if (isWithinViewport) {
					// Highlight line across full ruler width
					drawLine(
						color = colors.accent,
						start = Offset(2f, selY),
						end = Offset(w - 1f, selY),
						strokeWidth = 2.2f,
					)

					// Pointer thumb at right edge
					val pointerPath = Path().apply {
						moveTo(w, selY)
						lineTo(w - 5f, selY - 4f)
						lineTo(w - 5f, selY + 4f)
						close()
					}
					drawPath(pointerPath, color = colors.accent)

					// Indicator dot at left edge
					drawCircle(
						color = colors.accent,
						radius = 2.5f,
						center = Offset(4f, selY),
					)
				} else {
					// Off-screen indicator arrows
					val isAbove = selectedEntry.effectiveOrder > visibleMax
					val arrowY = if (isAbove) padTop + 2f else h - padBottom - 2f
					val arrowDir = if (isAbove) -1f else 1f

					val offscreenArrow = Path().apply {
						moveTo(trackX - 3f, arrowY)
						lineTo(trackX - 6f, arrowY - 4f * arrowDir)
						lineTo(trackX, arrowY - 4f * arrowDir)
						close()
					}
					drawPath(offscreenArrow, color = colors.accent.copy(alpha = 0.85f))
				}
			}
		}

		// Tooltip overlay on hover
		val tooltipItem = hoveredEntry
		val currentMouse = mousePos
		if (tooltipItem != null && currentMouse != null && !isLeftDragging && !isRightDragging) {
			val isOverridden = tooltipItem.isOverridden
			val tooltipText = buildString {
				append(tooltipItem.drawable.name)
				append(" · ")
				append(tr("canvas.drawOrder.title"))
				append(": ")
				append(tooltipItem.effectiveOrder.roundToInt())
				if (isOverridden) {
					append(" (")
					append(tr("canvas.drawOrder.reset").substringBefore("为").removePrefix("重置").trim())
					append(": ")
					append(tooltipItem.defaultOrder.roundToInt())
					append(")")
				}
			}

			Popup(
				popupPositionProvider = object : PopupPositionProvider {
					override fun calculatePosition(
						anchorBounds: IntRect,
						windowSize: IntSize,
						layoutDirection: LayoutDirection,
						popupContentSize: IntSize,
					): IntOffset {
						val x = anchorBounds.right + 6
						val y = (anchorBounds.top + currentMouse.y - popupContentSize.height / 2f).roundToInt()
							.coerceIn(8, windowSize.height - popupContentSize.height - 8)
						return IntOffset(x, y)
					}
				}
			) {
				Box(
					modifier = Modifier
						.background(colors.panelElevated, RoundedCornerShape(3.dp))
						.border(BorderStroke(1.dp, colors.borderHover), RoundedCornerShape(3.dp))
						.padding(horizontal = 7.dp, vertical = 4.dp),
				) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp),
					) {
						val awtColor = ComponentPalette.strong(tooltipItem.layerId)
						Box(
							modifier = Modifier
								.size(6.dp)
								.background(Color(awtColor.red, awtColor.green, awtColor.blue), RoundedCornerShape(1.dp))
						)
						Text(
							text = tooltipText,
							style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
							color = colors.textPrimary,
						)
					}
				}
			}
		}
	}
}

/**
 * Modal Dialog for precise numeric input of Draw Order (0..1000).
 */
@Composable
fun DrawOrderInputDialog(
	targetId: String,
	targetName: String,
	initialOrder: Float,
	defaultOrder: Float,
	isOverridden: Boolean,
	onConfirm: (Float) -> Unit,
	onReset: () -> Unit,
	onDismiss: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	var textValue by remember(initialOrder) { mutableStateOf(initialOrder.roundToInt().toString()) }
	val currentFloat = textValue.toFloatOrNull() ?: initialOrder
	val isValid = currentFloat in 0f..1000f

	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color(0x88000000))
			.clickable(onClick = onDismiss),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(320.dp)
				.background(colors.panelBackground, RoundedCornerShape(6.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
				.clickable(enabled = false) {}
				.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			// Title Bar
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					val awtColor = ComponentPalette.strong(targetId)
					Box(
						modifier = Modifier
							.size(8.dp)
							.background(Color(awtColor.red, awtColor.green, awtColor.blue), RoundedCornerShape(2.dp))
					)
					Text(
						text = tr("canvas.drawOrder.title"),
						style = typography.title.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
						color = colors.textPrimary,
					)
				}
				CompactIconButton(onClick = onDismiss, size = 20.dp) {
					IconClose(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			}

			// Target Name & Default
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				Text(
					text = targetName,
					style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
				)
				Text(
					text = "${tr("canvas.drawOrder.inputPrompt")} · 默认: ${defaultOrder.roundToInt()}",
					style = typography.caption.copy(fontSize = 9.5.sp),
					color = colors.textMuted,
				)
			}

			// Exact Numeric Input Field
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				CompactTextField(
					value = textValue,
					onValueChange = { input ->
						val filtered = input.filter { it.isDigit() || it == '.' }
						textValue = filtered
					},
					placeholder = "0..1000",
					isMono = true,
					modifier = Modifier.weight(1f),
					height = 28.dp,
				)

				if (isOverridden) {
					CompactButton(
						text = tr("canvas.drawOrder.reset"),
						onClick = {
							onReset()
							onDismiss()
						},
						height = 28.dp,
					)
				}
			}

			// Quick adjustment step buttons
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				fun adjust(delta: Int) {
					val curr = textValue.toIntOrNull() ?: initialOrder.roundToInt()
					val updated = (curr + delta).coerceIn(0, 1000)
					textValue = updated.toString()
				}

				CompactButton(text = "-100", onClick = { adjust(-100) }, modifier = Modifier.weight(1f), height = 22.dp)
				CompactButton(text = "-10", onClick = { adjust(-10) }, modifier = Modifier.weight(1f), height = 22.dp)
				CompactButton(text = "-1", onClick = { adjust(-1) }, modifier = Modifier.weight(1f), height = 22.dp)
				CompactButton(text = "+1", onClick = { adjust(1) }, modifier = Modifier.weight(1f), height = 22.dp)
				CompactButton(text = "+10", onClick = { adjust(10) }, modifier = Modifier.weight(1f), height = 22.dp)
				CompactButton(text = "+100", onClick = { adjust(100) }, modifier = Modifier.weight(1f), height = 22.dp)
			}

			// Confirmation Buttons
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.End,
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactButton(
					text = tr("project.cancel"),
					onClick = onDismiss,
					height = 24.dp,
				)
				Spacer(Modifier.width(8.dp))
				CompactButton(
					text = tr("canvas.drawOrder.title"),
					onClick = {
						if (isValid) {
							onConfirm(currentFloat)
							onDismiss()
						}
					},
					enabled = isValid,
					isPrimary = true,
					height = 24.dp,
				)
			}
		}
	}
}
