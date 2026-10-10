package io.github.psd2live.core

import io.github.psd2live.core.sim.*
import kotlinx.serialization.json.*
import org.umamo.edit.MeshTopologyEdit
import org.umamo.edit.VertexSource
import org.umamo.edit.withDrawablesDeleted
import org.umamo.edit.withMeshTopologyEdit
import org.umamo.runtime.model.*

/** A partition copies bindings at its ordered journal position, never from a mutable preview. */
internal object SourcePartitionJournal {
    const val OP = "canvas_source_partition"

    fun pieces(command: JsonObject): List<JsonObject> = command.getValue("pieces").jsonArray.map { it.jsonObject }
    fun commands(overlay: RigEditOverlay) = overlay.authoringJournal.filter { it["op"]?.jsonPrimitive?.contentOrNull == OP }

    fun encode(model: PuppetModel, source: DrawableId, layers: List<String>, ids: List<String>, names: List<String>,
               plan: SourcePartitionGeometry.Plan, textureSource: String = PuppetSourceAtlas.SOURCE_ID_RAW,
               followCutVertices: Boolean = false): JsonObject {
        val drawable = model.drawables.single { it.id == source }
        require(layers.size == plan.pieces.size && ids.size == layers.size && names.size == layers.size)
        return buildJsonObject {
            put("op", OP); put("source", source.raw)
            put("texture_source_id", textureSource)
            if (followCutVertices) put("follow_cut_vertices", true)
            put("before_mesh", RasterMeshJournal.fingerprint(requireNotNull(drawable.mesh)))
            put("parent", drawable.parentDeformerId?.raw?.let(::JsonPrimitive) ?: JsonNull)
            put("owners", JsonArray(plan.ownerByVertex.map(::JsonPrimitive)))
            put("pieces", JsonArray(plan.pieces.mapIndexed { index, piece -> buildJsonObject {
                put("id", ids[index]); put("layer_id", layers[index]); put("name", names[index])
                put("visible", drawable.isVisible)
                put("points", JsonArray(piece.mesh.positions.map(::JsonPrimitive)))
                put("texture_canvas", JsonArray(piece.mesh.uvs.map(::JsonPrimitive)))
                put("triangles", JsonArray(piece.mesh.indices.map(::JsonPrimitive)))
                put("sources", encodeSources(piece.sources))
            } }))
        }
    }

    private fun encodeSources(sources: List<VertexSource>) = JsonArray(sources.map { source -> buildJsonArray {
        when (source) {
            is VertexSource.FromOld -> add(source.oldIndex)
            is VertexSource.BarycentricOf -> {
                add(source.oldA); add(source.oldB); add(source.oldC); add(source.wa); add(source.wb); add(source.wc)
            }
            else -> error("Unsupported partition vertex source")
        }
    } })

    private fun sources(piece: JsonObject) = piece.getValue("sources").jsonArray.map { value ->
        val row = value.jsonArray
        when (row.size) {
            1 -> VertexSource.FromOld(row[0].jsonPrimitive.int)
            6 -> VertexSource.BarycentricOf(row[0].jsonPrimitive.int, row[1].jsonPrimitive.int, row[2].jsonPrimitive.int,
                row[3].jsonPrimitive.float, row[4].jsonPrimitive.float, row[5].jsonPrimitive.float)
            else -> throw IllegalArgumentException("Invalid partition vertex source")
        }
    }

    fun apply(model: PuppetModel, command: JsonObject): PuppetModel {
        val source = model.drawables.single { it.id.raw == command.getValue("source").jsonPrimitive.content }
        val mesh = requireNotNull(source.mesh)
        require(RasterMeshJournal.fingerprint(mesh) == command.getValue("before_mesh").jsonPrimitive.content) {
            "Partition baseline changed: ${source.id.raw}"
        }
        require(source.parentDeformerId?.raw == command.getValue("parent").jsonPrimitive.contentOrNull) {
            "Partition parent changed: ${source.id.raw}"
        }
        return partition(model, command) { clone, canvas ->
            val tileId = requireNotNull(clone.atlasTileId)
            val tile = model.atlas.tiles.single { it.id == tileId }
            val placed = clone.copy(texturePage = requireNotNull(tile.placement).pageIndex)
            placed to RasterMeshJournal.TextureCoordinates(model, placed).toUvs(canvas)
        }.model
    }

