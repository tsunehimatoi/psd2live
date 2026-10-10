package io.github.psd2live.application


import io.github.psd2live.core.BoneEnd
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionHandle
import io.github.psd2live.core.MotionInterpolation
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.MotionKeyEdits
import io.github.psd2live.core.MotionKeyRef
import io.github.psd2live.core.MotionPresets
import io.github.psd2live.core.MotionPresetSettings
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonDraftEdits
import io.github.psd2live.core.SkeletonSpec
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Pure candidate edits shared by application commands. The runtime command boundary owns persistence. */
internal object WorkspaceSkeletonMotionEdits {
    fun skeleton(current: SkeletonSpec?, request: JsonObject, proposal: () -> SkeletonSpec): SkeletonSpec {
        val mode = request.requiredText("mode")
        val spec = when (mode) {
            "auto" -> proposal().copy(enabled = true)
            "put" -> SkeletonSpec.fromJson(request.getValue("spec").jsonObject)
            "enable" -> requireNotNull(current) { "Create a skeleton before changing its enabled state" }
                .copy(enabled = request.getValue("enabled").jsonPrimitive.boolean)
            "bone" -> {
                val before = requireNotNull(current) { "Create a skeleton before editing bones" }
                val input = request.getValue("bone").jsonObject
                val id = input.requiredText("id")
                val fields = before.bone(id)?.toJson()?.let { JsonObject(it + input) } ?: input
                before.withBone(SkeletonBone.fromJson(fields))
            }
            "move" -> {
                val before = requireNotNull(current) { "Create a skeleton before moving joints" }
                val id = request.requiredText("bone_id")
                require(before.bone(id) != null) { "Bone not found: $id" }
                val end = BoneEnd.valueOf(request.requiredText("end").uppercase())
                val point = request.getValue("point").jsonArray
                before.withJointMoved(id, end, point[0].jsonPrimitive.float, point[1].jsonPrimitive.float)
            }
            "bind" -> {
                val before = requireNotNull(current) { "Create a skeleton before binding meshes" }
                before.withDrawableBound(request.requiredText("drawable_id"), request["bone_id"]?.jsonPrimitive?.contentOrNull)
            }
            "remove" -> {
                val before = requireNotNull(current) { "Create a skeleton before removing bones" }
                val id = request.requiredText("bone_id")
                val bone = requireNotNull(before.bone(id)) { "Bone not found: $id" }
                require(!bone.role.anchor && !bone.role.body) { "Body and anchor bones cannot be removed" }
                before.withoutBone(id)
            }
            else -> error("Unknown skeleton mode: $mode")
        }
        return SkeletonDraftEdits.validated(spec)
    }

