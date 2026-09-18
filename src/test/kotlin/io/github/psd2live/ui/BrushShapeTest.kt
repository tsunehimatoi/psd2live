package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.KeymapPreset
import io.github.psd2live.ui.state.ShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrushShapeTest {

    @Test
    fun testCircleBrushWeightAndContainment() {
        val center = Offset(100f, 100f)
        val radius = 50f
        val hardness = 0.5f

        // Point at center should have max weight (1.0)
        val centerWeight = computeBrushWeight(center, center, center, radius, hardness, BrushShape.CIRCLE)
        assertEquals(1.0f, centerWeight, 0.01f)
        assertTrue(isPointInBrush(center, center, center, radius, BrushShape.CIRCLE))

        // Point inside core radius (r * hardness = 25f)
        val insideCore = Offset(100f, 120f)
        val coreWeight = computeBrushWeight(insideCore, center, center, radius, hardness, BrushShape.CIRCLE)
        assertEquals(1.0f, coreWeight, 0.01f)

        // Point outside radius
        val outside = Offset(100f, 160f)
        val outsideWeight = computeBrushWeight(outside, center, center, radius, hardness, BrushShape.CIRCLE)
        assertEquals(0.0f, outsideWeight, 0.001f)
        assertFalse(isPointInBrush(outside, center, center, radius, BrushShape.CIRCLE))
    }

    @Test
    fun testLineBrushRotation() {
        val center = Offset(200f, 200f)
        val radius = 25f
        val hardness = 0.5f

        // Horizontal line (angle = 0 deg)
        // Point along line (e.g. x = 300, y = 200) has distance 0, so full weight
        val onHLine = Offset(300f, 200f)
        val hWeight = computeBrushWeight(onHLine, center, center, radius, hardness, BrushShape.LINE, angleDeg = 0f)
        assertEquals(1.0f, hWeight, 0.01f)
        assertTrue(isPointInBrush(onHLine, center, center, radius, BrushShape.LINE, angleDeg = 0f))

        // Point perpendicular beyond radius (x = 200, y = 250, distance = 50 > 25) should be outside
        val offHLine = Offset(200f, 250f)
        val offHWeight = computeBrushWeight(offHLine, center, center, radius, hardness, BrushShape.LINE, angleDeg = 0f)
        assertEquals(0.0f, offHWeight, 0.001f)
        assertFalse(isPointInBrush(offHLine, center, center, radius, BrushShape.LINE, angleDeg = 0f))

        // Rotate by 90 degrees (vertical line along x = 200)
        // Now (200, 250) is ALONG the vertical line (distance 0)!
        val onVLineWeight = computeBrushWeight(offHLine, center, center, radius, hardness, BrushShape.LINE, angleDeg = 90f)
        assertEquals(1.0f, onVLineWeight, 0.01f)
        assertTrue(isPointInBrush(offHLine, center, center, radius, BrushShape.LINE, angleDeg = 90f))

        // And (300, 200) is now perpendicular to the vertical line (distance 100 > 25) and outside!
        val offVLineWeight = computeBrushWeight(onHLine, center, center, radius, hardness, BrushShape.LINE, angleDeg = 90f)
        assertEquals(0.0f, offVLineWeight, 0.001f)
        assertFalse(isPointInBrush(onHLine, center, center, radius, BrushShape.LINE, angleDeg = 90f))
    }

    @Test
    fun testInfiniteLineBrushLength() {
        val center = Offset(200f, 200f)
        val radius = 30f
        val hardness = 0.5f

        // Points arbitrarily far along the line orientation (angle = 0 deg -> horizontal)
        val farAlongLine1 = Offset(5000f, 200f)
        val farAlongLine2 = Offset(-8000f, 200f)
        assertEquals(1.0f, computeBrushWeight(farAlongLine1, center, center, radius, hardness, BrushShape.LINE, angleDeg = 0f), 0.01f)
        assertEquals(1.0f, computeBrushWeight(farAlongLine2, center, center, radius, hardness, BrushShape.LINE, angleDeg = 0f), 0.01f)
        assertTrue(isPointInBrush(farAlongLine1, center, center, radius, BrushShape.LINE, angleDeg = 0f))
        assertTrue(isPointInBrush(farAlongLine2, center, center, radius, BrushShape.LINE, angleDeg = 0f))

        // Point perpendicular beyond radius (y distance = 50 > radius 30)
        val farPerpendicular = Offset(5000f, 250f)
        assertEquals(0.0f, computeBrushWeight(farPerpendicular, center, center, radius, hardness, BrushShape.LINE, angleDeg = 0f), 0.001f)
        assertFalse(isPointInBrush(farPerpendicular, center, center, radius, BrushShape.LINE, angleDeg = 0f))
    }

    @Test
    fun testRectangleBrush() {
        val center = Offset(100f, 100f)
        val radius = 40f
        val hardness = 0.5f

        // Center should have full weight 1.0
        val centerWeight = computeBrushWeight(center, center, center, radius, hardness, BrushShape.RECTANGLE, angleDeg = 0f)
        assertEquals(1.0f, centerWeight, 0.01f)

        // Corner of core rectangle (dx = 20, dy = 20 where coreW = radius * hardness = 20)
        val cornerCorePoint = Offset(120f, 120f)
        val cornerCoreWeight = computeBrushWeight(cornerCorePoint, center, center, radius, hardness, BrushShape.RECTANGLE, angleDeg = 0f)
        assertEquals(1.0f, cornerCoreWeight, 0.01f)
        assertTrue(isPointInBrush(cornerCorePoint, center, center, radius, BrushShape.RECTANGLE, angleDeg = 0f))

        // Midpoint of falloff band (dx = 30, dy = 0 -> distance to core is 10; falloff = 20)
        // normalized x = 10 / 20 = 0.5 -> Hermite weight = 1 - 0.25 * (3 - 1) = 0.5
        val midFalloffPoint = Offset(130f, 100f)
        val midWeight = computeBrushWeight(midFalloffPoint, center, center, radius, hardness, BrushShape.RECTANGLE, angleDeg = 0f)
        assertEquals(0.5f, midWeight, 0.01f)

        // Point outside box (dx = 50, dy = 0 -> distance to core is 30 > falloff 20)
        val outsidePoint = Offset(150f, 100f)
        assertEquals(0.0f, computeBrushWeight(outsidePoint, center, center, radius, hardness, BrushShape.RECTANGLE, angleDeg = 0f))
        assertFalse(isPointInBrush(outsidePoint, center, center, radius, BrushShape.RECTANGLE, angleDeg = 0f))
    }

    @Test
    fun testShortcutRegistryHasNewBrushActions() {
        val keymap = Keymap.of(KeymapPreset.PHOTOSHOP)
        assertTrue(keymap.bindingsFor(ShortcutAction.BRUSH_ROTATE_LEFT).isNotEmpty())
        assertTrue(keymap.bindingsFor(ShortcutAction.BRUSH_ROTATE_RIGHT).isNotEmpty())
        assertTrue(keymap.bindingsFor(ShortcutAction.BRUSH_SHAPE_CYCLE).isNotEmpty())

        val leftBinding = keymap.bindingsFor(ShortcutAction.BRUSH_ROTATE_LEFT).first().format()
        assertTrue(leftBinding == "Alt+[" || leftBinding == ",")

        val rightBinding = keymap.bindingsFor(ShortcutAction.BRUSH_ROTATE_RIGHT).first().format()
        assertTrue(rightBinding == "Alt+]" || rightBinding == ".")

        val cycleBinding = keymap.bindingsFor(ShortcutAction.BRUSH_SHAPE_CYCLE).first().format()
        assertEquals("Alt+B", cycleBinding)
    }
}
