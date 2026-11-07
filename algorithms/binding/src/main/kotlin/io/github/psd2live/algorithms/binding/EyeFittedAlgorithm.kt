package io.github.psd2live.algorithms.binding

import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.ParameterLink
import io.github.psd2live.format.model.ParameterNode
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.*

/**
 * Builds fitted eye motion on the source scaffold's existing topology and coordinate frames.
 * The scaffold must derive both its hierarchy and vertices from the fitted source trace.
 */
class EyeFittedAlgorithm : ChangingModifierAlgorithmPlugin {
    override fun changes(input: RigIR, instance: RigModifierInstance, context: ModifierAlgorithmContext, checkpoint: () -> Unit): ModifierModelChanges {
        require(instance.version == VERSION && instance.targets.all { it.kind == "mesh" })
        val scaffold = instance.config.getValue("scaffold").jsonPrimitive.content
        require(context.upstream.singleOrNull()?.let { it.capability == "rig.scaffold" && it.instanceId == scaffold &&
            it.targets.containsAll(instance.targets) } == true) { "Missing captured fitted eye scaffold: $scaffold" }
        val (captured, sources, raw) = EyeFittedInputs.decode(context, checkpoint)
        val targets = instance.targets.mapTo(hashSetOf()) { it.id }
        val meshIds = input.meshes.mapTo(hashSetOf()) { it.id }
        require(targets.all { it in meshIds })
        val eyeTargets = captured.materials.keys.intersect(targets)
        val eye = fit(captured, sources, instance, checkpoint)
        val gaze = input.deformers.filterIsInstance<io.github.psd2live.format.model.Deformer.Warp>()
            .filter { it.id in captured.gazeCarriers }
        val openings = input.meshes.mapNotNull { mesh -> captured.openings[mesh.id]?.let { mesh.id to it } }.toMap()
        val materialMeshes = input.meshes.filter { it.id in eyeTargets }
        val model = PuppetIr.toPuppet(RigIR(input.canvas, input.parameters, deformers = gaze, meshes = materialMeshes))
        val defaults = input.parameters.associate { it.id to it.default }
        val pose: (ParameterId) -> Float = { defaults[it.raw] ?: if (it == leftOpen || it == rightOpen) 1f else 0f }
        val coordinateInput = ModifierEvaluationInput(input)
        val meshUpdates = model.drawables.zip(materialMeshes).mapNotNull { (drawable, original) ->
            checkpoint()
            val id = original.id
            val material = captured.materials.getValue(id)
            val coordinates = if (original.parent != material.parent && original.geometry != null)
                BindingCoordinates.forMesh(coordinateInput, id, checkpoint = checkpoint) else null
            val updated = project(drawable, sources.layerForTarget(id), material,
                captured.tuning, captured.faceCenterX, captured.meshAlignment, coordinates,
                sources, raw.getValue(material.canonical), eye, openings, pose, checkpoint)
            val converted = PuppetIr.meshToIr(updated)
            val after = original.copy(offsets = converted.offsets,
                channels = converted.channels, maskedBy = converted.maskedBy)
            if (after == original) null else ModifierObjectUpdate(original, after)
        }
        val warpUpdates = model.deformers.filterIsInstance<Deformer.Warp>().zip(gaze).mapNotNull { (warp, original) ->
            checkpoint()
            val grid = gazeGrid(warp, captured.tuning, pose)
            val converted = PuppetIr.deformerToIr(warp.copy(geometryGrid = grid)) as io.github.psd2live.format.model.Deformer.Warp
            val after = original.copy(lattice = converted.lattice)
            if (after == original) null else ModifierObjectUpdate<io.github.psd2live.format.model.Deformer>(original, after)
        }
        val additions = parameters(captured.parameterNames).filter { it.id !in defaults }
        val tree = parameterTree(input, instance, captured.folderName, additions)
        val link = ParameterLink(gazeX.raw, gazeY.raw)
        val links = if (link in input.parameterLinks) null else {
            require(input.parameterLinks.none { it.horizontal in setOf(link.horizontal, link.vertical) ||
                it.vertical in setOf(link.horizontal, link.vertical) }) { "Incompatible eye gaze parameter link" }
            ModifierFieldChange(input.parameterLinks, input.parameterLinks + link)
        }
        return ModifierModelChanges(parameters = ModifierObjectChanges(added = additions),
            meshes = ModifierObjectChanges(updated = meshUpdates), deformers = ModifierObjectChanges(updated = warpUpdates),
            parameterLinks = links, parameterTree = tree)
    }

