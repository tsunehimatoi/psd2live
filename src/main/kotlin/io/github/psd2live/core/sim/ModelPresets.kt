package io.github.psd2live.core.sim

import io.github.psd2live.core.*
import kotlinx.serialization.json.*
import org.umamo.format.art.LayerRaster
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

/** Materialized presets: weights are journaled and simulations are stored in the overlay. */
object ModelPresets {
    enum class Preset { FRONT_HAIR, BACK_HAIR, CLOTHING, AUTO_WEIGHTS }
    enum class Garment { SKIRT, TROUSERS }

    data class Silhouette(val garment: Garment, val waist: Float, val hem: Float, val crotch: Float?)

    /** Scan alpha rows, ignoring isolated pixels. A persistent interior gap is the two trouser legs. */
    fun silhouette(raster: LayerRaster, name: String, alphaThreshold: Int = 8): Silhouette {
        val widths = IntArray(raster.height)
        val split = BooleanArray(raster.height)
        for (y in 0 until raster.height) {
            val runs = ArrayList<IntRange>()
            var start = -1
            for (x in 0..raster.width) {
                val opaque = x < raster.width && (raster.rgba[(y * raster.width + x) * 4 + 3].toInt() and 255) > alphaThreshold
                if (opaque && start < 0) start = x
                if (!opaque && start >= 0) {
                    if (x - start >= maxOf(2, raster.width / 100)) runs += start until x
                    start = -1
                }
            }
            if (runs.isNotEmpty()) {
                widths[y] = runs.last().last - runs.first().first + 1
                split[y] = runs.zipWithNext().any { (a, b) ->
                    a.count() >= widths[y] * 0.15f && b.count() >= widths[y] * 0.15f &&
                        b.first - a.last >= maxOf(3f, widths[y] * 0.06f)
                }
            }
        }
        val occupied = widths.indices.filter { widths[it] > 0 }
        require(occupied.isNotEmpty()) { "No opaque silhouette" }
        val top = occupied.first(); val bottom = occupied.last()
        val height = (bottom - top).coerceAtLeast(1)
        // Averaging several rows prevents an antialiased apex from being mistaken for the waist.
        val window = maxOf(1, height / 25)
        val upper = (top..minOf(bottom, top + height / 3)).filter { widths[it] > 0 }
        val widest = upper.maxOf { widths[it] }
        val waist = upper.filter { widths[it] >= widest * 0.4f }.minByOrNull { y ->
            (y..minOf(bottom, y + window)).map { widths[it] }.average()
        } ?: top
        val lower = (top + height / 3..bottom).filter { widths[it] > 0 }
        val gapRows = lower.count { split[it] }
        val normalizedName = name.lowercase()
        val namedSkirt = listOf("skirt", "dress", "裙", "スカート", "ワンピース").any { it in normalizedName }
        val namedTrousers = listOf("pants", "trouser", "shorts", "裤", "褲", "ズボン", "パンツ").any { it in normalizedName }
        val trousers = when {
            namedSkirt -> false
            namedTrousers -> true
            else -> gapRows >= maxOf(3, lower.size / 3)
        }
        val crotch = if (trousers) lower.firstOrNull { y ->
            (y..minOf(bottom, y + window)).count { split[it] } > window / 2
        } else null
        return Silhouette(if (trousers) Garment.TROUSERS else Garment.SKIRT,
            (waist - top).toFloat() / height, 1f, crotch?.let { (it - top).toFloat() / height })
    }

    data class Result(val overlay: RigEditOverlay, val simulationIds: List<String>, val garments: Map<String, Garment>)

