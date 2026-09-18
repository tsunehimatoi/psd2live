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
        assertTrue("ANIMATION" in names)
        assertTrue("PHYSICS" in names)
        assertEquals(6, InspectorTab.entries.size)
    }

    @Test
    fun testTabLocalizationAcrossLanguages() {
        val originalLang = I18n.currentLanguage
        try {
            // Chinese
            I18n.setLanguage(AppLanguage.CHINESE, persist = false)
            assertEquals("工具细节", I18n.text("tab.toolDetails"))
            assertEquals("检视面板", I18n.text("tab.inspector"))
            assertEquals("动画", I18n.text("tab.animation"))
            assertEquals("物理", I18n.text("tab.physics"))
            assertEquals("开", I18n.text("common.on"))
            assertEquals("关", I18n.text("common.off"))
            assertEquals("动作列表", I18n.text("animation.motionsTitle"))
            assertEquals("设置面板", I18n.text("animation.settingsPanel"))
            assertEquals("输入设置", I18n.text("physics.tab.inputSettings"))
            assertEquals("摆锤设置", I18n.text("physics.pendulumSettings"))
            assertEquals("正片叠底色", I18n.text("inspector.multiplyColor"))
            assertEquals("屏幕色", I18n.text("inspector.screenColor"))
            assertEquals("转换的分裂数量", I18n.text("inspector.conversionDivision"))
            assertEquals("变形器转换(3.2方法)", I18n.text("inspector.quadTransform"))

            // English
            I18n.setLanguage(AppLanguage.ENGLISH, persist = false)
            assertEquals("Tool Details", I18n.text("tab.toolDetails"))
            assertEquals("Inspector", I18n.text("tab.inspector"))
            assertEquals("Animation", I18n.text("tab.animation"))
            assertEquals("Physics", I18n.text("tab.physics"))
            assertEquals("ON", I18n.text("common.on"))
            assertEquals("OFF", I18n.text("common.off"))
            assertEquals("Motions", I18n.text("animation.motionsTitle"))
            assertEquals("Settings Panel", I18n.text("animation.settingsPanel"))
            assertEquals("Input Settings", I18n.text("physics.tab.inputSettings"))
            assertEquals("Pendulum Settings", I18n.text("physics.pendulumSettings"))
            assertEquals("Multiply Color", I18n.text("inspector.multiplyColor"))
            assertEquals("Screen Color", I18n.text("inspector.screenColor"))

            // Japanese
            I18n.setLanguage(AppLanguage.JAPANESE, persist = false)
            assertEquals("ツール詳細", I18n.text("tab.toolDetails"))
            assertEquals("インスペクタ", I18n.text("tab.inspector"))
            assertEquals("アニメーション", I18n.text("tab.animation"))
            assertEquals("物理演算", I18n.text("tab.physics"))
            assertEquals("オン", I18n.text("common.on"))
            assertEquals("オフ", I18n.text("common.off"))
            assertEquals("モーション", I18n.text("animation.motionsTitle"))
            assertEquals("設定パネル", I18n.text("animation.settingsPanel"))
            assertEquals("入力設定", I18n.text("physics.tab.inputSettings"))
            assertEquals("振り子設定", I18n.text("physics.pendulumSettings"))
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

        val originalAnimation = PSD2LiveState(activeInspectorTab = InspectorTab.ANIMATION)
        val jsonAnimation = WorkspaceStateCodec.encode(originalAnimation)
        val restoredAnimation = WorkspaceStateCodec.decode(jsonAnimation, PSD2LiveState())
        assertEquals(InspectorTab.ANIMATION, restoredAnimation.activeInspectorTab)

        val originalPhysics = PSD2LiveState(activeInspectorTab = InspectorTab.PHYSICS)
        val jsonPhysics = WorkspaceStateCodec.encode(originalPhysics)
        val restoredPhysics = WorkspaceStateCodec.decode(jsonPhysics, PSD2LiveState())
        assertEquals(InspectorTab.PHYSICS, restoredPhysics.activeInspectorTab)
    }

    @Test
    fun testPhysicsEditUpsertAndRemove() {
        val vm = io.github.psd2live.ui.state.PSD2LiveViewModel()
        try {
            val custom = io.github.psd2live.core.RigPhysicsEdit(
                id = "PhysicsTestGroup",
                name = "Test Group",
                inputParameter = "ParamAngleX",
                outputParameter = "ParamBodyAngleY",
                length = 12f,
                mobility = 0.85f,
                delay = 0.75f,
                acceleration = 1.2f,
                outputScale = 1.5f,
            )
            vm.upsertPhysicsEdit(custom)
            assertTrue(vm.state.value.generatePhysics)
            val edits = vm.state.value.rigEdits.physicsEdits
            assertEquals(1, edits.size)
            assertEquals("PhysicsTestGroup", edits[0].id)
            assertEquals(12f, edits[0].length)

            // Update same edit
            val updated = custom.copy(length = 18f)
            vm.upsertPhysicsEdit(updated)
            assertEquals(1, vm.state.value.rigEdits.physicsEdits.size)
            assertEquals(18f, vm.state.value.rigEdits.physicsEdits[0].length)

            // Remove edit
            vm.removePhysicsEdit("PhysicsTestGroup")
            assertEquals(0, vm.state.value.rigEdits.physicsEdits.size)
        } finally {
            vm.close()
        }
    }

    @Test
    fun testCubismPhysicsSimulatorDynamics() {
        val sim = io.github.psd2live.core.CubismPhysicsSimulator()
        sim.reset(strandCount = 2, totalLength = 15f, mobility = 0.95f, delay = 0.8f, acceleration = 1.5f)
        assertEquals(0.0f, sim.getOutputAngleDegrees(1), 0.01f)

        // Displace particle
        sim.setParticleAngle(1, 25f)
        assertEquals(25.0f, sim.getOutputAngleDegrees(1), 0.5f)

        // Simulate 60 steps (1 second)
        var sawOscillation = false
        for (step in 0 until 60) {
            sim.update(totalTranslationX = 0f, totalTranslationY = 0f, totalAngleDegrees = 0f, dt = 0.0166f)
            val ang = sim.getOutputAngleDegrees(1)
            if (ang < 0f) sawOscillation = true
        }
        assertTrue(sawOscillation, "Pendulum should oscillate through neutral position")

        // Simulate 200 more steps to verify damping
        for (step in 0 until 200) {
            sim.update(totalTranslationX = 0f, totalTranslationY = 0f, totalAngleDegrees = 0f, dt = 0.0166f)
        }
        val finalAngle = kotlin.math.abs(sim.getOutputAngleDegrees(1))
        assertTrue(finalAngle < 5f, "Pendulum should converge towards 0 under damping, got $finalAngle")
    }

    @Test
    fun testPendulumCenterAlignmentAndRootRelativeIntegrity() {
        val sim = io.github.psd2live.core.CubismPhysicsSimulator()
        sim.reset(strandCount = 3, totalLength = 16f, mobility = 0.90f, delay = 0.8f, acceleration = 1.5f)

        // 1. Initial rest state: horizontal deflection relative to root must be exactly 0
        val root = sim.particles[0]
        val bob1 = sim.particles[1]
        val bob2 = sim.particles[2]
        assertEquals(0f, bob1.x - root.x, 0.001f, "Bob 1 must align with root on vertical center line")
        assertEquals(0f, bob2.x - root.x, 0.001f, "Bob 2 must align with root on vertical center line")
        assertEquals(-8f, bob1.y - root.y, 0.001f, "Bob 1 must hang at -8cm from root")
        assertEquals(-16f, bob2.y - root.y, 0.001f, "Bob 2 must hang at -16cm from root")

        // 2. Translate root to x = 10f, simulate to steady state
        for (i in 0 until 120) {
            sim.update(totalTranslationX = 10f, totalTranslationY = 0f, totalAngleDegrees = 0f, dt = 0.0166f)
        }
        val steadyRelX1 = sim.particles[1].x - sim.particles[0].x
        val steadyRelX2 = sim.particles[2].x - sim.particles[0].x
        assertEquals(0f, steadyRelX1, 0.05f, "Bob 1 relative to root must return to 0 at steady state")
        assertEquals(0f, steadyRelX2, 0.05f, "Bob 2 relative to root must return to 0 at steady state")
    }
}