    private fun fit(input: EyeFittedInputs, sources: ModifierSourceInputs, instance: RigModifierInstance, checkpoint: () -> Unit): FittedEyeMotion {
        val align: (Float, Float) -> Pair<Float, Float> = { x, y -> input.alignment?.point(x, y) ?: (x to y) }
        val values = instance.config["values"]?.jsonObject.orEmpty()
        fun knob(id: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
            (values[id]?.jsonPrimitive?.float ?: default).also { require(it in range) { "Invalid fitted eye value: $id" } } / 100f
        require(values.keys.all { it in setOf("lowerShare", "roundness", "lashSquash") })
        val lower = knob("lowerShare", 5f, 0f..50f)
        val roundness = knob("roundness", 100f, 40f..160f)
        val squash = knob("lashSquash", 60f, 20f..100f)
        val fits = EyeFitting.fit(sources, input.faceCenterX, align).fits.mapValues { (side, fit) ->
            checkpoint()
            fit.moved(input.moves[side.name].orEmpty())
        }
        val artwork = EyeArtworkFitting.fit(sources, input.artworkStamps, fits, input.corrections, align)
        return FittedEyeMotion(fits.mapValues { (side, fit) ->
            EyeRig(fit.copy(artwork = artwork[side]), lower, roundness, squash)
        })
    }

    private fun project(
        original: Drawable, layer: ModifierSourceLayer, material: EyeFittedInputs.Material, tuning: EyeFittedInputs.Tuning, faceCenterX: Float,
        alignment: EyeFittedInputs.Alignment?, coordinates: BindingCoordinates?,
        sources: ModifierSourceInputs, raw: ScaffoldMesh.RawMesh,
        eyes: FittedEyeMotion, openings: Map<String, EyeOpeningAssociation.Layer>,
        pose: (ParameterId) -> Float, checkpoint: () -> Unit,
    ): Drawable {
        val mesh = original.mesh ?: return original
        val side = Side.valueOf(layer.side)
        val frame = material.frame.bounds()
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        val offsets = original.geometryGrid
        val originalFrame = original.parentDeformerId?.raw == material.parent
        // Exact source vertices avoid reconstructing and rounding unchanged parent-local positions.
        val sourceGeometry = originalFrame && raw.rigPositions.size == mesh.positions.size &&
            raw.triangles.contentEquals(mesh.indices) && raw.atlasUvs.contentEquals(mesh.uvs) &&
            raw.rigPositions.indices.all { i ->
                val value = if (i % 2 == 0) (raw.rigPositions[i] - frame.left) / width else (raw.rigPositions[i] - frame.top) / height
                value.toRawBits() == mesh.positions[i].toRawBits()
            }
        val motion = EyeModules.meshOf(layer.tag, layer.role)
        val eye = eyes.rigs[side]
        val iris = ScaffoldMesh.Geometry.Iris(material.alignedCentroidX, material.alignedCentroidY, tuning.irisJellySquash, tuning.irisJellyStretch)
        val fallback by lazy { ScaffoldMesh.Geometry.Eye(layer.tag == "EYELASH", material.alignedBounds.bounds(),
            material.fallbackWhites.map { it.bounds() }, material.alignedCentroidY, layer.left.toInt(),
            if (layer.tag == "EYELASH" && material.useSourceColumns) centerline(sources.raster(layer), layer.top, checkpoint) else null,
            tuning.blinkCorner, tuning.blinkDepth, tuning.eyelashSquash) }
        val neutralGeometry = when {
            motion != null -> fallback
            layer.tag == "IRIDES" -> iris
            else -> ScaffoldMesh.Geometry.Zero
        }
        val parent = ScaffoldArms.Parent(original.parentDeformerId ?: DeformerId("eye-source"), frame)
        val rest = offsets?.takeIf { it.axes.isEmpty() }?.cells?.singleOrNull()?.form?.positionDeltas
        val canonicalRest = if (sourceGeometry && rest != null)
            ScaffoldMesh.build(raw, parent, neutralGeometry, checkpoint).second.cells.single().form.positionDeltas else null
        val sourceRest = offsets == null || rest != null && canonicalRest != null &&
            rest.indices.all { rest[it] == canonicalRest[it] }
        val space = alignment.takeIf { material.aligned }
        val points = when {
            sourceGeometry -> raw.rigPositions
            coordinates != null -> coordinates.toCanvas(mesh.positions).also { points ->
                if (space != null) for (i in points.indices step 2) {
                    val (x, y) = space.point(points[i], points[i + 1])
                    points[i] = x
                    points[i + 1] = y
                }
            }
            else -> FloatArray(mesh.positions.size) { i ->
                if (i % 2 == 0) frame.left + mesh.positions[i] * width else frame.top + mesh.positions[i] * height
            }
        }
        val generated = when {
            motion != null -> when {
                side == Side.NONE && eyes.rigs.isNotEmpty() -> requireNotNull(EyeModules.pairedLidGrid(motion, layer.id, points, frame, eyes))
                eye != null -> EyeModules.lidGrid(motion, side, points, frame, eye, eyes.options, layer.id)
                else -> closure(points, frame, side, fallback, faceCenterX, checkpoint)
            }
            layer.tag == "IRIDES" -> jelly(points, frame, iris, checkpoint)
            else -> null
        }
        val localGrid = if (coordinates != null && generated != null) KeyformGrid(generated.axes, generated.cells.map { cell ->
            checkpoint()
            val deltas = cell.form.positionDeltas
            val posed = FloatArray(points.size)
            for (i in points.indices step 2) {
                val x = points[i] + deltas[i] * width
                val y = points[i + 1] + deltas[i + 1] * height
                val canvas = space?.canvas(x, y) ?: (x to y)
                posed[i] = canvas.first
                posed[i + 1] = canvas.second
            }
            val local = coordinates.fromCanvas(posed, mesh.positions)
            KeyformCell(cell.coordinate, MeshDeltaForm(FloatArray(local.size) { i -> local[i] - mesh.positions[i] }))
        }) else generated
        val retained = when {
            localGrid == null -> offsets
            sourceRest -> localGrid
            else -> addOffsets(original, localGrid, pose, checkpoint)
        }
        val swap = eye?.fit?.closed != null
        val oldOpacity = original.channelGrids[FormChannel.OPACITY]
        val opacity = if (!material.preset) null else when {
            layer.tag == "EYE_CLOSE" -> EyeModules.drawingOpacity(side, true, swap, 1f)
            layer.tag == "EYELASH" && swap && (layer.role == null || layer.role == "LOWER") ->
                EyeModules.drawingOpacity(side, false, true, 1f)
            else -> null
        }
        val masks = if (layer.tag != "IRIDES") original.maskedBy else {
            val selected = EyeOpeningAssociation.select(material.opening, openings.values.toList()).mapTo(hashSetOf()) { it.id }
            val before = listOfNotNull(material.ordinaryMask?.let(::DrawableId))
            val after = openings.filterValues { it.id in selected }.keys.map(::DrawableId)
            if (before != after) {
                require(!original.invertMask && (original.maskedBy == before || original.maskedBy == after)) {
                    "Incompatible authored iris masks on ${original.id.raw}"
                }
                after
            } else original.maskedBy
        }
        val channel = if (opacity != null && oldOpacity != null) {
            val desired = ChannelGrids(mapOf(FormChannel.OPACITY to opacity))
            val axes = BindingAxes.unionAxes(oldOpacity.axes + opacity.axes)
            grid<ChannelValue>(axes) { keys ->
                checkpoint()
                val byId = axes.mapIndexed { i, axis -> axis.parameterId to keys[i] }.toMap()
                val sample: (ParameterId) -> Float = { byId[it] ?: pose(it) }
                val authored = original.channelGrids.scalarAt(FormChannel.OPACITY, original.opacity, sample)
                val generatedOpacity = desired.scalarAt(FormChannel.OPACITY, original.opacity, sample)
                ChannelValue.Scalar(authored - original.opacity + generatedOpacity)
            }
        } else opacity
        return original.copy(geometryGrid = retained, maskedBy = masks,
            channelGrids = if (channel == null) original.channelGrids
                else ChannelGrids(original.channelGrids.gridsByChannel + (FormChannel.OPACITY to channel)))
    }

    private fun closure(points: FloatArray, frame: Bounds, side: Side, eye: ScaffoldMesh.Geometry.Eye, faceCenterX: Float, checkpoint: () -> Unit): KeyformGrid<MeshDeltaForm> {
        val opens = floatArrayOf(0f, 1f)
        val axes = when (side) {
            Side.LEFT -> listOf(KeyformAxis(leftOpen, opens))
            Side.RIGHT -> listOf(KeyformAxis(rightOpen, opens))
            Side.NONE -> listOf(KeyformAxis(leftOpen, opens), KeyformAxis(rightOpen, opens))
        }
        return grid(axes) { keys ->
            val deltas = offsets(points, frame, checkpoint) { x, y -> ScaffoldMesh.eyePoint(x, y, eye) }
            for (i in deltas.indices step 2) {
                val index = if (side == Side.NONE && points[i] < faceCenterX) 1 else 0
                deltas[i] *= 1f - keys[index]
                deltas[i + 1] *= 1f - keys[index]
            }
            MeshDeltaForm(deltas)
        }
    }

    private fun jelly(points: FloatArray, frame: Bounds, iris: ScaffoldMesh.Geometry.Iris, checkpoint: () -> Unit) =
        grid(listOf(KeyformAxis(jelly, symmetric))) { keys ->
            MeshDeltaForm(offsets(points, frame, checkpoint) { x, y -> ScaffoldMesh.irisPoint(x, y, iris, keys[0]) })
        }

    private fun offsets(points: FloatArray, frame: Bounds, checkpoint: () -> Unit, point: (Float, Float) -> Pair<Float, Float>): FloatArray {
        val width = frame.width.coerceAtLeast(1e-4f)
        val height = frame.height.coerceAtLeast(1e-4f)
        val delta = FloatArray(points.size)
        for (i in points.indices step 2) {
            if (i % 1024 == 0) checkpoint()
            val (x, y) = point(points[i], points[i + 1])
            delta[i] = (x - points[i]) / width
            delta[i + 1] = (y - points[i + 1]) / height
        }
        return delta
    }

    private fun addOffsets(original: Drawable, generated: KeyformGrid<MeshDeltaForm>, defaults: (ParameterId) -> Float,
                           checkpoint: () -> Unit): KeyformGrid<MeshDeltaForm> {
        val axes = BindingAxes.unionAxes(generated.axes + original.geometryGrid?.axes.orEmpty())
        val neutral = meshGridDefaultDeltas(original.copy(geometryGrid = generated), defaults)
        return grid(axes) { keys ->
            checkpoint()
            val byId = axes.mapIndexed { i, axis -> axis.parameterId to keys[i] }.toMap()
            val pose: (ParameterId) -> Float = { byId[it] ?: defaults(it) }
            val authored = meshGridDefaultDeltas(original, pose)
            val added = requireNotNull(meshGridDefaultDeltas(original.copy(geometryGrid = generated), pose))
            MeshDeltaForm(FloatArray(added.size) { i -> (authored?.get(i) ?: 0f) + (added[i] - (neutral?.get(i) ?: 0f)) })
        }
    }

    private fun gazeGrid(warp: Deformer.Warp, tuning: EyeFittedInputs.Tuning, defaults: (ParameterId) -> Float): KeyformGrid<WarpLatticeForm> {
        val axes = BindingAxes.unionAxes(listOf(KeyformAxis(gazeX, symmetric), KeyformAxis(gazeY, symmetric)) + warp.geometryGrid?.axes.orEmpty())
        return grid(axes) { keys ->
            val values = axes.mapIndexed { i, axis -> axis.parameterId to keys[i] }.toMap()
            val base = requireNotNull(warpControlPointsAt(warp.geometryGrid) { values[it] ?: defaults(it) })
            WarpLatticeForm(FloatArray(base.size) { i ->
                if (i % 2 == 0) base[i] + values.getValue(gazeX) * tuning.gazeX / 100f
                else base[i] - values.getValue(gazeY) * tuning.gazeY / 100f
            })
        }
    }

    private fun <T> grid(axes: List<KeyformAxis>, form: (FloatArray) -> T): KeyformGrid<T> {
        val coordinates = BindingAxes.coordinates(axes.asReversed())
        return KeyformGrid(axes, coordinates.map { reversed ->
            val at = reversed.reversedArray()
            KeyformCell(at, form(FloatArray(at.size) { i -> axes[i].keys[at[i]] }))
        })
    }

    private fun parameterTree(input: RigIR, instance: RigModifierInstance, name: String, additions: List<Parameter>): ModifierFieldChange<List<ParameterNode>>? {
        if (additions.isEmpty()) return null
        val id = instance.outputId("parameter-folder:ParamGroupEyes")
        val nodes = additions.map { ParameterNode.Param(it.id) }
        var found = false
        fun append(tree: List<ParameterNode>): List<ParameterNode> = tree.map { node ->
            if (node !is ParameterNode.Group) node
            else if (node.id == id) {
                found = true
                node.copy(children = node.children + nodes)
            } else node.copy(children = append(node.children))
        }
        val tree = append(input.parameterTree.ifEmpty { input.parameters.map { ParameterNode.Param(it.id) } })
        return ModifierFieldChange(input.parameterTree, if (found) tree else tree + ParameterNode.Group(id, name, true, nodes))
    }

    companion object {
        const val VERSION = 3
        private val leftOpen = EyeModules.open(Side.LEFT)
        private val rightOpen = EyeModules.open(Side.RIGHT)
        private val gazeX = ParameterId("ParamEyeBallX")
        private val gazeY = ParameterId("ParamEyeBallY")
        private val jelly = ParameterId("ParamEyeBallForm")
        private val symmetric = floatArrayOf(-1f, 0f, 1f)
        private fun ModifierSourceBounds.bounds() = Bounds(left, top, right, bottom)

        fun parameters(names: Map<String, String>): List<Parameter> =
            listOf(leftOpen, rightOpen, gazeX, gazeY, jelly).map { parameter ->
                val opens = parameter == leftOpen || parameter == rightOpen
                Parameter(parameter.raw, names.getValue(parameter.raw), if (opens) 0f else -1f, 1f, if (opens) 1f else 0f)
            }

        private fun centerline(raster: ModifierSourceInputs.Raster, top: Float, checkpoint: () -> Unit): FloatArray? {
            val line = FloatArray(raster.width) { Float.NaN }
            for (x in line.indices) {
                checkpoint()
                var weight = 0f
                var weightedY = 0f
                for (y in 0 until raster.height) {
                    val alpha = raster.alpha(x, y).toFloat()
                    weight += alpha
                    weightedY += (y + .5f) * alpha
                }
                if (weight > 0f) line[x] = top + weightedY / weight
            }
            val first = line.indexOfFirst { !it.isNaN() }
            if (first < 0) return null
            line.fill(line[first], 0, first)
            var previous = first
            for (x in first + 1 until line.size) {
                if (line[x].isNaN()) continue
                for (step in 1 until x - previous) line[previous + step] =
                    line[previous] + (line[x] - line[previous]) * step / (x - previous)
                previous = x
            }
            line.fill(line[previous], previous + 1)
            return line
        }
    }
}
