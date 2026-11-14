package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*

/** The source coordinates are measured before binding. The neutral scaffold supplies carrier identities and topology. */
object LegacyFaceBinding {
    val angleX = ParameterId("ParamAngleX")
    val angleY = ParameterId("ParamAngleY")
    val angleZ = ParameterId("ParamAngleZ")
    private val bodyLean = ParameterId("ParamBodyLean")
    private val x = KeyformAxis(angleX, floatArrayOf(-45f, 0f, 45f))
    private val y = KeyformAxis(angleY, floatArrayOf(-30f, 0f, 30f))
    private val z = KeyformAxis(angleZ, floatArrayOf(-30f, 0f, 30f))
    private val xy = listOf(x, y)

    data class Measurements(
        val head: ScaffoldHead.Input,
        val centerY: Float,
        val radiusX: Float,
        val radiusY: Float,
        val eyeLineY: Float,
    )

    fun bind(
        before: PuppetModel,
        source: Measurements,
        tuning: LegacyFaceTuning,
        strength: Float,
        angleParameters: List<Parameter>,
        folderName: String,
        checkpoint: () -> Unit = {},
    ): PuppetModel {
        require(strength.isFinite() && strength in 0f..4f)
        require(angleParameters.map { it.id } == listOf(angleX, angleY, angleZ))
        val head = source.head
        val face = head.faceFrame
        val pivot = head.alignedPivot
        val motion = LegacyFaceMotion(pivot.x, source.centerY, source.radiusX, source.radiusY, tuning)
        val regions = head.regions.associateBy { ScaffoldHead.featureId(it.feature, it.side) }
        val frames = ScaffoldHead.build(head, { it }, checkpoint).frames
        val hasLean = before.parameters.any { it.id == bodyLean }
        val deformers = before.deformers.map { deformer ->
            checkpoint()
            when (deformer) {
                is Deformer.Rotation -> if (deformer.id != ScaffoldHead.rotationId) deformer else {
                    val rest = requireNotNull(deformer.geometryGrid) { "Missing scaffold head pivot" }
                    val cells = z.keys.flatMapIndexed { index, angle ->
                        rest.cells.map { cell ->
                            checkpoint()
                            val form = cell.form
                            KeyformCell(intArrayOf(index) + cell.coordinate,
                                RotationPivotForm(form.originX, form.originY, angle, form.scale))
                        }
                    }
                    deformer.copy(geometryGrid = KeyformGrid(listOf(z) + rest.axes, cells))
                }
                is Deformer.Warp -> {
                    val warp = when (deformer.id) {
                        ScaffoldHead.headId -> lattice(deformer, xy, checkpoint) { u, v, a ->
                            LegacyFaceMotion.headContainerPoint(head.headFrame, pivot.x, pivot.y, u, v, a[0], a[1], strength, tuning)
                        }
                        ScaffoldHead.faceId -> lattice(deformer, xy, checkpoint) { u, v, a ->
                            val point = motion.surfacePoint(face.left + u * face.width, face.top + v * face.height, a[0], a[1], strength)
                            normalize(point.first, head.headFrame.left, head.headFrame.width) to
                                normalize(point.second, head.headFrame.top, head.headFrame.height)
                        }
                        ScaffoldHead.contourId -> lattice(deformer, listOf(x), checkpoint) { u, v, a ->
                            LegacyFaceMotion.faceContourPoint(u, v, a[0], strength,
                                normalize(source.eyeLineY, face.top, face.height).coerceIn(0.05f, 0.95f), tuning)
                        }
                        ScaffoldHead.displacementId -> lattice(deformer, xy, checkpoint) { u, v, a ->
                            LegacyFaceMotion.featureDisplacementPoint(u, v, a[0], a[1], strength, tuning,
                                face.width / face.height.coerceAtLeast(1e-4f))
                        }
                        else -> when (deformer.id.raw) {
                            "DeformHairFrontFollow" -> hair(deformer, head, requireNotNull(head.frontHair).bounds, true, tuning, checkpoint)
                            "DeformHairBackFollow" -> hair(deformer, head, requireNotNull(head.backHair).bounds, false, tuning, checkpoint)
                            else -> regions[deformer.id]?.let { region ->
                                val parent = frames.getValue(requireNotNull(deformer.parent))
                                val bounds = mapBounds(region.bounds, parent)
                                val shaped = lattice(deformer, xy, checkpoint) { u, v, a ->
                                    val (dx, dy) = motion.featureOffset(region.feature, region.bounds, u, v, a[0], a[1], strength)
                                    (bounds.left + u * bounds.width + dx / parent.width.coerceAtLeast(1e-4f)) to
                                        (bounds.top + v * bounds.height + dy / parent.height.coerceAtLeast(1e-4f))
                                }
                                if (region.feature != ScaffoldHead.Feature.EAR) shaped else shaped.copy(
                                    channelGrids = ChannelGrids(shaped.channelGrids.gridsByChannel + (FormChannel.OPACITY to
                                        grid<ChannelValue>(listOf(x), checkpoint) { ChannelValue.Scalar(motion.earOpacity(region.bounds, it[0], strength)) })))
                            } ?: deformer
                        }
                    }
                    if (warp === deformer || !hasLean) warp else lean(warp, tuning)
                }
            }
        }
        val folder = ParameterNode.Group(ParameterGroupId("ParamGroupFace"), folderName, true,
            angleParameters.map { ParameterNode.Param(it.id) })
        val existingFolder = before.parameterTree.singleOrNull { it is ParameterNode.Group && it.id == folder.id } as? ParameterNode.Group
        val nextTree = if (existingFolder == null) listOf(folder) + before.parameterTree
            else before.parameterTree.map {
                if (it === existingFolder) folder.copy(children = folder.children + existingFolder.children) else it
            }
        return before.copy(
            parameters = angleParameters + before.parameters,
            parameterTree = nextTree,
            parameterLinks = before.parameterLinks + listOf(ParameterLink(angleX, angleY)),
            deformers = deformers,
        )
    }

