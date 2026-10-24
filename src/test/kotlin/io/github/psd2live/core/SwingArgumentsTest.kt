package io.github.psd2live.core

import kotlin.test.*

/** A swing edited in the modifier stack is written back through `swing_put`: its arguments read back as the same swing. */
class SwingArgumentsTest {
	private val swing = RigSwingEdit("Bang", "Bang", listOf("WarpBang"), listOf(
		SwingMotion(SwingKind.LATERAL, listOf("ParamBang_X_1", "ParamBang_X_2"), SwingShape(0.3f, 0.1f, 0.6f, -0.1f, 0.4f, flip = true),
			SwingPhysics(12f, 0.8f, 1.1f, 1.4f, 2f)),
		SwingMotion(SwingKind.VERTICAL, listOf("ParamBang_Y"), SwingShape(magnitude = 0.2f), SwingPhysics()),
	), SwingFulcrum.LEFT, SwingPreset.ACCESSORY, tilt = 12f, offsetAlong = 0.1f, offsetAcross = -0.05f)

	@Test
	fun `the arguments of a swing read back as that swing`() {
		val request = SwingAuthoring.request(SwingAuthoring.arguments(swing))
		assertEquals(swing, request.edit)
		assertFalse(request.estimatePhysics, "the pendulums given are kept, not estimated again")
		val still = swing.copy(motions = swing.motions.map { it.copy(physics = null) })
		assertEquals(still, SwingAuthoring.request(SwingAuthoring.arguments(still)).edit)
	}
}
