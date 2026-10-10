package io.github.psd2live.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.umamo.edit.Pose
import org.umamo.edit.channelValueAt
import org.umamo.edit.geometryGridOf
import org.umamo.edit.withChannelKeyCaptured
import org.umamo.edit.withChannelKeyRemovedAt
import org.umamo.edit.withGeometryKeyRemoved
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterDeleted
import org.umamo.edit.withParameterRange
import org.umamo.edit.withParametersSyncedFromTree
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.RotationPivotInterpolator
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.keyIndexAt
import org.umamo.runtime.keyform.withAxisCollapsed
import org.umamo.runtime.keyform.withAxisSeeded
import org.umamo.runtime.keyform.withFormCaptured
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.edit.blendParametersIn
import org.umamo.edit.gridCoordinateOf
import org.umamo.edit.removeBlendBinding
import org.umamo.edit.removeBlendKey
import org.umamo.edit.withBlendShapeCaptured
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.channelGridsOf
import org.umamo.runtime.model.withDerivedRenderRoot
import io.github.psd2live.core.legacy.RigGenerationJournal
import io.github.psd2live.core.legacy.RigGenerationFrames
import io.github.psd2live.core.legacy.RigGenerationScaffold
import io.github.psd2live.core.legacy.RigMeshActivation
import io.github.psd2live.core.legacy.SupersededEntryNote
import io.github.psd2live.core.legacy.StubTolerance
import io.github.psd2live.core.legacy.ReplayCheckpoints

/**
 * A durable, replayable edit to one parameter. The complete desired value is stored instead of a
 * sequence of UI gestures, so rebuilding meshes or the generated base rig cannot silently lose it.
 */
data class RigParameterEdit(
	val id: String,
	val name: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val kind: ParameterKind = ParameterKind.NORMAL,
	val repeat: Boolean = false,
	/** True when this parameter did not exist in the generated base rig at creation time. */
	val created: Boolean = false,
) {
	init {
		require(id.isNotBlank() && id.none(Char::isISOControl)) { "Parameter ID must not be blank or contain control characters" }
		require(name.isNotBlank() && name.none(Char::isISOControl)) { "Parameter name must not be blank or contain control characters" }
		require(min.isFinite() && max.isFinite() && default.isFinite()) { "Parameter range values must be finite" }
		require(min < max) { "Parameter minimum must be less than maximum" }
		require(default in min..max) { "Parameter default must be within its range" }
	}

	fun asParameter(): Parameter = Parameter(ParameterId(id), name.trim(), min, max, default, kind, repeat)
}

enum class RigTargetKind {
	ART_MESH,
	WARP_DEFORMER,
	ROTATION_DEFORMER,
	PART,
	GLUE;

	companion object {
		fun fromString(raw: String): RigTargetKind = when (raw.trim().lowercase()) {
			"art_mesh", "artmesh", "drawable", "mesh" -> ART_MESH
			"warp_deformer", "warp", "warpdeformer" -> WARP_DEFORMER
			"rotation_deformer", "rotation", "rotationdeformer" -> ROTATION_DEFORMER
			"part" -> PART
			"glue" -> GLUE
			else -> throw IllegalArgumentException("Unknown target kind: $raw")
		}
	}
}

data class RigTargetRef(
	val kind: RigTargetKind,
	val id: String,
	val secondaryId: String? = null,
	val glueId: String? = null,
) {
	init {
		require(id.isNotBlank()) { "Target ID must not be blank" }
		if (kind == RigTargetKind.GLUE) {
			require(!secondaryId.isNullOrBlank()) { "Glue target requires secondaryId (meshB)" }
		}
	}

	fun asKeyformOwner(): KeyformOwner = when (kind) {
		RigTargetKind.ART_MESH -> KeyformOwner.Drawable(DrawableId(id))
		RigTargetKind.WARP_DEFORMER, RigTargetKind.ROTATION_DEFORMER -> KeyformOwner.Deformer(DeformerId(id))
		RigTargetKind.PART -> KeyformOwner.Part(PartId(id))
		RigTargetKind.GLUE -> KeyformOwner.Glue(DrawableId(id), DrawableId(secondaryId ?: id), glueId)
	}
}

data class RigKeyformGeometryEdit(
	val controlPoints: List<Float>? = null,
	val originX: Float? = null,
	val originY: Float? = null,
	val angle: Float? = null,
	val scale: Float? = null,
	val positionDeltas: List<Float>? = null,
) {
	init {
		controlPoints?.forEach { require(it.isFinite()) { "Control point values must be finite" } }
		positionDeltas?.forEach { require(it.isFinite()) { "Position delta values must be finite" } }
		originX?.let { require(it.isFinite()) { "originX must be finite" } }
		originY?.let { require(it.isFinite()) { "originY must be finite" } }
		angle?.let { require(it.isFinite()) { "angle must be finite" } }
		scale?.let { require(it.isFinite()) { "scale must be finite" } }
	}
}

typealias RigKeyformChannelsEdit = org.umamo.edit.KeyformChannelsEdit

data class RigKeyformSetEdit(
	val target: RigTargetRef,
	val coordinate: Map<String, Float>,
	val geometry: RigKeyformGeometryEdit? = null,
	val channels: RigKeyformChannelsEdit? = null,
) {
	init {
		require(coordinate.isNotEmpty()) { "Keyform coordinate must not be empty" }
		require(coordinate.keys.all { it.isNotBlank() }) { "Parameter IDs in coordinate must not be blank" }
		require(coordinate.values.all { it.isFinite() }) { "Coordinate values must be finite" }
		require(geometry != null || channels != null) { "Keyform set edit must specify geometry or channels" }
	}
}

data class RigKeyformDeleteEdit(
	val target: RigTargetRef,
	val parameterId: String,
	val keyValue: Float? = null,
	val channel: String? = null,
) {
	init {
		require(parameterId.isNotBlank()) { "Parameter ID must not be blank" }
		require(keyValue == null || keyValue.isFinite()) { "Key value must be finite" }
	}
}

data class RigKeyformCopyEdit(
	val sourceTarget: RigTargetRef,
	val sourceCoordinate: Map<String, Float>,
	val destinationTarget: RigTargetRef = sourceTarget,
	val destinationCoordinate: Map<String, Float>,
	val channels: List<String>? = null,
) {
	init {
		require(sourceCoordinate.isNotEmpty() && destinationCoordinate.isNotEmpty()) {
			"Source and destination coordinates must not be empty"
		}
	}
}

/**
 * Authoritative rig customization applied after every deterministic base-rig build. This value is
 * included in Agent history snapshots and export configuration.
 */
