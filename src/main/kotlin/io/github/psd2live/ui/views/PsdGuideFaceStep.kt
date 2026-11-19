package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected as semanticSelected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.application.TurnStyleCandidates
import io.github.psd2live.application.WorkspacePsdGuide
import io.github.psd2live.core.fit.TurnStyle
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ParameterKeyMarks
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.state.PsdGuideState
import io.github.psd2live.ui.state.workspaceFailureMessage
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.theme.selectOrOpen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun FaceGuidePreview(state: PsdGuideState) {
    val guide = state.guide ?: return
    val candidate = state.preset ?: return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val turn = remember(guide, candidate) { guide.turn(candidate) }
    if (turn == null) {
        val preview = remember(guide, candidate) { guide.preview(candidate) }
        LiveGuidePreview(preview)
        return
    }
    val batch = state.batch
    val defaultPose = remember(turn) { (turn.x.default + (turn.x.max - turn.x.default) * .75f) to turn.y.default }
    var tracking by remember { mutableStateOf(true) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    val retained = remember(candidate) { mutableMapOf<Map<String, Float>, WorkspacePsdGuide.Preview>() }
    val retainedFrames = remember(candidate) { mutableMapOf<Map<String, Float>, GuideRigFrame>() }
    val samples = remember(candidate, batch) {
        mutableStateMapOf<Int, WorkspacePsdGuide.Preview>().apply {
            batch.forEachIndexed { index, style -> retained[style.values]?.let { put(index, it) } }
        }
    }
    val failures = remember(candidate, batch) { mutableStateMapOf<Int, String>() }
    var frames by remember(candidate, batch) {
        mutableStateOf(
            buildMap {
                batch.forEachIndexed { index, style ->
                    retainedFrames[style.values]?.let { put(index, it) }
                }
            },
        )
    }

    LaunchedEffect(candidate, batch) {
        val currentKeys = batch.mapTo(hashSetOf()) { it.values }
        retained.keys.retainAll(currentKeys)
        retainedFrames.keys.retainAll(currentKeys)
        state.faceReadyIndices = frames.keys
        state.faceError = null
        batch.forEachIndexed { index, option ->
            if (samples.containsKey(index)) return@forEachIndexed
            launch {
                try {
                    val preview = guide.prepareStyle(candidate, turn, option.values)
                    ensureActive()
                    samples[index] = preview
                    retained[option.values] = preview
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val msg = workspaceFailureMessage(failure)
                    failures[index] = msg
                    state.faceError = failures.values.firstOrNull()
                }
            }
        }
    }

    LaunchedEffect(candidate, batch) {
        var lastPose: Pair<Pair<Float, Float>, Offset?>? = null
        snapshotFlow { Triple(samples.toMap(), state.turnPose ?: defaultPose, pointer.takeIf { tracking }) }
            .conflate().collect { (previews, angle, cursor) ->
                if (previews.isEmpty()) return@collect
                val poseKey = angle to cursor
                val existing = if (lastPose == poseKey) frames else emptyMap()
                if (lastPose != poseKey) retainedFrames.clear()
                val values = mapOf(turn.x.id to angle.first, turn.y.id to angle.second)
                val updated = withContext(Dispatchers.Default) {
                    previews.mapValues { (index, preview) ->
                        ensureActive()
                        existing[index] ?: guideRigFrame(preview, values, cursor)
                    }
                }
                frames = updated
                state.faceReadyIndices = updated.keys
                updated.forEach { (index, frame) ->
                    batch.getOrNull(index)?.let { retainedFrames[it.values] = frame }
                }
                lastPose = poseKey
            }
    }

    val renderers = remember(candidate, turn) { GuideRigRenderers() }
    DisposableEffect(renderers) { onDispose(renderers::close) }
    val progressOwner = remember(state, candidate, batch) { Any() }
    DisposableEffect(progressOwner) { onDispose { state.clearStyleProgress(progressOwner) } }
    val completed = frames.size + failures.size
    SideEffect { state.trackStyleProgress(progressOwner, batch, completed) }

    Column(Modifier.fillMaxSize().background(colors.panelBackground)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (completed < batch.size) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GuideSpinner(
                        Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = colors.accent,
                    )
                    Text(
                        tr("turnExplorer.progress", completed, batch.size),
                        style = typography.caption,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                GuideNote(tr("psdGuide.face.selectHint"), Modifier.weight(1f))
            }
            CompactToggleChip(
                text = tr("preview.mouseTracking.on"),
                selected = tracking,
                onToggle = { tracking = !tracking; pointer = null },
                showCheckWhenSelected = false,
                tooltip = tr(if (tracking) "preview.mouseTracking.on" else "preview.mouseTracking.off"),
                leadingIcon = {
                    IconMouse(
                        active = tracking,
                        modifier = Modifier.size(12.dp),
                        tint = if (tracking) colors.accent else colors.textMuted,
                    )
                },
            )
        }
        Divider(color = colors.divider, thickness = 0.5.dp)
        Box(Modifier.weight(1f).fillMaxWidth().padding(Spacing.Margin)) {
            FaceGrid(
                batch = batch,
                frames = frames,
                failures = failures.keys,
                selected = state.selectedStyle,
                enabled = !state.busy,
                renderers = renderers,
                tracking = tracking,
                onPointer = { pointer = it },
                onSelect = { state.selectedStyle = it },
                applied = state.appliedStyle,
                onApply = { option ->
                    state.selectedStyle = option
                    state.applyStyle()
                },
                flipGeneration = state.faceFlipGeneration,
                flipKeepIndex = state.faceFlipKeepIndex,
            )
        }
    }
}

