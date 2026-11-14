package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*

/** A candidate rejection with a stable code and the object that cannot be migrated losslessly. */
class MeshSplitRejected(val code: String, val subject: String) : IllegalArgumentException("$code: $subject")

internal data class MeshSplitVertex(val mesh: String, val index: Int)

/** The single source of identity and vertex correspondence for every split relation. */
internal data class MeshSplitResult(
    val original: Mesh,
    val pieces: List<Mesh>,
    val vertices: List<MeshSplitVertex?>,
    val sourceVertices: List<IntArray>,
)

internal class MeshSplitReferences(
    private val input: RigIR,
    private val splits: Map<String, MeshSplitResult>,
    private val instance: RigModifierInstance,
    private val checkpoint: () -> Unit,
) {
    private fun ids(ids: List<String>): List<String> = ids.flatMap { id ->
        splits[id]?.pieces?.map { it.id } ?: listOf(id)
    }

    private fun vertex(mesh: String, index: Int, code: String, subject: String): MeshSplitVertex {
        val split = splits[mesh] ?: return MeshSplitVertex(mesh, index)
        return split.vertices.getOrNull(index)
            ?: throw MeshSplitRejected(code, "$subject mesh:$mesh vertex:$index")
    }

    private fun children(children: List<ChildRef>): List<ChildRef> = children.flatMap { ref ->
        if (ref is ChildRef.MeshRef) ids(listOf(ref.id)).map { ChildRef.MeshRef(it) } else listOf(ref)
    }

    private fun composite(value: Composite): Composite = value.copy(maskedBy = ids(value.maskedBy))

    private fun render(group: RenderGroup): RenderGroup {
        checkpoint()
        return group.copy(composite = group.composite?.let(::composite), children = group.children.flatMap { child ->
            when (child) {
                is RenderMesh -> ids(listOf(child.id)).map { RenderMesh(it) }
                is RenderGroup -> listOf(render(child))
            }
        })
    }

    private fun mesh(mesh: Mesh): Mesh = mesh.copy(
        maskedBy = ids(mesh.maskedBy),
        // Every piece keeps the entire original tile, page and UV coordinate system. The first
        // piece is therefore a deterministic binding owner, including for consumers of other islands.
        textureSource = mesh.textureSource?.let { splits[it]?.pieces?.first()?.id ?: it },
    )

    private fun glues(): List<Glue> = input.glues.flatMapIndexed { glueIndex, glue ->
        checkpoint()
        if (glue.meshA !in splits && glue.meshB !in splits) return@flatMapIndexed listOf(glue)
        val subject = "glue:${glue.id ?: "#$glueIndex"}"
        val result = mutableListOf<Glue>()
        var pairs = mutableListOf<GluePair>()
        var endpoints: Pair<String, String>? = null
        fun flush() {
            val (a, b) = endpoints ?: return
            result += glue.copy(
                id = if (result.isEmpty() && glue.id != null) glue.id else instance.outputId("split:glue:$glueIndex:${result.size}"),
                meshA = a, meshB = b, pairs = pairs.toList(),
            )
            pairs = mutableListOf()
        }
        for (pair in glue.pairs) {
            checkpoint()
            val a = vertex(glue.meshA, pair.a, "mesh.split.glue_vertex_unmapped", subject)
            val b = vertex(glue.meshB, pair.b, "mesh.split.glue_vertex_unmapped", subject)
            val next = a.mesh to b.mesh
            // Keep consecutive runs rather than grouping all equal endpoints: Glue pairs may
            // share a vertex, so regrouping would change their sequential deformation result.
            if (endpoints != next) { flush(); endpoints = next }
            pairs += pair.copy(a = a.index, b = b.index)
        }
        flush()
        if (result.isEmpty()) listOf(glue.copy(
            id = glue.id ?: instance.outputId("split:glue:$glueIndex:0"),
            meshA = ids(listOf(glue.meshA)).first(), meshB = ids(listOf(glue.meshB)).first(),
        )) else result
    }

    private fun authoring(): Authoring = input.authoring.copy(
        paths = input.authoring.paths.map { path ->
            checkpoint()
            if (path.mesh !in splits) return@map path
            val mapped = path.points.map { point ->
                val vertices = listOf(point.a, point.b, point.c).map {
                    vertex(path.mesh, it, "mesh.split.path_vertex_unmapped", "path:${path.id}")
                }
                if (vertices.map { it.mesh }.distinct().size != 1)
                    throw MeshSplitRejected("mesh.split.path_crosses_islands", "path:${path.id} mesh:${path.mesh}")
                vertices.first().mesh to point.copy(a = vertices[0].index, b = vertices[1].index, c = vertices[2].index)
            }
            if (mapped.map { it.first }.distinct().size != 1)
                throw MeshSplitRejected("mesh.split.path_crosses_islands", "path:${path.id} mesh:${path.mesh}")
            path.copy(mesh = mapped.first().first, points = mapped.map { it.second })
        },
        vertexGroups = input.authoring.vertexGroups.flatMap { group ->
            checkpoint()
            val split = splits[group.mesh] ?: return@flatMap listOf(group)
            if (group.weights.size != split.vertices.size)
                throw MeshSplitRejected("mesh.split.invalid_vertex_group", "vertexGroup:${group.name} mesh:${group.mesh}")
            split.pieces.mapIndexed { index, piece ->
                group.copy(mesh = piece.id, weights = Floats.wrap(FloatArray(split.sourceVertices[index].size) {
                    group.weights[split.sourceVertices[index][it]]
                }))
            }
        },
    )

    fun changes(): ModifierModelChanges {
        val occupied = input.meshes.mapTo(HashSet()) { it.id }
        splits.values.flatMap { it.pieces }.forEach { piece ->
            if (piece.id.isBlank() || piece.id.any(Char::isISOControl) || !occupied.add(piece.id))
                throw MeshSplitRejected("mesh.split.mesh_id_collision", "mesh:${piece.id}")
        }
        // Advanced simulation particle indices span multiple meshes. Until those arrays can be
        // remapped as one unit, reject the candidate rather than retaining stale bindings.
        input.advanced.simulations.forEach { sim ->
            if (sim.targets.any { it.mesh in splits } || sim.particles.anchorMesh.any { it in splits } ||
                sim.statics.any { correction -> correction.offsets.keys.any { it in splits } })
                throw MeshSplitRejected("mesh.split.simulation_reference", "simulation:${sim.id}")
        }
        input.advanced.colliders.forEach { collider ->
            if (collider.mesh in splits)
                throw MeshSplitRejected("mesh.split.collider_reference", "collider:${collider.id} mesh:${collider.mesh}")
        }
        val glues = glues()
        val glueIds = HashSet<String>()
        glues.mapNotNull { it.id }.forEach {
            if (!glueIds.add(it)) throw MeshSplitRejected("mesh.split.glue_id_collision", "glue:$it")
        }
        val authoring = authoring()
        splits.values.forEach { split ->
            if (split.vertices.any { it == null })
                throw MeshSplitRejected("mesh.split.unassigned_vertex", "mesh:${split.original.id}")
        }
        val textures = input.textures.copy(bindings = buildMap {
            input.textures.bindings.forEach { (id, page) -> ids(listOf(id)).forEach { put(it, page) } }
        })
        val advanced = input.advanced.copy(hitAreas = input.advanced.hitAreas.map { it.copy(meshes = ids(it.meshes)) })
        fun <T> change(before: T, after: T) = if (before == after) null else ModifierFieldChange(before, after)
        return ModifierModelChanges(
            meshes = ModifierObjectChanges(
                removed = splits.values.map { it.original },
                added = splits.values.flatMap { it.pieces }.map(::mesh),
                updated = input.meshes.filter { it.id !in splits }.mapNotNull {
                    checkpoint()
                    val updated = mesh(it)
                    if (it == updated) null else ModifierObjectUpdate(it, updated)
                },
            ),
            parts = ModifierObjectChanges(updated = input.parts.mapNotNull {
                checkpoint()
                val updated = it.copy(children = children(it.children), composite = composite(it.composite))
                if (it == updated) null else ModifierObjectUpdate(it, updated)
            }),
            rootChildren = change(input.rootChildren, children(input.rootChildren)),
            renderRoot = change(input.renderRoot, render(input.renderRoot)),
            glues = change(input.glues, glues),
            textures = change(input.textures, textures),
            authoring = change(input.authoring, authoring),
            hitAreas = change(input.hitAreas, input.hitAreas.map { it.copy(meshes = ids(it.meshes)) }),
            advanced = change(input.advanced, advanced),
        )
    }
}