/** The rig after the legacy edits and the journal ([RigEditOverlay.replayAuthored]), with the entries that replay skipped. */
/** The state a journal replay carries: the model and the entries skipped so far. */
internal class ReplayState(val model: PuppetModel, val notes: List<SupersededEntryNote>)

internal class AuthoredReplay(val model: PuppetModel, val notes: List<SupersededEntryNote>)

data class RigEditOverlay(
	/** Embedded CMO3 baseline; imported rigs rebuild from this instead of generating a PSD rig. */
	val importedCmo3: String? = null,
	val importedLayerIds: Map<String, String> = emptyMap(),
	/** Null means no skeleton has been authored yet; [SkeletonSpec.Disabled] is an explicit opt-out. */
	val skeleton: SkeletonSpec? = null,
	val parameterEdits: List<RigParameterEdit> = emptyList(),
	val deletedParameterIds: Set<String> = emptySet(),
	val keyformSetEdits: List<RigKeyformSetEdit> = emptyList(),
	val keyformDeleteEdits: List<RigKeyformDeleteEdit> = emptyList(),
	val keyformCopyEdits: List<RigKeyformCopyEdit> = emptyList(),
    val warpEdits: List<RigWarpEdit> = emptyList(),
    /** The user's physics groups; one with a generated group's ID replaces it. */
    val physicsEdits: List<RigPhysicsEdit> = emptyList(),
    /** Generated and user physics groups turned off; the hair and eye presets use their own settings. */
    val disabledPhysicsIds: Set<String> = emptySet(),
    /**
     * Evaluation order of physics groups by ID; Cubism runs groups in order and a later group reads an
     * earlier one's outputs within a step. Groups not listed keep their catalog place after the listed ones.
     */
    val physicsOrder: List<String> = emptyList(),
    /**
     * The project's one frame rate: the preview, the parameters it reports and physics all step at it, and
     * the model declares it (physics3.json `Fps`, CMO3 physics FPS). [UNLIMITED_FPS] follows the display and
     * declares none, so a runtime steps physics with its own frames.
     */
    val physicsFps: Int = DEFAULT_PHYSICS_FPS,
    /** Regenerating sways; replayed after the journal so a changed setting rebuilds their forms. */
    val swingEdits: List<RigSwingEdit> = emptyList(),
    val assetLayers: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
    val calibrationLayerIds: Set<String> = emptySet(),
    /** Source layers active before the first mesh split; preserves the generated Warp frames on rebuild. */
    val splitBaselineLayerIds: Set<String> = emptySet(),
    /** Committed drawable ids, including formally named split pieces, preserved across rebuilds. */
    val splitDrawableIds: Map<String, String> = emptyMap(),
    val structureEdits: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    /** New authoring commands replay in actual order, after the legacy baseline. */
    val authoringJournal: List<kotlinx.serialization.json.JsonObject> = emptyList(),
    /** Authored motions and overrides of the generated ones; they do not touch the rig. */
    val motionClips: List<MotionClip> = emptyList(),
    /** How the user tuned each generated motion, by name, and which ones they deleted. */
    val motionPresets: Map<String, MotionPresetSettings> = emptyMap(),
    /** Simulated bodies; they read the rebuilt rig and, once baked, write back through their own generator. */
    val simEdits: List<io.github.psd2live.core.sim.RigSimEdit> = emptyList(),
) {
	init {
        require(motionClips.map { it.id }.distinct().size == motionClips.size) { "Duplicate motion IDs" }
        require(motionClips.mapNotNull { it.builtin?.lowercase() }.let { it.distinct().size == it.size }) { "A generated motion has one override" }
		require(warpEdits.map { it.id }.distinct().size == warpEdits.size) { "Duplicate Warp IDs" }
        require(physicsEdits.map { it.id }.distinct().size == physicsEdits.size) { "Duplicate physics IDs" }
        require(validFps(physicsFps)) { "FPS must be $UNLIMITED_FPS (unlimited) or within $PHYSICS_FPS_RANGE" }
        require(swingEdits.map { it.id }.distinct().size == swingEdits.size) { "Duplicate swing IDs" }
        require(simEdits.map { it.id }.distinct().size == simEdits.size) { "Duplicate simulation IDs" }
        require(simEdits.flatMap { it.outputParameters }.let { it.distinct().size == it.size }) { "Each simulation needs its own parameters" }
        require(swingEdits.flatMap { it.parameterIds }.let { it.distinct().size == it.size }) { "Each swing needs its own parameters" }
		require(parameterEdits.map(RigParameterEdit::id).distinct().size == parameterEdits.size) {
			"Rig parameter edits contain duplicate IDs"
		}
		require(parameterEdits.none { it.id in deletedParameterIds }) {
			"A parameter cannot be both edited and deleted"
		}
	}

	/**
	 * Replays the overlay on [base]. [skins] are the split parts the skeleton skinned with that base rig
	 * ([BuiltRig.primitiveSkins]); their records place them as skinned.
	 */
	fun applyTo(base: PuppetModel, skins: PrimitiveSkins = PrimitiveSkins.None): PuppetModel =
		replay(base, authoredOnly = false, skins).model

	/** [applyTo], with what the generated overrides could not apply as recorded. */
	internal fun applyToReporting(base: PuppetModel, skins: PrimitiveSkins = PrimitiveSkins.None): GeneratedOverrides.Outcome =
		replay(base, authoredOnly = false, skins)

	/**
	 * The rig after the legacy edits and the whole journal, before any swing or simulation writes its
	 * keyforms: the authored state a split materializes into its parts.
	 */
	fun authored(base: PuppetModel, skins: PrimitiveSkins = PrimitiveSkins.None): PuppetModel = replay(base, authoredOnly = true, skins).model

	/** [authored] on [rig]'s base, with the parts its skeleton skinned. */
	fun authored(rig: BuiltRig): PuppetModel = authored(rig.puppet, rig.primitiveSkins)

	private fun replay(base: PuppetModel, authoredOnly: Boolean, skins: PrimitiveSkins): GeneratedOverrides.Outcome {
		val authored = replayAuthored(base, skins)
		if (authoredOnly) return GeneratedOverrides.Outcome(authored.model, emptyList(), authored.notes)
		return finish(authored.model, authored.notes)
	}

	/** The index of the journal's last `rig_checkpoint` record, where replay starts; -1 without one. */
	internal val checkpointIndex: Int by lazy { RigCheckpoint.latest(authoringJournal) }

	/** The journal entries replay applies: those after the last checkpoint, else all. */
	internal val afterCheckpoint: List<kotlinx.serialization.json.JsonObject>
		get() = if (checkpointIndex < 0) authoringJournal else authoringJournal.subList(checkpointIndex + 1, authoringJournal.size)

	/**
	 * Whether building this overlay's authored state replays what only older builds wrote: after the last checkpoint, a
	 * split record (whose parts the generated base holds), a generation migration or a legacy partition; without a
	 * checkpoint, also the legacy static edits. Edits that continue such an overlay checkpoint its authored rig first
	 * ([RigCheckpoint]), so these replay once and no more.
	 */
	internal val replaysLegacy: Boolean by lazy {
		(checkpointIndex < 0 && (parameterEdits.isNotEmpty() || deletedParameterIds.isNotEmpty() || warpEdits.isNotEmpty() ||
			structureEdits.isNotEmpty() || keyformSetEdits.isNotEmpty() || keyformCopyEdits.isNotEmpty() || keyformDeleteEdits.isNotEmpty())) ||
			afterCheckpoint.any { it["op"]?.jsonPrimitive?.contentOrNull in LEGACY_OPS }
	}

	/**
	 * Whether an edit that adds entries to this journal checkpoints its authored rig first, where the journal ends, so a
	 * build of the result - any edit of the entries after the checkpoint included - replays at most
	 * [CHECKPOINT_INTERVAL] entries and the edit's own, without the base: the journal replays legacy records
	 * ([replaysLegacy]), has no checkpoint yet, or has [CHECKPOINT_INTERVAL] entries after its last.
	 */
	internal val checkpointsBeforeEntries: Boolean
		get() = replaysLegacy || checkpointIndex < 0 || afterCheckpoint.size >= CHECKPOINT_INTERVAL

	/** One journal entry replayed onto [state]; an entry that only addresses parts a later split supersedes is skipped and noted. */
	private fun replayEntry(state: ReplayState, command: kotlinx.serialization.json.JsonObject, skins: PrimitiveSkins): ReplayState {
		val model = state.model
		return try {
			ReplayState(when (command["op"]?.jsonPrimitive?.contentOrNull) {
				"structure" -> RigStructureEdits.replay(model, command.getValue("edits").jsonArray.map { it.jsonObject }.filterNot(::generatedPanelEdit))
				GeneratedOverrides.OP -> model
				else -> RigAuthoringJournal.replay(model, command, skins)
			}, state.notes)
		} catch (failure: java.util.concurrent.CancellationException) {
			throw failure
		} catch (failure: RuntimeException) {
			val stubs = stubTolerance[command] ?: throw failure
			if (!StubTolerance.onlyStubs(model, command, stubs)) throw failure
			ReplayState(model, state.notes + SupersededEntryNote(authoringJournal.indexOfFirst { it === command },
				command["op"]?.jsonPrimitive?.contentOrNull.orEmpty(), StubTolerance.targets(model, command, stubs).sorted(),
				failure.message ?: failure.javaClass.simpleName))
		}
	}

	/** Generated axes do not exist until swing/simulation materialization: panel moves and links that name them. */
	private val generatedIds: Set<String> by lazy { swingEdits.flatMap { it.parameterIds }.toSet() + simEdits.flatMap { it.outputParameters } }

	private fun generatedPanelEdit(edit: kotlinx.serialization.json.JsonObject): Boolean =
		edit["kind"]?.jsonPrimitive?.contentOrNull == "parameter" &&
			edit["action"]?.jsonPrimitive?.contentOrNull in setOf("move", "link") &&
			listOf("id", "partner_id", "before_id").any { field ->
				edit[field]?.jsonPrimitive?.contentOrNull in generatedIds
			}

	/**
	 * The authored rig: the legacy edits and the journal replayed on [base], before any swing, simulation or override
	 * writes its keyforms - the state [finish] completes. [skins] are the split parts the skeleton skinned with [base].
	 */
	internal fun replayAuthored(base: PuppetModel, skins: PrimitiveSkins = PrimitiveSkins.None): AuthoredReplay {
		// From the last checkpoint: its stored rig, bound to the base's atlas, then the entries after it.
		if (checkpointIndex >= 0) {
			val record = authoringJournal[checkpointIndex]
			val start = RigCheckpoint.authoredOn(record, base)
			val notes = RigCheckpoint.decode(record).authored.rig.supersededEntryNotes
			// At most [CHECKPOINT_INTERVAL] entries and the edit's own follow a checkpoint: they replay in full.
			var state = ReplayState(start, notes)
			for (command in afterCheckpoint) state = replayEntry(state, command, skins)
			return AuthoredReplay(state.model, state.notes)
		}
		// A journal without a checkpoint: one older builds wrote, or a new document's before its first edit checkpoints it.
		val earlyStructureEdits = structureEdits.filterNot(::generatedPanelEdit)
		// Everything the legacy stage reads, and what decides how the journal's structure edits split.
		val legacy = ReplayCheckpoints.Legacy(listOf(deletedParameterIds, parameterEdits, warpEdits, structureEdits,
			keyformSetEdits, keyformCopyEdits, keyformDeleteEdits, generatedIds, io.github.psd2live.i18n.I18n.currentLanguage.tag, skins))
		// The notes ride in the checkpointed state, so a replay resumed from a checkpoint still reports every entry.
		val replayed = ReplayCheckpoints.replay(base, legacy, authoringJournal, start = {
			var model = base
			// 1. Delete removed parameters
			for (id in deletedParameterIds.sorted()) model = model.withParameterDeleted(ParameterId(id))
			// 2. Replay parameter creations and range/name updates
			for (edit in parameterEdits) {
				val id = ParameterId(edit.id)
				if (model.parameters.none { it.id == id }) {
					model = model.withParameterCreated(id, edit.name, edit.kind)
				}
				model = model.withParameterRange(id, edit.min, edit.default, edit.max)
				val desired = edit.asParameter()
				model = model.copy(parameters = model.parameters.map { current -> if (current.id == id) desired else current })
			}
			val journalWarpIds = structureEdits.filter { it["action"]?.jsonPrimitive?.contentOrNull == "create_warp" }.map { it.getValue("id").jsonPrimitive.content }.toSet()
			for (warp in warpEdits) if (warp.id !in journalWarpIds) model = warp.applyTo(model)
			model = RigStructureEdits.replay(model, earlyStructureEdits)
			// 3. Apply keyform sets
			for (set in keyformSetEdits) {
				model = applyKeyformSet(model, set)
			}
			// 4. Apply keyform copies
			for (copy in keyformCopyEdits) {
				model = applyKeyformCopy(model, copy)
			}
			// 5. Apply keyform deletions
			for (delete in keyformDeleteEdits) {
				model = applyKeyformDelete(model, delete)
			}
			ReplayState(model, emptyList())
		}) { state, command -> replayEntry(state, command, skins) }
		return AuthoredReplay(replayed.model, replayed.notes)
	}

	/**
	 * The authored state from the journal's last checkpoint without the base: its stored rig and the entries after it,
	 * with the binding key of the atlas it is bound to. Null without a checkpoint, or when an entry after it needs the
	 * base (an `art_primitive` record places parts the base generated, a mesh creation samples artwork the stored
	 * atlas has no tile for).
	 */
	internal fun authoredFromCheckpoint(): Pair<AuthoredRig, String>? {
		if (checkpointIndex < 0) return null
		val after = afterCheckpoint
		if (after.any { it["op"]?.jsonPrimitive?.contentOrNull == ArtPrimitiveJournal.OP }) return null
		val stored = RigCheckpoint.decode(authoringJournal[checkpointIndex])
		var model = stored.authored.rig.puppet
		// A mesh created on artwork added after the checkpoint (an import a generation migration materializes)
		// samples a tile the stored atlas lacks; only the full build has it.
		val tiles = model.atlas.tiles.mapNotNullTo(HashSet()) { tile -> tile.source?.let { it.sourceId.raw to it.layerKey } }
		if (after.any { it["op"]?.jsonPrimitive?.contentOrNull == RasterMeshCreation.OP &&
				(it["source_id"]?.jsonPrimitive?.contentOrNull to it["source"]?.jsonPrimitive?.contentOrNull) !in tiles }) return null
		var notes = stored.authored.rig.supersededEntryNotes
		for (command in after) replayEntry(ReplayState(model, notes), command, PrimitiveSkins.None).let { model = it.model; notes = it.notes }
		return AuthoredRig(stored.authored.rig.copy(puppet = model, supersededEntryNotes = notes), stored.authored.visibilityTargets)
			.withJournalMeshes(after) to stored.bindingKey
	}

	/**
	 * Whether this overlay continues [previous]: its journal starts with [previous]'s and everything else the legacy
	 * stage reads is equal - what a regeneration checkpoint between the two journals needs ([RigRegenerationCheckpoint]).
	 * Past a checkpoint in that shared start nothing reads the legacy fields, so they may differ.
	 */
	internal fun continues(previous: RigEditOverlay): Boolean {
		if (previous === this) return true
		val journal = authoringJournal
		val before = previous.authoringJournal
		if (journal.size < before.size || (0 until before.size).any { journal[it] !== before[it] && journal[it] != before[it] }) return false
		return previous.checkpointIndex >= 0 || sameLegacyStage(previous)
	}

	/** Whether the static fields only a journal without a checkpoint replays are [previous]'s. */
	private fun sameLegacyStage(previous: RigEditOverlay) =
		deletedParameterIds == previous.deletedParameterIds && parameterEdits == previous.parameterEdits && warpEdits == previous.warpEdits &&
			structureEdits == previous.structureEdits && keyformSetEdits == previous.keyformSetEdits && keyformCopyEdits == previous.keyformCopyEdits &&
			keyformDeleteEdits == previous.keyformDeleteEdits

	/**
	 * Whether this overlay's authored state is [previous]'s with entries added to the journal ([appendedTo]): the
	 * journal starts with [previous]'s, everything else the authored stage reads is equal (the legacy fields only
	 * without a checkpoint), and no added entry is an `art_primitive` record.
	 */
	internal fun extends(previous: RigEditOverlay): Boolean {
		if (previous === this) return true
		val journal = authoringJournal
		val before = previous.authoringJournal
		if (journal.size < before.size || (0 until before.size).any { journal[it] !== before[it] && journal[it] != before[it] }) return false
		if ((previous.checkpointIndex < 0 && !sameLegacyStage(previous)) || generatedIds != previous.generatedIds) return false
		return (before.size until journal.size).none { journal[it]["op"]?.jsonPrimitive?.contentOrNull in setOf(ArtPrimitiveJournal.OP, RigCheckpoint.OP) }
	}

	/**
	 * [authored] - [previous]'s authored state - with the journal entries this overlay has past [previous]'s: the
	 * authored state of this overlay, as [replayAuthored] would give it from the base, without the base. Null when
	 * this overlay differs from [previous] in anything else the authored stage reads, or an added entry needs the base
	 * (an `art_primitive` record places parts the skeleton skinned with it).
	 */
	internal fun appendedTo(previous: RigEditOverlay, authored: AuthoredRig): AuthoredRig? {
		if (previous === this) return authored
		if (!extends(previous)) return null
		val added = authoringJournal.subList(previous.authoringJournal.size, authoringJournal.size)
		var model = authored.rig.puppet
		for (command in added) model = when (command["op"]?.jsonPrimitive?.contentOrNull) {
			"structure" -> RigStructureEdits.replay(model, command.getValue("edits").jsonArray.map { it.jsonObject }.filterNot(::generatedPanelEdit))
			GeneratedOverrides.OP -> model
			else -> RigAuthoringJournal.replay(model, command)
		}
		return AuthoredRig(authored.rig.copy(puppet = model), authored.visibilityTargets).withJournalMeshes(added)
	}

	/**
	 * [authored] - this overlay's [replayAuthored] output - completed: swings and simulations write their keyforms, edits
	 * of generated keyforms merge, and panel edits of generated parameters apply. [notes] pass through to the outcome.
	 */
	internal fun finish(authored: PuppetModel, notes: List<SupersededEntryNote> = emptyList()): GeneratedOverrides.Outcome {
		// Generated axes exist only now: their panel placement and links replay afterwards, including moves of
		// another parameter relative to them.
		val generatedPanelEdits = structureEdits.filter(::generatedPanelEdit)
		val deferredJournalEdits = mutableListOf<kotlinx.serialization.json.JsonObject>()
		for (command in authoringJournal) if (command["op"]?.jsonPrimitive?.contentOrNull == "structure")
			deferredJournalEdits += command.getValue("edits").jsonArray.map { it.jsonObject }.filter(::generatedPanelEdit)
		var model = authored
		// Swings and simulations write their keyforms onto the replayed rig, in the document graph's order.
		model = DocumentGenerators.generate(model, this)
		// Edits of generated keyforms merge with what the generators produce now.
		val overrides = if (authoringJournal.none(GeneratedOverrides::isOverride)) GeneratedOverrides.Outcome(model, emptyList())
			else GeneratedOverrides.applyAll(model, authoringJournal, ArtPrimitiveJournal.replacementDrawables(this).keys)
		model = overrides.model
		return GeneratedOverrides.Outcome(RigStructureEdits.replay(model, generatedPanelEdits + deferredJournalEdits).withParametersSyncedFromTree(), overrides.issues, notes)
	}

	/** Per journal entry (by identity) before a version 2 split, the drawables later v2 records supersede ([StubTolerance]). */
	private val stubTolerance: java.util.IdentityHashMap<kotlinx.serialization.json.JsonObject, Set<String>> by lazy { StubTolerance.of(authoringJournal) }

	companion object {
		/** Cubism Editor's default physics rate. */
		const val DEFAULT_PHYSICS_FPS = 60
		// Before [Empty], which checks against it while the companion initializes.
		val PHYSICS_FPS_RANGE = 1..240
		/** No fixed rate: physics steps with each rendered frame. */
		const val UNLIMITED_FPS = 0
		/** The rates the preview toolbar offers. */
		val FPS_CHOICES = listOf(30, 60, 90, 120, UNLIMITED_FPS)
		fun validFps(fps: Int) = fps == UNLIMITED_FPS || fps in PHYSICS_FPS_RANGE
		/** Journal records only older builds write and only the legacy replay reads ([replaysLegacy]). */
		/** How many entries may follow the journal's last checkpoint before an edit adding more checkpoints it ([checkpointsBeforeEntries]). */
		internal const val CHECKPOINT_INTERVAL = 32
		/** Whether [entry] is a record that replays on the base rather than acting on the authored rig. */
		internal fun isLegacyRecord(entry: kotlinx.serialization.json.JsonObject) = entry["op"]?.jsonPrimitive?.contentOrNull in LEGACY_OPS
		private val LEGACY_OPS = setOf(ArtPrimitiveJournal.OP, RigGenerationJournal.OP, RigGenerationFrames.OP, RigGenerationScaffold.OP,
			RigMeshActivation.OP, SourcePartitionJournal.OP, DepthSplit.OP)
		val Empty = RigEditOverlay()
	}
}

