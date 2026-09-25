package io.github.psd2live.ui.state

import androidx.compose.ui.input.key.KeyEvent

/**
 * Where a shortcut is dispatched from.
 *
 * The two scopes are not a stylistic split — they mirror the two places a key event can be handled.
 * `APP` bindings are matched in the root preview handler, which sees every key event regardless of
 * focus and consumes on match. `CANVAS` bindings are matched inside the canvas's own focusable
 * `onKeyEvent`, which only runs while the canvas holds focus. That focus dependency is the
 * mechanism that keeps canvas editing keys inert while a text field is being typed into.
 *
 * Because the root handler always runs first, a chord may appear in exactly one scope. A duplicate
 * across scopes is as much a hard error as one inside a scope — it would silently kill the canvas
 * action ("my tool key does nothing").
 */
enum class ShortcutScope { APP, CANVAS }

enum class ShortcutCategory(val scope: ShortcutScope, val labelKey: String) {
    FILE(ShortcutScope.APP, "shortcut.category.file"),
    EDIT(ShortcutScope.APP, "shortcut.category.edit"),
    TABS(ShortcutScope.APP, "shortcut.category.tabs"),
    VIEW(ShortcutScope.APP, "shortcut.category.view"),
    CANVAS_TOOLS(ShortcutScope.CANVAS, "shortcut.category.canvasTools"),
    CANVAS_EDIT(ShortcutScope.CANVAS, "shortcut.category.canvasEdit"),
}

/**
 * Every rebindable action. The label keys deliberately reuse the wording already shown in the menu
 * bar, the canvas toolbar and the help dialog, so renaming an action does not fork its name.
 */
enum class ShortcutAction(val category: ShortcutCategory, val labelKey: String) {
    // File
    OPEN_PROJECT(ShortcutCategory.FILE, "help.shortcuts.openProject"),
    OPEN_PSD(ShortcutCategory.FILE, "help.shortcuts.openPsd"),
    SAVE_PROJECT(ShortcutCategory.FILE, "help.shortcuts.saveProject"),
    SAVE_PROJECT_AS(ShortcutCategory.FILE, "help.shortcuts.saveProjectAs"),
    REANALYZE(ShortcutCategory.FILE, "help.shortcuts.reanalyze"),
    REEXPORT_PSD(ShortcutCategory.FILE, "help.shortcuts.reexportPsd"),
    GENERATE(ShortcutCategory.FILE, "help.shortcuts.generate"),
    OPEN_OUTPUT(ShortcutCategory.FILE, "help.shortcuts.openOutput"),
    TEXTURE_UPSCALE(ShortcutCategory.FILE, "help.shortcuts.textureUpscale"),

    // Edit & history
    UNDO(ShortcutCategory.EDIT, "help.shortcuts.undo"),
    REDO(ShortcutCategory.EDIT, "help.shortcuts.redo"),

