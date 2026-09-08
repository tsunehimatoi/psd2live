package org.umamo.edit

/**
 * The editor's interaction mode, Blender-style. Object mode selects whole entities (parts, drawables,
 * deformers) and shows the rendered puppet; Edit mode dives into the active entity's interior (mesh,
 * warp lattice, or rotation pivot). The mode scopes hit-testing and tool behaviour. v1 implements
 * Object fully and leaves Edit as a routed stub.
 *
 * エディタの操作モード（Blender 流）。オブジェクトモードは要素全体を選択し、編集モードは内部を編集する。
 */
enum class EditorMode {
	/** Whole-entity selection and posing — the default. */
	Object,

	/** Interior editing of the active entity (stubbed in v1). */
	Edit,
}