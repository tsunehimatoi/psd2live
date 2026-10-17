package io.github.psd2live.core.interior

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.Feature
import io.github.psd2live.format.compile.Handling
import io.github.psd2live.format.compile.LossEntry
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Effect removal and export hygiene. Shared source parameters are never owned by this cleanup. */
internal object InteriorLifecycle {
	fun referenced(
		model: PuppetModel,
		physics: List<RigPhysicsEdit>,
		overlay: RigEditOverlay,
	): Set<String> = buildSet {
		fun channels(grids: ChannelGrids) {
			grids.gridsByChannel.values.forEach { grid ->
				grid.axes.forEach { add(it.parameterId.raw) }
			}
		}
		fun <F : Any> blends(bindings: List<BlendShapeBinding<F>>) {
			bindings.forEach { binding ->
				add(binding.parameterId.raw)
				binding.limits.forEach { add(it.parameterId.raw) }
			}
		}
		for (d in model.deformers) {
			channels(d.channelGrids)
			when (d) {
				is Deformer.Warp -> {
					d.geometryGrid?.axes?.forEach { add(it.parameterId.raw) }
					blends(d.blendShapes)
				}
				is Deformer.Rotation -> {
					d.geometryGrid?.axes?.forEach { add(it.parameterId.raw) }
					blends(d.blendShapes)
				}
			}
		}
		for (d in model.drawables) {
			d.geometryGrid?.axes?.forEach { add(it.parameterId.raw) }
			channels(d.channelGrids)
			blends(d.blendShapes)
		}
		for (part in model.parts) {
			channels(part.channelGrids)
			blends(part.blendShapes)
		}
		model.glues.forEach { channels(it.channelGrids) }
		model.parameterLinks.forEach {
			add(it.horizontal.raw)
			add(it.vertical.raw)
		}
		physics.forEach { addAll(it.parameters) }
		overlay.motionClips.forEach { clip -> clip.curves.forEach { add(it.parameterId) } }
		overlay.interiorEffects
			.filter { it.enabled && it.issue(model) == null }
			.forEach { effect -> addAll(effect.sources.map { it.id.raw }) }
	}

	/**
	 * Remove only this feature's unused definitions in an exported copy, never from the editor
	 * document.
	 */
	fun exportModel(
		model: PuppetModel,
		overlay: RigEditOverlay,
		physics: List<RigPhysicsEdit>,
	): PuppetModel {
		val candidates =
			overlay.interiorEffects
				.filter { !it.enabled || it.issue(model) != null }
				.flatMapTo(HashSet()) { it.parameterIds }
		val removed = candidates - referenced(model, physics, overlay)
		if (removed.isEmpty()) return model
		fun tree(nodes: List<ParameterNode>): List<ParameterNode> = nodes.mapNotNull { node ->
			when (node) {
				is ParameterNode.Param -> node.takeIf { it.id.raw !in removed }
				is ParameterNode.Group -> node.copy(children = tree(node.children))
			}
		}
		return model.copy(
			parameters = model.parameters.filter { it.id.raw !in removed },
			parameterTree = tree(model.parameterTree),
			parameterLinks =
				model.parameterLinks.filter {
					it.horizontal.raw !in removed && it.vertical.raw !in removed
				},
		)
	}

	fun warnings(model: PuppetModel, overlay: RigEditOverlay): List<String> =
		overlay.interiorEffects
			.filter { it.enabled }
			.mapNotNull { effect ->
				effect.issue(model)?.let { tr("interior.export.skipped", effect.name, tr(it)) }
			}

	fun losses(model: PuppetModel, overlay: RigEditOverlay): List<LossEntry> =
		overlay.interiorEffects
			.filter { it.enabled }
			.mapNotNull { effect ->
				effect.issue(model)?.let {
					LossEntry(
						effect.target,
						Feature.BLEND_SHAPES,
						Handling.DROPPED,
						note = tr("interior.export.skipped", effect.name, tr(it)),
					)
				}
			}

	fun delete(preview: RigPreviewModel, overlay: RigEditOverlay, target: String): RigEditOverlay {
		val effect =
			overlay.interiorEffects.singleOrNull { it.target == target.removePrefix("mesh:") }
				?: return overlay
		val groups = effect.groups.mapTo(HashSet()) { it.id }
		var next =
			overlay.copy(
				interiorEffects = overlay.interiorEffects.filterNot { it.id == effect.id },
				physicsEdits = overlay.physicsEdits.filterNot { it.id in groups },
				disabledPhysicsIds = overlay.disabledPhysicsIds - groups,
				physicsOrder = overlay.physicsOrder.filterNot { it in groups },
				authoringJournal =
					overlay.authoringJournal.filterNot {
						GeneratedOverrides.isOverride(it) &&
							it["generator"]?.jsonPrimitive?.contentOrNull ==
								"interiorEffect:${effect.id}"
					},
			)
		val authored = preview.authored.rig.puppet
		val definitions =
			effect.definitions.filter { p -> authored.parameters.none { it.id == p.id } }
		val probe =
			next
				.copy(interiorEffects = next.interiorEffects + effect.enabled(false))
				.finish(authored.copy(parameters = authored.parameters + definitions))
				.model
		val used =
			referenced(probe, next.physicsEdits + next.interiorEffects.flatMap { it.groups }, next)
		val retained = definitions.filter { it.id.raw in used }
		val panelIds = effect.parameterIds.filter { it in used }
		if (retained.isNotEmpty() || panelIds.isNotEmpty()) {
			val create = buildJsonObject {
				put("op", "structure")
				put(RigEditOverlay.PANEL_AFTER_GENERATION, JsonArray(panelIds.map(::JsonPrimitive)))
				putJsonArray("edits") {
					retained.forEach { p ->
						add(
							buildJsonObject {
								put("action", "create")
								put("kind", "parameter")
								put("id", p.id.raw)
								put("name", p.name)
								put("min", p.min)
								put("max", p.max)
								put("default", p.default)
								put("parameter_kind", p.kind.name)
								put("repeat", p.repeat)
							}
						)
					}
				}
			}
			next = next.copy(authoringJournal = next.authoringJournal + create)
		}
		return next
	}
}