    fun motion(
        current: List<MotionClip>,
        request: JsonObject,
        ranges: Map<String, ClosedFloatingPointRange<Float>>,
        skeleton: SkeletonSpec?,
        presets: Map<String, MotionPresetSettings> = emptyMap(),
    ): List<MotionClip> {
        val mode = request.requiredText("mode")
        val next = when (mode) {
            "create" -> {
                val id = request["id"]?.jsonPrimitive?.content ?: MotionClips.newId(current)
                require(current.none { it.id == id }) { "Motion ID already exists" }
                val builtin = request["from_builtin"]?.jsonPrimitive?.content
                require(builtin == null || builtin in MotionClips.BUILTIN_NAMES) { "Unknown generated motion" }
                val source = builtin?.let { MotionClips.overrideOf(current, it) ?: MotionPresets.clip(id, it, skeleton,
                    (presets[it] ?: MotionPresetSettings()).copy(deleted = false)) }
                val name = request["name"]?.jsonPrimitive?.content ?: builtin ?: "New motion"
                val clip = source?.copy(id = id, name = MotionClips.uniqueName(current, name), builtin = null, enabled = true)
                    ?: MotionClip(id, MotionClips.uniqueName(current, name))
                current + clip
            }
            "duplicate" -> {
                val source = requireClip(current, request.requiredText("id"))
                val id = request["new_id"]?.jsonPrimitive?.content ?: MotionClips.newId(current)
                require(current.none { it.id == id }) { "Motion ID already exists" }
                current + source.copy(id = id, name = MotionClips.uniqueName(current, source.name), builtin = null)
            }
            "rename" -> {
                val source = requireClip(current, request.requiredText("id"))
                require(source.builtin == null) { "Generated motion names cannot be changed" }
                val name = request.requiredText("name").trim()
                require(name.isNotEmpty() && name.none(Char::isISOControl)) { "Invalid motion name" }
                current.map { if (it.id == source.id) it.copy(name = MotionClips.uniqueName(current.filterNot { other -> other.id == source.id }, name)) else it }
            }
            "properties" -> {
                val source = requireClip(current, request.requiredText("id"))
                require(listOf("loop", "duration", "fps", "fade_in", "fade_out", "enabled").any { it in request }) { "Provide a motion property" }
                val duration = request["duration"]?.jsonPrimitive?.float ?: source.duration
                val updated = source.copy(loop = request["loop"]?.jsonPrimitive?.boolean ?: source.loop,
                    duration = duration, fps = request["fps"]?.jsonPrimitive?.float ?: source.fps,
                    fadeIn = request["fade_in"]?.jsonPrimitive?.float ?: source.fadeIn,
                    fadeOut = request["fade_out"]?.jsonPrimitive?.float ?: source.fadeOut,
                    enabled = request["enabled"]?.jsonPrimitive?.boolean ?: source.enabled,
                    curves = if (duration != source.duration) MotionKeyEdits.withDuration(source, duration).curves else source.curves)
                current.map { if (it.id == source.id) updated else it }
            }
            "put" -> {
                val clip = MotionClips.fromJson(request.getValue("clip").jsonObject)
                current.filterNot { it.id == clip.id } + clip
            }
            "delete" -> {
                val id = request.requiredText("id")
                require(current.any { it.id == id }) { "Motion not found: $id" }
                current.filterNot { it.id == id }
            }
            "seed_builtin" -> {
                val name = request.requiredText("builtin")
                require(name in MotionClips.BUILTIN_NAMES) { "Unknown built-in motion: $name" }
                if (current.any { it.builtin == name }) current else {
                    val tracks = MotionClips.builtinTracks(name, skeleton)
                    require(tracks.isNotEmpty()) {
                        // Every built-in but Blink, Nod and Shake moves bones.
                        if (skeleton == null || skeleton.bones.isEmpty() || !skeleton.enabled)
                            "Built-in motion $name moves the skeleton, and this model has none enabled; create one first with skeleton_auto"
                        else "Built-in motion $name has no tracks for this model: its skeleton lacks the bones $name moves"
                    }
                    current + MotionClips.fromTracks(
                        id = request["id"]?.jsonPrimitive?.contentOrNull ?: MotionClips.newId(current),
                        name = name, builtin = name, loop = MotionClips.isLoopBuiltin(name), tracks = tracks,
                        duration = MotionClips.builtinDuration(name, tracks),
                    )
                }
            }
            "set_key" -> {
                val clip = requireClip(current, request.requiredText("id"))
                val parameter = request.requiredText("parameter")
                val key = parseKey(request.getValue("key").jsonObject)
                val curve = clip.curve(parameter)
                val replacement = MotionCurve(parameter, MotionClips.normalized(curve?.keys.orEmpty() + key))
                val updated = clip.copy(curves = if (curve == null) clip.curves + replacement
                    else clip.curves.map { if (it.parameterId == parameter) replacement else it })
                current.map { if (it.id == clip.id) updated else it }
            }
            "delete_key" -> {
                val clip = requireClip(current, request.requiredText("id"))
                val parameter = request.requiredText("parameter")
                val time = request.getValue("time").jsonPrimitive.float
                val curve = requireNotNull(clip.curve(parameter)) { "Motion curve not found: $parameter" }
                require(curve.keys.any { abs(it.time - time) < MotionClips.TIME_EPSILON }) { "Motion key not found at $time" }
                val kept = curve.keys.filterNot { abs(it.time - time) < MotionClips.TIME_EPSILON }
                val updated = clip.copy(curves = clip.curves.filterNot { it.parameterId == parameter } +
                    if (kept.isEmpty()) emptyList() else listOf(MotionCurve(parameter, kept)))
                current.map { if (it.id == clip.id) updated else it }
            }
            "remove_curve" -> {
                val clip = requireClip(current, request.requiredText("id"))
                val parameter = request.requiredText("parameter")
                require(clip.curve(parameter) != null) { "Motion curve not found: $parameter" }
                current.map { if (it.id == clip.id) clip.copy(curves = clip.curves.filterNot { c -> c.parameterId == parameter }) else it }
            }
            "pose", "move_keys", "delete_keys", "paste_keys", "replace_keys" -> {
                val clip = requireClip(current, request.requiredText("id"))
                val selection = request["selection"]?.jsonArray.orEmpty().mapTo(linkedSetOf()) {
                    val key = it.jsonObject
                    MotionKeyRef(key.requiredText("parameter"), key.getValue("time").jsonPrimitive.float)
                }
                require(selection.all { ref -> clip.curve(ref.parameterId)?.keys?.any(ref::matches) == true }) { "Motion selection contains a missing key" }
                val next = when (mode) {
                    "pose" -> MotionKeyEdits.pose(clip, request.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.float },
                        request.getValue("time").jsonPrimitive.float)
                    "move_keys" -> MotionKeyEdits.move(clip, selection, request.getValue("dt").jsonPrimitive.float,
                        request["dv"]?.jsonPrimitive?.float ?: 0f, ranges, request["normalized"]?.jsonPrimitive?.boolean ?: false).first
                    "delete_keys" -> MotionKeyEdits.delete(clip, selection)
                    "paste_keys" -> MotionKeyEdits.paste(clip, request.getValue("keys").jsonArray.map {
                        val key = it.jsonObject; key.requiredText("parameter") to parseKey(key.getValue("key").jsonObject)
                    }, request.getValue("time").jsonPrimitive.float).first
                    else -> {
                        val replacements = request.getValue("keys").jsonArray.map {
                            val input = it.jsonObject
                            MotionKeyRef(input.requiredText("parameter"), input.getValue("from_time").jsonPrimitive.float) to parseKey(input.getValue("key").jsonObject)
                        }
                        require(replacements.map { it.first }.distinct().size == replacements.size) { "Duplicate key replacements" }
                        require(replacements.all { (ref, _) -> clip.curve(ref.parameterId)?.keys?.any(ref::matches) == true }) { "Missing key replacement" }
                        MotionKeyEdits.mapKeys(clip, replacements.mapTo(linkedSetOf()) { it.first }) { parameter, key ->
                            replacements.first { (ref, _) -> ref.parameterId == parameter && ref.matches(key) }.second
                        }
                    }
                }
                current.map { if (it.id == clip.id) next else it }
            }
            else -> error("Unknown motion mode: $mode")
        }
        return validated(next, ranges)
    }

    /** Generated-motion knobs and lifecycle share the same candidate as timeline edits. */
    fun preset(document: io.github.psd2live.project.WorkspaceDocument, request: JsonObject): io.github.psd2live.project.WorkspaceDocument {
        val name = request.requiredText("builtin")
        require(name in MotionClips.BUILTIN_NAMES) { "Unknown generated motion: $name" }
        val existing = document.rigEdits.motionPresets[name] ?: MotionPresetSettings()
        val next = when (request.requiredText("action")) {
            "update" -> {
                val values = request["values"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float }
                val knobs = MotionPresets.knobs(name).associateBy { it.id }
                require(values.all { (id, value) -> knobs[id]?.let { value.isFinite() && value in it.min..it.max } == true }) { "Unknown or out-of-range motion knob" }
                existing.copy(values = (existing.values + values).filter { (id, value) -> value != knobs.getValue(id).default },
                    disabled = request["disabled"]?.jsonPrimitive?.boolean ?: existing.disabled)
            }
            "reset" -> existing.copy(values = emptyMap())
            "delete" -> MotionPresetSettings(deleted = true)
            "restore" -> MotionPresetSettings()
            else -> error("Unknown preset action")
        }
        val presets = (document.rigEdits.motionPresets + (name to next)).filterValues { !it.isDefault }
        val clips = if (request.requiredText("action") in setOf("reset", "delete")) document.rigEdits.motionClips.filterNot { it.builtin == name }
            else document.rigEdits.motionClips
        return document.copy(rigEdits = document.rigEdits.copy(motionPresets = presets, motionClips = clips))
    }

    /** [next] once every clip is known to fit the model's parameters and the limits a request may not pass. */
    fun validated(next: List<MotionClip>, ranges: Map<String, ClosedFloatingPointRange<Float>>): List<MotionClip> {
        require(next.map { it.id }.distinct().size == next.size) { "Duplicate motion IDs" }
        require(next.mapNotNull { it.builtin }.distinct().size == next.count { it.builtin != null }) { "Duplicate built-in override" }
        require(next.map { it.name.lowercase() }.distinct().size == next.size) { "Duplicate motion names" }
        next.forEach { clip ->
            require(clip.builtin == null || clip.builtin in MotionClips.BUILTIN_NAMES) { "Unknown built-in override: ${clip.builtin}" }
            require(clip.builtin != null || MotionClips.BUILTIN_NAMES.none { it.equals(clip.name, ignoreCase = true) }) {
                "Custom motion name conflicts with a generated motion: ${clip.name}"
            }
            require(clip.curves.size <= 256) { "A motion may contain at most 256 curves" }
            clip.curves.forEach { curve ->
                val range = requireNotNull(ranges[curve.parameterId]) { "Unknown motion parameter: ${curve.parameterId}" }
                require(curve.keys.size <= 4096) { "A motion curve may contain at most 4096 keys" }
                require(curve.keys.all { it.time in 0f..clip.duration && it.value in range }) {
                    "Motion keys for ${curve.parameterId} must lie within the clip and parameter ranges"
                }
                require(curve.keys.all { it.outHandle.x in 0f..1f && it.inHandle.x in 0f..1f }) {
                    "Motion handle time fractions must be between 0 and 1"
                }
            }
        }
        return next
    }

    private fun requireClip(clips: List<MotionClip>, id: String): MotionClip =
        requireNotNull(clips.firstOrNull { it.id == id }) { "Motion not found: $id" }

    private fun parseKey(value: JsonObject): MotionKey {
        fun handle(name: String) = value[name]?.jsonArray?.let { MotionHandle(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }
            ?: MotionHandle()
        val interpolation = value["interpolation"]?.jsonPrimitive?.contentOrNull?.let(MotionInterpolation::valueOf)
            ?: MotionInterpolation.BEZIER
        return MotionKey(value.getValue("time").jsonPrimitive.float, value.getValue("value").jsonPrimitive.float,
            interpolation, handle("out"), handle("in"))
    }

    private fun JsonObject.requiredText(key: String): String = getValue(key).jsonPrimitive.content
}
