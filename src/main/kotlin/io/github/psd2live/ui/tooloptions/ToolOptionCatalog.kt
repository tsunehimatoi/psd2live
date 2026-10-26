package io.github.psd2live.ui.tooloptions

import io.github.psd2live.core.SkeletonWeightBrushMode
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.BrushFalloff
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.CreatePlacementKind
import io.github.psd2live.ui.CreateRelation
import io.github.psd2live.ui.DEFORM_BRUSH_TOOLS
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.GLUE_WEIGHT_MODE_LABELS
import io.github.psd2live.ui.GlueSubTool
import io.github.psd2live.ui.GlueWeightMode
import io.github.psd2live.ui.SELECTION_TOOLS
import io.github.psd2live.ui.SkeletonEditSubTool
import io.github.psd2live.ui.SkeletonPoseSubTool
import io.github.psd2live.ui.WeightPaintMode
import io.github.psd2live.ui.state.referenceConversion

/*
 * Every tool's options, in the order the bar lays them out. Shared values are single instances, so the deform and
 * weight brushes - which share the editor's radius, hardness and strength - also share one range for each.
 */

private val wholeRange: (Float) -> ClosedFloatingPointRange<Float> = { 0f..1f }

// ─── Brushes ────────────────────────────────────────────────────────────────

internal val BRUSH_RADIUS = SliderOption("brush.radius", "editor.radius", OptionUnit.PX, { 1f..it },
    { it.radius }, { e, v -> e.radius = v }, logarithmic = true, role = OptionRole.SIZE)

/** Never 1: the deform brush's weight divides by one minus the hardness. */
internal val BRUSH_HARDNESS = SliderOption("brush.hardness", "editor.hardness", OptionUnit.PERCENT, { 0f..0.95f },
    { it.hardness }, { e, v -> e.hardness = v }, role = OptionRole.HARDNESS)

internal val BRUSH_STRENGTH = SliderOption("brush.strength", "editor.strength", OptionUnit.PERCENT, { 0.01f..1f },
    { it.strength }, { e, v -> e.strength = v }, role = OptionRole.STRENGTH)

internal val BRUSH_ANGLE = SliderOption("brush.angle", "editor.angle", OptionUnit.DEGREES, { 0f..359f },
    { it.brushAngle }, { e, v -> e.brushAngle = v.mod(360f) }, step = 15f, role = OptionRole.ANGLE)

internal val BRUSH_FALLOFF = ChoiceOption("brush.falloff", "editor.falloff", { BrushFalloff.entries }, { _, f -> tr(f.labelKey) },
    { it.brushFalloff }, { e, f -> e.brushFalloff = f }, inline = false)

internal val BRUSH_CONNECTED = ToggleOption("brush.connected", "editor.connectedOnly", { it.connectedOnly }, { e, on -> e.connectedOnly = on })

internal val TEMPORARY_WARP_DIVISIONS = ChoiceOption("tempWarp.divisions", "editor.tempWarp.divisions", { (1..4).toList() },
    { _, n -> "$n×$n" }, { it.temporaryWarpDivisions }, { e, n -> e.changeTemporaryWarpDivisions(n) })

internal val SOFT_SELECTION = ToggleOption("soft.selection", "editor.softSelection", { it.softSelection }, { e, on -> e.softSelection = on })

internal val SOFT_RADIUS = SliderOption("soft.radius", "editor.softRadius", OptionUnit.PX, { 1f..it },
    { it.softRadius }, { e, v -> e.softRadius = v }, logarithmic = true, role = OptionRole.SIZE)

internal val INFLATE_DIRECTION = ChoiceOption("brush.inflate", "editor.tool.inflate", { listOf(false, true) },
    { _, shrink -> tr(if (shrink) "editor.inflate.shrink" else "editor.inflate.grow") },
    { it.inflateInvert }, { e, shrink -> e.inflateInvert = shrink })

/** Points and lines snap through one radius, so both faces of the point tool place a vertex by one rule. */
internal val POINT_SNAP = SliderOption("point.snap", "editor.knifeSnapRadius", OptionUnit.PX, { 3f..30f },
    { it.knifeSnapRadius }, { e, v -> e.knifeSnapRadius = v }, step = 1f, integer = true)

internal val POINT_SPACING = SliderOption("point.spacing", "editor.pointSpacing", OptionUnit.PX, { 4f..200f },
    { it.pointSpacing }, { e, v -> e.pointSpacing = v }, logarithmic = true, role = OptionRole.SIZE)

internal val POINT_MIRROR = ToggleOption("point.mirror", "editor.pointMirror", { it.pointMirror }, { e, on -> e.pointMirror = on })

internal val GLUE_DISTANCE = SliderOption("glue.distance", "editor.glueDistance", OptionUnit.PX, { 0.5f..200f },
    { it.glueDistance }, { e, v -> e.glueDistance = v }, logarithmic = true, role = OptionRole.SIZE)

internal val WEIGHT_MODE = ChoiceOption("weight.mode", "editor.weightMode", { WeightPaintMode.entries }, { _, m -> tr(m.labelKey) },
    { it.weightPaintMode }, { e, m -> e.weightPaintMode = m })

// ─── Skeleton weights ───────────────────────────────────────────────────────

internal val SKELETON_WEIGHT_MODE = ChoiceOption("skeleton.weightMode", "skeleton.tool.weights", { SkeletonWeightBrushMode.entries },
    { _, m -> tr("skeleton.weights.mode.${m.name.lowercase()}") }, { it.skeletonWeightBrushMode }, { e, m -> e.skeletonWeightBrushMode = m })

internal val SKELETON_WEIGHT_RADIUS = SliderOption("skeleton.weightRadius", "editor.radius", OptionUnit.PX, { 1f..it },
    { it.skeletonWeightRadius }, { e, v -> e.skeletonWeightRadius = v }, logarithmic = true, role = OptionRole.SIZE)

internal val SKELETON_WEIGHT_STRENGTH = SliderOption("skeleton.weightStrength", "editor.strength", OptionUnit.PERCENT, { 0.01f..1f },
    { it.skeletonWeightStrength }, { e, v -> e.skeletonWeightStrength = v }, role = OptionRole.STRENGTH)

internal val SKELETON_WEIGHT_VALUE = SliderOption("skeleton.weightValue", "skeleton.weights.value", OptionUnit.PERCENT, wholeRange,
    { it.skeletonWeightReplaceValue }, { e, v -> e.skeletonWeightReplaceValue = v })

// ─── Variants: the flavour of the tool in hand - toolbar rows, and chips in the context menu ───

internal val BRUSH_TIP = ChoiceOption("variant.brushTip", "editor.brushShape", { BrushShape.entries }, { _, s -> tr(s.labelKey) },
    { it.brushShape }, { e, s -> e.brushShape = s }, variant = true, place = OptionPlace.MENU)

internal val MESH_POINT_MODE = ChoiceOption("variant.meshPoint", "editor.tool.mesh_point", { io.github.psd2live.ui.MeshPointMode.entries },
    { _, m -> tr(m.labelKey) }, { it.meshPointMode }, { e, m -> e.selectMeshPointMode(m) }, variant = true, place = OptionPlace.MENU)

