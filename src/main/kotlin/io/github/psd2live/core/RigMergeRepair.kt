package io.github.psd2live.core

import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel

/**
 * Regeneration merges an earlier build made differently, redone with this one's (see the materialized rig design,
 * section 11). A checkpoint stores both sides a regeneration merged - the generated rigs before and after, in the
 * checkpoints - and what the merge made of the authored rig; merging the same inputs again shows whether this build's
 * merge agrees. Where it does not, the merge is redone, and its correction - from what the checkpoint stored to what the
 * merge makes now - is carried through every later regeneration and onto the current authored rig by the same three-way
 * merge: the later edits stay, and what the old merge left behind follows the generators as the new one would have.
 *
 * Agreement is structural - each object's parent, each mesh's vertex count, keyform axes and blend shapes - so values a
 * rebuild reproduces only to rounding never count as a disagreement.
 */
internal object RigMergeRepair {
	class Repair(val authored: PuppetModel, val issues: List<RigRegeneration.Issue>)

	/**
	 * [current], the authored rig at the end of [overlay]'s journal, with the corrections of every regeneration checkpoint
	 * this build merges differently, all rigs bound to [atlas] and [sources]; null when it merges every one alike, or
	 * the corrections leave [current]'s structure as it is (a repair carried them already).
	 * [named] gives a generated rig the deformer names the journal entries before a checkpoint gave it, as the
	 * regeneration that wrote the checkpoint merged it (a generation migration's frames).
	 */
	fun repaired(overlay: RigEditOverlay, current: PuppetModel, atlas: PuppetAtlas, sources: List<ArtSource>,
	             named: (PuppetModel, List<kotlinx.serialization.json.JsonObject>) -> PuppetModel = { model, _ -> model },
	             checkpoint: () -> Unit = {}): Repair? {
		val journal = overlay.authoringJournal
		fun bound(model: PuppetModel) = model.reboundTo(atlas, sources)
		val generated = journal.indices.filter { RigCheckpoint.isRecord(journal[it]) && RigCheckpoint.generated(journal[it]) != null }
		var correction: Pair<PuppetModel, PuppetModel>? = null
		val issues = ArrayList<RigRegeneration.Issue>()
		for ((previousIndex, index) in generated.zipWithNext()) {
			checkpoint()
			val before = requireNotNull(RigCheckpoint.generated(journal[previousIndex])).authored.rig.puppet
			val after = requireNotNull(RigCheckpoint.generated(journal[index])).authored.rig.puppet
			if (PuppetIr.toIr(before) == PuppetIr.toIr(after)) continue
			// A checkpoint a repair wrote holds every correction before it: the rig is right from there on.
			if (RigCheckpoint.issues(journal[index]).any { it.kind == RigRegeneration.IssueKind.REMERGED }) { correction = null; issues.clear(); continue }
			val stored = bound(RigCheckpoint.decode(journal[index]).authored.rig.puppet)
			// A split merges its own way (the original cut into parts on both sides): only a correction carries over it.
			val split = journal.getOrNull(index - 1)?.get("op")?.jsonPrimitive?.contentOrNull == ArtPrimitiveJournal.OP
			val input = if (split) null else overlay.copy(authoringJournal = journal.subList(0, index)).authoredFromCheckpoint()?.first?.rig?.puppet?.let(::bound)
			if (input == null) {
				correction?.let { (old, new) -> correction = stored to RigRegeneration.merge(old, new, stored, checkpoint).model }
				continue
			}
			val corrected = correction?.let { (old, new) -> RigRegeneration.merge(old, new, input, checkpoint).model } ?: input
			val records = journal.subList(0, index)
			val merged = RigRegeneration.merge(named(bound(before), records), named(bound(after), records),
				corrected, checkpoint)
			val now = structure(merged.model); val then = structure(stored)
			if (correction == null && now == then) continue
			val differing = (now.keys + then.keys).filter { now[it] != then[it] }.sorted()
			issues += RigRegeneration.Issue(RigRegeneration.IssueKind.REMERGED, "journal:$index",
				differing.take(8).joinToString(", ") + if (differing.size > 8) ", +${differing.size - 8} more" else "")
			issues += merged.issues
			correction = stored to merged.model
		}
		val (old, new) = correction ?: return null
		val rig = bound(current)
		val result = RigRegeneration.merge(old, new, rig, checkpoint)
		// The stale checkpoints stay in the journal; once a repair carried their correction, there is nothing left to do.
		if (structure(result.model) == structure(rig)) return null
		return Repair(result.model, issues + result.issues)
	}

	/** [stale]'s last answer, by model identity: a repair check merges again, so a model asks it once. */
	@Volatile private var checked: Pair<RigPreviewModel, List<RigRegeneration.Issue>>? = null

	/**
	 * The regeneration checkpoints of [model]'s journal that this build merges differently and whose correction would
	 * change its rig - what "update generated rig" would repair - as `remerged` issues; empty when there is nothing to
	 * repair, or the model has no generated rig to compare (an imported CMO3 model, a journal with fewer than two
	 * checkpoints storing one). The answer is kept per model.
	 */
	fun stale(model: RigPreviewModel, named: (PuppetModel, List<kotlinx.serialization.json.JsonObject>) -> PuppetModel = { m, _ -> m },
	          checkpoint: () -> Unit = {}): List<RigRegeneration.Issue> {
		checked?.let { (seen, answer) -> if (seen === model) return answer }
		val overlay = model.config.rigEdits
		val found = if (overlay.importedCmo3 != null ||
			overlay.authoringJournal.count { RigCheckpoint.isRecord(it) && RigCheckpoint.generated(it) != null } < 2) emptyList()
		else repaired(overlay, model.authoredPuppet(overlay), model.rig.puppet.atlas, model.rig.puppet.sources, named, checkpoint)
			?.issues?.filter { it.kind == RigRegeneration.IssueKind.REMERGED }.orEmpty()
		checked = model to found
		return found
	}

	/** What a merge decides about each object's place and shape, without the values a rebuild reproduces only to rounding. */
	fun structure(model: PuppetModel): Map<String, Any?> = buildMap {
		for (deformer in model.deformers) put("deformer:${deformer.id.raw}", deformer.parent?.raw)
		for (drawable in model.drawables) put("mesh:${drawable.id.raw}", listOf(drawable.parentDeformerId?.raw, drawable.mesh?.vertexCount,
			drawable.geometryGrid?.axes.orEmpty().map { it.parameterId.raw }.sorted(), drawable.blendShapes.map { it.parameterId.raw }.sorted()))
	}
}