    // Tabs
    NEW_EDIT_TAB(ShortcutCategory.TABS, "tab.new.edit"),
    NEW_PREVIEW_TAB(ShortcutCategory.TABS, "tab.new.preview"),
    OPEN_HISTORY_TAB(ShortcutCategory.TABS, "tab.new.history"),
    DUPLICATE_TAB(ShortcutCategory.TABS, "tab.duplicate"),
    CLOSE_TAB(ShortcutCategory.TABS, "tab.close"),
    NEXT_TAB(ShortcutCategory.TABS, "tab.next"),
    PREV_TAB(ShortcutCategory.TABS, "tab.prev"),
    JUMP_TAB_1(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_2(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_3(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_4(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_5(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_6(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_7(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_8(ShortcutCategory.TABS, "shortcut.tab.jump"),
    JUMP_TAB_9(ShortcutCategory.TABS, "shortcut.tab.jump"),

    // View & interface
    ZOOM_IN(ShortcutCategory.VIEW, "menu.view.zoomIn"),
    ZOOM_OUT(ShortcutCategory.VIEW, "menu.view.zoomOut"),
    ZOOM_RESET(ShortcutCategory.VIEW, "menu.view.zoomReset"),
    OPEN_SETTINGS(ShortcutCategory.VIEW, "help.shortcuts.settings"),
    OPEN_HELP(ShortcutCategory.VIEW, "help.shortcuts.help"),

    // Canvas tools
    TOOL_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.select"),
    TOOL_LASSO_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.lasso_select"),
    TOOL_BRUSH_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.brush_select"),
    TOOL_BRUSH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.brush"),
    TOOL_SMOOTH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.smooth"),
    TOOL_INFLATE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.inflate"),
    TOOL_SKELETON_WARP(ShortcutCategory.CANVAS_TOOLS, "editor.tool.skeleton_warp"),
    TOOL_CREATE_WARP(ShortcutCategory.CANVAS_TOOLS, "editor.tool.create_warp"),
    TOOL_CREATE_ROTATION(ShortcutCategory.CANVAS_TOOLS, "editor.tool.create_rotation"),
    TOOL_CREATE_DEFORM_PATH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.create_deform_path"),
    TOOL_GLUE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.glue"),
    TOOL_SUBDIVIDE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.subdivide"),
    TOOL_KNIFE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.knife"),
    TOOL_PAINT_BRUSH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_brush"),
    TOOL_PAINT_PENCIL(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_pencil"),
    TOOL_PAINT_ERASER(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_eraser"),
    TOOL_PAINT_BUCKET(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_bucket"),
    TOOL_PAINT_EYEDROPPER(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_eyedropper"),
    TOOL_PAINT_LINE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_line"),
    TOOL_PAINT_RECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_rect"),
    TOOL_PAINT_ELLIPSE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_ellipse"),

    // Canvas editing
    SELECT_ALL(ShortcutCategory.CANVAS_EDIT, "shortcut.selectAll"),
    INVERT_SELECTION(ShortcutCategory.CANVAS_EDIT, "help.shortcuts.invertSelection"),
    SELECTION_STYLE_BOX(ShortcutCategory.CANVAS_EDIT, "shortcut.selectionBox"),
    SELECTION_STYLE_LASSO(ShortcutCategory.CANVAS_EDIT, "shortcut.selectionLasso"),
    SELECT_LINKED(ShortcutCategory.CANVAS_EDIT, "shortcut.selectLinked"),
    CANCEL(ShortcutCategory.CANVAS_EDIT, "shortcut.cancel"),
    FINISH_PATH(ShortcutCategory.CANVAS_EDIT, "shortcut.finishPath"),
    DELETE_SELECTION(ShortcutCategory.CANVAS_EDIT, "editor.delete"),
    BRUSH_RADIUS_DOWN(ShortcutCategory.CANVAS_EDIT, "shortcut.brushRadiusDown"),
    BRUSH_RADIUS_UP(ShortcutCategory.CANVAS_EDIT, "shortcut.brushRadiusUp"),
    BRUSH_HARDNESS_DOWN(ShortcutCategory.CANVAS_EDIT, "shortcut.brushHardnessDown"),
    BRUSH_HARDNESS_UP(ShortcutCategory.CANVAS_EDIT, "shortcut.brushHardnessUp"),
    BRUSH_ROTATE_LEFT(ShortcutCategory.CANVAS_EDIT, "shortcut.brushRotateLeft"),
    BRUSH_ROTATE_RIGHT(ShortcutCategory.CANVAS_EDIT, "shortcut.brushRotateRight"),
    BRUSH_SHAPE_CYCLE(ShortcutCategory.CANVAS_EDIT, "shortcut.brushShapeCycle"),
    PAINT_SHAPE_CYCLE(ShortcutCategory.CANVAS_EDIT, "shortcut.paintShapeCycle"),
    PAINT_OPACITY_DOWN(ShortcutCategory.CANVAS_EDIT, "shortcut.paintOpacityDown"),
    PAINT_OPACITY_UP(ShortcutCategory.CANVAS_EDIT, "shortcut.paintOpacityUp"),
    AXIS_CONSTRAIN_X(ShortcutCategory.CANVAS_EDIT, "shortcut.axisX"),
    AXIS_CONSTRAIN_Y(ShortcutCategory.CANVAS_EDIT, "shortcut.axisY"),
    FRAME_VIEW(ShortcutCategory.CANVAS_EDIT, "shortcut.frameView"),
    RESET_CAMERA(ShortcutCategory.CANVAS_EDIT, "shortcut.resetCamera"),
    ;

    val scope: ShortcutScope get() = category.scope

    /** 1-based tab index for the `JUMP_TAB_n` family, null for every other action. */
    val jumpIndex: Int?
        get() {
            val name = name
            if (!name.startsWith("JUMP_TAB_")) return null
            return name.removePrefix("JUMP_TAB_").toIntOrNull()
        }

    companion object {
        /** `JUMP_TAB_1`..`JUMP_TAB_9`, used to lay the tab-jump rows out in order. */
        val jumpActions: List<ShortcutAction> = entries.filter { it.jumpIndex != null }
    }
}

enum class KeymapPreset(
    val id: String,
    val labelKey: String,
    val descKey: String,
) {
    PHOTOSHOP("photoshop", "shortcut.preset.photoshop", "shortcut.preset.photoshop.desc"),
    BLENDER("blender", "shortcut.preset.blender", "shortcut.preset.blender.desc"),
    CUBISM("cubism", "shortcut.preset.cubism", "shortcut.preset.cubism.desc"),
    ;

    companion object {
        fun fromId(id: String?): KeymapPreset = entries.firstOrNull { it.id == id } ?: PHOTOSHOP
    }
}

/**
 * The Photoshop-style table. This is the app's historical behaviour verbatim — every alias the old
 * `when` ladders accepted is listed explicitly here, because the matcher is exact and would
 * otherwise drop them. `Ctrl+Shift+*` entries exist only for that reason.
 */
private val PS_DEFAULTS: Map<ShortcutAction, List<KeyBinding>> = mapOf(
    ShortcutAction.OPEN_PROJECT to keys("Ctrl+O"),
    ShortcutAction.OPEN_PSD to keys("Ctrl+Shift+O"),
    ShortcutAction.SAVE_PROJECT to keys("Ctrl+S"),
    ShortcutAction.SAVE_PROJECT_AS to keys("Ctrl+Shift+S"),
    ShortcutAction.REANALYZE to keys("Ctrl+R", "Ctrl+Shift+R"),
    ShortcutAction.REEXPORT_PSD to keys("Ctrl+Shift+E"),
    ShortcutAction.GENERATE to keys("Ctrl+G"),
    ShortcutAction.OPEN_OUTPUT to emptyList(),
    ShortcutAction.TEXTURE_UPSCALE to keys("Ctrl+U", "Ctrl+Shift+U"),

    ShortcutAction.UNDO to keys("Ctrl+Z"),
    ShortcutAction.REDO to keys("Ctrl+Shift+Z", "Ctrl+Y"),

    ShortcutAction.NEW_EDIT_TAB to keys("Ctrl+T"),
    ShortcutAction.NEW_PREVIEW_TAB to keys("Ctrl+Shift+T"),
    ShortcutAction.OPEN_HISTORY_TAB to keys("Ctrl+H", "Ctrl+Shift+H"),
    ShortcutAction.DUPLICATE_TAB to keys("Ctrl+Shift+D"),
    ShortcutAction.CLOSE_TAB to keys("Ctrl+W", "Ctrl+Shift+W"),
    ShortcutAction.NEXT_TAB to keys("Ctrl+Tab"),
    ShortcutAction.PREV_TAB to keys("Ctrl+Shift+Tab"),
    ShortcutAction.JUMP_TAB_1 to keys("Ctrl+1", "Ctrl+NumPad1"),
    ShortcutAction.JUMP_TAB_2 to keys("Ctrl+2", "Ctrl+NumPad2"),
    ShortcutAction.JUMP_TAB_3 to keys("Ctrl+3", "Ctrl+NumPad3"),
    ShortcutAction.JUMP_TAB_4 to keys("Ctrl+4", "Ctrl+NumPad4"),
    ShortcutAction.JUMP_TAB_5 to keys("Ctrl+5", "Ctrl+NumPad5"),
    ShortcutAction.JUMP_TAB_6 to keys("Ctrl+6", "Ctrl+NumPad6"),
    ShortcutAction.JUMP_TAB_7 to keys("Ctrl+7", "Ctrl+NumPad7"),
    ShortcutAction.JUMP_TAB_8 to keys("Ctrl+8", "Ctrl+NumPad8"),
    ShortcutAction.JUMP_TAB_9 to keys("Ctrl+9", "Ctrl+NumPad9"),

    ShortcutAction.ZOOM_IN to keys("Ctrl+=", "Ctrl+Plus", "Ctrl+NumPadAdd"),
    ShortcutAction.ZOOM_OUT to keys("Ctrl+-", "Ctrl+NumPadSubtract"),
    ShortcutAction.ZOOM_RESET to keys("Ctrl+0", "Ctrl+NumPad0"),
    ShortcutAction.OPEN_SETTINGS to keys("Ctrl+,"),
    ShortcutAction.OPEN_HELP to keys("F1"),

    ShortcutAction.TOOL_SELECT to keys("V", "T"),
    ShortcutAction.TOOL_LASSO_SELECT to keys("L"),
    ShortcutAction.TOOL_BRUSH_SELECT to keys("W"),
    ShortcutAction.TOOL_BRUSH to keys("B"),
    ShortcutAction.TOOL_SMOOTH to keys("Shift+B"),
    ShortcutAction.TOOL_INFLATE to keys("I"),
    ShortcutAction.TOOL_SKELETON_WARP to keys("Shift+W"),
    ShortcutAction.TOOL_CREATE_WARP to keys("C"),
    ShortcutAction.TOOL_CREATE_ROTATION to keys("R"),
    ShortcutAction.TOOL_CREATE_DEFORM_PATH to keys("P", "D"),
    ShortcutAction.TOOL_GLUE to keys("G"),
    // M and K were the free letters near the mesh tools. Blender's knife is K, so the paint bucket -
    // a PAINT-mode tool whose key only matters there - moves aside to Shift+K rather than the knife
    // losing its conventional key.
    ShortcutAction.TOOL_SUBDIVIDE to keys("M"),
    ShortcutAction.TOOL_KNIFE to keys("K"),
    ShortcutAction.TOOL_PAINT_BRUSH to keys("Shift+P", "J"),
    ShortcutAction.TOOL_PAINT_PENCIL to keys("N"),
    ShortcutAction.TOOL_PAINT_ERASER to keys("E"),
    ShortcutAction.TOOL_PAINT_BUCKET to keys("Shift+K"),
    ShortcutAction.TOOL_PAINT_EYEDROPPER to keys("Alt+I"),
    ShortcutAction.TOOL_PAINT_LINE to keys("U"),
    ShortcutAction.TOOL_PAINT_RECT to keys("Shift+R"),
    ShortcutAction.TOOL_PAINT_ELLIPSE to keys("Shift+O"),

    ShortcutAction.SELECT_ALL to keys("Ctrl+A"),
    ShortcutAction.INVERT_SELECTION to keys("Ctrl+I"),
    ShortcutAction.SELECTION_STYLE_BOX to keys("Q"),
    ShortcutAction.SELECTION_STYLE_LASSO to keys("Shift+Q"),
    ShortcutAction.SELECT_LINKED to keys("Shift+L"),
    ShortcutAction.CANCEL to keys("Esc"),
    ShortcutAction.FINISH_PATH to keys("Enter"),
    ShortcutAction.DELETE_SELECTION to keys("Del", "Backspace"),
    ShortcutAction.BRUSH_RADIUS_DOWN to keys("["),
    ShortcutAction.BRUSH_RADIUS_UP to keys("]"),
    ShortcutAction.BRUSH_HARDNESS_DOWN to keys("Shift+["),
    ShortcutAction.BRUSH_HARDNESS_UP to keys("Shift+]"),
    ShortcutAction.BRUSH_ROTATE_LEFT to keys("Alt+[", ","),
    ShortcutAction.BRUSH_ROTATE_RIGHT to keys("Alt+]", "."),
    ShortcutAction.BRUSH_SHAPE_CYCLE to keys("Alt+B"),
    // The paint shape tool's three faces, on the same "the tool's key, shifted" pairing.
    ShortcutAction.PAINT_SHAPE_CYCLE to keys("Shift+U"),
    // The paint tip's third parameter, on the same Shift pairing the Alt + drag gesture uses.
    ShortcutAction.PAINT_OPACITY_DOWN to keys("Alt+Shift+["),
    ShortcutAction.PAINT_OPACITY_UP to keys("Alt+Shift+]"),
    ShortcutAction.AXIS_CONSTRAIN_X to keys("X"),
    ShortcutAction.AXIS_CONSTRAIN_Y to keys("Y"),
    ShortcutAction.FRAME_VIEW to keys("F"),
    ShortcutAction.RESET_CAMERA to keys("Home", "0"),
)

/**
 * Blender-flavoured overrides. Only rows where Blender has a directly corresponding function with
 * a well-known key are listed; everything else is inherited from [PS_DEFAULTS], which is why the
 * three presets cannot drift apart on the rows they share.
 */
private val BLENDER_OVERRIDES: Map<ShortcutAction, List<KeyBinding>> = mapOf(
    ShortcutAction.TOOL_SELECT to keys("G", "E"),
    ShortcutAction.TOOL_LASSO_SELECT to keys("C"),
    // Sculpt-mode brush keys.
    ShortcutAction.TOOL_BRUSH to keys("V"),
    ShortcutAction.TOOL_SMOOTH to keys("S"),
    ShortcutAction.TOOL_SKELETON_WARP to keys("Shift+A"),
    ShortcutAction.TOOL_CREATE_WARP to keys("Shift+W"),
    ShortcutAction.TOOL_GLUE to keys("Shift+G"),
    ShortcutAction.TOOL_PAINT_ERASER to keys("Shift+E"),
    // Numpad-dot = view selected.
    ShortcutAction.FRAME_VIEW to keys("NumPadDot"),
    // Workspace cycling.
    ShortcutAction.NEXT_TAB to keys("Ctrl+PageDown"),
    ShortcutAction.PREV_TAB to keys("Ctrl+PageUp"),
)

/**
 * Live2D Cubism Editor overrides, taken from the official 5.x shortcut table.
 */
private val CUBISM_OVERRIDES: Map<ShortcutAction, List<KeyBinding>> = mapOf(
    // Arrow/select tool. `V` has no Cubism default, so it survives as a spare.
    ShortcutAction.TOOL_SELECT to keys("A", "V"),
    // Deform path tool.
    ShortcutAction.TOOL_CREATE_DEFORM_PATH to keys("P", "D"),
    // Show the whole work area.
    ShortcutAction.RESET_CAMERA to keys("Shift+F", "Home", "0"),
)

private fun presetBindings(preset: KeymapPreset): Map<ShortcutAction, List<KeyBinding>> = when (preset) {
    KeymapPreset.PHOTOSHOP -> PS_DEFAULTS
    KeymapPreset.BLENDER -> PS_DEFAULTS + BLENDER_OVERRIDES
    KeymapPreset.CUBISM -> PS_DEFAULTS + CUBISM_OVERRIDES
}

/** Parses the canonical text form; a bad literal here is a programming error, not user input. */
private fun keys(vararg texts: String): List<KeyBinding> =
    texts.map { parseKeyBinding(it) ?: error("Unparseable default binding: '$it'") }

/**
 * The live binding set. Immutable: every edit produces a new instance, which is what lets the
 * dispatchers read it straight off the state object without any change-tracking plumbing.
 */
class Keymap private constructor(
    val preset: KeymapPreset,
    private val bindings: Map<ShortcutAction, List<KeyBinding>>,
) {
    init {
        // Catches "added an action, forgot its default" — the realistic way this file rots.
        val missing = ShortcutAction.entries.filterNot { it in PS_DEFAULTS }
        check(missing.isEmpty()) { "Shortcut actions missing a default binding: $missing" }
    }

    private val appIndex: Map<KeyBinding, ShortcutAction> by lazy { buildIndex(ShortcutScope.APP) }
    private val canvasIndex: Map<KeyBinding, ShortcutAction> by lazy { buildIndex(ShortcutScope.CANVAS) }

    fun bindingsFor(action: ShortcutAction): List<KeyBinding> = bindings[action].orEmpty()

    /** All bindings formatted for display; the first entry is the primary one. */
    fun labelsFor(action: ShortcutAction): List<String> = bindingsFor(action).map { it.format() }

    /** The single label menus show. Null when the action is unbound. */
    fun labelFor(action: ShortcutAction): String? = bindingsFor(action).firstOrNull()?.format()

    /** The action bound to this exact chord in this scope, or null. */
    fun match(event: KeyEvent, scope: ShortcutScope): ShortcutAction? {
        val index = if (scope == ShortcutScope.APP) appIndex else canvasIndex
        return index[keyBindingOf(event)]
    }

    /** Replaces one action's bindings; an empty list is a legitimate "unbound". */
    fun with(action: ShortcutAction, list: List<KeyBinding>): Keymap =
        Keymap(preset, bindings + (action to list))

    /** Every chord of every action mapped to the actions using it, for conflict display. */
    fun conflictIndex(): Map<KeyBinding, List<ShortcutAction>> =
        bindings.entries
            .flatMap { (action, list) -> list.map { it to action } }
            .groupBy({ it.first }, { it.second })

    /**
     * Decides whether [binding] may be assigned to [action] at [index]. Refusing rather than warning
     * is deliberate: an accepted conflict produces a binding that is silently dead, which is the
     * exact failure this feature exists to remove.
     */
    fun validateCapture(action: ShortcutAction, index: Int, binding: KeyBinding): CaptureCheck {
        if (isReservedBinding(binding)) return CaptureCheck.Reserved
        val own = bindingsFor(action)
        own.forEachIndexed { i, existing ->
            if (existing == binding && i != index) return CaptureCheck.DuplicateSelf
        }
        val owner = conflictIndex()[binding]?.firstOrNull { it != action }
        return if (owner == null) CaptureCheck.Ok else CaptureCheck.Conflict(owner)
    }

    private fun buildIndex(scope: ShortcutScope): Map<KeyBinding, ShortcutAction> = buildMap {
        for ((action, list) in bindings) {
            if (action.scope != scope) continue
            for (binding in list) put(binding, action)
        }
    }

    companion object {
        val DEFAULT: Keymap = Keymap(KeymapPreset.PHOTOSHOP, presetBindings(KeymapPreset.PHOTOSHOP))

        /** Rebuilds a keymap from a preset plus the user's per-action overrides. */
        fun of(preset: KeymapPreset, overrides: Map<ShortcutAction, List<KeyBinding>> = emptyMap()): Keymap =
            Keymap(preset, presetBindings(preset) + overrides)
    }
}

sealed interface CaptureCheck {
    data object Ok : CaptureCheck
    data object Reserved : CaptureCheck
    data object DuplicateSelf : CaptureCheck
    data class Conflict(val action: ShortcutAction) : CaptureCheck
}

/**
 * Which binding cell the settings panel is currently recording into, plus the reason the last
 * attempt was refused. Recording stays active on a refusal so the user can just try another chord.
 */
data class KeyCapture(
    val action: ShortcutAction,
    val index: Int,
    val feedback: CaptureCheck? = null,
)

/** Builds the keymap as persisted in [AppSettings]. */
internal fun loadPersistedKeymap(): Keymap =
    Keymap.of(AppSettings.keymapPreset, AppSettings.keymapOverrides())
