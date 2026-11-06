package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.*
import kotlinx.serialization.Serializable

/** One observation, including absent objects and collection membership/order. */
@Serializable
data class ModifierReadKey(val section: String, val id: String? = null)

/** Values observed by a successful invocation. Matching never assumes unobserved objects are unchanged. */
class ModifierReadSet internal constructor(private val values: Map<ModifierReadKey, Any?>) {
    val keys: Set<ModifierReadKey> get() = values.keys.toSet()
    companion object {
        /** Reconstruct observations against the exact input sent to a worker, never worker-supplied old values. */
        fun capture(model: RigIR, context: ModifierAlgorithmContext, keys: List<ModifierReadKey>): ModifierReadSet {
            require(keys.size <= 10000 && keys.distinct().size == keys.size) { "Too many or duplicate read observations" }
            require(keys.all { it.section.isNotBlank() && (it.id == null || it.id.isNotBlank()) }) { "Invalid read observation" }
            val input = ModifierEvaluationInput(model, context)
            return ModifierReadSet(keys.associateWith(input::lookup))
        }
    }
    fun matches(model: RigIR, context: ModifierAlgorithmContext = ModifierAlgorithmContext.Empty): Boolean {
        val input = ModifierEvaluationInput(model, context)
        return values.all { (key, value) -> input.lookup(key) == value }
    }
}

/** Read-only views of actual preceding output. Each lookup records the input needed to reproduce the result. */
class ModifierEvaluationInput(private val model: RigIR, private val author: ModifierAlgorithmContext = ModifierAlgorithmContext.Empty) {
    private val observed = LinkedHashMap<ModifierReadKey, Any?>()
    private val indexes = HashMap<String, Map<String, Any>>()
    fun reads(): ModifierReadSet = ModifierReadSet(observed.toMap())
    private fun <T> read(section: String, id: String? = null): T {
        val key = ModifierReadKey(section, id)
        val value = lookup(key)
        observed[key] = value
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
    internal fun lookup(key: ModifierReadKey): Any? {
        if (key.id != null) {
            val index = indexes.getOrPut(key.section) {
                fun <T : Any> index(values: List<T>, id: (T) -> String): Map<String, Any> {
                    val result = values.associateBy(id)
                    require(result.size == values.size) { "Duplicate ${key.section} IDs" }
                    return result
                }
                when (key.section) {
                    "parameter" -> index(model.parameters) { it.id }
                    "mesh" -> index(model.meshes) { it.id }
                    "deformer" -> index(model.deformers) { it.id }
                    "part" -> index(model.parts) { it.id }
                    "physics" -> index(model.physics.groups) { it.id }
                    "motion" -> index(model.clips) { it.id }
                    else -> error("Unknown object section: ${key.section}")
                }
            }
            return index[key.id]
        }
        return when (key.section) {
            "parameter" -> model.parameters
            "mesh" -> model.meshes
            "meshIds" -> model.meshes.map { it.id }
            "deformer" -> model.deformers
            "part" -> model.parts
            "physics" -> model.physics
            "motion" -> model.clips
            "canvas" -> model.canvas
            "parameterLinks" -> model.parameterLinks
            "parameterTree" -> model.parameterTree
            "parameterRoles" -> model.parameterRoles
            "rootChildren" -> model.rootChildren
            "rootPart" -> model.rootPart
            "glue" -> model.glues
            "renderRoot" -> model.renderRoot
            "textures" -> model.textures
            "restPose" -> model.restPose
            "authoring" -> model.authoring
            "advanced" -> model.advanced
            "hitArea" -> model.hitAreas
            "upstream" -> author.upstream
            "context" -> author
            else -> error("Unknown input section: ${key.section}")
        }
    }
    val parameters: List<Parameter> get() = read("parameter")
    val meshes: List<Mesh> get() = read("mesh")
    /** Ordered membership without observing unrelated geometry or drawing channels. */
    val meshIds: List<String> get() = read("meshIds")
    val deformers: List<Deformer> get() = read("deformer")
    val parts: List<Part> get() = read("part")
    val physics: Physics get() = read("physics")
    val clips: List<Clip> get() = read("motion")
    val canvas: Canvas get() = read("canvas")
    val parameterLinks: List<ParameterLink> get() = read("parameterLinks")
    val parameterTree: List<ParameterNode> get() = read("parameterTree")
    val parameterRoles: List<ParameterRole> get() = read("parameterRoles")
    val rootChildren: List<ChildRef> get() = read("rootChildren")
    val rootPart: String? get() = read("rootPart")
    val glues: List<Glue> get() = read("glue")
    val renderRoot: RenderGroup get() = read("renderRoot")
    val textures: Textures get() = read("textures")
    val restPose: Map<String, Float> get() = read("restPose")
    val authoring: Authoring get() = read("authoring")
    val advanced: AdvancedIR get() = read("advanced")
    val hitAreas: List<HitAreaIR> get() = read("hitArea")
    val context: ModifierAlgorithmContext get() = read("context")
    val upstream: List<ModifierUpstreamCapability> get() = read("upstream")
    fun providers(capability: String): List<ModifierUpstreamCapability> = upstream.filter { it.capability == capability }
    fun parameter(id: String): Parameter? = read("parameter", id)
    fun mesh(id: String): Mesh? = read("mesh", id)
    fun deformer(id: String): Deformer? = read("deformer", id)
    fun part(id: String): Part? = read("part", id)
    fun physics(id: String): PhysicsGroup? = read("physics", id)
    fun motion(id: String): Clip? = read("motion", id)

    /** Immediate parent first; coordinates remain in each object's declared local frame. */
    fun parentsOfMesh(id: String): List<Deformer> {
        val target = requireNotNull(mesh(id)) { "Missing mesh: $id" }
        val result = ArrayList<Deformer>()
        val visited = HashSet<String>()
        var parent = target.parent
        while (parent != null) {
            require(visited.add(parent)) { "Deformer cycle at $parent" }
            val item = requireNotNull(deformer(parent)) { "Missing parent deformer: $parent" }
            result += item
            parent = item.parent
        }
        return result
    }
}

/** Implementations cannot access the complete RigIR through this entrypoint. */
interface ReadingModifierAlgorithmPlugin : ChangingModifierAlgorithmPlugin {
    fun changes(input: ModifierEvaluationInput, instance: RigModifierInstance, checkpoint: () -> Unit): ModifierModelChanges
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges = changes(ModifierEvaluationInput(input, context), instance, checkpoint)
}