    /** Selection narrows the preset; an empty selection applies to every recognized matching part. */
    fun apply(
        overlay: RigEditOverlay, model: PuppetModel, analysis: PipelineAnalysis,
        layerIdByDrawableId: Map<String, String>, preset: Preset, selectedLayers: Set<String> = emptySet(),
        alphaThreshold: Int = 8,
    ): Result {
        val layers = analysis.layers.associateBy { it.source.id.raw }
        val world = CpuDeformationEvaluator().evaluate(model, emptyMap()).worldPositions
        var next = overlay
        var grouped = model
        val ids = ArrayList<String>()
        val garments = LinkedHashMap<String, Garment>()
        val changedTargets = HashSet<String>()
        val existingTargets = overlay.simEdits.flatMapTo(HashSet()) { it.targets }
        for (drawable in model.drawables) {
            val mesh = drawable.mesh ?: continue
            val layerId = layerIdByDrawableId[drawable.id.raw] ?: continue
            val layer = layers[layerId] ?: continue
            if (selectedLayers.isNotEmpty() && layerId !in selectedLayers) continue
            if (layer.opaquePixels <= 0 || !layer.source.visible || layer.semantic.type != LayerType.PRESET) continue
            val tag = layer.semantic.tag
            val matches = when (preset) {
                Preset.FRONT_HAIR -> tag == SemanticTag.FRONT_HAIR
                Preset.BACK_HAIR -> tag == SemanticTag.BACK_HAIR
                Preset.CLOTHING -> tag == SemanticTag.BOTTOMWEAR
                Preset.AUTO_WEIGHTS -> selectedLayers.isNotEmpty() || drawable.id.raw in existingTargets ||
                    tag in setOf(SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.BOTTOMWEAR)
            }
            if (!matches) continue
            val hair = tag == SemanticTag.FRONT_HAIR || tag == SemanticTag.BACK_HAIR
            val profile = if (tag == SemanticTag.BOTTOMWEAR) silhouette(layer.source.raster, layer.source.name, alphaThreshold) else null
            if (profile != null) garments[drawable.id.raw] = profile.garment
            val positions = world[drawable.id] ?: continue
            val pin = pinWeights(mesh, positions, hair, profile)
            // An existing user PIN group is the one the simulation already reads. Refresh it in place.
            val existingSim = overlay.simEdits.firstOrNull { drawable.id.raw in it.targets }
            val pinName = existingSim?.groups?.get(VertexGroupKind.PIN)
                ?: model.vertexGroups.firstOrNull { it.drawableId == drawable.id && it.kind == VertexGroupKind.PIN }?.name
                ?: "preset_pin"
            val command = VertexGroupJournal.encode(VertexGroup(pinName, drawable.id, VertexGroupKind.PIN, pin))
            if (!VertexGroupJournal.isNoOp(grouped, command)) {
                next = next.copy(authoringJournal = next.authoringJournal + command)
                grouped = RigAuthoringJournal.apply(grouped, command)
            }
            changedTargets += drawable.id.raw
            if (preset == Preset.AUTO_WEIGHTS) continue
            // The generated follow and physics warps have the same rest frame. Bypass only the legacy
            // physics warp on this mesh; other hair meshes and custom parenting keep their behavior.
            val legacy = when (tag) {
                SemanticTag.FRONT_HAIR -> "DeformHairFrontPhysics"
                SemanticTag.BACK_HAIR -> "DeformHairBackPhysics"
                else -> null
            }
            if (legacy != null && drawable.parentDeformerId?.raw == legacy) {
                val parent = model.deformers.first { it.id.raw == legacy }.parent
                val bind = buildJsonObject {
                    put("op", "structure")
                    putJsonArray("edits") { add(buildJsonObject {
                        put("action", "bind"); put("kind", "mesh"); put("id", drawable.id.raw)
                        put("parent_id", parent?.raw?.let(::JsonPrimitive) ?: JsonNull); put("space", "local")
                    }) }
                }
                next = next.copy(authoringJournal = next.authoringJournal + bind)
                grouped = RigAuthoringJournal.apply(grouped, bind)
            }
            // Stable per-mesh IDs make repeated application update the preset, instead of stacking bakes.
            val id = "preset_${drawable.id.raw}"
            val previous = next.simEdits.firstOrNull { it.id == id }
            require(existingSim == null || existingSim.id == id) { "${drawable.name} already belongs to simulation ${existingSim?.name}" }
            val kind = if (hair) SimKind.HAIR else SimKind.CLOTH
            val colliders = if (hair) emptyList() else model.drawables.filter { candidate ->
                val candidateLayer = layers[layerIdByDrawableId[candidate.id.raw]]
                candidate.mesh != null && candidateLayer?.semantic?.tag in setOf(SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR)
            }.map { SimColliderRef(it.id.raw) }
            val material = if (profile?.garment == Garment.TROUSERS)
                SimMaterial.preset(kind).copy(bend = 0.5f, goal = 0.25f, slack = 0.015f)
            else SimMaterial.preset(kind)
            val edit = previous?.copy(enabled = true, groups = previous.groups + (VertexGroupKind.PIN to pinName))
                ?: RigSimEdit(id, layer.source.name, kind, listOf(drawable.id.raw), material,
                    groups = mapOf(VertexGroupKind.PIN to pinName), colliders = colliders)
            next = SimAuthoring.put(next, grouped, edit)
            ids += id
        }
        require(changedTargets.isNotEmpty()) { "No matching meshes for this preset" }
        if (preset == Preset.AUTO_WEIGHTS) ids += next.simEdits.filter { sim -> sim.targets.any { it in changedTargets } }.map { it.id }
        return Result(next, ids, garments)
    }

    /** World y is up. Each disconnected mesh island gets its own root, so neither trouser leg drops away. */
    internal fun pinWeights(mesh: DrawableMesh, world: FloatArray, hair: Boolean, profile: Silhouette?): FloatArray {
        val parent = IntArray(mesh.vertexCount) { it }
        fun root(v: Int): Int { var r = v; while (parent[r] != r) r = parent[r]; return r }
        for (i in mesh.indices.indices step 3) {
            val a = root(mesh.indices[i]); val b = root(mesh.indices[i + 1]); val c = root(mesh.indices[i + 2])
            parent[b] = a; parent[c] = a
        }
        val components = (0 until mesh.vertexCount).groupBy(::root)
        val weights = FloatArray(mesh.vertexCount)
        for (vertices in components.values) {
            val top = vertices.maxOf { world[it * 2 + 1] }
            val bottom = vertices.minOf { world[it * 2 + 1] }
            val height = (top - bottom).coerceAtLeast(1e-4f)
            val waist = if (hair || profile == null) 0f else (profile.waist / profile.hem.coerceAtLeast(1e-4f)).coerceIn(0f, 0.33f)
            val solid = waist + if (hair) 0.06f else 0.08f
            val fade = solid + if (hair) 0.10f else 0.14f
            for (v in vertices) {
                val depth = (top - world[v * 2 + 1]) / height
                val t = ((depth - solid) / (fade - solid)).coerceIn(0f, 1f)
                weights[v] = 1f - t * t * (3f - 2f * t)
            }
        }
        return weights
    }
}
