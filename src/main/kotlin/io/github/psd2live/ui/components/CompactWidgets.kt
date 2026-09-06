package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/** Vector Eye Icon (Visible or Hidden/Crossed-out) */
@Composable
fun IconEye(
	visible: Boolean,
	modifier: Modifier = Modifier.size(16.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val eyePath = Path().apply {
			moveTo(w * 0.1f, h * 0.5f)
			cubicTo(w * 0.3f, h * 0.2f, w * 0.7f, h * 0.2f, w * 0.9f, h * 0.5f)
			cubicTo(w * 0.7f, h * 0.8f, w * 0.3f, h * 0.8f, w * 0.1f, h * 0.5f)
		}
		drawPath(eyePath, color = tint, style = stroke)
		if (visible) {
			drawCircle(color = tint, radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.5f), style = Fill)
		} else {
			drawLine(
				color = tint,
				start = Offset(w * 0.2f, h * 0.2f),
				end = Offset(w * 0.8f, h * 0.8f),
				strokeWidth = 1.4f,
				cap = StrokeCap.Round,
			)
		}
	}
}

/** Vector Play Icon */
@Composable
fun IconPlay(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val path = Path().apply {
			moveTo(w * 0.25f, h * 0.15f)
			lineTo(w * 0.85f, h * 0.5f)
			lineTo(w * 0.25f, h * 0.85f)
			close()
		}
		drawPath(path, color = tint, style = Fill)
	}
}

/** Vector Pause Icon */
@Composable
fun IconPause(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val barW = w * 0.22f
		drawRect(color = tint, topLeft = Offset(w * 0.22f, h * 0.18f), size = Size(barW, h * 0.64f))
		drawRect(color = tint, topLeft = Offset(w * 0.56f, h * 0.18f), size = Size(barW, h * 0.64f))
	}
}

/** Vector Reset / Revert Arrow Icon */
@Composable
fun IconReset(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val arcPath = Path().apply {
			arcTo(
				rect = androidx.compose.ui.geometry.Rect(w * 0.15f, h * 0.15f, w * 0.85f, h * 0.85f),
				startAngleDegrees = 45f,
				sweepAngleDegrees = 270f,
				forceMoveTo = false,
			)
		}
		drawPath(arcPath, color = tint, style = stroke)
		val arrowPath = Path().apply {
			moveTo(w * 0.55f, h * 0.1f)
			lineTo(w * 0.85f, h * 0.28f)
			lineTo(w * 0.62f, h * 0.45f)
		}
		drawPath(arrowPath, color = tint, style = stroke)
	}
}

/** Vector Lock / Unlock Icon */
@Composable
fun IconLock(
	locked: Boolean,
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)

		val bodyTop = h * 0.45f
		val bodyH = h * 0.45f
		val bodyW = w * 0.72f
		val bodyL = (w - bodyW) * 0.5f

		if (locked) {
			// Closed Shackle
			val shacklePath = Path().apply {
				moveTo(w * 0.30f, bodyTop)
				lineTo(w * 0.30f, h * 0.28f)
				arcTo(
					rect = androidx.compose.ui.geometry.Rect(w * 0.30f, h * 0.12f, w * 0.70f, h * 0.44f),
					startAngleDegrees = 180f,
					sweepAngleDegrees = 180f,
					forceMoveTo = false,
				)
				lineTo(w * 0.70f, bodyTop)
			}
			drawPath(shacklePath, color = tint, style = stroke)
		} else {
			// Open Shackle
			val shacklePath = Path().apply {
				moveTo(w * 0.28f, bodyTop)
				lineTo(w * 0.28f, h * 0.22f)
				arcTo(
					rect = androidx.compose.ui.geometry.Rect(w * 0.28f, h * 0.06f, w * 0.68f, h * 0.38f),
					startAngleDegrees = 180f,
					sweepAngleDegrees = 180f,
					forceMoveTo = false,
				)
				lineTo(w * 0.68f, h * 0.26f)
			}
			drawPath(shacklePath, color = tint, style = stroke)
		}

		// Body
		drawRoundRect(
			color = tint,
			topLeft = Offset(bodyL, bodyTop),
			size = Size(bodyW, bodyH),
			cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
			style = if (locked) Fill else stroke,
		)

		if (locked) {
			drawCircle(
				color = Color(0xFF1E1F22),
				radius = 1.3f,
				center = Offset(w * 0.5f, bodyTop + bodyH * 0.45f),
				style = Fill,
			)
		}
	}
}

