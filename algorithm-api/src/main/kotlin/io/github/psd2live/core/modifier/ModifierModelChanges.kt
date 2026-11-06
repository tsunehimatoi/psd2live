package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.*

/** Object replacement is exclusive; additive geometry composition must be resolved before producing this edit. */
data class ModifierObjectUpdate<T>(val before: T, val after: T)
enum class ModifierChangeAction { ADD, UPDATE, REMOVE, REUSE }
data class ModifierObjectContribution(val kind: String, val id: String, val action: ModifierChangeAction)

/** Ordered edits with explicit preconditions. Unmentioned objects retain their identity and position. */
class ModifierObjectChanges<T>(
    added: List<T> = emptyList(), updated: List<ModifierObjectUpdate<T>> = emptyList(),
    removed: List<T> = emptyList(), reused: List<T> = emptyList(),
) {
    val added = added.toList()
    val updated = updated.toList()
    val removed = removed.toList()
    val reused = reused.toList()
    val hasWrites get() = added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()
    val isEmpty get() = !hasWrites && reused.isEmpty()

    fun contributions(kind: String, id: (T) -> String): List<ModifierObjectContribution> =
        added.map { ModifierObjectContribution(kind, id(it), ModifierChangeAction.ADD) } +
        updated.map { ModifierObjectContribution(kind, id(it.after), ModifierChangeAction.UPDATE) } +
        removed.map { ModifierObjectContribution(kind, id(it), ModifierChangeAction.REMOVE) } +
        reused.map { ModifierObjectContribution(kind, id(it), ModifierChangeAction.REUSE) }

    fun apply(input: List<T>, id: (T) -> String, checkpoint: () -> Unit = {}): List<T> {
        if (isEmpty) return input
        val current = LinkedHashMap<String, T>()
        input.forEach { value ->
            checkpoint()
            require(current.put(id(value), value) == null) { "Duplicate input object: ${id(value)}" }
        }
        val touched = HashSet<String>()
        fun claim(value: T): String = id(value).also {
            require(it.isNotBlank() && touched.add(it)) { "Duplicate or empty change target: $it" }
        }
        fun expect(value: T): String = claim(value).also {
            require(current.containsKey(it) && current[it] == value) { "Stale or missing object: $it" }
        }
        // Validate the complete candidate before constructing its replacement collection.
        reused.forEach { checkpoint(); expect(it) }
        removed.forEach { checkpoint(); expect(it) }
        updated.forEach {
            checkpoint()
            val key = expect(it.before)
            require(id(it.after) == key) { "An update cannot change object identity: $key" }
        }
        added.forEach {
            checkpoint()
            val key = claim(it)
            require(!current.containsKey(key)) { "Object already exists: $key" }
        }
        if (added.isEmpty() && updated.isEmpty() && removed.isEmpty()) return input
        removed.forEach { checkpoint(); current.remove(id(it)) }
        updated.forEach { checkpoint(); current[id(it.after)] = it.after }
        added.forEach { checkpoint(); current[id(it)] = it }
        return current.values.toList()
    }
}

/** Explicit replacement of a singleton or ordered relation collection, including nullable values. */
data class ModifierFieldChange<T>(val before: T, val after: T) {
    fun apply(input: T): T {
        require(input == before) { "Stale field input" }
        return after
    }
}

