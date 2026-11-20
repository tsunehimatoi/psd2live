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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
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
import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.HelpTab
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.PsdGuideStep
import io.github.psd2live.ui.state.SettingsTopic
import io.github.psd2live.ui.state.StartSection
import io.github.psd2live.ui.tutorial.TutorialId
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
 * Combined two-module view used by headless screenshot tests; in the app the two panels live as
 * independent dock modules ([PSD_GUIDE_CONTENT] and [PSD_GUIDE_CONTROLS]) inside [PageDock].
 */
@Composable
internal fun PsdGuidePage(viewModel: PSD2LiveViewModel) {
    val state = viewModel.psdGuide
    if (state.path == null) return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
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
            GuideModuleFrame(Modifier.width(sidebarWidth).fillMaxHeight()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .background(colors.panelElevated)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = psdGuideControlsTitle(viewModel),
                        style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    PsdGuideControlsPanel(viewModel)
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
 * Right dock module ([PSD_GUIDE_CONTROLS]): step controls on top with an integrated, content-sized
 * workflow action footer pinned at the bottom.
 */
@Composable
internal fun PsdGuideControlsPanel(
    viewModel: PSD2LiveViewModel,
    actions: PageActions? = null,
) {
    val state = viewModel.psdGuide
    val source = state.source
    val step = state.step
    val colors = LocalToolColors.current

    Column(Modifier.fillMaxSize().background(colors.panelBackground)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (source == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GuideNote(state.taskProgress?.text ?: tr("psdGuide.reading"))
                }
            } else {
                when (step) {
                    PsdGuideStep.SPLIT -> Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SplitGuideControls(state)
                    }
                    PsdGuideStep.BINDING -> Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BindingGuideControls(state)
                    }
                    PsdGuideStep.PRESET, PsdGuideStep.FACE -> {
                        val scroll = rememberScrollState()
                        Box(Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scroll)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
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
        }

        if (state.path != null) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
            PsdGuideActionsSection(viewModel, actions)
        }
    }
}