/** Vector Mouse Pointer / Tracking Icon */
@Composable
fun IconMouse(
	active: Boolean,
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val cursorPath = Path().apply {
			moveTo(w * 0.20f, h * 0.10f)
			lineTo(w * 0.20f, h * 0.88f)
			lineTo(w * 0.44f, h * 0.64f)
			lineTo(w * 0.68f, h * 0.88f)
			lineTo(w * 0.82f, h * 0.74f)
			lineTo(w * 0.56f, h * 0.52f)
			lineTo(w * 0.85f, h * 0.52f)
			close()
		}
		drawPath(cursorPath, color = tint, style = if (active) Fill else stroke)
	}
}

/** Vector Search Glass Icon */
@Composable
fun IconSearch(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round)
		drawCircle(color = tint, radius = w * 0.32f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
		drawLine(
			color = tint,
			start = Offset(w * 0.66f, h * 0.66f),
			end = Offset(w * 0.88f, h * 0.88f),
			strokeWidth = 1.4f,
			cap = StrokeCap.Round,
		)
	}
}

/** Vector Folder Icon */
@Composable
fun IconFolder(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color = LocalToolColors.current.textPrimary,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val path = Path().apply {
			moveTo(w * 0.1f, h * 0.25f)
			lineTo(w * 0.4f, h * 0.25f)
			lineTo(w * 0.5f, h * 0.38f)
			lineTo(w * 0.9f, h * 0.38f)
			lineTo(w * 0.9f, h * 0.8f)
			lineTo(w * 0.1f, h * 0.8f)
			close()
		}
		drawPath(path, color = tint, style = stroke)
	}
}

/** Vector Chevron / Triangle Icon */
@Composable
fun IconChevron(
	expanded: Boolean,
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val path = Path().apply {
			if (expanded) {
				moveTo(w * 0.25f, h * 0.35f)
				lineTo(w * 0.5f, h * 0.65f)
				lineTo(w * 0.75f, h * 0.35f)
			} else {
				moveTo(w * 0.35f, h * 0.25f)
				lineTo(w * 0.65f, h * 0.5f)
				lineTo(w * 0.35f, h * 0.75f)
			}
		}
		drawPath(path, color = tint, style = stroke)
	}
}

/** Vector Checkmark Icon */
@Composable
fun IconCheck(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = Color.White,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val path = Path().apply {
			moveTo(w * 0.2f, h * 0.5f)
			lineTo(w * 0.45f, h * 0.75f)
			lineTo(w * 0.8f, h * 0.25f)
		}
		drawPath(path, color = tint, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
	}
}

/** Vector Close / Cross Icon */
@Composable
fun IconClose(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		drawLine(color = tint, start = Offset(w * 0.2f, h * 0.2f), end = Offset(w * 0.8f, h * 0.8f), strokeWidth = 1.3f, cap = StrokeCap.Round)
		drawLine(color = tint, start = Offset(w * 0.8f, h * 0.2f), end = Offset(w * 0.2f, h * 0.8f), strokeWidth = 1.3f, cap = StrokeCap.Round)
	}
}

