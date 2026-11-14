package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.theme.*

/** Coordinates Expand All / Collapse All actions across nested modifier inspector sections. */
@Stable
internal class ModifierFoldController {
    var generation by mutableIntStateOf(0)
        private set
    var targetExpanded by mutableStateOf<Boolean?>(null)
        private set

    fun expandAll() {
        targetExpanded = true
        generation++
    }

    fun collapseAll() {
        targetExpanded = false
        generation++
    }
}

internal val LocalModifierFoldController = compositionLocalOf<ModifierFoldController?> { null }

/**
 * Collapsible card/group for modifier settings and details.
 * [level] 0 renders an elevated section card; [level] >= 1 renders a recessed nested sub-card.
 */
@Composable
internal fun ModifierCollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    level: Int = 0,
    summary: String = "",
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val shape = RoundedCornerShape(Corners.Control)
    val containerColor = if (level == 0) colors.panelElevated else colors.panelBackground
    val borderColor = if (level == 0) colors.border.copy(alpha = 0.55f) else colors.divider.copy(alpha = 0.6f)
    val headerBg = if (hovered && enabled) colors.controlHover.copy(alpha = 0.6f) else Color.Transparent
    val titleColor = if (enabled) colors.textPrimary else colors.textDisabled
    val chevronTint = if (enabled) colors.textMuted else colors.textDisabled
    val summaryColor = if (enabled) colors.textMuted else colors.textDisabled
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded && enabled) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 2600f),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor, shape)
            .border(1.dp, borderColor, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (level == 0) 26.dp else 23.dp)
                .background(headerBg)
                .hoverable(interaction)
                .handCursor(enabled)
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onToggle,
                )
                .padding(horizontal = if (level == 0) 8.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            IconChevron(
                expanded = false,
                tint = chevronTint,
                modifier = Modifier.size(10.dp).rotate(chevronRotation),
            )
            leading?.invoke()
            Text(
                text = title,
                style = typography.caption.copy(
                    fontSize = if (level == 0) 11.sp else 10.5.sp,
                    fontWeight = if (level == 0) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = typography.caption.copy(fontSize = 10.sp),
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke()
        }

        AnimatedVisibility(
            visible = expanded && enabled,
            enter = expandVertically(
                spring(dampingRatio = 0.82f, stiffness = 2400f, visibilityThreshold = IntSize.VisibilityThreshold),
            ) + fadeIn(
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 2400f),
            ),
            exit = shrinkVertically(
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3000f, visibilityThreshold = IntSize.VisibilityThreshold),
            ) + fadeOut(
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3000f),
            ),
        ) {
            Column(Modifier.fillMaxWidth().background(containerColor)) {
                Divider(color = colors.divider.copy(alpha = 0.5f), thickness = 0.5.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (level == 0) 8.dp else 6.dp,
                            vertical = if (level == 0) 6.dp else 5.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * Stateful [ModifierCollapsibleSection] that remembers its own expansion state and responds
 * to [LocalModifierFoldController] expand-all / collapse-all commands.
 */
@Composable
internal fun RememberedModifierSection(
    stateKey: String,
    title: String,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = true,
    level: Int = 0,
    summary: String = "",
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(defaultExpanded) }
    val foldController = LocalModifierFoldController.current
    val generation = foldController?.generation ?: 0
    LaunchedEffect(generation) {
        if (generation > 0) {
            foldController?.targetExpanded?.let { expanded = it }
        }
    }
    ModifierCollapsibleSection(
        title = title,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        level = level,
        summary = summary,
        enabled = enabled,
        leading = leading,
        trailing = trailing,
        content = content,
    )
}

/** Compact status pill with colored dot for modifier runtime state. */
@Composable
internal fun ModifierStatusBadge(
    status: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val isError = status == "missing_dependency" || status == "missing_plugin"
    val isActive = status == "active"
    val dotColor = when {
        isError -> colors.error
        isActive -> colors.accent
        else -> colors.textMuted
    }
    val textColor = when {
        isError -> colors.error
        isActive -> colors.textPrimary
        else -> colors.textMuted
    }
    val bgColor = when {
        isError -> colors.error.copy(alpha = 0.14f)
        isActive -> colors.accent.copy(alpha = 0.14f)
        else -> colors.controlHover.copy(alpha = 0.45f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Control))
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(dotColor))
        Text(
            text = tr("modifier.status.$status"),
            style = typography.caption.copy(fontSize = 10.sp),
            color = textColor,
            maxLines = 1,
        )
    }
}

/** Elevated card container for non-collapsible modifier blocks (e.g. target list, empty state). */
@Composable
internal fun ModifierCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalToolColors.current
    val shape = RoundedCornerShape(Corners.Control)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.panelElevated, shape)
            .border(1.dp, colors.border.copy(alpha = 0.55f), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}