internal fun BuiltRig.withRigEdits(overlay: RigEditOverlay, layerVisibility: Map<String, Boolean> = emptyMap(),
                                 drawOrderOverrides: Map<String, Float> = emptyMap()): BuiltRig {
	if (overlay == RigEditOverlay.Empty) return withDrawOrderOverrides(drawOrderOverrides)
	return authoredRig(overlay).finished(overlay, layerVisibility, drawOrderOverrides)
}

/**
 * A rig with [RigEditOverlay.replayAuthored] applied: [rig]'s puppet is the authored state, its maps already name the
 * meshes the journal creates, and [visibilityTargets] are those meshes, in journal order, with the source layer whose
 * visibility the finished rig applies to them. The materialized state a revision stores (see the materialized rig design).
 */
internal data class AuthoredRig(val rig: BuiltRig, val visibilityTargets: List<Pair<String, String>>)

/** This base rig with [overlay]'s legacy edits and journal replayed, before any generator writes its keyforms. */
internal fun BuiltRig.authoredRig(overlay: RigEditOverlay): AuthoredRig {
	if (overlay == RigEditOverlay.Empty) return AuthoredRig(this, emptyList())
	val replayed = overlay.replayAuthored(puppet, primitiveSkins)
	// After a checkpoint its rig's maps hold every mesh up to it; the base's would miss what it stores.
	val start = if (overlay.checkpointIndex < 0) AuthoredRig(this, emptyList()) else
		RigCheckpoint.decode(overlay.authoringJournal[overlay.checkpointIndex]).authored.reboundTo(puppet.atlas, puppet.sources)
			.let { it.copy(rig = it.rig.copy(primitiveSkins = primitiveSkins)) }
	return AuthoredRig(start.rig.copy(puppet = replayed.model, supersededEntryNotes = replayed.notes), start.visibilityTargets)
		.withJournalMeshes(overlay.afterCheckpoint)
}

