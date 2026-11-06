package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.BlendShapeLowering
import io.github.psd2live.i18n.tr
import org.umamo.runtime.model.PuppetModel

/** Localized application adapter for the independent binding kernel. */
internal object CubismBlendShapes {
    fun lawful(generated: PuppetModel): PuppetModel = BlendShapeLowering(
        carrierName = { head, meshName ->
            tr(if (head) "model.deformer.meshTurn" else "model.deformer.meshShape", meshName)
        },
    ).lawful(generated)
}
