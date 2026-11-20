package io.github.psd2live.core.sim

import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.SwingGenerator
import io.github.psd2live.core.modifier.ModifierParameterGroups
import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterKeys
import org.umamo.runtime.keyform.MeshDeltaInterpolator
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.isDense
import org.umamo.runtime.keyform.withKeyInserted
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/**
 * Writes baked simulations back onto a rebuilt rig, after the swings: adds the static corrections to the
 * target meshes' keyforms on the static parameters' axes, and creates each mode's parameter with its key
 * shapes - as blend shapes where the runtime has them and the simulation asks for them, which add to the
 * grid instead of multiplying it, otherwise as another keyform axis. Nothing is simulated here. A bake that no longer fits (a mesh gone or remeshed, a static parameter deleted) is skipped where
 * it does not fit and reported by [issues] instead of failing the rebuild.
 */
object SimGenerator {
    /** Past this many cells a mesh's keyform grid is left alone. */
    private const val MAX_CELLS = 200_000
    /**
     * The mode parameters span -[MODE_RANGE]..[MODE_RANGE]. Wide on purpose: a keyform axis snaps a value
     * within 0.001 of a key onto it (Cubism does too), and over -1..1 that is a visible jolt on a large body
     * each time a mode swings through rest.
     */
    const val MODE_RANGE = 30f
    /** Past this many keyforms on a target, a simulation left to choose writes its modes as blend shapes. */
    const val AUTO_BLEND_CELLS = 64L

    fun apply(
        model: PuppetModel,
        sims: List<RigSimEdit>,
        physicsFolderId: ParameterGroupId = physicsGroupId,
        physicsFolderName: String? = null,
    ): PuppetModel =
        sims.fold(model) { current, sim -> applyOne(current, sim, physicsFolderId, physicsFolderName).first }

    fun issues(model: PuppetModel, sim: RigSimEdit): List<String> = applyOne(model, sim).second

    /** `ParamSim<id>_<k>` for mode [k] (1-based). */
    fun parameterId(sim: RigSimEdit, k: Int) = "ParamSim${SwingGenerator.asciiStem(sim.id)}_$k"

    /**
     * `ParamSim<id>_Y`: how the body lags and bounces up and down, as an artist's `裙y` or `长发y`. A pendulum
     * answers only sideways, so the inputs that move the body up and down drive it apart, as translations.
     */
    fun verticalParameterId(sim: RigSimEdit) = "ParamSim${SwingGenerator.asciiStem(sim.id)}_Y"

    /** What mode parameter [id], the [k]th (0-based) of [sideways] that are not the vertical one, is called unless renamed. */
    fun defaultParameterName(sim: RigSimEdit, id: String, k: Int, sideways: Int) = when {
        id == verticalParameterId(sim) -> "${sim.name} Y"
        sideways == 1 -> sim.name
        else -> "${sim.name} ${k + 1}"
    }

    /** `PhysicsSim_<id>`: the pendulum of a baked simulation; a later mode with one of its own adds `_<k>`. */
    fun physicsId(sim: RigSimEdit) = "PhysicsSim_${sim.id}"

    /** `PhysicsSim_<id>_y`: the pendulum driving [verticalParameterId]. */
    fun verticalPhysicsId(sim: RigSimEdit) = "PhysicsSim_${sim.id}_y"

    /** The pendulums of every enabled baked simulation whose parameters exist, under the names given them. */
    fun physicsRules(sims: List<RigSimEdit>, available: Set<String>): List<RigPhysicsEdit> = sims.filter { it.enabled }.flatMap { sim ->
        sim.bake?.pendulums.orEmpty().mapNotNull { rule ->
            written(sim, rule).let { it.copy(inputs = it.inputs.filter { p -> p.parameter in available }, outputs = it.outputs.filter { o -> o.parameter in available }) }
                .takeIf { it.inputs.isNotEmpty() && it.outputs.isNotEmpty() }
        }
    }