/** [commands] - journal entries [rig]'s puppet already holds - recorded in the maps: the meshes they create, split or rebuild. */
internal fun AuthoredRig.withJournalMeshes(commands: List<JsonObject>): AuthoredRig {
	val model = rig.puppet
	val bounds = rig.sourceBoundsByDrawableId.toMutableMap()
	val layers = rig.layerIdByDrawableId.toMutableMap()
	val pages = rig.pageByDrawableId.toMutableMap()
	val visible = visibilityTargets.toMutableList()
	for (command in commands) {
		val op = command["op"]?.jsonPrimitive?.contentOrNull
		if (op == SourcePartitionJournal.OP) {
			for (piece in SourcePartitionJournal.pieces(command)) {
				val id = piece.getValue("id").jsonPrimitive.content
				val drawable = model.drawables.singleOrNull { it.id.raw == id } ?: continue
				layers[id] = piece.getValue("layer_id").jsonPrimitive.content
				visible += id to layers.getValue(id)
				pages[id] = drawable.texturePage
				val canvas = piece.getValue("texture_canvas").jsonArray.map { it.jsonPrimitive.float }
				val xs = canvas.indices.step(2).map { canvas[it] }; val ys = canvas.indices.step(2).map { canvas[it + 1] }
				bounds[id] = Bounds(xs.min(), ys.min(), xs.max(), ys.max())
			}
			continue
		}
		if (op == ArtPrimitiveJournal.OP) {
			for (id in command.getValue("supersedes").jsonArray.map { it.jsonPrimitive.content }) {
				layers -= id; bounds -= id; pages -= id
			}
			for (primitive in ArtPrimitiveJournal.primitives(command)) {
				val id = primitive.getValue("id").jsonPrimitive.content
				val drawable = model.drawables.singleOrNull { it.id.raw == id } ?: continue
				layers[id] = primitive.getValue("layer_id").jsonPrimitive.content
				visible += id to layers.getValue(id)
				pages[id] = drawable.texturePage
				val edges = primitive.getValue("neutral_bounds").jsonArray.map { it.jsonPrimitive.float }
				require(edges.size == 4 && edges.all(Float::isFinite) && edges[2] >= edges[0] && edges[3] >= edges[1]) {
					"Invalid art primitive neutral bounds"
				}
				bounds[id] = Bounds(edges[0], edges[1], edges[2], edges[3])
			}
			continue
		}
		if (op != RasterMeshJournal.OP && op != RasterMeshCreation.OP && op != RigMeshActivation.OP) continue
		val id = command.getValue("id").jsonPrimitive.content
		if ((op == RasterMeshCreation.OP || op == RigMeshActivation.OP) && model.drawables.any { it.id.raw == id }) {
			layers[id] = command.getValue("layer_id").jsonPrimitive.content
			pages[id] = model.drawables.single { it.id.raw == id }.texturePage
		}
		val value = command["neutral_bounds"]?.jsonArray ?: continue
		val numbers = value.map { it.jsonPrimitive.content.toFloat() }
		require(numbers.size == 4 && numbers.all(Float::isFinite) && numbers[2] >= numbers[0] && numbers[3] >= numbers[1]) {
			"Invalid rebuilt mesh neutral bounds"
		}
		if (model.drawables.any { it.id.raw == id }) bounds[id] = Bounds(numbers[0], numbers[1], numbers[2], numbers[3])
	}
	return AuthoredRig(rig.copy(sourceBoundsByDrawableId = bounds, layerIdByDrawableId = layers, pageByDrawableId = pages), visible)
}

