package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.GridIcon
import io.github.psd2live.ui.components.HelpPanel
import io.github.psd2live.ui.components.IconInfo
import io.github.psd2live.ui.components.HelpNav
import io.github.psd2live.ui.components.SettingsNav
import io.github.psd2live.ui.components.SettingsPanel
import io.github.psd2live.ui.components.resetAllSettings
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.TutorialPath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the app's pages do outside themselves: files to pick, tutorials to start, folders to reveal. */
@Stable
internal class PageActions(
	val onOpenTutorialCatalog: (() -> Unit)?,
	val onStartTutorial: ((TutorialId) -> Unit)?,
	val onStartTutorialPath: ((TutorialId, TutorialPath) -> Unit)?,
	val onOpenProject: (() -> Unit)?,
	val onOpenPsd: (() -> Unit)?,
	val onOpenCmo3: (() -> Unit)?,
	val onReplaceCmo3: (() -> Unit)?,
	val onRevealFile: (String) -> Unit,
	/** A file is being dragged over the window; the start page shows where it will land. */
	val filesDragging: Boolean,
	val start: StartPageModel,
)

/** The content of one panel of an app page. */
@Composable
internal fun PageModuleContent(id: String, state: PSD2LiveState, viewModel: PSD2LiveViewModel, actions: PageActions) {
	when (id) {
		START_NAV -> StartNavPanel(state, viewModel, actions)
		START_MAIN -> StartMainPanel(state, viewModel, actions)
		START_DETAILS -> StartDetailsPanel(state, viewModel, actions)
		PSD_GUIDE_CONTENT -> PsdGuideContentPanel(viewModel)
		PSD_GUIDE_CONTROLS -> PsdGuideControlsPanel(viewModel)
		PSD_GUIDE_ACTIONS -> PsdGuideActionsPanel(viewModel)
		HELP_NAV -> HelpNav(viewModel.helpTab, viewModel.helpPage, viewModel::setHelpTab, viewModel::showHelpPage)
		HELP_CONTENT -> HelpPanel(viewModel.helpTab, viewModel.helpPage, state.keymap, onOpenPage = viewModel::showHelpPage,
			onStartInteractiveTutorial = actions.onStartTutorialPath, operationDocs = viewModel.operationDocs)
		SETTINGS_NAV -> SettingsNav(viewModel.settingsTopic, viewModel::setSettingsTopic, onResetAll = { resetAllSettings(viewModel) })
		SETTINGS_CONTENT -> SettingsPanel(viewModel.settingsTopic, state, viewModel)
	}
}

/** A dock panel's title on an app page: a topic panel is named after the topic it shows, a contents panel after its page. */
internal fun pageModuleTitle(id: String, viewModel: PSD2LiveViewModel): String = when (id) {
	START_NAV -> pageTitle(AppPage.START)
	START_MAIN -> startSectionTitle(viewModel.startSection)
	START_DETAILS -> startDetailsTitle(viewModel.startSection)
	PSD_GUIDE_CONTENT -> pageTitle(AppPage.PSD_GUIDE)
	PSD_GUIDE_CONTROLS -> psdGuideControlsTitle(viewModel)
	PSD_GUIDE_ACTIONS -> tr("dock.module.psdGuide.actions")
	HELP_NAV -> pageTitle(AppPage.HELP)
	HELP_CONTENT -> helpContentTitle(viewModel)
	SETTINGS_NAV -> pageTitle(AppPage.SETTINGS)
	SETTINGS_CONTENT -> tr(viewModel.settingsTopic.labelKey)
	else -> moduleTitle(id)
}

/** The help content panel's title: the page shown in the manual and the tutorials, the topic otherwise. */
private fun helpContentTitle(viewModel: PSD2LiveViewModel): String =
	if (viewModel.helpTab == HelpTab.MANUAL || viewModel.helpTab == HelpTab.TUTORIALS)
		Manual.texts(io.github.psd2live.i18n.I18n.currentLanguage)[viewModel.helpPage]?.title ?: tr(viewModel.helpTab.labelKey)
	else tr(viewModel.helpTab.labelKey)

/** A page's name, for its tab. */
internal fun pageTitle(page: AppPage): String = tr(when (page) {
	AppPage.START -> "start.page.tab"
	AppPage.PSD_GUIDE -> "psdGuide.title"
	AppPage.HELP -> "page.help"
	AppPage.SETTINGS -> "page.settings"
})

/** A page's icon, for its tab and the links to it. */
@Composable
internal fun PageIcon(page: AppPage, tint: Color, modifier: Modifier = Modifier.size(12.dp)) {
	when (page) {
		AppPage.START, AppPage.PSD_GUIDE -> StartIcon(modifier, tint)
		AppPage.HELP -> IconInfo(modifier = modifier, tint = tint)
		AppPage.SETTINGS -> GridIcon(modifier, tint) {
			// A cog as one closed outline: six broad teeth on a round body, the axle hole open. Broad teeth and
			// rounded joins keep it apart from the sun's eight thin rays beside it in the title bar.
			val cog = Path()
			for (i in 0 until 6) {
				val a = i * PI / 3 - PI / 2
				fun at(r: Float, offset: Double) = p(9f + r * cos(a + offset).toFloat(), 9f + r * sin(a + offset).toFloat())
				val base = at(5.9f, -0.36)
				if (i == 0) cog.moveTo(base.x, base.y) else cog.lineTo(base.x, base.y)
				at(7.6f, -0.2).let { cog.lineTo(it.x, it.y) }
				at(7.6f, 0.2).let { cog.lineTo(it.x, it.y) }
				at(5.9f, 0.36).let { cog.lineTo(it.x, it.y) }
				// The body between teeth follows the circle rather than cutting a chord across it.
				val r = 5.9f * s
				val c = p(9f, 9f)
				cog.arcTo(Rect(c, r), Math.toDegrees(a + 0.36).toFloat(), Math.toDegrees(PI / 3 - 0.72).toFloat(), false)
			}
			cog.close()
			shape(cog)
			ring(9f, 9f, 2.1f)
		}
	}
}
