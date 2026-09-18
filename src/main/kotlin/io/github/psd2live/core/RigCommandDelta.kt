package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import kotlin.math.abs

/**
 * Decides whether a compiled authoring command would write anything the model does not already hold.
 *
 * This exists because the change guards in the workspace funnel cannot see the difference. `authorRig`
 * appends every compiled command to `rigEdits.authoringJournal`, and that journal is part of the
 * document the revision hash is taken over — so a command that changed nothing still changes the
 * document, and still earns a history node. `compile` is where it has to be decided, because it is the
 * single choke point every producer goes through: the canvas gesture path, the MCP tools, and the agent
 * authoring tools alike.
 *
 * **The test is about the address, not the value.** A command is a no-op only when the slot it
 * addresses already exists *and* already holds the value it carries. That distinction is load-bearing:
 * `applyKeyformSet` seeds a key on every axis that has none, so writing the current shape at an unkeyed
 * coordinate is a real edit — it is the documented `seed` idiom — even though the evaluated value is
 * unchanged. Asking "does the value match?" would silently drop it.
 *
 * Anything this cannot resolve exactly answers *changed*, and the two failure modes are not symmetric.
 * Wrongly dropping a real edit loses the change with no signal anywhere; wrongly keeping a no-op costs
 * one visible, recoverable history node. Every ambiguous branch therefore takes the safe side, and the
 * ops with no single addressed slot are listed as always-changed rather than given an invented test.
 */
internal object RigCommandDelta {

    /** Geometry is compared in the payload's own space; a difference below this is float noise. */
    private const val EPS = 1e-6f

    fun isNoOp(model: PuppetModel, command: JsonObject): Boolean {
        val op = command.getValue("op").jsonPrimitive.content
        return when (op) {
            "set" -> with(RigAuthoringJournal) { isSetNoOp(model, command) }
            "canvas_geometry" -> with(RigAuthoringJournal) { isCanvasGeometryNoOp(model, command) }
            "structure" -> isStructureNoOp(model, command)
            "path_put" -> isPathPutNoOp(model, command)
            // "copy" and "delete" rewrite or remove a slot with no single value to compare; "warp" and
            // "canvas_create_*" require an unused id; "canvas_topology" and "structure" rebuild or
            // restructure rather than overwrite one addressed slot. Each is a change whenever it is
            // valid, so they are left alone rather than given a test that could only be wrong in the
            // dangerous direction.
            else -> false
        }
    }

    private fun RigAuthoringJournal.isSetNoOp(model: PuppetModel, command: JsonObject): Boolean {
        val target = target(command.text("target"))
        val coordinate = command.coordinate("key")
        val geometry = command["geometry"]?.jsonObject ?: return true
        return isGeometryNoOp(model, target, coordinate, geometry)
    }

    private fun RigAuthoringJournal.isGeometryNoOp(
        model: PuppetModel,
        target: RigTargetRef,
        coordinate: Map<String, Float>,
        geometry: JsonObject,
    ): Boolean = when (target.kind) {
        RigTargetKind.WARP_DEFORMER -> {
            val payload = geometry.floats("controlPoints") ?: return false
            val deformer = model.deformers.firstOrNull { it.id.raw == target.id } as? Deformer.Warp ?: return false
            val cell = addressedCell(deformer.geometryGrid ?: return false, coordinate) ?: return false
            cell.form.controlPoints.approx(payload)
        }

        RigTargetKind.ART_MESH -> {
            val payload = geometry.floats("positionDeltas") ?: return false
            val drawable = model.findDrawable(target.id) ?: return false
            val cell = addressedCell(drawable.geometryGrid ?: return false, coordinate) ?: return false
            cell.form.positionDeltas.approx(payload)
        }

        RigTargetKind.ROTATION_DEFORMER -> {
            // apply discards a rotation geometry payload that is not complete, so an incomplete one is a
            // genuine no-op rather than a mis-specified edit.
            val originX = geometry.number("originX") ?: return true
            val originY = geometry.number("originY") ?: return true
            val angle = geometry.number("angle") ?: return true
            val scale = geometry.number("scale") ?: 1f
            val deformer = model.deformers.firstOrNull { it.id.raw == target.id } as? Deformer.Rotation ?: return false
            val cell = addressedCell(deformer.geometryGrid ?: return false, coordinate) ?: return false
            cell.form.originX.approx(originX) && cell.form.originY.approx(originY) &&
                cell.form.angle.approx(angle) && cell.form.scale.approx(scale)
        }

        // Parts and glues hold no standalone geometry: apply ignores this half entirely.
        RigTargetKind.PART, RigTargetKind.GLUE -> true
    }

