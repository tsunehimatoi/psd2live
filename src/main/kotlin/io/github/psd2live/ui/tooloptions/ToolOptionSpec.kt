package io.github.psd2live.ui.tooloptions

import io.github.psd2live.ui.CanvasEditor
import kotlin.math.exp

/*
 * The settings and actions of the tool in hand, described once. The canvas's tool options bar and its context
 * menu both render these, so a parameter has one range, one step and one label wherever it is changed - the bar,
 * the menu or the brush keys. Nothing here knows about Compose; labels are message keys.
 */

/** What a value is, so the brush keys can find "the size" or "the hardness" of whatever tool is in hand. */
internal enum class OptionRole { SIZE, HARDNESS, STRENGTH, OPACITY, ANGLE }

/** How a value is shown: pixels, a fraction shown as a percentage, degrees, or a bare number. */
internal enum class OptionUnit { PX, PERCENT, DEGREES, COUNT }

/** The icon a menu row or bar chip carries; the views map each to its drawing. */
internal enum class OptionIcon {
    SELECT_ALL, INVERT, LINKED, DESELECT,
    SPLIT, SUBDIVIDE, CONNECT, MERGE, DELETE, DUPLICATE,
    WARP, ROTATION, PATH, SKELETON, SWING, DEPTH_SPLIT,
    CONFIRM, CANCEL, UNDO, CLEAR,
    FILL, ERASE, SWAP, REMERGE, RESET, SELECTION, TOPOLOGY, CREATE, GROUP,
    MESH, REFERENCE, REBUILD, MATERIAL, UNLINK, PIN, MIRROR,
    // Deform's keyform tools.
    KEYFORM, KEYFORM_PASTE, INTERPOLATE,
}

/**
 * Where an option shows: the options bar at the bottom left, the context menu, or both - or the mode bar at the top
 * and the menu ([TOP]), for what decides how the canvas is worked: the element mode, the confirm and cancel of a
 * step in hand. The bottom bar keeps to the values that tune the tool.
 */
internal enum class OptionPlace(val bar: Boolean, val menu: Boolean, val top: Boolean = false) {
    BOTH(true, true), BAR(true, false), MENU(false, true), TOP(false, true, true),
}

internal sealed interface ToolOption {
    /** Unique within one tool's list, for keys and tests. */
    val id: String
    val place: OptionPlace
}

/**
 * A caption opening a group: a section title in the menu, a rule in the bar. A [submenu] group is folded into one
 * row of the context menu that opens it as a second level, so a long list of actions does not make the menu scroll.
 */
internal class SectionOption(
    override val id: String,
    val labelKey: String,
    override val place: OptionPlace = OptionPlace.BOTH,
    val submenu: Boolean = false,
    val icon: OptionIcon? = null,
    /** A [submenu] group the context menu always folds, however few rows it takes: the create actions. */
    val alwaysFolds: Boolean = false,
) : ToolOption

/**
 * A number. [get] and [set] are in the value's own units (a fraction for [OptionUnit.PERCENT]); every write goes
 * through [apply], which clamps to [bounds], so the bar, the menu and the keys cannot leave the range.
 * [bounds] takes the editor's brush size limit, the document's long side or more.
 */
internal class SliderOption(
    override val id: String,
    val labelKey: String,
    val unit: OptionUnit,
    val bounds: (sizeLimit: Float) -> ClosedFloatingPointRange<Float>,
    val get: (CanvasEditor) -> Float,
    private val set: (CanvasEditor, Float) -> Unit,
    /** The additive step of the brush keys; sizes ignore it and scale by [SIZE_KEY_FACTOR] instead. */
    val step: Float = 0.05f,
    val logarithmic: Boolean = false,
    val role: OptionRole? = null,
    val integer: Boolean = false,
    override val place: OptionPlace = OptionPlace.BOTH,
    /** Run when a drag or an entry on the bar ends: what a value that records history closes its one step with. */
    val finish: ((CanvasEditor) -> Unit)? = null,
) : ToolOption {
    fun range(editor: CanvasEditor): ClosedFloatingPointRange<Float> = bounds(editor.brushSizeLimit)

    fun clamp(value: Float, sizeLimit: Float): Float {
        val r = bounds(sizeLimit)
        val v = value.coerceIn(r.start, r.endInclusive)
        return if (integer) kotlin.math.round(v) else v
    }

    fun apply(editor: CanvasEditor, value: Float) = set(editor, clamp(value, editor.brushSizeLimit))

    /** The value the brush keys step to, up or down from [value]. */
    fun stepped(value: Float, up: Boolean): Float = when {
        role == OptionRole.SIZE -> if (up) value * SIZE_KEY_FACTOR else value / SIZE_KEY_FACTOR
        else -> if (up) value + step else value - step
    }

    /** The value a sideways drag of [dx] pixels on the bar reaches from [start]. */
    fun scrubbed(start: Float, dx: Float, sizeLimit: Float): Float {
        val r = bounds(sizeLimit)
        return if (logarithmic && r.start > 0f) start * exp(dx / 120f)
        else start + dx * (r.endInclusive - r.start) / 240f
    }

    fun display(value: Float): String = when (unit) {
        OptionUnit.PX -> if (value < 10f && !integer) "${"%.1f".format(value)}px" else "${value.toInt()}px"
        OptionUnit.PERCENT -> "${kotlin.math.round(value * 100f).toInt()}%"
        OptionUnit.DEGREES -> "${value.toInt()}°"
        OptionUnit.COUNT -> if (integer) "${value.toInt()}" else "%.2f".format(value)
    }

    companion object {
        const val SIZE_KEY_FACTOR = 1.2f
    }
}

