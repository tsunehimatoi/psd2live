package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId

/** Generates arm hang lattices in the captured parents' default coordinate frames. */
object ScaffoldArms {
    data class Parent(val id: DeformerId, val frame: Bounds)
    data class Layer(val id: String, val name: String, val side: Side, val bounds: Bounds, val parent: Parent)
    data class ArmFrame(val warp: Deformer.Warp, val bounds: Bounds, val layerIds: List<String>)

    fun build(
        layers: List<Layer>,
        availableParents: Set<DeformerId>,
        partId: PartId,
        nameForArm: (String) -> String,
        checkpoint: () -> Unit = {},
    ): List<ArmFrame> = buildList {
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            checkpoint()
            val arms = layers.filter { it.side == side }
            if (arms.isEmpty()) continue
            val parent = arms.map { it.parent }.distinct().singleOrNull() ?: continue
            if (parent.id !in availableParents) continue
            val frame = arms.map { it.bounds }.reduce(Bounds::union).expanded(0.06f)
            val sideToken = if (side == Side.LEFT) "L" else "R"
            val warp = ScaffoldPairs.identityWarp(
                DeformerId("DeformArmHang_$sideToken"), nameForArm(arms.first().name), parent.id,
                frame, parent.frame, partId, rows = 6, columns = 3,
            )
            add(ArmFrame(warp, frame, arms.map { it.id }))
        }
    }
}
