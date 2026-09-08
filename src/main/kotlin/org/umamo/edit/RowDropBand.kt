package org.umamo.edit

/**
 * Where a row drop lands relative to the target row: before it, into it (nesting inside a container
 * row, when the panel's rules allow it), or after it.  Shared by every row-dragging panel (outliner,
 * parameters) so their drop rules and indicators speak one vocabulary.
 *
 * 行ドロップが対象行のどこに落ちるか（前・中・後）。行ドラッグを持つ各パネルで共有される。
 */
enum class RowDropBand { Before, Into, After }