internal val PAINT_SHAPE_KIND = ChoiceOption("variant.paintShape", "editor.tool.paint_shape", { io.github.psd2live.ui.PaintShape.entries },
    { _, s -> tr(s.labelKey) }, { it.paintShape }, { e, s -> e.selectPaintShape(s) }, variant = true, place = OptionPlace.MENU)

internal val GLUE_SUB = ChoiceOption("variant.glue", "editor.tool.glue", { GlueSubTool.entries },
    { _, s -> tr(io.github.psd2live.ui.GLUE_SUB_TOOL_LABELS.first { it.first == s }.second) }, { it.glueSubTool }, { e, s -> e.glueSubTool = s },
    variant = true, place = OptionPlace.MENU)

internal val SKELETON_EDIT_SUB = ChoiceOption("variant.skeletonEdit", "editor.tool.skeleton_edit", { SkeletonEditSubTool.entries },
    { _, s -> tr(s.labelKey) }, { it.skeletonEditSubTool }, { e, s -> e.skeletonEditSubTool = s }, variant = true, place = OptionPlace.MENU)

internal val SKELETON_POSE_SUB = ChoiceOption("variant.skeletonPose", "editor.tool.skeleton_pose", { SkeletonPoseSubTool.entries },
    { _, s -> tr(s.labelKey) }, { it.skeletonPoseSubTool }, { e, s -> e.skeletonPoseSubTool = s }, variant = true, place = OptionPlace.MENU)

internal val WEIGHT_KIND = ChoiceOption("variant.weightKind", "editor.weightKind", { io.github.psd2live.ui.PAINTED_GROUP_KINDS },
    { _, k -> tr("sim.group.${k.jsonName}") }, { it.weightGroupKind }, { e, k -> e.weightGroupKind = k }, variant = true, place = OptionPlace.MENU)

// ─── Paint ──────────────────────────────────────────────────────────────────

internal val PAINT_BRUSH_SIZE = SliderOption("paint.size", "editor.paint.size", OptionUnit.PX, { 1f..it },
    { it.paintSize }, { e, v -> e.paintSize = v }, logarithmic = true, role = OptionRole.SIZE)

internal val PAINT_SHAPE_SIZE = SliderOption("paint.shapeWidth", "editor.width", OptionUnit.PX, { 1f..it },
    { it.paintShapeSize }, { e, v -> e.paintShapeSize = v }, logarithmic = true, role = OptionRole.SIZE)

internal val PAINT_HARDNESS = SliderOption("paint.hardness", "editor.hardness", OptionUnit.PERCENT, wholeRange,
    { it.paintHardness }, { e, v -> e.paintHardness = v }, role = OptionRole.HARDNESS)

internal val PAINT_OPACITY = SliderOption("paint.opacity", "editor.opacity", OptionUnit.PERCENT, { 0.01f..1f },
    { it.paintOpacity }, { e, v -> e.paintOpacity = v }, role = OptionRole.OPACITY)

internal val PAINT_TOLERANCE = SliderOption("paint.tolerance", "editor.tolerance", OptionUnit.COUNT, { 0f..255f },
    { it.paintTolerance.toFloat() }, { e, v -> e.paintTolerance = v.toInt() }, step = 4f, integer = true)

internal val PAINT_FILL = ChoiceOption("paint.fill", "editor.tool.paint_shape", { listOf(false, true) },
    { _, filled -> tr(if (filled) "editor.filled" else "editor.outline") }, { it.paintShapeFilled }, { e, f -> e.paintShapeFilled = f })

// Painting assists, the selection, the gradient and the blur tip.

internal val PAINT_STABILIZER = SliderOption("paint.stabilizer", "editor.paint.stabilizer", OptionUnit.COUNT,
    { 0f..io.github.psd2live.core.PaintStabilizer.MAX_LEVEL.toFloat() }, { it.paintStabilizer.toFloat() },
    { e, v -> e.paintStabilizer = v.toInt() }, step = 1f, integer = true)

internal val PAINT_SYMMETRY = ChoiceOption("paint.symmetry", "editor.paint.symmetry", { io.github.psd2live.ui.PAINT_SYMMETRY_LABELS.map { it.first } },
    { _, m -> tr(io.github.psd2live.ui.PAINT_SYMMETRY_LABELS.first { it.first == m }.second) }, { it.paintSymmetry },
    { e, m -> e.paintSymmetry = m }, inline = false)

internal val PAINT_LOCK_ALPHA = ToggleOption("paint.lockAlpha", "editor.paint.lockAlpha", { it.paintLockAlpha }, { e, on -> e.paintLockAlpha = on })

internal val PAINT_CONTIGUOUS = ToggleOption("paint.contiguous", "editor.paint.contiguous", { it.paintContiguous }, { e, on -> e.paintContiguous = on })

internal val PAINT_SELECT_KIND = ChoiceOption("variant.paintSelect", "editor.tool.paint_select", { io.github.psd2live.ui.PaintSelectShape.entries },
    { _, s -> tr(s.labelKey) }, { it.paintSelectShape }, { e, s -> e.paintSelectShape = s }, variant = true, place = OptionPlace.MENU)

internal val PAINT_SELECT_OP = ChoiceOption("paint.selectOp", "editor.paintSelect.op", { io.github.psd2live.ui.PAINT_SELECT_OP_LABELS.map { it.first } },
    { _, op -> tr(io.github.psd2live.ui.PAINT_SELECT_OP_LABELS.first { it.first == op }.second) }, { it.paintSelectOp },
    { e, op -> e.paintSelectOp = op }, inline = false)

internal val PAINT_FEATHER = SliderOption("paint.feather", "editor.paintSelect.feather", OptionUnit.PX, { 1f..256f },
    { it.paintFeather }, { e, v -> e.paintFeather = v }, logarithmic = true)

internal val PAINT_SELECT_GROW = SliderOption("paint.selectGrow", "editor.paintSelect.grow", OptionUnit.PX, { 1f..256f },
    { it.paintSelectGrow }, { e, v -> e.paintSelectGrow = v }, logarithmic = true)

internal val PAINT_TRANSFORM_SMOOTH = ToggleOption("paint.transformSmooth", "editor.paintTransform.smooth",
    { it.paintTransformSmooth }, { e, on -> e.paintTransformSmooth = on })

internal val PAINT_GRADIENT_KIND = ChoiceOption("paint.gradientKind", "editor.paintGradient.kind", { listOf(false, true) },
    { _, radial -> tr(if (radial) "editor.paintGradient.radial" else "editor.paintGradient.linear") }, { it.paintGradientRadial },
    { e, radial -> e.paintGradientRadial = radial })

internal val PAINT_GRADIENT_END = ChoiceOption("paint.gradientEnd", "editor.paintGradient.end", { io.github.psd2live.ui.PaintGradientEnd.entries },
    { _, end -> tr(end.labelKey) }, { it.paintGradientEnd }, { e, end -> e.paintGradientEnd = end })

