package io.github.psd2live.algorithms.binding

import io.github.psd2live.algorithm.worker.AlgorithmWorker
import io.github.psd2live.core.modifier.ChangingModifierAlgorithmPlugin
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.core.modifier.ModifierModelChanges
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.PuppetModel

object LegacyBodyV2Worker {
    @JvmStatic fun main(args: Array<String>) = AlgorithmWorker.run(
        mapOf((LegacyBodyV2Algorithm.ENTRYPOINT to LegacyBodyV2Algorithm.VERSION) to LegacyBodyV2Algorithm))
}

object LegacyBodyV2Algorithm : ChangingModifierAlgorithmPlugin {
    const val TYPE = "body/legacy"
    const val VERSION = 2
    const val ENTRYPOINT = "builtin.626f64792f6c6567616379.v2/motion"
    private val body = LegacyBodyAlgorithm()

    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                         checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION)
        val scaffold = context.values.getValue("scaffold")
        val head = LegacyFaceInputs.decode(ModifierAlgorithmContext(
            JsonObject(context.values.getValue("pitch").jsonObject + ("scaffold" to scaffold))),
            checkpoint)
        val nodes = ScaffoldHead.build(head.measurements.head, { it }, checkpoint).deformers
        val neutral = PuppetModel(emptyList(), emptyList(), nodes, emptyList(), emptyList(), null)
        val angleIds = listOf(LegacyFaceBinding.angleX, LegacyFaceBinding.angleY, LegacyFaceBinding.angleZ)
        val angles = angleIds.mapIndexed { i, id ->
            val limit = if (i == 0) 45f else 30f
            Parameter(id, head.parameterLabels[i], -limit, limit, 0f)
        }
        val posed = LegacyFaceBinding.bind(neutral, head.measurements, head.tuning, head.strength, angles,
            head.folderLabel, checkpoint)
        val pitch = posed.deformers.filterIsInstance<Deformer.Warp>()
            .filter { warp -> warp.geometryGrid?.axes?.any { it.parameterId == LegacyFaceBinding.angleY } == true }
            .map(PuppetIr::deformerToIr)
        val encoded = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(0f, 0f), emptyList(), deformers = pitch)))
        val bodyContext = ModifierAlgorithmContext(JsonObject(context.values - "pitch"),
            context.resources + (LegacyBodyInputs.HEAD_POSES to encoded), context.upstream)
        return body.changes(input, instance.copy(version = 1), bodyContext, checkpoint)
    }
}