    /**
     * Baked pendulum [rule] as [sim] writes it: under its given name, each output on its parameter's output
     * ID with its scale stretched to the output's range, so the pendulum still sweeps the same share of it.
     */
    fun written(sim: RigSimEdit, rule: RigPhysicsEdit): RigPhysicsEdit = rule.copy(
        name = sim.outputNames[rule.id] ?: rule.name,
        outputs = rule.outputs.map { o -> o.copy(parameter = sim.outputId(o.parameter), scale = o.scale * sim.outputRange(o.parameter) / MODE_RANGE) },
    )

    /** Every pendulum of [sim]'s bake as written, whether or not its parameters exist. */
    fun writtenPendulums(sim: RigSimEdit): List<RigPhysicsEdit> = sim.bake?.pendulums.orEmpty().map { written(sim, it) }

    /**
     * Baked mode [axis] as [sim] writes it: on its output ID, its keys stretched to the output's range and
     * its shapes scaled by the output's gain.
     */
    fun written(sim: RigSimEdit, axis: SimBakedAxis): SimBakedAxis {
        val stretch = sim.outputRange(axis.parameter) / MODE_RANGE
        val gain = sim.outputGain(axis.parameter)
        return SimBakedAxis(sim.outputId(axis.parameter), FloatArray(axis.keys.size) { axis.keys[it] * stretch },
            if (gain == 1f) axis.offsets else axis.offsets.mapValues { (_, per) -> per.map { form -> FloatArray(form.size) { form[it] * gain } } },
            axis.limits.map { limit ->
                val source = limit.parameterId.raw
                val sourceStretch = sim.outputRange(source) / MODE_RANGE
                BlendWeightLimit(ParameterId(sim.outputId(source)), limit.points.map { it.copy(value = it.value * sourceStretch) })
            }, axis.normalWeight)
    }

    /** The simulation a generated pendulum belongs to. */
    fun simulationOf(groupId: String, sims: List<RigSimEdit>): RigSimEdit? = sims.firstOrNull { sim -> sim.bake?.pendulums.orEmpty().any { it.id == groupId } }

    /** [pose] without the parameters [sim]'s bake drives, so they sit at their defaults under the live simulation. */
    fun withoutModes(pose: Map<ParameterId, Float>, sim: RigSimEdit): Map<ParameterId, Float> {
        if (sim.bake == null) return pose
        val owned = sim.outputParameters.toSet()
        return pose.filterKeys { it.raw !in owned }
    }

    /**
     * Whether [sim]'s modes go in as blend shapes on [model]. Keyform axes play the same and every Cubism
     * version reads them, so left to choose, blend shapes are used only where the axes would multiply a
     * target's keyforms past [AUTO_BLEND_CELLS].
     */
    fun usesBlendShapes(model: PuppetModel, sim: RigSimEdit): Boolean {
        if (!model.runtimeTarget.supports(RuntimeFeature.MeshWarpBlendShapes)) return false
        sim.blendShapes?.let { return it }
        if (sim.bake?.modes?.any { it.axis.limits.isNotEmpty() } == true) return true
        val modes = sim.bake?.modes?.map { it.axis.keys.size } ?: List(sim.modes) { sim.keys }
        val own = (sim.bake?.parameters ?: (1..sim.modes).map { parameterId(sim, it) }).map { ParameterId(sim.outputId(it)) }.toSet()
        val added = modes.fold(1L) { n, keys -> n * keys }
        return sim.targets.any { target ->
            val grid = model.drawables.firstOrNull { it.id.raw == target }?.geometryGrid
            val cells = grid?.axes?.filter { it.parameterId !in own }?.fold(1L) { n, axis -> n * axis.keys.size } ?: 1L
            cells * added > AUTO_BLEND_CELLS
        }
    }

    private val physicsGroupId = ParameterGroupId(ModifierParameterGroups.PHYSICS)