/** Integrated bottom action section inside [PsdGuideControlsPanel], sized naturally to the active step. */
@Composable
private fun PsdGuideActionsSection(
    viewModel: PSD2LiveViewModel,
    actions: PageActions? = null,
) {
    val state = viewModel.psdGuide
    if (state.path == null) return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val step = state.step
    val source = state.source
    val steps = state.steps
    val stepIdx = steps.indexOf(step).coerceAtLeast(0)
    val ready = !state.busy && state.bindings != null
    val canAdvance = ready && when (step) {
        PsdGuideStep.SPLIT -> state.splitDraftValid
        PsdGuideStep.PRESET -> state.finalCandidate != null
        else -> true
    }
    val previous = state.previousStep
    val next = state.nextStep
    val isLastStep = next == null
    val canFinish = ready && !state.saving && state.finalCandidate != null
    val busyStatus = if (source != null && state.busy) state.taskProgress?.text else null

    val statusDotColor = when {
        busyStatus != null -> colors.accent
        step == PsdGuideStep.SPLIT && !state.splitDraftValid -> colors.error
        step == PsdGuideStep.BINDING && state.bindings?.findings.orEmpty().isNotEmpty() -> colors.warning
        step == PsdGuideStep.FACE && state.appliedStyle != null -> colors.highlight
        else -> colors.highlight
    }
    val summaryText = busyStatus ?: when (step) {
        PsdGuideStep.SPLIT -> when {
            !state.splitScanned -> tr("psdGuide.actions.ready")
            !state.splitDraftValid -> tr("psdGuide.split.invalidNames")
            state.splitItems.isEmpty() -> tr("psdGuide.split.none")
            else -> tr("editor.meshSplit.selectedCount", state.selectedSplitCount, state.splitItems.size)
        }
        PsdGuideStep.BINDING -> {
            val count = state.bindings?.findings.orEmpty().size
            if (count > 0) tr("psdGuide.binding.tab.todo", count) else tr("psdGuide.binding.clean")
        }
        PsdGuideStep.PRESET -> tr("psdGuide.actions.ready")
        PsdGuideStep.FACE -> tr(if (state.appliedStyle != null) "psdGuide.face.appliedBadge" else "psdGuide.actions.ready")
    }

    val navigateToStep: (PsdGuideStep) -> Unit = { target ->
        if (step == PsdGuideStep.SPLIT && target != PsdGuideStep.SPLIT) {
            state.advanceFromSplit(target)
        } else {
            state.go(target)
        }
    }

    val onCollaborateWithAgent: () -> Unit = {
        viewModel.createPsdGuideProject {
            viewModel.openSettings(SettingsTopic.MCP)
        }
    }
    val onOpenDocs: () -> Unit = {
        viewModel.createPsdGuideProject {
            val openCatalog = actions?.onOpenTutorialCatalog
            if (openCatalog != null) {
                openCatalog()
            } else {
                viewModel.setHelpTab(HelpTab.TUTORIALS)
                viewModel.showStartPage(StartSection.HELP)
            }
        }
    }
    val onOpenWalkthrough: () -> Unit = {
        viewModel.createPsdGuideProject {
            val startTutorial = actions?.onStartTutorial
            if (startTutorial != null) {
                startTutorial(TutorialId.BASIC)
            } else {
                viewModel.setHelpTab(HelpTab.TUTORIALS)
                viewModel.showStartPage(StartSection.HELP)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panelElevated)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status & segmented progress strip.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busyStatus != null) {
                GuideSpinner(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = colors.accent)
            } else {
                Box(Modifier.size(6.dp).clip(CircleShape).background(statusDotColor))
            }
            Text(
                text = summaryText,
                style = typography.caption,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                steps.indices.forEach { idx ->
                    val active = idx <= stepIdx
                    Box(
                        Modifier
                            .width(12.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) colors.accent else colors.divider),
                    )
                }
                Spacer(Modifier.width(3.dp))
                Text(
                    text = "${stepIdx + 1}/${steps.size}",
                    style = typography.monoSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }

        if (isLastStep) {
            val cardShape = RoundedCornerShape(Corners.Control)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(colors.panelBackground, cardShape)
                    .border(0.5.dp, colors.divider, cardShape)
                    .padding(3.dp),
            ) {
                MenuGlide(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        GuideFinishOptionRow(
                            title = tr("psdGuide.finish.agent"),
                            subtitle = tr("psdGuide.finish.agent.desc"),
                            dotColor = colors.accent,
                            enabled = canFinish,
                            onClick = onCollaborateWithAgent,
                        )
                        GuideFinishOptionRow(
                            title = tr("psdGuide.finish.docs"),
                            subtitle = tr("psdGuide.finish.docs.desc"),
                            dotColor = colors.highlight,
                            enabled = canFinish,
                            onClick = onOpenDocs,
                        )
                        GuideFinishOptionRow(
                            title = tr("psdGuide.finish.walkthrough"),
                            subtitle = tr("psdGuide.finish.walkthrough.desc"),
                            dotColor = colors.warning,
                            enabled = canFinish,
                            onClick = onOpenWalkthrough,
                        )
                    }
                }
            }
        } else if (step == PsdGuideStep.SPLIT) {
            val stripShape = RoundedCornerShape(Corners.Control)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(stripShape)
                    .background(colors.panelBackground, stripShape)
                    .border(0.5.dp, colors.divider, stripShape)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("psdGuide.actions.skipHint"),
                    style = typography.captionSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CompactButton(
                    text = tr("psdGuide.disableGuide"),
                    onClick = viewModel::disablePsdGuideAndImport,
                    enabled = ready && !state.saving,
                    height = 22.dp,
                )
                CompactButton(
                    text = tr("psdGuide.skipOnce"),
                    onClick = viewModel::skipPsdGuideOnce,
                    enabled = ready && !state.saving,
                    height = 22.dp,
                )
            }
        }

        // Primary navigation row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactButton(
                text = tr("psdGuide.discard.action"),
                onClick = state::requestClose,
                enabled = !state.saving,
            )
            Spacer(Modifier.weight(1f))
            if (previous != null) {
                CompactButton(
                    text = tr("psdGuide.back"),
                    onClick = { navigateToStep(previous) },
                    enabled = ready && !state.saving,
                )
            }
            if (next != null) {
                CompactButton(
                    text = tr(if (step == PsdGuideStep.SPLIT) "psdGuide.continue" else "psdGuide.next"),
                    onClick = { navigateToStep(next) },
                    enabled = canAdvance && !state.saving,
                    isPrimary = true,
                )
            } else {
                CompactButton(
                    text = tr("psdGuide.finish.done"),
                    onClick = { viewModel.createPsdGuideProject() },
                    enabled = canFinish,
                    isPrimary = true,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideFinishOptionRow(
    title: String,
    subtitle: String,
    dotColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val shape = RoundedCornerShape(Corners.Control)
    TooltipArea(
        tooltip = {
            Surface(
                color = colors.panelElevated,
                shape = RoundedCornerShape(Corners.Control),
                border = BorderStroke(1.dp, colors.border),
                elevation = 4.dp,
            ) {
                Text(
                    text = subtitle,
                    style = typography.captionSmall,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).widthIn(max = 240.dp),
                )
            }
        },
        delayMillis = 350,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .glideItem(enabled)
                .clip(shape)
                .background(Color.Transparent, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .handCursor(enabled)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (enabled) dotColor else colors.textDisabled),
            )
            Text(
                text = title,
                style = typography.caption.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) colors.textPrimary else colors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "→",
                style = typography.monoSmall,
                color = if (enabled) colors.textMuted else colors.textDisabled,
                maxLines = 1,
            )
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
    ready: Boolean,
    stepCounts: Map<PsdGuideStep, StepCountBadge>,
    onSelectStep: (PsdGuideStep) -> Unit,
) {
    val colors = LocalToolColors.current
    Column(Modifier.fillMaxWidth().background(colors.panelElevated)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val singleRow = maxWidth >= 500.dp
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
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    GuideHeaderFileMeta(fileName, metaText, Modifier.fillMaxWidth())
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