/** Typed model edits; untouched fields and geometry buffers are not traversed or copied. */
data class ModifierModelChanges(
    val parameters: ModifierObjectChanges<Parameter> = ModifierObjectChanges(),
    val parts: ModifierObjectChanges<Part> = ModifierObjectChanges(),
    val deformers: ModifierObjectChanges<Deformer> = ModifierObjectChanges(),
    val meshes: ModifierObjectChanges<Mesh> = ModifierObjectChanges(),
    val physicsGroups: ModifierObjectChanges<PhysicsGroup> = ModifierObjectChanges(),
    val clips: ModifierObjectChanges<Clip> = ModifierObjectChanges(),
    val canvas: ModifierFieldChange<Canvas>? = null,
    val parameterLinks: ModifierFieldChange<List<ParameterLink>>? = null,
    val parameterTree: ModifierFieldChange<List<ParameterNode>>? = null,
    val parameterRoles: ModifierFieldChange<List<ParameterRole>>? = null,
    val rootChildren: ModifierFieldChange<List<ChildRef>>? = null,
    val rootPart: ModifierFieldChange<String?>? = null,
    val glues: ModifierFieldChange<List<Glue>>? = null,
    val renderRoot: ModifierFieldChange<RenderGroup>? = null,
    val textures: ModifierFieldChange<Textures>? = null,
    val physicsFps: ModifierFieldChange<Float?>? = null,
    val restPose: ModifierFieldChange<Map<String, Float>>? = null,
    val authoring: ModifierFieldChange<Authoring>? = null,
    val advanced: ModifierFieldChange<AdvancedIR>? = null,
    val hitAreas: ModifierFieldChange<List<HitAreaIR>>? = null,
) {
    fun contributions(): List<ModifierObjectContribution> = buildList {
        addAll(parameters.contributions("parameter") { it.id })
        addAll(parts.contributions("part") { it.id })
        addAll(deformers.contributions("deformer") { it.id })
        addAll(meshes.contributions("mesh") { it.id })
        addAll(physicsGroups.contributions("physics") { it.id })
        addAll(clips.contributions("motion") { it.id })
    }

    /** Conservative section ownership: whole-field replacement never implies composability. */
    fun writeKinds(): Set<String> = buildSet {
        if (parameters.hasWrites) add("parameter")
        if (parts.hasWrites) add("part")
        if (deformers.hasWrites) add("deformer")
        if (meshes.hasWrites) add("mesh")
        if (physicsGroups.hasWrites || physicsFps != null) add("physics")
        if (clips.hasWrites) add("motion")
        if (canvas != null) add("canvas")
        if (parameterLinks != null || parameterRoles != null || glues != null) add("relations")
        if (parameterTree != null || rootChildren != null || rootPart != null || renderRoot != null) add("hierarchy")
        if (textures != null) add("texture")
        if (restPose != null) add("restPose")
        if (authoring != null) add("authoring")
        if (advanced != null) add("advanced")
        if (hitAreas != null) add("hitArea")
    }

    fun requireWrites(allowed: Set<String>) {
        val outside = if ("*" in allowed) emptySet() else writeKinds() - allowed
        require(outside.isEmpty()) { "Undeclared change sections: ${outside.sorted().joinToString()}" }
    }

    /** Candidate only. The host still validates references, write ownership and domain invariants before publishing. */
    fun apply(input: RigIR, checkpoint: () -> Unit = {}): RigIR {
        checkpoint()
        fun <T> field(change: ModifierFieldChange<T>?, value: T): T {
            checkpoint()
            return if (change == null) value else change.apply(value)
        }
        return input.copy(
            parameters = parameters.apply(input.parameters, { it.id }, checkpoint),
            parts = parts.apply(input.parts, { it.id }, checkpoint),
            deformers = deformers.apply(input.deformers, { it.id }, checkpoint),
            meshes = meshes.apply(input.meshes, { it.id }, checkpoint),
            clips = clips.apply(input.clips, { it.id }, checkpoint),
            canvas = field(canvas, input.canvas),
            parameterLinks = field(parameterLinks, input.parameterLinks),
            parameterTree = field(parameterTree, input.parameterTree),
            parameterRoles = field(parameterRoles, input.parameterRoles),
            rootChildren = field(rootChildren, input.rootChildren),
            rootPart = field(rootPart, input.rootPart),
            glues = field(glues, input.glues),
            renderRoot = field(renderRoot, input.renderRoot),
            textures = field(textures, input.textures),
            restPose = field(restPose, input.restPose),
            authoring = field(authoring, input.authoring),
            advanced = field(advanced, input.advanced),
            hitAreas = field(hitAreas, input.hitAreas),
            physics = if (physicsGroups.isEmpty && physicsFps == null) input.physics else input.physics.copy(
                groups = physicsGroups.apply(input.physics.groups, { it.id }, checkpoint),
                fps = field(physicsFps, input.physics.fps)),
        ).also { checkpoint() }
    }
}

/** Direct-output extension. Legacy workers can materialize it until the incremental wire protocol is negotiated. */
interface ChangingModifierAlgorithmPlugin : ContextualModifierAlgorithmPlugin {
    fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                checkpoint: () -> Unit): ModifierModelChanges

    override fun evaluate(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                          checkpoint: () -> Unit): RigIR = changes(input, instance, context, checkpoint).apply(input, checkpoint)
}