    /**
     * [ids] moved from the root of the parameter tree to [folderId], wherever the user has put it,
     * or creates [folderId] in canonical order when [folderName] is supplied.
     */
    private fun withPhysicsGroup(
        model: PuppetModel,
        ids: List<ParameterId>,
        folderId: ParameterGroupId = physicsGroupId,
        folderName: String? = null,
    ): PuppetModel {
        if (ids.isEmpty()) return model
        val moved = ids.toSet()
        var placed = false
        fun place(nodes: List<ParameterNode>): List<ParameterNode> = nodes.mapNotNull { node ->
            when (node) {
                is ParameterNode.Param -> node.takeIf { it.id !in moved }
                is ParameterNode.Group -> {
                    val children = place(node.children)
                    if (node.id == folderId && !placed) {
                        placed = true
                        val existing = children.mapNotNullTo(HashSet()) { (it as? ParameterNode.Param)?.id }
                        node.copy(children = children + ids.filter { it !in existing }.map { ParameterNode.Param(it) })
                    } else node.copy(children = children)
                }
            }
        }
        val tree = place(model.parameterTree)
        if (placed) return model.copy(parameterTree = tree)
        if (folderName == null) return model
        val group = ParameterNode.Group(folderId, folderName, true, ids.map { ParameterNode.Param(it) })
        val rank = ModifierParameterGroups.canonicalRank(folderId.raw)
        val inserted = if (rank == null) tree + group else {
            val index = tree.indexOfFirst { node ->
                node is ParameterNode.Group && (ModifierParameterGroups.canonicalRank(node.id.raw)?.let { it > rank } == true)
            }
            if (index < 0) tree + group else tree.toMutableList().apply { add(index, group) }
        }
        return model.copy(parameterTree = inserted)
    }

