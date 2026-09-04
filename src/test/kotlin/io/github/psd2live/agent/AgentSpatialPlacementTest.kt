package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentSpatialPlacementTest {
	private val spatial = AgentViewSpatialMetadata(
		pixelWidth = 800,
		pixelHeight = 400,
		canvasWidth = 2000f,
		canvasHeight = 3000f,
		requestedViewRect = Bounds(100f, 200f, 500f, 400f),
		viewRect = Bounds(100f, 200f, 500f, 400f),
		canvasUnitsPerPixelX = 0.5f,
		canvasUnitsPerPixelY = 0.5f,
	)

	@Test
	fun generatedPngKeepsCanvasSizeWhenItsPixelResolutionChanges() {
		val placement = spatial.placementForGeneratedPng("view-source", 2000, 1000)

		assertEquals(Bounds(100f, 200f, 500f, 400f), placement.canvasRect)
		assertEquals(0.2f, placement.canvasUnitsPerPixelX)
		assertEquals(0.2f, placement.canvasUnitsPerPixelY)
	}

	@Test
	fun mismatchedAspectIsNotSilentlyStretched() {
		assertFailsWith<IllegalArgumentException> {
			spatial.placementForGeneratedPng("view-source", 1000, 1000)
		}
	}

	@Test
	fun sourcePixelSubregionMapsToItsCanvasSubregion() {
		val placement = spatial.placementForGeneratedPng(
			sourceViewId = "view-source",
			imagePixelWidth = 400,
			imagePixelHeight = 200,
			sourcePixelRect = AgentPixelRect(left = 200, top = 100, width = 400, height = 200),
		)

		assertEquals(Bounds(200f, 250f, 400f, 350f), placement.canvasRect)
	}
}
