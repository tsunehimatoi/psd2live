package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.components.ModalDialogFrame
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.PsdGuideStep
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideItem

private val PsdGuideStep.label get() = "psdGuide.step.${name.lowercase()}"

private data class StepCountBadge(val count: Int, val isWarning: Boolean = false)

internal fun psdGuideControlsTitle(viewModel: PSD2LiveViewModel): String =
    tr(viewModel.psdGuide.step.label)

/**
 * Combined three-module view used by headless screenshot tests; in the app the three panels live as
 * independent dock modules ([PSD_GUIDE_CONTENT], [PSD_GUIDE_CONTROLS], and [PSD_GUIDE_ACTIONS]) inside [PageDock].
 */
@Composable
internal fun PsdGuidePage(viewModel: PSD2LiveViewModel) {
    val state = viewModel.psdGuide
    if (state.path == null) return
    val colors = LocalToolColors.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.windowBackground)
            .padding(Spacing.Gap),
    ) {
        val density = LocalDensity.current
        val dividerWidth = Spacing.Gap
        val minSidebar = 300.dp
        val maxSidebar = minOf(520.dp, (maxWidth - dividerWidth - 260.dp).coerceAtLeast(minSidebar))
        val available = (maxWidth - dividerWidth).coerceAtLeast(1.dp)
        var sidebarFraction by remember { mutableStateOf(0.34f) }
        val sidebarWidth = (available * sidebarFraction).coerceIn(minSidebar, maxSidebar)

        Row(Modifier.fillMaxSize()) {
            GuideModuleFrame(Modifier.weight(1f).fillMaxHeight()) {
                PsdGuideContentPanel(viewModel)
            }
            Box(
                Modifier
                    .panelDivider(horizontal = true) { deltaPx ->
                        val deltaDp = with(density) { deltaPx.toDp() }
                        val current = (available * sidebarFraction).coerceIn(minSidebar, maxSidebar)
                        val nextWidth = (current - deltaDp).coerceIn(minSidebar, maxSidebar)
                        sidebarFraction = (nextWidth / available).coerceIn(0f, 1f)
                    }
                    .width(dividerWidth)
                    .fillMaxHeight(),
            )
            Column(
                modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(Spacing.Gap),
            ) {
                GuideInspectorModule(
                    title = psdGuideControlsTitle(viewModel),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    scrollable = false,
                ) {
                    PsdGuideControlsPanel(viewModel, includeOuterPadding = false)
                }
                GuideInspectorModule(
                    title = tr("dock.module.psdGuide.actions"),
                    modifier = Modifier.fillMaxWidth().height(92.dp),
                    scrollable = false,
                ) {
                    PsdGuideActionsPanel(viewModel, includeOuterPadding = false)
                }
            }
        }
    }
}

/**
 * Left dock module ([PSD_GUIDE_CONTENT]): owns the step header bar and live preview viewport.
 */
