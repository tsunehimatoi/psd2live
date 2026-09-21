package io.github.psd2live.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.state.KeyBinding
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.theme.LocalToolColors

private val KeyCapShape = RoundedCornerShape(4.dp)

/**
 * One physical-looking keycap. Used for both literal gesture keys (`Shift`) and tokens from a
 * [KeyBinding] chord (`Ctrl`, `Z`, …).
 */
@Composable
fun KeyCap(
	label: String,
	modifier: Modifier = Modifier,
	compact: Boolean = true,
) {
	val colors = LocalToolColors.current
	val shape = KeyCapShape
	Box(
		modifier = modifier
			.shadow(1.dp, shape, clip = false)
			.background(colors.panelElevated, shape)
			.border(1.dp, colors.border.copy(alpha = 0.85f), shape)
			.heightIn(min = if (compact) 18.dp else 22.dp)
			.widthIn(min = if (compact) 18.dp else 22.dp)
			.padding(horizontal = if (compact) 5.dp else 7.dp, vertical = if (compact) 1.dp else 2.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = label,
			color = colors.textPrimary,
			fontSize = if (compact) 10.sp else 11.sp,
			fontWeight = FontWeight.SemiBold,
			fontFamily = FontFamily.Monospace,
			maxLines = 1,
			lineHeight = if (compact) 12.sp else 14.sp,
		)
	}
}

/** Renders [binding] as a row of keycaps joined by `+`. */
@Composable
fun KeyChordCaps(
	binding: KeyBinding,
	modifier: Modifier = Modifier,
	compact: Boolean = true,
) {
	KeyChordCaps(tokens = binding.format().split('+'), modifier = modifier, compact = compact)
}

/** Renders an already-split chord (`Ctrl`, `Shift`, `Z`) as keycaps. */
@Composable
fun KeyChordCaps(
	tokens: List<String>,
	modifier: Modifier = Modifier,
	compact: Boolean = true,
) {
	val colors = LocalToolColors.current
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(2.dp),
	) {
		tokens.forEachIndexed { index, token ->
			if (index > 0) {
				Text(
					text = "+",
					color = colors.textMuted,
					fontSize = if (compact) 9.sp else 10.sp,
					fontFamily = FontFamily.Monospace,
				)
			}
			KeyCap(label = token, compact = compact)
		}
	}
}

/**
 * All bindings currently assigned to [action], as keycap chords separated by `/`.
 * Unbound actions render an em dash so the surrounding sentence still reads.
 */
@Composable
fun ShortcutActionCaps(
	action: ShortcutAction,
	keymap: Keymap,
	modifier: Modifier = Modifier,
	compact: Boolean = true,
) {
	val colors = LocalToolColors.current
	val bindings = keymap.bindingsFor(action)
	if (bindings.isEmpty()) {
		Text(
			text = "—",
			color = colors.textMuted,
			fontSize = if (compact) 10.sp else 11.sp,
			modifier = modifier,
		)
		return
	}
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		bindings.forEachIndexed { index, binding ->
			if (index > 0) {
				Text(
					text = "/",
					color = colors.textMuted,
					fontSize = if (compact) 9.sp else 10.sp,
					fontFamily = FontFamily.Monospace,
				)
			}
			KeyChordCaps(binding = binding, compact = compact)
		}
	}
}
