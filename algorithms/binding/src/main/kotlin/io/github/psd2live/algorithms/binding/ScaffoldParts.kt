package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*

/** Builds the source organization tree in the same order used by binding identities. */
object ScaffoldParts {
    enum class Slot(val id: PartId, val label: String) {
        BACK_HAIR(PartId("PartHairBack"), "model.part.backHair"),
        FACE(PartId("PartFace"), "model.part.face"),
        FRONT_HAIR(PartId("PartHairFront"), "model.part.frontHair"),
        ACCESSORIES(PartId("PartHeadAccessories"), "model.part.headAccessories"),
        EXTRA(PartId("PartExtra"), "model.part.extra"),
        BODY(PartId("PartBody"), "model.part.body"),
        UNKNOWN(PartId("PartBody"), "model.part.body"),
    }

    data class Material(val id: DrawableId, val slot: Slot, val sourceOrder: Int)
    data class Result(val parts: List<Part>, val rootChildren: List<OrgChild>)

    fun build(materials: List<Material>, label: (String) -> String): Result {
        val children = materials.sortedBy { it.sourceOrder }.groupBy { it.slot }
        fun childrenOf(slot: Slot) = children[slot].orEmpty().map { OrgChild.Drawable(it.id) }
        val head = PartId("PartHead")
        val headSlots = listOf(Slot.BACK_HAIR, Slot.FACE, Slot.FRONT_HAIR, Slot.ACCESSORIES)
        val parts = buildList {
            add(Part(head, label("model.part.head"), headSlots.map { OrgChild.Part(it.id) }, groupMode = PartGroupMode.PassThrough))
            for (slot in headSlots + listOf(Slot.EXTRA, Slot.BODY)) {
                val drawables = childrenOf(slot) + if (slot == Slot.BODY) childrenOf(Slot.UNKNOWN) else emptyList()
                add(Part(slot.id, label(slot.label), drawables, groupMode = PartGroupMode.PassThrough))
            }
        }
        return Result(parts, listOf(head, Slot.EXTRA.id, Slot.BODY.id).map { OrgChild.Part(it) })
    }
}
