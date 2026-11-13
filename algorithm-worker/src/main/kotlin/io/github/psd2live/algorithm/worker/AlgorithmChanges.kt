package io.github.psd2live.algorithm.worker

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Sparse before/after carriers contain only explicitly edited objects, never the complete input model. */
object AlgorithmChanges {
    const val CAPABILITY = "model_changes_v1"
    const val RASTER_CAPABILITY = "model_changes_v2"
    private val empty = RigIR(Canvas(0f, 0f), emptyList())
    private val objectNames = setOf("parameters", "parts", "deformers", "meshes", "physicsGroups", "clips")
    private val fieldNames = setOf("canvas", "parameterLinks", "parameterTree", "parameterRoles", "rootChildren", "rootPart", "glues", "renderRoot", "textures", "physicsFps", "restPose", "authoring", "advanced", "hitAreas")

    fun encode(changes: ModifierModelChanges, allowRasters: Boolean = false, write: (ByteArray) -> JsonObject): JsonObject {
        require(changes.rasters.isEmpty() || allowRasters) { "Host does not support generated raster outputs" }
        fun <T> before(c: ModifierObjectChanges<T>) = c.removed + c.reused + c.updated.map { it.before }
        fun <T> after(c: ModifierObjectChanges<T>) = c.added + c.updated.map { it.after }
        fun <T> field(c: ModifierFieldChange<T>?, fallback: T, prior: Boolean): T =
            if (c == null) fallback else if (prior) c.before else c.after
        fun carrier(prior: Boolean) = empty.copy(
            parameters = if (prior) before(changes.parameters) else after(changes.parameters),
            parts = if (prior) before(changes.parts) else after(changes.parts),
            deformers = if (prior) before(changes.deformers) else after(changes.deformers),
            meshes = if (prior) before(changes.meshes) else after(changes.meshes),
            clips = if (prior) before(changes.clips) else after(changes.clips),
            canvas = field(changes.canvas, empty.canvas, prior),
            parameterLinks = field(changes.parameterLinks, empty.parameterLinks, prior),
            parameterTree = field(changes.parameterTree, empty.parameterTree, prior),
            parameterRoles = field(changes.parameterRoles, empty.parameterRoles, prior),
            rootChildren = field(changes.rootChildren, empty.rootChildren, prior),
            rootPart = field(changes.rootPart, empty.rootPart, prior),
            glues = field(changes.glues, empty.glues, prior),
            renderRoot = field(changes.renderRoot, empty.renderRoot, prior),
            textures = field(changes.textures, empty.textures, prior),
            restPose = field(changes.restPose, empty.restPose, prior),
            authoring = field(changes.authoring, empty.authoring, prior),
            advanced = field(changes.advanced, empty.advanced, prior),
            hitAreas = field(changes.hitAreas, empty.hitAreas, prior),
            physics = Physics(if (prior) before(changes.physicsGroups) else after(changes.physicsGroups),
                field(changes.physicsFps, null, prior)),
        )
        fun <T> counts(c: ModifierObjectChanges<T>): JsonArray = JsonArray(
            listOf(c.added.size, c.updated.size, c.removed.size, c.reused.size).map(::JsonPrimitive))
        return buildJsonObject {
            put("schema", if (changes.rasters.isEmpty()) CAPABILITY else RASTER_CAPABILITY)
            if (changes.rasters.isNotEmpty()) put("rasters", AlgorithmRasters.encode(changes.rasters, write))
            put("before", write(RigIrBinary.encode(carrier(true))))
            put("after", write(RigIrBinary.encode(carrier(false))))
            putJsonObject("objects") {
                if (!changes.parameters.isEmpty) put("parameters", counts(changes.parameters))
                if (!changes.parts.isEmpty) put("parts", counts(changes.parts))
                if (!changes.deformers.isEmpty) put("deformers", counts(changes.deformers))
                if (!changes.meshes.isEmpty) put("meshes", counts(changes.meshes))
                if (!changes.physicsGroups.isEmpty) put("physicsGroups", counts(changes.physicsGroups))
                if (!changes.clips.isEmpty) put("clips", counts(changes.clips))
            }
            putJsonArray("fields") {
                if (changes.canvas != null) add("canvas")
                if (changes.parameterLinks != null) add("parameterLinks")
                if (changes.parameterTree != null) add("parameterTree")
                if (changes.parameterRoles != null) add("parameterRoles")
                if (changes.rootChildren != null) add("rootChildren")
                if (changes.rootPart != null) add("rootPart")
                if (changes.glues != null) add("glues")
                if (changes.renderRoot != null) add("renderRoot")
                if (changes.textures != null) add("textures")
                if (changes.physicsFps != null) add("physicsFps")
                if (changes.restPose != null) add("restPose")
                if (changes.authoring != null) add("authoring")
                if (changes.advanced != null) add("advanced")
                if (changes.hitAreas != null) add("hitAreas")
            }
        }
    }

