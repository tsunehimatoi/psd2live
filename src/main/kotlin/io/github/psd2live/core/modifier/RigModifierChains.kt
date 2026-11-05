package io.github.psd2live.core.modifier

import io.github.psd2live.core.RigIdRemap
import io.github.psd2live.core.fit.HairChain
import io.github.psd2live.core.fit.HairKind
import org.umamo.runtime.model.*

/** Temporary native ordinals affect the original pendulum formulas, never the public object identities. */
internal object RigModifierChains {
    private val families = mapOf(
        "hair/chains" to HairKind.entries.filterTo(linkedSetOf()) { it.hair },
        "outfit.skirt" to setOf(HairKind.SKIRT),
        "outfit.ribbons" to setOf(HairKind.RIBBON, HairKind.PENDANT),
        "outfit.creature" to setOf(HairKind.EAR, HairKind.ANIMAL_TAIL, HairKind.WING),
    )
    fun kinds(type: String): Set<HairKind> = families[type].orEmpty()

    data class Binding(val native: HairChain) {
        val key = "${native.kind.word}:${native.layerId.length}:${native.layerId}"
        val warp = DeformerId("SourceChain:$key")
        fun suffix(parameter: String) = parameter.removePrefix(native.kind.prefix + native.kind.word + native.index)
        val parameters = native.parameters.associate { it.raw to ParameterId("SourceChain:$key:${suffix(it.raw)}") }
    }

    data class Owned(val binding: Binding, val owner: String, val aliases: Map<String, String>)
    data class Projection(val model: PuppetModel, val chains: List<Owned>)

    fun normalize(model: PuppetModel, chains: List<Binding>) = RigIdRemap(
        deformers = chains.associate { DeformerId(it.native.warpId) to it.warp },
        parameters = chains.flatMap { it.parameters.entries }.associate { ParameterId(it.key) to it.value },
    ).model(model)

    fun projection(model: PuppetModel, target: ModifierTarget, instances: List<RigModifierInstance>, chains: List<Binding>,
                   cache: MutableMap<List<Owned>, Projection>,
                   restrict: (PuppetModel, Set<ParameterId>) -> PuppetModel): Projection {
        if (chains.isEmpty()) return Projection(model, emptyList())
        val mesh = model.drawables.single { it.id.raw == target.id }
        val branch = model.deformers.associateBy { it.id }
        val visited = HashSet<DeformerId>()
        var parent = mesh.parentDeformerId
        while (parent != null) {
            require(visited.add(parent)) { "Cyclic chain hierarchy" }
            parent = branch.getValue(parent).parent
        }
        val mappings = LinkedHashMap<ParameterId, ParameterId>()
        val owners = chains.filter { it.warp in visited }.map { chain ->
            val owner = requireNotNull(instances.lastOrNull { chain.native.kind in kinds(it.type) }) { "A chain output has no modifier owner" }
            Owned(chain, owner.id, chain.parameters.mapValues { (_, parameter) ->
                ParameterId(owner.outputId("chain-parameter:${parameter.raw.removePrefix("SourceChain:")}")).also { mappings[parameter] = it }.raw
            })
        }
        return cache.getOrPut(owners) {
            val all = chains.flatMapTo(HashSet()) { it.parameters.values }
            val kept = model.parameters.filter { it.id !in all || it.id in mappings }.mapTo(HashSet()) { it.id }
            Projection(RigIdRemap(parameters = mappings).model(restrict(model, kept)), owners)
        }
    }
}
