package io.github.psd2live.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.MeshFillAlgorithm
import io.github.psd2live.core.MeshFillParameters
import io.github.psd2live.core.MeshFillRanges
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/** One numeric control of a fill algorithm's own parameter group. */
private class FillControl(
	val key: String,
	val value: Float,
	val range: ClosedFloatingPointRange<Float>,
	val step: Double,
	val decimals: Int,
	val unit: String,
	val update: (MeshFillParameters, Float) -> MeshFillParameters,
)

private fun fillControls(algorithm: MeshFillAlgorithm, p: MeshFillParameters): List<FillControl> {
	val r = MeshFillRanges
	val rows = r.maxRows.first.toFloat()..r.maxRows.last.toFloat()
	return when (algorithm) {
		MeshFillAlgorithm.SIMPLE_TRIANGLES -> emptyList()
		MeshFillAlgorithm.GRADED_POISSON -> listOf(
			FillControl("edgeRatio", p.poisson.edgeRatio, r.edgeRatio, 0.25, 2, "×") { s, v -> s.copy(poisson = s.poisson.copy(edgeRatio = v)) },
			FillControl("gradation", p.poisson.gradation, r.gradation, 0.25, 2, "") { s, v -> s.copy(poisson = s.poisson.copy(gradation = v)) },
			FillControl("jitter", p.poisson.jitter, r.jitter, 0.05, 2, "") { s, v -> s.copy(poisson = s.poisson.copy(jitter = v)) },
		)
		MeshFillAlgorithm.ADAPTIVE_QUADTREE -> listOf(
			FillControl("edgeRatio", p.quadtree.edgeRatio, r.edgeRatio, 0.25, 2, "×") { s, v -> s.copy(quadtree = s.quadtree.copy(edgeRatio = v)) },
			FillControl("gradation", p.quadtree.gradation, r.gradation, 0.25, 2, "") { s, v -> s.copy(quadtree = s.quadtree.copy(gradation = v)) },
			FillControl("angle", p.quadtree.angle, r.angle, 5.0, 0, "°") { s, v -> s.copy(quadtree = s.quadtree.copy(angle = v)) },
		)
		MeshFillAlgorithm.TRIANGLE_FRACTAL -> listOf(
			FillControl("edgeRatio", p.fractal.edgeRatio, r.edgeRatio, 0.25, 2, "×") { s, v -> s.copy(fractal = s.fractal.copy(edgeRatio = v)) },
			FillControl("gradation", p.fractal.gradation, r.gradation, 0.25, 2, "") { s, v -> s.copy(fractal = s.fractal.copy(gradation = v)) },
			FillControl("angle", p.fractal.angle, r.angle, 5.0, 0, "°") { s, v -> s.copy(fractal = s.fractal.copy(angle = v)) },
		)
		MeshFillAlgorithm.CONTOUR_PAVING -> listOf(
			FillControl("edgeRatio", p.paving.edgeRatio, r.edgeRatio, 0.25, 2, "×") { s, v -> s.copy(paving = s.paving.copy(edgeRatio = v)) },
			FillControl("gradation", p.paving.gradation, r.gradation, 0.25, 2, "") { s, v -> s.copy(paving = s.paving.copy(gradation = v)) },
			FillControl("maxRows", p.paving.maxRows.toFloat(), rows, 1.0, 0, "") { s, v ->
				s.copy(paving = s.paving.copy(maxRows = kotlin.math.round(v).toInt().coerceIn(r.maxRows)))
			},
		)
	}
}

/**
 * The selected algorithm's own parameters. [labelWidth] lays each control out as one inspector form row;
 * without it each control gets a caption line above its slider, as in the layer mesh dialog.
 */
@Composable
fun MeshFillParameterControls(
	algorithm: MeshFillAlgorithm,
	parameters: MeshFillParameters,
	onChange: (MeshFillParameters) -> Unit,
	enabled: Boolean = true,
	labelWidth: Dp? = null,
	showHints: Boolean = true,
	onGestureStart: () -> Unit = {},
	onGestureEnd: () -> Unit = {},
	onEditStart: (String) -> Unit = {},
	onEditEnd: (String) -> Unit = {},
) {
	val latest by rememberUpdatedState(parameters)
	val change by rememberUpdatedState(onChange)
	for (control in fillControls(algorithm, parameters)) key(algorithm, control.key) {
		// Reads the parameters at event time: a slider drag outlives the composition that started it.
		val update = { v: Float -> change(control.update(latest, v.coerceIn(control.range))) }
		FillControlRow(control, update, enabled, labelWidth, showHints, onGestureStart, onGestureEnd,
			{ onEditStart(control.key) }, { onEditEnd(control.key) })
	}
}

@Composable
private fun FillControlRow(
	control: FillControl,
	update: (Float) -> Unit,
	enabled: Boolean,
	labelWidth: Dp?,
	showHints: Boolean,
	onGestureStart: () -> Unit,
	onGestureEnd: () -> Unit,
	onEditStart: () -> Unit,
	onEditEnd: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val label = tr("mesh.settings.fillParam.${control.key}")
	if (labelWidth != null) {
		Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(label, style = typography.body.copy(fontSize = 10.5.sp), color = colors.textPrimary,
				modifier = Modifier.width(labelWidth), textAlign = TextAlign.Right)
			Spacer(Modifier.width(5.dp))
			CompactSlider(value = control.value, onValueChange = update, onValueChangeStarted = onGestureStart,
				onValueChangeFinished = onGestureEnd, valueRange = control.range, enabled = enabled, height = 14.dp,
				modifier = Modifier.weight(1f))
			Spacer(Modifier.width(4.dp))
			FillSpinner(control, update, enabled, 20.dp, onEditStart, onEditEnd)
		}
	} else {
		Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(label, style = typography.body.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
				color = colors.textPrimary)
			Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				CompactSlider(value = control.value, onValueChange = update, onValueChangeStarted = onGestureStart,
					onValueChangeFinished = onGestureEnd, valueRange = control.range, enabled = enabled, height = 16.dp,
					modifier = Modifier.weight(1f))
				FillSpinner(control, update, enabled, 22.dp, onEditStart, onEditEnd)
			}
		}
	}
	if (showHints) {
		Text(tr("mesh.settings.fillParamHint.${control.key}"), style = typography.caption.copy(fontSize = 9.sp),
			color = colors.textMuted)
	}
}

@Composable
private fun FillSpinner(
	control: FillControl,
	update: (Float) -> Unit,
	enabled: Boolean,
	height: Dp,
	onEditStart: () -> Unit,
	onEditEnd: () -> Unit,
) = CompactNumberSpinner(
	value = control.value.toDouble(),
	onValueChange = { update(it.toFloat()) },
	min = control.range.start.toDouble(),
	max = control.range.endInclusive.toDouble(),
	step = control.step,
	decimals = control.decimals,
	unit = control.unit,
	enabled = enabled,
	modifier = Modifier.width(62.dp),
	height = height,
	onEditStart = onEditStart,
	onEditEnd = onEditEnd,
)