@Composable
internal fun FaceGuideControls(state: PsdGuideState) {
    val guide = state.guide ?: return
    val candidate = state.preset ?: return
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val turn = remember(guide, candidate) { guide.turn(candidate) }

    GuideNote(tr("psdGuide.step.face.desc"))

    if (turn == null) {
        GuideInfoBanner(tr("psdGuide.face.unavailable"), GuideBannerTone.INFO)
        return
    }

    val defaultPose = remember(turn) { (turn.x.default + (turn.x.max - turn.x.default) * .75f) to turn.y.default }
    val pose = state.turnPose ?: defaultPose
    val xKeys = remember(turn) { ParameterKeyMarks(listOf(turn.x.min, turn.x.default, turn.x.max), emptyList()) }
    val yKeys = remember(turn) { ParameterKeyMarks(listOf(turn.y.min, turn.y.default, turn.y.max), emptyList()) }
    val defaultValues = remember(guide, turn) { guide.turnValues(turn) }
    val batch = state.batch
    val inspectedValues = state.selectedStyle?.values
        ?: state.appliedStyle?.values
        ?: defaultValues

    GuideSectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    tr("psdGuide.face.section.pose"),
                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("turnExplorer.angle", pose.first, pose.second),
                    style = typography.monoSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CompactButton(
                tr("psdGuide.face.reset"),
                { state.turnPose = turn.x.default to turn.y.default },
                enabled = !state.busy,
                height = 22.dp,
            )
        }
        ParameterPad2D(
            turn.x,
            turn.y,
            { pose.first },
            { pose.second },
            state.busy,
            state.busy,
            xKeys,
            emptyList(),
            emptyList(),
            yKeys,
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 188.dp).fillMaxWidth().aspectRatio(1f),
            onChange = { x, y -> state.turnPose = x to y },
        )
    }

    GuideSectionCard {
        Text(
            tr("psdGuide.face.section.explore"),
            style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            CompactButton(
                tr("turnExplorer.next"),
                { state.newStyles(false) },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            CompactButton(
                tr("turnExplorer.nearby"),
                { state.newStyles(true) },
                enabled = !state.busy && state.selectedStyle != null,
                modifier = Modifier.weight(1f),
            )
        }
    }

    GuideSectionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tr("psdGuide.face.section.selection"),
                Modifier.weight(1f),
                style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.appliedStyle != null) {
                GuideBadge(
                    tr("psdGuide.face.appliedBadge"),
                    colors.highlight,
                    colors.highlightContainer,
                )
            }
        }
        GuideNote(tr(if (state.appliedStyle != null) "psdGuide.face.applied" else "psdGuide.face.default"))
        val canApply = !state.busy &&
            batch.indexOf(state.selectedStyle) in state.faceReadyIndices &&
            state.selectedStyle?.values != state.appliedStyle?.values
        CompactButton(
            tr("psdGuide.face.use"),
            state::applyStyle,
            enabled = canApply,
            isPrimary = canApply,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.appliedStyle != null) {
            CompactButton(
                tr("psdGuide.face.keepPreset"),
                state::keepPresetStyle,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    FaceModifierParametersCard(
        values = inspectedValues,
        enabled = !state.busy && batch.isNotEmpty(),
        onPresetChange = state::updateFaceStylePreset,
        onKnobChange = state::updateFaceStyleValue,
    )

    state.faceError?.let { err ->
        GuideInfoBanner(err, GuideBannerTone.ERROR)
    }
}

@Composable
private fun FaceModifierParametersCard(
    values: Map<String, Float>,
    enabled: Boolean,
    onPresetChange: (Int) -> Unit,
    onKnobChange: (String, Float) -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val knobs = RigModules.FACE_DEPTH_TURN.knobs
    val styleKeys = remember { listOf("default") + TurnStyle.PRESETS }
    val styleIndex = (values["style"] ?: 0f).roundToInt().coerceIn(0, styleKeys.lastIndex)
    val styleLabel = tr("settings.rig.turnStyle." + styleKeys[styleIndex])
    val numericKnobs = remember(knobs) { knobs.filter { it.id != "style" } }

    GuideSectionCard {
        Text(
            tr("psdGuide.face.params.summary", styleLabel, knobs.size),
            color = colors.textPrimary,
            style = typography.caption,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        var expanded by rememberSaveable("psd-guide-face-params") { mutableStateOf(false) }
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val rowBg by animateColorAsState(
            targetValue = if (hovered) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent,
            animationSpec = tween(Motion.Instant),
        )
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 90f else 0f,
            animationSpec = tween(Motion.Instant, easing = FastOutSlowInEasing),
        )
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(Corners.Control))
                    .background(rowBg)
                    .hoverable(interaction)
                    .handCursor()
                    .clickable(interactionSource = interaction, indication = null) { expanded = !expanded }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                IconChevron(
                    expanded = false,
                    tint = colors.textMuted,
                    modifier = Modifier.size(10.dp).rotate(chevronRotation),
                )
                Text(
                    text = tr("psdGuide.face.params.title"),
                    style = typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(Motion.Quick, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(Motion.Fade, easing = LinearOutSlowInEasing)),
                exit = shrinkVertically(tween(Motion.Instant, easing = FastOutLinearInEasing)) +
                    fadeOut(tween(Motion.Instant, easing = FastOutLinearInEasing)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tr("settings.rig.turnStyle"),
                            style = typography.body.copy(fontSize = 10.5.sp),
                            color = if (styleIndex != 0) colors.accent else colors.textPrimary,
                            textAlign = TextAlign.Right,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(66.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        CompactDropdown(
                            items = styleKeys.indices.toList(),
                            selectedItem = styleIndex,
                            onItemSelected = { if (it != styleIndex) onPresetChange(it) },
                            itemLabel = { tr("settings.rig.turnStyle." + styleKeys[it]) },
                            enabled = enabled,
                            height = 20.dp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Divider(color = colors.divider, thickness = 0.5.dp)
                    numericKnobs.forEach { knob ->
                        val committed = values[knob.id] ?: knob.default
                        var draft by remember(knob.id, committed) { mutableStateOf(committed) }
                        var dragging by remember(knob.id) { mutableStateOf(false) }
                        val changed = abs(draft - knob.default) >= 0.5f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tr("settings.rig.knob.face.depth-turn.${knob.id}"),
                                style = typography.body.copy(fontSize = 10.5.sp),
                                color = if (changed) colors.accent else colors.textPrimary,
                                textAlign = TextAlign.Right,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(66.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            CompactSlider(
                                value = draft,
                                onValueChange = { next ->
                                    draft = next
                                    if (!dragging) onKnobChange(knob.id, next)
                                },
                                onValueChangeStarted = { dragging = true },
                                onValueChangeFinished = {
                                    dragging = false
                                    onKnobChange(knob.id, draft)
                                },
                                valueRange = knob.range,
                                enabled = enabled,
                                height = 14.dp,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            CompactNumberSpinner(
                                value = draft.toDouble(),
                                onValueChange = { next ->
                                    val clamped = next.toFloat().coerceIn(knob.range)
                                    draft = clamped
                                    onKnobChange(knob.id, clamped)
                                },
                                min = knob.range.start.toDouble(),
                                max = knob.range.endInclusive.toDouble(),
                                step = 1.0,
                                decimals = 0,
                                unit = "%",
                                enabled = enabled,
                                modifier = Modifier.width(58.dp),
                                height = 20.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FaceGrid(
    batch: List<TurnStyleCandidates.Option>,
    frames: Map<Int, GuideRigFrame>,
    failures: Set<Int>,
    selected: TurnStyleCandidates.Option?,
    enabled: Boolean,
    renderers: GuideRigRenderers,
    tracking: Boolean,
    onPointer: (Offset?) -> Unit,
    onSelect: (TurnStyleCandidates.Option) -> Unit,
    applied: TurnStyleCandidates.Option? = null,
    onApply: ((TurnStyleCandidates.Option) -> Unit)? = null,
    flipGeneration: Int = 0,
    flipKeepIndex: Int? = null,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val density = LocalDensity.current.density
    val shape = RoundedCornerShape(Corners.Control)
    Column(Modifier.fillMaxSize().background(colors.panelBackground), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        for (row in 0..2) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
            for (column in 0..2) {
                val index = row * 3 + column
                val style = batch.getOrNull(index) ?: continue
                val active = style == selected
                val isApplied = applied != null && style.values == applied.values
                val frame = frames[index]
                val usable = enabled && frame != null
                val label = tr("turnExplorer.option", index + 1)
                val badgeText = "#${index + 1}"
                val interaction = remember(index) { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val showHover = hovered && usable && !active
                val flipAngle = remember(index) { Animatable(0f) }
                LaunchedEffect(flipGeneration) {
                    if (flipGeneration <= 0 || index == flipKeepIndex) {
                        flipAngle.snapTo(0f)
                        return@LaunchedEffect
                    }
                    delay((row + column) * 28L)
                    flipAngle.animateTo(90f, tween(120, easing = FastOutLinearInEasing))
                    flipAngle.snapTo(-90f)
                    flipAngle.animateTo(0f, tween(180, easing = LinearOutSlowInEasing))
                }
                val borderColor by animateColorAsState(
                    targetValue = when {
                        active -> colors.accent
                        isApplied -> colors.highlight
                        showHover -> colors.borderHover
                        else -> colors.divider
                    },
                    animationSpec = tween(Motion.Instant),
                )
                val borderWidth = when {
                    active -> 2.dp
                    isApplied -> 1.5.dp
                    showHover -> 1.dp
                    else -> 0.5.dp
                }
                val hoverOverlay by animateFloatAsState(
                    targetValue = if (showHover) 0.08f else 0f,
                    animationSpec = tween(Motion.Instant),
                )
                val frameAlpha by animateFloatAsState(
                    targetValue = if (frame != null) 1f else 0f,
                    animationSpec = tween(Motion.Fade, easing = LinearOutSlowInEasing),
                )
                val angle = flipAngle.value
                val depthScale = 1f - 0.05f * (abs(angle) / 90f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            rotationY = angle
                            cameraDistance = 14f * density
                            scaleX = depthScale
                            scaleY = depthScale
                        }
                        .clip(shape)
                        .background(colors.inputBackground)
                        .border(borderWidth, borderColor, shape)
                        .hoverable(interaction, enabled = usable)
                        .guidePointer(tracking, onPointer)
                        .selectOrOpen(
                            enabled = usable,
                            onSelect = { onSelect(style) },
                            onOpen = { (onApply ?: onSelect)(style) },
                        )
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                            semanticSelected = active
                        }
                        .handCursor(usable),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawGuideChecker(light = colors.checkerLight, dark = colors.checkerDark)
                    }
                    GuideRigCanvas(frame, renderers, Modifier.fillMaxSize().graphicsLayer { alpha = frameAlpha })
                    if (hoverOverlay > 0f) {
                        Box(Modifier.fillMaxSize().background(colors.accent.copy(alpha = hoverOverlay)))
                    }
                    Row(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                            GuideBadge(
                                text = badgeText,
                                color = if (active) colors.accentText else colors.textPrimary,
                                background = if (active) colors.accent else colors.panelElevated.copy(alpha = 0.88f),
                            )
                        }
                        if (isApplied) {
                            GuideBadge(
                                text = tr("psdGuide.face.appliedBadge"),
                                color = colors.highlight,
                                background = colors.highlightContainer,
                            )
                        }
                    }
                    if (frame == null && index !in failures) {
                        GuideSpinner(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent.copy(alpha = 0.7f),
                        )
                    }
                    if (index in failures) {
                        Text(
                            tr("turnExplorer.failed"),
                            style = typography.captionSmall,
                            color = colors.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