/** The finished rig: [overlay]'s generators, overrides and deferred panel edits on the authored state, then layer visibility and draw orders. */
internal fun AuthoredRig.finished(overlay: RigEditOverlay, layerVisibility: Map<String, Boolean> = emptyMap(),
                                  drawOrderOverrides: Map<String, Float> = emptyMap()): BuiltRig {
	// Without edits the base is the rig: no generator runs (as [withRigEdits] has it).
	if (overlay == RigEditOverlay.Empty) return rig.withDrawOrderOverrides(drawOrderOverrides)
	val outcome = overlay.finish(rig.puppet, rig.supersededEntryNotes)
	var model = outcome.model
	for ((id, layer) in visibilityTargets) {
		val visible = layerVisibility[layer] ?: continue
		model = model.copy(drawables = model.drawables.map { if (it.id.raw == id) it.copy(isVisible = visible) else it })
	}
	model = ArtPrimitiveJournal.pruneAtlas(model, overlay)
	return rig.copy(puppet = model, overrideIssues = outcome.issues, supersededEntryNotes = outcome.notes)
		.withDrawOrderOverrides(drawOrderOverrides)
}

internal fun applyKeyformDelete(model: PuppetModel, delete: RigKeyformDeleteEdit): PuppetModel {
    val requestedChannel = delete.channel?.takeUnless { it.equals("geometry", ignoreCase = true) }?.let { name ->
        val canonical = when (name.lowercase()) {
            "draworder" -> "DRAW_ORDER"
            "multiplycolor" -> "MULTIPLY_COLOR"
            "screencolor" -> "SCREEN_COLOR"
            "glueintensity" -> "GLUE_INTENSITY"
            "flipx" -> "FLIP_X"
            "flipy" -> "FLIP_Y"
            else -> name.uppercase()
        }
        FormChannel.entries.firstOrNull { it.name == canonical }
            ?: throw IllegalArgumentException("Unknown keyform channel: $name")
    }
	val paramId = ParameterId(delete.parameterId)
	val param = model.parameters.firstOrNull { it.id == paramId } ?: return model
	val owner = delete.target.asKeyformOwner()
	if (param.kind == ParameterKind.BLEND_SHAPE) {
		return if (delete.keyValue == null) model.removeBlendBinding(owner, paramId)
		else model.removeBlendKey(owner, paramId, delete.keyValue)
	}
	var current = model

	val deleteGeometry = delete.channel == null || delete.channel.equals("geometry", ignoreCase = true)
	if (deleteGeometry) {
		val grid = current.geometryGridOf(owner)
		if (grid != null && grid.axisIndexOf(paramId) >= 0) {
			if (delete.keyValue != null) {
				val keyIndex = grid.keyIndexAt(paramId, delete.keyValue)
				if (keyIndex >= 0) {
					current = current.withGeometryKeyRemoved(owner, param, keyIndex)
				}
			} else {
				val collapsed = grid.withAxisCollapsed(paramId, param.default)
				current = current.withReplacedGeometryGrid(owner, collapsed)
			}
		}
	}

	val deleteChannels = delete.channel == null || !delete.channel.equals("geometry", ignoreCase = true)
	if (deleteChannels) {
        val targetCh = requestedChannel
		val channelFilter: (FormChannel) -> Boolean = { ch ->
			targetCh == null || ch == targetCh
		}
		val grids = current.channelGridsOf(owner)
		if (grids != null) {
			for ((channel, track) in grids.gridsByChannel) {
				if (channelFilter(channel) && track.axisIndexOf(paramId) >= 0) {
					if (delete.keyValue != null) {
						val keyIndex = track.keyIndexAt(paramId, delete.keyValue)
						if (keyIndex >= 0) {
							current = current.withChannelKeyRemovedAt(KeyableTarget(owner, channel), param, keyIndex)
						}
					} else {
						val survivingKey = track.axes[track.axisIndexOf(paramId)].keys.firstOrNull() ?: param.default
						val collapsed = track.withAxisCollapsed(paramId, survivingKey)
						val nextGrids = if (collapsed == null) {
							grids.gridsByChannel - channel
						} else {
							grids.gridsByChannel + (channel to collapsed)
						}
						current = current.withReplacedChannelGrids(owner, ChannelGrids(nextGrids))
					}
				}
			}
		}
	}
	return current
}

