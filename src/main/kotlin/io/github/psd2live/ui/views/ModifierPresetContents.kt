package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.modifier.BuiltinModifierPlugins
import io.github.psd2live.core.modifier.ModifierPluginReference
import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.core.modifier.ModifierStatus
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.theme.Corners
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.Motion
import io.github.psd2live.ui.theme.Spacing
import io.github.psd2live.ui.theme.handCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class PresetModifierRow(val instance: RigModifierInstance, val status: ModifierStatus?)

/** Keep even inactive modifiers and group only identical captured package references. */
internal fun groupPresetModifiers(
    stack: RigModifierStack,
    statuses: Map<String, ModifierStatus>,
): Map<ModifierPluginReference?, List<PresetModifierRow>> =
    stack.instances.map { PresetModifierRow(it, statuses[it.id]) }.groupBy { (instance) ->
        instance.plugin?.takeUnless {
            it.id == "builtin" || BuiltinModifierPlugins.isHistoricalBuiltinReference(instance.type, instance.version, it)
        } ?: BuiltinModifierPlugins.reference(instance.type, instance.version)
    }

@Composable
internal fun ModifierPresetContents(model: RigPreviewModel) {
    val evaluation = model.modifierEvaluation
    val groups = remember(model, evaluation) {
        val stack = evaluation?.stack ?: model.config.rigEdits.modifierStack?.let(RigModifierStack::fromJson) ?: RigModifierStack()
        groupPresetModifiers(stack, evaluation?.entries.orEmpty().associate { it.instance.id to it.status })
    }
    val count = groups.values.sumOf { it.size }
    val active = groups.values.sumOf { rows -> rows.count { it.status == ModifierStatus.ACTIVE } }
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Gap)) {
        Text(
            tr("modifier.preset.contents.counts", groups.keys.count { it != null }, count, active),
            color = colors.textPrimary,
            style = typography.caption,
            lineHeight = 14.sp,
        )
        if (count > 0) {
            var expanded by rememberSaveable("psd-guide-preset-plugins") { mutableStateOf(false) }
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
                        text = tr("modifier.preset.contents.list"),
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
                    val packageNames by produceState(emptyMap<ModifierPluginReference, String>(), groups.keys) {
                        if (groups.keys.any { it != null && !it.id.startsWith("builtin.") }) {
                            value = withContext(Dispatchers.IO) {
                                ModifierPluginStore.shared.packages().associate { it.reference to it.manifest.name }
                            }
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, top = 4.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        groups.entries.forEachIndexed { index, (reference, rows) ->
                            if (index > 0) Divider(color = colors.divider, thickness = 0.5.dp)
                            val builtin = reference?.id?.startsWith("builtin.") == true
                            val standalone = rows.singleOrNull().takeIf { builtin }
                            val title = when {
                                standalone != null -> standalone.label()
                                reference == null -> tr("modifier.preset.contents.unresolved")
                                builtin -> modifierName(rows.first().instance.type)
                                else -> packageNames[reference] ?: reference.id
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    title,
                                    Modifier.weight(1f),
                                    color = colors.textPrimary,
                                    style = typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                reference?.let { Text(it.version, color = colors.textMuted, style = typography.monoSmall) }
                                standalone?.let { ModifierStatusBadge(it.status?.name?.lowercase() ?: "loading") }
                            }
                            if (standalone == null) {
                                rows.forEach { row ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = Spacing.Margin),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Gap),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            row.label(),
                                            Modifier.weight(1f),
                                            color = colors.textPrimary,
                                            style = typography.caption,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        ModifierStatusBadge(row.status?.name?.lowercase() ?: "loading")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PresetModifierRow.label(): String =
    instance.config["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: modifierName(instance.type)
