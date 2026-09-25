package io.github.psd2live.core

import org.umamo.runtime.model.Glue

/** One vertex of one mesh: [mesh] is the drawable id, [index] the vertex in its rest mesh. */
internal data class MeshVertex(val mesh: String, val index: Int)

/**
 * Glued vertices seen as the single points they stand for.
 *
 * A glue pair is two vertices that sit on one spot and are meant to stay there, so the editor treats
 * each connected set of pairs as one point: picking any member picks them all, and a gesture moves them
 * all by the same amount. That is what keeps a pair coincident while it is edited - two copies moved
 * separately would drift apart, and the weld would drag the artwork back together at runtime.
 *
 * Pairs chain across glues (a vertex glued to two meshes), so the groups are connected components.
 */
internal class WeldGroups private constructor(private val groupOf: Map<MeshVertex, List<MeshVertex>>) {
    /** Every group, each once, members in a stable order. */
    val groups: List<List<MeshVertex>> = groupOf.values.distinctBy { it.first() }

    /** The group [vertex] belongs to, or the vertex alone when it is not glued. */
    fun members(vertex: MeshVertex): List<MeshVertex> = groupOf[vertex] ?: listOf(vertex)

    fun isWelded(vertex: MeshVertex): Boolean = vertex in groupOf

    /** [selection] (mesh -> vertices) grown so no group is ever half selected. */
    fun expand(selection: Map<String, Set<Int>>): Map<String, Set<Int>> {
        if (groupOf.isEmpty()) return selection
        val grown = selection.mapValuesTo(LinkedHashMap()) { LinkedHashSet(it.value) }
        for ((mesh, indices) in selection) {
            for (index in indices) {
                val group = groupOf[MeshVertex(mesh, index)] ?: continue
                for (member in group) grown.getOrPut(member.mesh) { LinkedHashSet() } += member.index
            }
        }
        return grown
    }

    companion object {
        val EMPTY = WeldGroups(emptyMap())

        /** Groups formed by the pairs of [glues]; a pair whose vertex [exists] rejects is ignored. */
        fun of(glues: List<Glue>, exists: (MeshVertex) -> Boolean = { true }): WeldGroups {
            val parent = HashMap<MeshVertex, MeshVertex>()
            fun root(vertex: MeshVertex): MeshVertex {
                var current = vertex
                while (true) {
                    val next = parent.getValue(current)
                    if (next == current) return current
                    parent[current] = parent.getValue(next)
                    current = next
                }
            }
            for (glue in glues) {
                for (pair in glue.pairs) {
                    val a = MeshVertex(glue.meshA.raw, pair.indexA)
                    val b = MeshVertex(glue.meshB.raw, pair.indexB)
                    if (!exists(a) || !exists(b)) continue
                    parent.putIfAbsent(a, a)
                    parent.putIfAbsent(b, b)
                    val ra = root(a)
                    val rb = root(b)
                    if (ra != rb) parent[rb] = ra
                }
            }
            if (parent.isEmpty()) return EMPTY
            val byRoot = LinkedHashMap<MeshVertex, MutableList<MeshVertex>>()
            for (vertex in parent.keys.sortedWith(compareBy({ it.mesh }, { it.index }))) {
                byRoot.getOrPut(root(vertex)) { ArrayList() } += vertex
            }
            val groupOf = HashMap<MeshVertex, List<MeshVertex>>()
            for (group in byRoot.values) for (member in group) groupOf[member] = group
            return WeldGroups(groupOf)
        }
    }
}
