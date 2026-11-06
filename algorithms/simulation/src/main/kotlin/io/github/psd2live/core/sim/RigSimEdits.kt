package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsInput
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.VertexGroupKind

/** What a simulated body is made of. Rigid and soft bodies come later. */
enum class SimKind(val jsonName: String) {
    CLOTH("cloth"),
    HAIR("hair");

    companion object {
        fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown simulation kind: $text (${entries.joinToString { it.jsonName }})")
    }
}

/**
 * What one glue does in a simulation. A glue is never a pin by itself: the user picks this per glue, and
 * [IGNORE] - the runtime weld only - is the default.
 */
enum class GlueRole(val jsonName: String) {
    IGNORE("ignore"),
    /** The simulated side's glued vertices follow the other side's deformed vertices. */
    PIN("pin"),
    /** Both sides are simulated and held together, weighted as the glue weights them. */
    CONSTRAINT("constraint");

    companion object {
        fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown glue role: $text (ignore, pin, constraint)")
    }
}

/** The key a glue role is stored under: its two meshes, as the glue itself is addressed. */
fun glueKey(glue: Glue): String = "${glue.meshA.raw}|${glue.meshB.raw}"

/**
 * Material values, each 0..1 except [mass]. A vertex group of the matching kind multiplies its value per
 * vertex, so painting 0 removes it there and 1 keeps the full value.
 */
data class SimMaterial(
    /** Relative particle mass. */
    val mass: Float = 1f,
    /** Resistance to stretching; cloth and hair want this near 1. */
    val stretch: Float = 0.98f,
    /** Resistance to bending. */
    val bend: Float = 0.3f,
    /** Velocity damping rate in 1/s. */
    val damping: Float = 1.5f,
    /** Spring toward the rig-carried rest shape; 0 turns it off. */
    val goal: Float = 0.1f,
    /** How far a vertex may drift from its pinned root beyond the rest path, as a fraction (long-range limit). */
    val slack: Float = 0.03f,
    /** Resistance to bunching up: how firmly each triangle keeps its area. Light cloth low, heavy parts high. */
    val area: Float = 0.3f,
    /**
     * How much softer the material is across its grain than along it, the grain running away from the
     * pins: 0 is alike every way, 1 lets strands part and fold lengthwise while they keep their length.
     */
    val anisotropy: Float = 0.3f,
) {
    init {
        require(listOf(mass, stretch, bend, damping, goal, slack, area, anisotropy).all(Float::isFinite)) { "Material values must be finite" }
        require(mass > 0f && stretch in 0f..1f && bend in 0f..1f && damping >= 0f && goal in 0f..1f && slack in 0f..1f &&
            area in 0f..1f && anisotropy in 0f..1f) {
            "Material out of range: mass > 0, stretch/bend/goal/slack/area/anisotropy 0..1, damping >= 0"
        }
    }

    fun toJson() = buildJsonObject {
        put("mass", mass); put("stretch", stretch); put("bend", bend); put("damping", damping); put("goal", goal); put("slack", slack)
        put("area", area); put("anisotropy", anisotropy)
    }

    companion object {
        fun fromJson(o: JsonObject, base: SimMaterial = SimMaterial()) = SimMaterial(
            o.number("mass") ?: base.mass, o.number("stretch") ?: base.stretch, o.number("bend") ?: base.bend,
            o.number("damping") ?: base.damping, o.number("goal") ?: base.goal, o.number("slack") ?: base.slack,
            o.number("area") ?: base.area, o.number("anisotropy") ?: base.anisotropy,
        )

        fun preset(kind: SimKind) = SimMaterialPreset.default(kind).material
    }
}

/**
 * Named materials for each kind. Picking one only fills in the values, so a project keeps the values and
 * never the name; the panel names the preset a material matches.
 */
