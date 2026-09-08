package org.umamo.interop.moc3.export

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.format.moc3.moc.Section
import org.umamo.interop.ExportNotice
import org.umamo.interop.nearestLegacyBlendMode
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartGroupMode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.multiplyColor
import org.umamo.runtime.model.screenColor

/**
 * Removes from a copy of the rig everything the export target's runtime cannot load.
 *
 * A MOC3 version is a hard capability boundary: a Cubism 3.0 MOC3 has nowhere to put a blend shape, a color
 * table, or an offscreen, and the sections that would carry them do not exist in its table.  Dropping
 * them at the SECTION level is not enough - the CountInfo would still count the keyforms a stripped
 * section no longer holds - so the removal happens here, on the [PuppetModel], before any lowering
 * runs.  The lowering then sees one shape it can express completely.
 *
 * Reporting is the other half of the reason: a notice raised here names entities the rigger
 * recognizes ("Warp40", "ParamAngleX"), while the same loss detected after lowering could only name
 * section indices.
 *
 * What can be carried is asked of the SECTION TABLE, not of [RuntimeTarget.supports].  The two ladders
 * are close but not the same, and where they differ the section table is the one that decides what a
 * file can hold: [RuntimeTarget] follows the official editor's target dialog, which gates the reversed
 * mask at Cubism 4.0 and the parameter repeat at Cubism 5.3 even though both are carried by every MOC3 version back
 * to v1.  Stripping on the editor's ladder would make a v5 file that uses either one round-trip
 * LOSSILY through a v5 export - LimeBirb's repeating parameters are exactly that case in the corpus.
 * RuntimeTarget's own docblock anticipates this and says so: edit gating is editor parity, the
 * section-level nuances belong to export lowering.
 *
 * The consequence is that a pre-export confirmation built on
 * [org.umamo.runtime.model.unsupportedFeaturesInUse] can name a feature this does not strip.  That is
 * the conservative direction (it over-warns, never under-warns), and the report returned here is what
 * actually happened.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md § Export</a>
 */
object Moc3VersionDowngrade {
	/**
	 * A rig reduced to a target's capabilities, plus what that cost.
	 *
	 * @property PuppetModel puppet  The stripped copy; the caller's model is untouched.
	 * @property List        notices One [ExportNotice.FeatureStripped] per feature actually removed.
	 */
	class Stripped(val puppet: PuppetModel, val notices: List<ExportNotice>)

	/**
	 * Strips [puppet] down to what moc [version] can carry.
	 *
	 * @param PuppetModel puppet  The rig to export.
	 * @param MocVersion  version The moc version being written.
	 * @return Stripped The reduced rig and its notices; the input model when nothing had to go.
	 */
	fun strip(puppet: PuppetModel, version: MocVersion): Stripped {
		val unsupported = RuntimeFeature.entries.filterNot { feature -> carries(feature, version) }
		if (unsupported.isEmpty()) {
			return Stripped(puppet, emptyList())
		}
		val notices = ArrayList<ExportNotice>()
		var stripped = puppet

		/**
		 * Records one feature's removal, skipping the notice when nothing carried it.
		 *
		 * @param RuntimeFeature feature  The feature removed.
		 * @param List           subjects The entities it was removed from.
		 */
		fun report(feature: RuntimeFeature, subjects: List<String>) {
			if (subjects.isNotEmpty()) {
				notices.add(ExportNotice.FeatureStripped(feature, subjects))
			}
		}

		for (feature in unsupported) {
			stripped =
				when (feature) {
					RuntimeFeature.WarpQuadTransform -> stripQuadTransform(stripped, ::report)
					RuntimeFeature.MeshWarpBlendShapes -> stripMeshWarpBlendShapes(stripped, ::report)
					RuntimeFeature.BlendShapeParameters -> stripBlendShapeParameters(stripped, ::report)
					RuntimeFeature.MultiplyColor ->
						stripColorChannel(stripped, FormChannel.MULTIPLY_COLOR, ColorRgb.MultiplyIdentity, ::report)

					RuntimeFeature.ScreenColor ->
						stripColorChannel(stripped, FormChannel.SCREEN_COLOR, ColorRgb.ScreenIdentity, ::report)

					RuntimeFeature.ExtendedBlendShapes -> stripExtendedBlendShapes(stripped, ::report)
					RuntimeFeature.ExtendedBlendModes -> stripExtendedBlendModes(stripped, ::report)
					RuntimeFeature.PartComposite -> stripPartComposites(stripped, ::report)
					// Carried by EVERY moc version, so `carries` is unconditionally true for both and neither
					// ever reaches this loop: the reversed mask is a bit in the constant-flag byte and
					// parameter repeat is section 54, and both go back to v1.  The arm exists to keep the
					// `when` exhaustive, not because there is work to do.  If a future version gate ever
					// drops one of them, this has to grow a real strip - returning the model unchanged would
					// write the feature into a file with nowhere to put it.
					RuntimeFeature.ReversedMask, RuntimeFeature.ParameterRepeat -> stripped
					// Neither is representable in PuppetModel, so there is nothing here to remove: motion
					// sync is a sidecar family and an art path survives only as CMO3 round-trip payload.
					RuntimeFeature.MotionSync, RuntimeFeature.ArtPath -> stripped
				}
		}
		return Stripped(stripped, notices)
	}