    fun applyOne(
        model: PuppetModel,
        sim: RigSimEdit,
        physicsFolderId: ParameterGroupId = physicsGroupId,
        physicsFolderName: String? = null,
    ): Pair<PuppetModel, List<String>> {
        val bake = sim.bake?.takeIf { sim.enabled } ?: return model to emptyList()
        val issues = ArrayList<String>()
        var current = model
        val blend = usesBlendShapes(model, sim)
        val vertical = verticalParameterId(sim)
        val sideways = bake.modes.count { it.axis.parameter != vertical }
        val created = ArrayList<ParameterId>()
        val modes = bake.modes.map { mode ->
            val axis = written(sim, mode.axis)
            if (blend || axis.normalWeight == 1f) axis else axis.withMeshOffsets(axis.offsets.mapValues { (_, forms) ->
                forms.map { form -> FloatArray(form.size) { form[it] * axis.normalWeight } }
            })
        }
        for ((k, mode) in bake.modes.withIndex()) {
            val baked = mode.axis.parameter
            val keys = modes[k].keys
            val id = ParameterId(modes[k].parameter)
            if (current.parameters.any { it.id == id }) continue
            val name = sim.outputNames[baked] ?: defaultParameterName(sim, baked, k, sideways)
            current = current.withParameterCreated(id, name, if (blend) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL)
            created += id
            current = current.copy(parameters = current.parameters.map {
                if (it.id != id) it
                else it.copy(min = keys.first(), max = keys.last(), default = 0f, keys = if (blend) keys.toList() else it.keys)
            })
        }
        current = withPhysicsGroup(current, created, physicsFolderId, physicsFolderName)
        // Static corrections first: blend shapes add to the grid as it is at the default pose. The modes
        // swing as far as the simulation times their gain, over their output range; the statics stay exact.
        for (axis in bake.statics + modes) {
            val parameter = current.parameters.firstOrNull { it.id.raw == axis.parameter }
            if (parameter == null) { issues += "${sim.id}: parameter ${axis.parameter} no longer exists"; continue }
            val asBlend = blend && axis !in bake.statics
            if (parameter.kind != (if (asBlend) ParameterKind.BLEND_SHAPE else ParameterKind.NORMAL)) {
                issues += "${sim.id}: ${axis.parameter} is not a ${if (asBlend) "blend shape" else "normal"} parameter"; continue
            }
            for ((mesh, count) in bake.vertexCounts) {
                val offsets = axis.offsets[mesh] ?: continue
                val drawable = current.drawables.firstOrNull { it.id.raw == mesh }
                val target = drawable?.mesh
                if (target == null) { issues += "${sim.id}: mesh $mesh not found"; continue }
                if (target.vertexCount != count || offsets.any { it.size != count * 2 }) {
                    issues += "${sim.id}: $mesh was remeshed since the bake"; continue
                }
                val next = if (asBlend) drawable.copy(blendShapes = drawable.blendShapes.filter { it.parameterId != parameter.id } +
                    blendBinding(current, drawable, parameter.id, axis, mesh))
                else {
                    val grid = drawable.geometryGrid ?: KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(count * 2)))))
                    val cells = withOffsets(grid, parameter, axis, mesh)?.takeIf { it.cells.size <= MAX_CELLS }
                    if (cells == null) { issues += "${sim.id}: $mesh keyforms cannot take ${axis.parameter}"; continue }
                    drawable.copy(geometryGrid = cells)
                }
                current = current.copy(drawables = current.drawables.map { if (it.id == drawable.id) next else it })
            }
            val authored = current.parameters.single { it.id == parameter.id }.keys
            if (authored != null) current = current.withParameterKeys(parameter.id, (authored + axis.keys.toList()).distinct().sorted())
        }
        return current to issues.distinct()
    }

    /**
     * [axis]'s offsets as a blend shape on [drawable]: each key's form is the grid at the default pose plus
     * the offsets, with the channels as they are there, so only the shape moves.
     */
    private fun blendBinding(model: PuppetModel, drawable: Drawable, parameter: ParameterId, axis: SimBakedAxis, mesh: String): BlendShapeBinding<MeshForm> {
        val defaults = model.parameters.associate { it.id to it.default }
        val defaultValue: (ParameterId) -> Float = { defaults[it] ?: 0f }
        val count = drawable.mesh!!.vertexCount * 2
        val reference = meshGridDefaultDeltas(drawable, defaultValue) ?: FloatArray(count)
        val drawOrder = drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder.toFloat(), defaultValue)
        val opacity = drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, defaultValue)
        val multiply = drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, defaultValue)
        val screen = drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, defaultValue)
        val neutral = axis.keys.indexOfFirst { it == 0f }
        val limits = axis.limits.filter { limit -> model.parameters.any { it.id == limit.parameterId } }
        return BlendShapeBinding(parameter, axis.keys.copyOf(), neutral, axis.keys.indices.map { k ->
            if (k == neutral) null else {
                val add = axis.offsets.getValue(mesh)[k]
                MeshForm(FloatArray(count) { reference[it] + add[it] }, drawOrder, opacity, multiply, screen)
            }
        }, limits)
    }

    /**
     * [grid] plus [axis]'s offsets for the mesh: a new axis gets the baked keys, each a copy of the grid with
     * its offsets added; an axis the grid already has gains the baked keys and every cell adds the offsets
     * at its key, so what was keyed there before is kept.
     */
    fun withOffsets(grid: KeyformGrid<MeshDeltaForm>, parameter: Parameter, axis: SimBakedAxis, mesh: String): KeyformGrid<MeshDeltaForm>? {
        if (!grid.isDense) return null
        val count = grid.cells.first().form.positionDeltas.size
        var next = grid
        var index = next.axisIndexOf(parameter.id)
        if (index < 0) {
            val cells = ArrayList<KeyformCell<MeshDeltaForm>>(next.cells.size * axis.keys.size)
            for (k in axis.keys.indices) for (cell in next.cells) cells += KeyformCell(cell.coordinate + k, cell.form)
            next = KeyformGrid(next.axes + KeyformAxis(parameter.id, axis.keys.copyOf()), cells)
            index = next.axes.size - 1
        } else {
            for (key in axis.keys) next = next.withKeyInserted(parameter.id, key, MeshDeltaInterpolator)
        }
        val keys = next.axes[index].keys
        return KeyformGrid(next.axes, next.cells.map { cell ->
            val add = axis.at(mesh, keys[cell.coordinate[index]]) ?: return null
            if (add.size != count) return null
            val base = cell.form.positionDeltas
            KeyformCell(cell.coordinate, MeshDeltaForm(FloatArray(count) { base[it] + add[it] }))
        })
    }
}
