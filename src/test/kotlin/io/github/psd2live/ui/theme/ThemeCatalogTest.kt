package io.github.psd2live.ui.theme

import androidx.compose.ui.graphics.Color
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeCatalogTest {
	@Test
	fun builtInIdsAreUniqueAndDarkLightKeepTheirPalettes() {
		assertEquals(ThemeCatalog.builtIns.size, ThemeCatalog.builtIns.map { it.id }.toSet().size)
		assertEquals(ToolColors.Dark, ThemeCatalog.builtIn(ThemeCatalog.DARK_ID).colors)
		assertEquals(ToolColors.Light, ThemeCatalog.builtIn(ThemeCatalog.LIGHT_ID).colors)
	}

	@Test
	fun unknownIdFallsBackToDark() {
		assertEquals(ToolColors.Dark, ThemeCatalog.resolve("custom-gone", emptyList()))
	}

	@Test
	fun accentOverrideCascadesButExplicitDerivedOverrideWins() {
		val accent = Color(0xFFE0648A)
		val theme = CustomTheme("c", "t", ThemeCatalog.DARK_ID).withColor(ColorToken.ACCENT, accent)
		val colors = theme.resolve()
		assertEquals(accent, colors.accent)
		assertNotEquals(ToolColors.Dark.selection, colors.selection)
		assertNotEquals(ToolColors.Dark.accentHover, colors.accentHover)
		assertEquals(ToolColors.Dark.panelBackground, colors.panelBackground)

		val pinned = Color(0xFF123456)
		val withSelection = theme.withColor(ColorToken.SELECTION, pinned).resolve()
		assertEquals(pinned, withSelection.selection)
	}

	@Test
	fun lightPanelOnDarkBaseSwitchesToLightShading() {
		val colors = CustomTheme("c", "t", ThemeCatalog.DARK_ID)
			.withColor(ColorToken.TEXT_PRIMARY, Color(0xFF202020))
			.withColor(ColorToken.PANEL_BACKGROUND, Color(0xFFF4F4F4))
			.resolve()
		assertFalse(colors.isDark)
	}

	@Test
	fun settingABaseValueDropsTheOverride() {
		val theme = CustomTheme("c", "t", ThemeCatalog.DARK_ID)
			.withColor(ColorToken.WARNING, Color(0xFF00FF00))
			.withColor(ColorToken.WARNING, ToolColors.Dark.warning)
		assertTrue(theme.overrides.isEmpty())
	}

	@Test
	fun codecRoundTripsNameBaseAndTranslucentColours() {
		val theme = CustomTheme(
			id = "custom-1",
			name = "Mine",
			baseId = "nord",
			overrides = mapOf(ColorToken.ACCENT to Color(0xFFFF8800), ColorToken.SCRIM to Color(0x66000000)),
		)
		assertEquals(theme, ThemeCodec.decode(ThemeCodec.encode(theme), "custom-1"))
	}

	@Test
	fun codecSkipsUnknownKeysAndBadValuesAndRejectsOtherText() {
		val decoded = ThemeCodec.decode(
			"psd2live-theme 1\nname=X\nbase=nope\nfuture=#FFFFFF\naccent=#GG0000\nerror=#FF0000\n",
			"id",
		)!!
		assertEquals(ThemeCatalog.DARK_ID, decoded.baseId)
		assertEquals(mapOf(ColorToken.ERROR to Color(0xFFFF0000)), decoded.overrides)
		assertNull(ThemeCodec.decode("hello", "id"))
	}

	@Test
	fun everyThemeStringExistsInAllBundles() {
		val keys = ThemeCatalog.builtIns.map { it.nameKey } +
			ColorTokenGroup.entries.map { it.labelKey } +
			ColorToken.entries.map { it.labelKey }
		for (bundle in listOf("Messages", "Messages_zh_CN", "Messages_ja", "Messages_ko")) {
			val properties = Properties()
			javaClass.getResourceAsStream("/i18n/$bundle.properties")!!.reader(Charsets.UTF_8).use(properties::load)
			for (key in keys) assertTrue(properties.containsKey(key), "$bundle is missing $key")
		}
	}
}
