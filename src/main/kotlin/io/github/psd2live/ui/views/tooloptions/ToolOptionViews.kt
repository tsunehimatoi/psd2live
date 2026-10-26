package io.github.psd2live.ui.views.tooloptions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.BrushFalloff
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.GlueSubTool
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.SkeletonEditSubTool
import io.github.psd2live.ui.SkeletonPoseSubTool
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.ICON_FINE
import io.github.psd2live.ui.components.IconCheck
import io.github.psd2live.ui.components.IconPin
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconDeformPath
import io.github.psd2live.ui.components.IconDrawOrder
import io.github.psd2live.ui.components.IconRotationDeformer
import io.github.psd2live.ui.components.IconSkeleton
import io.github.psd2live.ui.components.IconTrash
import io.github.psd2live.ui.components.IconUndo
import io.github.psd2live.ui.components.IconWarpDeformer
import io.github.psd2live.ui.components.PaintFgBgSwatch
import io.github.psd2live.ui.components.copySheets
import io.github.psd2live.ui.components.meshPatch
import io.github.psd2live.ui.components.subdivide
import io.github.psd2live.ui.components.toHex
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.tooloptions.ActionOption
import io.github.psd2live.ui.tooloptions.ChoiceOption
import io.github.psd2live.ui.tooloptions.NoteOption
import io.github.psd2live.ui.tooloptions.OptionIcon
import io.github.psd2live.ui.tooloptions.PinOption
import io.github.psd2live.ui.tooloptions.ReferencePinsOption
import io.github.psd2live.ui.tooloptions.SectionOption
import io.github.psd2live.ui.tooloptions.SliderOption
import io.github.psd2live.ui.tooloptions.ToggleOption
import io.github.psd2live.ui.tooloptions.ToolOption
import io.github.psd2live.ui.tooloptions.barOptions
import io.github.psd2live.ui.tooloptions.topOptions
import io.github.psd2live.ui.tooloptions.foldedGroups
import io.github.psd2live.ui.tooloptions.menuOptions
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import io.github.psd2live.ui.views.BrushShapeIcon
import io.github.psd2live.ui.views.GlueSubToolIcon
import io.github.psd2live.ui.views.PaintShapeIcon
import io.github.psd2live.ui.views.PaintSelectShapeIcon
import io.github.psd2live.ui.views.SkeletonEditSubToolIcon
import io.github.psd2live.ui.views.SkeletonPoseSubToolIcon
import io.github.psd2live.ui.views.VertexGroupKindIcon
import io.github.psd2live.ui.views.texture.AccentButton
import io.github.psd2live.ui.views.texture.BarChip
import io.github.psd2live.ui.views.texture.BarDivider
import io.github.psd2live.ui.views.texture.BarValueChip
import io.github.psd2live.ui.views.texture.FloatingBar
import io.github.psd2live.ui.views.texture.FloatingMenu
import io.github.psd2live.ui.views.texture.FloatingMenuPin
import io.github.psd2live.ui.views.texture.FloatingMenuRadio
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import io.github.psd2live.ui.views.texture.FloatingMenuSlider
import io.github.psd2live.ui.views.texture.FloatingMenuSubmenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSwitch
import io.github.psd2live.ui.views.texture.GlidingSegments
import io.github.psd2live.ui.views.texture.LocalMenuGlideHost
import io.github.psd2live.ui.views.texture.MenuGlide
import io.github.psd2live.ui.views.texture.glideRow
import io.github.psd2live.ui.views.vertexGroupKindColor
import org.umamo.runtime.model.VertexGroupKind
import io.github.psd2live.ui.theme.Motion

/** Whether [editor] has a tool options bar to show: options of its own, or paint mode's colours. */
internal fun toolOptionsBarShown(editor: CanvasEditor): Boolean =
    editor.hierarchyMode == EditHierarchyMode.PAINT || barOptions(editor).isNotEmpty()

/**
 * The tool options bar: at the bottom left of the canvas, the values that tune the tool in hand as [barOptions] lists
 * them - Photoshop's options bar in the canvas's frosted style; what decides how the canvas is worked sits in the
 * mode bar at the top ([ModeBarToolOptions]). Paint mode leads with its colours. Its menus open upwards.
 */
