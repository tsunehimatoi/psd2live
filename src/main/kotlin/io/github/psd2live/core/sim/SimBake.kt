package io.github.psd2live.core.sim

import io.github.psd2live.core.quality.*

import io.github.psd2live.core.RigPhysicsEdit
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel
import java.security.MessageDigest

/**
 * Keys on one parameter axis, with what each target mesh adds at each key: local mesh deltas, the same
 * space the mesh's keyforms are stored in. Between keys the offsets blend as keyforms do.
 */
class SimBakedAxis(val parameter: String, val keys: FloatArray, val offsets: Map<String, List<FloatArray>>) {
    init {
        require(parameter.isNotBlank()) { "Baked axis needs a parameter" }
        require(keys.size >= 2 && keys.toList() == keys.sorted() && keys.distinct().size == keys.size) { "Baked axis needs sorted distinct keys" }
        require(offsets.values.all { per -> per.size == keys.size && per.map { it.size }.distinct().size <= 1 }) { "Baked offsets need one array per key" }
    }

    /** The offsets of [mesh] at parameter [value]: linear between keys, held past the ends. */
    fun at(mesh: String, value: Float): FloatArray? {
        val per = offsets[mesh] ?: return null
        if (value <= keys.first()) return per.first()
        if (value >= keys.last()) return per.last()
        val upper = keys.indexOfFirst { it >= value }
        val lower = upper - 1
        val t = (value - keys[lower]) / (keys[upper] - keys[lower])
        val a = per[lower]; val b = per[upper]
        return FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }
    }

    fun toJson() = buildJsonObject {
        put("parameter", parameter)
        putJsonArray("keys") { keys.forEach { add(it) } }
        putJsonObject("offsets") {
            for ((mesh, per) in offsets) putJsonArray(mesh) { per.forEach { form -> addJsonArray { form.forEach { add(round(it)) } } } }
        }
    }

    companion object {
        fun fromJson(o: JsonObject) = SimBakedAxis(
            o.getValue("parameter").jsonPrimitive.content,
            o.getValue("keys").jsonArray.map { it.jsonPrimitive.float }.toFloatArray(),
            o.getValue("offsets").jsonObject.mapValues { (_, per) -> per.jsonArray.map { form -> form.jsonArray.map { it.jsonPrimitive.float }.toFloatArray() } },
        )

        /** Six significant digits: a lattice-local delta is a fraction of the lattice, so plain decimals would lose it. */
        private fun round(value: Float): Float = "%.6g".format(java.util.Locale.ROOT, value).toFloat()
    }
}

/** One dynamic mode: the axis of its parameter, how far it swings (px) and its share of the motion. */
class SimBakedMode(val axis: SimBakedAxis, val amplitude: Float, val energy: Float) {
    fun toJson() = buildJsonObject {
        put("axis", axis.toJson()); put("amplitude_px", amplitude); put("energy", energy)
    }

    companion object {
        fun fromJson(o: JsonObject) = SimBakedMode(SimBakedAxis.fromJson(o.getValue("axis").jsonObject),
            o["amplitude_px"]?.jsonPrimitive?.floatOrNull ?: 0f, o["energy"]?.jsonPrimitive?.floatOrNull ?: 0f)
    }
}

/**
 * A simulation reduced to what Cubism can play: static corrections on the parameters that push the body
 * ([statics]; a leg lifting the skirt), and dynamic [modes], each a parameter of keyforms driven by one
 * vertex of the fitted [physics] pendulum. Materialized when baked and only written back on rebuild, never
 * simulated again.
 *
 * [fingerprint] is [SimBake.fingerprint] of what it was baked from; when the setup drifts from it the
 * bake is stale but still exports. Compared by content, so equal bakes are equal edits.
 */
