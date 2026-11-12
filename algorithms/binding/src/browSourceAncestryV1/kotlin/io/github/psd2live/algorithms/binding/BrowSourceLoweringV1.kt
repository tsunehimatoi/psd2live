package io.github.psd2live.algorithms.binding

import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/** Frozen source-state lowering. Caller owns provider checks, real axis witnesses and write scope. */
object BrowSourceLoweringV1 {
    fun lower(
        canvas: Canvas, controls: List<Parameter>, live: Mesh, target: BrowShapeInputs.Target,
        canonicalId: String, name: String, branch: List<Deformer>, turn: HeadTurn, lift: Float,
        options: BrowOptions, wide: Boolean, smile: Boolean, turnName: String, shapeName: String,
        witnessMeshes: List<Mesh>, witnessWarps: List<Deformer.Warp>, checkpoint: () -> Unit = {},
    ): RigIR {
        val side = if (target.fit.side == Side.LEFT) "L" else "R"
        val raw = target.raw
        val positions = FloatArray(raw.rigPositions.size) { i ->
            if (i % 1024 == 0) checkpoint()
            if (target.parent == null) raw.rigPositions[i]
            else if (i % 2 == 0) (raw.rigPositions[i] - target.frame.left) / target.frame.width.coerceAtLeast(1e-4f)
            else (raw.rigPositions[i] - target.frame.top) / target.frame.height.coerceAtLeast(1e-4f)
        }
        val travel = requireNotNull(target.eye).let { it.opening * it.width * lift }
        val rig = BrowRig(target.fit, travel, options, checkpoint)
        val grid = rig.grid(raw.rigPositions, target.frame, ParameterId("ParamBrow${side}Y"), ParameterId("ParamBrow${side}Angle"))
        val shapes = rig.shapes(raw.rigPositions, target.frame, ParameterId("ParamBrow${side}X"), ParameterId("ParamBrow${side}Form"),
            EyeModules.open(target.fit.side), wide, if (smile) ParameterId("ParamEye${side}Smile") else null,
            if (options.spring) ParameterId("ParamBrow${side}Spring") else null) +
            turn.shapes(raw.rigPositions, target.frame, requireNotNull(DepthTurnMotion.shell("EYEBROW", null)),
                ParameterId("ParamAngleX"), ParameterId("ParamAngleY"))
        val native = live.copy(id = canonicalId, name = name,
            parent = target.parent, geometry = MeshGeometry(Floats.wrap(positions), Floats.wrap(raw.atlasUvs), Ints.wrap(raw.triangles)),
            offsets = offsets(grid), shapes = emptyList(), channels = emptyMap())
        val model = PuppetIr.toPuppet(RigIR(canvas, controls, meshes = listOf(native) + witnessMeshes,
            deformers = branch + witnessWarps))
        val drawable = model.drawables.first()
        val rest = meshGridDefaultDeltas(drawable) { parameter ->
            if (parameter.raw in setOf("ParamEyeLOpen", "ParamEyeROpen")) 1f else 0f
        }
        val lowered = BlendShapeLowering({ headTurn, name -> (if (headTurn) turnName else shapeName).replace("{meshName}", name) }, checkpoint)
            .lawful(model.copy(drawables = listOf(drawable.copy(blendShapes = shapes.map { binding -> binding.copy(forms = binding.forms.map { form ->
                form?.let {
                    val base = rest?.takeIf { deltas -> deltas.size == it.positionDeltas.size &&
                        binding.parameterId.raw in setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ") }
                    val deltas = if (base == null) it.positionDeltas else FloatArray(base.size) { i -> it.positionDeltas[i] + base[i] }
                    MeshForm(deltas, drawable.drawOrder, drawable.opacity, drawable.multiplyColor, drawable.screenColor)
                }
            }) })) + model.drawables.drop(1)))
        require(lowered.parameters == model.parameters) { "Source brow lowering cannot change upstream parameter kinds" }
        val result = PuppetIr.toIr(lowered)
        val mesh = result.meshes.first().copy(id = live.id)
        val nodes = result.deformers.associateBy { it.id }
        val ancestors = generateSequence(mesh.parent) { nodes.getValue(it).parent }.map(nodes::getValue).toList()
        return RigIR(result.canvas, result.parameters, meshes = listOf(mesh), deformers = ancestors)
    }

    private fun offsets(grid: KeyformGrid<MeshDeltaForm>) = KeyGrid(
        grid.axes.map { KeyAxis(it.parameterId.raw, Floats.wrap(it.keys)) },
        grid.cells.map { KeyCell(Ints.wrap(it.coordinate), MeshOffsets(Floats.wrap(it.form.positionDeltas))) })
}