    /**
     * The cell a `set` at [coordinate] would overwrite, or null when the write is not a plain overwrite.
     *
     * Null covers every way apply would *restructure* rather than overwrite: a missing grid, a
     * coordinate naming a parameter the grid does not key (apply seeds a whole new axis), a coordinate
     * covering only some of the grid's axes, and a coordinate whose value falls between keys (apply
     * inserts one). All of those are changes.
     */
    private fun <TForm> addressedCell(
        grid: KeyformGrid<TForm>,
        coordinate: Map<String, Float>,
    ): KeyformCell<TForm>? {
        if (coordinate.keys != grid.axes.map { it.parameterId.raw }.toSet()) return null
        return findCellAtCoordinate(grid, coordinate)
    }

    /**
     * `canvas_geometry` carries absolute points, and apply branches on the kind and on whether the
     * coordinate is empty — so the comparison branches the same way rather than comparing the evaluated
     * lattice in every case.
     */
    private fun RigAuthoringJournal.isCanvasGeometryNoOp(model: PuppetModel, command: JsonObject): Boolean {
        val kind = command.text("kind")
        val id = command.text("id")
        val points = command.getValue("points").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
        val key = command.coordinate("key")

        if (key.isEmpty()) return isEmptyKeyCanvasGeometryNoOp(model, kind, id, points, key)

        // A non-empty coordinate writes through one keyform cell, so the value being overwritten is the
        // lattice evaluated at that coordinate: apply derives its deltas from the same evaluation.
        val addressed = when (kind) {
            "mesh" -> model.findDrawable(id)?.geometryGrid
            "warp" -> (model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp)?.geometryGrid
            "rotation" -> (model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Rotation)?.geometryGrid
            else -> null
        } ?: return false
        if (addressedCell(addressed, key) == null) return false
        return evaluatedPoints(model, kind, id, key).approx(points)
    }

    /**
     * The empty-coordinate branches of `canvas_geometry`, each of which apply handles differently — two
     * of them rewrite the whole grid rather than one cell, so an equal evaluated lattice is not enough.
     */
    private fun RigAuthoringJournal.isEmptyKeyCanvasGeometryNoOp(
        model: PuppetModel,
        kind: String,
        id: String,
        points: FloatArray,
        key: Map<String, Float>,
    ): Boolean = when (kind) {
        // apply replaces the grid with a single unkeyed cell, so a *keyed* grid would be restructured
        // even if it happened to sample to the same lattice. Require the unkeyed shape.
        "warp" -> {
            val deformer = model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp ?: return false
            val grid = deformer.geometryGrid ?: return false
            grid.axes.isEmpty() && grid.cells.singleOrNull()?.form?.controlPoints?.approx(points) == true
        }

        // apply writes mesh.positions and leaves the grid alone, so this compares the rest mesh rather
        // than the pose-sampled one. A keyed mesh whose default pose is non-zero reads as changed here,
        // which is the safe direction.
        "mesh" -> model.findDrawable(id)?.mesh?.positions?.approx(points) == true

        // apply derives a pivot form from the four handle points and stores it as a single unkeyed cell,
        // so the evaluated handles round-trip: equal handles mean an equal form.
        "rotation" -> {
            val deformer = model.deformers.firstOrNull { it.id.raw == id } as? Deformer.Rotation ?: return false
            val grid = deformer.geometryGrid ?: return false
            grid.axes.isEmpty() && evaluatedPoints(model, kind, id, key).approx(points)
        }

        else -> false
    }

    private fun evaluatedPoints(model: PuppetModel, kind: String, id: String, key: Map<String, Float>): FloatArray =
        runCatching { RigGeometryTools.geometry(model, kind, id, key).points }.getOrNull() ?: FloatArray(0)

    /**
     * A `structure` command is a no-op when every edit in it is, folded against the model as it stands:
     * an edit that changes nothing leaves the model alone, so the next one still sees the same state.
     * The moment one of them *would* change something the whole command is a change, because this
     * deliberately does not reconstruct what the earlier edits produced.
     */
    private fun isStructureNoOp(model: PuppetModel, command: JsonObject): Boolean {
        val edits = command["edits"]?.jsonArray ?: return false
        if (edits.isEmpty()) return false
        for (element in edits) {
            if (!isStructureEditNoOp(model, element.jsonObject)) return false
        }
        return true
    }

