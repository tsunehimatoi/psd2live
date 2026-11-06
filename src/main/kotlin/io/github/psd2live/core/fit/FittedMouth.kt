package io.github.psd2live.core.fit

import io.github.psd2live.algorithms.binding.MouthRig

/**
 * The fitted mouth of a project: [rig] when the drawn mouth could be read ([result]), and which layers it moves;
 * [chin] is how far down the face reaches below the mouth, in the mouth's frame.
 */
class FittedMouth(val result: MouthFitting.Result, val rig: MouthRig?, val chin: Float = 0.6f, layers: List<io.github.psd2live.core.ClassifiedLayer> = emptyList()) {
	private val variant = result.layers.firstOrNull()?.semantic?.variant

	/** Whether a closed mouth is drawn beside the open one: it shows at rest and hands over as the lips part. */
	val closedLine: Boolean = layers.any { it.semantic.tag == io.github.psd2live.core.SemanticTag.MOUTH_CLOSE && it.opaquePixels > 0 && covers(it) }

	/** Whether [layer] is a part of the fitted mouth: the mouth itself, its closed line, teeth, tongue or inside, its lips and lines. */
	fun covers(layer: io.github.psd2live.core.ClassifiedLayer): Boolean = rig != null && (layer.semantic.role == null || layer.semantic.role in ROLES) &&
		layer.semantic.variant == variant && layer.semantic.tag in TAGS

	private val own = layers.filter { covers(it) && it.opaquePixels > 0 }

	/** Whether the mouth is drawn in layers - its lips or lip lines apart from its inside - rather than whole. */
	val layered: Boolean = own.any { it.semantic.role in LIPS }

	/** Whether lip layers with their skin cover the mouth's inside. */
	val lipped: Boolean = own.any { it.semantic.role == io.github.psd2live.core.PartRole.UPPER_LIP || it.semantic.role == io.github.psd2live.core.PartRole.LOWER_LIP }

	/**
	 * How [layer] follows the lips ([MouthRig.Follow]).
	 */
	fun follow(layer: io.github.psd2live.core.ClassifiedLayer): MouthRig.Follow {
		val tag = layer.semantic.tag
		val whole = if (rig?.options?.exactClose == true) MouthRig.Follow.WHOLE_TIGHT else MouthRig.Follow.WHOLE
		return when (tag) {
			io.github.psd2live.core.SemanticTag.TOOTH_T -> MouthRig.Follow.UPPER_TEETH
			io.github.psd2live.core.SemanticTag.TOOTH_B -> MouthRig.Follow.LOWER_TEETH
			io.github.psd2live.core.SemanticTag.TONGUE -> MouthRig.Follow.TONGUE
			io.github.psd2live.core.SemanticTag.MOUTH_CLOSE -> whole
			else -> when (layer.semantic.role) {
				io.github.psd2live.core.PartRole.UPPER_LINE -> MouthRig.Follow.UPPER_LINE
				io.github.psd2live.core.PartRole.LOWER_LINE -> MouthRig.Follow.LOWER_LINE
				io.github.psd2live.core.PartRole.UPPER_LIP -> MouthRig.Follow.UPPER_LIP
				io.github.psd2live.core.PartRole.LOWER_LIP -> MouthRig.Follow.LOWER_LIP
				io.github.psd2live.core.PartRole.HIGHLIGHT, io.github.psd2live.core.PartRole.SHADOW -> lying(layer)
				// Clipped onto the opening, the inside's base is drawn larger than any opening and stays deep;
				// the unclipped opening itself maps onto [u1, l1] so it clips teeth and tongue cleanly on both cel-line and lip-skin rigs.
				else -> if (layer.source.clipped && layered) MouthRig.Follow.DEEP else if (!layered) whole else MouthRig.Follow.OPENING
			}
		}
	}

	/** What a highlight or shadow on the mouth lies on, from where its middle is against the drawn lip edges. */
	private fun lying(layer: io.github.psd2live.core.ClassifiedLayer): MouthRig.Follow {
		val fit = rig?.fit ?: return MouthRig.Follow.WHOLE
		val (u, v) = fit.toMouth(layer.centroidX, layer.centroidY)
		val r = v - fit.baseline(u)
		val top = MouthFit.sample(fit.upper, u) - fit.baseline(u); val bottom = MouthFit.sample(fit.lower, u) - fit.baseline(u)
		val margin = (bottom - top) * 0.2f
		return when {
			r < top + margin -> MouthRig.Follow.UPPER_LIP
			r > bottom - margin -> MouthRig.Follow.LOWER_LIP
			layer.semantic.role == io.github.psd2live.core.PartRole.SHADOW -> MouthRig.Follow.DEEP
			lipped -> MouthRig.Follow.INSIDE
			else -> MouthRig.Follow.OPENING
		}
	}

	companion object {
		/** The roles that set a layer of the mouth apart from its inside. */
		val LIPS = setOf(io.github.psd2live.core.PartRole.UPPER_LIP, io.github.psd2live.core.PartRole.LOWER_LIP,
			io.github.psd2live.core.PartRole.UPPER_LINE, io.github.psd2live.core.PartRole.LOWER_LINE)
		/** The roles a layer of the fitted mouth can have. */
		val ROLES = LIPS + io.github.psd2live.core.PartRole.HIGHLIGHT + io.github.psd2live.core.PartRole.SHADOW
		val TAGS = setOf(io.github.psd2live.core.SemanticTag.MOUTH, io.github.psd2live.core.SemanticTag.MOUTH_OPEN,
			io.github.psd2live.core.SemanticTag.MOUTH_CLOSE, io.github.psd2live.core.SemanticTag.TOOTH_T,
			io.github.psd2live.core.SemanticTag.TOOTH_B, io.github.psd2live.core.SemanticTag.TONGUE)
	}
}