    /** The pieces of a partition, the replacements of each Glue touching the source in order, and the cut followers. */
    class Partitioned(val model: PuppetModel, val ids: List<DrawableId>, val glueGroups: List<List<Glue>>, val followers: List<Glue>)

    /**
     * Applies [command] to [model] without checking it against a regenerated source. [uvs] gives each piece's
     * stored texture coordinates from its canvas ones; a materialized split keeps the canvas coordinates.
     */
    fun partition(model: PuppetModel, command: JsonObject, uvs: (Drawable, FloatArray) -> Pair<Drawable, FloatArray>): Partitioned {
        val source = model.drawables.single { it.id.raw == command.getValue("source").jsonPrimitive.content }
        val mesh = requireNotNull(source.mesh)
        val records = pieces(command)
        val ids = records.map { DrawableId(it.getValue("id").jsonPrimitive.content) }
        require(ids.size >= 2 && ids.distinct().size == ids.size && model.drawables.none { it.id in ids }) { "Invalid partition identities" }
        val owners = command.getValue("owners").jsonArray.map { it.jsonPrimitive.int }
        require(owners.size == mesh.vertexCount && owners.all { it in ids.indices }) { "Invalid partition vertex ownership" }
        val oldToNew = Array(ids.size) { IntArray(mesh.vertexCount) { -1 } }
        var current = model
        for ((index, record) in records.withIndex()) {
            val id = ids[index]
            val tileId = PuppetSourceAtlas.tileIdFor(record.getValue("layer_id").jsonPrimitive.content,
                command["texture_source_id"]?.jsonPrimitive?.content ?: PuppetSourceAtlas.SOURCE_ID_RAW)
            val unplaced = source.copy(id = id, name = record.getValue("name").jsonPrimitive.content,
                atlasTileId = tileId, textureSourceId = null,
                isVisible = record.getValue("visible").jsonPrimitive.boolean)
            val points = record.getValue("points").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val canvas = record.getValue("texture_canvas").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val (clone, textured) = uvs(unplaced, canvas)
            val replacement = DrawableMesh(points, textured,
                record.getValue("triangles").jsonArray.map { it.jsonPrimitive.int }.toIntArray())
            RasterMeshJournal.validateMesh(replacement)
            val sources = sources(record)
            require(sources.size == replacement.vertexCount) { "Invalid partition migration dimensions" }
            sources.forEachIndexed { vertex, value ->
                RasterMeshJournal.validateSource(value, mesh.vertexCount)
                if (value is VertexSource.FromOld) oldToNew[index][value.oldIndex] = vertex
            }
            current = current.copy(drawables = current.drawables + clone,
                vertexGroups = current.vertexGroups + model.vertexGroups.filter { it.drawableId == source.id }.map { it.copy(drawableId = id) })
                .withMeshTopologyEdit(id, MeshTopologyEdit(replacement, sources))
            val paths = model.deformPaths.filter { it.drawableId == source.id }.map { path ->
                DeformPathJournal.rebind(path, mesh, replacement).copy(id = "${id.raw}/${path.id}", drawableId = id)
            }
            current = current.copy(deformPaths = current.deformPaths + paths)
        }
        val glues = model.glues.map { glue ->
            if (glue.meshA != source.id && glue.meshB != source.id) listOf(glue) else {
                val groups = glueGroups(glue, source.id, owners)
                groups.mapIndexed { index, (group, pairs) ->
                    val (a, b) = group
                    glue.copy(meshA = if (a < 0) glue.meshA else ids[a], meshB = if (b < 0) glue.meshB else ids[b],
                        pairs = pairs.map { pair ->
                            val va = if (a < 0) pair.indexA else oldToNew[a][pair.indexA]
                            val vb = if (b < 0) pair.indexB else oldToNew[b][pair.indexB]
                            require(va >= 0 && vb >= 0) { "Partition lost an original Glue vertex" }
                            GluePair(va, vb, pair.weightA, pair.weightB)
                        }, id = if (index == 0 || glue.id == null) glue.id else "${glue.id}/${ids[maxOf(a, b)].raw}/$index")
                }
            }
        }
        fun children(old: List<OrgChild>) = old.flatMap { child ->
            if (child == OrgChild.Drawable(source.id)) listOf(child) + ids.map { OrgChild.Drawable(it) } else listOf(child)
        }
        val followers = if (command["follow_cut_vertices"]?.jsonPrimitive?.boolean == true)
            cutVertexFollowers(source.id, ids, records.map(::sources), owners, oldToNew) else emptyList()
        return Partitioned(current.copy(glues = glues.flatten() + followers,
            drawables = current.drawables.map { drawable -> drawable.copy(maskedBy = drawable.maskedBy.flatMap {
                if (it == source.id) ids else listOf(it)
            }) }, parts = current.parts.map { it.copy(children = children(it.children)) }, rootChildren = children(current.rootChildren))
            .withDerivedRenderRoot(), ids, glues.filterIndexed { index, _ -> model.glues[index].let { it.meshA == source.id || it.meshB == source.id } },
            followers)
    }