internal fun applyKeyformSet(model: PuppetModel, set: RigKeyformSetEdit, capturePose: Map<String, Float>? = null): PuppetModel {
	val blendTargets = model.blendParametersIn(set.coordinate)
	if (blendTargets.isNotEmpty()) {
		val owner = set.target.asKeyformOwner()
		val geo = set.geometry
		return model.withBlendShapeCaptured(
			owner,
			set.coordinate,
			observedMesh = geo?.positionDeltas?.toFloatArray(),
			observedWarp = geo?.controlPoints?.toFloatArray(),
			observedRotation = if (geo?.originX != null && geo.originY != null && geo.angle != null) {
				RotationPivotForm(geo.originX, geo.originY, geo.angle, geo.scale ?: 1f)
			} else null,
			channels = set.channels,
			poseCoordinate = capturePose ?: set.coordinate,
		)
	}
	val gridCoordinate = model.gridCoordinateOf(set.coordinate)
	val gridSet = if (gridCoordinate.size == set.coordinate.size) set else set.copy(coordinate = gridCoordinate)
	if (gridSet.coordinate.isEmpty()) return model
	var current = model
	val owner = gridSet.target.asKeyformOwner()
	val poseFn: (ParameterId) -> Float = { id ->
		gridSet.coordinate[id.raw] ?: (current.parameters.firstOrNull { it.id == id }?.default ?: 0f)
	}
	val poseMap: Pose = gridSet.coordinate.mapKeys { ParameterId(it.key) }

	// 1. Geometry edit
	val geo = set.geometry
	if (geo != null) {
		when (set.target.kind) {
			RigTargetKind.WARP_DEFORMER -> {
				val deformer = current.deformers.firstOrNull { it.id.raw == set.target.id } as? Deformer.Warp
				if (deformer != null && geo.controlPoints != null) {
					val expectedSize = (deformer.rows + 1) * (deformer.columns + 1) * 2
					require(geo.controlPoints.size == expectedSize) {
						"Warp ${set.target.id} control points size mismatch: expected $expectedSize, got ${geo.controlPoints.size}"
					}
					val form = WarpLatticeForm(geo.controlPoints.toFloatArray())
					val identity = FloatArray(expectedSize)
					for (r in 0..deformer.rows) for (c in 0..deformer.columns) {
						val i = (r * (deformer.columns + 1) + c) * 2
						identity[i] = c.toFloat() / deformer.columns
						identity[i + 1] = r.toFloat() / deformer.rows
					}
					var grid: KeyformGrid<WarpLatticeForm>? = deformer.geometryGrid
						?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), WarpLatticeForm(identity))))
					for ((paramName, _) in gridSet.coordinate) {
						val param = current.parameters.firstOrNull { it.id.raw == paramName }
							?: throw IllegalArgumentException("Parameter not found: $paramName")
						if (grid == null || grid.axisIndexOf(param.id) < 0) {
							grid = grid.withAxisSeeded(param, form)
								?: throw IllegalStateException("Cannot seed warp axis: $paramName")
						}
					}
					if (grid != null) {
						grid = grid.withFormCaptured(poseFn, form, WarpLatticeInterpolator)
						current = current.copy(
							deformers = current.deformers.map {
								if (it.id == deformer.id && it is Deformer.Warp) it.copy(geometryGrid = grid) else it
							},
						)
					}
				}
			}
			RigTargetKind.ROTATION_DEFORMER -> {
				val deformer = current.deformers.firstOrNull { it.id.raw == set.target.id } as? Deformer.Rotation
				if (deformer != null && geo.angle != null && geo.originX != null && geo.originY != null) {
					val form = RotationPivotForm(geo.originX, geo.originY, geo.angle, geo.scale ?: 1f)
					var grid: KeyformGrid<RotationPivotForm>? = deformer.geometryGrid
					for ((paramName, _) in gridSet.coordinate) {
						val param = current.parameters.firstOrNull { it.id.raw == paramName }
							?: throw IllegalArgumentException("Parameter not found: $paramName")
						if (grid == null || grid.axisIndexOf(param.id) < 0) {
							grid = grid.withAxisSeeded(param, form)
								?: throw IllegalStateException("Cannot seed rotation axis: $paramName")
						}
					}
					if (grid != null) {
						grid = grid.withFormCaptured(poseFn, form, RotationPivotInterpolator)
						current = current.copy(
							deformers = current.deformers.map {
								if (it.id == deformer.id && it is Deformer.Rotation) it.copy(geometryGrid = grid) else it
							},
						)
					}
				}
			}
			RigTargetKind.ART_MESH -> {
				val drawable = current.findDrawable(set.target.id)
				if (drawable != null && geo.positionDeltas != null) {
					val expectedDeltas = (drawable.mesh?.positions?.size ?: geo.positionDeltas.size)
					require(geo.positionDeltas.size == expectedDeltas) {
						"Mesh ${set.target.id} position deltas size mismatch: expected $expectedDeltas, got ${geo.positionDeltas.size}"
					}
					val form = MeshDeltaForm(geo.positionDeltas.toFloatArray())
					val neutralForm = MeshDeltaForm(FloatArray(expectedDeltas))
					var grid: KeyformGrid<MeshDeltaForm>? = drawable.geometryGrid
						?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), neutralForm)))
					for ((paramName, _) in gridSet.coordinate) {
						val param = current.parameters.firstOrNull { it.id.raw == paramName }
							?: throw IllegalArgumentException("Parameter not found: $paramName")
						if (grid == null || grid.axisIndexOf(param.id) < 0) {
							grid = grid.withAxisSeeded(param, neutralForm)
								?: throw IllegalStateException("Cannot seed drawable axis: $paramName")
						}
					}
					if (grid != null) {
						grid = grid.withFormCaptured(poseFn, form, MeshDeltaInterpolator)
						current = current.copy(
							drawables = current.drawables.map {
								if (it.id == drawable.id) it.copy(geometryGrid = grid) else it
							},
						)
					}
				}
			}
			RigTargetKind.PART, RigTargetKind.GLUE -> {
				// Parts and glues do not hold standalone geometry
			}
		}
	}

	// 2. Channels edit
	val ch = set.channels
	if (ch != null) {
		val channelEntries = buildList<Pair<FormChannel, ChannelValue>> {
			ch.opacity?.let { add(FormChannel.OPACITY to ChannelValue.Scalar(it.coerceIn(0f, 1f))) }
			ch.drawOrder?.let { add(FormChannel.DRAW_ORDER to ChannelValue.Scalar(it.coerceIn(0f, 1000f))) }
			ch.multiplyColor?.let { add(FormChannel.MULTIPLY_COLOR to ChannelValue.Color(ColorRgb(it[0].coerceIn(0f, 1f), it[1].coerceIn(0f, 1f), it[2].coerceIn(0f, 1f)))) }
			ch.screenColor?.let { add(FormChannel.SCREEN_COLOR to ChannelValue.Color(ColorRgb(it[0].coerceIn(0f, 1f), it[1].coerceIn(0f, 1f), it[2].coerceIn(0f, 1f)))) }
			ch.glueIntensity?.let { add(FormChannel.GLUE_INTENSITY to ChannelValue.Scalar(it.coerceIn(0f, 1f))) }
			ch.flipX?.let { add(FormChannel.FLIP_X to ChannelValue.Flag(it)) }
			ch.flipY?.let { add(FormChannel.FLIP_Y to ChannelValue.Flag(it)) }
		}
		for ((channel, value) in channelEntries) {
			for ((paramName, _) in gridSet.coordinate) {
				val param = current.parameters.firstOrNull { it.id.raw == paramName } ?: continue
				current = current.withChannelKeyCaptured(KeyableTarget(owner, channel), param, poseMap, value)
			}
		}
	}
	return current
}