internal val PAINT_BLUR_STRENGTH = SliderOption("paint.blurStrength", "editor.strength", OptionUnit.PERCENT, { 0.05f..1f },
    { it.paintBlurStrength }, { e, v -> e.paintBlurStrength = v }, role = OptionRole.OPACITY)

// ─── The catalog ────────────────────────────────────────────────────────────

/** The options of the tool in hand on [editor], in bar order. Empty when the tool has nothing to set or do. */
internal fun toolOptions(editor: CanvasEditor): List<ToolOption> = buildList {
    val placement = editor.placement
    if (placement != null) {
        addPlacement(editor, placement.kind)
        return@buildList
    }
    when (editor.tool) {
        CanvasTool.CREATE_WARP, CanvasTool.CREATE_ROTATION, CanvasTool.CREATE_DEFORM_PATH -> {
            // Armed without a part to build on: the tool waits for the pick that starts its placement.
            add(NoteOption("create.wait", { tr("editor.creationSelectFirst") }, warning = { true }))
            if (editor.tool != CanvasTool.CREATE_DEFORM_PATH) add(SEQUENTIAL_CREATE)
            add(ActionOption("create.cancel", "action.cancel", { !it.busy }, { it.cancel() }, OptionIcon.CANCEL, place = OptionPlace.TOP))
            return@buildList
        }
        CanvasTool.GLUE -> {
            addGlue(editor)
            return@buildList
        }
        CanvasTool.INTERIOR -> {
            addInterior(editor)
            return@buildList
        }
        CanvasTool.FEATURE -> {
            addFeature(editor)
            return@buildList
        }
        else -> Unit
    }
    when (editor.hierarchyMode) {
        EditHierarchyMode.SELECT -> addObjectMode(editor)
        EditHierarchyMode.DEFORM, EditHierarchyMode.EDIT -> addPointModes(editor)
        EditHierarchyMode.SIMULATE -> addSimulate(editor)
        EditHierarchyMode.SKELETON -> addSkeleton(editor)
        EditHierarchyMode.PAINT -> addPaint(editor)
    }
}

/** The options of [toolOptions] the context menu shows. */
internal fun menuOptions(editor: CanvasEditor): List<ToolOption> = toolOptions(editor).filter { it.place.menu }.trimSections()

/** The options of [toolOptions] the mode bar shows at the top. */
internal fun topOptions(editor: CanvasEditor): List<ToolOption> = toolOptions(editor).filter { it.place.top }

/** The options of [toolOptions] the options bar shows. */
internal fun barOptions(editor: CanvasEditor): List<ToolOption> = toolOptions(editor).filter { it.place.bar }.trimSections()

/** The rows the context menu shows before it folds a group into a second level. */
internal const val MENU_ROW_BUDGET = 10

/** A group folds only when it saves rows worth a second level: three entries or more. */
private const val MIN_FOLDED_GROUP = 3

/**
 * The ids of the groups of [options] the context menu folds into a second level. Folding hides what it folds, so a
 * group stays open unless the menu needs the room: while every row fits [MENU_ROW_BUDGET] nothing folds; otherwise
 * the largest groups fold first (the later of two the same size), only until the rest fits; a group that
 * [SectionOption.alwaysFolds] (the create actions) folds whatever the room. A group is a submenu
 * section with the options up to the next section, or a choice too long to lay out on one row; open, it takes a
 * caption and a row per entry, folded a single row.
 */
internal fun foldedGroups(editor: CanvasEditor, options: List<ToolOption>): Set<String> {
    val groups = mutableListOf<Pair<String, Int>>()
    val always = mutableSetOf<String>()
    var rows = 0
    var i = 0
    while (i < options.size) {
        val option = options[i]
        val members = when {
            option is SectionOption && option.submenu -> options.drop(i + 1).takeWhile { it !is SectionOption }.size
            option is ChoiceOption<*> && !option.inline && !option.variant -> option.choices(editor).size
            else -> -1
        }
        if (members >= 0 && option is SectionOption && option.alwaysFolds) {
            always += option.id
            rows++
            i += 1 + members
        } else if (members >= 0) {
            rows += 1 + members
            if (members >= MIN_FOLDED_GROUP) groups += option.id to members
            i += 1 + if (option is SectionOption) members else 0
        } else {
            rows++
            i++
        }
    }
    val folded = always
    for ((id, members) in groups.withIndex().sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }
        .thenByDescending { it.index }).map { it.value }) {
        if (rows <= MENU_ROW_BUDGET) break
        folded += id
        rows -= members
    }
    return folded
}

/** Drops the section captions left with nothing under them, and a leading one in the bar. */
private fun List<ToolOption>.trimSections(): List<ToolOption> = filterIndexed { i, option ->
    option !is SectionOption || (i + 1 < size && this[i + 1] !is SectionOption)
}

/**
 * Steps the [role] value of the tool in hand - the brush keys' `[`, `]` and their Shift and Alt variants - and
 * says whether the tool had one. Sizes scale, the rest step.
 */
internal fun CanvasEditor.stepOption(role: OptionRole, up: Boolean): Boolean {
    val option = toolOptions(this).filterIsInstance<SliderOption>().firstOrNull { it.role == role } ?: return false
    option.apply(this, option.stepped(option.get(this), up))
    return true
}

/**
 * Picks choice [n] (1-based) of the mode or tool in hand - the number keys: a warp's deform levels, Simulate's group
 * kinds, Edit's vertex, edge and face; otherwise the variants the toolbar lists under the tool: the brush tips, the glue and skeleton sub-tools, the vertex group kinds and the
 * paint shapes. Says whether there was a choice [n] to pick.
 */
internal fun CanvasEditor.pickVariant(n: Int): Boolean {
    fun <T> pick(choices: List<T>, take: (T) -> Unit): Boolean = choices.getOrNull(n - 1)?.let { take(it); true } ?: false
    return when {
        deformLevelsShown() -> pick(listOf(1, 2)) { setEditLevel(it) }
        hierarchyMode == EditHierarchyMode.SIMULATE -> pick(io.github.psd2live.ui.PAINTED_GROUP_KINDS) { weightGroupKind = it }
        // The point tools take Blender's 1 2 3: vertex, edge and face.
        pointElementModes() && target()?.kind == "mesh" && (tool in SELECTION_TOOLS || tool == CanvasTool.TRANSFORM) -> pick(listOf(0, 1, 2)) { elementMode = it }
        tool in DEFORM_BRUSH_TOOLS || tool == CanvasTool.WEIGHT_PAINT -> pick(BrushShape.entries) { brushShape = it }
        tool == CanvasTool.GLUE -> pick(GlueSubTool.entries) { glueSubTool = it }
        tool == CanvasTool.SKELETON_EDIT -> pick(SkeletonEditSubTool.entries) { skeletonEditSubTool = it }
        tool == CanvasTool.SKELETON_POSE -> pick(SkeletonPoseSubTool.entries) { skeletonPoseSubTool = it }
        tool == CanvasTool.MESH_POINT -> pick(io.github.psd2live.ui.MeshPointMode.entries) { selectMeshPointMode(it) }
        tool == CanvasTool.PAINT_SHAPE -> pick(io.github.psd2live.ui.PaintShape.entries) { selectPaintShape(it) }
        tool == CanvasTool.PAINT_SELECT -> pick(io.github.psd2live.ui.PaintSelectShape.entries) { paintSelectShape = it }
        else -> false
    }
}