    private fun hair(deformer: Deformer.Warp, head: ScaffoldHead.Input, frame: Bounds, front: Boolean,
                     tuning: LegacyFaceTuning, checkpoint: () -> Unit): Deformer.Warp {
        val bounds = mapBounds(frame, head.headFrame)
        val roll = LegacyFaceMotion.Companion.HairRoll(head.canvasPivot.x, head.canvasPivot.y, head.faceFrame.bottom, tuning.hairRollHold / 100f)
        val bends = roll.hold > 0f && frame.bottom > roll.chinY + frame.height * 0.1f
        return lattice(deformer, if (bends) xy + z else xy, checkpoint) { u, v, a ->
            val point = LegacyFaceMotion.hairFollowPoint(bounds, u, v, a[0], a[1],
                -(if (front) tuning.frontHairTurn else tuning.backHairTurn) / 100f,
                -(if (front) tuning.frontHairTilt else tuning.backHairTilt) / 100f,
                if (front) tuning.frontHairPerspective / 100f else 0f)
            if (!bends) point else {
                val (dx, dy) = roll.turnBack(frame.left + u * frame.width, frame.top + v * frame.height, frame.bottom, a[2])
                point.first + dx / head.headFrame.width to point.second + dy / head.headFrame.height
            }
        }
    }

    private fun lean(deformer: Deformer.Warp, tuning: LegacyFaceTuning): Deformer.Warp {
        val grid = requireNotNull(deformer.geometryGrid)
        val yi = grid.axes.indexOfFirst { it.parameterId == angleY }
        if (yi < 0) return deformer
        val keys = grid.axes[yi].keys
        val forms = grid.cells.associateBy { it.coordinate.toList() }
        val leans = floatArrayOf(-10f, 0f, 10f)
        val shifts = floatArrayOf(tuning.faceLeanUp, 0f, -tuning.faceLeanDown)
        val cells = grid.cells.flatMap { cell ->
            leans.indices.map { l ->
                fun at(index: Int) = forms.getValue(cell.coordinate.copyOf().also { it[yi] = index }.toList()).form.controlPoints
                val form = if (shifts[l] == 0f) cell.form else {
                    val target = (keys[cell.coordinate[yi]] + shifts[l]).coerceIn(keys.first(), keys.last())
                    var j = 0
                    while (j < keys.size - 2 && target > keys[j + 1]) j++
                    val t = ((target - keys[j]) / (keys[j + 1] - keys[j])).coerceIn(0f, 1f)
                    val a = at(j)
                    val b = at(j + 1)
                    WarpLatticeForm(FloatArray(a.size) { a[it] + (b[it] - a[it]) * t })
                }
                KeyformCell(cell.coordinate + l, form)
            }
        }
        return deformer.copy(geometryGrid = KeyformGrid(grid.axes + KeyformAxis(bodyLean, leans), cells))
    }

    private fun lattice(deformer: Deformer.Warp, axes: List<KeyformAxis>, checkpoint: () -> Unit,
                        point: (Float, Float, FloatArray) -> Pair<Float, Float>): Deformer.Warp =
        deformer.copy(geometryGrid = grid(axes, checkpoint) { values ->
            val points = FloatArray((deformer.columns + 1) * (deformer.rows + 1) * 2)
            var i = 0
            for (row in 0..deformer.rows) for (column in 0..deformer.columns) {
                val (u, v) = point(column.toFloat() / deformer.columns, row.toFloat() / deformer.rows, values)
                points[i++] = u
                points[i++] = v
            }
            WarpLatticeForm(points)
        })

    private fun <T> grid(axes: List<KeyformAxis>, checkpoint: () -> Unit, form: (FloatArray) -> T): KeyformGrid<T> {
        val cells = mutableListOf<KeyformCell<T>>()
        val coordinate = IntArray(axes.size)
        val values = FloatArray(axes.size)
        fun visit(axis: Int) {
            if (axis == axes.size) {
                checkpoint()
                cells += KeyformCell(coordinate.copyOf(), form(values))
                return
            }
            for ((index, value) in axes[axis].keys.withIndex()) {
                coordinate[axis] = index
                values[axis] = value
                visit(axis + 1)
            }
        }
        visit(0)
        return KeyformGrid(axes, cells)
    }

    private fun normalize(value: Float, start: Float, extent: Float) = (value - start) / extent.coerceAtLeast(1e-4f)
    private fun mapBounds(child: Bounds, parent: Bounds) = Bounds(
        normalize(child.left, parent.left, parent.width), normalize(child.top, parent.top, parent.height),
        normalize(child.right, parent.left, parent.width), normalize(child.bottom, parent.top, parent.height),
    )
}