@Composable
internal fun BoxScope.ToolOptionsBar(editor: CanvasEditor, focus: () -> Unit) {
    val options = barOptions(editor)
    val painting = editor.hierarchyMode == EditHierarchyMode.PAINT
    if (!painting && options.isEmpty()) return
    val colors = LocalToolColors.current
    FloatingBar(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 8.dp, bottom = 8.dp, end = 56.dp)
            .tutorialTarget(TutorialTargetId.TOOL_OPTIONS_BAR),
    ) {
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (painting) {
                // Photoshop's foreground/background pair; X swaps them on the canvas too.
                PaintFgBgSwatch(
                    foreground = editor.paintColor,
                    background = editor.paintSecondaryColor,
                    onForegroundChanged = { editor.paintColor = it },
                    onBackgroundChanged = { editor.paintSecondaryColor = it },
                    onSwap = { editor.swapPaintColors(); focus() },
                    squareSize = 15.dp,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
                Text(editor.paintColor.toHex(), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = colors.textMuted,
                    modifier = Modifier.padding(end = 2.dp))
                if (options.isNotEmpty()) BarDivider()
            }
            options.forEach { option -> BarOption(editor, option, focus) }
        }
    }
}

@Composable
private fun BarOption(editor: CanvasEditor, option: ToolOption, focus: () -> Unit) {
    val colors = LocalToolColors.current
    when (option) {
        is SectionOption -> BarDivider()
        is SliderOption -> BarValueChip(
            label = tr(option.labelKey),
            display = option.display(option.get(editor)),
            value = option.get(editor),
            onValueChange = { option.apply(editor, it) },
            valueRange = option.range(editor),
            scrub = { start, dx -> option.scrubbed(start, dx, editor.brushSizeLimit) },
            logarithmic = option.logarithmic,
            onCommit = { option.finish?.invoke(editor); focus() },
            rise = true,
        )
        is ChoiceOption<*> -> BarChoice(editor, option, focus)
        is ToggleOption -> BarChip(tr(option.labelKey), selected = option.get(editor), onClick = {
            option.set(editor, !option.get(editor)); focus()
        })
        is ActionOption -> {
            val label = tr(option.labelKey, *option.labelArgs(editor).toTypedArray())
            val run = { invokeMenuAction({}, focus, opensDialog = option.opensDialog) { option.run(editor) } }
            val icon: (@Composable (Color) -> Unit)? = option.icon?.let { icon -> { tint -> OptionIconView(icon, tint) } }
            if (option.primary) AccentButton(label, onClick = run, enabled = option.enabled(editor), icon = icon)
            else BarChip(label, selected = false, onClick = run, enabled = option.enabled(editor), icon = icon)
        }
        is PinOption -> BarChip(option.label(editor) + " " + option.value(editor), selected = option.pinned(editor), onClick = {
            option.toggle(editor); focus()
        }, enabled = option.enabled(editor))
        // A panel of its own, for menus only.
        is ReferencePinsOption -> Unit
        is NoteOption -> option.text(editor)?.let { text ->
            Text(text, fontSize = 10.5.sp, color = if (option.warning(editor)) colors.warning else colors.textMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 4.dp))
        }
    }
}

/** The chord of the number key that picks choice [index] (0-based) of a variant, for its tooltip. */
private fun variantKey(editor: CanvasEditor, index: Int): String =
    ShortcutAction.pickActions.getOrNull(index)?.let(editor.state.keymap::labelFor)?.let { "  ($it)" }.orEmpty()

@Composable
private fun <T> BarChoice(editor: CanvasEditor, option: ChoiceOption<T>, focus: () -> Unit) {
    val current = option.get(editor)
    val choices = option.choices(editor)
    if (option.variant) {
        // Icons side by side; the one in hand also names itself, so the bar says what the tool is set to.
        choices.forEachIndexed { i, choice ->
            val selected = choice == current
            BarChip(if (selected) option.label(editor, choice) else null, selected = selected,
                tooltip = option.label(editor, choice) + variantKey(editor, i),
                icon = { tint -> ChoiceIcon(choice, tint) },
                onClick = { option.set(editor, choice); focus() })
        }
        return
    }
    if (option.inline) {
        GlidingSegments(choices, current, { option.set(editor, it); focus() }, label = { option.label(editor, it) },
            tooltip = { tr(option.labelKey) })
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip("${tr(option.labelKey)}  ${option.label(editor, current)}", selected = false, onClick = { open = !open },
            chevron = true, open = open, icon = if (choiceHasIcon(current)) { tint -> ChoiceIcon(current, tint) } else null)
        FloatingMenu(open, { open = false }, alignment = Alignment.BottomStart, rise = true) {
            choices.forEach { choice ->
                FloatingMenuRadio(option.label(editor, choice), selected = choice == current,
                    onSelect = { option.set(editor, choice); open = false; focus() },
                    icon = if (choiceHasIcon(choice)) { tint -> ChoiceIcon(choice, tint) } else null)
            }
        }
    }
}