	/**
	 * Whether moc [version] has somewhere to put [feature].
	 *
	 * Asked of the [Section] table itself rather than restated as version numbers, so a section whose
	 * introduction version is corrected in one place cannot leave this saying otherwise - and each
	 * branch doubles as the citation for why the feature has that floor.
	 *
	 * @param RuntimeFeature feature The feature to place.
	 * @param MocVersion     version The version being written.
	 * @return Boolean True when the version can carry it.
	 */
	private fun carries(feature: RuntimeFeature, version: MocVersion): Boolean =
		when (feature) {
			RuntimeFeature.WarpQuadTransform -> Section.WARP_MODE.indexIn(version) >= 0
			RuntimeFeature.MeshWarpBlendShapes -> Section.BLENDSHAPE_MESH_OBJECT.indexIn(version) >= 0
			RuntimeFeature.BlendShapeParameters -> Section.PARAM_TYPE.indexIn(version) >= 0
			RuntimeFeature.MultiplyColor, RuntimeFeature.ScreenColor -> Section.COLOR_MULTIPLY_R.indexIn(version) >= 0
			RuntimeFeature.ExtendedBlendShapes -> Section.BLENDSHAPE_ROTATION_OBJECT.indexIn(version) >= 0
			RuntimeFeature.ExtendedBlendModes -> Section.ARTMESH_EXTENDED_BLEND.indexIn(version) >= 0
			RuntimeFeature.PartComposite -> Section.OFFSCREEN_OWNER_PART.indexIn(version) >= 0
			RuntimeFeature.ParameterRepeat -> Section.PARAM_REPEAT.indexIn(version) >= 0
			// The reversed mask is a BIT in the drawable/offscreen constant-flag byte, not a section of
			// its own, and that byte is in every version - so no moc version has to give it up.
			RuntimeFeature.ReversedMask -> true
			// Neither is representable in PuppetModel, so no version can fail to carry what is not there.
			RuntimeFeature.MotionSync, RuntimeFeature.ArtPath -> true
		}

	/** The quad-transform flag (Cubism 3.3's warp method) cleared on every warp that set it. */
	private fun stripQuadTransform(puppet: PuppetModel, report: Reporter): PuppetModel {
		val affected = ArrayList<String>()
		val deformers =
			puppet.deformers.map { deformer ->
				if (deformer is Deformer.Warp && deformer.isQuadTransform) {
					affected.add(deformer.name)
					deformer.copy(isQuadTransform = false)
				} else {
					deformer
				}
			}
		report(RuntimeFeature.WarpQuadTransform, affected)
		return if (affected.isEmpty()) puppet else puppet.copy(deformers = deformers)
	}

