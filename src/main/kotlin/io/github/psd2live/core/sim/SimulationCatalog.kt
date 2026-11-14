package io.github.psd2live.core.sim

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.modifier.*
import org.umamo.runtime.model.PuppetModel

/** Read projection only: generated bodies remain owned by their modifier, never copied into simEdits. */
internal object SimulationCatalog {
    fun owner(overlay: RigEditOverlay, id: String): RigModifierInstance? = overlay.modifierStack?.let {
        RigModifierStack.fromJson(it).instances.firstOrNull { entry -> entry.type == "simulation" && entry.id == id }
    }

    private class Snapshot(val overlay: RigEditOverlay, val entries: List<RigSimEdit>)
    @Volatile private var snapshot: Snapshot? = null

    fun entries(overlay: RigEditOverlay): List<RigSimEdit> {
        snapshot?.takeIf { it.overlay === overlay }?.let { return it.entries }
        return readEntries(overlay).also { snapshot = Snapshot(overlay, it) }
    }

    fun physicsOwner(overlay: RigEditOverlay, group: String): RigSimEdit? {
        val generated = overlay.modifierStack?.let { RigModifierStack.fromJson(it).instances.firstOrNull { entry ->
            entry.type == "simulation" && entry.localOutputName(group)?.startsWith("physics:") == true
        } }
        return if (generated != null) entries(overlay).firstOrNull { it.id == generated.id }
            else SimGenerator.simulationOf(group, overlay.simEdits)
    }

    private fun readEntries(overlay: RigEditOverlay): List<RigSimEdit> = overlay.simEdits + overlay.modifierStack?.let {
        RigModifierStack.fromJson(it).instances.filter { entry -> entry.type == "simulation" }.map { entry ->
            val source = RigSimEdit.fromJson(entry.config)
            val resolved = if (source.bake == null) source else RigModifierAlgorithms.simulationOutputs(entry, source)
            resolved.copy(id = entry.id, enabled = entry.enabled && source.enabled)
        }
    }.orEmpty()

    fun compare(model: io.github.psd2live.core.RigPreviewModel, overlay: RigEditOverlay, id: String, motions: List<String>,
                progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): List<SimCompare.Result> {
        val entry = owner(overlay, id) ?: return SimCompare.compare(overlay,
            SimAuthoring.AuthoredRigs(model::authoredPuppet), id, motions, progress = progress, cancelled = cancelled)
        if (cancelled()) throw java.util.concurrent.CancellationException("Simulation comparison cancelled")
        val stack = RigModifierStack.fromJson(requireNotNull(overlay.modifierStack))
        val prefix = stack.copy(instances = stack.instances.takeWhile { it.id != entry.id })
        val pipeline = io.github.psd2live.core.PSD2LivePipeline().apply { deferRuntimeBundles = true }
        pipeline.reuseModifierInputs(model)
        val before = pipeline.buildPreview(model.analysis.source, model.config.copy(rigEdits = overlay.copy(modifierStack = prefix.toJson())))
        val weighted = RigSimulationWeights.apply(before.rig.puppet, entry.config)
        val source = RigModifierAlgorithms.simulationOutputs(entry, RigSimEdit.fromJson(entry.config))
        val clips = stack.instances.filter { it.enabled && it.type == "motion" }.map {
            io.github.psd2live.core.MotionClips.fromJson(it.config).copy(id = it.outputId("motion"), builtin = null)
        }
        val comparison = RigEditOverlay(simEdits = listOf(source), motionClips = overlay.motionClips + clips, physicsFps = overlay.physicsFps)
        return SimCompare.compare(comparison, SimAuthoring.AuthoredRigs { weighted }, source.id, motions,
            progress = progress, cancelled = cancelled)
    }

    fun stale(overlay: RigEditOverlay, model: PuppetModel, edit: RigSimEdit): Boolean {
        val original = owner(overlay, edit.id)?.let { RigSimEdit.fromJson(it.config) }
        return SimBake.stale(model, if (original == null) edit else edit.copy(id = original.id))
    }
}