enum class SimMaterialPreset(val jsonName: String, val kind: SimKind, val material: SimMaterial) {
    COTTON("cotton", SimKind.CLOTH, SimMaterial(stretch = 0.98f, bend = 0.3f, damping = 1.5f, goal = 0.1f, area = 0.3f, anisotropy = 0.3f)),
    /** Light and smooth: it falls in soft folds, follows through long and settles slowly. */
    SILK("silk", SimKind.CLOTH, SimMaterial(mass = 0.6f, stretch = 0.98f, bend = 0.12f, damping = 0.8f, goal = 0.05f, slack = 0.04f, area = 0.15f, anisotropy = 0.4f)),
    /** Sheer and airy: the lightest and limpest, the air stills it sooner than silk. */
    CHIFFON("chiffon", SimKind.CLOTH, SimMaterial(mass = 0.4f, stretch = 0.96f, bend = 0.06f, damping = 1.2f, goal = 0.04f, slack = 0.05f, area = 0.1f, anisotropy = 0.35f)),
    /** Heavy and soft: it swings late and settles quickly. */
    WOOL("wool", SimKind.CLOTH, SimMaterial(mass = 1.4f, stretch = 0.97f, bend = 0.4f, damping = 2.2f, goal = 0.12f, slack = 0.03f, area = 0.45f, anisotropy = 0.25f)),
    /** Heavy and stiff: broad folds that keep their shape. */
    DENIM("denim", SimKind.CLOTH, SimMaterial(mass = 1.6f, stretch = 0.99f, bend = 0.6f, damping = 2f, goal = 0.2f, slack = 0.015f, area = 0.6f, anisotropy = 0.2f)),
    /** The stiffest: it moves as a whole, barely bunches and springs back to its drawn shape. */
    LEATHER("leather", SimKind.CLOTH, SimMaterial(mass = 1.8f, stretch = 1f, bend = 0.75f, damping = 2.5f, goal = 0.3f, slack = 0.01f, area = 0.75f, anisotropy = 0.15f)),
    /** Stretches and snaps back, as a band or a stocking does. */
    ELASTIC("elastic", SimKind.CLOTH, SimMaterial(mass = 0.8f, stretch = 0.85f, bend = 0.4f, damping = 1.2f, goal = 0.3f, slack = 0.08f, area = 0.7f, anisotropy = 0.1f)),
    HAIR("hair", SimKind.HAIR, SimMaterial(stretch = 1f, bend = 0.45f, damping = 2f, goal = 0.15f, slack = 0.01f, area = 0.5f, anisotropy = 0.7f)),
    /** Fine strands: light, they curl and part easily and keep swinging. */
    FINE_HAIR("fine_hair", SimKind.HAIR, SimMaterial(mass = 0.7f, stretch = 1f, bend = 0.3f, damping = 1.6f, goal = 0.1f, slack = 0.015f, area = 0.4f, anisotropy = 0.8f)),
    /** Thick or set hair: heavier locks that swing as one and hold their shape. */
    THICK_HAIR("thick_hair", SimKind.HAIR, SimMaterial(mass = 1.3f, stretch = 1f, bend = 0.6f, damping = 2.4f, goal = 0.22f, slack = 0.008f, area = 0.6f, anisotropy = 0.6f)),
    /**
     * Bangs lying on the forehead: drawn sweeping across it rather than hanging, so gravity is not carried
     * along the strands and a goal as weak as hanging hair's sags them into a slack spring that resonates
     * with a head shake. A firm goal and more damping keep them resting on the head, the tips trailing.
     */
    BANGS("bangs", SimKind.HAIR, SimMaterial(stretch = 1f, bend = 0.45f, damping = 4f, goal = 0.4f, slack = 0.01f, area = 0.5f, anisotropy = 0.7f));

    companion object {
        fun default(kind: SimKind) = when (kind) {
            SimKind.CLOTH -> COTTON
            SimKind.HAIR -> HAIR
        }

        fun of(kind: SimKind) = entries.filter { it.kind == kind }

        /** The preset whose values [material] has, or null for a custom one. */
        fun matching(material: SimMaterial) = entries.firstOrNull { it.material == material }

        fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown material preset: $text (${entries.joinToString { it.jsonName }})")
    }
}

/**
 * The span of an input the bake trains on, in its parameter's units: the motion library drives it between
 * [min] and [max] instead of the whole range, so the mode parameters reach their ends at that span.
 */
data class SimInputRange(val min: Float, val max: Float) {
    init {
        require(min.isFinite() && max.isFinite() && min < max) { "An input range needs min < max" }
    }

    fun toJson() = buildJsonArray { add(min); add(max) }

