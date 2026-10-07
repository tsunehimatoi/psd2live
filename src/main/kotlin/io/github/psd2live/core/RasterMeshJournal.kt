package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopologyEdit
import org.umamo.edit.VertexSource
import org.umamo.edit.withMeshTopologyEdit
import org.umamo.runtime.model.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Materialized mesh replacement; replay never reruns a paint gesture or depends on atlas packing. */
internal object RasterMeshJournal {
    const val OP = "canvas_mesh_rebuild"

    data class Plan(val mesh: DrawableMesh, val sources: List<VertexSource>, val glueMap: IntArray,
                    val previousPositions: FloatArray? = null)

    fun prepare(previous: DrawableMesh, replacement: DrawableMesh, checkpoint: () -> Unit = {}): Plan {
        checkpoint()
        validateMesh(previous)
        validateMesh(replacement)
        if (previous.positions.contentEquals(replacement.positions) && previous.indices.contentEquals(replacement.indices)) {
            return Plan(replacement, List(previous.vertexCount) { VertexSource.FromOld(it) }, IntArray(previous.vertexCount) { it })
        }
        val sources = (0 until replacement.vertexCount).map { vertex ->
            checkpoint()
            val x = replacement.positions[vertex * 2]; val y = replacement.positions[vertex * 2 + 1]
            val nearest = if (vertex < previous.vertexCount && previous.positions[vertex * 2] == x && previous.positions[vertex * 2 + 1] == y) vertex
                else nearest(previous, x, y)
            if (abs(previous.positions[nearest * 2] - x) <= 1e-6f && abs(previous.positions[nearest * 2 + 1] - y) <= 1e-6f) {
                VertexSource.FromOld(nearest)
            } else {
                val bound = DeformPathTools.bind(previous.positions, previous.indices, x, y)
                // Outside the previous silhouette, inherit the nearest vertex rather than extrapolating a form.
                if (minOf(bound.wa, bound.wb, bound.wc) < -1e-4f) VertexSource.FromOld(nearest)
                else {
                    val a = bound.wa.coerceAtLeast(0f); val b = bound.wb.coerceAtLeast(0f); val c = bound.wc.coerceAtLeast(0f)
                    val total = a + b + c
                    VertexSource.BarycentricOf(bound.a, bound.b, bound.c, a / total, b / total, c / total)
                }
            }
        }
        val glueMap = IntArray(previous.vertexCount) { vertex ->
            checkpoint()
            if (vertex < replacement.vertexCount && previous.positions[vertex * 2] == replacement.positions[vertex * 2] &&
                previous.positions[vertex * 2 + 1] == replacement.positions[vertex * 2 + 1]) vertex
            else nearest(replacement, previous.positions[vertex * 2], previous.positions[vertex * 2 + 1])
        }
        return Plan(replacement, sources, glueMap)
    }