class SimBakeResult(
    val fingerprint: String,
    val vertexCounts: Map<String, Int>,
    val statics: List<SimBakedAxis>,
    val modes: List<SimBakedMode>,
    /** The pendulum driving [modes]; null when there are none. */
    val physics: RigPhysicsEdit? = null,
    /** Share of the simulated motion the pendulum and keys reproduce (R²), on held-out motion the fit never saw. */
    val fit: Float = 0f,
    /** How far, in px, the baked result strays from the simulation on that motion (95th percentile of frames). */
    val maxErrorPx: Float = 0f,
    /** How much of its range the busiest mode parameter uses on that motion, 0..1 (1 = it reaches ±1). */
    val peak: Float = 0f,
    /** Share of frames where a mode parameter sits at ±1 on that motion; the body stalls there. */
    val clipped: Float = 0f,
    /** How jerky the baked motion is against the simulation's (third differences, 1 = as smooth). */
    val jerk: Float = 0f,
    /** Pendulums of their own for later modes that react apart from the swing; [physics] drives the rest. */
    val extraPhysics: List<RigPhysicsEdit> = emptyList(),
) {
    /** Every pendulum the bake writes. */
    val pendulums: List<RigPhysicsEdit> get() = listOfNotNull(physics) + extraPhysics

    val parameters: List<String> get() = modes.map { it.axis.parameter }

    fun toJson() = buildJsonObject {
        put("fingerprint", fingerprint)
        putJsonObject("vertex_counts") { vertexCounts.forEach { (k, v) -> put(k, v) } }
        if (statics.isNotEmpty()) putJsonArray("statics") { statics.forEach { add(it.toJson()) } }
        putJsonArray("modes") { modes.forEach { add(it.toJson()) } }
        physics?.let { put("physics", it.toJson()) }
        if (extraPhysics.isNotEmpty()) putJsonArray("extra_physics") { extraPhysics.forEach { add(it.toJson()) } }
        put("fit", fit); put("max_error_px", maxErrorPx)
        put("peak", peak); put("clipped", clipped); put("jerk", jerk)
    }

    /** What the panel and MCP show: no arrays. */
    fun quality(id: String, fence: QualityFence = QualityFence.OBSERVATION, stale: Boolean = false): QualityReport {
        val finiteGeometry = (statics + modes.map { it.axis }).all { axis -> axis.keys.all(Float::isFinite) &&
            axis.offsets.values.all { forms -> forms.all { it.all(Float::isFinite) && it.size % 2 == 0 } } } &&
            modes.all { it.amplitude.isFinite() && it.energy.isFinite() }
        val checked = QualityInspection.inspect(SimulationQualityInput(id, mapOf("fit_r2" to fit, "error_p95_px" to maxErrorPx,
            "parameter_peak" to peak, "clipped_frames" to clipped, "jerk_ratio" to jerk), stale = stale, bake = true), listOf(SimulationQualityCheck), fence)
        return if (finiteGeometry) checked else checked.copy(findings = checked.findings +
            QualityFinding.message(QualityRule.SIMULATION_NON_FINITE, "simulation:$id", "Baked keys or offsets are not finite coordinate pairs"))
    }

    fun summary() = buildJsonObject {
        put("quality", quality(fingerprint).toJson())
        putJsonArray("modes") {
            for (mode in modes) addJsonObject { put("parameter", mode.axis.parameter); put("amplitude_px", mode.amplitude); put("energy", mode.energy) }
        }
        modes.firstOrNull()?.let { put("keys", it.axis.keys.size) }
        physics?.let { put("pendulum", it.id); put("segments", it.segments.size) }
        if (extraPhysics.isNotEmpty()) putJsonArray("own_pendulums") { extraPhysics.forEach { add(it.id) } }
        if (statics.isNotEmpty()) putJsonArray("static_inputs") { statics.forEach { add(it.parameter) } }
        // All measured on held-out motion the fit never saw.
        put("fit_r2", fit); put("error_p95_px", maxErrorPx)
        put("parameter_peak", peak); put("clipped_frames", clipped); put("jerk_ratio", jerk)
    }

    private val canonical: String by lazy { toJson().toString() }
    private val digest: String by lazy { SimBake.sha1(canonical) }

    override fun equals(other: Any?) = other is SimBakeResult && other.canonical == canonical
    override fun hashCode() = canonical.hashCode()
    override fun toString() = "SimBakeResult($digest)"

    companion object {
        fun fromJson(o: JsonObject) = SimBakeResult(
            o.getValue("fingerprint").jsonPrimitive.content,
            o["vertex_counts"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int } ?: emptyMap(),
            o["statics"]?.jsonArray?.map { SimBakedAxis.fromJson(it.jsonObject) } ?: emptyList(),
            o["modes"]?.jsonArray?.map { SimBakedMode.fromJson(it.jsonObject) } ?: emptyList(),
            o["physics"]?.jsonObject?.let(RigPhysicsEdit::fromJson),
            o["fit"]?.jsonPrimitive?.floatOrNull ?: 0f,
            o["max_error_px"]?.jsonPrimitive?.floatOrNull ?: 0f,
            o["peak"]?.jsonPrimitive?.floatOrNull ?: 0f,
            o["clipped"]?.jsonPrimitive?.floatOrNull ?: 0f,
            o["jerk"]?.jsonPrimitive?.floatOrNull ?: 0f,
            o["extra_physics"]?.jsonArray?.map { RigPhysicsEdit.fromJson(it.jsonObject) } ?: emptyList(),
        )
    }
}

