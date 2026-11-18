package io.github.psd2live.core.modifier

import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.core.sim.RigSimEdit
import org.umamo.runtime.model.PuppetModel
import java.security.MessageDigest

/** Includes upstream geometry, forms, relationships and sidecars with their exact persisted float bits. */
object SimulationInput {
    fun fingerprint(rig: RigIR): String = MessageDigest.getInstance("SHA-256").digest(RigIrBinary.encode(rig))
        .let { java.util.HexFormat.of().formatHex(it) }
    /** The same dependency closure selects the geometry sampled each frame and the geometry hashed for reuse. */
    private fun geometry(edit: RigSimEdit, glues: List<Pair<String, String>>, meshParents: Map<String, String?>,
                         deformerParents: Map<String, String?>): Pair<Set<String>, Set<String>> {
        val neighbours = mutableMapOf<String, MutableList<String>>()
        for ((a, b) in glues) {
            neighbours.getOrPut(a) { mutableListOf() }.add(b)
            neighbours.getOrPut(b) { mutableListOf() }.add(a)
        }
        val meshes = (edit.targets + edit.colliders.map { it.mesh }).toMutableSet()
        val pending = ArrayDeque(meshes)
        while (pending.isNotEmpty()) {
            for (id in neighbours[pending.removeFirst()].orEmpty()) if (meshes.add(id)) pending.addLast(id)
        }
        val deformers = HashSet<String>()
        for (mesh in meshes) {
            var parent = meshParents[mesh]
            while (parent != null && deformers.add(parent)) parent = deformerParents[parent]
        }
        return meshes to deformers
    }

    internal fun bakeModel(model: PuppetModel, edit: RigSimEdit): PuppetModel {
        val (meshes, deformers) = geometry(edit, model.glues.map { it.meshA.raw to it.meshB.raw },
            model.drawables.associate { it.id.raw to it.parentDeformerId?.raw },
            model.deformers.associate { it.id.raw to it.parent?.raw })
        return model.copy(drawables = model.drawables.filter { it.id.raw in meshes },
            deformers = model.deformers.filter { it.id.raw in deformers },
            glues = model.glues.filter { it.meshA.raw in meshes })
    }

    fun simulationFingerprint(rig: RigIR, edit: RigSimEdit): String {
        val (meshes, deformers) = geometry(edit, rig.glues.map { it.meshA to it.meshB },
            rig.meshes.associate { it.id to it.parent }, rig.deformers.associate { it.id to it.parent })
        // Sidecars remain conservative: paths, parameter ranges and authored data may affect carrier evaluation.
        val input = rig.copy(meshes = rig.meshes.filter { it.id in meshes }, deformers = rig.deformers.filter { it.id in deformers },
            textures = io.github.psd2live.format.model.Textures())
        return "simulation-v2:" + fingerprint(input)
    }

    fun simulationMatches(rig: RigIR, edit: io.github.psd2live.core.sim.RigSimEdit, expected: String?): Boolean =
        expected == null || expected == if (expected.startsWith("simulation-v2:")) simulationFingerprint(rig, edit) else fingerprint(rig)

}