/**
 * The tool's options that belong at the top ([topOptions]): the element mode and the confirm and cancel of a step in
 * hand, in the mode bar after a rule. Nothing shows while the tool has none.
 */
@Composable
internal fun ModeBarToolOptions(editor: CanvasEditor, focus: () -> Unit) {
    val options = topOptions(editor)
    if (options.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        BarDivider()
        options.forEach { option -> BarOption(editor, option, focus) }
    }
}

/** One entry of the context menu: an option on its own line, or a group folded into a second level. */
private sealed interface MenuEntry {
    data class Single(val option: ToolOption) : MenuEntry
    data class Level(val id: String, val label: String, val icon: OptionIcon?, val trailing: String?, val options: List<ToolOption>,
                     val choice: ChoiceOption<*>? = null) : MenuEntry
}

/** Lays [options] out as menu entries, folding the groups [foldedGroups] picks into rows that open a second level. */
private fun menuEntries(editor: CanvasEditor, options: List<ToolOption>): List<MenuEntry> = buildList {
    val folded = foldedGroups(editor, options)
    var i = 0
    while (i < options.size) {
        val option = options[i]
        if (option is SectionOption && option.id in folded) {
            val members = options.drop(i + 1).takeWhile { it !is SectionOption }
            add(MenuEntry.Level(option.id, tr(option.labelKey), option.icon, null, members))
            i += 1 + members.size
            continue
        }
        if (option is ChoiceOption<*> && option.id in folded) {
            // A long list of choices opens beside the menu, the one in force named on its row.
            add(MenuEntry.Level(option.id, tr(option.labelKey), null, choiceLabel(editor, option), emptyList(), option))
        } else add(MenuEntry.Single(option))
        i++
    }
}

private fun <T> choiceLabel(editor: CanvasEditor, option: ChoiceOption<T>): String = option.label(editor, option.get(editor))

/**
 * The context menu's share of the tool's options ([menuOptions]), dense enough to need no scrolling: values on one
 * line each, a choice as one row of chips, actions as rows with their icons. Only when that runs past
 * [io.github.psd2live.ui.tooloptions.MENU_ROW_BUDGET] rows do the largest groups fold into rows that open a second
 * level beside the menu ([foldedGroups]).
 * [onAction] returns focus to the canvas; an action closes the menu through [onDismiss] unless it is one taken
 * several times in a row.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ToolOptionMenu(editor: CanvasEditor, onDismiss: () -> Unit, onAction: () -> Unit = {}) {
    val entries = menuEntries(editor, menuOptions(editor))
    var openId by remember { mutableStateOf<String?>(null) }
    val open = entries.filterIsInstance<MenuEntry.Level>().firstOrNull { it.id == openId }
    MenuCascade {
        GlidingColumn(
            modifier = Modifier.width(236.dp),
            count = entries.size,
            highlights = { i -> (entries[i] as? MenuEntry.Single)?.option.let { rowHighlights(it, editor) } },
            dangers = { i -> ((entries[i] as? MenuEntry.Single)?.option as? ActionOption)?.danger == true },
            // A plain row closes the second level the pointer has left, the way a cascading menu does.
            onHover = { i -> if (entries[i] is MenuEntry.Single) openId = null },
        ) { i ->
            when (val entry = entries[i]) {
                is MenuEntry.Single -> MenuOption(editor, entry.option, onDismiss, onAction)
                is MenuEntry.Level -> FloatingMenuSubmenuRow(
                    label = entry.label,
                    open = openId == entry.id,
                    onOpen = { openId = entry.id },
                    trailing = entry.trailing ?: entry.options.count { it is ActionOption }.takeIf { it > 0 }?.toString(),
                    icon = entry.icon?.let { icon -> { tint -> OptionIconView(icon, tint) } },
                )
            }
        }
        MenuSecondLevel(open?.id) {
            val level = open ?: return@MenuSecondLevel
            val choice = level.choice
            val rows = if (choice != null) choice.choices(editor).size else level.options.size
            GlidingColumn(
                modifier = Modifier.width(196.dp),
                count = rows + 1,
                highlights = { i -> i > 0 && (choice != null || rowHighlights(level.options[i - 1], editor)) },
                dangers = { i -> i > 0 && choice == null && (level.options[i - 1] as? ActionOption)?.danger == true },
            ) { i ->
                when {
                    i == 0 -> FloatingMenuSection(level.label)
                    choice != null -> MenuChoiceRadio(editor, choice, i - 1)
                    else -> MenuOption(editor, level.options[i - 1], onDismiss, onAction)
                }
            }
        }
    }
}

/**
 * Whether a menu row takes the gliding row highlight: a row that does one thing does - an action, a switch, a
 * folded group; a caption or a note does not, and neither does a row of controls of its own (a slider, a row of
 * choices), whose controls answer the pointer themselves.
 */
