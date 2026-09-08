package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the strip diff: a restricted feature is reported only when the document actually uses it,
 * latent state never counts, and a supporting target reports nothing.
 */
class RuntimeFeatureScanTest {
	private val drawableId = DrawableId("drawable")
	private val partId = PartId("part")
	private val deformerId = DeformerId("deformer")
	private val parameterId = ParameterId("Param")

	private fun drawable(
		blendMode: BlendMode = BlendMode.Normal,
		alphaBlendMode: AlphaBlendMode = AlphaBlendMode.Over,
		invertMask: Boolean = false,
		multiplyColor: ColorRgb = ColorRgb.MultiplyIdentity,
		blendShapes: List<BlendShapeBinding<MeshForm>> = emptyList(),
	): Drawable =
		Drawable(
			id = drawableId,
			name = drawableId.raw,
			parentDeformerId = null,
			blendMode = blendMode,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			alphaBlendMode = alphaBlendMode,
			invertMask = invertMask,
			multiplyColor = multiplyColor,
			blendShapes = blendShapes,
		)

	private fun model(
		drawables: List<Drawable> = emptyList(),
		parts: List<Part> = emptyList(),
		deformers: List<Deformer> = emptyList(),
		parameters: List<Parameter> = emptyList(),
	): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = parts,
			deformers = deformers,
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) } + parts.map { part -> OrgChild.Part(part.id) },
			rootPartId = null,
		)

	@Test
	fun bareModelReportsNothingUnderAnyTarget() {
		val bare = model()
		RuntimeTarget.entries.forEach { target ->
			assertEquals(emptySet(), bare.unsupportedFeaturesInUse(target), "bare model under $target")
		}
	}

	@Test
	fun supportingTargetsReportNothing() {
		val kitchenSink =
			model(
				drawables = listOf(drawable(blendMode = BlendMode.Overlay, invertMask = true)),
				parts = listOf(Part(partId, "part", children = emptyList(), groupMode = PartGroupMode.Isolated)),
			)
		assertEquals(emptySet(), kitchenSink.unsupportedFeaturesInUse(RuntimeTarget.NoTarget))
		assertEquals(emptySet(), kitchenSink.unsupportedFeaturesInUse(RuntimeTarget.Cubism53))
	}

	@Test
	fun isolatedPartReportsPartCompositeOnly() {
		val isolated = model(parts = listOf(Part(partId, "part", children = emptyList(), groupMode = PartGroupMode.Isolated)))
		assertEquals(setOf(RuntimeFeature.PartComposite), isolated.unsupportedFeaturesInUse(RuntimeTarget.Cubism50))
	}

	@Test
	fun latentCompositeDoesNotCount() {
		// A PassThrough part with stored composite settings is invisible to the runtime - not "in use".
		val latent =
			model(
				parts =
					listOf(
						Part(
							partId,
							"part",
							children = emptyList(),
							groupMode = PartGroupMode.PassThrough,
							composite = PartComposite(blendMode = BlendMode.Overlay, invertMask = true),
						),
					),
			)
		assertEquals(emptySet(), latent.unsupportedFeaturesInUse(RuntimeTarget.Cubism30))
	}

	@Test
	fun extendedBlendModesCoverColorAndAlpha() {
		val extendedColor = model(drawables = listOf(drawable(blendMode = BlendMode.Overlay)))
		assertEquals(setOf(RuntimeFeature.ExtendedBlendModes), extendedColor.unsupportedFeaturesInUse(RuntimeTarget.Cubism50))
		val extendedAlpha = model(drawables = listOf(drawable(alphaBlendMode = AlphaBlendMode.Atop)))
		assertEquals(setOf(RuntimeFeature.ExtendedBlendModes), extendedAlpha.unsupportedFeaturesInUse(RuntimeTarget.Cubism50))
	}

	@Test
	fun reversedMaskReportsBelowFourOh() {
		val inverted = model(drawables = listOf(drawable(invertMask = true)))
		assertEquals(setOf(RuntimeFeature.ReversedMask), inverted.unsupportedFeaturesInUse(RuntimeTarget.Cubism33))
		assertEquals(emptySet(), inverted.unsupportedFeaturesInUse(RuntimeTarget.Cubism40))
	}

	@Test
	fun multiplyColorStaticReportsBelowFourTwo() {
		val tinted = model(drawables = listOf(drawable(multiplyColor = ColorRgb(0.5f, 0.5f, 0.5f))))
		assertEquals(setOf(RuntimeFeature.MultiplyColor), tinted.unsupportedFeaturesInUse(RuntimeTarget.Cubism40))
		assertEquals(emptySet(), tinted.unsupportedFeaturesInUse(RuntimeTarget.Cubism42))
	}

	@Test
	fun quadWarpReportsBelowThreeThree() {
		val quadWarp =
			model(
				deformers =
					listOf(
						Deformer.Warp(
							id = deformerId,
							name = "warp",
							parent = null,
							partId = null,
							rows = 2,
							columns = 2,
							isQuadTransform = true,
							geometryGrid = null,
						),
					),
			)
		assertEquals(setOf(RuntimeFeature.WarpQuadTransform), quadWarp.unsupportedFeaturesInUse(RuntimeTarget.Cubism30))
		assertEquals(emptySet(), quadWarp.unsupportedFeaturesInUse(RuntimeTarget.Cubism33))
	}

	@Test
	fun rotationBlendShapesReportBelowFiveOh() {
		val binding = BlendShapeBinding<RotationForm>(parameterId, floatArrayOf(0f, 1f), 0, listOf(null, null))
		val rotation =
			model(
				deformers =
					listOf(
						Deformer.Rotation(
							id = deformerId,
							name = "rotation",
							parent = null,
							partId = null,
							baseAngle = 0f,
							geometryGrid = null,
							blendShapes = listOf(binding),
						),
					),
			)
		assertEquals(setOf(RuntimeFeature.ExtendedBlendShapes), rotation.unsupportedFeaturesInUse(RuntimeTarget.Cubism42))
		assertEquals(emptySet(), rotation.unsupportedFeaturesInUse(RuntimeTarget.Cubism50))
	}

	@Test
	fun blendShapeParametersReportBelowFourTwo() {
		val blendShapeParameter = model(parameters = listOf(Parameter(parameterId, "Param", 0f, 1f, 0f, ParameterKind.BLEND_SHAPE)))
		assertEquals(
			setOf(RuntimeFeature.BlendShapeParameters),
			blendShapeParameter.unsupportedFeaturesInUse(RuntimeTarget.Cubism40),
		)
		assertEquals(emptySet(), blendShapeParameter.unsupportedFeaturesInUse(RuntimeTarget.Cubism42))
	}

	@Test
	fun partMasksAreNotAFeature() {
		// Part-typed composite masks decompose to drawable ids at the CMO3/MOC3 boundary, so their use
		// is never reported under any target - only the offscreen composite itself is a 5.3 feature.
		val partMasked =
			model(
				parts =
					listOf(
						Part(
							partId,
							"part",
							children = emptyList(),
							groupMode = PartGroupMode.Isolated,
							composite = PartComposite(maskedByParts = listOf(partId)),
						),
					),
			)
		assertEquals(emptySet(), partMasked.unsupportedFeaturesInUse(RuntimeTarget.Cubism53))
		assertEquals(setOf(RuntimeFeature.PartComposite), partMasked.unsupportedFeaturesInUse(RuntimeTarget.Cubism50))
	}
}