    companion object {
        fun fromJson(e: JsonElement) = e.jsonArray.let { SimInputRange(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }
    }
}

/**
 * How one baked mode parameter is written back: under [id] instead of the generated one, spanning
 * ±[range] instead of ±[SimGenerator.MODE_RANGE], and swinging [gain] times as far as simulated instead of
 * the body's exaggeration. All three apply without baking again; null keeps the default.
 */
data class SimOutput(val id: String? = null, val range: Float? = null, val gain: Float? = null) {
    init {
        require(id == null || id.isNotBlank() && id.none { it.isWhitespace() || it.isISOControl() }) { "An output ID must not be blank or contain spaces" }
        require(range == null || range in RigSimEdit.OUTPUT_RANGES) { "An output range is within ${RigSimEdit.OUTPUT_RANGES}" }
        require(gain == null || gain in RigSimEdit.OUTPUT_GAINS) { "An output gain is within ${RigSimEdit.OUTPUT_GAINS}" }
    }

    val isDefault: Boolean get() = id == null && range == null && gain == null

    fun toJson() = buildJsonObject {
        id?.let { put("id", it) }; range?.let { put("range", it) }; gain?.let { put("gain", it) }
    }

    companion object {
        fun fromJson(o: JsonObject) = SimOutput(o.string("id")?.trim()?.ifEmpty { null }, o.number("range"), o.number("gain"))
    }
}

/**
 * One simulated body: ArtMesh [targets] simulated together, held by pins and glue.
 *
 * [groups] names the vertex group used for each kind; a kind without an entry uses the target's first
 * group of that kind, and none at all means the material value everywhere (no pins, for [VertexGroupKind.PIN]).
 */
/**
 * A body the simulated particles keep out of: a circle on vertex [a] of [mesh] (not a target), or with [b] a
 * capsule along the two, following the mesh as the rig deforms it - an arm, a leg, the torso. [radius] runs to
 * [radiusB] at [b]; [friction] 0..1 is the share of a touching particle's slide taken away.
 */
data class SimCollider(val mesh: String, val a: Int, val b: Int = a, val radius: Float, val radiusB: Float = radius, val friction: Float = 0f) {
    init {
        require(mesh.isNotBlank() && a >= 0 && b >= 0) { "A collider needs a mesh and its vertices" }
        require(radius.isFinite() && radius > 0f && radiusB.isFinite() && radiusB > 0f) { "A collider radius must be positive" }
        require(friction in 0f..1f) { "Collider friction is within 0..1" }
    }

    fun toJson() = buildJsonObject {
        put("mesh", mesh); put("a", a); if (b != a) put("b", b)
        put("radius", radius); if (radiusB != radius) put("radius_b", radiusB)
        if (friction != 0f) put("friction", friction)
    }