    fun decode(value: JsonObject, directory: Path): ModifierModelChanges =
        decode(value) { AlgorithmObjects.read(directory, it) }

    fun decode(value: JsonObject, read: (JsonElement) -> ByteArray): ModifierModelChanges {
        val schema = value.getValue("schema").jsonPrimitive.content
        require(schema == CAPABILITY || schema == RASTER_CAPABILITY) { "Unsupported change schema" }
        val rasterKeys = if (schema == RASTER_CAPABILITY) setOf("rasters") else emptySet()
        require(value.keys == setOf("schema", "before", "after", "objects", "fields") + rasterKeys) { "Invalid change envelope" }
        val operations = value.getValue("objects").jsonObject
        require(operations.keys.all { it in objectNames }) { "Unknown object change collection" }
        val fields = value.getValue("fields").jsonArray.map { it.jsonPrimitive.content }
        require(fields.distinct().size == fields.size && fields.all { it in fieldNames }) { "Unknown or duplicate changed field" }
        var total = 0L
        val counts = operations.mapValues { (_, encoded) ->
            encoded.jsonArray.map { it.jsonPrimitive.int }.also { values ->
                require(values.size == 4 && values.all { it in 0..10000 }) { "Invalid change counts" }
                total += values.sumOf { it.toLong() }
            }
        }
        require(total <= 10000) { "Too many changes" }
        val before = RigIrBinary.decode(read(value.getValue("before")))
        val after = RigIrBinary.decode(read(value.getValue("after")))
        fun <T> objects(name: String, old: List<T>, new: List<T>): ModifierObjectChanges<T> {
            val (add, update, remove, reuse) = counts[name] ?: listOf(0, 0, 0, 0)
            require(old.size == remove + reuse + update && new.size == add + update) { "Change payload count mismatch: $name" }
            return ModifierObjectChanges(added = new.take(add),
                updated = List(update) { ModifierObjectUpdate(old[remove + reuse + it], new[add + it]) },
                removed = old.take(remove), reused = old.subList(remove, remove + reuse))
        }
        fun <T> field(name: String, old: T, new: T): ModifierFieldChange<T>? =
            if (name in fields) ModifierFieldChange(old, new) else null
        return ModifierModelChanges(
            rasters = if (schema == RASTER_CAPABILITY) AlgorithmRasters.decode(value.getValue("rasters").jsonArray, read) else emptyList(),
            parameters = objects("parameters", before.parameters, after.parameters),
            parts = objects("parts", before.parts, after.parts),
            deformers = objects("deformers", before.deformers, after.deformers),
            meshes = objects("meshes", before.meshes, after.meshes),
            physicsGroups = objects("physicsGroups", before.physics.groups, after.physics.groups),
            clips = objects("clips", before.clips, after.clips),
            canvas = field("canvas", before.canvas, after.canvas),
            parameterLinks = field("parameterLinks", before.parameterLinks, after.parameterLinks),
            parameterTree = field("parameterTree", before.parameterTree, after.parameterTree),
            parameterRoles = field("parameterRoles", before.parameterRoles, after.parameterRoles),
            rootChildren = field("rootChildren", before.rootChildren, after.rootChildren),
            rootPart = field("rootPart", before.rootPart, after.rootPart),
            glues = field("glues", before.glues, after.glues),
            renderRoot = field("renderRoot", before.renderRoot, after.renderRoot),
            textures = field("textures", before.textures, after.textures),
            physicsFps = field("physicsFps", before.physics.fps, after.physics.fps),
            restPose = field("restPose", before.restPose, after.restPose),
            authoring = field("authoring", before.authoring, after.authoring),
            advanced = field("advanced", before.advanced, after.advanced),
            hitAreas = field("hitAreas", before.hitAreas, after.hitAreas),
        )
    }
}
