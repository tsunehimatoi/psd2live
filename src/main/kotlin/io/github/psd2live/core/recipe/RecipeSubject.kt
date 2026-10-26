package io.github.psd2live.core.recipe

import io.github.psd2live.core.PartRole
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.PuppetModel

/**
 * What the art gives the rig's modules to work on: each visible layer's part, side and role. A module [RigModule.applies]
 * to art that has what it moves, and adds parameters for the sides the art has.
 */
class RecipeSubject(private val pieces: List<Piece>) {
	data class Piece(val tag: SemanticTag, val side: Side, val role: PartRole?)

	/** Whether a visible layer is [tag] (with [role] when given; [anyRole] false asks for the part itself, without a role). */
	fun has(tag: SemanticTag, role: PartRole? = null, anyRole: Boolean = true): Boolean =
		pieces.any { it.tag == tag && (if (role != null) it.role == role else anyRole || it.role == null) }

	/** The sides [tag]'s layers are on; a layer without a side counts as both. */
	fun sides(tag: SemanticTag): Set<Side> = pieces.filter { it.tag == tag }.flatMapTo(LinkedHashSet()) {
		if (it.side == Side.NONE) listOf(Side.LEFT, Side.RIGHT) else listOf(it.side)
	}

	companion object {
		val Empty = RecipeSubject(emptyList())

		fun of(analysis: PipelineAnalysis?): RecipeSubject = analysis?.let { result ->
			RecipeSubject(result.layers.filter { it.opaquePixels > 0 }.map { Piece(it.semantic.tag, it.semantic.side, it.semantic.role) }.distinct())
		} ?: Empty
	}
}

/**
 * What a module adds to the rig: the [parameters] it creates, the keyforms (grid cells) and blend-shape forms it adds,
 * and its physics groups. [estimated] is true for a module that is off, whose cost comes from its own estimate rather
 * than the built rig.
 */
data class ModuleCost(
	val parameters: List<String>,
	val keyforms: Int = 0,
	val blendShapes: Int = 0,
	val physicsGroups: Int = 0,
	val estimated: Boolean = true,
) {
	companion object {
		/**
		 * [module]'s cost read off [model], the rig it is on in: its parameters that the rig has; on every grid keyed on
		 * them, the cells beyond those the grid would have without their axes; every blend-shape form they drive; and its
		 * physics groups among [physicsGroups].
		 */
		fun measure(module: RigModule, subject: RecipeSubject, model: PuppetModel, physicsGroups: Collection<String>): ModuleCost {
			val own = module.parameters(subject).toSet()
			val present = model.parameters.map { it.id.raw }.filter { it in own }
			val ids = present.toSet()
			var keyforms = 0
			var blendForms = 0
			fun grid(grid: KeyformGrid<*>?) {
				if (grid == null) return
				val axes = grid.axes.filter { it.parameterId.raw in ids }
				if (axes.isEmpty()) return
				val cells = grid.cells.size
				keyforms += cells - cells / axes.fold(1) { product, axis -> product * axis.keys.size.coerceAtLeast(1) }
			}
			fun channels(grids: ChannelGrids) = grids.gridsByChannel.values.forEach(::grid)
			fun blends(bindings: List<org.umamo.runtime.model.BlendShapeBinding<*>>) {
				blendForms += bindings.filter { it.parameterId.raw in ids }.sumOf { binding -> binding.forms.count { it != null } }
			}
			for (drawable in model.drawables) { grid(drawable.geometryGrid); channels(drawable.channelGrids); blends(drawable.blendShapes) }
			for (deformer in model.deformers) {
				channels(deformer.channelGrids)
				when (deformer) {
					is org.umamo.runtime.model.Deformer.Warp -> { grid(deformer.geometryGrid); blends(deformer.blendShapes) }
					is org.umamo.runtime.model.Deformer.Rotation -> { grid(deformer.geometryGrid); blends(deformer.blendShapes) }
				}
			}
			for (part in model.parts) { channels(part.channelGrids); blends(part.blendShapes) }
			return ModuleCost(present, keyforms, blendForms, module.physicsGroups.count { it in physicsGroups }, estimated = false)
		}
	}
}
