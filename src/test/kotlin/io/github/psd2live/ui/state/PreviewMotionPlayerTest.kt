package io.github.psd2live.ui.state

import io.github.psd2live.core.StandardParameters
import io.github.psd2live.core.cubismMotionSlots
import kotlin.test.*

class PreviewMotionPlayerTest {
    @Test fun aMotionEndsItselfOnceItsTracksRunOut() {
        val player = PreviewMotionPlayer()
        assertTrue(player.start("Nod", skeleton = null))
        assertEquals("nod", player.activeName)
        assertEquals(-18f, player.advance(0.55f).getValue(StandardParameters.ANGLE_Y), 1e-4f)
        player.advance(1.44f)
        assertEquals("nod", player.activeName)
        assertTrue(player.advance(0.1f).isEmpty())
        assertNull(player.activeName)
    }

    @Test fun aNewMotionReplacesThePlayingOneFromItsStart() {
        val player = PreviewMotionPlayer()
        player.start("Nod", skeleton = null)
        player.advance(1.5f)
        assertTrue(player.start("Shake", skeleton = null))
        assertEquals("shake", player.activeName)
        val values = player.advance(0.4f)
        assertEquals(-20f, values.getValue(StandardParameters.ANGLE_X), 1e-4f)
        assertFalse(StandardParameters.ANGLE_Y in values)
    }

    @Test fun replayingTheSameMotionRestartsIt() {
        val player = PreviewMotionPlayer()
        player.start("Blink", skeleton = null)
        player.advance(1.1f)
        player.start("Blink", skeleton = null)
        assertEquals(0f, player.advance(0.45f).getValue(StandardParameters.EYE_L_OPEN), 1e-4f)
        assertEquals("blink", player.activeName)
    }

    @Test fun aMotionWithoutTracksStopsTheOnePlaying() {
        val player = PreviewMotionPlayer()
        player.start("Nod", skeleton = null)
        assertFalse(player.start("Unknown", skeleton = null))
        assertNull(player.activeName)
        assertTrue(player.advance(0.1f).isEmpty())
    }

    @Test fun stoppingByNameLeavesAnotherMotionPlaying() {
        val player = PreviewMotionPlayer()
        player.start("Shake", skeleton = null)
        player.stop("nod")
        assertEquals("shake", player.activeName)
        player.stop("Shake")
        assertNull(player.activeName)
    }

    @Test fun motionSlotsFindALoopPresetInTheIdleGroup() {
        val manifest = """
            {"Version":3,"FileReferences":{"Moc":"m.moc3","Textures":[],"Motions":{
              "Idle":[{"File":"motions/m.v2.idle.motion3.json"},{"File":"motions/m.v2.idleCute.motion3.json"}],
              "Nod":[{"File":"motions/m.v2.nod.motion3.json"}]
            }}}
        """.trimIndent()
        val slots = cubismMotionSlots(manifest)
        assertEquals("Idle" to 0, slots["idle"])
        assertEquals("Idle" to 1, slots["idlecute"])
        assertEquals("Nod" to 0, slots["nod"])
        assertTrue(cubismMotionSlots("{").isEmpty())
    }
}