    fun apply(model: PuppetModel, id: DrawableId, plan: Plan): PuppetModel {
        val drawable = requireNotNull(model.drawables.singleOrNull { it.id == id }) { "Mesh not found: ${id.raw}" }
        val original = requireNotNull(drawable.mesh)
        val previous = plan.previousPositions?.let { points ->
            require(points.size == original.positions.size && points.all(Float::isFinite)) { "Invalid mesh migration parent coordinates" }
            DrawableMesh(points, original.uvs, original.indices).also(::validateMesh)
        } ?: original
        validateMesh(plan.mesh)
        require(plan.sources.size == plan.mesh.vertexCount && plan.glueMap.size == previous.vertexCount) { "Invalid mesh migration dimensions" }
        require(plan.glueMap.all { it in 0 until plan.mesh.vertexCount }) { "Invalid glue migration index" }
        plan.sources.forEach { validateSource(it, previous.vertexCount) }
        if (previous.positions.contentEquals(plan.mesh.positions) && previous.indices.contentEquals(plan.mesh.indices) &&
            previous.uvs.indices.all { abs(previous.uvs[it] - plan.mesh.uvs[it]) <= 1e-6f } &&
            plan.sources.withIndex().all { (index, source) -> source is VertexSource.FromOld && source.oldIndex == index } &&
            plan.glueMap.indices.all { plan.glueMap[it] == it }) return model
        // Imported CMO3 bases are canvas positions, while their forms reconstruct parent-space
        // absolutes. Rebase both kinds of forms before interpolating in the parent's neutral space.
        fun rebase(values: FloatArray): FloatArray {
            require(values.size == original.positions.size) { "Invalid mesh migration keyform dimensions" }
            return FloatArray(values.size) { values[it] + original.positions[it] - previous.positions[it] }
        }
        val normalized = if (plan.previousPositions == null) model else model.copy(drawables = model.drawables.map { current ->
            if (current.id != id) current else current.copy(mesh = previous,
                geometryGrid = current.geometryGrid?.let { grid -> KeyformGrid(grid.axes,
                    grid.cells.map { cell -> KeyformCell(cell.coordinate, MeshDeltaForm(rebase(cell.form.positionDeltas))) }) },
                blendShapes = current.blendShapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                    form?.let { MeshForm(rebase(it.positionDeltas), it.drawOrder, it.opacity, it.multiplyColor, it.screenColor) }
                }) })
        })
        val migrated = normalized.withMeshTopologyEdit(id, MeshTopologyEdit(plan.mesh, plan.sources))
        val paths = model.deformPaths.filter { it.drawableId == id }.associate { path ->
            path.id to DeformPathJournal.rebind(path, previous, plan.mesh)
        }
        return migrated.copy(
            deformPaths = migrated.deformPaths.map { paths[it.id] ?: it },
            glues = model.glues.map { glue ->
                if (glue.meshA != id && glue.meshB != id) glue else {
                    val pairs = glue.pairs.map { pair ->
                        require((glue.meshA != id || pair.indexA in plan.glueMap.indices) &&
                            (glue.meshB != id || pair.indexB in plan.glueMap.indices)) { "Invalid source glue index" }
                        GluePair(if (glue.meshA == id) plan.glueMap[pair.indexA] else pair.indexA,
                            if (glue.meshB == id) plan.glueMap[pair.indexB] else pair.indexB, pair.weightA, pair.weightB)
                    }
                    // Multiple authored connections remain sequential even when a topology edit
                    // maps them onto one new endpoint. Collapsing their weights changes motion.
                    glue.copy(pairs = pairs)
                }
            },
        )
    }

    fun encode(model: PuppetModel, id: DrawableId, replacement: DrawableMesh, textureModel: PuppetModel = model,
               neutralBounds: Bounds? = null, checkpoint: () -> Unit = {}, referenceModel: PuppetModel = textureModel,
               previousPositions: FloatArray? = null): JsonObject {
        val drawable = model.drawables.single { it.id == id }
        val previous = requireNotNull(drawable.mesh)
        val plan = prepare(previousPositions?.let { DrawableMesh(it, previous.uvs, previous.indices) } ?: previous, replacement, checkpoint)
        val texture = TextureCoordinates(textureModel, textureModel.drawables.single { it.id == id })
        val reference = TextureCoordinates(referenceModel, referenceModel.drawables.single { it.id == id })
        val canvas = texture.toCanvas(replacement.uvs)
        require(canvas.all(Float::isFinite)) { "Invalid mesh migration texture coordinates" }
        return buildJsonObject {
            put("op", OP); put("id", id.raw); put("before_mesh", fingerprint(previous))
            previousPositions?.let { put("previous_parent_points", JsonArray(it.map(::JsonPrimitive))) }
            neutralBounds?.let { bounds -> put("neutral_bounds", buildJsonArray {
                add(bounds.left); add(bounds.top); add(bounds.right); add(bounds.bottom)
            }) }
            put("parent", drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
            put("source", reference.layer.key)
            put("source_id", reference.sourceId.raw)
            put("points", JsonArray(replacement.positions.map(::JsonPrimitive)))
            put("triangles", JsonArray(replacement.indices.map(::JsonPrimitive)))
            put("texture_canvas", JsonArray(canvas.map(::JsonPrimitive)))
            put("sources", JsonArray(plan.sources.map { source -> buildJsonArray {
                when (source) {
                    is VertexSource.FromOld -> add(source.oldIndex)
                    is VertexSource.BarycentricOf -> {
                        add(source.oldA); add(source.oldB); add(source.oldC); add(source.wa); add(source.wb); add(source.wc)
                    }
                    else -> error("Unsupported raster vertex source")
                }
            } }))
            put("glue_map", JsonArray(plan.glueMap.map(::JsonPrimitive)))
        }
    }

    fun replay(model: PuppetModel, command: JsonObject): PuppetModel {
        val id = DrawableId(command.getValue("id").jsonPrimitive.content)
        val drawable = model.drawables.single { it.id == id }
        val previous = requireNotNull(drawable.mesh)
        val recordedParent = command.getValue("parent").jsonPrimitive.contentOrNull
        // Recorded under a parent the base no longer generates ([VanishedParent]): the mesh is the recorded one in
        // another space, so its fingerprint cannot match; the vertex count still has to.
        val vanished = recordedParent != null && drawable.parentDeformerId?.raw != recordedParent &&
            model.deformers.none { it.id.raw == recordedParent }
        if (vanished) require(command.getValue("glue_map").jsonArray.size == previous.vertexCount) { "Mesh migration baseline changed: ${id.raw}" }
        else {
            require(fingerprint(previous) == command.getValue("before_mesh").jsonPrimitive.content) { "Mesh migration baseline changed: ${id.raw}" }
            require(drawable.parentDeformerId?.raw == recordedParent) { "Mesh migration parent changed: ${id.raw}" }
        }
        val texture = TextureCoordinates(model, drawable)
        require(texture.layer.key == command.getValue("source").jsonPrimitive.content &&
            texture.sourceId.raw == command.getValue("source_id").jsonPrimitive.content) { "Mesh migration artwork changed: ${id.raw}" }
        val recordedPoints = command.getValue("points").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        val triangles = command.getValue("triangles").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        val canvas = command.getValue("texture_canvas").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        require(canvas.size == recordedPoints.size && canvas.all(Float::isFinite)) { "Invalid mesh migration texture coordinates" }
        val recordedPrevious = command["previous_parent_points"]?.jsonArray?.map { it.jsonPrimitive.float }?.toFloatArray()
        var points = recordedPoints
        var previousPositions = recordedPrevious
        if (vanished) {
            val failure = "Mesh migration parent cannot be evaluated: ${id.raw}"
            val fit = requireNotNull(VanishedParent.Affine.fit(recordedPoints, canvas)) { failure }
            val space = VanishedParent.Space(model, drawable, failure)
            points = space.toLocal(fit.map(recordedPoints))
            previousPositions = recordedPrevious?.let { space.toLocal(fit.map(it)) }
        }
        val mesh = DrawableMesh(points, texture.toUvs(canvas), triangles)
        val sources = command.getValue("sources").jsonArray.map { value ->
            val row = value.jsonArray
            when (row.size) {
                1 -> VertexSource.FromOld(row[0].jsonPrimitive.int)
                6 -> VertexSource.BarycentricOf(row[0].jsonPrimitive.int, row[1].jsonPrimitive.int, row[2].jsonPrimitive.int,
                    row[3].jsonPrimitive.float, row[4].jsonPrimitive.float, row[5].jsonPrimitive.float)
                else -> throw IllegalArgumentException("Invalid mesh migration vertex source")
            }
        }
        val glueMap = command.getValue("glue_map").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        return apply(model, id, Plan(mesh, sources, glueMap, previousPositions))
    }

    fun isNoOp(model: PuppetModel, command: JsonObject): Boolean = replay(model, command) === model

    internal fun validateSource(source: VertexSource, count: Int) {
        when (source) {
            is VertexSource.FromOld -> require(source.oldIndex in 0 until count) { "Invalid mesh migration source index" }
            is VertexSource.BarycentricOf -> {
                require(listOf(source.oldA, source.oldB, source.oldC).all { it in 0 until count }) { "Invalid mesh migration source triangle" }
                require(listOf(source.wa, source.wb, source.wc).all { it.isFinite() && it in 0f..1f } &&
                    abs(source.wa + source.wb + source.wc - 1f) < 1e-4f) { "Invalid mesh migration source weights" }
            }
            else -> throw IllegalArgumentException("Unsupported mesh migration source")
        }
    }

    internal fun validateMesh(mesh: DrawableMesh) {
        require(mesh.positions.size >= 6 && mesh.positions.size % 2 == 0 && mesh.positions.all(Float::isFinite) &&
            mesh.uvs.size == mesh.positions.size && mesh.uvs.all(Float::isFinite)) { "Invalid mesh migration vertices" }
        require(mesh.indices.isNotEmpty() && mesh.indices.size % 3 == 0 && mesh.indices.all { it in 0 until mesh.vertexCount }) { "Invalid mesh migration triangles" }
        // Paths require at least one non-degenerate triangle, even when every new vertex matches an old one.
        DeformPathTools.bind(mesh.positions, mesh.indices, mesh.positions[0], mesh.positions[1])
    }

    private fun nearest(mesh: DrawableMesh, x: Float, y: Float): Int = (0 until mesh.vertexCount).minBy { vertex ->
        val dx = mesh.positions[vertex * 2].toDouble() - x; val dy = mesh.positions[vertex * 2 + 1].toDouble() - y
        dx * dx + dy * dy
    }

    /** UVs are intentionally excluded: a repack changes addresses, never vertex identity. */
    internal fun fingerprint(mesh: DrawableMesh): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val word = ByteBuffer.allocate(4)
        fun add(value: Int) { word.clear(); word.putInt(value); digest.update(word.array()) }
        add(mesh.positions.size); mesh.positions.forEach { add(it.toRawBits()) }
        add(mesh.indices.size); mesh.indices.forEach(::add)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Page and tile UVs both lower to source canvas pixels, including rotated/scaled atlas tiles. */
    internal class TextureCoordinates(val model: PuppetModel, drawable: Drawable) {
        private val resolved = LayerTexture.of(model, drawable)
        val texture: LayerTexture get() = resolved.texture
        val sourceId = resolved.sourceId
        val layer = resolved.layer

        fun toCanvas(uvs: FloatArray): FloatArray = resolved.texture.toCanvas(uvs)

        fun toUvs(canvas: FloatArray): FloatArray = resolved.texture.toUvs(canvas)
    }
}
