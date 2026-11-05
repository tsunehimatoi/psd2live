package io.github.psd2live.core.sim

import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.RigAuthoringJournal
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.VertexGroupJournal
import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroup
import org.umamo.runtime.model.VertexGroupKind

/**
 * Model presets that materialize as ordinary edits, shared by the GUI and MCP: weights are journaled vertex
 * groups and simulations are overlay entries, so everything a preset makes stays editable in the simulation
 * panel. Applying a preset again updates what it made before (stable IDs and group names) instead of stacking.
 */
object ModelPresets {
    enum class Preset(val jsonName: String) {
        FRONT_HAIR("front_hair"),
        BACK_HAIR("back_hair"),
        /**
         * Tops, bottoms, neckwear, sleeves and legwear, simulated only where they hang loose (see [ClothFit]);
         * one cloth simulation per kind of garment, and garments worn tight are left to the rig.
         */
        CLOTHING("clothing"),
        /** Recomputes the weights only, for the selection or every simulated mesh; no new simulation. */
        AUTO_WEIGHTS("auto_weights");

        companion object {
            fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown model preset: $text (${entries.joinToString { it.jsonName }})")
        }
    }

    enum class Garment(val jsonName: String) { SKIRT("skirt"), TROUSERS("trousers") }

    const val FRONT_HAIR_SIM = "preset_front_hair"
    const val BACK_HAIR_SIM = "preset_back_hair"
    const val SKIRT_SIM = "preset_skirt"
    const val TROUSERS_SIM = "preset_trousers"
    const val TOP_SIM = "preset_top"
    const val NECKWEAR_SIM = "preset_neckwear"
    const val SLEEVES_SIM = "preset_sleeves"
    const val LEGWEAR_SIM = "preset_legwear"

    /** The clothing simulation each kind of garment goes to. */
    val CLOTHING_SIMS = mapOf(
        ClothFit.Wear.TOP to TOP_SIM,
        ClothFit.Wear.SKIRT to SKIRT_SIM,
        ClothFit.Wear.TROUSERS to TROUSERS_SIM,
        ClothFit.Wear.NECKWEAR to NECKWEAR_SIM,
        ClothFit.Wear.SLEEVE to SLEEVES_SIM,
        ClothFit.Wear.LEGWEAR to LEGWEAR_SIM,
    )
    val PRESET_SIMS = setOf(FRONT_HAIR_SIM, BACK_HAIR_SIM) + CLOTHING_SIMS.values

    /** The group each preset writes for a kind, unless the simulation already names one of its own. */
    val GROUP_NAMES = mapOf(
        VertexGroupKind.PIN to "preset_pin",
        VertexGroupKind.MASS to "preset_mass",
        VertexGroupKind.WIND to "preset_wind",
    )

    /** Layers the clothing preset reads; each is simulated only where it hangs loose. */
    val CLOTHING_TAGS = setOf(SemanticTag.TOPWEAR, SemanticTag.BOTTOMWEAR, SemanticTag.NECKWEAR, SemanticTag.HANDWEAR, SemanticTag.LEGWEAR)

    private val SKIRT_NAMES = listOf("skirt", "dress", "裙", "スカート", "ワンピース")
    private val TROUSER_NAMES = listOf("pants", "trouser", "shorts", "jeans", "裤", "褲", "ズボン", "パンツ")

    /** What the silhouette of a bottomwear layer says, in canvas px (y down); [crotch] exists only for trousers. */
    class GarmentProfile internal constructor(
        val garment: Garment,
        val top: Float,
        val waist: Float,
        val crotch: Float?,
        val hem: Float,
        /** How the garment was told apart: "name" or "silhouette". */
        val decidedBy: String,
    ) {
        fun toJson() = buildJsonObject {
            put("garment", garment.jsonName); put("decided_by", decidedBy)
            put("waist", round(waist)); crotch?.let { put("crotch", round(it)) }; put("hem", round(hem))
        }
    }