/**
 * One of a few values. [inline] lays the choices out side by side (a handful of short ones); otherwise the bar shows
 * the current one behind a menu and the context menu behind a second level. [label] names a choice, already
 * translated. A [variant] is the flavour of the tool itself - a brush tip, a sub-tool - drawn as its icon.
 */
internal class ChoiceOption<T>(
    override val id: String,
    val labelKey: String,
    val choices: (CanvasEditor) -> List<T>,
    val label: (CanvasEditor, T) -> String,
    val get: (CanvasEditor) -> T,
    val set: (CanvasEditor, T) -> Unit,
    val inline: Boolean = true,
    override val place: OptionPlace = OptionPlace.BOTH,
    val variant: Boolean = false,
    /** Whether a row of it in the menu carries its name; off for choices that name themselves. */
    val captioned: Boolean = true,
) : ToolOption

/** On or off. */
internal class ToggleOption(
    override val id: String,
    val labelKey: String,
    val get: (CanvasEditor) -> Boolean,
    val set: (CanvasEditor, Boolean) -> Unit,
    override val place: OptionPlace = OptionPlace.BOTH,
) : ToolOption

/**
 * A command of the tool. [labelArgs] fills the label's placeholders; [primary] is the action the tool exists for;
 * [keepsMenu] leaves the context menu open after it, for a step taken several times in a row.
 * [opensDialog] keeps focus with the dialog or editor window the action opens.
 */
internal class ActionOption(
    override val id: String,
    val labelKey: String,
    val enabled: (CanvasEditor) -> Boolean,
    val run: (CanvasEditor) -> Unit,
    val icon: OptionIcon? = null,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val keepsMenu: Boolean = false,
    val labelArgs: (CanvasEditor) -> List<Any> = { emptyList() },
    override val place: OptionPlace = OptionPlace.BOTH,
    val opensDialog: Boolean = false,
) : ToolOption

/**
 * A value something is pinned at, or free to pin: a parameter a reference layer shows at, say. [label] and [value]
 * are shown as they are (a parameter's own name, a number); a click calls [toggle] and keeps the menu open, so
 * several can be pinned in a row.
 */
internal class PinOption(
    override val id: String,
    val label: (CanvasEditor) -> String,
    val value: (CanvasEditor) -> String,
    val pinned: (CanvasEditor) -> Boolean,
    val toggle: (CanvasEditor) -> Unit,
    val enabled: (CanvasEditor) -> Boolean = { true },
    override val place: OptionPlace = OptionPlace.MENU,
) : ToolOption

/**
 * A reference's keyform pin as the compact parameter panel ([io.github.psd2live.ui.views.ReferencePinPanel]): a
 * track or 2D pad per parameter, each with its push pin. Shown in menus only.
 */
internal class ReferencePinsOption(
    override val id: String,
    val target: io.github.psd2live.ui.state.ReferenceTarget,
    override val place: OptionPlace = OptionPlace.MENU,
) : ToolOption

/** A line of state - what the tool acts on, or what it is waiting for. Null [text] shows nothing. */
internal class NoteOption(
    override val id: String,
    val text: (CanvasEditor) -> String?,
    val warning: (CanvasEditor) -> Boolean = { false },
    override val place: OptionPlace = OptionPlace.BOTH,
) : ToolOption
