package io.github.psd2live.core

import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.withDrawablesDeleted
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import io.github.psd2live.core.legacy.RigGenerationFrames
import io.github.psd2live.core.legacy.RigGenerationMigration
import io.github.psd2live.core.legacy.ArtPrimitiveReplay

/**
 * Where an edit changes what the generators make under the journal, the journal is not replayed on the new output:
 * the previous generated rig G, the new one G' and the authored rig M merge ([RigRegeneration]) and the result is
 * stored as a `rig_checkpoint` ([RigCheckpoint]) where the previous journal ends, before the entries the edit adds.
 * Replay then starts there; the entries before it stay as history.
 *
 * After a generation migration the authored rig's meshes hang from renamed copies of the generated deformers
 * ([RigGenerationFrames]); G and G' merge under those names ([RigGenerationFrames.named]), or every mesh would read as
 * one the user re-parented and keep its old place. The checkpoint stores the generation as the generators made it.
 */
internal object RigRegenerationCheckpoint {
	/**
	 * [next] - a configuration whose edits continue [current]'s ([RigEditOverlay.continues]) - with a checkpoint where
	 * [current]'s journal ends, when the generated rig of [source] under [next], as that journal sees it, differs from
	 * [current]'s. Null when it does not: replaying the journal gives what it gave.
	 *
	 * G is [current]'s base as this build generates it, so what a newer generator would make differently for objects
	 * the user left counts as the user's and stays: generator improvements arrive only through [updated]. With
	 * [currentSource] G' is generated from [source] as it is now rather than the document's frozen generation input.
	 */
	fun checkpointed(pipeline: PSD2LivePipeline, current: RigPreviewModel, next: PipelineConfig, source: SourceArt,
	                 checkpoint: () -> Unit = {}, currentSource: Boolean = false): PipelineConfig? {
		val before = current.config.rigEdits
		val after = next.rigEdits
		val boundary = before.authoringJournal.size
		// The edit checkpointed already (a preset that switched the generation itself).
		if (after.authoringJournal.subList(boundary, after.authoringJournal.size).any(RigCheckpoint::isRecord)) return null
		// A change of the generation rules (settings, classification) generates from the source as it is now, layers added
		// since the generation input was frozen included; the meshes rebuilt from a saved input keep it.
		val (base, bindingKey) = if (!currentSource) pipeline.generatedBaseOf(source, next) else
			pipeline.generatedBaseOf(RigGenerationMigration.meshInput(source, next), next.copy(generationSource = null, meshSource = null))
		if (current.sources.baseKnown && base === current.baseRig) return null
		checkpoint()
		// The new generation as the entries up to the boundary see it: later splits' parts not yet in place.
		val records = after.authoringJournal.subList(0, boundary)
		val (previous, seen) = ArtPrimitiveReplay.withVersion1Parts(current.baseRig.resolvedPuppet().reboundTo(base.puppet.atlas, base.puppet.sources),
			current.baseRig.primitiveSkins, base.resolvedPuppet(records.filter(ArtPrimitiveV2::isV2)), base.primitiveSkins, records)
		if (PuppetIr.toIr(previous) == PuppetIr.toIr(seen)) return null
		checkpoint()
		val record = merged(RigGenerationFrames.named(previous, records), RigGenerationFrames.named(seen, records), current.authored, base,
			bindingKey, seen, checkpoint).record
		val journal = ArrayList<JsonObject>(after.authoringJournal).apply { add(boundary, record) }
		return next.copy(rigEdits = after.copy(authoringJournal = journal))
	}

	/**
	 * How a split turns a rig's original drawable into its parts. [split] makes the parts from [model]'s own original -
	 * its geometry, keyforms, channels and bindings - and removes the original, so a part of the generated rig and the
	 * same part of the authored rig differ exactly where the user had changed the original. Null when [model]'s original
	 * cannot be split that way (it is missing, or its vertices are not the ones the split cut); the parts are then the
	 * user's alone. With [user] the result also holds what only the authored rig has: the Glues the split creates.
	 */
	fun interface SplitParts {
		fun split(model: PuppetModel, user: Boolean): PuppetModel?
	}

