package io.github.psd2live.algorithms.binding

import org.umamo.runtime.model.*

/** Generates the rest frames of the head, face features and hair planes from source measurements. */
object ScaffoldHead {
    enum class Feature(val idStem: String, val rows: Int, val columns: Int) {
        EYE("EyeShape", 4, 8),
        IRIS("IrisPreserve", 3, 3),
        BROW("BrowShape", 3, 3),
        NOSE("NoseShape", 4, 3),
        MOUTH("MouthShape", 3, 4),
        EAR("EarOcclusion", 3, 3),
    }

    data class Region(val feature: Feature, val side: Side, val bounds: Bounds, val name: String, val gazeName: String? = null)
    data class Point(val x: Float, val y: Float)
    data class Hair(val bounds: Bounds, val sway: Boolean)
    data class Input(
        val bodyFrame: Bounds,
        val characterFrame: Bounds,
        val headFrame: Bounds,
        val faceFrame: Bounds,
        val alignedPivot: Point,
        val canvasPivot: Point,
        val initialAngle: Float,
        val featureDisplacement: Boolean,
        val regions: List<Region>,
        val frontHair: Hair? = null,
        val backHair: Hair? = null,
    )

    data class Result(val deformers: List<Deformer>, val frames: Map<DeformerId, Bounds>)

    val leanId = DeformerId("DeformBodyLean")
    val breathId = DeformerId("DeformBodyZBreath")
    val rotationId = DeformerId("DeformHeadRotation")
    val headId = DeformerId("DeformHeadContainer")
    val faceId = DeformerId("DeformFaceNinePose")
    val contourId = DeformerId("DeformFaceContour")
    val displacementId = DeformerId("DeformFeatureDisplacement")
    private val bodyPart = PartId("PartBody")
    private val headPart = PartId("PartHead")
    private val facePart = PartId("PartFace")
    private val unit = Bounds(0f, 0f, 1f, 1f)

    fun featureId(feature: Feature, side: Side) = DeformerId("Deform${feature.idStem}${sideToken(side)}")
    fun gazeId(side: Side) = DeformerId("DeformEyeGaze${sideToken(side)}")