	/** Every mesh and warp blend-shape binding dropped (moc 4.2 introduced them). */
	private fun stripMeshWarpBlendShapes(puppet: PuppetModel, report: Reporter): PuppetModel {
		val affected = ArrayList<String>()
		val drawables =
			puppet.drawables.map { drawable ->
				if (drawable.blendShapes.isEmpty()) {
					drawable
				} else {
					affected.add(drawable.name)
					drawable.copy(blendShapes = emptyList())
				}
			}
		val deformers =
			puppet.deformers.map { deformer ->
				if (deformer is Deformer.Warp && deformer.blendShapes.isNotEmpty()) {
					affected.add(deformer.name)
					deformer.copy(blendShapes = emptyList())
				} else {
					deformer
				}
			}
		report(RuntimeFeature.MeshWarpBlendShapes, affected)
		return if (affected.isEmpty()) puppet else puppet.copy(drawables = drawables, deformers = deformers)
	}

	/** Rotation- and part-target blend records dropped (moc 5.0 introduced those two targets). */
	private fun stripExtendedBlendShapes(puppet: PuppetModel, report: Reporter): PuppetModel {
		val affected = ArrayList<String>()
		val deformers =
			puppet.deformers.map { deformer ->
				if (deformer is Deformer.Rotation && deformer.blendShapes.isNotEmpty()) {
					affected.add(deformer.name)
					deformer.copy(blendShapes = emptyList())
				} else {
					deformer
				}
			}
		val parts =
			puppet.parts.map { part ->
				if (part.blendShapes.isEmpty()) {
					part
				} else {
					affected.add(part.name)
					part.copy(blendShapes = emptyList())
				}
			}
		report(RuntimeFeature.ExtendedBlendShapes, affected)
		return if (affected.isEmpty()) puppet else puppet.copy(deformers = deformers, parts = parts)
	}

	/** Blend-shape parameters demoted to normal axes; their records are already gone by this point. */
	private fun stripBlendShapeParameters(puppet: PuppetModel, report: Reporter): PuppetModel {
		val affected = ArrayList<String>()
		val parameters =
			puppet.parameters.map { parameter: Parameter ->
				if (parameter.kind == ParameterKind.BLEND_SHAPE) {
					affected.add(parameter.id.raw)
					parameter.copy(kind = ParameterKind.NORMAL)
				} else {
					parameter
				}
			}
		report(RuntimeFeature.BlendShapeParameters, affected)
		return if (affected.isEmpty()) puppet else puppet.copy(parameters = parameters)
	}

	/** One color channel reset to its identity everywhere - statics and tracks alike. */
	private fun stripColorChannel(
		puppet: PuppetModel,
		channel: FormChannel,
		identity: ColorRgb,
		report: Reporter,
	): PuppetModel {
		val isMultiply = channel == FormChannel.MULTIPLY_COLOR
		val identityValue = ChannelValue.Color(identity)
		val affected = ArrayList<String>()

		/**
		 * Whether an owner tints through this channel - a non-identity static or a track.
		 *
		 * @param ColorRgb     staticColor The owner's static color.
		 * @param ChannelGrids grids       The owner's tracks.
		 * @return Boolean True when the channel is in use.
		 */
		fun tints(staticColor: ColorRgb, grids: ChannelGrids): Boolean =
			staticColor != identity || grids.varies(channel, identityValue)

		val drawables =
			puppet.drawables.map { drawable: Drawable ->
				val staticColor = if (isMultiply) drawable.multiplyColor else drawable.screenColor
				if (!tints(staticColor, drawable.channelGrids)) {
					drawable
				} else {
					affected.add(drawable.name)
					drawable
						.copy(channelGrids = drawable.channelGrids.without(channel))
						.let { if (isMultiply) it.copy(multiplyColor = identity) else it.copy(screenColor = identity) }
				}
			}
		val deformers =
			puppet.deformers.map { deformer ->
				val staticColor = if (isMultiply) deformer.multiplyColor else deformer.screenColor
				if (!tints(staticColor, deformer.channelGrids)) {
					deformer
				} else {
					affected.add(deformer.name)
					val grids = deformer.channelGrids.without(channel)
					when (deformer) {
						is Deformer.Warp ->
							if (isMultiply) {
								deformer.copy(channelGrids = grids, multiplyColor = identity)
							} else {
								deformer.copy(channelGrids = grids, screenColor = identity)
							}

						is Deformer.Rotation ->
							if (isMultiply) {
								deformer.copy(channelGrids = grids, multiplyColor = identity)
							} else {
								deformer.copy(channelGrids = grids, screenColor = identity)
							}
					}
				}
			}
		val parts =
			puppet.parts.map { part: Part ->
				val composite = part.activeComposite ?: return@map part
				val staticColor = if (isMultiply) composite.multiplyColor else composite.screenColor
				if (!tints(staticColor, part.channelGrids)) {
					part
				} else {
					affected.add(part.name)
					val reset: PartComposite =
						if (isMultiply) {
							composite.copy(multiplyColor = identity)
						} else {
							composite.copy(screenColor = identity)
						}
					part.copy(composite = reset, channelGrids = part.channelGrids.without(channel))
				}
			}
		report(if (isMultiply) RuntimeFeature.MultiplyColor else RuntimeFeature.ScreenColor, affected)
		return if (affected.isEmpty()) {
			puppet
		} else {
			puppet.copy(drawables = drawables, deformers = deformers, parts = parts)
		}
	}