    /**
     * [command] as a split of whichever rig holds its source ([RigRegenerationCheckpoint.SplitParts]): each part takes
     * that rig's source vertices through its vertex sources, at rest and in every keyform, and its texture coordinates
     * through the rig's atlas when the atlas has its layer (canvas units otherwise); the source goes. Null when the rig
     * has no such source or other vertices than the command cut. The Glues that make cut vertices follow are the user's.
     */
    fun splitParts(command: JsonObject): RigRegenerationCheckpoint.SplitParts = RigRegenerationCheckpoint.SplitParts { model, user ->
        val sourceId = DrawableId(command.getValue("source").jsonPrimitive.content)
        val source = model.drawables.firstOrNull { it.id == sourceId } ?: return@SplitParts null
        val mesh = source.mesh ?: return@SplitParts null
        if (command.getValue("owners").jsonArray.size != mesh.vertexCount) return@SplitParts null
        val records = pieces(command)
        val partitioned = try {
            partition(model, command) { clone, canvas ->
                val tile = model.atlas.tiles.firstOrNull { it.id == clone.atlasTileId }
                if (tile?.placement == null) clone to canvas else {
                    val placed = clone.copy(texturePage = tile.placement!!.pageIndex)
                    placed to RasterMeshJournal.TextureCoordinates(model, placed).toUvs(canvas)
                }
            }
        } catch (failure: IllegalArgumentException) {
            return@SplitParts null
        }
        val rests = partitioned.ids.withIndex().associate { (index, id) -> id to interpolate(mesh.positions, sources(records[index])) }
        val parts = partitioned.model.copy(drawables = partitioned.model.drawables.map { drawable ->
            val rest = rests[drawable.id] ?: return@map drawable
            val part = requireNotNull(drawable.mesh)
            drawable.copy(mesh = DrawableMesh(rest, part.uvs, part.indices))
        })
        val followers = partitioned.followers.toHashSet()
        (if (user) parts else parts.copy(glues = parts.glues.filterNot { it in followers })).withDrawablesDeleted(setOf(sourceId))
    }

    /** New cut vertices follow the already welded triangle, in exactly its rendered affine space.
     * Standard directional Glue pairs express the barycentric sum without moving any source vertex.
     * Non-owner copies of old boundary vertices likewise follow their one canonical owner. */
    private fun cutVertexFollowers(source: DrawableId, ids: List<DrawableId>, sources: List<List<VertexSource>>,
                                   owners: List<Int>, oldToNew: Array<IntArray>): List<Glue> {
        val result = ArrayList<Glue>()
        fun add(piece: Int, vertex: Int, old: Int, weight: Float, step: Int) {
            val owner = owners[old]
            val target = oldToNew[owner][old]
            require(target >= 0) { "Partition lost a cut vertex ancestor" }
            result += Glue(ids[piece], ids[owner], listOf(GluePair(vertex, target, weight, 0f)),
                id = "${source.raw}/partition-follow/$piece/$vertex/$step")
        }
        sources.forEachIndexed { piece, vertices -> vertices.forEachIndexed { vertex, ancestry ->
            when (ancestry) {
                is VertexSource.FromOld -> if (owners[ancestry.oldIndex] != piece)
                    add(piece, vertex, ancestry.oldIndex, 1f, 0)
                is VertexSource.BarycentricOf -> {
                    val claims = listOf(ancestry.oldA to ancestry.wa, ancestry.oldB to ancestry.wb, ancestry.oldC to ancestry.wc)
                        .filter { it.second > 0f }
                    var total = 0f
                    claims.forEachIndexed { step, (old, weight) ->
                        total += weight
                        add(piece, vertex, old, weight / total, step)
                    }
                }
                else -> error("Unsupported cut vertex ancestry")
            }
        } }
        return result
    }

