package org.umamo.runtime.model

/**
 * The restricted features this document ACTUALLY USES under [target] - the decision aid the
 * Properties Document tab marks its per-target restricted list with.  A feature the target supports
 * is never reported, and neither is a restricted feature the document does not use.  A MOC3
 * export's own "was removed" notices come from the version-downgrade pass, not from here.
 *
 * [RuntimeFeature.MotionSync] and [RuntimeFeature.ArtPath] are never reported: they are not
 * representable in [PuppetModel] (motion-sync is a sidecar family, an art path survives only as CMO3
 * round-trip payload), so an export dialog cannot warn about them from the model alone.
 *
 * Latent state does not count as use: a non-isolated part's stored [Part.composite] is invisible
 * to both renderer and runtime, so only isolated parts' composites are scanned.
 *
 * @param RuntimeTarget target The target to diff against.
 * @return Set The restricted features in use; empty when the document fits the target.
 */
fun PuppetModel.unsupportedFeaturesInUse(target: RuntimeTarget): Set<RuntimeFeature> {
	val unsupportedFeatures = RuntimeFeature.entries.filterNot { feature -> target.supports(feature) }
	if (unsupportedFeatures.isEmpty()) {
		return emptySet()
	}
	// Materialized once for the whole scan rather than inside every feature branch.
	val activeComposites = parts.mapNotNull { part -> part.activeComposite }
	return unsupportedFeatures.filterTo(linkedSetOf()) { feature -> usesFeature(feature, activeComposites) }
}

/**
 * Whether the document uses [feature] at all, independent of any target.
 *
 * @param RuntimeFeature feature The feature to probe for.
 * @param List activeComposites The isolated parts' composites, precomputed by the caller.
 * @return Boolean True when the feature is present somewhere in the document.
 */
private fun PuppetModel.usesFeature(feature: RuntimeFeature, activeComposites: List<PartComposite>): Boolean =
	when (feature) {
		RuntimeFeature.WarpQuadTransform ->
			deformers.any { deformer -> deformer is Deformer.Warp && deformer.isQuadTransform }

		RuntimeFeature.ReversedMask ->
			drawables.any { drawable -> drawable.invertMask } || activeComposites.any { composite -> composite.invertMask }

		RuntimeFeature.MeshWarpBlendShapes ->
			drawables.any { drawable -> drawable.blendShapes.isNotEmpty() } ||
				deformers.any { deformer -> deformer is Deformer.Warp && deformer.blendShapes.isNotEmpty() }

		RuntimeFeature.BlendShapeParameters ->
			parameters.any { parameter -> parameter.kind == ParameterKind.BLEND_SHAPE }

		RuntimeFeature.MultiplyColor -> usesColorChannel(FormChannel.MULTIPLY_COLOR, ColorRgb.MultiplyIdentity)

		RuntimeFeature.ScreenColor -> usesColorChannel(FormChannel.SCREEN_COLOR, ColorRgb.ScreenIdentity)

		RuntimeFeature.ExtendedBlendShapes ->
			deformers.any { deformer -> deformer is Deformer.Rotation && deformer.blendShapes.isNotEmpty() }

		RuntimeFeature.ExtendedBlendModes ->
			drawables.any { drawable -> !drawable.blendMode.isLegacy || drawable.alphaBlendMode != AlphaBlendMode.Over } ||
				activeComposites.any { composite -> !composite.blendMode.isLegacy || composite.alphaBlendMode != AlphaBlendMode.Over }

		RuntimeFeature.PartComposite -> parts.any { part -> part.isIsolated }

		RuntimeFeature.ParameterRepeat -> parameters.any { parameter -> parameter.repeat }

		RuntimeFeature.MotionSync,
		RuntimeFeature.ArtPath,
		-> false
	}

/**
 * Whether any drawable, deformer, or isolated part tints through [channel] - a non-identity static,
 * or a keyform track holding some non-identity cell.  A track of pure identity cells is not use, so
 * the track side goes through [ChannelGrids.varies] rather than a mere presence check.
 *
 * @param FormChannel channel The color channel (multiply or screen).
 * @param ColorRgb identity That channel's identity color (leaves pixels unchanged).
 * @return Boolean True when the channel is in use somewhere.
 */
private fun PuppetModel.usesColorChannel(channel: FormChannel, identity: ColorRgb): Boolean {
	val isMultiply = channel == FormChannel.MULTIPLY_COLOR
	val identityValue = ChannelValue.Color(identity)
	val drawableUses =
		drawables.any { drawable ->
			val staticColor = if (isMultiply) drawable.multiplyColor else drawable.screenColor
			staticColor != identity || drawable.channelGrids.varies(channel, identityValue)
		}
	val deformerUses =
		deformers.any { deformer ->
			val staticColor = if (isMultiply) deformer.multiplyColor else deformer.screenColor
			staticColor != identity || deformer.channelGrids.varies(channel, identityValue)
		}
	// A part's color track keys its composite, so it only counts while the part is isolated.
	val compositeUses =
		parts.any { part ->
			val composite = part.activeComposite ?: return@any false
			val staticColor = if (isMultiply) composite.multiplyColor else composite.screenColor
			staticColor != identity || part.channelGrids.varies(channel, identityValue)
		}
	return drawableUses || deformerUses || compositeUses
}