package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.format.compile.RigIrObjects
import io.github.psd2live.format.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
internal data class ModifierWrite(val kind: String, val id: String)

/** Ordinary materialized output at its original stack position, with an explicit upstream input contract. */
@Serializable
internal data class AppliedModifierNode(
    val before: JsonObject,
    val after: JsonObject,
    val capability: ModifierCapability,
    val requires: Set<String>,
    val providers: Map<String, String>,
    val composableKinds: Set<String>,
    val exclusiveFamily: String?,
    /** Null means an opaque generator observed the whole prefix. Context is frozen with its output. */
    val reads: List<ModifierReadKey>? = null,
    /** Editable motion records are separate from the frozen generator configuration. Empty means deleted. */
    val authoredMotions: List<JsonObject>? = null,
    /** Exact clips survive disabling and edits to fields unrelated to their curves. */
    val motionSource: JsonObject? = null,
    val authoredPhysics: List<JsonObject> = emptyList(),
    val disabledPhysics: Set<String> = emptySet(),
    val appliedPhysicsOrder: List<String> = emptyList(),
    val motionInputs: Set<String> = emptySet(),
    val motionOutputs: Set<String> = emptySet(),
    /** Only algorithm writes participate in generator collision checks; author corrections are ordered edits. */
    val algorithmWrites: List<ModifierWrite>? = null,
    /** The built-in pendulum reads other groups only to validate collisions, not to generate its output. */
    val physicsValidationOnly: Boolean = false,
    /** Existing objects the materialized patch replaces or removes at this stage. */
    val patchInputs: List<ModifierWrite> = emptyList(),
) {
    fun capturePhysics(): AppliedModifierNode {
        val input = decode(before).physics.groups.associateBy { it.id }
        val output = decode(after).physics.groups
        return copy(authoredPhysics = output.filter { input[it.id] != it }.map { Physics3Json.setting(it).toJson() },
            appliedPhysicsOrder = output.map { it.id })
    }

    fun editPhysics(id: String, edit: RigPhysicsEdit? = null, enabled: Boolean? = null, delete: Boolean = false): AppliedModifierNode {
        val records = authoredPhysics.map(RigPhysicsEdit::fromJson)
        require(records.any { it.id == id }) { "Applied physics group not found: $id" }
        require(edit == null || edit.id == id)
        val rig = decode(after)
        if (edit != null) require(edit.parameters.all { p -> rig.parameters.any { it.id == p } }) {
            "Applied physics may only reference parameters available at its stack position"
        }
        val next = if (delete) records.filterNot { it.id == id } else records.map { if (it.id == id) edit ?: it else it }
        val disabled = when { delete || enabled == true -> disabledPhysics - id; enabled == false -> disabledPhysics + id; else -> disabledPhysics }
        val owned = records.mapTo(HashSet()) { it.id }
        val output = (rig.physics.groups.filterNot { it.id in owned } + next.filterNot { it.id in disabled }.map(Physics3Json::group))
            .sortedBy { group -> appliedPhysicsOrder.indexOf(group.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
        return copy(after = encode(rig.copy(physics = rig.physics.copy(groups = output))),
            authoredPhysics = next.map { it.toJson() }, disabledPhysics = disabled)
    }

    fun captureMotion(instance: RigModifierInstance): AppliedModifierNode {
        val rig = decode(after)
        val previous = decode(before).clips.associateBy { it.id }
        val changed = rig.clips.filter { previous[it.id] != it }
        if (changed.isEmpty()) return this
        val records = changed.map { clip ->
            if (instance.type == "motion" && clip.id == instance.outputId("motion"))
                MotionClips.fromJson(instance.config).copy(id = clip.id, builtin = null, enabled = true,
                    name = clip.name, fileStem = clip.file, exportGroup = clip.group)
            else AppliedMotionRecords.record(clip, rig.parameters)
        }
        return copy(authoredMotions = records.map(MotionClips::toJson),
            motionSource = encode(RigIR(rig.canvas, emptyList(), clips = rig.clips)),
            motionOutputs = motionOutputs + changed.map { it.id })
    }

    fun replaceMotions(clips: List<MotionClip>): AppliedModifierNode {
        val previous = requireNotNull(authoredMotions).map(MotionClips::fromJson)
        val ids = previous.mapTo(HashSet()) { it.id }
        require(clips.all { it.id in ids }) { "Cannot introduce another motion into applied content" }
        require(clips.all { clip -> clip.parameterRoles == previous.single { it.id == clip.id }.parameterRoles }) {
            "Edit parameter roles separately from applied motion curves"
        }
        val rig = decode(after)
        val parameters = rig.parameters.mapTo(HashSet()) { it.id }
        require(clips.all { clip -> clip.curves.all { it.parameterId in parameters } }) {
            "Applied motion may only reference parameters available at its stack position"
        }
        val original = rig.clips.associateBy { it.id }
        val sourceClips = motionSource?.let(::decode)?.clips ?: rig.clips
        val sources = sourceClips.associateBy { it.id }
        val materialized = clips.map { clip -> AppliedMotionRecords.update(clip, previous.single { it.id == clip.id }, sources[clip.id]) }
        val enabled = clips.filter { it.enabled }.mapTo(HashSet()) { it.id }
        val updated = materialized.filter { it.id in enabled }.associateBy { it.id }
        val next = rig.clips.filterNot { it.id in ids && it.id !in updated }.map { updated[it.id] ?: it }.toMutableList()
        for (clip in updated.values.filter { it.id !in original }) {
            val successor = sourceClips.dropWhile { it.id != clip.id }.drop(1).firstOrNull { following -> next.any { it.id == following.id } }
            val position = successor?.let { following -> next.indexOfFirst { it.id == following.id } } ?: next.size
            next.add(position, clip)
        }
        val saved = materialized.associateBy { it.id }
        val nextSources = sourceClips.filterNot { it.id in ids && it.id !in saved }.map { saved[it.id] ?: it } +
            materialized.filter { it.id !in sources }
        return copy(after = encode(rig.copy(clips = next)), authoredMotions = clips.map(MotionClips::toJson),
            motionSource = encode(RigIR(rig.canvas, emptyList(), clips = nextSources)))
    }

    fun duplicateMotion(sourceId: String, record: MotionClip): AppliedModifierNode {
        val rig = decode(after)
        val stored = decode(motionSource ?: after)
        require(record.id !in motionOutputs && rig.clips.none { it.id == record.id }) { "Motion ID already exists at this stage" }
        val original = materializedMotions().single { it.id == sourceId }
        val duplicate = original.copy(id = record.id, name = record.name, file = requireNotNull(record.fileStem),
            group = record.exportGroup ?: if (record.loop) "Idle" else record.name)
        return copy(after = encode(rig.copy(clips = rig.clips + listOfNotNull(duplicate.takeIf { record.enabled }))),
            authoredMotions = authoredMotions.orEmpty() + MotionClips.toJson(record),
            motionSource = encode(stored.copy(clips = stored.clips + duplicate)), motionOutputs = motionOutputs + record.id)
    }

    fun materializedMotions(): List<Clip> {
        val ids = authoredMotions.orEmpty().map(MotionClips::fromJson).mapTo(HashSet()) { it.id }
        return decode(motionSource ?: after).clips.filter { it.id in ids }
    }

    fun definition(instance: RigModifierInstance): RigModifierDefinition {
        val original by lazy { decode(before) }
        val materialized by lazy { decode(after) }
        val observed by lazy { reads?.let { keys -> ModifierReadSet.capture(original, ModifierAlgorithmContext.Empty,
            if (physicsValidationOnly) keys.filterNot { it.section == "physics" } else keys) } }
        return RigModifierDefinition(instance.type, instance.version, requires, exclusiveFamily,
            parameters = { materialized.parameters.filter { parameter -> original.parameters.none { it == parameter } } },
            providers = { providers }, appliedConfig = { capability.config }, provides = capability.provides.orEmpty(),
            composableKinds = composableKinds,
            motionInputs = { motionInputs }, motionOutputs = { motionOutputs.intersect(materialized.clips.mapTo(HashSet()) { it.id }) },
            resolveParameter = { _, previous, desired -> desired.takeIf { original.parameters.any { it == previous } } },
            plugin = ModifierAlgorithmPlugin { input, _, checkpoint ->
                checkpoint()
                require(observed?.matches(input) ?: (input == original)) {
                    "Applied content ${instance.id} has changed upstream inputs; undo the upstream edit or apply its prerequisites together"
                }
                patch(original, materialized, input).also { result ->
                    if (physicsValidationOnly) io.github.psd2live.algorithms.physics.PhysicsGroups.validateAppend(
                        Physics(), result.physics.groups)
                }
            }, pluginReference = instance.plugin)
    }

    companion object {
        private fun pack(bytes: ByteArray): String {
            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { it.write(bytes) }
            return Base64.getEncoder().encodeToString(output.toByteArray())
        }
        private fun unpack(value: JsonElement) = GZIPInputStream(Base64.getDecoder().decode(value.jsonPrimitive.content).inputStream()).use { it.readBytes() }
        fun encode(rig: RigIR): JsonObject = RigIrObjects.split(rig).let { objects -> buildJsonObject {
            put("frame", pack(objects.frame))
            put("deformers", JsonArray(objects.deformers.map { JsonPrimitive(pack(it)) }))
            put("meshes", JsonArray(objects.meshes.map { JsonPrimitive(pack(it)) }))
        } }
        internal fun decode(value: JsonObject): RigIR = RigIrObjects.join(RigIrObjects.Objects(unpack(value.getValue("frame")),
            value.getValue("deformers").jsonArray.map(::unpack), value.getValue("meshes").jsonArray.map(::unpack)))

        /** Only changed objects/fields are replaced; unrelated authored content is never copied from the snapshot. */
        internal fun patch(before: RigIR, after: RigIR, current: RigIR): RigIR {
            fun <T> field(old: T, next: T, now: T): T {
                if (old == next) return now
                require(now == old) { "Applied content has a changed field input" }
                return next
            }
            fun <T> objects(old: List<T>, next: List<T>, now: List<T>, id: (T) -> String): List<T> {
                if (old == next) return now
                val previous = old.associateBy(id)
                val desired = next.associateBy(id)
                val actual = now.associateBy(id)
                require(actual.size == now.size) { "Duplicate applied content input" }
                val changed = (previous.keys + desired.keys).filterTo(HashSet()) { previous[it] != desired[it] }
                changed.forEach { key -> require(actual[key] == previous[key]) { "Applied content input changed: $key" } }
                // Reordering existing objects needs the complete original order, not an inferred placement.
                if (old.map(id).filter { it in desired } != next.map(id).filter { it in previous }) return field(old, next, now)
                val output = now.filterNot { id(it) in changed && id(it) !in desired }.map { item ->
                    if (id(item) in changed) desired.getValue(id(item)) else item
                }.toMutableList()
                next.forEachIndexed { index, item ->
                    if (id(item) in previous) return@forEachIndexed
                    val successor = next.drop(index + 1).firstOrNull { following -> output.any { id(it) == id(following) } }
                    val position = successor?.let { following -> output.indexOfFirst { id(it) == id(following) } } ?: output.size
                    output.add(position, item)
                }
                return output
            }
            return current.copy(
                canvas = field(before.canvas, after.canvas, current.canvas),
                parameters = objects(before.parameters, after.parameters, current.parameters) { it.id },
                parts = objects(before.parts, after.parts, current.parts) { it.id },
                deformers = objects(before.deformers, after.deformers, current.deformers) { it.id },
                meshes = objects(before.meshes, after.meshes, current.meshes) { it.id },
                clips = objects(before.clips, after.clips, current.clips) { it.id },
                physics = current.physics.copy(groups = objects(before.physics.groups, after.physics.groups, current.physics.groups) { it.id },
                    fps = field(before.physics.fps, after.physics.fps, current.physics.fps)),
                parameterLinks = field(before.parameterLinks, after.parameterLinks, current.parameterLinks),
                parameterTree = field(before.parameterTree, after.parameterTree, current.parameterTree),
                parameterRoles = field(before.parameterRoles, after.parameterRoles, current.parameterRoles),
                rootChildren = field(before.rootChildren, after.rootChildren, current.rootChildren),
                rootPart = field(before.rootPart, after.rootPart, current.rootPart),
                glues = field(before.glues, after.glues, current.glues),
                renderRoot = field(before.renderRoot, after.renderRoot, current.renderRoot),
                textures = field(before.textures, after.textures, current.textures),
                restPose = field(before.restPose, after.restPose, current.restPose),
                authoring = field(before.authoring, after.authoring, current.authoring),
                advanced = field(before.advanced, after.advanced, current.advanced),
                hitAreas = field(before.hitAreas, after.hitAreas, current.hitAreas),
            )
        }
    }
}