private fun rowHighlights(option: ToolOption?, editor: CanvasEditor): Boolean = when (option) {
    null -> true
    is NoteOption, is SectionOption, is SliderOption, is ReferencePinsOption -> false
    is ChoiceOption<*> -> !(option.inline || option.variant)
    is ActionOption -> option.enabled(editor)
    is PinOption -> option.enabled(editor)
    else -> true
}

/**
 * A column of menu rows with the mode menu's motion: the rows arrive one after another, and one highlight glides to
 * the row under the pointer instead of each row lighting up on its own. [highlights] says which rows take it - not a
 * caption or a note; [onHover] hears which row the pointer entered.
 */
@Composable
internal fun GlidingColumn(
    modifier: Modifier,
    count: Int,
    highlights: (Int) -> Boolean,
    onHover: (Int) -> Unit = {},
    dangers: (Int) -> Boolean = { false },
    row: @Composable (Int) -> Unit,
) {
    MenuGlide(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // The column's rows take the highlight; a row's own glideRow stays out of it.
            for (i in 0 until count) Box(Modifier.glideRow(highlights(i), dangers(i)) { onHover(i) }) {
                CompositionLocalProvider(LocalMenuGlideHost provides null) { row(i) }
            }
        }
    }
}

@Composable
private fun MenuOption(editor: CanvasEditor, option: ToolOption, onDismiss: () -> Unit, onAction: () -> Unit) {
    val colors = LocalToolColors.current
    when (option) {
        is SectionOption -> FloatingMenuSection(tr(option.labelKey))
        is SliderOption -> FloatingMenuSlider(
            label = tr(option.labelKey),
            value = option.get(editor),
            onValueChange = { option.apply(editor, it) },
            valueRange = option.range(editor),
            display = option.display(option.get(editor)),
            logarithmic = option.logarithmic,
        )
        is ChoiceOption<*> -> if (option.inline || option.variant) MenuChoiceRow(editor, option) else {
            // A long choice the menu has room for: its name, then a row per choice.
            FloatingMenuSection(tr(option.labelKey))
            MenuChoiceRadios(editor, option)
        }
        is ToggleOption -> FloatingMenuSwitch(tr(option.labelKey), option.get(editor), { option.set(editor, it) })
        is PinOption -> FloatingMenuPin(option.label(editor), option.value(editor), option.pinned(editor),
            { option.toggle(editor); onAction() }, enabled = option.enabled(editor))
        is ReferencePinsOption -> editor.state.previewModel?.rig?.puppet?.let { puppet ->
            io.github.psd2live.ui.views.ReferencePinPanel(puppet, editor.state.parameterValues, option.target.pin(editor.state.layout), editor.editable) { point ->
                editor.viewModel.toggleReferencePoint(option.target, point); onAction()
            }
        }
        is ActionOption -> FloatingMenuRow(
            label = tr(option.labelKey, *option.labelArgs(editor).toTypedArray()),
            onClick = {
                invokeMenuAction(onDismiss, onAction, option.keepsMenu, option.opensDialog) { option.run(editor) }
            },
            enabled = option.enabled(editor),
            danger = option.danger,
            icon = option.icon?.let { icon ->
                { tint -> OptionIconView(icon, tint) }
            },
        )
        is NoteOption -> option.text(editor)?.let { text ->
            Text(text, fontSize = 10.5.sp, color = if (option.warning(editor)) colors.warning else colors.textMuted, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
        }
    }
}

/** A choice on one line: its name, then its choices as chips - icons for a tool's variants. */
@Composable
private fun <T> MenuChoiceRow(editor: CanvasEditor, option: ChoiceOption<T>) {
    val colors = LocalToolColors.current
    val current = option.get(editor)
    Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 9.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (option.captioned) Text(tr(option.labelKey), color = colors.textMuted, fontSize = 10.5.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(54.dp))
        val choices = option.choices(editor)
        val icons = option.variant && choices.all { choiceHasIcon(it) }
        GlidingSegments(
            choices = choices,
            selected = current,
            onSelect = { option.set(editor, it) },
            label = { if (icons) null else option.label(editor, it) },
            tooltip = { choice -> if (icons) option.label(editor, choice) + variantKey(editor, choices.indexOf(choice)) else null },
            iconOf = if (icons) { choice, tint -> ChoiceIcon(choice, tint) } else null,
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        )
    }
}

