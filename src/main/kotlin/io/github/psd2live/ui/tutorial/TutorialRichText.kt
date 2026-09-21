package io.github.psd2live.ui.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.components.KeyChordCaps
import io.github.psd2live.ui.components.KeyCap
import io.github.psd2live.ui.components.ShortcutActionCaps
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.ShortcutAction

/**
 * Markup tokens embedded in tutorial i18n strings:
 * - `{key:SHORTCUT_ACTION}` → current [Keymap] binding(s) as keycaps
 * - `{kbd:Token}` → a literal keycap (`Shift`, `Alt`, `Space`, `Esc`, …)
 *
 * Action names must match [ShortcutAction] enum constants.
 */
internal sealed interface TutorialSpan {
	data class Text(val value: String) : TutorialSpan
	data class Action(val action: ShortcutAction) : TutorialSpan
	data class LiteralKey(val token: String) : TutorialSpan
}

private val MARKUP = Regex("""\{(key|kbd):([A-Za-z0-9_+\[\]]+)\}""")

internal fun parseTutorialMarkup(text: String): List<TutorialSpan> {
	if (text.isEmpty()) return emptyList()
	val out = ArrayList<TutorialSpan>()
	var last = 0
	for (match in MARKUP.findAll(text)) {
		if (match.range.first > last) {
			out += TutorialSpan.Text(text.substring(last, match.range.first))
		}
		val kind = match.groupValues[1]
		val name = match.groupValues[2]
		when (kind) {
			"key" -> {
				val action = runCatching { ShortcutAction.valueOf(name) }.getOrNull()
				if (action != null) out += TutorialSpan.Action(action)
				else out += TutorialSpan.Text(match.value)
			}
			"kbd" -> out += TutorialSpan.LiteralKey(name)
			else -> out += TutorialSpan.Text(match.value)
		}
		last = match.range.last + 1
	}
	if (last < text.length) out += TutorialSpan.Text(text.substring(last))
	return out
}

/**
 * Tutorial body/action copy that mixes prose with keyboard-shaped keycaps resolved from [keymap].
 * Newlines start a new paragraph row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TutorialRichText(
	text: String,
	keymap: Keymap,
	style: TextStyle,
	color: Color,
	modifier: Modifier = Modifier,
) {
	val lines = remember(text) { text.split('\n') }
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		lines.forEach { line ->
			val spans = remember(line) { parseTutorialMarkup(line) }
			if (spans.isEmpty()) return@forEach
			FlowRow(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
				itemVerticalAlignment = Alignment.CenterVertically,
			) {
				spans.forEach { span ->
					when (span) {
						is TutorialSpan.Text -> {
							if (span.value.isNotEmpty()) {
								Text(text = span.value, style = style, color = color)
							}
						}
						is TutorialSpan.Action -> ShortcutActionCaps(action = span.action, keymap = keymap)
						is TutorialSpan.LiteralKey -> {
							val tokens = span.token.split('+')
							if (tokens.size == 1) KeyCap(label = tokens.first())
							else KeyChordCaps(tokens = tokens)
						}
					}
				}
			}
		}
	}
}
