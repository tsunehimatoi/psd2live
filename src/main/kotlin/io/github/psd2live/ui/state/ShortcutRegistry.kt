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
 * `TIMELINE` bindings are matched by the motion editor's timeline the same way, while it holds focus.
 * The canvas and the timeline never hold focus together, so they may share a chord (Del deletes
 * vertices in one and keys in the other).
 *
 * Because the root handler always runs first, an `APP` chord may appear in no other scope. Such a
 * duplicate is as much a hard error as one inside a scope — it would silently kill the canvas
 * action ("my tool key does nothing").
 */
enum class ShortcutScope {
    APP, CANVAS, TIMELINE;

    /** Whether one chord in both scopes would collide: the same scope, or either is the root's. */
    fun overlaps(other: ShortcutScope): Boolean = this == other || this == APP || other == APP
}

/**
 * How an action answers its input.
 *
 * - `COMMAND` fires once per key press, wheel notch or button click.
 * - `HOLD` lasts while its key or button is held, so a wheel notch cannot drive it.
 * - `DRAG` is a mouse gesture: a button (with its modifiers) starts it and the pointer's motion
 *   drives it until the button is released, so only a mouse button can be bound to it.
 */
enum class ShortcutKind { COMMAND, HOLD, DRAG }

enum class ShortcutCategory(val scope: ShortcutScope, val labelKey: String) {
    FILE(ShortcutScope.APP, "shortcut.category.file"),
    EDIT(ShortcutScope.APP, "shortcut.category.edit"),
    TABS(ShortcutScope.APP, "shortcut.category.tabs"),
    VIEW(ShortcutScope.APP, "shortcut.category.view"),
    CANVAS_TOOLS(ShortcutScope.CANVAS, "shortcut.category.canvasTools"),
    CANVAS_EDIT(ShortcutScope.CANVAS, "shortcut.category.canvasEdit"),
    CANVAS_MOUSE(ShortcutScope.CANVAS, "shortcut.category.canvasMouse"),
    TIMELINE(ShortcutScope.TIMELINE, "shortcut.category.timeline"),
}

/**
 * Every rebindable action. The label keys deliberately reuse the wording already shown in the menu
 * bar, the canvas toolbar and the help dialog, so renaming an action does not fork its name.
 */
