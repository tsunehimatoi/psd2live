package io.github.psd2live.ui.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlin.math.roundToInt

private val MaskDim = Color(0xB30A0C10)

/** One scene owns the scrim, frame, hit regions and coach, in that paint order.
 * Menu steps invoke this INSIDE the menu popup; other steps use the workspace scene.
 * Only the four dim regions consume input, leaving the real target interactive.
 */
@Composable
fun TutorialOverlay(
	tutorialId: TutorialId,
	step: TutorialStep,
	stepIndex: Int,
	registry: TutorialTargetRegistry,
	reviewing: Boolean = false,
	isFirstStep: Boolean = false,
	onNext: () -> Unit,
	onPrevious: () -> Unit,
	onSkip: () -> Unit,
	onExit: () -> Unit,
	onFinish: () -> Unit,
	onContinueNext: (() -> Unit)? = null,
	onOpenCatalog: (() -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val density = LocalDensity.current
	var origin by remember { mutableStateOf(Offset.Zero) }
	val target = step.targetId?.let(registry::boundsOf)
	val hole = target?.translate(-origin)?.inflate(with(density) { 4.dp.toPx() })
	Layout(
		modifier = Modifier.fillMaxSize()
			.onGloballyPositioned { origin = it.positionInWindow() }
			.drawBehind {
				val window = Rect(Offset.Zero, size)
				spotlightMaskRects(window, listOfNotNull(hole)).forEach {
					drawRect(MaskDim, it.topLeft, it.size)
				}
				hole?.intersect(window)?.takeIf { it.width > 0f && it.height > 0f }?.let {
					val stroke = 2.dp.toPx()
					drawRect(
						colors.accent,
						it.topLeft + Offset(stroke / 2, stroke / 2),
						Size((it.width - stroke).coerceAtLeast(0f), (it.height - stroke).coerceAtLeast(0f)),
						style = Stroke(stroke),
					)
				}
			},
		content = {
			repeat(4) {
				Box(
					Modifier.clickable(
						interactionSource = remember { MutableInteractionSource() },
						indication = null,
						onClick = {},
					),
				)
			}
			key(tutorialId, step.key) {
				TutorialCoachCard(
					tutorialId = tutorialId,
					step = step,
					stepIndex = stepIndex,
					isFirstStep = isFirstStep,
					onNext = onNext,
					onPrevious = onPrevious,
					onSkip = onSkip,
					onExit = onExit,
					onFinish = onFinish,
					onContinueNext = onContinueNext,
					onOpenCatalog = onOpenCatalog,
					targetMissing = target == null && !step.isDone,
					reviewing = reviewing,
				)
			}
		},
	) { measurables, constraints ->
		val width = constraints.maxWidth
		val height = constraints.maxHeight
		val margin = 16.dp.roundToPx()
		val cardWidth = 340.dp.roundToPx().coerceAtMost((width - margin * 2).coerceAtLeast(1))
		val card = measurables.last().measure(
			Constraints(
				minWidth = cardWidth,
				maxWidth = cardWidth,
				maxHeight = (height - margin * 2).coerceAtLeast(1),
			),
		)
		val masks = spotlightMaskRects(Rect(0f, 0f, width.toFloat(), height.toFloat()), listOfNotNull(hole))
		val blockers = measurables.take(4).mapIndexed { index, measurable ->
			val rect = masks.getOrNull(index) ?: Rect.Zero
			val x = rect.left.roundToInt()
			val y = rect.top.roundToInt()
			Triple(
				measurable.measure(
					Constraints.fixed(
						(rect.right.roundToInt() - x).coerceAtLeast(0),
						(rect.bottom.roundToInt() - y).coerceAtLeast(0),
					),
				),
				x,
				y,
			)
		}
		val position = coachPosition(
			hole,
			IntSize(width, height),
			IntSize(card.width, card.height),
			margin.toFloat(),
			step.preferSideBubble || step.coachBesideMenu,
		)
		layout(width, height) {
			blockers.forEach { (placeable, x, y) -> placeable.place(x, y) }
			card.place(position.x, position.y, zIndex = 1f)
		}
	}
}

@Composable
fun TutorialCoachCard(
	tutorialId: TutorialId,
	step: TutorialStep,
	stepIndex: Int,
	isFirstStep: Boolean,
	onNext: () -> Unit,
	onPrevious: () -> Unit,
	onSkip: () -> Unit,
	onExit: () -> Unit,
	onFinish: () -> Unit = {},
	onContinueNext: (() -> Unit)? = null,
	onOpenCatalog: (() -> Unit)? = null,
	targetMissing: Boolean = false,
	reviewing: Boolean = false,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val definition = tutorialDefinition(tutorialId)
	val actionable = definition.actionableSteps
	val displayIndex = actionable.indexOfFirst { it.key == step.key }.takeIf { it >= 0 }
		?: stepIndex.coerceAtMost(actionable.lastIndex)
	val stepTotal = actionable.size.coerceAtLeast(1)
	val progressLabel = if (step.isDone) {
		tr("tutorial.basic.progress.done")
	} else {
		tr("tutorial.basic.progress", displayIndex + 1, stepTotal)
	}
	val nextId = tutorialId.nextId

	Column(
		modifier = modifier
			.background(colors.panelElevated, RoundedCornerShape(12.dp))
			.border(1.dp, colors.accent.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
			.verticalScroll(rememberScrollState())
			.padding(16.dp)
			.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = tr(tutorialId.titleKey),
			style = typography.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
			color = colors.accent,
		)
		LinearProgressIndicator(
			progress = if (step.isDone) 1f else (displayIndex + 1f) / stepTotal,
			modifier = Modifier.fillMaxWidth().height(3.dp),
			color = colors.accent,
			backgroundColor = colors.accent.copy(alpha = 0.12f),
		)
		Text(text = progressLabel, style = typography.caption.copy(fontSize = 10.sp), color = colors.accent)
		Text(
			text = tr(step.titleKey(tutorialId)),
			style = typography.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
		)
		Text(
			text = tr(step.bodyKey(tutorialId)),
			style = typography.caption.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
			color = colors.textMuted,
		)
		if (targetMissing) {
			Text(tr("tutorial.basic.targetMissing"), style = typography.caption, color = colors.accent)
		}
		if (!step.isDone) {
			Column(
				modifier = Modifier.fillMaxWidth()
					.background(colors.accent.copy(alpha = 0.08f), RoundedCornerShape(6.dp)).padding(10.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Text(tr("tutorial.basic.action"), style = typography.caption, color = colors.accent)
				Text(
					tr(step.actionKey(tutorialId)),
					style = typography.caption.copy(lineHeight = 17.sp),
					color = colors.textPrimary,
				)
			}
			Text(
				tr(if (step.allowsNext || reviewing) "tutorial.basic.hint.manual" else "tutorial.basic.hint.auto"),
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
			)
		}
		Spacer(modifier = Modifier.height(2.dp))
		if (step.isDone) {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				if (onContinueNext != null && nextId != null) {
					CompactButton(
						text = tr("tutorial.common.continueNext", tr(nextId.titleKey)),
						onClick = onContinueNext,
						isPrimary = true,
						height = 26.dp,
						modifier = Modifier.fillMaxWidth(),
					)
				}
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					if (onOpenCatalog != null) {
						CompactButton(
							text = tr("tutorial.common.openCatalog"),
							onClick = onOpenCatalog,
							height = 24.dp,
							modifier = Modifier.weight(1f),
						)
					}
					CompactButton(
						text = tr("tutorial.basic.finish"),
						onClick = onFinish,
						isPrimary = onContinueNext == null || nextId == null,
						height = 24.dp,
						modifier = Modifier.weight(1f),
					)
				}
			}
		} else {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				CompactButton(text = tr("tutorial.basic.exit"), onClick = onExit, height = 24.dp)
				if (!isFirstStep) {
					CompactButton(text = tr("tutorial.basic.previous"), onClick = onPrevious, height = 24.dp)
				}
				Spacer(modifier = Modifier.weight(1f))
				when {
					step.allowsNext || reviewing ->
						CompactButton(text = tr("tutorial.basic.next"), onClick = onNext, isPrimary = true, height = 24.dp)
					else ->
						CompactButton(text = tr("tutorial.basic.skip"), onClick = onSkip, height = 24.dp)
				}
			}
		}
	}
}