internal fun applyKeyformCopy(model: PuppetModel, copy: RigKeyformCopyEdit): PuppetModel {
	val sourceOwner = copy.sourceTarget.asKeyformOwner()
	val sourcePose: Pose = copy.sourceCoordinate.mapKeys { ParameterId(it.key) }

	var extractedGeo: RigKeyformGeometryEdit? = null
	val copyGeometry = copy.channels == null || copy.channels.any { it.equals("geometry", ignoreCase = true) }
	if (copyGeometry) {
		when (copy.sourceTarget.kind) {
			RigTargetKind.WARP_DEFORMER -> {
				val warp = model.deformers.firstOrNull { it.id.raw == copy.sourceTarget.id } as? Deformer.Warp
				val grid = warp?.geometryGrid
				if (grid != null) {
					val cell = findCellAtCoordinate(grid, copy.sourceCoordinate)
					if (cell != null) {
						extractedGeo = RigKeyformGeometryEdit(controlPoints = cell.form.controlPoints.toList())
					}
				}
			}
			RigTargetKind.ROTATION_DEFORMER -> {
				val rot = model.deformers.firstOrNull { it.id.raw == copy.sourceTarget.id } as? Deformer.Rotation
				val grid = rot?.geometryGrid
				if (grid != null) {
					val cell = findCellAtCoordinate(grid, copy.sourceCoordinate)
					if (cell != null) {
						extractedGeo = RigKeyformGeometryEdit(
							originX = cell.form.originX,
							originY = cell.form.originY,
							angle = cell.form.angle,
							scale = cell.form.scale,
						)
					}
				}
			}
			RigTargetKind.ART_MESH -> {
				val drawable = model.findDrawable(copy.sourceTarget.id)
				val grid = drawable?.geometryGrid
				if (grid != null) {
					val cell = findCellAtCoordinate(grid, copy.sourceCoordinate)
					if (cell != null) {
						extractedGeo = RigKeyformGeometryEdit(positionDeltas = cell.form.positionDeltas.toList())
					}
				}
			}
			RigTargetKind.PART, RigTargetKind.GLUE -> {}
		}
	}

	val channelsEdit: RigKeyformChannelsEdit? = run {
		var opacity: Float? = null
		var drawOrder: Float? = null
		var multiplyColor: List<Float>? = null
		var screenColor: List<Float>? = null
		var glueIntensity: Float? = null
		var flipX: Boolean? = null
		var flipY: Boolean? = null
		var foundAny = false

		fun shouldCopy(name: String): Boolean =
			copy.channels == null || copy.channels.any { it.equals(name, ignoreCase = true) }

		if (shouldCopy("opacity")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.OPACITY), sourcePose) as? ChannelValue.Scalar)?.let {
				opacity = it.value
				foundAny = true
			}
		}
		if (shouldCopy("draw_order") || shouldCopy("drawOrder")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.DRAW_ORDER), sourcePose) as? ChannelValue.Scalar)?.let {
				drawOrder = it.value
				foundAny = true
			}
		}
		if (shouldCopy("multiply_color") || shouldCopy("multiplyColor")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.MULTIPLY_COLOR), sourcePose) as? ChannelValue.Color)?.let {
				multiplyColor = listOf(it.color.red, it.color.green, it.color.blue)
				foundAny = true
			}
		}
		if (shouldCopy("screen_color") || shouldCopy("screenColor")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.SCREEN_COLOR), sourcePose) as? ChannelValue.Color)?.let {
				screenColor = listOf(it.color.red, it.color.green, it.color.blue)
				foundAny = true
			}
		}
		if (shouldCopy("glue_intensity") || shouldCopy("glueIntensity")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.GLUE_INTENSITY), sourcePose) as? ChannelValue.Scalar)?.let {
				glueIntensity = it.value
				foundAny = true
			}
		}
		if (shouldCopy("flip_x") || shouldCopy("flipX")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.FLIP_X), sourcePose) as? ChannelValue.Flag)?.let {
				flipX = it.flag
				foundAny = true
			}
		}
		if (shouldCopy("flip_y") || shouldCopy("flipY")) {
			(model.channelValueAt(KeyableTarget(sourceOwner, FormChannel.FLIP_Y), sourcePose) as? ChannelValue.Flag)?.let {
				flipY = it.flag
				foundAny = true
			}
		}

		if (foundAny) {
			RigKeyformChannelsEdit(
				opacity = opacity,
				drawOrder = drawOrder,
				multiplyColor = multiplyColor,
				screenColor = screenColor,
				glueIntensity = glueIntensity,
				flipX = flipX,
				flipY = flipY,
			)
		} else null
	}

	if (extractedGeo == null && channelsEdit == null) return model
	return applyKeyformSet(
		model,
		RigKeyformSetEdit(
			target = copy.destinationTarget,
			coordinate = copy.destinationCoordinate,
			geometry = extractedGeo,
			channels = channelsEdit,
		),
	)
}