	/**
	 * [next] - [current]'s configuration with a split's records appended - with a checkpoint after them: the split as a
	 * regeneration. The source now shows the parts instead of [original], so the generators make them (G'); G and the
	 * authored rig M have [original] split by [parts] into the same parts, and the three merge ([RigRegeneration]). A part
	 * the user had not changed is the generated one, the user's changes to the original carry over onto the parts, and
	 * the generators' later changes reach the parts like any generated object. Replay starts after the split: its records
	 * are never replayed. [partLayers] and [partBounds] are each part's source layer and neutral bounds.
	 */
	fun split(pipeline: PSD2LivePipeline, current: RigPreviewModel, next: PipelineConfig, source: SourceArt, original: DrawableId,
	          parts: SplitParts, partLayers: Map<DrawableId, String>, partBounds: Map<DrawableId, Bounds>,
	          checkpoint: () -> Unit = {}): PipelineConfig {
		val earlier = current.config.rigEdits.authoringJournal
		val journal = next.rigEdits.authoringJournal
		require(next.rigEdits.continues(current.config.rigEdits)) { "A split appends to the journal" }
		val (base, bindingKey) = pipeline.generatedBaseOf(source, next)
		checkpoint()
		val atlas = base.puppet.atlas; val sources = base.puppet.sources
		val (previous, seen) = ArtPrimitiveReplay.withVersion1Parts(current.baseRig.resolvedPuppet().reboundTo(atlas, sources), current.baseRig.primitiveSkins,
			base.resolvedPuppet(journal.filter(ArtPrimitiveV2::isV2)), base.primitiveSkins, earlier)
		checkpoint()
		val authored = current.authored
		// Edits of the original's generated cells (a version 2 part split again) are the user's changes to it.
		val overrides = earlier.filter { GeneratedOverrides.isOverride(it) && it["target"]?.jsonPrimitive?.contentOrNull == "mesh:${original.raw}" }
		val m = authored.rig.puppet.let { if (overrides.isEmpty()) it else GeneratedOverrides.applyAll(it, overrides).model }.reboundTo(atlas, sources)
		val splitAuthored = requireNotNull(parts.split(m, user = true)) { "The split original is not in the authored rig: ${original.raw}" }
		val splitGenerated = parts.split(previous, user = false) ?: previous.withDrawablesDeleted(setOf(original))
		checkpoint()
		val ids = partLayers.keys.map { it.raw }
		val rig = authored.rig.copy(puppet = splitAuthored,
			layerIdByDrawableId = authored.rig.layerIdByDrawableId - original.raw + partLayers.entries.associate { (id, layer) -> id.raw to layer },
			sourceBoundsByDrawableId = authored.rig.sourceBoundsByDrawableId - original.raw + partBounds.entries.associate { (id, bounds) -> id.raw to bounds },
			pageByDrawableId = authored.rig.pageByDrawableId - original.raw + splitAuthored.drawables.filter { it.id.raw in ids }.associate { it.id.raw to it.texturePage })
		// A mesh the journal created shows its layer's visibility; its parts show theirs.
		val targets = authored.visibilityTargets.flatMap { (id, target) ->
			if (id != original.raw) listOf(id to target) else partLayers.entries.map { (part, partLayer) -> part.raw to partLayer }
		}
		val record = merged(RigGenerationFrames.named(splitGenerated, earlier), RigGenerationFrames.named(seen, earlier), AuthoredRig(rig, targets),
			base, bindingKey, seen, checkpoint).record
		return next.copy(rigEdits = next.rigEdits.copy(authoringJournal = journal + record))
	}

	/** A regeneration with this build's generators: the new configuration and what its merge reported. */
	class Update(val config: PipelineConfig, val issues: List<RigRegeneration.Issue>)

	/**
	 * [current]'s document regenerated by this build's generators: the generated rig its last checkpoint stores (what
	 * the generators made then), the one they make now for the same input and the authored rig merge, and the result
	 * is checkpointed at the end of the journal. Null when the journal has no checkpoint storing its generated rig (a
	 * document without one is generated by this build on every build already) or the generators make the same rig.
	 */
	fun updated(pipeline: PSD2LivePipeline, current: RigPreviewModel, source: SourceArt, checkpoint: () -> Unit = {}): Update? {
		val overlay = current.config.rigEdits
		val index = overlay.checkpointIndex.takeIf { it >= 0 } ?: return null
		val journal = overlay.authoringJournal
		val stored = RigCheckpoint.generated(journal[index]) ?: return null
		val (base, bindingKey) = pipeline.generatedBaseOf(source, current.config)
		checkpoint()
		val atlas = base.puppet.atlas; val sources = base.puppet.sources
		// The stored generated rig holds the version 1 parts its checkpoint merged; their skins now come from the base.
		val (previous, now) = ArtPrimitiveReplay.withVersion1Parts(stored.authored.rig.puppet.reboundTo(atlas, sources), PrimitiveSkins.None,
			base.resolvedPuppet(journal.subList(0, index).filter(ArtPrimitiveV2::isV2)), base.primitiveSkins, journal.subList(0, index),
			replayOnPrevious = false)
		if (PuppetIr.toIr(previous) == PuppetIr.toIr(now)) return null
		checkpoint()
		val merged = merged(RigGenerationFrames.named(previous, journal), RigGenerationFrames.named(now, journal), current.authored, base, bindingKey,
			base.resolvedPuppet(journal.filter(ArtPrimitiveV2::isV2)), checkpoint)
		return Update(current.config.copy(rigEdits = overlay.copy(authoringJournal = journal + merged.record)), merged.issues)
	}

	private class Merged(val record: JsonObject, val issues: List<RigRegeneration.Issue>)

	/**
	 * The checkpoint of [authored] merged from [previous] onto [next], bound to [base]'s atlas; [generated] is stored as
	 * the generation the checkpoint was made from.
	 */
	private fun merged(previous: PuppetModel, next: PuppetModel, authored: AuthoredRig, base: BuiltRig, bindingKey: String,
	                   generated: PuppetModel, checkpoint: () -> Unit): Merged {
		val result = RigRegeneration.merge(previous, next, authored.rig.puppet.reboundTo(base.puppet.atlas, base.puppet.sources), checkpoint)
		val model = result.model
		val ids = model.drawables.mapTo(HashSet()) { it.id.raw }
		fun <V> joined(generatedMap: Map<String, V>, user: Map<String, V>) = (user + generatedMap).filterKeys { it in ids }
		val rig = base.copy(puppet = model,
			layerIdByDrawableId = joined(base.layerIdByDrawableId, authored.rig.layerIdByDrawableId),
			sourceBoundsByDrawableId = joined(base.sourceBoundsByDrawableId, authored.rig.sourceBoundsByDrawableId),
			pageByDrawableId = model.drawables.filter { it.id.raw in base.pageByDrawableId || it.id.raw in authored.rig.pageByDrawableId }
				.associate { it.id.raw to it.texturePage },
			supersededEntryNotes = authored.rig.supersededEntryNotes, overrideIssues = emptyList())
		val record = RigCheckpoint.encode(AuthoredRig(rig, authored.visibilityTargets.filter { it.first in ids }), bindingKey, result.issues,
			generated = AuthoredRig(base.copy(puppet = generated), emptyList()))
		return Merged(record, result.issues)
	}
}