	/** The 5.3 blend surface degraded to its nearest pre-5.3 ancestor, alpha back to Over. */
	private fun stripExtendedBlendModes(puppet: PuppetModel, report: Reporter): PuppetModel {
		val affected = ArrayList<String>()
		val drawables =
			puppet.drawables.map { drawable ->
				if (drawable.blendMode.isLegacy && drawable.alphaBlendMode == AlphaBlendMode.Over) {
					drawable
				} else {
					affected.add(drawable.name)
					drawable.copy(
						blendMode = nearestLegacyBlendMode(drawable.blendMode),
						alphaBlendMode = AlphaBlendMode.Over,
					)
				}
			}
		val parts =
			puppet.parts.map { part ->
				val composite = part.activeComposite ?: return@map part
				if (composite.blendMode.isLegacy && composite.alphaBlendMode == AlphaBlendMode.Over) {
					part
				} else {
					affected.add(part.name)
					part.copy(
						composite =
							composite.copy(
								blendMode = nearestLegacyBlendMode(composite.blendMode),
								alphaBlendMode = AlphaBlendMode.Over,
							),
					)
				}
			}
		report(RuntimeFeature.ExtendedBlendModes, affected)
		return if (affected.isEmpty()) puppet else puppet.copy(drawables = drawables, parts = parts)
	}

	/**
	 * Isolated parts demoted to grouped: the subtree keeps its single stacking slot but stops being
	 * composited as its own layer.  The composite settings stay on the part - they are latent by
	 * design, and this strip runs on an export copy the rigger never sees.
	 */
	private fun stripPartComposites(puppet: PuppetModel, report: Reporter): PuppetModel {
		val affected = ArrayList<String>()
		val parts =
			puppet.parts.map { part ->
				if (part.isIsolated) {
					affected.add(part.name)
					part.copy(groupMode = PartGroupMode.Grouped)
				} else {
					part
				}
			}
		report(RuntimeFeature.PartComposite, affected)
		return if (affected.isEmpty()) puppet else puppet.copy(parts = parts)
	}
}

/** How each strip hands its result back: the feature removed and who it was removed from. */
private typealias Reporter = (RuntimeFeature, List<String>) -> Unit

/**
 * This owner's tracks without [channel], sharing [ChannelGrids.Empty] when nothing is left.
 *
 * @param FormChannel channel The channel to drop.
 * @return ChannelGrids The remaining tracks.
 */
private fun ChannelGrids.without(channel: FormChannel): ChannelGrids {
	if (this[channel] == null) {
		return this
	}
	val remaining = gridsByChannel - channel
	return if (remaining.isEmpty()) ChannelGrids.Empty else ChannelGrids(remaining)
}