    companion object {
        fun fromJson(o: JsonObject): SimCollider {
            val a = requireNotNull(o["a"]?.jsonPrimitive?.intOrNull) { "A collider needs vertex a" }
            val radius = requireNotNull(o.number("radius")) { "A collider needs a radius" }
            return SimCollider(requireNotNull(o.string("mesh")) { "A collider needs a mesh" }, a, o["b"]?.jsonPrimitive?.intOrNull ?: a,
                radius, o.number("radius_b") ?: radius, o.number("friction") ?: 0f)
        }
    }
}

data class RigSimEdit(
    val id: String,
    val name: String,
    val kind: SimKind,
    val targets: List<String>,
    val material: SimMaterial = SimMaterial.preset(kind),
    val groups: Map<VertexGroupKind, String> = emptyMap(),
    val glueRoles: Map<String, GlueRole> = emptyMap(),
    /**
     * Parameters that move the rig during training and preview; the bake reads them. Always explicit: a new
     * body starts from [defaultInputs], and empty means nothing shakes it.
     */
    val inputs: List<PhysicsInput> = emptyList(),
    /** Per input, the span the bake trains on; an input left out trains over its parameter's whole range. */
    val inputRanges: Map<String, SimInputRange> = emptyMap(),
    val enabled: Boolean = true,
    /** Sideways modes the bake keeps, 1..[MAX_MODES]: one parameter and one pendulum vertex each. */
    val modes: Int = 2,
    /**
     * Whether the vertical inputs bake into an up-and-down parameter of their own ([SimGenerator.verticalParameterId]):
     * null when they move the body enough, true always, false never (they then take no part in the bake).
     */
    val vertical: Boolean? = null,
    /**
     * Parameters whose pose is baked exactly, as corrections on their own axes. Null bakes none.
     */
    val staticInputs: List<String>? = null,
    /** Keys on each mode parameter and static axis, odd within [KEY_COUNTS]: more follow arcs and pushes more closely. */
    val keys: Int = 5,
    /**
     * Writes the modes as blend shapes (true) or keyform axes (false) where the target runtime has blend
     * shapes; null picks blend shapes only where keyform axes would multiply a mesh's keyforms past a few dozen.
     */
    val blendShapes: Boolean? = null,
    /** Bakes again with every change made in the simulation panel or through MCP, in the same history step. */
    val autoBake: Boolean = true,
    /**
     * How much larger than simulated the modes swing, within [EXAGGERATIONS]: the key shapes are scaled as
     * they are written back, so it applies without baking again and never pushes the parameters to ±1.
     */
    val exaggeration: Float = DEFAULT_EXAGGERATION,
    /**
     * Names given to the bake's outputs, keyed by parameter or pendulum ID; the others are named after the
     * body. Names only: they apply without baking again.
     */
    val outputNames: Map<String, String> = emptyMap(),
    /** How each mode parameter is written back, keyed by the ID the bake gives it. */
    val outputs: Map<String, SimOutput> = emptyMap(),
    /** The materialized bake; the rebuild writes it back without simulating. */
    val bake: SimBakeResult? = null,
    /** Bodies the particles keep out of; the bake learns the motion they shape. */
    val colliders: List<SimCollider> = emptyList(),
    /** Inputs the bake keeps even when the rig shows them moving the body too little to bake. */
    val forcedInputs: List<String> = emptyList(),
    /**
     * Motions (clip IDs or built-in names) the bake also trains on, each with how much it counts against the
     * designed motion, within [TRAINING_WEIGHTS]: for a model played mostly through a few motions of its own.
     */
    val trainingClips: Map<String, Float> = emptyMap(),
) {
    init {
        require(listOf(id, name).all { it.isNotBlank() && it.none(Char::isISOControl) }) { "Simulation ID and name are required" }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.all { it.isNotBlank() }) { "Simulation needs distinct target meshes" }
        require(inputs.map { it.parameter }.distinct().size == inputs.size) { "A parameter feeds a simulation once" }
        require(modes in 1..MAX_MODES) { "A simulation bakes 1..$MAX_MODES modes" }
        require(staticInputs == null || staticInputs.size <= MAX_STATIC_INPUTS && staticInputs.distinct().size == staticInputs.size) {
            "At most $MAX_STATIC_INPUTS distinct static inputs"
        }
        require(keys in KEY_COUNTS && keys % 2 == 1) { "A simulation bakes an odd number of keys within $KEY_COUNTS" }
        require(exaggeration in EXAGGERATIONS) { "Exaggeration is within $EXAGGERATIONS" }
        require(outputNames.all { (k, v) -> k.isNotBlank() && v.isNotBlank() && v.none(Char::isISOControl) }) { "Output names must not be blank" }
        require(outputs.values.none { it.isDefault }) { "An output setting must change something" }
        require(outputs.keys.map(::outputId).let { it.distinct().size == it.size }) { "Each output needs its own ID" }
        require(colliders.none { it.mesh in targets }) { "A collider rides a mesh the simulation does not move" }
        require(forcedInputs.distinct().size == forcedInputs.size && forcedInputs.all { forced -> inputs.any { it.parameter == forced } }) {
            "Only listed inputs can be kept"
        }
        require(trainingClips.all { (clip, weight) -> clip.isNotBlank() && weight in TRAINING_WEIGHTS }) { "A training motion counts within $TRAINING_WEIGHTS" }
    }

    /** This edit reading [inputs] instead: the training ranges and kept inputs of those left out go with them. */
    fun withInputs(inputs: List<PhysicsInput>): RigSimEdit = copy(inputs = inputs,
        inputRanges = inputRanges.filterKeys { p -> inputs.any { it.parameter == p } },
        forcedInputs = forcedInputs.filter { p -> inputs.any { it.parameter == p } })

    /** The ID mode parameter [baked] (as the bake names it) is written under. */
    fun outputId(baked: String): String = outputs[baked]?.id ?: baked

    /** The ±range mode parameter [baked] spans. */
    fun outputRange(baked: String): Float = outputs[baked]?.range ?: SimGenerator.MODE_RANGE

    /** How much farther than simulated mode [baked] swings. */
    fun outputGain(baked: String): Float = outputs[baked]?.gain ?: exaggeration

