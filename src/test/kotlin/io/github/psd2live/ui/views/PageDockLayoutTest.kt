package io.github.psd2live.ui.views

import io.github.psd2live.ui.state.AppPage
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD_GUIDE_CONTENT
import io.github.psd2live.ui.state.PageArrangement
import io.github.psd2live.ui.state.START_DETAILS
import io.github.psd2live.ui.state.START_MAIN
import io.github.psd2live.ui.state.pageOfModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageDockLayoutTest {
	@Test fun eachPageStartsWithEveryPanelOnce() {
		AppPage.entries.forEach { page ->
			val modules = pageDockLayout(page).allModules()
			assertEquals(page.modules.sorted(), modules.sorted(), "$page")
			assertTrue(modules.all { pageOfModule(it) == page })
		}
	}

	@Test fun aSavedLayoutComesBackAsArranged() {
		val moved = dockModule(pageDockLayout(AppPage.START), START_DETAILS, null, DockSide.BOTTOM)
		val root = pageDockRoot(AppPage.START, PageArrangement(dockJson.encodeToString(DockNode.serializer(), moved)))
		assertEquals(moved, root)
	}

	@Test fun aLayoutNamingOtherPanelsFallsBackToTheDefault() {
		val foreign = dockModule(pageDockLayout(AppPage.START), PSD_GUIDE_CONTENT, null, DockSide.RIGHT)
		val root = pageDockRoot(AppPage.START, PageArrangement(dockJson.encodeToString(DockNode.serializer(), foreign)))
		assertEquals(AppPage.START.modules.sorted(), root.allModules().sorted())
		assertEquals(pageDockLayout(AppPage.START).allModules(), root.allModules())
		assertEquals(pageDockLayout(AppPage.START).allModules(), pageDockRoot(AppPage.START, PageArrangement("not json")).allModules())
	}

	@Test fun aPanelThePageGainedJoinsASavedLayout() {
		val without = pageDockLayout(AppPage.START).remove(START_DETAILS)!!
		val root = pageDockRoot(AppPage.START, PageArrangement(dockJson.encodeToString(DockNode.serializer(), without)))
		assertEquals(AppPage.START.modules.sorted(), root.allModules().sorted())
	}

	@Test fun legacySinglePanelPsdGuideLayoutUpgradesToSplitModules() {
		val legacy = DockNode(id = "leaf:legacy", modules = listOf(io.github.psd2live.ui.state.PSD_GUIDE_CONTENT))
		val root = pageDockRoot(AppPage.PSD_GUIDE, PageArrangement(dockJson.encodeToString(DockNode.serializer(), legacy)))
		assertTrue(root.horizontal)
		assertEquals(listOf(io.github.psd2live.ui.state.PSD_GUIDE_CONTENT), root.first?.modules)
		assertEquals(listOf(io.github.psd2live.ui.state.PSD_GUIDE_CONTROLS), root.second?.modules)
	}

	@Test fun theArrangementSurvivesThePreferences() {
		val arrangement = PageArrangement(dockJson.encodeToString(DockNode.serializer(), pageDockLayout(AppPage.START)), setOf(START_MAIN, "unknown"))
		AppSettings.setPageArrangement(AppPage.START, arrangement)
		assertEquals(arrangement.copy(hiddenModules = setOf(START_MAIN)), AppSettings.pageArrangement(AppPage.START))
		AppSettings.setPageArrangement(AppPage.START, PageArrangement())
		assertEquals(PageArrangement(), AppSettings.pageArrangement(AppPage.START))
	}
}