/** Choice [index] of [option] as one radio row. */
@Composable
private fun <T> MenuChoiceRadio(editor: CanvasEditor, option: ChoiceOption<T>, index: Int) {
    val choice = option.choices(editor).getOrNull(index) ?: return
    FloatingMenuRadio(option.label(editor, choice), selected = choice == option.get(editor), onSelect = { option.set(editor, choice) },
        icon = if (choiceHasIcon(choice)) { tint -> ChoiceIcon(choice, tint) } else null)
}

@Composable
private fun <T> MenuChoiceRadios(editor: CanvasEditor, option: ChoiceOption<T>) {
    val current = option.get(editor)
    option.choices(editor).forEach { choice ->
        FloatingMenuRadio(option.label(editor, choice), selected = choice == current, onSelect = { option.set(editor, choice) },
            icon = if (choiceHasIcon(choice)) { tint -> ChoiceIcon(choice, tint) } else null)
    }
}

/** Whether [choice] has a drawing of its own: the variants of the tools, and the falloff curves. */
private fun choiceHasIcon(choice: Any?): Boolean = choice is BrushShape || choice is PaintShape || choice is GlueSubTool ||
    choice is io.github.psd2live.ui.PaintSelectShape ||
    choice is SkeletonEditSubTool || choice is SkeletonPoseSubTool || choice is VertexGroupKind || choice is BrushFalloff

@Composable
private fun ChoiceIcon(choice: Any?, tint: Color) {
    when (choice) {
        is BrushShape -> BrushShapeIcon(choice, tint)
        is PaintShape -> PaintShapeIcon(choice, tint)
        is io.github.psd2live.ui.PaintSelectShape -> PaintSelectShapeIcon(choice, tint)
        is GlueSubTool -> GlueSubToolIcon(choice, tint)
        is SkeletonEditSubTool -> SkeletonEditSubToolIcon(choice, tint)
        is SkeletonPoseSubTool -> SkeletonPoseSubToolIcon(choice, tint)
        // A group kind keeps the colour the canvas paints it in.
        is VertexGroupKind -> VertexGroupKindIcon(choice, vertexGroupKindColor(choice))
        is BrushFalloff -> FalloffCurveIcon(choice, tint)
        else -> Unit
    }
}

/** The profile itself drawn as a bump, the way Blender's falloff menu pictures each entry. */
@Composable
private fun FalloffCurveIcon(falloff: BrushFalloff, tint: Color) = GridIcon(Modifier.size(13.dp), tint) {
    val samples = 24
    outline(path {
        for (i in 0..samples) {
            val x = i / samples.toFloat()
            val y = falloff.weight(1f - kotlin.math.abs(x * 2f - 1f), i)
            val gx = 1.6f + 14.8f * x
            val gy = 15.6f - 12.6f * y
            if (i == 0) m(gx, gy) else l(gx, gy)
        }
    })
}