@Composable
internal fun PsdGuideContentPanel(viewModel: PSD2LiveViewModel) {
    val state = viewModel.psdGuide
    val path = state.path ?: return
    val appState by viewModel.state.collectAsState()
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val step = state.step
    val source = state.source
    val ready = !state.busy && state.bindings != null
    val canAdvance = ready && when (step) {
        PsdGuideStep.SPLIT -> state.splitDraftValid
        PsdGuideStep.PRESET -> state.finalCandidate != null
        else -> true
    }
    val stepShift = with(LocalDensity.current) { 14.dp.roundToPx() }
    val next = state.nextStep
    val steps = state.steps
    val stepIdx = steps.indexOf(step).coerceAtLeast(0)
    val fileName = path.fileName.toString()
    val metaText = source?.model?.analysis?.let { analysis ->
        tr(
            "psdGuide.meta",
            analysis.source.widthPx.toString(),
            analysis.source.heightPx.toString(),
            analysis.layers.size,
        )
    }
    val stepCounts = buildMap {
        if (state.splitScanned && state.selectedSplitCount > 0) {
            put(PsdGuideStep.SPLIT, StepCountBadge(state.selectedSplitCount, isWarning = !state.splitDraftValid))
        }
        val findings = state.bindings?.findings.orEmpty()
        if (findings.isNotEmpty()) {
            put(PsdGuideStep.BINDING, StepCountBadge(findings.size, isWarning = true))
        }
    }
    val navigateToStep: (PsdGuideStep) -> Unit = { target ->
        if (step == PsdGuideStep.SPLIT && target != PsdGuideStep.SPLIT) {
            state.advanceFromSplit(target)
        } else {
            state.go(target)
        }
    }

    Box(Modifier.fillMaxSize().background(colors.panelBackground)) {
        Column(Modifier.fillMaxSize()) {
            GuideTopHeaderBar(
                fileName = fileName,
                metaText = metaText,
                steps = steps,
                currentStep = step,
                reachedStep = state.reached,
                nextStep = next,
                canAdvance = canAdvance,
                stepIdx = stepIdx,
                ready = ready,
                stepCounts = stepCounts,
                onSelectStep = navigateToStep,
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    var lastError by remember { mutableStateOf<String?>(null) }
                    if (state.error != null) lastError = state.error
                    AnimatedVisibility(
                        visible = state.error != null,
                        enter = expandVertically(tween(Motion.Fold, easing = FastOutSlowInEasing)) +
                            fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)),
                        exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing)) +
                            fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)),
                    ) {
                        lastError?.let { err ->
                            Column(Modifier.fillMaxWidth()) {
                                GuideInfoBanner(
                                    text = err,
                                    tone = GuideBannerTone.ERROR,
                                    actionLabel = if (!state.busy && state.canRetry) tr("psdGuide.retry") else null,
                                    onAction = state::retry,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
                            }
                        }
                    }

                    if (source == null) {
                        Box(
                            Modifier.weight(1f).fillMaxWidth().background(colors.panelBackground).padding(Spacing.Margin),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.busy || state.error == null) {
                                GuideLoadingState(state.taskProgress?.text ?: tr("psdGuide.reading"))
                            }
                        }
                    } else {
                        key(path) {
                            AnimatedContent(
                                targetState = step,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                transitionSpec = {
                                    val direction = if (targetState > initialState) 1 else -1
                                    (fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)) +
                                        slideInHorizontally(tween(Motion.Quick, easing = FastOutSlowInEasing)) {
                                            direction * stepShift
                                        })
                                        .togetherWith(fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)))
                                        .using(SizeTransform(clip = true) { _, _ -> tween(Motion.Quick) })
                                },
                                label = "PSD guide preview",
                            ) { shownStep ->
                                when (shownStep) {
                                    PsdGuideStep.SPLIT -> SplitGuidePreview(state, source, appState.keymap)
                                    PsdGuideStep.BINDING -> BindingGuidePreview(state, source)
                                    PsdGuideStep.PRESET -> PresetGuidePreview(state, source)
                                    PsdGuideStep.FACE -> FaceGuidePreview(state)
                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = source != null && state.busy,
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    enter = fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)),
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = colors.accent,
                        backgroundColor = colors.divider,
                    )
                }
            }
        }

        if (state.confirmDiscard) {
            val cancel = { state.confirmDiscard = false }
            ModalDialogFrame(
                title = tr("psdGuide.discard.title"),
                onDismiss = cancel,
                footer = {
                    CompactButton(tr("project.cancel"), cancel)
                    CompactButton(tr("project.discard"), viewModel::discardPsdGuide, isPrimary = true)
                },
            ) {
                Text(tr("psdGuide.discard.message"), color = colors.textPrimary, style = typography.body)
            }
        }
    }
}