private val SEQUENTIAL_CREATE = ToggleOption("create.sequential", "editor.sequentialCreate", { it.sequentialCreate }, { e, on -> e.sequentialCreate = on })

private fun MutableList<ToolOption>.addPlacement(editor: CanvasEditor, kind: CreatePlacementKind) {
    if (kind == CreatePlacementKind.PATH) {
        add(ActionOption("path.finish", "editor.finishPath", { it.editable && it.draft.size >= 2 }, { it.confirmPlacement() },
            OptionIcon.CONFIRM, primary = true, place = OptionPlace.TOP))
        add(ActionOption("path.undoPoint", "editor.undoPoint", { !it.busy && it.draft.isNotEmpty() }, { it.undoDraftPoint() },
            OptionIcon.UNDO, keepsMenu = true, place = OptionPlace.TOP))
    } else {
        add(ActionOption("placement.confirm", "editor.placementConfirm", { it.editable }, { it.confirmPlacement() },
            OptionIcon.CONFIRM, primary = true, place = OptionPlace.TOP))
    }
    add(ActionOption("placement.cancel", "editor.placementCancel", { true }, { it.cancelPlacement() }, OptionIcon.CANCEL, place = OptionPlace.TOP))
    if (kind == CreatePlacementKind.WARP || kind == CreatePlacementKind.ROTATION) add(SEQUENTIAL_CREATE)
}

private fun MutableList<ToolOption>.addGlue(editor: CanvasEditor) {
    val pair = editor.glueMeshPair()
    add(GLUE_SUB)
    if (editor.glueSubTool == GlueSubTool.WEIGHT) {
        add(ChoiceOption("glue.weightSide", "editor.glueWeight", { GLUE_WEIGHT_MODE_LABELS.map { it.first } },
            { _, mode -> GLUE_WEIGHT_MODE_LABELS.first { it.first == mode }.second }, { it.glueWeightMode },
            { e, mode: GlueWeightMode -> e.glueWeightMode = mode }))
    }
    add(GLUE_DISTANCE)
    add(NoteOption("glue.pair", { e ->
        val p = e.glueMeshPair()
        if (p == null) tr("editor.glueNeedTwo", e.glueMeshCount())
        else "${e.meshLabel(p.first)} ↔ ${e.meshLabel(p.second)}"
    }, warning = { it.glueMeshPair() == null }))
    add(ActionOption("glue.swap", "editor.glueSwap", { it.glueMeshPair() != null && it.editable }, { it.swapGlueEnds() }, OptionIcon.SWAP, keepsMenu = true))
    add(ActionOption("glue.apply", if (pair != null && editor.glueAlreadyBound()) "editor.glueReplace" else "editor.glueCreate",
        { it.glueMeshPair() != null && it.editable && it.gluePreviewPoints().isNotEmpty() }, { it.applyGlue() }, OptionIcon.CONFIRM, primary = true,
        place = OptionPlace.TOP))
    add(ActionOption("glue.remergeAll", "editor.glueRemergeAll", { it.glueMeshPair() != null && it.editable }, { it.remergeGlue() },
        OptionIcon.REMERGE, place = OptionPlace.MENU))
}

/**
 * Object mode: the layer in hand's mesh and material submenus, everything that can be built on it in one Create
 * submenu, then the selection.
 */
private fun MutableList<ToolOption>.addObjectMode(editor: CanvasEditor) {
    val target = editor.selectionTarget()
    if (target?.kind == "mesh") addLayerActions(editor, editor.targetLayerId(target), target.id)
    if (target?.kind == "mesh" || target?.kind == "warp") {
        add(SectionOption("object.create", "editor.toolbar.create", OptionPlace.MENU, submenu = true, icon = OptionIcon.CREATE, alwaysFolds = true))
        if (target.kind == "mesh") {
            add(createAction("object.createWarp", "editor.createWarp", OptionIcon.WARP, CreatePlacementKind.WARP, CreateRelation.AS_PARENT))
            add(createAction("object.createRotation", "editor.createRotation", OptionIcon.ROTATION, CreatePlacementKind.ROTATION, CreateRelation.AS_PARENT))
            add(createAction("object.createPath", "editor.treeAddPath", OptionIcon.PATH, CreatePlacementKind.PATH, CreateRelation.AS_CHILD))
            add(ActionOption("object.skeleton", if (editor.committedSkeleton == null) "skeleton.tree.create" else "skeleton.tree.edit",
                { it.editable }, { if (it.committedSkeleton == null) it.createSkeleton() else it.beginSkeletonEdit() },
                OptionIcon.SKELETON, place = OptionPlace.MENU))
        }
        add(ActionOption("object.swing", "swing.menu", { it.editable }, { it.viewModel.beginSwing(it.swingTargets()) },
            OptionIcon.SWING, place = OptionPlace.MENU))
    }
    addSelectionActions(objectMode = true)
}

/**
 * What acts on layer [layerId] as a whole, each in a submenu: its meshes - made again from its pixels, split into one
 * layer per separate mesh, the front/back split of [meshId] where it covers another part - and the material it shows.
 */
private fun MutableList<ToolOption>.addLayerActions(editor: CanvasEditor, layerId: String?, meshId: String? = null) {
    if (layerId == null) return
    // A layer the generator has not read can be kept as reference: on the canvas, out of the rig and exports.
    val layout = editor.state.layout
    val conversion = editor.state.referenceConversion(layerId)
    add(ActionOption("layer.reference", conversion.labelKey, { it.editable && it.state.referenceConversion(layerId).enabled },
        { e -> e.viewModel.markLayersReference(listOf(layerId), conversion.toReference) },
        if (conversion.toReference) OptionIcon.REFERENCE else OptionIcon.MESH, place = OptionPlace.MENU))
    if (layerId in layout.referenceLayerIds) addReferencePins(editor, layerId)
    val rig = editor.state.previewModel?.rig
    val meshed = rig != null && rig.puppet.drawables.any { rig.layerIdByDrawableId[it.id.raw] == layerId && it.mesh != null }
    if (meshed) {
        add(SectionOption("layer.mesh", "editor.menu.mesh", OptionPlace.MENU, submenu = true, icon = OptionIcon.MESH, alwaysFolds = true))
        add(ActionOption("layer.meshRebuild", "mesh.rebuild", { it.editable }, { it.viewModel.rebuildLayerMesh(layerId) },
            OptionIcon.REBUILD, place = OptionPlace.MENU))
        add(ActionOption("layer.meshSplit", "canvas.hierarchy.splitMeshComponents", { it.editable }, { it.viewModel.requestMeshSplit(layerId) },
            OptionIcon.SPLIT, place = OptionPlace.MENU, opensDialog = true))
        val split = meshId?.let { id -> editor.model.drawables.firstOrNull { it.id.raw == id }?.takeIf { editor.viewModel.depthSplitMiddleIds(id).isNotEmpty() } }
        if (split != null) add(ActionOption("layer.depthSplit", "editor.depthSplit.quick", { it.editable }, { it.viewModel.requestDepthSplit(split.id.raw) },
            OptionIcon.DEPTH_SPLIT, labelArgs = { listOf(split.name) }, place = OptionPlace.MENU, opensDialog = true))
    }
    val material = editor.state.layout.layerMaterials[layerId]?.let(editor.state.layout.materials::item) ?: return
    add(SectionOption("layer.material", "editor.menu.material", OptionPlace.MENU, submenu = true, icon = OptionIcon.MATERIAL, alwaysFolds = true))
    add(ActionOption("layer.materialOpen", "materials.menu.edit", { true }, { it.viewModel.openMaterialEditor(material.id) },
        OptionIcon.MATERIAL, place = OptionPlace.MENU, opensDialog = true))
    add(ActionOption("layer.materialUnlink", "materials.link.unlink", { it.editable }, { it.viewModel.unlinkMaterials(listOf(layerId)) },
        OptionIcon.UNLINK, place = OptionPlace.MENU))
}

