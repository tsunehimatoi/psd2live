package io.github.psd2live.core.modifier

import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.RigIR
import java.security.MessageDigest

/** Includes upstream geometry, forms, relationships and sidecars with their exact persisted float bits. */
object SimulationInput {
    fun fingerprint(rig: RigIR): String = MessageDigest.getInstance("SHA-256").digest(RigIrBinary.encode(rig))
        .let { java.util.HexFormat.of().formatHex(it) }
    /** Simulation reads its targets, collision carriers, glue-connected meshes and their ancestor frames. */
    fun simulationFingerprint(rig: RigIR, edit: io.github.psd2live.core.sim.RigSimEdit): String {
        val meshes = (edit.targets + edit.colliders.map { it.mesh }).toMutableSet()
        do {
            val size = meshes.size
            rig.glues.forEach { if (it.meshA in meshes || it.meshB in meshes) { meshes += it.meshA; meshes += it.meshB } }
        } while (size != meshes.size)
        val parents = rig.deformers.associateBy { it.id }
        val deformers = HashSet<String>()
        for (mesh in rig.meshes.filter { it.id in meshes }) {
            var parent = mesh.parent
            while (parent != null && deformers.add(parent)) parent = parents[parent]?.parent
        }
        // Sidecars remain conservative: paths, parameter ranges and authored data may affect carrier evaluation.
        val input = rig.copy(meshes = rig.meshes.filter { it.id in meshes }, deformers = rig.deformers.filter { it.id in deformers },
            textures = io.github.psd2live.format.model.Textures())
        return "simulation-v2:" + fingerprint(input)
    }

    fun simulationMatches(rig: RigIR, edit: io.github.psd2live.core.sim.RigSimEdit, expected: String?): Boolean =
        expected == null || expected == if (expected.startsWith("simulation-v2:")) simulationFingerprint(rig, edit) else fingerprint(rig)

}