internal fun <TForm> findCellAtCoordinate(
	grid: KeyformGrid<TForm>,
	coordinate: Map<String, Float>,
): org.umamo.runtime.model.KeyformCell<TForm>? {
	val indices = IntArray(grid.axes.size)
	for (axisIndex in grid.axes.indices) {
		val axis = grid.axes[axisIndex]
		val requestedValue = coordinate[axis.parameterId.raw] ?: return null
		val keyIndex = grid.keyIndexAt(axis.parameterId, requestedValue)
		if (keyIndex < 0) return null
		indices[axisIndex] = keyIndex
	}
	val linear = grid.linearIndexOf(indices)
	return grid.cellsByLinearIndex[linear]
}

internal fun PuppetModel.findDrawable(id: String): Drawable? =
	drawables.firstOrNull { it.id.raw == id || it.id.raw == "artmesh_$id" || it.id.raw.removePrefix("artmesh_") == id }

internal fun PuppetModel.withReplacedChannelGrids(owner: KeyformOwner, channelGrids: ChannelGrids): PuppetModel =
	when (owner) {
		is KeyformOwner.Drawable ->
			copy(drawables = drawables.map { if (it.id == owner.id) it.copy(channelGrids = channelGrids) else it })
		is KeyformOwner.Part ->
			copy(parts = parts.map { if (it.id == owner.id) it.copy(channelGrids = channelGrids) else it }).withDerivedRenderRoot()
		is KeyformOwner.Deformer ->
			copy(
				deformers = deformers.map { deformer ->
					if (deformer.id != owner.id) deformer else when (deformer) {
						is Deformer.Warp -> deformer.copy(channelGrids = channelGrids)
						is Deformer.Rotation -> deformer.copy(channelGrids = channelGrids)
					}
				},
			)
		is KeyformOwner.Glue ->
			copy(
				glues = glues.map { glue ->
					if (owner.matches(glue)) glue.copy(channelGrids = channelGrids) else glue
				},
			)
	}

@Suppress("UNCHECKED_CAST")
internal fun PuppetModel.withReplacedGeometryGrid(owner: KeyformOwner, grid: KeyformGrid<*>?): PuppetModel =
	when (owner) {
		is KeyformOwner.Drawable ->
			copy(
				drawables = drawables.map {
					if (it.id == owner.id) it.copy(geometryGrid = grid as KeyformGrid<MeshDeltaForm>?) else it
				},
			)
		is KeyformOwner.Deformer ->
			copy(
				deformers = deformers.map { deformer ->
					if (deformer.id != owner.id) deformer else when (deformer) {
						is Deformer.Warp -> deformer.copy(geometryGrid = grid as KeyformGrid<WarpLatticeForm>?)
						is Deformer.Rotation -> deformer.copy(geometryGrid = grid as KeyformGrid<RotationPivotForm>?)
					}
				},
			)
		is KeyformOwner.Part, is KeyformOwner.Glue -> this
	}