/**
 * A reference layer's place and keyform pin, as the hierarchy's reference menu has them: move it behind or in front of
 * the model as a reference image, then its pin as the compact parameter panel - a track or 2D pad per keyed parameter,
 * whose keyform dots each add or take away a point it shows at - and unpinning.
 */
private fun MutableList<ToolOption>.addReferencePins(editor: CanvasEditor, layerId: String) {
    val target = io.github.psd2live.ui.state.ReferenceTarget.Layer(layerId)
    add(ActionOption("layer.referenceBackground", "references.toBackground", { it.editable }, { e ->
        e.viewModel.setReferenceMode(target, io.github.psd2live.ui.state.ReferenceMode.BACKGROUND) }, OptionIcon.PIN, place = OptionPlace.MENU))
    add(ActionOption("layer.referenceForeground", "references.toForeground", { it.editable }, { e ->
        e.viewModel.setReferenceMode(target, io.github.psd2live.ui.state.ReferenceMode.FOREGROUND) }, OptionIcon.PIN, place = OptionPlace.MENU))
    val puppet = editor.state.previewModel?.rig?.puppet ?: return
    if (puppet.parameters.isEmpty()) return
    add(SectionOption("layer.pin", "layers.pin.menu", OptionPlace.MENU, submenu = true, icon = OptionIcon.PIN, alwaysFolds = true))
    add(ReferencePinsOption("layer.pins", target))
    if (target.pin(editor.state.layout).isNotEmpty()) add(ActionOption("layer.unpin", "layers.pin.clear", { it.editable }, { e ->
        e.viewModel.setReferencePin(target, emptyList())
    }, OptionIcon.CLEAR, place = OptionPlace.MENU))
}

private fun pinValue(value: Float): String =
    if (value == kotlin.math.round(value)) value.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')

/** The tree's place-then-confirm create on the mesh in hand, the same entry as the hierarchy tree's Add menu. */
private fun createAction(id: String, labelKey: String, icon: OptionIcon, kind: CreatePlacementKind, relation: CreateRelation) =
    ActionOption(id, labelKey, { it.editable }, { e ->
        e.target()?.id?.let { meshId -> e.beginTreeCreate(kind, relation, false, meshId) }
    }, icon, place = OptionPlace.MENU)

private fun MutableList<ToolOption>.addSelectionActions(objectMode: Boolean, mesh: Boolean = false) {
    add(SectionOption("selection", "editor.selectionMode", OptionPlace.MENU, submenu = true, icon = OptionIcon.SELECTION))
    add(ActionOption("selection.all", "shortcut.selectAll", { it.editable }, { it.selectAll(invert = false) }, OptionIcon.SELECT_ALL,
        place = OptionPlace.MENU))
    add(ActionOption("selection.invert", "help.shortcuts.invertSelection", { it.editable }, { it.selectAll(invert = true) }, OptionIcon.INVERT,
        place = OptionPlace.MENU))
    if (!objectMode) {
        add(ActionOption("selection.linked", "shortcut.selectLinked", { it.editable && it.vertices.isNotEmpty() }, { it.selectLinked() },
            OptionIcon.LINKED, place = OptionPlace.MENU))
    }
    if (mesh) {
        add(ActionOption("selection.outline", "editor.selectOutline", { it.editable }, { it.selectOutline() }, OptionIcon.SELECTION,
            place = OptionPlace.MENU))
        add(ActionOption("selection.inner", "editor.selectInner", { it.editable }, { it.selectOutline(inner = true) }, OptionIcon.SELECTION,
            place = OptionPlace.MENU))
    }
    add(ActionOption("selection.none", "editor.deselect",
        { if (objectMode) it.objects.isNotEmpty() else it.vertices.isNotEmpty() },
        { e ->
            if (objectMode) e.objects = emptySet()
            else { e.vertices = emptySet(); e.selectedEdges = emptySet(); e.selectedFaces = emptySet() }
        }, OptionIcon.DESELECT, place = OptionPlace.MENU))
}