    /**
     * Reads a garment from [raster], its top row at canvas y [top]. Each row's opaque runs give
     * its extent and the widest interior gap with enough cloth on both sides. The waist is the narrowest
     * row of the upper third; trousers are a gap that starts at the crotch and holds down to the hem. A
     * garment [name] decides the type first, since a slit skirt or legs drawn together fool the silhouette.
     */
    fun garmentProfile(raster: LayerRaster, top: Float, name: String, alphaThreshold: Int = 8): GarmentProfile {
        val w = raster.width
        val h = raster.height
        val minRun = maxOf(2, w / 100)
        val rowLeft = IntArray(h) { -1 }
        val rowRight = IntArray(h) { -1 }
        val gapCenter = FloatArray(h) { Float.NaN }
        val starts = IntArray(w + 1)
        val ends = IntArray(w + 1)
        for (y in 0 until h) {
            var runs = 0
            var start = -1
            for (x in 0..w) {
                val opaque = x < w && (raster.rgba[(y * w + x) * 4 + 3].toInt() and 255) > alphaThreshold
                if (opaque && start < 0) start = x
                if (!opaque && start >= 0) {
                    if (x - start >= minRun) { starts[runs] = start; ends[runs] = x; runs++ }
                    start = -1
                }
            }
            if (runs == 0) continue
            rowLeft[y] = starts[0]
            rowRight[y] = ends[runs - 1]
            val width = rowRight[y] - rowLeft[y]
            var total = 0
            for (k in 0 until runs) total += ends[k] - starts[k]
            var before = 0
            var bestGap = 0
            for (k in 0 until runs - 1) {
                before += ends[k] - starts[k]
                val gap = starts[k + 1] - ends[k]
                if (gap >= maxOf(3f, width * 0.04f) && before >= width * 0.15f && total - before >= width * 0.15f && gap > bestGap) {
                    bestGap = gap
                    gapCenter[y] = (ends[k] + starts[k + 1]) * 0.5f
                }
            }
        }
        val occupied = (0 until h).filter { rowLeft[it] >= 0 }
        require(occupied.isNotEmpty()) { "$name has no opaque pixels" }
        val first = occupied.first()
        val last = occupied.last()
        val height = (last - first).coerceAtLeast(1)
        fun width(y: Int) = if (rowLeft[y] < 0) 0 else rowRight[y] - rowLeft[y]
        val widest = occupied.maxOf(::width)
        // Averaging a few rows keeps an antialiased apex or a single notch from passing for the waist.
        val window = maxOf(1, height / 25)
        fun smoothed(y: Int) = (maxOf(first, y - window / 2)..minOf(last, y + window / 2)).map(::width).average()
        // A waistband is a run of equally narrow rows; the waist line is where it ends and the cloth starts.
        val upper = (first..first + height * 35 / 100).filter { width(it) >= widest * 0.4f }
        val narrowest = upper.minOfOrNull(::smoothed)
        val waist = if (narrowest == null) first else {
            var y = upper.first { smoothed(it) == narrowest }
            val band = y + maxOf(1, height * 10 / 100)
            while (y + 1 in upper && y + 1 <= band && smoothed(y + 1) <= narrowest * 1.03) y++
            y
        }

        val split = BooleanArray(h) { !gapCenter[it].isNaN() }
        val splitBelow = IntArray(h + 1)
        for (y in h - 1 downTo 0) splitBelow[y] = splitBelow[y + 1] + if (split[y]) 1 else 0
        val minLeg = maxOf(3, height * 12 / 100)
        val crotchRow = (first + 1..last - minLeg).firstOrNull { y ->
            split[y] && splitBelow[y] - splitBelow[last + 1] >= (last - y + 1) * 0.7f
        }?.takeIf { crotch ->
            // The gap between legs stays near the middle; a pleat gap at one edge is not a crotch.
            val rows = (crotch..last).filter { split[it] }
            rows.count { y -> (gapCenter[y] - rowLeft[y]) / width(y).coerceAtLeast(1) in 0.2f..0.8f } >= rows.size * 0.8f
        }
        val lower = name.lowercase()
        val named = when {
            SKIRT_NAMES.any { it in lower } -> Garment.SKIRT
            TROUSER_NAMES.any { it in lower } -> Garment.TROUSERS
            else -> null
        }
        val garment = named ?: if (crotchRow != null) Garment.TROUSERS else Garment.SKIRT
        val decidedBy = if (named != null) "name" else "silhouette"
        if (garment == Garment.SKIRT) return GarmentProfile(garment, top + first, top + waist, null, top + last + 1, decidedBy)
        // Trousers drawn with the legs together have no gap: the crotch is assumed.
        val crotch = crotchRow ?: (waist + (last - waist) * 35 / 100)
        return GarmentProfile(garment, top + first, top + waist, top + crotch, top + last + 1, decidedBy)
    }

