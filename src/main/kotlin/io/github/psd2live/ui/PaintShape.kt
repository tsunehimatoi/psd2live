package io.github.psd2live.ui

import io.github.psd2live.ui.state.ShortcutAction

/**
 * The shapes the paint shape tool draws.
 *
 * One tool with three faces, the way the deform brushes carry their own three: the row in the toolbar
 * stays where it is while the artist moves between them, and the gesture is the same for all of them -
 * press a corner, drag the other one, land it. What changes is only the outline drawn between them.
 *
 * The chord is the shape's own, so the keys that used to be three tools are now three faces of one.
 */
enum class PaintShape(val action: ShortcutAction, val labelKey: String) {
    LINE(ShortcutAction.TOOL_PAINT_LINE, "editor.tool.paint_line"),
    RECTANGLE(ShortcutAction.TOOL_PAINT_RECT, "editor.tool.paint_rect"),
    ELLIPSE(ShortcutAction.TOOL_PAINT_ELLIPSE, "editor.tool.paint_ellipse"),
    ;

    /** Only the box shapes have an inside to fill; a line is its own outline. */
    val canFill: Boolean get() = this != LINE

    /** The name this shape's strokes take in the paint history. */
    val strokeLabelKey: String
        get() = when (this) {
            LINE -> "editor.paint.strokeLine"
            RECTANGLE -> "editor.paint.strokeRect"
            ELLIPSE -> "editor.paint.strokeEllipse"
        }
}