    fun build(input: Input, label: (String) -> String, checkpoint: () -> Unit = {}): Result {
        val deformers = mutableListOf<Deformer>()
        val frames = linkedMapOf<DeformerId, Bounds>()
        fun add(deformer: Deformer, frame: Bounds) {
            checkpoint()
            deformers += deformer
            frames[deformer.id] = frame
        }

        val body = input.bodyFrame
        val head = input.headFrame
        val face = input.faceFrame
        add(warp(breathId, label("model.deformer.breath"), leanId, bodyPart, 6, 4) { u, v ->
            ((body.left + u * body.width - body.left) / body.width) to
                ((body.top + v * body.height - body.top) / body.height)
        }, body)
        val pivot = input.canvasPivot
        add(Deformer.Rotation(rotationId, label("model.deformer.headRotation"), breathId, headPart, input.initialAngle,
            static(RotationPivotForm(normalize(pivot.x, body.left, body.width), normalize(pivot.y, body.top, body.height),
                0f, 1f))), input.characterFrame)
        add(warp(headId, label("model.deformer.headContainer"), rotationId, headPart, 5, 4) { u, v ->
            (head.left + u * head.width - input.alignedPivot.x) to (head.top + v * head.height - input.alignedPivot.y)
        }, head)
        add(warp(faceId, label("model.deformer.face"), headId, facePart, 8, 8) { u, v ->
            normalize(face.left + u * face.width, head.left, head.width) to
                normalize(face.top + v * face.height, head.top, head.height)
        }, face)
        add(warp(contourId, label("model.deformer.faceContour"), faceId, facePart, 16, 8) { u, v -> u to v }, face)
        if (input.featureDisplacement) {
            add(warp(displacementId, label("model.deformer.featureDisplacement"), faceId, facePart, 8, 8) { u, v ->
                (0.5f + (u - 0.5f)) to v
            }, face)
        }

        val featureParent = if (input.featureDisplacement) displacementId else faceId
        val shared = mutableMapOf<Feature, Pair<DeformerId, Bounds>>()
        for ((feature, name) in listOf(Feature.EYE to "Eyes", Feature.BROW to "Brows", Feature.EAR to "Ears")) {
            val left = input.regions.firstOrNull { it.feature == feature && it.side == Side.LEFT } ?: continue
            val right = input.regions.firstOrNull { it.feature == feature && it.side == Side.RIGHT } ?: continue
            val bounds = left.bounds.union(right.bounds).expanded(0.04f)
            val id = DeformerId("Deform$name")
            val parent = if (feature == Feature.EAR) faceId else featureParent
            add(ScaffoldPairs.identityWarp(id, label("model.deformer.${name.lowercase()}"), parent, bounds, face, facePart, 3, 4), bounds)
            shared[feature] = id to bounds
        }
        for (region in input.regions.filter { it.feature != Feature.IRIS }) {
            val feature = region.feature
            val defaultParent = when (feature) {
                Feature.EYE, Feature.BROW, Feature.MOUTH -> featureParent
                else -> faceId
            }
            val (parent, bounds) = shared[feature] ?: (defaultParent to face)
            add(featureWarp(region, parent, bounds), region.bounds)
        }
        for (region in input.regions.filter { it.feature == Feature.IRIS }) {
            val eyes = input.regions.filter { it.feature == Feature.EYE }
            val eye = eyes.firstOrNull { it.side == region.side } ?: eyes.firstOrNull { it.side == Side.NONE }
            val eyeBounds = eye?.bounds ?: if (region.side == Side.NONE) eyes.map { it.bounds }.reduceOrNull(Bounds::union) else null
            if (eyeBounds == null) continue
            val iris = featureWarp(region, featureId(Feature.EYE, eye?.side ?: Side.NONE), eyeBounds)
            add(iris, region.bounds)
            add(ScaffoldPairs.identityWarp(gazeId(region.side), requireNotNull(region.gazeName), iris.id,
                unit, unit, facePart, 2, 2), region.bounds)
        }

        fun addHair(hair: Hair, side: String, physicsRows: Int) {
            val part = PartId("PartHair$side")
            val follow = DeformerId("DeformHair${side}Follow")
            val key = "${side.lowercase()}Hair"
            add(ScaffoldPairs.identityWarp(follow, label("model.deformer.${key}Follow"), headId, hair.bounds, head, part, 4, 3), hair.bounds)
            if (hair.sway) {
                val sway = DeformerId("DeformHair${side}Physics")
                add(ScaffoldPairs.identityWarp(sway, label("model.deformer.${key}Physics"), follow,
                    unit, unit, part, physicsRows, 3), hair.bounds)
            }
        }
        input.frontHair?.let { addHair(it, "Front", 4) }
        input.backHair?.let { addHair(it, "Back", 6) }
        return Result(deformers, frames)
    }

    private fun featureWarp(region: Region, parent: DeformerId, parentFrame: Bounds): Deformer.Warp =
        ScaffoldPairs.identityWarp(featureId(region.feature, region.side), region.name, parent, region.bounds,
            parentFrame, facePart, region.feature.rows, region.feature.columns)

    private fun sideToken(side: Side) = when (side) {
        Side.LEFT -> "L"
        Side.RIGHT -> "R"
        Side.NONE -> "Both"
    }

    private fun normalize(value: Float, start: Float, extent: Float) = (value - start) / extent.coerceAtLeast(1e-4f)

    private fun <T> static(form: T) = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), form)))

    private fun warp(
        id: DeformerId, name: String, parent: DeformerId, part: PartId, rows: Int, columns: Int,
        point: (Float, Float) -> Pair<Float, Float>,
    ): Deformer.Warp {
        val points = FloatArray((rows + 1) * (columns + 1) * 2)
        var index = 0
        for (row in 0..rows) for (column in 0..columns) {
            val (x, y) = point(column.toFloat() / columns, row.toFloat() / rows)
            points[index++] = x
            points[index++] = y
        }
        return Deformer.Warp(id, name, parent, part, rows, columns, true, static(WarpLatticeForm(points)))
    }
}