/** The drawing of [icon], in [tint]. */
@Composable
internal fun OptionIconView(icon: OptionIcon, tint: Color) {
    val modifier = Modifier.size(12.dp)
    when (icon) {
        OptionIcon.SELECT_ALL -> IconMenuSelectAll(tint, Modifier.size(13.dp))
        OptionIcon.INVERT -> IconMenuInvert(tint, Modifier.size(13.dp))
        OptionIcon.LINKED -> IconMenuLinked(tint, Modifier.size(13.dp))
        OptionIcon.DESELECT -> IconClose(modifier = Modifier.size(10.dp), tint = tint)
        OptionIcon.SPLIT -> IconMenuSplit(tint, modifier)
        OptionIcon.SUBDIVIDE -> GridIcon(modifier, tint) { subdivide() }
        OptionIcon.CONNECT -> IconMenuConnect(tint, modifier)
        OptionIcon.MERGE, OptionIcon.REMERGE -> IconMenuMerge(tint, modifier)
        OptionIcon.DELETE -> IconTrash(modifier = modifier, tint = tint)
        OptionIcon.DUPLICATE -> GridIcon(modifier, tint) { copySheets() }
        OptionIcon.WARP -> IconWarpDeformer(tint, modifier)
        OptionIcon.ROTATION -> IconRotationDeformer(modifier = modifier, tint = tint)
        OptionIcon.PATH -> IconDeformPath(modifier = modifier, tint = tint)
        OptionIcon.SKELETON -> IconSkeleton(modifier = modifier, tint = tint)
        OptionIcon.SWING -> IconSwing(tint, modifier)
        OptionIcon.DEPTH_SPLIT -> IconDrawOrder(modifier = modifier, tint = tint)
        OptionIcon.CONFIRM -> IconCheck(modifier = Modifier.size(11.dp), tint = tint)
        OptionIcon.CANCEL -> IconClose(modifier = Modifier.size(9.dp), tint = tint)
        OptionIcon.UNDO -> IconUndo(Modifier.size(12.dp), tint)
        OptionIcon.CLEAR -> IconTrash(modifier = Modifier.size(11.dp), tint = tint)
        OptionIcon.FILL -> GridIcon(modifier, tint) { fillBox(3f, 3f, 12f, 12f, 1.6f) }
        OptionIcon.ERASE -> GridIcon(modifier, tint) { box(3f, 3f, 12f, 12f, 1.6f, ICON_FINE); line(4.4f, 13.6f, 13.6f, 4.4f) }
        OptionIcon.SWAP -> GridIcon(modifier, tint) {
            line(3f, 6f, 14f, 6f); chevron(14f, 6f, 1f, 0f, 2.6f)
            line(15f, 12f, 4f, 12f); chevron(4f, 12f, -1f, 0f, 2.6f)
        }
        OptionIcon.RESET -> GridIcon(modifier, tint) { arcArrow(9f, 9f, 5.8f, -60f, 230f); dot(9f, 9f, 1.4f) }
        OptionIcon.SELECTION -> GridIcon(modifier, tint) { box(2.4f, 2.4f, 13.2f, 13.2f, 1.6f, ICON_FINE, dash = floatArrayOf(1.6f, 2.2f)) }
        OptionIcon.TOPOLOGY -> GridIcon(modifier, tint) { subdivide() }
        OptionIcon.CREATE -> GridIcon(modifier, tint) { line(9f, 3f, 9f, 15f); line(3f, 9f, 15f, 9f) }
        OptionIcon.GROUP -> GridIcon(modifier, tint) { dot(4.6f, 12.6f, 2f); dot(9f, 7.6f, 2f); dot(13.4f, 12.6f, 2f); ring(9f, 10f, 7.2f, ICON_FINE) }
        OptionIcon.MESH -> GridIcon(modifier, tint) { meshPatch() }
        OptionIcon.REFERENCE -> io.github.psd2live.ui.views.IconReferenceImage(tint, 12.dp)
        OptionIcon.REBUILD -> GridIcon(modifier, tint) { arcArrow(9f, 9f, 5.8f, -60f, 300f) }
        OptionIcon.MATERIAL -> GridIcon(modifier, tint) { box(2f, 6.5f, 8f, 5f, 2.5f); box(8f, 6.5f, 8f, 5f, 2.5f) }
        OptionIcon.UNLINK -> GridIcon(modifier, tint) { box(1f, 6.5f, 6.5f, 5f, 2.5f); box(10.5f, 6.5f, 6.5f, 5f, 2.5f) }
        OptionIcon.PIN -> IconPin(pinned = false, modifier = modifier, tint = tint)
        // A shape and its reflection across a dashed axis.
        OptionIcon.MIRROR -> GridIcon(modifier, tint) {
            outline(path { m(9f, 1.6f); l(9f, 16.4f) }, ICON_FINE, dash = floatArrayOf(1.6f, 1.8f))
            shape(path { m(6.6f, 4f); l(6.6f, 14f); l(1.8f, 14f); z() })
            outline(path { m(11.4f, 4f); l(11.4f, 14f); l(16.2f, 14f); z() })
        }
        // A shape between two keys: a square at the key between two dots on its track.
        OptionIcon.KEYFORM -> GridIcon(modifier, tint) { line(1.8f, 9f, 16.2f, 9f, ICON_FINE); dot(3f, 9f, 1.6f); dot(15f, 9f, 1.6f); fillBox(6.4f, 6.4f, 5.2f, 5.2f, 1f) }
        // A clipboard.
        OptionIcon.KEYFORM_PASTE -> GridIcon(modifier, tint) { box(3.4f, 3.6f, 11.2f, 12.6f, 1.6f, ICON_FINE); fillBox(6.4f, 1.8f, 5.2f, 3.4f, 1f) }
        // The key between two others, hollow, on the line from one to the other.
        OptionIcon.INTERPOLATE -> GridIcon(modifier, tint) { line(4.6f, 9f, 13.4f, 9f, ICON_FINE); dot(3f, 9f, 1.8f); dot(15f, 9f, 1.8f); ring(9f, 9f, 2.4f, ICON_FINE) }
    }
}