    /** The mode parameters the bake writes, under their output IDs. */
    val outputParameters: List<String> get() = bake?.parameters.orEmpty().map(::outputId)

    /** The mode parameters' keys as the bake solves them: [keys] values evenly spread over -1..1, written out times [SimGenerator.MODE_RANGE]. */
    val modeKeys: FloatArray get() = FloatArray(keys) { -1f + 2f * it / (keys - 1) }

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name); put("kind", kind.jsonName)
        putJsonArray("targets") { targets.forEach { add(it) } }
        put("material", material.toJson())
        if (groups.isNotEmpty()) putJsonObject("groups") { groups.forEach { (k, v) -> put(k.jsonName, v) } }
        if (glueRoles.isNotEmpty()) putJsonObject("glue_roles") { glueRoles.forEach { (k, v) -> put(k, v.jsonName) } }
        putJsonArray("inputs") { inputs.forEach { add(it.toJson()) } }
        if (inputRanges.isNotEmpty()) putJsonObject("input_ranges") { inputRanges.forEach { (k, v) -> put(k, v.toJson()) } }
        if (!enabled) put("enabled", false)
        if (modes != 2) put("modes", modes)
        vertical?.let { put("vertical", it) }
        staticInputs?.let { list -> putJsonArray("static_inputs") { list.forEach { add(it) } } }
        if (keys != 5) put("keys", keys)
        blendShapes?.let { put("blend_shapes", it) }
        if (!autoBake) put("auto_bake", false)
        if (exaggeration != DEFAULT_EXAGGERATION) put("exaggeration", exaggeration)
        if (outputNames.isNotEmpty()) putJsonObject("output_names") { outputNames.forEach { (k, v) -> put(k, v) } }
        if (outputs.isNotEmpty()) putJsonObject("outputs") { outputs.forEach { (k, v) -> put(k, v.toJson()) } }
        // "colliders" held the retired margin-based collision and is ignored on load.
        if (colliders.isNotEmpty()) putJsonArray("obstacles") { colliders.forEach { add(it.toJson()) } }
        if (forcedInputs.isNotEmpty()) putJsonArray("force_inputs") { forcedInputs.forEach { add(it) } }
        if (trainingClips.isNotEmpty()) putJsonObject("training_clips") { trainingClips.forEach { (k, v) -> put(k, v) } }
        bake?.let { put("bake", it.toJson()) }
    }

    /**
     * [o] laid over this edit: arrays and maps replace, `material` merges field by field over `material_preset`
     * when given. `bake` is taken as given (null clears it); leaving it out keeps the bake, which then reads as
     * stale if the setup changed.
     */
    fun patched(o: JsonObject): RigSimEdit {
        val nextKind = o.string("kind")?.let(SimKind::parse) ?: kind
        val nextInputs = o["inputs"]?.jsonArray?.map { PhysicsInput.fromJson(it.jsonObject) } ?: inputs
        val baseMaterial = o.string("material_preset")?.let(SimMaterialPreset::parse)?.material
            ?: if (nextKind != kind) SimMaterial.preset(nextKind) else material
        return copy(
            name = o.string("name") ?: name,
            kind = nextKind,
            targets = o["targets"]?.jsonArray?.map { it.jsonPrimitive.content } ?: targets,
            material = o["material"]?.jsonObject?.let { SimMaterial.fromJson(it, baseMaterial) } ?: baseMaterial,
            groups = o["groups"]?.jsonObject?.filterKeys { it.lowercase() !in VertexGroupKind.RETIRED }
                ?.map { (k, v) -> VertexGroupKind.parse(k) to v.jsonPrimitive.content }?.toMap() ?: groups,
            glueRoles = o["glue_roles"]?.jsonObject?.map { (k, v) -> k to GlueRole.parse(v.jsonPrimitive.content) }?.toMap() ?: glueRoles,
            inputs = nextInputs,
            inputRanges = o["input_ranges"]?.jsonObject?.mapValues { (_, v) -> SimInputRange.fromJson(v) } ?: inputRanges,
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: enabled,
            modes = o["modes"]?.jsonPrimitive?.intOrNull ?: modes,
            vertical = when (val value = o["vertical"]) {
                null -> vertical
                is JsonNull -> null
                else -> value.jsonPrimitive.booleanOrNull ?: vertical
            },
            staticInputs = when (val value = o["static_inputs"]) {
                null -> staticInputs
                is JsonNull -> null
                else -> value.jsonArray.map { it.jsonPrimitive.content }
            },
            keys = o["keys"]?.jsonPrimitive?.intOrNull ?: keys,
            blendShapes = when (val value = o["blend_shapes"]) {
                null -> blendShapes
                is JsonNull -> null
                else -> value.jsonPrimitive.booleanOrNull ?: blendShapes
            },
            autoBake = o["auto_bake"]?.jsonPrimitive?.booleanOrNull ?: autoBake,
            exaggeration = o["exaggeration"]?.jsonPrimitive?.floatOrNull ?: exaggeration,
            outputNames = o["output_names"]?.jsonObject?.map { (k, v) -> k to v.jsonPrimitive.content.trim() }
                ?.filter { it.second.isNotEmpty() }?.toMap() ?: outputNames,
            outputs = o["outputs"]?.jsonObject?.mapValues { (_, v) -> SimOutput.fromJson(v.jsonObject) }?.filterValues { !it.isDefault } ?: outputs,
            bake = when (val value = o["bake"]) {
                null -> bake
                is JsonNull -> null
                else -> SimBakeResult.fromJson(value.jsonObject)
            },
            colliders = o["obstacles"]?.jsonArray?.map { SimCollider.fromJson(it.jsonObject) } ?: colliders,
            // An input taken off the list is no longer kept.
            forcedInputs = (o["force_inputs"]?.jsonArray?.map { it.jsonPrimitive.content }?.distinct() ?: forcedInputs)
                .filter { forced -> nextInputs.any { it.parameter == forced } },
            trainingClips = o["training_clips"]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.float } ?: trainingClips,
        )
    }

    companion object {
        const val MAX_MODES = 3
        const val MAX_STATIC_INPUTS = 4
        val KEY_COUNTS = 3..9
        val EXAGGERATIONS = 1f..2f
        const val DEFAULT_EXAGGERATION = 1.3f
        /** The ±range a mode parameter may span, and how much farther than simulated one may swing. */
        val OUTPUT_RANGES = 1f..100f
        val OUTPUT_GAINS = 0f..3f
        val TRAINING_WEIGHTS = 0.05f..1f

        /**
         * The inputs a new body starts from: the head and body turning and tilting, then nodding and the body
         * rising, sinking and leaning. Clothing hangs from the body, so a nod does not move it, and leaning in
         * mostly holds it in another pose, which its up-and-down pendulum cannot hold and goes limp trying to:
         * of the vertical inputs it takes only the body rising and sinking.
         */
        fun defaultInputs(available: Set<String>?, kind: SimKind? = null): List<PhysicsInput> =
            listOf(
                PhysicsInput("ParamAngleX", 60f, io.github.psd2live.core.PhysicsSourceType.X),
                PhysicsInput("ParamAngleZ", 60f, io.github.psd2live.core.PhysicsSourceType.ANGLE),
                PhysicsInput("ParamBodyAngleX", 40f, io.github.psd2live.core.PhysicsSourceType.X),
                PhysicsInput("ParamBodyAngleZ", 40f, io.github.psd2live.core.PhysicsSourceType.ANGLE),
            ).filter { available == null || it.parameter in available } +
                VERTICAL_INPUTS.filter { (available == null || it in available) && (kind != SimKind.CLOTH || it == CLOTH_VERTICAL_INPUT) }
                    .map { PhysicsInput(it, 40f, io.github.psd2live.core.PhysicsSourceType.X) }

        val VERTICAL_INPUTS = listOf("ParamAngleY", "ParamBodyAngleY", "ParamBodyLean")

        private const val CLOTH_VERTICAL_INPUT = "ParamBodyAngleY"

        /**
         * An edit saved before inputs were always written down, where none meant the defaults, takes them as
         * listed; those the model lacks drop out with its next edit ([SimAuthoring.put]).
         */
        fun fromJson(o: JsonObject): RigSimEdit {
            val kind = o.string("kind")?.let(SimKind::parse) ?: SimKind.CLOTH
            val id = requireNotNull(o.string("id")) { "id is required" }
            val targets = requireNotNull(o["targets"]?.jsonArray) { "targets is required" }.map { it.jsonPrimitive.content }
            val inputs = if ("inputs" in o) emptyList() else defaultInputs(null)
            return RigSimEdit(id, o.string("name") ?: id, kind, targets, inputs = inputs).patched(o)
        }
    }
}

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.floatOrNull