/** Vector Trash Can / Delete Icon */
@Composable
fun IconTrash(
	modifier: Modifier = Modifier.size(12.dp),
	tint: Color = LocalToolColors.current.textMuted,
) {
	Canvas(modifier = modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		// Can lid / handle
		drawLine(color = tint, start = Offset(w * 0.38f, h * 0.12f), end = Offset(w * 0.62f, h * 0.12f), strokeWidth = 1.2f, cap = StrokeCap.Round)
		drawLine(color = tint, start = Offset(w * 0.20f, h * 0.24f), end = Offset(w * 0.80f, h * 0.24f), strokeWidth = 1.2f, cap = StrokeCap.Round)
		// Can body
		val bodyPath = Path().apply {
			moveTo(w * 0.28f, h * 0.24f)
			lineTo(w * 0.32f, h * 0.86f)
			quadraticTo(w * 0.33f, h * 0.92f, w * 0.40f, h * 0.92f)
			lineTo(w * 0.60f, h * 0.92f)
			quadraticTo(w * 0.67f, h * 0.92f, w * 0.68f, h * 0.86f)
			lineTo(w * 0.72f, h * 0.24f)
		}
		drawPath(bodyPath, color = tint, style = stroke)
		// Vertical slats inside bin
		drawLine(color = tint, start = Offset(w * 0.43f, h * 0.38f), end = Offset(w * 0.43f, h * 0.78f), strokeWidth = 1.0f, cap = StrokeCap.Round)
		drawLine(color = tint, start = Offset(w * 0.57f, h * 0.38f), end = Offset(w * 0.57f, h * 0.78f), strokeWidth = 1.0f, cap = StrokeCap.Round)
	}
}

/** Practical Compact Tool Button */
@Composable
fun CompactButton(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	isPrimary: Boolean = false,
	leadingIcon: (@Composable () -> Unit)? = null,
	height: Dp = 26.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	val bgColor = when {
		!enabled -> colors.controlBackground.copy(alpha = 0.4f)
		isPrimary -> if (isHovered || isPressed) colors.accentHover else colors.accent
		isPressed -> colors.controlActive
		isHovered -> colors.controlHover
		else -> colors.controlBackground
	}

	val borderColor = when {
		!enabled -> colors.border.copy(alpha = 0.3f)
		isPrimary -> colors.accent
		isHovered -> colors.borderHover
		else -> colors.border
	}

	val textColor = when {
		!enabled -> colors.textDisabled
		isPrimary -> colors.accentText
		else -> colors.textPrimary
	}

	Box(
		modifier = modifier
			.height(height)
			.background(bgColor, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, borderColor), RoundedCornerShape(2.dp))
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.padding(horizontal = 8.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			if (leadingIcon != null) {
				leadingIcon()
				Spacer(Modifier.width(5.dp))
			}
			Text(
				text = text,
				style = typography.body.copy(fontSize = 11.5.sp, fontWeight = if (isPrimary) FontWeight.Medium else FontWeight.Normal),
				color = textColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** Practical Compact Icon Button */
@Composable
fun CompactIconButton(
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	tooltip: String? = null,
	size: Dp = 24.dp,
	content: @Composable () -> Unit,
) {
	val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	val bgColor = when {
		!enabled -> Color.Transparent
		isPressed -> colors.controlActive
		isHovered -> colors.controlHover
		else -> colors.controlBackground
	}

	Box(
		modifier = modifier
			.size(size)
			.background(bgColor, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, if (isHovered && enabled) colors.borderHover else colors.border), RoundedCornerShape(2.dp))
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default),
		contentAlignment = Alignment.Center,
	) {
		content()
	}
}

/** Practical Compact Text Field */
@Composable
fun CompactTextField(
	value: String,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	placeholder: String = "",
	enabled: Boolean = true,
	isMono: Boolean = false,
	onCommit: (() -> Unit)? = null,
	leadingIcon: (@Composable () -> Unit)? = null,
	trailingIcon: (@Composable () -> Unit)? = null,
	height: Dp = 24.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()

	val textStyle = if (isMono) typography.mono else typography.body

	BasicTextField(
		value = value,
		onValueChange = onValueChange,
		modifier = modifier
			.height(height)
			.background(colors.inputBackground, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, if (isHovered && enabled) colors.borderHover else colors.border), RoundedCornerShape(2.dp))
			.padding(horizontal = 6.dp),
		enabled = enabled,
		textStyle = textStyle.copy(color = if (enabled) colors.textPrimary else colors.textDisabled, fontSize = 11.5.sp),
		cursorBrush = SolidColor(colors.accent),
		singleLine = true,
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
		keyboardActions = KeyboardActions(onDone = { onCommit?.invoke() }),
		interactionSource = interactionSource,
		decorationBox = { innerTextField ->
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.fillMaxSize(),
			) {
				if (leadingIcon != null) {
					leadingIcon()
					Spacer(Modifier.width(4.dp))
				}
				Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
					if (value.isEmpty() && placeholder.isNotEmpty()) {
						Text(
							text = placeholder,
							style = textStyle.copy(fontSize = 11.5.sp),
							color = colors.textMuted,
							maxLines = 1,
						)
					}
					innerTextField()
				}
				if (trailingIcon != null) {
					Spacer(Modifier.width(4.dp))
					trailingIcon()
				}
			}
		},
	)
}

