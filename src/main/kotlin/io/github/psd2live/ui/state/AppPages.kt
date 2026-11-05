package io.github.psd2live.ui.state

/**
 * Pages of the app rather than of a project: each is a tab at the head of the workspace strip that covers the dock
 * with its own dock of [modules]. Their panels move, tab, float and hide like the editor's, and their arrangement is
 * kept in the preferences, never in a project. Every page has the same shape: a contents list, the topic it picks,
 * and on the start page the details of what the topic's action will do.
 */
internal enum class AppPage(val modules: List<String>) {
	START(listOf(START_NAV, START_MAIN, START_DETAILS)),
	HELP(listOf(HELP_NAV, HELP_CONTENT)),
	SETTINGS(listOf(SETTINGS_NAV, SETTINGS_CONTENT)),
}

internal const val START_NAV = "start.nav"
internal const val START_MAIN = "start.main"
internal const val START_DETAILS = "start.details"

internal const val HELP_NAV = "help.nav"
internal const val HELP_CONTENT = "help.content"

internal const val SETTINGS_NAV = "settings.nav"
internal const val SETTINGS_CONTENT = "settings.content"

/**
 * The help page's topics, in its contents' order. The manual and the tutorials are trees of pages ([Manual]); the
 * page shown is the view model's helpPage.
 */
enum class HelpTab(internal val labelKey: String) {
	MANUAL("help.tab.manual"),
	TUTORIALS("help.tab.tutorials"),
	EXAMPLES("help.tab.examples"),
	COMMUNITY_LINKS("help.tab.links"),
	LICENSES("help.tab.licenses"),
	ABOUT("help.tab.about"),
}

/** The settings page's topics, in its contents' order: what people come to change first. */
internal enum class SettingsTopic(val labelKey: String) {
	SCALE("dialog.settings.category.scale"),
	THEME("dialog.settings.category.theme"),
	LANGUAGE("dialog.settings.category.language"),
	CANVAS("settings.canvas.title"),
	GRAPHICS("dialog.settings.category.graphics"),
	PROMPTS("dialog.settings.category.prompts"),
	SHORTCUTS("dialog.settings.category.shortcuts"),
	FILES("settings.files.title"),
	MCP("dialog.agent.title"),
    PLUGINS("plugins.title"),
}

/** The page whose dock holds [module], or null for an editor module. */
internal fun pageOfModule(module: String): AppPage? = AppPage.entries.firstOrNull { module in it.modules }

/** A page's arrangement as last left: its dock tree as JSON (null for the default) and the panels closed in it. */
internal data class PageArrangement(val layoutJson: String? = null, val hiddenModules: Set<String> = emptySet())