    /** Reassign the body and its materialized offsets, so late simulation generation targets the pieces. */
    fun migrateSimulations(overlay: RigEditOverlay, model: PuppetModel, source: String, ids: List<String>,
                          plan: SourcePartitionGeometry.Plan): RigEditOverlay {
        val roleTargets = model.glues.filter { it.meshA.raw == source || it.meshB.raw == source }.associate { glue ->
            glueKey(glue) to glueGroups(glue, DrawableId(source), plan.ownerByVertex.toList()).map { (group, _) ->
                val a = if (group.first < 0) glue.meshA.raw else ids[group.first]
                val b = if (group.second < 0) glue.meshB.raw else ids[group.second]
                "$a|$b"
            }.distinct()
        }
        return overlay.copy(simEdits = overlay.simEdits.map { sim ->
            val roles = sim.glueRoles.flatMap { (key, role) -> (roleTargets[key] ?: listOf(key)).map { it to role } }.toMap()
            if (source !in sim.targets) sim.copy(glueRoles = roles) else {
                fun migrate(axis: SimBakedAxis): SimBakedAxis {
                    val previous = axis.offsets[source] ?: return axis
                    require(previous.all { it.size == plan.ownerByVertex.size * 2 }) { "Simulation bake has a stale partition topology" }
                    val offsets = ids.mapIndexed { index, id -> id to previous.map { interpolate(it, plan.pieces[index].sources) } }.toMap()
                    return SimBakedAxis(axis.parameter, axis.keys, axis.offsets - source + offsets)
                }
                val bake = sim.bake?.let { previous ->
                    require(previous.vertexCounts[source] == plan.ownerByVertex.size) { "Simulation bake has a stale partition topology" }
                    SimBakeResult.fromJson(previous.withGeometry(
                        previous.vertexCounts - source + ids.mapIndexed { index, id -> id to plan.pieces[index].mesh.vertexCount },
                        previous.statics.map(::migrate), previous.modes.map { SimBakedMode(migrate(it.axis), it.amplitude, it.energy) }).toJson())
                }
                sim.copy(targets = sim.targets.flatMap { if (it == source) ids else listOf(it) }, bake = bake, glueRoles = roles)
            }
        })
    }

    /**
     * Bones bound to [source] bind its parts [ids] instead, in its place in each bone's list, so the base skins the
     * parts of a version 2 split rather than its stub. Manual weights painted on the source stay with it.
     */
    fun migrateBones(overlay: RigEditOverlay, source: String, ids: List<String>): RigEditOverlay {
        val skeleton = overlay.skeleton ?: return overlay
        if (skeleton.bones.none { source in it.drawableIds }) return overlay
        return overlay.copy(skeleton = skeleton.copy(bones = skeleton.bones.map { bone ->
            if (source !in bone.drawableIds) bone
            else bone.copy(drawableIds = bone.drawableIds.flatMap { if (it == source) ids else listOf(it) }.distinct())
        }))
    }

    /** Preserve sequential evaluation when component owners alternate within an existing Glue. */
    private fun glueGroups(glue: Glue, source: DrawableId, owners: List<Int>): List<Pair<Pair<Int, Int>, List<GluePair>>> {
        val groups = ArrayList<Pair<Pair<Int, Int>, MutableList<GluePair>>>()
        for (pair in glue.pairs) {
            val group = (if (glue.meshA == source) owners[pair.indexA] else -1) to
                (if (glue.meshB == source) owners[pair.indexB] else -1)
            if (groups.lastOrNull()?.first == group) groups.last().second += pair
            else groups += group to mutableListOf(pair)
        }
        return groups
    }

    private fun interpolate(values: FloatArray, sources: List<VertexSource>) = FloatArray(sources.size * 2) { index ->
        val axis = index % 2
        when (val source = sources[index / 2]) {
            is VertexSource.FromOld -> values[source.oldIndex * 2 + axis]
            is VertexSource.BarycentricOf -> values[source.oldA * 2 + axis] * source.wa +
                values[source.oldB * 2 + axis] * source.wb + values[source.oldC * 2 + axis] * source.wc
            else -> error("Unsupported partition vertex source")
        }
    }
}