/** Deform and Edit: the pose a deformation keys, the brush or edit tool in hand, then topology and the selection. */
private fun MutableList<ToolOption>.addPointModes(editor: CanvasEditor) {
    val editing = editor.hierarchyMode == EditHierarchyMode.EDIT
    val target = editor.selectionTarget()
    val mesh = target?.kind == "mesh"
    if (mesh && (editor.tool in SELECTION_TOOLS || editor.tool == CanvasTool.TRANSFORM)) add(ELEMENT_MODE)
    when (editor.tool) {
        CanvasTool.BRUSH_SELECT -> add(BRUSH_RADIUS)
        CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE -> {
            add(BRUSH_TIP)
            add(BRUSH_RADIUS)
            add(BRUSH_STRENGTH)
            add(BRUSH_HARDNESS)
            if (editor.brushShape != BrushShape.CIRCLE) add(BRUSH_ANGLE)
            add(BRUSH_FALLOFF)
            add(BRUSH_CONNECTED)
            if (editor.tool == CanvasTool.INFLATE) add(INFLATE_DIRECTION)
        }
        CanvasTool.SUBDIVIDE -> {
            add(BRUSH_RADIUS)
            add(ActionOption("subdivide.run", "editor.subdivide", { it.editable && it.vertices.isNotEmpty() }, { it.topology("subdivide") },
                OptionIcon.SUBDIVIDE, primary = true))
        }
        CanvasTool.MESH_POINT -> if (editor.meshPointMode == io.github.psd2live.ui.MeshPointMode.POINT) {
            add(MESH_POINT_MODE)
            add(POINT_SPACING)
            add(POINT_SNAP)
            add(POINT_MIRROR)
        } else {
            add(MESH_POINT_MODE)
            add(POINT_SNAP)
            add(ActionOption("knife.finish", "editor.finishCut", { it.editable && it.knifeDraft.size >= 2 }, { it.finishKnife() },
                OptionIcon.CONFIRM, primary = true, place = OptionPlace.TOP))
            add(ActionOption("knife.undoPoint", "editor.undoPoint", { !it.busy && it.knifeDraft.isNotEmpty() }, { it.undoDraftPoint() },
                OptionIcon.UNDO, keepsMenu = true, place = OptionPlace.TOP))
            add(ActionOption("knife.cancel", "action.cancel", { !it.busy && it.knifeDraft.isNotEmpty() }, { it.cancel() }, OptionIcon.CANCEL, place = OptionPlace.TOP))
        }
        else -> Unit
    }
    // Deform's soft selection: the points around the picked ones follow a drag of them.
    if (!editing && target?.kind != "rotation" && (editor.tool in SELECTION_TOOLS || editor.tool == CanvasTool.TRANSFORM)) {
        add(SOFT_SELECTION)
        if (editor.softSelection) {
            add(SOFT_RADIUS)
            add(BRUSH_FALLOFF)
        }
    }
    if (editing && mesh && !editor.knifing) {
        add(SectionOption("topology", "editor.topology", OptionPlace.MENU, submenu = true, icon = OptionIcon.TOPOLOGY))
        for (action in TOPOLOGY_ACTIONS) {
            if (editor.tool == CanvasTool.SUBDIVIDE && action.first == "subdivide") continue
            add(ActionOption("topology.${action.first}", "editor.${action.first}", { it.editable && it.vertices.isNotEmpty() },
                { it.topology(action.first) }, action.second, danger = action.first == "delete", place = OptionPlace.MENU))
        }
        add(ActionOption("topology.delete_edges", "editor.delete_edges", { it.editable && it.elementMode == 1 && it.selectedEdges.isNotEmpty() },
            { it.topology("delete_edges") }, OptionIcon.DELETE, danger = true, place = OptionPlace.MENU))
        // No selection re-triangulates the whole mesh, so this one is on whenever the mesh can be edited.
        add(ActionOption("topology.retriangulate", "editor.retriangulate", { it.editable }, { it.topology("retriangulate") },
            OptionIcon.TOPOLOGY, place = OptionPlace.MENU))
        // The whole mesh, made again from the layer's pixels, beside the edits to its points.
        addLayerActions(editor, editor.targetLayerId(target), target?.id)
    }
    if (!editing && editor.activeTemporaryWarp() != null) {
        add(TEMPORARY_WARP_DIVISIONS)
        add(ActionOption("tempWarp.end", "editor.tempWarp.end", { true }, { it.endTemporaryWarp() }, OptionIcon.CONFIRM, primary = true, place = OptionPlace.TOP))
    } else if (!editing && editor.canBeginTemporaryWarp()) {
        add(ActionOption("tempWarp.begin", "editor.tempWarp.begin", { it.editable }, { it.beginTemporaryWarp() }, OptionIcon.WARP, place = OptionPlace.MENU))
    }
    if (!editing && target != null) {
        addKeyformTools(editor)
        addKeyformMirror()
    }
    if (editor.tool in SELECTION_TOOLS || editor.tool == CanvasTool.TRANSFORM || (editor.tool == CanvasTool.MESH_POINT && !editor.knifing))
        addSelectionActions(objectMode = false, mesh = mesh)
}

/**
 * Deform: the keyform mirrors, in one submenu - the key in hand onto its opposite key and back, onto the object on
 * the other side, flipped in place, and every key at once. Each row names where it writes and is off when it has
 * nothing to mirror ([CanvasEditor.keyformMirrorPlan]).
 */
private fun MutableList<ToolOption>.addKeyformMirror() {
    add(SectionOption("mirror", "editor.mirror.menu", OptionPlace.MENU, submenu = true, icon = OptionIcon.MIRROR, alwaysFolds = true))
    for ((action, labelKey) in listOf(
        CanvasEditor.KeyformMirrorAction.TO_OPPOSITE_KEY to "editor.mirror.toOppositeKey",
        CanvasEditor.KeyformMirrorAction.FROM_OPPOSITE_KEY to "editor.mirror.fromOppositeKey",
        CanvasEditor.KeyformMirrorAction.TO_OPPOSITE_OBJECT to "editor.mirror.toOppositeObject",
        CanvasEditor.KeyformMirrorAction.IN_PLACE to "editor.mirror.inPlace",
        CanvasEditor.KeyformMirrorAction.ALL_KEYS to "editor.mirror.allKeys",
        CanvasEditor.KeyformMirrorAction.ALL_KEYS_TO_OBJECT to "editor.mirror.allKeysToObject",
    )) add(ActionOption("mirror.${action.name.lowercase()}", labelKey, { it.editable && it.keyformMirrorPlan(action) != null },
        { it.mirrorKeyform(action) }, OptionIcon.MIRROR, labelArgs = { e -> listOf(e.keyformMirrorPlan(action)?.label ?: "…") },
        place = OptionPlace.MENU))
}

/**
 * Deform: the keyform tools, in one submenu - copy the shapes in hand, paste the copied ones onto the keys in hand
 * (or with options: what to carry, a blend ratio, a flip and which shape goes where), reset the key to its default-key
 * shape (a mesh also to its rest), interpolate it from the keys on either side along each parameter it sits between
 * keys of, restore every key of the object, and edit the opacity, draw order and colours of many keyforms at once.
 * With points picked, the shape rows keep to them, as the rows say.
 */
private fun MutableList<ToolOption>.addKeyformTools(editor: CanvasEditor) {
    add(SectionOption("keyform", "editor.keyform.menu", OptionPlace.MENU, submenu = true, icon = OptionIcon.KEYFORM, alwaysFolds = true))
    fun scope(e: CanvasEditor): String = e.keyformPointCount().let { if (it > 0) tr("editor.keyform.points", it) else "" }
    // Why the shape rows are off: nothing bound yet, or the pose between keys.
    add(NoteOption("keyform.offKey", { it.keyformOffKeyNote() }, warning = { true }, place = OptionPlace.MENU))
    fun tool(id: String, labelKey: String, icon: OptionIcon, action: CanvasEditor.KeyformToolAction, parameter: String? = null,
             args: (CanvasEditor) -> List<Any> = { listOf(scope(it)) }) =
        ActionOption("keyform.$id", labelKey, { it.editable && it.keyformToolCommands(action, parameter).isNotEmpty() },
            { it.runKeyformTool(action, parameter) }, icon, labelArgs = args, place = OptionPlace.MENU)
    add(ActionOption("keyform.copy", "editor.keyform.copy", { it.canCopyKeyform() }, { it.copyKeyform() }, OptionIcon.DUPLICATE, place = OptionPlace.MENU))
    add(tool("paste", "editor.keyform.paste", OptionIcon.KEYFORM_PASTE, CanvasEditor.KeyformToolAction.PASTE) { e ->
        listOf(e.keyformClipboardLabel(), scope(e))
    })
    add(ActionOption("keyform.pasteOptions", "editor.keyform.pasteOptions", { it.editable && it.keyformClipboard.isNotEmpty() },
        { e -> io.github.psd2live.ui.components.KeyformDialogs.request = io.github.psd2live.ui.components.KeyformDialogs.Request.Paste(e) },
        OptionIcon.KEYFORM_PASTE, place = OptionPlace.MENU, opensDialog = true))
    add(tool("resetDefault", "editor.keyform.resetDefault", OptionIcon.RESET, CanvasEditor.KeyformToolAction.RESET_DEFAULT))
    if (editor.selectionTarget()?.kind == "mesh") add(tool("resetRest", "editor.keyform.resetRest", OptionIcon.RESET, CanvasEditor.KeyformToolAction.RESET_REST))
    for (parameter in editor.keyformInterpolationAxes()) add(tool("interpolate.$parameter", "editor.keyform.interpolate", OptionIcon.INTERPOLATE,
        CanvasEditor.KeyformToolAction.INTERPOLATE, parameter) { e -> listOf(e.parameterLabel(parameter), scope(e)) })
    add(tool("restore", "editor.keyform.restore", OptionIcon.RESET, CanvasEditor.KeyformToolAction.RESTORE) { emptyList() })
    add(ActionOption("keyform.multiKey", "editor.keyform.multiKey", { it.editable && it.multiKeyTargets().isNotEmpty() },
        { e -> io.github.psd2live.ui.components.KeyformDialogs.request = io.github.psd2live.ui.components.KeyformDialogs.Request.MultiKey(e) },
        OptionIcon.KEYFORM, place = OptionPlace.MENU, opensDialog = true))
}