/** Practical Compact Number Stepper */
@Composable
fun CompactNumberSpinner(
	value: Double,
	onValueChange: (Double) -> Unit,
	modifier: Modifier = Modifier,
	min: Double = 0.0,
	max: Double = 100000.0,
	step: Double = 1.0,
	decimals: Int = 0,
	unit: String = "",
	enabled: Boolean = true,
	height: Dp = 24.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val formatted = if (decimals == 0) value.toLong().toString() else "%.${decimals}f".format(value)

	var textState by remember(value) { mutableStateOf(formatted) }

	Row(
		modifier = modifier
			.height(height)
			.background(colors.inputBackground, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(2.dp)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		BasicTextField(
			value = textState,
			onValueChange = { input ->
				textState = input
				input.toDoubleOrNull()?.let { num ->
					onValueChange(num.coerceIn(min, max))
				}
			},
			modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
			textStyle = typography.mono.copy(
				color = if (enabled) colors.textPrimary else colors.textDisabled,
				fontSize = 11.sp,
				textAlign = TextAlign.Right,
			),
			cursorBrush = SolidColor(colors.accent),
			singleLine = true,
			enabled = enabled,
		)
		if (unit.isNotEmpty()) {
			Text(
				text = unit,
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
				modifier = Modifier.padding(end = 4.dp),
			)
		}
		// Compact step buttons
		Column(
			modifier = Modifier
				.fillMaxHeight()
				.width(14.dp)
				.border(BorderStroke(1.dp, colors.border)),
		) {
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.background(colors.controlBackground)
					.clickable(enabled = enabled) {
						val next = (value + step).coerceIn(min, max)
						onValueChange(next)
					},
				contentAlignment = Alignment.Center,
			) {
				IconChevron(expanded = false, modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.background(colors.controlBackground)
					.clickable(enabled = enabled) {
						val next = (value - step).coerceIn(min, max)
						onValueChange(next)
					},
				contentAlignment = Alignment.Center,
			) {
				IconChevron(expanded = true, modifier = Modifier.size(8.dp), tint = colors.textMuted)
			}
		}
	}
}

/** Practical Compact DCC Slider */
@Composable
fun CompactSlider(
	value: Float,
	onValueChange: (Float) -> Unit,
    onValueChangeStarted: () -> Unit = {},
    onValueChangeFinished: () -> Unit = {},
	modifier: Modifier = Modifier,
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	steps: Int = 0,
	enabled: Boolean = true,
	height: Dp = 16.dp,
) {
	val changeValue by rememberUpdatedState(onValueChange)
    val startChange by rememberUpdatedState(onValueChangeStarted)
    val finishChange by rememberUpdatedState(onValueChangeFinished)
    val colors = LocalToolColors.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	val rangeSpan = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
	val fraction = ((value - valueRange.start) / rangeSpan).coerceIn(0f, 1f)

	BoxWithConstraints(
		modifier = modifier
			.height(height)
			.pointerHoverIcon(if (enabled) PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)) else PointerIcon.Default)
            .pointerInput(valueRange, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    startChange()
                    try {
                        changeValue(valueRange.start + (down.position.x / size.width.toFloat()).coerceIn(0f, 1f) * rangeSpan)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) changeValue(valueRange.start + (change.position.x / size.width.toFloat()).coerceIn(0f, 1f) * rangeSpan)
                            change.consume()
                        } while (event.changes.any { it.pressed })
                    } finally { finishChange() }
                }
            },
		contentAlignment = Alignment.CenterStart,
	) {
		val totalW = maxWidth
		val trackH = 4.dp
		val thumbW = 5.dp
		val thumbH = 10.dp

		// Background Track
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(trackH)
				.background(colors.inputBackground, RoundedCornerShape(2.dp))
				.border(BorderStroke(0.5.dp, colors.border), RoundedCornerShape(2.dp)),
		)

		// Active Track
		Box(
			modifier = Modifier
				.width(totalW * fraction)
				.height(trackH)
				.background(
					if (enabled) (if (isHovered || isPressed) colors.accentHover else colors.accent)
					else colors.textDisabled,
					RoundedCornerShape(2.dp),
				),
		)

		// Compact Sleek Thumb Indicator (Small 5x10dp bar, clean & non-obtrusive)
		val thumbOffset = ((totalW - thumbW) * fraction).coerceAtLeast(0.dp)
		Box(
			modifier = Modifier
				.padding(start = thumbOffset)
				.size(width = thumbW, height = thumbH)
				.background(
					if (enabled) (if (isPressed) Color.White else Color(0xFFE0E6ED)) else colors.textDisabled,
					RoundedCornerShape(1.dp),
				)
				.border(
					BorderStroke(0.5.dp, if (enabled) colors.accent else Color.Transparent),
					RoundedCornerShape(1.dp),
				),
		)
	}
}