enum class ShortcutAction(
    val category: ShortcutCategory,
    val labelKey: String,
    val kind: ShortcutKind = ShortcutKind.COMMAND,
) {
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
    TOGGLE_FULLSCREEN(ShortcutCategory.VIEW, "menu.view.fullscreen"),
    OPEN_SETTINGS(ShortcutCategory.VIEW, "help.shortcuts.settings"),
    OPEN_HELP(ShortcutCategory.VIEW, "help.shortcuts.help"),

    TEMPORARY_SELECT(ShortcutCategory.CANVAS_TOOLS, "shortcut.temporarySelect", ShortcutKind.HOLD),
    QUICK_PREVIEW(ShortcutCategory.CANVAS_TOOLS, "shortcut.quickPreview"),
    TOGGLE_OBJECT_MODE(ShortcutCategory.CANVAS_TOOLS, "shortcut.toggleObjectMode"),

    MODE_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.mode.select"),
    MODE_DEFORM(ShortcutCategory.CANVAS_TOOLS, "editor.mode.deform"),
    MODE_EDIT(ShortcutCategory.CANVAS_TOOLS, "editor.mode.edit"),
    MODE_SIMULATE(ShortcutCategory.CANVAS_TOOLS, "editor.mode.simulate"),
    MODE_SKELETON(ShortcutCategory.CANVAS_TOOLS, "editor.mode.skeleton"),
    MODE_PAINT(ShortcutCategory.CANVAS_TOOLS, "editor.mode.paint"),
    MODE_PREVIEW(ShortcutCategory.CANVAS_TOOLS, "editor.mode.preview"),

    // Canvas tools
    TOOL_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.select"),
    TOOL_TRANSFORM(ShortcutCategory.CANVAS_TOOLS, "editor.tool.transform"),
    TOOL_LASSO_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.lasso_select"),
    TOOL_BRUSH_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.brush_select"),
    TOOL_BRUSH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.brush"),
    TOOL_SMOOTH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.smooth"),
    TOOL_INFLATE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.inflate"),
    TOOL_SKELETON_POSE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.skeleton_pose"),
    TOOL_SKELETON_EDIT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.skeleton_edit"),
    TOOL_CREATE_WARP(ShortcutCategory.CANVAS_TOOLS, "editor.tool.create_warp"),
    TOOL_CREATE_ROTATION(ShortcutCategory.CANVAS_TOOLS, "editor.tool.create_rotation"),
    TOOL_CREATE_DEFORM_PATH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.create_deform_path"),
    TOOL_GLUE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.glue"),
    TOOL_SUBDIVIDE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.subdivide"),
    // The point tool's two faces. TOOL_KNIFE keeps its name so saved keymaps still bind the line face.
    TOOL_KNIFE(ShortcutCategory.CANVAS_TOOLS, "shortcut.meshPointLine"),
    TOOL_MESH_POINT(ShortcutCategory.CANVAS_TOOLS, "shortcut.meshPointPoint"),
    TOOL_WEIGHT_PAINT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.weight_paint"),
    TOOL_WEIGHT_GRADIENT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.weight_gradient"),
    TOOL_PAINT_BRUSH(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_brush"),
    TOOL_PAINT_PENCIL(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_pencil"),
    TOOL_PAINT_ERASER(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_eraser"),
    TOOL_PAINT_BUCKET(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_bucket"),
    TOOL_PAINT_EYEDROPPER(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_eyedropper"),
    TOOL_PAINT_LINE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_line"),
    TOOL_PAINT_RECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_rect"),
    TOOL_PAINT_ELLIPSE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_ellipse"),
    TOOL_INTERIOR(ShortcutCategory.CANVAS_TOOLS, "editor.tool.interior"),
    TOOL_FEATURE(ShortcutCategory.CANVAS_TOOLS, "editor.tool.feature"),
    TOOL_PAINT_SELECT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_select"),
    TOOL_PAINT_GRADIENT(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_gradient"),
    TOOL_PAINT_BLUR(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_blur"),
    TOOL_PAINT_TRANSFORM(ShortcutCategory.CANVAS_TOOLS, "editor.tool.paint_transform"),
    // The numbered choices of the mode or tool in hand: Deform's levels, otherwise the variants the toolbar
    // lists under the tool (brush tips, glue and skeleton sub-tools, the vertex group kinds, paint shapes).
    PICK_OPTION_1(ShortcutCategory.CANVAS_TOOLS, "shortcut.pickOption"),
    PICK_OPTION_2(ShortcutCategory.CANVAS_TOOLS, "shortcut.pickOption"),
    PICK_OPTION_3(ShortcutCategory.CANVAS_TOOLS, "shortcut.pickOption"),
    PICK_OPTION_4(ShortcutCategory.CANVAS_TOOLS, "shortcut.pickOption"),
    PICK_OPTION_5(ShortcutCategory.CANVAS_TOOLS, "shortcut.pickOption"),

    // Canvas editing
    SELECT_ALL(ShortcutCategory.CANVAS_EDIT, "shortcut.selectAll"),
    INVERT_SELECTION(ShortcutCategory.CANVAS_EDIT, "help.shortcuts.invertSelection"),
    SELECTION_STYLE_BOX(ShortcutCategory.CANVAS_EDIT, "shortcut.selectionBox"),
    SELECTION_STYLE_LASSO(ShortcutCategory.CANVAS_EDIT, "shortcut.selectionLasso"),
    SELECT_LINKED(ShortcutCategory.CANVAS_EDIT, "shortcut.selectLinked"),
    MIRROR_KEYFORM(ShortcutCategory.CANVAS_EDIT, "shortcut.mirrorKeyform"),
    // Deform's keyform tools.
    KEYFORM_COPY(ShortcutCategory.CANVAS_EDIT, "shortcut.keyformCopy"),
    KEYFORM_PASTE(ShortcutCategory.CANVAS_EDIT, "shortcut.keyformPaste"),
    KEYFORM_RESET(ShortcutCategory.CANVAS_EDIT, "shortcut.keyformReset"),
    KEYFORM_INTERPOLATE(ShortcutCategory.CANVAS_EDIT, "shortcut.keyformInterpolate"),
    SOFT_SELECTION_TOGGLE(ShortcutCategory.CANVAS_EDIT, "shortcut.softSelection"),
    TEMPORARY_DEFORM(ShortcutCategory.CANVAS_EDIT, "shortcut.temporaryDeform"),
    CANCEL(ShortcutCategory.CANVAS_EDIT, "shortcut.cancel"),
    FINISH_PATH(ShortcutCategory.CANVAS_EDIT, "shortcut.finishPath"),
    DELETE_SELECTION(ShortcutCategory.CANVAS_EDIT, "editor.delete"),
    // One copy and one paste for the canvas; each mode decides what they carry (objects in Select mode).
    COPY(ShortcutCategory.CANVAS_EDIT, "shortcut.copy"),
    PASTE(ShortcutCategory.CANVAS_EDIT, "shortcut.paste"),
    LAYER_TO_FRONT(ShortcutCategory.CANVAS_EDIT, "layers.reorder.front"),
    LAYER_FORWARD(ShortcutCategory.CANVAS_EDIT, "layers.reorder.forward"),
    LAYER_BACKWARD(ShortcutCategory.CANVAS_EDIT, "layers.reorder.backward"),
    LAYER_TO_BACK(ShortcutCategory.CANVAS_EDIT, "layers.reorder.back"),
    LAYER_TOGGLE_REFERENCE(ShortcutCategory.CANVAS_EDIT, "layers.reference.toggle"),
    REFERENCE_TOGGLE_SHOWN(ShortcutCategory.CANVAS_EDIT, "shortcut.toggleReference"),
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
    PAINT_DESELECT(ShortcutCategory.CANVAS_EDIT, "shortcut.paintDeselect"),
    PAINT_FILL_SELECTION(ShortcutCategory.CANVAS_EDIT, "shortcut.paintFillSelection"),
    PAINT_RESET_COLORS(ShortcutCategory.CANVAS_EDIT, "shortcut.paintResetColors"),
    PAINT_LOCK_ALPHA(ShortcutCategory.CANVAS_EDIT, "shortcut.paintLockAlpha"),
    AXIS_CONSTRAIN_X(ShortcutCategory.CANVAS_EDIT, "shortcut.axisX"),
    AXIS_CONSTRAIN_Y(ShortcutCategory.CANVAS_EDIT, "shortcut.axisY"),
    FRAME_VIEW(ShortcutCategory.CANVAS_EDIT, "shortcut.frameView"),
    RESET_CAMERA(ShortcutCategory.CANVAS_EDIT, "shortcut.resetCamera"),
    // Canvas aids: grid, guides, onion skin and random pose.
    TOGGLE_GRID(ShortcutCategory.CANVAS_EDIT, "shortcut.toggleGrid"),
    TOGGLE_GUIDES(ShortcutCategory.CANVAS_EDIT, "shortcut.toggleGuides"),
    LOCK_GUIDES(ShortcutCategory.CANVAS_EDIT, "shortcut.lockGuides"),
    TOGGLE_SNAP(ShortcutCategory.CANVAS_EDIT, "shortcut.toggleSnap"),
    TOGGLE_ONION_SKIN(ShortcutCategory.CANVAS_EDIT, "shortcut.toggleOnionSkin"),
    RANDOM_POSE(ShortcutCategory.CANVAS_EDIT, "shortcut.randomPose"),
    RESTORE_RANDOM_POSE(ShortcutCategory.CANVAS_EDIT, "shortcut.restoreRandomPose"),

    // Canvas mouse gestures: a button starts them, the pointer's motion drives them.
    PAN_VIEW(ShortcutCategory.CANVAS_MOUSE, "shortcut.panView", ShortcutKind.DRAG),
    ZOOM_DRAG(ShortcutCategory.CANVAS_MOUSE, "shortcut.zoomDrag", ShortcutKind.DRAG),
    BRUSH_ADJUST_DRAG(ShortcutCategory.CANVAS_MOUSE, "shortcut.brushAdjustDrag", ShortcutKind.DRAG),
    BRUSH_ADJUST_ALT_DRAG(ShortcutCategory.CANVAS_MOUSE, "shortcut.brushAdjustAltDrag", ShortcutKind.DRAG),

    // Motion editor timeline
    MOTION_PLAY_PAUSE(ShortcutCategory.TIMELINE, "shortcut.motion.playPause"),
    MOTION_KEY_POSE(ShortcutCategory.TIMELINE, "shortcut.motion.keyPose"),
    MOTION_TOGGLE_AUTO_KEY(ShortcutCategory.TIMELINE, "shortcut.motion.autoKey"),
    MOTION_DELETE_KEYS(ShortcutCategory.TIMELINE, "shortcut.motion.deleteKeys"),
    MOTION_COPY_KEYS(ShortcutCategory.TIMELINE, "shortcut.motion.copyKeys"),
    MOTION_PASTE_KEYS(ShortcutCategory.TIMELINE, "shortcut.motion.pasteKeys"),
    MOTION_SELECT_ALL(ShortcutCategory.TIMELINE, "shortcut.motion.selectAll"),
    MOTION_FIT_VIEW(ShortcutCategory.TIMELINE, "shortcut.motion.fitView"),
    MOTION_GO_START(ShortcutCategory.TIMELINE, "shortcut.motion.goStart"),
    MOTION_GO_END(ShortcutCategory.TIMELINE, "shortcut.motion.goEnd"),
    MOTION_PREV_FRAME(ShortcutCategory.TIMELINE, "shortcut.motion.prevFrame"),
    MOTION_NEXT_FRAME(ShortcutCategory.TIMELINE, "shortcut.motion.nextFrame"),
    MOTION_ADD_MARKER(ShortcutCategory.TIMELINE, "shortcut.motion.addMarker"),
    MOTION_PREV_MARKER(ShortcutCategory.TIMELINE, "shortcut.motion.prevMarker"),
    MOTION_NEXT_MARKER(ShortcutCategory.TIMELINE, "shortcut.motion.nextMarker"),
    MOTION_TOGGLE_BOOKMARK(ShortcutCategory.TIMELINE, "shortcut.motion.bookmark"),
    MOTION_TOGGLE_SHY(ShortcutCategory.TIMELINE, "shortcut.motion.shy"),
    MOTION_HIDE_SHY(ShortcutCategory.TIMELINE, "shortcut.motion.hideShy"),
    MOTION_TOGGLE_FREEZE(ShortcutCategory.TIMELINE, "shortcut.motion.freeze"),
    MOTION_CLOSE_LOOP(ShortcutCategory.TIMELINE, "shortcut.motion.closeLoop"),
    ;

    val scope: ShortcutScope get() = category.scope

    /** 1-based tab index for the `JUMP_TAB_n` family, null for every other action. */
    val jumpIndex: Int?
        get() {
            val name = name
            if (!name.startsWith("JUMP_TAB_")) return null
            return name.removePrefix("JUMP_TAB_").toIntOrNull()
        }

    /** 1-based choice for the `PICK_OPTION_n` family, null for every other action. */
    val pickIndex: Int?
        get() = if (name.startsWith("PICK_OPTION_")) name.removePrefix("PICK_OPTION_").toIntOrNull() else null

    /** The number a numbered family's shared label is filled with, null for an action with a label of its own. */
    val labelIndex: Int? get() = jumpIndex ?: pickIndex

    companion object {
        /** `JUMP_TAB_1`..`JUMP_TAB_9`, used to lay the tab-jump rows out in order. */
        val jumpActions: List<ShortcutAction> = entries.filter { it.jumpIndex != null }

        /** `PICK_OPTION_1`..`PICK_OPTION_5`, in order: the n-th picks choice n. */
        val pickActions: List<ShortcutAction> = entries.filter { it.pickIndex != null }
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
    ShortcutAction.TOGGLE_FULLSCREEN to keys("F11"),

    ShortcutAction.TEMPORARY_SELECT to keys("Z"),
    ShortcutAction.QUICK_PREVIEW to keys("`"),
    ShortcutAction.TOGGLE_OBJECT_MODE to keys("Tab"),

    ShortcutAction.MODE_SELECT to keys("Alt+1", "Alt+NumPad1"),
    ShortcutAction.MODE_DEFORM to keys("Alt+2", "Alt+NumPad2"),
    ShortcutAction.MODE_EDIT to keys("Alt+3", "Alt+NumPad3"),
    ShortcutAction.MODE_SIMULATE to keys("Alt+4", "Alt+NumPad4"),
    ShortcutAction.MODE_SKELETON to keys("Alt+5", "Alt+NumPad5"),
    ShortcutAction.MODE_PAINT to keys("Alt+6", "Alt+NumPad6"),
    ShortcutAction.MODE_PREVIEW to keys("Alt+7", "Alt+NumPad7"),

    ShortcutAction.TOOL_SELECT to keys("V"),
    ShortcutAction.TOOL_TRANSFORM to keys("T"),
    ShortcutAction.TOOL_LASSO_SELECT to keys("L"),
    ShortcutAction.TOOL_BRUSH_SELECT to keys("W"),
    ShortcutAction.TOOL_BRUSH to keys("B"),
    ShortcutAction.TOOL_SMOOTH to keys("Shift+B"),
    ShortcutAction.TOOL_INFLATE to keys("I"),
    ShortcutAction.TOOL_SKELETON_POSE to keys("Shift+W"),
    ShortcutAction.TOOL_SKELETON_EDIT to keys("Alt+W"),
    ShortcutAction.TOOL_CREATE_WARP to keys("C"),
    ShortcutAction.TOOL_CREATE_ROTATION to keys("R"),
    ShortcutAction.TOOL_CREATE_DEFORM_PATH to keys("P", "D"),
    ShortcutAction.TOOL_GLUE to keys("G"),
    // M and K were the free letters near the mesh tools. Blender's knife is K, so the paint bucket -
    // a PAINT-mode tool whose key only matters there - moves aside to Shift+K rather than the knife
    // losing its conventional key.
    ShortcutAction.TOOL_SUBDIVIDE to keys("M"),
    ShortcutAction.TOOL_KNIFE to keys("K"),
    // Beside the subdivide brush's M: both add points to a mesh.
    ShortcutAction.TOOL_MESH_POINT to keys("Alt+M"),
    ShortcutAction.TOOL_WEIGHT_PAINT to keys("Shift+V"),
    ShortcutAction.TOOL_WEIGHT_GRADIENT to keys("Alt+V"),
    ShortcutAction.TOOL_PAINT_BRUSH to keys("Shift+P", "J"),
    ShortcutAction.TOOL_PAINT_PENCIL to keys("N"),
    ShortcutAction.TOOL_PAINT_ERASER to keys("E"),
    ShortcutAction.TOOL_PAINT_BUCKET to keys("Shift+K"),
    ShortcutAction.TOOL_PAINT_EYEDROPPER to keys("Alt+I"),
    ShortcutAction.TOOL_PAINT_LINE to keys("U"),
    ShortcutAction.TOOL_PAINT_RECT to keys("Shift+R"),
    ShortcutAction.TOOL_PAINT_ELLIPSE to keys("Shift+O"),
    ShortcutAction.TOOL_INTERIOR to keys("Shift+I"),
    ShortcutAction.TOOL_FEATURE to keys("Shift+Y"),
    // Photoshop's M, G and R belong to tools of the other modes here, so the paint tools take Alt chords instead -
    // the selection Alt+L, after the lasso it starts as.
    ShortcutAction.TOOL_PAINT_SELECT to keys("Alt+L"),
    ShortcutAction.TOOL_PAINT_GRADIENT to keys("Alt+G"),
    ShortcutAction.TOOL_PAINT_BLUR to keys("Alt+J"),
    // Photoshop's Ctrl+T opens a workspace tab here, so Free Transform takes the transform tool's T with Alt;
    // in Paint mode the T of the object transform tool picks it too.
    ShortcutAction.TOOL_PAINT_TRANSFORM to keys("Alt+T"),
    ShortcutAction.PICK_OPTION_1 to keys("1", "NumPad1"),
    ShortcutAction.PICK_OPTION_2 to keys("2", "NumPad2"),
    ShortcutAction.PICK_OPTION_3 to keys("3", "NumPad3"),
    ShortcutAction.PICK_OPTION_4 to keys("4", "NumPad4"),
    ShortcutAction.PICK_OPTION_5 to keys("5", "NumPad5"),

    ShortcutAction.SELECT_ALL to keys("Ctrl+A"),
    ShortcutAction.INVERT_SELECTION to keys("Ctrl+I", "Ctrl+Shift+I"),
    ShortcutAction.SELECTION_STYLE_BOX to keys("Q"),
    ShortcutAction.SELECTION_STYLE_LASSO to keys("Shift+Q"),
    ShortcutAction.SELECT_LINKED to keys("Shift+L"),
    ShortcutAction.MIRROR_KEYFORM to keys("Shift+M"),
    // Deform's keyform tools, on Ctrl+Alt beside the clipboard keys that copy objects.
    ShortcutAction.KEYFORM_COPY to keys("Ctrl+Alt+C"),
    ShortcutAction.KEYFORM_PASTE to keys("Ctrl+Alt+V"),
    ShortcutAction.KEYFORM_RESET to keys("Ctrl+Alt+X"),
    ShortcutAction.KEYFORM_INTERPOLATE to keys("Ctrl+Alt+I"),
    // Blender's proportional editing key.
    ShortcutAction.SOFT_SELECTION_TOGGLE to keys("O"),
    ShortcutAction.TEMPORARY_DEFORM to keys("Shift+T"),
    ShortcutAction.CANCEL to keys("Esc"),
    ShortcutAction.FINISH_PATH to keys("Enter", "NumPadEnter"),
    ShortcutAction.DELETE_SELECTION to keys("Del", "Backspace"),
    ShortcutAction.COPY to keys("Ctrl+C"),
    ShortcutAction.PASTE to keys("Ctrl+V"),
    // Photoshop's arrange keys: one step with Ctrl, all the way with Ctrl+Shift.
    ShortcutAction.LAYER_TO_FRONT to keys("Ctrl+Shift+]"),
    ShortcutAction.LAYER_FORWARD to keys("Ctrl+]"),
    ShortcutAction.LAYER_BACKWARD to keys("Ctrl+["),
    ShortcutAction.LAYER_TO_BACK to keys("Ctrl+Shift+["),
    ShortcutAction.LAYER_TOGGLE_REFERENCE to keys("Ctrl+Alt+R"),
    ShortcutAction.REFERENCE_TOGGLE_SHOWN to keys("Alt+R"),
    ShortcutAction.BRUSH_RADIUS_DOWN to keys("["),
    ShortcutAction.BRUSH_RADIUS_UP to keys("]"),
    ShortcutAction.BRUSH_HARDNESS_DOWN to keys("Shift+["),
    ShortcutAction.BRUSH_HARDNESS_UP to keys("Shift+]"),
    // Alt + wheel turns the brush, Shift making the step 45° instead of 15°.
    ShortcutAction.BRUSH_ROTATE_LEFT to keys("Alt+[", ",", "Alt+WheelUp", "Alt+Shift+WheelUp"),
    ShortcutAction.BRUSH_ROTATE_RIGHT to keys("Alt+]", ".", "Alt+WheelDown", "Alt+Shift+WheelDown"),
    ShortcutAction.BRUSH_SHAPE_CYCLE to keys("Alt+B"),
    // The paint shape tool's three faces, on the same "the tool's key, shifted" pairing.
    ShortcutAction.PAINT_SHAPE_CYCLE to keys("Shift+U"),
    // The paint tip's third parameter, on the same Shift pairing the Alt + drag gesture uses.
    ShortcutAction.PAINT_OPACITY_DOWN to keys("Alt+Shift+["),
    ShortcutAction.PAINT_OPACITY_UP to keys("Alt+Shift+]"),
    // Photoshop's selection and fill keys; D is the deform path's, so the default colours take Alt+D.
    ShortcutAction.PAINT_DESELECT to keys("Ctrl+D"),
    ShortcutAction.PAINT_FILL_SELECTION to keys("Alt+Backspace", "Alt+Del"),
    ShortcutAction.PAINT_RESET_COLORS to keys("Alt+D"),
    ShortcutAction.PAINT_LOCK_ALPHA to keys("/"),
    ShortcutAction.AXIS_CONSTRAIN_X to keys("X"),
    ShortcutAction.AXIS_CONSTRAIN_Y to keys("Y"),
    ShortcutAction.FRAME_VIEW to keys("F"),
    ShortcutAction.RESET_CAMERA to keys("Home", "0"),
    // Photoshop's grid and guide keys.
    ShortcutAction.TOGGLE_GRID to keys("Ctrl+'"),
    ShortcutAction.TOGGLE_GUIDES to keys("Ctrl+;"),
    ShortcutAction.LOCK_GUIDES to keys("Ctrl+Alt+;"),
    ShortcutAction.TOGGLE_SNAP to keys("Ctrl+Shift+;"),
    ShortcutAction.TOGGLE_ONION_SKIN to keys("Alt+O"),
    ShortcutAction.RANDOM_POSE to keys("Alt+P"),
    ShortcutAction.RESTORE_RANDOM_POSE to keys("Alt+Shift+P"),

    // Space + left drag also pans, but Space is a latch rather than a modifier and is not rebindable.
    ShortcutAction.PAN_VIEW to keys("MouseMiddle", "Shift+MouseMiddle"),
    ShortcutAction.ZOOM_DRAG to keys("Ctrl+MouseMiddle"),
    // Photoshop parity: Alt + right drag retunes the brush, Shift picking its third parameter.
    ShortcutAction.BRUSH_ADJUST_DRAG to keys("Alt+MouseRight"),
    ShortcutAction.BRUSH_ADJUST_ALT_DRAG to keys("Alt+Shift+MouseRight"),

    ShortcutAction.MOTION_PLAY_PAUSE to keys("Space"),
    ShortcutAction.MOTION_KEY_POSE to keys("K"),
    ShortcutAction.MOTION_TOGGLE_AUTO_KEY to keys("Alt+K", "Shift+K"),
    ShortcutAction.MOTION_DELETE_KEYS to keys("Del", "Backspace"),
    ShortcutAction.MOTION_COPY_KEYS to keys("Ctrl+C"),
    ShortcutAction.MOTION_PASTE_KEYS to keys("Ctrl+V"),
    ShortcutAction.MOTION_SELECT_ALL to keys("Ctrl+A"),
    ShortcutAction.MOTION_FIT_VIEW to keys("F"),
    ShortcutAction.MOTION_GO_START to keys("Home"),
    ShortcutAction.MOTION_GO_END to keys("End"),
    ShortcutAction.MOTION_PREV_FRAME to keys("Left"),
    ShortcutAction.MOTION_NEXT_FRAME to keys("Right"),
    ShortcutAction.MOTION_ADD_MARKER to keys("M"),
    ShortcutAction.MOTION_PREV_MARKER to keys("Ctrl+Left"),
    ShortcutAction.MOTION_NEXT_MARKER to keys("Ctrl+Right"),
    ShortcutAction.MOTION_TOGGLE_BOOKMARK to keys("B"),
    ShortcutAction.MOTION_TOGGLE_SHY to keys("Shift+H"),
    ShortcutAction.MOTION_HIDE_SHY to keys("H"),
    ShortcutAction.MOTION_TOGGLE_FREEZE to keys("Shift+F"),
    ShortcutAction.MOTION_CLOSE_LOOP to keys("Shift+L"),
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
    ShortcutAction.TOOL_SKELETON_POSE to keys("Shift+A"),
    ShortcutAction.TOOL_CREATE_WARP to keys("Shift+W"),
    ShortcutAction.TOOL_GLUE to keys("Shift+G"),
    ShortcutAction.TOOL_PAINT_ERASER to keys("Shift+E"),
    // Blender hands Alt + letter to clearing transforms and the like in every mode, and these chords would switch
    // into Paint from anywhere: the newer paint tools are left to the toolbar there.
    ShortcutAction.TOOL_PAINT_SELECT to emptyList(),
    ShortcutAction.TOOL_PAINT_GRADIENT to emptyList(),
    ShortcutAction.TOOL_PAINT_BLUR to emptyList(),
    ShortcutAction.TOOL_PAINT_TRANSFORM to emptyList(),
    // Numpad-dot = view selected.
    ShortcutAction.FRAME_VIEW to keys("NumPadDot"),
    // Workspace cycling.
    ShortcutAction.NEXT_TAB to keys("Ctrl+PageDown"),
    ShortcutAction.PREV_TAB to keys("Ctrl+PageUp"),
    // Insert keyframe.
    ShortcutAction.MOTION_KEY_POSE to keys("I", "K"),
    // Alt+letter is Blender's "clear" family (Alt+G, Alt+R, Alt+S...): the canvas aids stay unbound there.
    ShortcutAction.TOGGLE_ONION_SKIN to emptyList(),
    ShortcutAction.RANDOM_POSE to emptyList(),
    ShortcutAction.RESTORE_RANDOM_POSE to emptyList(),
)

/**
 * Live2D Cubism Editor overrides, taken from the official 5.x shortcut table.
 */
private val CUBISM_OVERRIDES: Map<ShortcutAction, List<KeyBinding>> = mapOf(
    // Arrow/select tool. `V` has no Cubism default, so it survives as a spare.
    ShortcutAction.TOOL_SELECT to keys("A", "V"),
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

    private val indexes: Map<ShortcutScope, Map<KeyBinding, ShortcutAction>> by lazy {
        ShortcutScope.entries.associateWith(::buildIndex)
    }

    fun bindingsFor(action: ShortcutAction): List<KeyBinding> = bindings[action].orEmpty()

    /** All bindings formatted for display; the first entry is the primary one. */
    fun labelsFor(action: ShortcutAction): List<String> = bindingsFor(action).map { it.format() }

    /** The single label menus show. Null when the action is unbound. */
    fun labelFor(action: ShortcutAction): String? = bindingsFor(action).firstOrNull()?.format()

    /** The action bound to this exact chord in this scope, or null. */
    fun match(event: KeyEvent, scope: ShortcutScope): ShortcutAction? = match(keyBindingOf(event), scope)

    /** The action bound to [binding] in [scope], or null; a null binding (an unnameable input) matches nothing. */
    fun match(binding: KeyBinding?, scope: ShortcutScope): ShortcutAction? =
        binding?.let { indexes.getValue(scope)[it] }

    /**
     * The command a wheel notch or a button click fires in [scope]. Gestures are left out: a press
     * that starts a drag is [gesture]'s.
     */
    fun mouseCommand(binding: KeyBinding?, scope: ShortcutScope): ShortcutAction? =
        match(binding, scope)?.takeIf { it.kind != ShortcutKind.DRAG }

    /** The drag gesture a press of this button with these modifiers starts in [scope], or null. */
    fun gesture(binding: KeyBinding?, scope: ShortcutScope = ShortcutScope.CANVAS): ShortcutAction? =
        match(binding, scope)?.takeIf { it.kind == ShortcutKind.DRAG }

    /** The other actions [binding] would collide with if [action] used it: same chord, overlapping scope. */
    fun conflictsFor(action: ShortcutAction, binding: KeyBinding): List<ShortcutAction> =
        conflictIndex()[binding].orEmpty().filter { it != action && it.scope.overlaps(action.scope) }

    /** Replaces one action's bindings; an empty list is a legitimate "unbound". */
    fun with(action: ShortcutAction, list: List<KeyBinding>): Keymap =
        Keymap(preset, bindings + (action to list))

    /** Every chord of every action mapped to the actions using it, for conflict display. */
    fun conflictIndex(): Map<KeyBinding, List<ShortcutAction>> = conflicts

    private val conflicts: Map<KeyBinding, List<ShortcutAction>> by lazy {
        bindings.entries
            .flatMap { (action, list) -> list.map { it to action } }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * Decides whether [binding] may be assigned to [action] at [index]. Refusing rather than warning
     * is deliberate: an accepted conflict produces a binding that is silently dead, which is the
     * exact failure this feature exists to remove.
     */
    fun validateCapture(action: ShortcutAction, index: Int, binding: KeyBinding): CaptureCheck {
        val mouse = binding.mouse
        when (action.kind) {
            // A drag needs a button to hold; keys and wheel notches have no motion to drive it.
            ShortcutKind.DRAG -> if (mouse == null || mouse.isWheel) return CaptureCheck.Unsupported(UNSUPPORTED_DRAG)
            else -> {
                // The left and right presses belong to the tools and the context menus, and only a
                // drag gesture may claim one (with a modifier).
                if (mouse == MouseInput.LEFT || mouse == MouseInput.RIGHT) return CaptureCheck.Unsupported(UNSUPPORTED_BUTTON)
                if (action.kind == ShortcutKind.HOLD && mouse?.isWheel == true) return CaptureCheck.Unsupported(UNSUPPORTED_HOLD)
            }
        }
        if (isReservedBinding(binding, action.scope)) return CaptureCheck.Reserved
        val own = bindingsFor(action)
        own.forEachIndexed { i, existing ->
            if (existing == binding && i != index) return CaptureCheck.DuplicateSelf
        }
        val owner = conflictsFor(action, binding).firstOrNull()
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
    /** The input is of a kind the action cannot use; [messageKey] says which. */
    data class Unsupported(val messageKey: String) : CaptureCheck
}

private const val UNSUPPORTED_DRAG = "dialog.settings.shortcuts.dragNeedsButton"
private const val UNSUPPORTED_BUTTON = "dialog.settings.shortcuts.buttonDragOnly"
private const val UNSUPPORTED_HOLD = "dialog.settings.shortcuts.holdNoWheel"

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
