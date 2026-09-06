package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
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
 * Scale: 0 at bottom, 1000 at top.
 * - Plots each layer/drawable as a colored tick mark on the vertical track.
 * - Highlights the currently selected layer with an accent indicator line and value badge.
 * - Allows dragging vertically to modify the selected layer's draw order in real time.
 * - Hover tooltip displays layer name, effective draw order, and default value.
 * - Right-click or double-click opens a precision numeric input dialog (0..1000).
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
	var isDragging by remember { mutableStateOf(false) }

	val padTop = 18f
	val padBottom = 18f

	fun orderToY(order: Float, h: Float): Float {
		val usable = (h - padTop - padBottom).coerceAtLeast(1f)
		return h - padBottom - (order.coerceIn(0f, 1000f) / 1000f) * usable
	}

	fun yToOrder(y: Float, h: Float): Float {
		val usable = (h - padTop - padBottom).coerceAtLeast(1f)
		return (((h - padBottom - y) / usable) * 1000f).coerceIn(0f, 1000f)
	}

	val drawables = model?.rig?.puppet?.drawables.orEmpty()

	// Compute order entries with y coordinates
	val entries = remember(drawables, state.drawOrderOverrides, state.selectedLayerId, rulerHeightPx) {
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
			.width(40.dp)
			.fillMaxHeight()
			.background(colors.panelElevated.copy(alpha = 0.5f))
			.border(BorderStroke(1.dp, colors.divider))
			.onGloballyPositioned { rulerHeightPx = it.size.height.toFloat().coerceAtLeast(1f) }
			.pointerHoverIcon(
				PointerIcon(
					if (isDragging) Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
					else if (hoveredEntry != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
					else Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
				)
			)
			.onPointerEvent(PointerEventType.Move) { event ->
				mousePos = event.changes.firstOrNull()?.position
			}
			.onPointerEvent(PointerEventType.Exit) {
				if (!isDragging) mousePos = null
			}
			.pointerInput(entries, state.selectedLayerId, rulerHeightPx) {
				detectTapGestures(
					onDoubleTap = { pos ->
						val target = entries.filter { abs(it.y - pos.y) <= 10f }.minByOrNull { abs(it.y - pos.y) } ?: selectedEntry
						if (target != null) {
							onRequestSetOrder?.invoke(
								target.layerId,
								target.drawable.name,
								target.effectiveOrder,
								target.defaultOrder,
								target.isOverridden,
							)
						}
					},
					onPress = { pos ->
						val hit = entries.filter { abs(it.y - pos.y) <= 8f }.minByOrNull { abs(it.y - pos.y) }
						if (hit != null && hit.layerId != state.selectedLayerId) {
							viewModel.selectLayer(hit.layerId)
						}
					}
				)
			}
			.pointerInput(entries, state.selectedLayerId, rulerHeightPx) {
				detectVerticalDragGestures(
					onDragStart = { pos ->
						isDragging = true
						val hit = entries.filter { abs(it.y - pos.y) <= 8f }.minByOrNull { abs(it.y - pos.y) }
						if (hit != null && hit.layerId != state.selectedLayerId) {
							viewModel.selectLayer(hit.layerId)
						}
					},
					onDragEnd = { isDragging = false },
					onDragCancel = { isDragging = false },
					onVerticalDrag = { change, _ ->
						change.consume()
						val targetId = state.selectedLayerId ?: hoveredEntry?.layerId ?: return@detectVerticalDragGestures
						val newOrder = yToOrder(change.position.y, rulerHeightPx).roundToInt().toFloat()
						viewModel.setLayerDrawOrder(targetId, newOrder)
					}
				)
			}
			.onPointerEvent(PointerEventType.Press) { event ->
				if (event.button == PointerButton.Secondary) {
					val pos = event.changes.firstOrNull()?.position ?: Offset.Zero
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
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val w = size.width
			val h = size.height

			val trackX = w - 6f

			// Vertical track guide line
			drawLine(
				color = colors.divider,
				start = Offset(trackX, padTop),
				end = Offset(trackX, h - padBottom),
				strokeWidth = 1f,
			)

			// Major scale ticks (every 100 units) and minor ticks (every 50 units)
			for (order in 0..1000 step 50) {
				val y = orderToY(order.toFloat(), h)
				val isMajor = (order % 100 == 0)

				if (isMajor) {
					// Tick line extending left from track
					drawLine(
						color = colors.borderHover.copy(alpha = 0.6f),
						start = Offset(trackX - 6f, y),
						end = Offset(trackX, y),
						strokeWidth = 1f,
					)

					// Scale number (e.g. 1000, 900, ..., 0)
					val label = "$order"
					val textResult = textMeasurer.measure(
						text = label,
						style = TextStyle(
							fontSize = 7.5.sp,
							fontFamily = FontFamily.Monospace,
							fontWeight = FontWeight.Normal,
							color = colors.textMuted.copy(alpha = 0.5f),
						)
					)
					drawText(
						textLayoutResult = textResult,
						topLeft = Offset((trackX - 8f - textResult.size.width).coerceAtLeast(1f), y - textResult.size.height * 0.5f),
					)
				} else {
					// Minor tick line
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
				val awtColor = ComponentPalette.strong(entry.layerId)
				val markColor = Color(awtColor.red, awtColor.green, awtColor.blue)

				// Tick across the track
				drawLine(
					color = markColor.copy(alpha = 0.8f),
					start = Offset(trackX - 12f, entry.y),
					end = Offset(trackX + 2f, entry.y),
					strokeWidth = 1.6f,
				)
			}

			// Draw selected layer indicator on top
			if (selectedEntry != null) {
				val selY = selectedEntry.y

				// Highlight line across the full ruler width
				drawLine(
					color = colors.accent,
					start = Offset(2f, selY),
					end = Offset(w - 1f, selY),
					strokeWidth = 2.2f,
				)

				// Handle / Pointer thumb at the right edge
				val pointerPath = Path().apply {
					moveTo(w, selY)
					lineTo(w - 5f, selY - 4f)
					lineTo(w - 5f, selY + 4f)
					close()
				}
				drawPath(pointerPath, color = colors.accent)

				// Small indicator dot at left edge
				drawCircle(
					color = colors.accent,
					radius = 2.5f,
					center = Offset(4f, selY),
				)
			}
		}

		// Tooltip overlay on hover
		val tooltipItem = hoveredEntry
		val currentMouse = mousePos
		if (tooltipItem != null && currentMouse != null && !isDragging) {
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

			// Show tooltip using a popup placed to the right of the ruler (into the canvas)
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