/** Compact Checkbox */
@Composable
fun CompactCheckbox(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	label: String = "",
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()

	Row(
		modifier = modifier
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) {
				onCheckedChange(!checked)
			}
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.padding(vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.size(14.dp)
				.background(
					if (checked) colors.accent else colors.inputBackground,
					RoundedCornerShape(2.dp),
				)
				.border(
					BorderStroke(1.dp, if (checked) colors.accent else if (isHovered && enabled) colors.borderHover else colors.border),
					RoundedCornerShape(2.dp),
				),
			contentAlignment = Alignment.Center,
		) {
			if (checked) {
				IconCheck(modifier = Modifier.size(10.dp), tint = Color.White)
			}
		}
		if (label.isNotEmpty()) {
			Spacer(Modifier.width(6.dp))
			Text(
				text = label,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = if (enabled) (if (isHovered) colors.textPrimary else colors.textPrimary) else colors.textDisabled,
			)
		}
	}
}

/** Compact Toggle Chip / Button matching desktop tool aesthetic */
@Composable
fun CompactToggleChip(
	text: String,
	selected: Boolean,
	onToggle: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	leadingIcon: (@Composable () -> Unit)? = null,
	showCheckWhenSelected: Boolean = true,
	height: Dp = 22.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val isPressed by interactionSource.collectIsPressedAsState()

	val bgColor = when {
		!enabled -> colors.controlBackground.copy(alpha = 0.35f)
		selected -> if (isHovered || isPressed) colors.accent.copy(alpha = 0.28f) else colors.accent.copy(alpha = 0.16f)
		isPressed -> colors.controlActive
		isHovered -> colors.controlHover
		else -> colors.controlBackground.copy(alpha = 0.65f)
	}

	val borderColor = when {
		!enabled -> colors.border.copy(alpha = 0.25f)
		selected -> colors.accent
		isHovered -> colors.borderHover
		else -> colors.border
	}

	val contentColor = when {
		!enabled -> colors.textDisabled
		selected -> colors.accent
		isHovered -> colors.textPrimary
		else -> colors.textMuted
	}

	Box(
		modifier = modifier
			.height(height)
			.background(bgColor, RoundedCornerShape(2.dp))
			.border(BorderStroke(1.dp, borderColor), RoundedCornerShape(2.dp))
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onToggle() }
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.padding(horizontal = 4.dp),
		contentAlignment = Alignment.Center,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			if (leadingIcon != null) {
				leadingIcon()
				Spacer(Modifier.width(3.dp))
			} else if (selected && showCheckWhenSelected) {
				IconCheck(modifier = Modifier.size(9.dp), tint = contentColor)
				Spacer(Modifier.width(3.dp))
			}
			Text(
				text = text,
				style = typography.body.copy(
					fontSize = 10.5.sp,
					fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
				),
				color = contentColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** Compact Radio Button matching CompactCheckbox design */
@Composable
fun CompactRadioButton(
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	label: String = "",
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }

	Row(
		modifier = modifier
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null, onClick = onClick)
			.padding(vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			modifier = Modifier
				.size(14.dp)
				.clip(CircleShape)
				.background(colors.inputBackground, CircleShape)
				.border(
					BorderStroke(1.dp, if (selected) colors.accent else colors.border),
					CircleShape,
				),
			contentAlignment = Alignment.Center,
		) {
			if (selected) {
				Box(
					modifier = Modifier
						.size(6.dp)
						.clip(CircleShape)
						.background(colors.accent, CircleShape),
				)
			}
		}
		if (label.isNotEmpty()) {
			Spacer(Modifier.width(6.dp))
			Text(
				text = label,
				style = typography.body.copy(fontSize = 11.5.sp),
				color = if (enabled) colors.textPrimary else colors.textDisabled,
			)
		}
	}
}