object SimBake {
    /**
     * Changes whenever the simulation itself moves differently for the same setup, so bakes made by an
     * earlier solver read as stale and are made again.
     */
    private const val SOLVER_VERSION = "2"

    /**
     * What a bake of [edit] depends on in [model]: the settings, the targets' rest meshes, their vertex
     * groups and the glues touching them. A bake's own keys change none of these,
     * so the model with or without them gives the same answer.
     */
    fun fingerprint(model: PuppetModel, edit: RigSimEdit): String {
        val text = StringBuilder("solver:").append(SOLVER_VERSION).append('|')
        // The default inputs hash as no inputs did when that meant them, so a bake from then stays current.
        val available = model.parameters.mapTo(HashSet()) { it.id.raw }
        val defaults = edit.inputs.filter { it.parameter in available } == RigSimEdit.defaultInputs(available)
        val settings = JsonObject(edit.toJson() - "name" - "enabled" - "bake" - "blend_shapes" - "auto_bake" - "exaggeration" - "output_names" - "outputs" -
            listOfNotNull("inputs".takeIf { defaults }))
        text.append(settings.toString())
        for (raw in edit.targets) {
            val drawable = model.drawables.firstOrNull { it.id.raw == raw }
            val mesh = drawable?.mesh
            text.append("|mesh:").append(raw).append(':').append(mesh?.vertexCount ?: -1)
            mesh?.positions?.forEach { text.append(',').append(Math.round(it * 100f)) }
            for (group in model.vertexGroups.filter { it.drawableId.raw == raw }.sortedBy { it.name }) {
                text.append("|group:").append(group.name).append(':').append(group.kind.jsonName)
                group.weights.forEach { text.append(',').append(Math.round(it * 1000f)) }
            }
        }
        for (glue in model.glues.filter { it.meshA.raw in edit.targets || it.meshB.raw in edit.targets }) {
            text.append("|glue:").append(glueKey(glue)).append(':').append(glue.intensity)
            glue.pairs.forEach { text.append(',').append(it.indexA).append('/').append(it.indexB).append('/').append(it.weightA).append('/').append(it.weightB) }
        }
        return sha1(text.toString())
    }

    /** Whether [edit]'s bake no longer matches its setup in [model]. */
    fun stale(model: PuppetModel, edit: RigSimEdit): Boolean = edit.bake != null && edit.bake.fingerprint != fingerprint(model, edit)

    internal fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