private val TOPOLOGY_ACTIONS = listOf(
    "split" to OptionIcon.SPLIT, "subdivide" to OptionIcon.SUBDIVIDE, "connect" to OptionIcon.CONNECT,
    "merge" to OptionIcon.MERGE, "delete" to OptionIcon.DELETE, "duplicate" to OptionIcon.DUPLICATE,
)

internal val ELEMENT_MODE = ChoiceOption("edit.element", "editor.elementMode", { listOf(0, 1, 2) },
    { _, i -> tr("editor.${listOf("vertex", "edge", "face")[i]}") }, { it.elementMode }, { e, i -> e.elementMode = i }, place = OptionPlace.TOP,
    captioned = false)

private fun MutableList<ToolOption>.addSimulate(editor: CanvasEditor) {
    add(WEIGHT_KIND)
    when (editor.tool) {
        CanvasTool.WEIGHT_PAINT -> {
            add(BRUSH_TIP)
            add(WEIGHT_MODE)
            add(BRUSH_RADIUS)
            add(BRUSH_STRENGTH)
            add(BRUSH_HARDNESS)
            if (editor.brushShape != BrushShape.CIRCLE) add(BRUSH_ANGLE)
        }
        CanvasTool.WEIGHT_GRADIENT -> {
            add(WEIGHT_MODE)
            add(BRUSH_STRENGTH)
        }
        else -> Unit
    }
    add(SectionOption("weight.group", "editor.weightGroup", OptionPlace.MENU, submenu = true, icon = OptionIcon.GROUP))
    add(ActionOption("weight.fill", "editor.weightFill", { it.editable }, { it.fillVertexGroup(1f) }, OptionIcon.FILL, place = OptionPlace.MENU))
    add(ActionOption("weight.clear", "editor.weightClear", { it.editable }, { it.fillVertexGroup(0f) }, OptionIcon.ERASE, place = OptionPlace.MENU))
    add(ActionOption("weight.invert", "editor.weightInvert", { it.editable }, { it.invertVertexGroup() }, OptionIcon.INVERT, place = OptionPlace.MENU))
    add(ActionOption("weight.delete", "editor.weightDelete", { it.editable }, { it.deleteVertexGroup() }, OptionIcon.DELETE,
        danger = true, place = OptionPlace.MENU))
}

private fun MutableList<ToolOption>.addSkeleton(editor: CanvasEditor) {
    when (editor.tool) {
        CanvasTool.SKELETON_POSE -> {
            add(SKELETON_POSE_SUB)
            add(ActionOption("skeleton.resetPose", "animation.resetPose", { it.bakedSkeleton != null }, { it.resetSkeletonPose() },
                OptionIcon.RESET, place = OptionPlace.MENU))
        }
        CanvasTool.SKELETON_EDIT -> {
            add(SKELETON_EDIT_SUB)
            if (editor.skeletonEditSubTool == SkeletonEditSubTool.WEIGHTS) {
                add(SKELETON_WEIGHT_MODE)
                add(SKELETON_WEIGHT_RADIUS)
                add(SKELETON_WEIGHT_STRENGTH)
                if (editor.skeletonWeightBrushMode == SkeletonWeightBrushMode.REPLACE) add(SKELETON_WEIGHT_VALUE)
            }
            add(NoteOption("skeleton.hint", { tr(it.skeletonEditSubTool.hintKey) }, place = OptionPlace.BAR))
            add(ActionOption("skeleton.cancel", "skeleton.panel.cancel", { it.skeletonDraft != null }, { it.cancelSkeletonEdit() },
                OptionIcon.CANCEL, place = OptionPlace.MENU))
            add(ActionOption("skeleton.done", "skeleton.panel.done", { it.skeletonDraft != null }, { it.finishSkeletonEdit() },
                OptionIcon.CONFIRM, primary = true, place = OptionPlace.MENU))
        }
        else -> Unit
    }
}

/** What every stroke tool offers past its tip: the stabilizer, the mirror and - for the colour tips - lock transparency. */
private fun MutableList<ToolOption>.addStrokeAssists(lockAlpha: Boolean) {
    add(PAINT_STABILIZER)
    add(PAINT_SYMMETRY)
    if (lockAlpha) add(PAINT_LOCK_ALPHA)
}

