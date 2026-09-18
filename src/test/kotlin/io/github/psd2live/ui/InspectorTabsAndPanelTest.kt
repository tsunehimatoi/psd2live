package io.github.psd2live.ui

import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.project.WorkspaceStateCodec
import io.github.psd2live.ui.state.InspectorTab
import io.github.psd2live.ui.state.PSD2LiveState
import org.umamo.runtime.model.ColorRgb
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectorTabsAndPanelTest {

    @Test
    fun testInspectorTabEnumValues() {
        val names = InspectorTab.entries.map { it.name }
        assertTrue("LAYERS" in names)
        assertTrue("PARAMETERS" in names)
        assertTrue("TOOL_DETAILS" in names)
        assertTrue("INSPECTOR" in names)
        assertEquals(4, InspectorTab.entries.size)
    }

    @Test
    fun testTabLocalizationAcrossLanguages() {
        val originalLang = I18n.currentLanguage
        try {
            // Chinese
            I18n.setLanguage(AppLanguage.CHINESE, persist = false)
            assertEquals("工具细节", I18n.text("tab.toolDetails"))
            assertEquals("检视面板", I18n.text("tab.inspector"))
            assertEquals("正片叠底色", I18n.text("inspector.multiplyColor"))
            assertEquals("屏幕色", I18n.text("inspector.screenColor"))
            assertEquals("转换的分裂数量", I18n.text("inspector.conversionDivision"))
            assertEquals("变形器转换(3.2方法)", I18n.text("inspector.quadTransform"))

            // English
            I18n.setLanguage(AppLanguage.ENGLISH, persist = false)
            assertEquals("Tool Details", I18n.text("tab.toolDetails"))
            assertEquals("Inspector", I18n.text("tab.inspector"))
            assertEquals("Multiply Color", I18n.text("inspector.multiplyColor"))
            assertEquals("Screen Color", I18n.text("inspector.screenColor"))

            // Japanese
            I18n.setLanguage(AppLanguage.JAPANESE, persist = false)
            assertEquals("ツール詳細", I18n.text("tab.toolDetails"))
            assertEquals("インスペクタ", I18n.text("tab.inspector"))
            assertEquals("乗算色", I18n.text("inspector.multiplyColor"))
            assertEquals("スクリーン色", I18n.text("inspector.screenColor"))
        } finally {
            I18n.setLanguage(originalLang, persist = false)
        }
    }

    @Test
    fun testColorHexConversions() {
        fun colorRgbToHex(rgb: ColorRgb): String {
            val r = (rgb.red.coerceIn(0f, 1f) * 255f).roundToInt()
            val g = (rgb.green.coerceIn(0f, 1f) * 255f).roundToInt()
            val b = (rgb.blue.coerceIn(0f, 1f) * 255f).roundToInt()
            return String.format("#%02X%02X%02X", r, g, b)
        }

        fun parseHexToColorRgb(hex: String): ColorRgb? {
            val clean = hex.trim().removePrefix("#")
            if (clean.length != 6) return null
            val rgb = clean.toIntOrNull(16) ?: return null
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            return ColorRgb(r, g, b)
        }

        // Multiply Identity (#FFFFFF)
        assertEquals("#FFFFFF", colorRgbToHex(ColorRgb.MultiplyIdentity))
        val parsedWhite = parseHexToColorRgb("#FFFFFF")
        assertEquals(1f, parsedWhite?.red)
        assertEquals(1f, parsedWhite?.green)
        assertEquals(1f, parsedWhite?.blue)

        // Screen Identity (#000000)
        assertEquals("#000000", colorRgbToHex(ColorRgb.ScreenIdentity))
        val parsedBlack = parseHexToColorRgb("#000000")
        assertEquals(0f, parsedBlack?.red)
        assertEquals(0f, parsedBlack?.green)
        assertEquals(0f, parsedBlack?.blue)

        // Custom Color (#123456)
        val parsedCustom = parseHexToColorRgb("#123456")
        assertTrue(parsedCustom != null)
        assertEquals("#123456", colorRgbToHex(parsedCustom))
    }

    @Test
    fun testWorkspaceStateCodecPreservesNewTabs() {
        val originalToolDetails = PSD2LiveState(activeInspectorTab = InspectorTab.TOOL_DETAILS)
        val jsonToolDetails = WorkspaceStateCodec.encode(originalToolDetails)
        val restoredToolDetails = WorkspaceStateCodec.decode(jsonToolDetails, PSD2LiveState())
        assertEquals(InspectorTab.TOOL_DETAILS, restoredToolDetails.activeInspectorTab)

        val originalInspector = PSD2LiveState(activeInspectorTab = InspectorTab.INSPECTOR)
        val jsonInspector = WorkspaceStateCodec.encode(originalInspector)
        val restoredInspector = WorkspaceStateCodec.decode(jsonInspector, PSD2LiveState())
        assertEquals(InspectorTab.INSPECTOR, restoredInspector.activeInspectorTab)
    }
}