    /**
     * Where a top stops resting on the torso, canvas y: the waist of the bottoms, or the top of legwear
     * reaching up under the top. Specks a fraction of the size of the real clothing do not count.
     */
    fun waistLine(layers: List<ClassifiedLayer>, alphaThreshold: Int = 8): Float? {
        val largest = layers.filter { it.semantic.tag in CLOTHING_TAGS }.maxOfOrNull { it.opaquePixels } ?: return null
        val worn = layers.filter { it.opaquePixels > 0 && it.opaquePixels >= largest * 0.05f }
        fun bounds(layer: ClassifiedLayer) =
            ClothFit.clothBounds(layer.source.raster, layer.source.bounds.left.toFloat(), layer.source.bounds.top.toFloat(), alphaThreshold)
        val topBottom = worn.filter { it.semantic.tag == SemanticTag.TOPWEAR }.mapNotNull { bounds(it)?.get(3) }.maxOrNull()
        return worn.filter { it.semantic.tag == SemanticTag.BOTTOMWEAR }.mapNotNull { layer ->
            runCatching {
                garmentProfile(layer.source.raster, layer.source.bounds.top.toFloat(), layer.source.name, alphaThreshold).waist
            }.getOrNull()
        }.minOrNull() ?: worn.filter { it.semantic.tag == SemanticTag.LEGWEAR }.mapNotNull { bounds(it)?.get(1) }
            .filter { topBottom != null && it < topBottom }.minOrNull()
    }

    /** Per-vertex weights of one mesh; a null group is not written. */
    class PresetWeights(val pin: FloatArray, val mass: FloatArray? = null, val wind: FloatArray? = null) {
        fun groups(): Map<VertexGroupKind, FloatArray> = buildMap {
            put(VertexGroupKind.PIN, pin)
            mass?.let { put(VertexGroupKind.MASS, it) }; wind?.let { put(VertexGroupKind.WIND, it) }
        }
    }

    /**
     * Cloth weights from a garment's looseness, with [canvas] the mesh's rest vertices in canvas px: pinned
     * where it is worn tight, free where it hangs loose, heavier and catching more wind the looser it is.
     * Cloth on a limb catches less wind, being smaller and held closer.
     */
    fun clothWeights(mesh: DrawableMesh, canvas: FloatArray, field: ClothFit.Field): PresetWeights {
        val n = mesh.vertexCount
        val loose = FloatArray(n) { field.at(canvas[it * 2], canvas[it * 2 + 1]) }
        val wind = if (field.wear.onLimb) 0.6f else 1f
        return PresetWeights(FloatArray(n) { 1f - loose[it] }, FloatArray(n) { 0.6f + 0.4f * loose[it] }, FloatArray(n) { wind * loose[it] })
    }

    /** Whether [weights] leave enough of a mesh free to be worth simulating. */
    internal fun hangsLoose(weights: PresetWeights) = weights.pin.count { it < 0.5f } >= maxOf(3, weights.pin.size / 100)

