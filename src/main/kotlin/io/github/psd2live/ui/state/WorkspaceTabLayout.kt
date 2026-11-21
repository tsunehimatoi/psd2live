package io.github.psd2live.ui.state

/**
 * Where the workspace tab strip sits around the dock.
 */
enum class WorkspaceTabLayout(val labelKey: String, val descriptionKey: String) {
	/** Collapsible vertical rail along the left edge, matching the start hub's navigation rail. */
	LEFT("settings.tabs.layout.left", "settings.tabs.layout.left.desc"),
	/** Horizontal strip across the top of the workspace. */
	TOP("settings.tabs.layout.top", "settings.tabs.layout.top.desc"),
}
