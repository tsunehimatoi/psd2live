package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the runtime repository's capability matrix to the official Cubism Editor's "Model target
 * version selection" dialog, both sides of every version boundary.  (The format-version mapping
 * each target persists as is pinned in :interop's RuntimeTargetMappingTest.)
 */
class RuntimeTargetTest {
	@Test
	fun featureBoundariesMatchTheOfficialDialog() {
		// New warp deformer method: SDK3.3+.
		assertTrue(!RuntimeTarget.Cubism30.supports(RuntimeFeature.WarpQuadTransform))
		assertTrue(RuntimeTarget.Cubism33.supports(RuntimeFeature.WarpQuadTransform))
		// Reversed Mask: SDK4.0+.
		assertTrue(!RuntimeTarget.Cubism33.supports(RuntimeFeature.ReversedMask))
		assertTrue(RuntimeTarget.Cubism40.supports(RuntimeFeature.ReversedMask))
		// Blend Shape (art mesh + warp), blend-shape parameters, multiply color, screen color: SDK4.2+.
		listOf(
			RuntimeFeature.MeshWarpBlendShapes,
			RuntimeFeature.BlendShapeParameters,
			RuntimeFeature.MultiplyColor,
			RuntimeFeature.ScreenColor,
		).forEach { feature ->
			assertTrue(!RuntimeTarget.Cubism40.supports(feature), "$feature is 4.2+, not 4.0")
			assertTrue(RuntimeTarget.Cubism42.supports(feature), "$feature is supported at 4.2")
		}
		// Blend Shape (part / rotation / glue / scalar) and motion-sync: SDK5.0+.
		listOf(RuntimeFeature.ExtendedBlendShapes, RuntimeFeature.MotionSync).forEach { feature ->
			assertTrue(!RuntimeTarget.Cubism42.supports(feature), "$feature is 5.0+, not 4.2")
			assertTrue(RuntimeTarget.Cubism50.supports(feature), "$feature is supported at 5.0")
		}
		// Blend mode, offscreen drawing, parameter repeat: SDK5.3+.
		listOf(RuntimeFeature.ExtendedBlendModes, RuntimeFeature.PartComposite, RuntimeFeature.ParameterRepeat)
			.forEach { feature ->
				assertTrue(!RuntimeTarget.Cubism50.supports(feature), "$feature is 5.3+, not 5.0")
				assertTrue(RuntimeTarget.Cubism53.supports(feature), "$feature is supported at 5.3")
			}
		// ArtPath is "Latest Cubism only": no Cubism ceiling allows it.
		assertTrue(!RuntimeTarget.Cubism53.supports(RuntimeFeature.ArtPath), "ArtPath is beyond every Cubism target")
		assertTrue(RuntimeTarget.NoTarget.supports(RuntimeFeature.ArtPath), "ArtPath is authorable with no target")
	}

	@Test
	fun restrictedListsDeriveFromTheMatrix() {
		assertEquals(emptyList(), RuntimeTarget.NoTarget.restrictedFeatures(), "NoTarget restricts nothing")
		assertEquals(
			RuntimeTarget.Cubism50.restrictedFeatures(),
			RuntimeTarget.Ayagami.restrictedFeatures(),
			"Ayagami is effectively Cubism 5.0 at present",
		)
		assertEquals(listOf(RuntimeFeature.ArtPath), RuntimeTarget.Cubism53.restrictedFeatures())
		assertEquals(
			RuntimeFeature.entries.toList(),
			RuntimeTarget.Cubism30.restrictedFeatures(),
			"every feature postdates Cubism 3.0",
		)
	}

	@Test
	fun displayNamesAreDistinctAndNonBlank() {
		val names = RuntimeTarget.entries.map { target -> target.displayName }
		assertEquals(names.size, names.toSet().size, "display names are distinct")
		assertTrue(names.none { name -> name.isBlank() }, "display names are non-blank")
	}

	@Test
	fun cubismLevelTextTracksTheGatingLevel() {
		assertNull(RuntimeTarget.NoTarget.cubismLevelText(), "no ceiling, no text")
		assertEquals("5.0", RuntimeTarget.Ayagami.cubismLevelText(), "the Ayagami label's level feed")
		assertEquals("3.3", RuntimeTarget.Cubism33.cubismLevelText())
	}
}