    private fun isStructureEditNoOp(model: PuppetModel, edit: JsonObject): Boolean {
        val action = edit["action"]?.jsonPrimitive?.contentOrNull ?: return false
        val kind = edit["kind"]?.jsonPrimitive?.contentOrNull ?: return false
        val id = edit["id"]?.jsonPrimitive?.contentOrNull ?: return false
        return when (action) {
            "rename" -> {
                val requested = edit["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: return false
                requested == currentName(model, kind, id)
            }
            "visibility" -> {
                val requested = edit["visible"]?.jsonPrimitive?.booleanOrNull ?: return false
                requested == currentVisibility(model, kind, id)
            }
            "static" -> isStaticNoOp(model, kind, id, edit)
            "part" -> {
                val requested = edit["part_id"]?.jsonPrimitive?.contentOrNull
                val deformer = model.deformers.firstOrNull { it.id.raw == id } ?: return false
                requested == deformer.partId?.raw
            }
            // move, bind and create_warp all restructure rather than overwrite a value.
            else -> false
        }
    }

    private fun isStaticNoOp(model: PuppetModel, kind: String, id: String, edit: JsonObject): Boolean {
        val drawable = if (kind == "mesh") model.findDrawable(id) ?: return false else null
        val deformer = if (kind == "warp" || kind == "rotation") model.deformers.firstOrNull { it.id.raw == id } ?: return false else null
        var any = false

        edit["opacity"]?.jsonPrimitive?.floatOrNull?.let { value ->
            any = true
            val stored = drawable?.opacity ?: (deformer as? Deformer.Warp)?.opacity ?: (deformer as Deformer.Rotation).opacity
            if (!stored.approx(value)) return false
        }
        edit["draw_order"]?.jsonPrimitive?.floatOrNull?.let { value ->
            any = true
            if (drawable == null || !drawable.drawOrder.approx(value)) return false
        }
        edit["multiply_color"]?.jsonArray?.let { value ->
            any = true
            val stored = drawable?.multiplyColor
                ?: (deformer as? Deformer.Warp)?.multiplyColor ?: (deformer as Deformer.Rotation).multiplyColor
            if (!stored.approxColors(value)) return false
        }
        edit["screen_color"]?.jsonArray?.let { value ->
            any = true
            val stored = drawable?.screenColor
                ?: (deformer as? Deformer.Warp)?.screenColor ?: (deformer as Deformer.Rotation).screenColor
            if (!stored.approxColors(value)) return false
        }
        edit["blend_mode"]?.jsonPrimitive?.contentOrNull?.let { value ->
            any = true
            if (drawable == null || !drawable.blendMode.name.equals(value, ignoreCase = true)) return false
        }
        edit["masked_by"]?.jsonArray?.let { value ->
            any = true
            val requested = value.map { it.jsonPrimitive.content }
            if (drawable == null || drawable.maskedBy.map { it.raw } != requested) return false
        }
        edit["invert_mask"]?.jsonPrimitive?.booleanOrNull?.let { value ->
            any = true
            if (drawable == null || drawable.invertMask != value) return false
        }
        edit["culling"]?.jsonPrimitive?.booleanOrNull?.let { value ->
            any = true
            if (drawable == null || drawable.culling != value) return false
        }
        edit["selectable"]?.jsonPrimitive?.booleanOrNull?.let { value ->
            any = true
            val stored = when (kind) {
                "mesh" -> drawable?.isSelectable
                "part" -> model.parts.firstOrNull { it.id.raw == id }?.isSelectable
                else -> deformer?.isSelectable
            } ?: return false
            if (stored != value) return false
        }
        edit["quad"]?.jsonPrimitive?.booleanOrNull?.let { value ->
            any = true
            if (kind != "warp" || (deformer as? Deformer.Warp)?.isQuadTransform != value) return false
        }
        // An edit naming no property writes nothing, which is not the same as an edit that changed nothing.
        return any
    }

    private fun currentName(model: PuppetModel, kind: String, id: String): String? = when (kind) {
        "mesh" -> model.findDrawable(id)?.name
        "part" -> model.parts.firstOrNull { it.id.raw == id }?.name
        else -> model.deformers.firstOrNull { it.id.raw == id }?.name
    }

    private fun currentVisibility(model: PuppetModel, kind: String, id: String): Boolean? = when (kind) {
        "mesh" -> model.findDrawable(id)?.isVisible
        "part" -> model.parts.firstOrNull { it.id.raw == id }?.isVisible
        else -> null
    }

    private fun ColorRgb.approxColors(other: JsonArray): Boolean {
        if (other.size != 3) return false
        return red.approx(other[0].jsonPrimitive.float) &&
            green.approx(other[1].jsonPrimitive.float) &&
            blue.approx(other[2].jsonPrimitive.float)
    }

    private fun isPathPutNoOp(model: PuppetModel, command: JsonObject): Boolean {
        val id = command.getValue("id").jsonPrimitive.content
        val existing = model.deformPaths.firstOrNull { it.id == id } ?: return false
        // Both sides are built from the same key set, so encoding the stored path and comparing the JSON
        // is exact — and it is the same comparison apply makes when it replaces the path.
        return runCatching { DeformPathJournal.encode(existing) == command }.getOrDefault(false)
    }

    private fun FloatArray.approx(other: List<Float>): Boolean =
        size == other.size && indices.all { abs(this[it] - other[it]) <= EPS }

    private fun FloatArray.approx(other: FloatArray): Boolean =
        size == other.size && indices.all { abs(this[it] - other[it]) <= EPS }

    private fun Float.approx(other: Float): Boolean = abs(this - other) <= EPS
}