/**
 * Bottom-right dock module ([PSD_GUIDE_ACTIONS]): independent workflow action bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PsdGuideActionsPanel(
    viewModel: PSD2LiveViewModel,
    includeOuterPadding: Boolean = true,
) {
    val state = viewModel.psdGuide
    if (state.path == null) return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val step = state.step
    val source = state.source
    val ready = !state.busy && state.bindings != null
    val canAdvance = ready && when (step) {
        PsdGuideStep.SPLIT -> state.splitDraftValid
        PsdGuideStep.PRESET -> state.finalCandidate != null
        else -> true
    }
    val previous = state.previousStep
    val next = state.nextStep
    val statusText = if (source != null && state.busy) state.taskProgress?.text else null
    val navigateToStep: (PsdGuideStep) -> Unit = { target ->
        if (step == PsdGuideStep.SPLIT && target != PsdGuideStep.SPLIT) {
            state.advanceFromSplit(target)
        } else {
            state.go(target)
        }
    }

    val paddingMod = if (includeOuterPadding) Modifier.padding(horizontal = 10.dp, vertical = 8.dp) else Modifier
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.panelBackground)
            .then(paddingMod),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!statusText.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GuideSpinner(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = colors.accent)
                    Text(
                        text = statusText,
                        style = typography.caption,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            GuideFooterBar(
                step = step,
                saving = state.saving,
                ready = ready,
                canAdvance = canAdvance,
                hasFinalCandidate = state.finalCandidate != null,
                previous = previous,
                next = next,
                onDiscard = state::requestClose,
                onDisableGuide = viewModel::disablePsdGuideAndImport,
                onSkipOnce = viewModel::skipPsdGuideOnce,
                onGo = navigateToStep,
                onCreate = viewModel::createPsdGuideProject,
            )
        }
    }
}

/**
 * Top-right dock module ([PSD_GUIDE_CONTROLS]): independent inspector panel for the active guide step.
 */
