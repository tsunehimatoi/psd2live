package io.github.psd2live.application

import io.github.psd2live.core.*
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/** One process-owned preview clock, shared by desktop frames and explicit agent steps. */
internal class WorkspacePreviewPhysics {
    private var key: List<Any?>? = null
    private var engine: PhysicsEngine? = null
    private var playing: Boolean? = null
    private var released = emptyMap<String, Float>()
    private var last = emptyMap<ParameterId, Float>()
    private var stillFor = 0f
    var settled: Boolean = true
        private set

    fun reset() { engine?.reset(); playing = null; last = emptyMap(); stillFor = 0f; settled = true }

    fun step(model: RigPreviewModel, inputs: Map<ParameterId, Float>, locked: Set<ParameterId>, dt: Float,
             isPlaying: Boolean): Map<ParameterId, Float> {
        require(dt.isFinite() && dt in 0f..1f) { "Physics delta must lie within 0..1 seconds" }
        val config = model.config
        val groups = if (!config.generatePhysics || config.meshOnly) emptyList() else PhysicsCatalog.groups(
            if (config.rigEdits.importedCmo3 != null) PhysicsGenerator.Presets(false, false, false)
            else PhysicsGenerator.Presets.present(model.analysis, config.hairSimulationFront, config.hairSimulationBack),
            PhysicsGenerator.Presets(config.physicsFrontHair, config.physicsBackHair, config.physicsEyeJelly), config.rigEdits,
            model.rig.puppet.parameters.mapTo(hashSetOf()) { it.id.raw }, model.rig.puppet).filter { it.active }.map { it.setting }
        val parameters = model.rig.puppet.parameters
        val nextKey = listOf(groups, parameters, config.rigEdits.physicsFps)
        if (nextKey != key) {
            val before = engine?.strands.orEmpty().flatMap { it.setting.outputParameters }.toSet()
            key = nextKey
            engine = PhysicsEngine(groups, PhysicsEngine.ranges(parameters), config.rigEdits.physicsFps.toFloat()).also { it.carryOver(engine) }
            val now = groups.flatMap { it.outputParameters }.toSet()
            released = parameters.filter { it.id.raw in before - now }.associate { it.id.raw to it.default }
        }
        if (playing != isPlaying) { engine?.reset(); playing = isPlaying; stillFor = 0f }
        val result = (released + engine!!.step(inputs.mapKeys { it.key.raw }, dt)).mapKeys { ParameterId(it.key) }.filterKeys { it !in locked }
        released = emptyMap()
        val moved = result.any { (id, value) -> last[id]?.let { abs(value - it) > 1e-4f } != false }
        stillFor = if (moved) 0f else stillFor + dt
        settled = result.isEmpty() || (!isPlaying && stillFor >= 0.5f)
        last = result
        return result
    }
}