/** Compact Dropdown Selector */
@Composable
fun <T> CompactDropdown(
	items: List<T>,
	selectedItem: T,
	onItemSelected: (T) -> Unit,
	modifier: Modifier = Modifier,
	itemLabel: (T) -> String = { it.toString() },
	enabled: Boolean = true,
	height: Dp = 24.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var expanded by remember { mutableStateOf(false) }

	Box(modifier = modifier) {
		Row(
			modifier = Modifier
				.height(height)
				.fillMaxWidth()
				.background(colors.inputBackground, RoundedCornerShape(2.dp))
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(2.dp))
				.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
				.clickable(enabled = enabled) { expanded = true }
				.padding(horizontal = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(
				text = itemLabel(selectedItem),
				style = typography.body.copy(fontSize = 11.5.sp),
				color = if (enabled) colors.textPrimary else colors.textDisabled,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			IconChevron(expanded = expanded, modifier = Modifier.size(10.dp), tint = colors.textMuted)
		}

		DropdownMenu(
			expanded = expanded,
			onDismissRequest = { expanded = false },
			modifier = Modifier
				.background(colors.panelElevated)
				.border(BorderStroke(1.dp, colors.border)),
		) {
			items.forEach { item ->
				val isSelected = item == selectedItem
				DropdownMenuItem(
					onClick = {
						onItemSelected(item)
						expanded = false
					},
					modifier = Modifier
						.height(26.dp)
						.background(if (isSelected) colors.selection else Color.Transparent)
						.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
				) {
					Text(
						text = itemLabel(item),
						style = typography.body.copy(
							fontSize = 11.5.sp,
							color = if (isSelected) colors.selectionText else colors.textPrimary,
						),
					)
				}
			}
		}
	}
}

/** Compact Tab Bar */
@Composable
fun CompactTabBar(
	tabs: List<String>,
	selectedIndex: Int,
	onTabSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	height: Dp = 26.dp,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Row(
		modifier = modifier
			.height(height)
			.fillMaxWidth()
			.background(colors.windowBackground)
			.border(BorderStroke(1.dp, colors.divider)),
		verticalAlignment = Alignment.CenterVertically,
	) {
		tabs.forEachIndexed { index, title ->
			val isSelected = index == selectedIndex
			val interactionSource = remember(index) { MutableInteractionSource() }
			val isHovered by interactionSource.collectIsHoveredAsState()

			val bg = when {
				isSelected -> colors.panelBackground
				isHovered -> colors.controlHover
				else -> Color.Transparent
			}

			Box(
				modifier = Modifier
					.fillMaxHeight()
					.background(bg)
					.drawBehind {
						if (isSelected) {
							drawRect(
								color = colors.accent,
								topLeft = Offset.Zero,
								size = Size(size.width, 2.dp.toPx()),
							)
						}
					}
					.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
					.clickable(interactionSource = interactionSource, indication = null) {
						onTabSelected(index)
					}
					.border(
						BorderStroke(
							1.dp,
							if (isSelected) colors.divider else Color.Transparent,
						),
					)
					.padding(horizontal = 14.dp),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = title,
					style = typography.body.copy(
						fontSize = 11.5.sp,
						fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
					),
					color = if (isSelected) colors.textPrimary else colors.textMuted,
				)
			}
		}
	}
}

/** Compact Section Header */
@Composable
fun CompactSectionHeader(
	title: String,
	modifier: Modifier = Modifier,
	trailing: (@Composable () -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current

	Row(
		modifier = modifier
			.fillMaxWidth()
			.height(24.dp)
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.divider))
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text = title,
			style = typography.header.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)
		trailing?.invoke()
	}
}