@Composable
internal fun PsdGuideControlsPanel(
    viewModel: PSD2LiveViewModel,
    includeOuterPadding: Boolean = true,
) {
    val state = viewModel.psdGuide
    val source = state.source
    val step = state.step
    val colors = LocalToolColors.current
    val paddingMod = if (includeOuterPadding) Modifier.padding(horizontal = 10.dp, vertical = 8.dp) else Modifier

    if (source == null) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.panelBackground).then(paddingMod),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GuideNote(state.taskProgress?.text ?: tr("psdGuide.reading"))
        }
        return
    }

    when (step) {
        PsdGuideStep.SPLIT -> Column(
            modifier = Modifier.fillMaxSize().background(colors.panelBackground).then(paddingMod),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SplitGuideControls(state)
        }
        PsdGuideStep.BINDING -> Column(
            modifier = Modifier.fillMaxSize().background(colors.panelBackground).then(paddingMod),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BindingGuideControls(state)
        }
        PsdGuideStep.PRESET, PsdGuideStep.FACE -> {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize().background(colors.panelBackground)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .then(paddingMod),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (step == PsdGuideStep.PRESET) {
                        PresetGuideControls(state, source)
                    } else {
                        FaceGuideControls(state)
                    }
                }
                if (scroll.canScrollForward || scroll.canScrollBackward) {
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideTopHeaderBar(
    fileName: String,
    metaText: String?,
    steps: List<PsdGuideStep>,
    currentStep: PsdGuideStep,
    reachedStep: PsdGuideStep,
    nextStep: PsdGuideStep?,
    canAdvance: Boolean,
    stepIdx: Int,
    ready: Boolean,
    stepCounts: Map<PsdGuideStep, StepCountBadge>,
    onSelectStep: (PsdGuideStep) -> Unit,
) {
    val colors = LocalToolColors.current
    Column(Modifier.fillMaxWidth().background(colors.panelElevated)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val singleRow = maxWidth >= 560.dp
            if (singleRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GuideHeaderFileMeta(fileName, metaText, Modifier.weight(1f))
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        GuidePipelineStepper(
                            steps = steps,
                            current = currentStep,
                            reached = reachedStep,
                            nextStep = nextStep,
                            canAdvance = canAdvance,
                            ready = ready,
                            counts = stepCounts,
                            onSelect = onSelectStep,
                        )
                    }
                    Box(Modifier.width(1.dp).height(14.dp).background(colors.divider))
                    GuideStepProgress(stepIdx, steps.size)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GuideHeaderFileMeta(fileName, metaText, Modifier.weight(1f))
                        GuideStepProgress(stepIdx, steps.size)
                    }
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        GuidePipelineStepper(
                            steps = steps,
                            current = currentStep,
                            reached = reachedStep,
                            nextStep = nextStep,
                            canAdvance = canAdvance,
                            ready = ready,
                            counts = stepCounts,
                            onSelect = onSelectStep,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
    }
}

@Composable
private fun GuideHeaderFileMeta(
    fileName: String,
    metaText: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = fileName,
            style = typography.title,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (metaText != null) {
            Text(
                text = metaText,
                style = typography.caption,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GuideStepProgress(stepIdx: Int, total: Int) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Text(
        text = tr("psdGuide.step.progress", stepIdx + 1, total),
        style = typography.caption,
        color = colors.textMuted,
        maxLines = 1,
    )
}

@Composable
private fun GuidePipelineStepper(
    steps: List<PsdGuideStep>,
    current: PsdGuideStep,
    reached: PsdGuideStep,
    nextStep: PsdGuideStep?,
    canAdvance: Boolean,
    ready: Boolean,
    counts: Map<PsdGuideStep, StepCountBadge>,
    onSelect: (PsdGuideStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val itemShape = RoundedCornerShape(Corners.Control)

    MenuGlide(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            steps.forEachIndexed { index, target ->
                val isCurrent = target == current
                val isCompleted = target < current && target <= reached
                val isNextAdvance = target == nextStep && canAdvance
                val isReachable = target <= reached || isNextAdvance
                val clickable = ready && isReachable && !isCurrent
                val itemBg by animateColorAsState(
                    targetValue = if (isCurrent) colors.accent.copy(alpha = 0.14f) else Color.Transparent,
                    animationSpec = tween(Motion.Instant),
                )
                val badgeBg by animateColorAsState(
                    targetValue = when {
                        isCurrent -> colors.accent
                        isCompleted -> colors.highlightContainer
                        isReachable -> colors.controlBackground
                        else -> colors.controlBackground.copy(alpha = 0.6f)
                    },
                    animationSpec = tween(Motion.Instant),
                )
                val badgeFg by animateColorAsState(
                    targetValue = when {
                        isCurrent -> colors.accentText
                        isCompleted -> colors.highlight
                        isReachable -> colors.textPrimary
                        else -> colors.textDisabled
                    },
                    animationSpec = tween(Motion.Instant),
                )

                Row(
                    modifier = Modifier
                        .clip(itemShape)
                        .background(itemBg, itemShape)
                        .glideItem(clickable && !isCurrent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = clickable,
                            onClick = { onSelect(target) },
                        )
                        .handCursor(clickable)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                            .clip(itemShape)
                            .background(badgeBg)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isCompleted) {
                            IconCheck(modifier = Modifier.size(10.dp), tint = badgeFg)
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = typography.monoSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                                color = badgeFg,
                                maxLines = 1,
                            )
                        }
                    }

                    Text(
                        text = tr(target.label),
                        style = typography.caption.copy(
                            fontWeight = when {
                                isCurrent -> FontWeight.SemiBold
                                isReachable -> FontWeight.Medium
                                else -> FontWeight.Normal
                            },
                        ),
                        color = if (isCurrent || isReachable) colors.textPrimary else colors.textMuted,
                        maxLines = 1,
                    )

                    counts[target]?.let { countBadge ->
                        val countColor = when {
                            countBadge.isWarning -> colors.warning
                            isCurrent -> colors.selectionText
                            else -> colors.textMuted
                        }
                        val countBg = when {
                            countBadge.isWarning -> colors.warning.copy(alpha = 0.16f)
                            isCurrent -> colors.selection
                            else -> colors.controlBackground
                        }
                        Box(
                            modifier = Modifier
                                .clip(itemShape)
                                .background(countBg)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = countBadge.count.toString(),
                                style = typography.monoSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
                                color = countColor,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (index < steps.lastIndex) {
                    Text(
                        text = "→",
                        style = typography.monoSmall.copy(fontSize = 10.sp),
                        color = if (target < current) colors.textMuted else colors.textDisabled,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideLoadingState(label: String) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Column(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GuideSpinner(
            modifier = Modifier.size(20.dp),
            color = colors.accent,
            trackColor = colors.border.copy(alpha = 0.45f),
            strokeWidth = 2.dp,
        )
        Text(
            text = label,
            style = typography.body,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideFooterBar(
    step: PsdGuideStep,
    saving: Boolean,
    ready: Boolean,
    canAdvance: Boolean,
    hasFinalCandidate: Boolean,
    previous: PsdGuideStep?,
    next: PsdGuideStep?,
    onDiscard: () -> Unit,
    onDisableGuide: () -> Unit,
    onSkipOnce: () -> Unit,
    onGo: (PsdGuideStep) -> Unit,
    onCreate: () -> Unit,
) {
    val typography = LocalToolTypography.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val normalStyle = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal)
    val primaryStyle = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium)

    val discardLabel = tr("psdGuide.discard.action")
    val disableLabel = tr("psdGuide.disableGuide")
    val skipLabel = tr("psdGuide.skipOnce")
    val continueLabel = tr("psdGuide.continue")
    val backLabel = tr("psdGuide.back")
    val nextLabel = tr("psdGuide.next")
    val createLabel = tr("psdGuide.create")

    fun measureButtonDp(label: String, primary: Boolean = false): Dp {
        val style = if (primary) primaryStyle else normalStyle
        val textPx = measurer.measure(label, style, maxLines = 1, softWrap = false).size.width
        return with(density) { textPx.toDp() } + 16.dp
    }

    val isStep1 = step == PsdGuideStep.SPLIT
    val actionWidthsDp = buildList {
        add(measureButtonDp(discardLabel))
        if (isStep1) {
            add(measureButtonDp(disableLabel))
            add(measureButtonDp(skipLabel))
            add(measureButtonDp(continueLabel, primary = true))
        } else {
            if (previous != null) add(measureButtonDp(backLabel))
            add(measureButtonDp(if (next != null) nextLabel else createLabel, primary = true))
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val actionGap = Spacing.Gap
        val neededSingleRow = actionWidthsDp.fold(0.dp) { acc, w -> acc + w } +
            actionGap * (actionWidthsDp.size - 1).coerceAtLeast(0)
        val fitsSingleRow = neededSingleRow + 8.dp <= maxWidth

        val rightActions: @Composable () -> Unit = {
            if (isStep1) {
                CompactButton(disableLabel, onDisableGuide, enabled = ready && !saving)
                CompactButton(skipLabel, onSkipOnce, enabled = ready && !saving)
                CompactButton(continueLabel, { next?.let(onGo) }, enabled = canAdvance && next != null, isPrimary = true)
            } else {
                if (previous != null) {
                    CompactButton(backLabel, { onGo(previous) }, enabled = ready && !saving)
                }
                if (next != null) {
                    CompactButton(nextLabel, { onGo(next) }, enabled = canAdvance && !saving, isPrimary = true)
                } else {
                    CompactButton(
                        text = createLabel,
                        onClick = onCreate,
                        enabled = ready && !saving && hasFinalCandidate,
                        isPrimary = true,
                    )
                }
            }
        }

        if (fitsSingleRow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(actionGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactButton(discardLabel, onDiscard, enabled = !saving)
                Spacer(Modifier.weight(1f))
                rightActions()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.Gap),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactButton(discardLabel, onDiscard, enabled = !saving)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Gap),
                ) {
                    rightActions()
                }
            }
        }
    }
}