/** Select all: everything inside the frame checked. */
@Composable
private fun IconMenuSelectAll(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    panel(2.4f, 2.4f, 13.2f, 13.2f, 2f)
    outline(path { m(5.6f, 9.2f); l(8f, 11.6f); l(12.6f, 6.4f) }, 1.6f)
}

/** Invert selection: a disc half filled. */
@Composable
private fun IconMenuInvert(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    fill(Path().apply {
        moveTo(p(9f, 2.4f).x, p(9f, 2.4f).y)
        lineTo(p(9f, 15.6f).x, p(9f, 15.6f).y)
        arcTo(androidx.compose.ui.geometry.Rect(p(2.4f, 2.4f), p(15.6f, 15.6f)), 90f, 180f, forceMoveTo = false)
        close()
    })
    ring(9f, 9f, 6.6f)
}

/** Select linked: two vertices joined by an edge. */
@Composable
private fun IconMenuLinked(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    line(7.6f, 9f, 10.4f, 9f)
    shape(circlePath(4.8f, 9f, 2.8f))
    shape(circlePath(13.2f, 9f, 2.8f))
}

/** Split: a vertex inserted in the middle of an edge. */
@Composable
private fun IconMenuSplit(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    line(4.8f, 11.4f, 13.2f, 6.6f)
    ring(3.4f, 12.2f, 1.6f, ICON_FINE)
    ring(14.6f, 5.8f, 1.6f, ICON_FINE)
    dot(9f, 9f, 1.9f)
}

/** Connect: an edge drawn between two vertices. */
@Composable
private fun IconMenuConnect(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    line(4f, 13f, 14f, 5f)
    dot(4f, 13f, 1.9f)
    dot(14f, 5f, 1.9f)
}

/** Merge: two vertices drawn into one. */
@Composable
private fun IconMenuMerge(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    ring(3.6f, 4f, 1.5f, ICON_FINE)
    ring(14.4f, 4f, 1.5f, ICON_FINE)
    line(4.8f, 5.4f, 7.6f, 8.8f)
    chevron(7.6f, 8.8f, 1f, 1.2f, 2.6f)
    line(13.2f, 5.4f, 10.4f, 8.8f)
    chevron(10.4f, 8.8f, -1f, 1.2f, 2.6f)
    dot(9f, 12.6f, 2.2f)
}

/** Swing: a pendulum with the arc it sways along. */
@Composable
private fun IconSwing(tint: Color, modifier: Modifier) = GridIcon(modifier, tint) {
    dot(9f, 2.6f, 1.2f)
    line(9f, 2.6f, 12.2f, 11.6f)
    dot(12.6f, 12.6f, 2.2f)
    arcArrow(9f, 2.6f, 12.8f, 55f, 125f)
}