    /**
     * Strand pins for hair and other hanging parts: each connected island of the mesh is a strand rooted
     * at its own top, held for [solid] of its length and released over the next [fade]. Only the pin: on
     * tml back hair, lighter roots and wind toward the tips cut the bake's held-out R² from 0.69 to 0.56.
     */
    fun strandWeights(mesh: DrawableMesh, canvas: FloatArray, solid: Float, fade: Float): PresetWeights {
        val n = mesh.vertexCount
        val parent = IntArray(n) { it }
        fun root(v: Int): Int {
            var r = v
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        for (i in 0 until mesh.indices.size / 3 * 3 step 3) {
            val a = root(mesh.indices[i])
            parent[root(mesh.indices[i + 1])] = a
            parent[root(mesh.indices[i + 2])] = a
        }
        val pin = FloatArray(n)
        for (island in (0 until n).groupBy(::root).values) {
            val top = island.minOf { canvas[it * 2 + 1] }
            val length = (island.maxOf { canvas[it * 2 + 1] } - top).coerceAtLeast(1e-3f)
            for (v in island) pin[v] = 1f - smoothstep(solid, solid + fade, (canvas[v * 2 + 1] - top) / length)
        }
        return PresetWeights(pin)
    }

    class Applied(val overlay: RigEditOverlay, val simulationIds: List<String>, val garments: Map<String, JsonObject>) {
        fun toJson() = buildJsonObject {
            putJsonArray("simulations") { simulationIds.forEach { add(it) } }
            if (garments.isNotEmpty()) putJsonObject("garments") { garments.forEach { (mesh, garment) -> put(mesh, garment) } }
        }
    }

    /** What the clothing preset read from one garment layer. */
    private class Fit(val field: ClothFit.Field, val profile: GarmentProfile?) {
        fun toJson(simulated: Boolean) = buildJsonObject {
            put("garment", field.wear.jsonName)
            profile?.toJson()?.forEach { (key, value) -> if (key != "garment") put(key, value) }
            field.toJson().forEach { (key, value) -> put(key, value) }
            put("simulated", simulated)
        }
    }

    /**
     * [preset] applied to [model], the rig [overlay] rebuilds; [layers] narrows it to those layers, and
     * empty means every recognized part. Hair and clothing simulations are created or updated; a mesh that
     * already belongs to a simulation of the user's own is refused rather than taken over.
     */
    fun apply(
        overlay: RigEditOverlay,
        model: PuppetModel,
        analysis: PipelineAnalysis,
        layerIdByDrawableId: Map<String, String>,
        preset: Preset,
        layers: Set<String> = emptySet(),
        alphaThreshold: Int = 8,
        allowNoLooseClothing: Boolean = false,
    ): Applied {
        val layerById = analysis.layers.associateBy { it.source.id.raw }
        val canvas = restCanvas(model)
        val candidates = model.drawables.mapNotNull { drawable ->
            if (drawable.mesh == null || canvas[drawable.id.raw] == null) return@mapNotNull null
            val layer = layerById[layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw] ?: return@mapNotNull null
            if (layer.opaquePixels <= 0 || (layers.isNotEmpty() && layer.source.id.raw !in layers)) null else drawable to layer
        }
        val writer = Writer(overlay, model, canvas)
        // Clothing specks next to the real garments (a stray stroke on an empty layer) are not worn.
        val largestClothing = analysis.layers.filter { it.semantic.tag in CLOTHING_TAGS }.maxOfOrNull { it.opaquePixels } ?: 0
        fun worn(layer: ClassifiedLayer) = layer.semantic.tag in CLOTHING_TAGS && layer.opaquePixels >= largestClothing * 0.05f
        val waist by lazy { waistLine(analysis.layers, alphaThreshold) }
        val fits = HashMap<String, Fit?>()
        fun fitOf(layer: ClassifiedLayer) = fits.getOrPut(layer.source.id.raw) {
            val source = layer.source
            val top = source.bounds.top.toFloat()
            runCatching {
                val profile = if (layer.semantic.tag == SemanticTag.BOTTOMWEAR) garmentProfile(source.raster, top, source.name, alphaThreshold) else null
                val wear = when (layer.semantic.tag) {
                    SemanticTag.TOPWEAR -> ClothFit.Wear.TOP
                    SemanticTag.NECKWEAR -> ClothFit.Wear.NECKWEAR
                    SemanticTag.HANDWEAR -> ClothFit.Wear.SLEEVE
                    SemanticTag.LEGWEAR -> ClothFit.Wear.LEGWEAR
                    else -> if (profile?.garment == Garment.TROUSERS) ClothFit.Wear.TROUSERS else ClothFit.Wear.SKIRT
                }
                Fit(ClothFit.analyze(source.raster, source.bounds.left.toFloat(), top, wear, profile?.waist ?: waist, alphaThreshold), profile)
            }.getOrNull()
        }
        val garments = LinkedHashMap<String, JsonObject>()
        when (preset) {
            Preset.FRONT_HAIR, Preset.BACK_HAIR -> {
                val front = preset == Preset.FRONT_HAIR
                val tag = if (front) SemanticTag.FRONT_HAIR else SemanticTag.BACK_HAIR
                val targets = candidates.filter { it.second.semantic.tag == tag }.map { it.first }
                require(targets.isNotEmpty()) { if (front) "No front hair meshes" else "No back hair meshes" }
                val id = if (front) FRONT_HAIR_SIM else BACK_HAIR_SIM
                for (drawable in targets) writer.weights(drawable, id, hairWeights(drawable, canvas, front))
                writer.simulation(id, tr(if (front) "presets.sim.frontHair" else "presets.sim.backHair"), SimKind.HAIR,
                    targets.map { it.id.raw }, hairMaterial(front), keepOthers = layers.isNotEmpty())
            }
            Preset.CLOTHING -> {
                val targets = candidates.filter { worn(it.second) }
                require(targets.isNotEmpty()) { "No clothing meshes" }
                val loose = LinkedHashMap<ClothFit.Wear, MutableList<Drawable>>()
                val tight = ArrayList<String>()
                for ((drawable, layer) in targets) {
                    val fit = fitOf(layer) ?: continue
                    val weights = clothWeights(drawable.mesh!!, canvas.getValue(drawable.id.raw), fit.field)
                    val simulated = hangsLoose(weights)
                    garments[drawable.id.raw] = fit.toJson(simulated)
                    if (!simulated) { tight += drawable.id.raw; continue }
                    writer.weights(drawable, CLOTHING_SIMS.getValue(fit.field.wear), weights)
                    loose.getOrPut(fit.field.wear) { ArrayList() } += drawable
                }
                // A garment now read as tight leaves the clothing simulation it was in; one read as another
                // kind moves over as its simulation is put.
                val released = writer.release(tight, CLOTHING_SIMS.values.toSet())
                require(allowNoLooseClothing || loose.isNotEmpty() || released) { "Every garment is worn tight; nothing hangs loose enough to simulate" }
                for ((wear, members) in loose) {
                    writer.simulation(CLOTHING_SIMS.getValue(wear), tr("presets.sim.${wear.jsonName}"), SimKind.CLOTH,
                        members.map { it.id.raw }, clothMaterial(wear), keepOthers = layers.isNotEmpty())
                }
            }
            Preset.AUTO_WEIGHTS -> {
                val simulated = overlay.simEdits.flatMapTo(HashSet()) { it.targets }
                val targets = candidates.filter { layers.isNotEmpty() || it.first.id.raw in simulated }
                require(targets.isNotEmpty()) { "No simulated or selected meshes" }
                for ((drawable, layer) in targets) {
                    val owner = overlay.simEdits.firstOrNull { drawable.id.raw in it.targets }?.id
                    val positions = canvas.getValue(drawable.id.raw)
                    val fit = if (layer.semantic.tag in CLOTHING_TAGS) fitOf(layer) else null
                    val weights = when {
                        layer.semantic.tag == SemanticTag.FRONT_HAIR -> hairWeights(drawable, canvas, true)
                        layer.semantic.tag == SemanticTag.BACK_HAIR -> hairWeights(drawable, canvas, false)
                        fit != null -> clothWeights(drawable.mesh!!, positions, fit.field).also { garments[drawable.id.raw] = fit.toJson(owner != null) }
                        else -> strandWeights(drawable.mesh!!, positions, 0.06f, 0.12f)
                    }
                    writer.weights(drawable, owner, weights)
                }
                writer.nameWrittenGroups()
            }
        }
        return Applied(writer.overlay, writer.simulationIds.toList(), garments)
    }

    /** Cloth on a limb or legs bends and bunches less and keeps closer to its drawn shape than a skirt or coat. */
    private fun clothMaterial(wear: ClothFit.Wear): SimMaterial {
        val cloth = SimMaterial.preset(SimKind.CLOTH)
        return when (wear) {
            ClothFit.Wear.TOP, ClothFit.Wear.SKIRT -> cloth
            ClothFit.Wear.NECKWEAR -> cloth.copy(bend = 0.4f, area = 0.4f)
            ClothFit.Wear.TROUSERS, ClothFit.Wear.SLEEVE, ClothFit.Wear.LEGWEAR -> cloth.copy(bend = 0.5f, area = 0.5f, goal = 0.25f, slack = 0.015f)
        }
    }

    /**
     * Front hair rests on the forehead and back hair hangs: as hanging hair, bangs swing about twice as far
     * as the head moves them and bounce with every nod (see [SimMaterialPreset.BANGS]).
     */
    private fun hairMaterial(front: Boolean) = (if (front) SimMaterialPreset.BANGS else SimMaterialPreset.HAIR).material

    private fun hairWeights(drawable: Drawable, canvas: Map<String, FloatArray>, front: Boolean) =
        // Back hair lies on the head further down before it hangs free.
        if (front) strandWeights(drawable.mesh!!, canvas.getValue(drawable.id.raw), 0.06f, 0.12f)
        else strandWeights(drawable.mesh!!, canvas.getValue(drawable.id.raw), 0.12f, 0.18f)

    /** Rest vertices of every visible mesh in canvas px; glue is left out so it cannot pull the rest shape. */
    internal fun restCanvas(model: PuppetModel): Map<String, FloatArray> =
        CpuDeformationEvaluator().evaluate(model.copy(glues = emptyList()), emptyMap()).worldPositions
            .entries.associate { (id, world) -> id.raw to FloatArray(world.size) { if (it % 2 == 1) -world[it] else world[it] } }

    /** Journals groups and puts simulations, keeping the model the next command lands on in step. */
    private class Writer(var overlay: RigEditOverlay, var model: PuppetModel, val canvas: Map<String, FloatArray>) {
        val simulationIds = LinkedHashSet<String>()
        /** Per simulation, the group name used for each kind written to its meshes. */
        private val written = LinkedHashMap<String, MutableMap<VertexGroupKind, String>>()

        fun weights(drawable: Drawable, simulation: String?, weights: PresetWeights) {
            val named = simulation?.let { id -> overlay.simEdits.firstOrNull { it.id == id }?.groups }.orEmpty()
            for ((kind, values) in weights.groups()) {
                val name = named[kind] ?: GROUP_NAMES.getValue(kind)
                val command = VertexGroupJournal.encode(VertexGroup(name, drawable.id, kind, FloatArray(values.size) { values[it].coerceIn(0f, 1f) }))
                if (!VertexGroupJournal.isNoOp(model, command)) {
                    overlay = overlay.copy(authoringJournal = overlay.authoringJournal + command)
                    model = RigAuthoringJournal.apply(model, command)
                }
                if (simulation != null) written.getOrPut(simulation) { LinkedHashMap() }[kind] = name
            }
        }

        /**
         * Creates or updates preset simulation [id] on [targets]. A target in another preset simulation moves
         * here (a garment now read as the other type); one in a simulation of the user's own is refused.
         */
        fun simulation(id: String, name: String, kind: SimKind, targets: List<String>,
            material: SimMaterial, keepOthers: Boolean) {
            for (other in overlay.simEdits.filter { it.id != id }) {
                val shared = other.targets.filter { it in targets }
                if (shared.isEmpty()) continue
                require(other.id in PRESET_SIMS) { "${shared.first()} already belongs to simulation ${other.name}" }
                val left = other.targets - shared.toSet()
                overlay = if (left.isEmpty()) SimAuthoring.remove(overlay, other.id)
                    else overlay.copy(simEdits = overlay.simEdits.map { if (it.id == other.id) it.copy(targets = left) else it })
            }
            val previous = overlay.simEdits.firstOrNull { it.id == id }
            val allTargets = if (keepOthers && previous != null) (previous.targets + targets).distinct() else targets
            val groups = previous?.groups.orEmpty() + written[id].orEmpty()
            val available = model.parameters.mapTo(HashSet()) { it.id.raw }
            val defaults = RigSimEdit.defaultInputs(available, kind)
            // Inputs left at the defaults every kind once shared move to this kind's own, and a material left at
            // the kind's default (front hair before it had one of its own) to this preset's.
            val edit = previous?.copy(targets = allTargets, groups = groups, enabled = true,
                inputs = if (previous.inputs == RigSimEdit.defaultInputs(available)) defaults else previous.inputs,
                material = if (previous.material == SimMaterial.preset(kind)) material else previous.material)
                ?: RigSimEdit(id, name, kind, allTargets, material, groups = groups, inputs = defaults)
            overlay = SimAuthoring.put(overlay, model, edit)
            simulationIds += id
        }

        /**
         * Takes [targets] out of the [simulations] they are in, removing a simulation left with none.
         * Returns whether anything changed.
         */
        fun release(targets: Collection<String>, simulations: Set<String>): Boolean {
            val before = overlay
            for (edit in overlay.simEdits.filter { it.id in simulations && it.targets.any(targets::contains) }) {
                val left = edit.targets.filterNot(targets::contains)
                overlay = if (left.isEmpty()) SimAuthoring.remove(overlay, edit.id)
                    else SimAuthoring.put(overlay, model, edit.copy(targets = left))
            }
            return overlay != before
        }

        /** Points every simulation whose meshes got weights at the groups just written, and lists it. */
        fun nameWrittenGroups() {
            for ((id, kinds) in written) {
                val edit = overlay.simEdits.firstOrNull { it.id == id } ?: continue
                overlay = SimAuthoring.put(overlay, model, edit.copy(groups = edit.groups + kinds))
                simulationIds += id
            }
        }
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun round(value: Float) = kotlin.math.round(value * 10f) / 10f
}