private fun MutableList<ToolOption>.addPaint(editor: CanvasEditor) {
    // Paint mode cannot start without a layer, so until one is picked that is all the bar has to say.
    add(NoteOption("paint.layer", { if (it.paintSession == null) tr("editor.paintSelectLayerHint") else null }, warning = { true }))
    // A selection softer than half everywhere still confines the tools, though it has no edge to show.
    add(NoteOption("paint.faintSelection", { e -> e.paintSession?.takeIf { it.hasSelection && it.selectionOutline.isEmpty() }
        ?.let { tr("editor.paintSelect.faint") } }, warning = { true }))
    when (editor.tool) {
        CanvasTool.PAINT_BRUSH, CanvasTool.PAINT_ERASER -> {
            add(PAINT_BRUSH_SIZE)
            add(PAINT_HARDNESS)
            add(PAINT_OPACITY)
            addStrokeAssists(lockAlpha = true)
        }
        CanvasTool.PAINT_PENCIL -> {
            add(PAINT_BRUSH_SIZE)
            add(PAINT_OPACITY)
            addStrokeAssists(lockAlpha = true)
        }
        CanvasTool.PAINT_BLUR -> {
            add(PAINT_BRUSH_SIZE)
            add(PAINT_HARDNESS)
            add(PAINT_BLUR_STRENGTH)
            addStrokeAssists(lockAlpha = false)
        }
        CanvasTool.PAINT_BUCKET -> {
            add(PAINT_TOLERANCE)
            add(PAINT_CONTIGUOUS)
            add(PAINT_LOCK_ALPHA)
        }
        CanvasTool.PAINT_GRADIENT -> {
            add(PAINT_GRADIENT_KIND)
            add(PAINT_GRADIENT_END)
            add(PAINT_OPACITY)
            add(PAINT_LOCK_ALPHA)
        }
        CanvasTool.PAINT_SHAPE -> {
            add(PAINT_SHAPE_KIND)
            add(PAINT_SHAPE_SIZE)
            add(PAINT_OPACITY)
            if (editor.paintShape.canFill) add(PAINT_FILL)
            add(PAINT_LOCK_ALPHA)
        }
        CanvasTool.PAINT_TRANSFORM -> {
            add(NoteOption("paint.transformHint", { e -> tr(if (e.paintTransform == null) "editor.paintTransform.start" else "editor.paintTransform.hint") }))
            add(PAINT_TRANSFORM_SMOOTH)
            add(ActionOption("paint.transformApply", "editor.paintTransform.apply", { it.paintTransform != null }, { it.applyPaintTransform() },
                OptionIcon.CONFIRM, primary = true, place = OptionPlace.TOP))
            add(ActionOption("paint.transformCancel", "action.cancel", { it.paintTransform != null }, { it.cancelPaintTransform() },
                OptionIcon.CANCEL, place = OptionPlace.TOP))
        }
        CanvasTool.PAINT_SELECT -> {
            add(PAINT_SELECT_KIND)
            add(PAINT_SELECT_OP)
            if (editor.paintSelectShape == io.github.psd2live.ui.PaintSelectShape.WAND) {
                add(PAINT_TOLERANCE)
                add(PAINT_CONTIGUOUS)
            }
            if (editor.paintSelectShape == io.github.psd2live.ui.PaintSelectShape.QUICK) {
                add(PAINT_BRUSH_SIZE)
                add(PAINT_TOLERANCE)
            }
            add(PAINT_FEATHER)
            add(PAINT_SELECT_GROW)
        }
        else -> Unit
    }
    // The selection's commands, as Photoshop's Select menu has them; every tool lands through the selection.
    add(SectionOption("paint.selection", "editor.paintSelect.section", OptionPlace.MENU))
    val session: (CanvasEditor) -> Boolean = { it.paintSession != null }
    add(ActionOption("paint.selectAll", "shortcut.selectAll", session, { it.selectAllPaint() }, OptionIcon.SELECT_ALL, place = OptionPlace.MENU))
    add(ActionOption("paint.deselect", "editor.paintSelect.deselect", { it.paintHasSelection }, { it.deselectPaint() }, OptionIcon.DESELECT,
        place = OptionPlace.MENU))
    add(ActionOption("paint.invert", "editor.paintSelect.invert", { it.paintHasSelection }, { it.invertPaintSelection() }, OptionIcon.INVERT,
        place = OptionPlace.MENU))
    add(ActionOption("paint.featherSelection", "editor.paintSelect.featherAction", { it.paintHasSelection }, { it.featherPaintSelection() },
        labelArgs = { listOf(it.paintFeather.toInt()) }, place = OptionPlace.MENU))
    add(ActionOption("paint.expandSelection", "editor.paintSelect.expandAction", { it.paintHasSelection }, { it.growPaintSelection() },
        labelArgs = { listOf(it.paintSelectGrow.toInt()) }, place = OptionPlace.MENU))
    add(ActionOption("paint.contractSelection", "editor.paintSelect.contractAction", { it.paintHasSelection },
        { it.growPaintSelection(contract = true) }, labelArgs = { listOf(it.paintSelectGrow.toInt()) }, place = OptionPlace.MENU))
    add(ActionOption("paint.selectOpaque", "editor.paintSelect.opaque", session, { it.selectOpaquePaint() }, place = OptionPlace.MENU))
    add(ActionOption("paint.fillSelection", "editor.paintSelect.fill", session, { it.fillPaintSelection() }, OptionIcon.FILL, place = OptionPlace.MENU))
    add(ActionOption("paint.deleteSelection", "editor.paintSelect.delete", { it.paintHasSelection }, { it.deletePaintSelection() },
        OptionIcon.ERASE, danger = true, place = OptionPlace.MENU))
    add(SectionOption("paint.layerActions", "editor.paintTargetLayer", OptionPlace.MENU))
    add(ActionOption("paint.clear", "editor.paintClear", { it.paintSession != null }, { it.clearCurrentLayerPaint() }, OptionIcon.CLEAR,
        danger = true, place = OptionPlace.MENU))
    // A mesh rebuilt from the pixels just painted, or the material they reach.
    addLayerActions(editor, editor.paintSession?.layerId?.takeUnless { it.startsWith(io.github.psd2live.application.WorkspacePaintSession.MATERIAL_PREFIX) })
}

// ─── Interior ───────────────────────────────────────────────────────────────

/** The interior tool: what is missing to edit, the line being drawn, and the selected guide's purpose. */
/** The feature tool: what to drag, and putting the last feature handled - or every one - back to the art's fit. */
private fun MutableList<ToolOption>.addFeature(editor: CanvasEditor) {
    val tool = editor.featureTool
    add(NoteOption("feature.hint", { tool.hint() }, warning = { tool.handles.isEmpty() }, place = OptionPlace.BAR))
    add(ActionOption("feature.reset", "editor.tool.feature.reset", { e -> e.editable && tool.last?.let { e.state.recipe.moves(it).isNotEmpty() } == true },
        { e -> tool.last?.let { e.viewModel.resetFeatureMoves(listOf(it)) } }, OptionIcon.RESET))
    add(ActionOption("feature.resetAll", "editor.tool.feature.resetAll", { e -> e.editable && e.state.recipe.moves.isNotEmpty() },
        { e -> e.viewModel.resetFeatureMoves(e.state.recipe.moves.keys) }, OptionIcon.RESET, danger = true))
}

private fun MutableList<ToolOption>.addInterior(editor: CanvasEditor) {
    val tool = editor.interiorTool
    add(NoteOption("interior.missing", { tool.missing() }, warning = { true }, place = OptionPlace.BAR))
    add(ActionOption("interior.enable", "interior.canvas.enable", { it.editable && tool.canEnable() },
        { tool.enable() }, OptionIcon.CREATE, primary = true))
    add(ActionOption("interior.finishLine", "interior.canvas.finishLine", { it.editable && tool.drawing.size >= 2 }, { tool.finish() },
        OptionIcon.CONFIRM, primary = true, place = OptionPlace.TOP))
    add(ActionOption("interior.undoPoint", "editor.undoPoint", { tool.drawing.isNotEmpty() }, { tool.deleteSelection() },
        OptionIcon.UNDO, keepsMenu = true, place = OptionPlace.TOP))
    add(ActionOption("interior.cancelLine", "action.cancel", { tool.drawing.isNotEmpty() }, { it.cancelInterior() }, OptionIcon.CANCEL,
        place = OptionPlace.TOP))
    if (tool.selected != null) {
        add(ToggleOption("interior.fold", "interior.markFold", { tool.selectedIsFold() }, { e, on -> if (e.editable) tool.markFold(on) }))
        add(ActionOption("interior.deleteGuide", "interior.canvas.delete", { it.editable }, { tool.deleteSelection() }, OptionIcon.DELETE,
            danger = true))
    }
}
