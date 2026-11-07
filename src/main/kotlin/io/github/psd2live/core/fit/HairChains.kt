package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.ClassifiedLayer
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.algorithms.binding.ChainKind
import io.github.psd2live.algorithms.binding.ChainMotion
import org.umamo.runtime.model.ParameterId

enum class HairKind {
    FRONT, SIDE, BACK, AHOGE, TAIL, SKIRT, RIBBON, PENDANT, EAR, ANIMAL_TAIL, WING;

    internal val motion get() = ChainKind.valueOf(name)
    val word get() = motion.word
    val prefix get() = motion.prefix
    val hair get() = motion.hair

	companion object {
		/** The kind of creature part [layer] is, or null: an animal ear, a tail, a wing. */
		fun partOf(layer: ClassifiedLayer): HairKind? = when {
			layer.semantic.role == PartRole.ANIMAL -> EAR
			layer.semantic.tag == SemanticTag.TAIL -> ANIMAL_TAIL
			layer.semantic.tag == SemanticTag.WINGS -> WING
			else -> null
		}

		/** The kind of clothing chain [layer] hangs in, or null: a skirt, a ribbon or a pendant. */
		fun clothOf(layer: ClassifiedLayer): HairKind? = when (layer.semantic.role) {
			PartRole.SKIRT -> SKIRT
			PartRole.RIBBON -> if (layer.semantic.tag == SemanticTag.BACK_HAIR) null else RIBBON
			PartRole.PENDANT -> PENDANT
			else -> null
		}

		fun of(layer: ClassifiedLayer): HairKind? = when {
			layer.semantic.tag != SemanticTag.FRONT_HAIR && layer.semantic.tag != SemanticTag.BACK_HAIR -> null
			// A hair's shadow or highlight rides the chain of the hair it lies on ([HairChain.owner]).
			layer.semantic.role == PartRole.SHADOW || layer.semantic.role == PartRole.HIGHLIGHT -> null
			layer.semantic.role == PartRole.AHOGE -> AHOGE
			layer.semantic.role == PartRole.TAIL || layer.semantic.role == PartRole.BRAID -> TAIL
			layer.semantic.role == PartRole.SIDE -> SIDE
			layer.semantic.tag == SemanticTag.FRONT_HAIR -> FRONT
			else -> BACK
		}
	}
}

data class HairChain(
    val layerId: String, val kind: HairKind, val index: Int, val frame: Bounds, val freeFrom: Float, val regions: Int,
    val strength: Float = 1f, val softness: Float = 0.5f,
) {
    private val motion = ChainMotion(layerId, kind.motion, index, frame, freeFrom, regions, strength, softness)
    val warpId get() = motion.warpId
    val parameters get() = motion.parameters
    val bounce get() = motion.bounce
    fun segment(j: Int, k: Int): ParameterId = motion.segment(j, k)
    fun shapes(rest: FloatArray, rows: Int, columns: Int, toParent: (FloatArray) -> FloatArray) =
        motion.shapes(rest, rows, columns, toParent)

    companion object {
        const val ROWS = ChainMotion.ROWS
        const val COLUMNS = ChainMotion.COLUMNS

        private fun source(layer: ClassifiedLayer, kind: HairKind? = null) =
            ChainMotion.Layer(layer.source.id.raw, layer.semantic.tag.name, layer.bounds, layer.opaquePixels, kind?.motion)

        fun owner(layer: ClassifiedLayer, chains: List<HairChain>, layers: Map<String, ClassifiedLayer>): HairChain? =
            ChainMotion.owner(source(layer), chains.map { it.motion }, layers.mapValues { source(it.value) })
                ?.let { owner -> chains.first { it.motion == owner } }

        fun plan(layers: List<ClassifiedLayer>, faceWidth: Float, freeY: (HairKind, Bounds) -> Float, strength: Float, softness: Float,
                 kindOf: (ClassifiedLayer) -> HairKind? = HairKind::of, first: Int = 0): List<HairChain> =
            ChainMotion.plan(layers.map { source(it, kindOf(it)) }, faceWidth, { kind, bounds -> freeY(HairKind.valueOf(kind.name), bounds) },
                strength, softness, first).map {
                HairChain(it.layerId, HairKind.valueOf(it.kind.name), it.index, it.frame, it.freeFrom, it.regions, it.strength, it.softness)
            }

        internal fun expressionInputs(parameters: List<String>) = ChainMotion.expressionInputs(parameters)

        fun physics(available: Set<String>, softness: Float = 0.5f, impulses: List<String> = emptyList()) =
            ChainMotion.physics(available, softness, impulses) { key, name -> io.github.psd2live.i18n.tr(key, name) }
    }
}
