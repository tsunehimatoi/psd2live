package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.*
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterNode

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ModifierStackList(stack: RigModifierStack, reports: Map<String, JsonObject>, vm: PSD2LiveViewModel,
    ordering: Boolean, enabled: Boolean, selectedId: String?, onSelect: (String) -> Unit) {
    val colors = LocalToolColors.current
    val nodes = remember(stack) { RigModifierOrganization.nodes(stack) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { mutableStateMapOf<String, ParamItemLayout>() }
    val drag = remember { ParameterDragState() }
    val preview = rememberGraphicsLayer()
    val scroll = rememberScrollState()
    var container by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var expected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stack, enabled, ordering) { drag.clear() }
    fun organize(next: List<ModifierPanelNode>, state: String? = vm.modifierEditState()) {
        if (enabled && next != nodes) vm.editModifier("modifier_organize", buildJsonObject { put("nodes", RigModifierOrganization.encode(next)) }, state)
    }
    fun folderName(node: ModifierPanelNode): String = requireNotNull(node.name).let { if (it.startsWith("@")) tr(it.drop(1)) else it }
    fun newFolder(parent: String?) {
        var index = 1
        while (nodes.any { it.id == "folder/user-$index" }) index++
        val id = "folder/user-$index"
        organize(nodes + ModifierPanelNode(id, parent, tr("parameters.newFolderName")))
        collapsed[parent.orEmpty()] = false
        renaming = id; renameDraft = tr("parameters.newFolderName")
    }
    fun startDrag(layout: ParamItemLayout, position: Offset, row: LayoutCoordinates) {
        val parent = container ?: return
        if (!enabled || !parent.isAttached || !row.isAttached) return
        val measured = bounds[layout.key] ?: return
        expected = vm.modifierEditState()
        drag.onPress(measured, parent.localPositionOf(row, position))
    }
    val visible = mutableListOf<Pair<ModifierPanelNode, Int>>()
    if (ordering) stack.instances.forEach { visible += ModifierPanelNode(it.id) to 0 }
    else {
        val visited = hashSetOf<String>()
        fun visit(parent: String?, depth: Int) {
            nodes.filter { it.parent == parent }.forEach { node ->
                if (visited.add(node.id)) {
                    visible += node to depth
                    if (node.name != null && collapsed[node.id] != true) visit(node.id, depth + 1)
                }
            }
        }
        visit(null, 0)
    }
    Column(Modifier.fillMaxSize()) {
        if (!ordering) PanelToolbar(labels = listOf(tr("parameters.newFolderShort")), iconCount = 1) { labels ->
            PanelToolButton(tr("parameters.newFolderShort"), labels > 0, { newFolder(null) }, enabled, tr("parameters.newFolder")) { IconFolder() }
        }
        if (stack.instances.isEmpty()) Text(tr("modifier.empty"), color = colors.textMuted, style = LocalToolTypography.current.caption,
            modifier = Modifier.padding(Spacing.Margin))
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { container = it }
            .onPointerEvent(PointerEventType.Move) { event -> event.changes.firstOrNull()?.let { drag.onMove(it.position, bounds.values) } }
            .onPointerEvent(PointerEventType.Release) { event ->
                if (event.button == PointerButton.Primary && drag.isPressed) drag.onRelease { item, target ->
                    if (ordering) {
                        val remaining = stack.instances.filterNot { it.id == item.id }
                        val index = when (target) {
                            is ParamDropTarget.Before -> remaining.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }
                            is ParamDropTarget.Append, ParamDropTarget.Root -> remaining.size
                            else -> null
                        }
                        if (enabled && index != null && index != stack.index(item.id)) vm.editModifier("modifier_move", buildJsonObject {
                            put("id", item.id); put("index", index)
                        }, expected)
                    } else {
                        val parent = when (target) {
                            is ParamDropTarget.Nest -> target.folderId
                            is ParamDropTarget.Before -> target.parentGroupId
                            is ParamDropTarget.Append -> target.parentGroupId
                            ParamDropTarget.Root -> null
                        }
                        organize(RigModifierOrganization.move(nodes, item.id, parent, (target as? ParamDropTarget.Before)?.id), expected)
                    }
                }
            }) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = Spacing.Margin)) {
                for ((node, depth) in visible) key(node.id) {
                    val instance = stack.instances.firstOrNull { it.id == node.id }
                    val name = if (node.name != null) folderName(node) else instance?.config?.get("name")?.jsonPrimitive?.contentOrNull ?: modifierName(requireNotNull(instance).type)
                    val descendants = if (node.name == null) emptySet() else RigModifierOrganization.descendants(nodes, node.id)
                    val layout = ParamItemLayout(node.id, node.id, if (node.name != null) "folder" else "modifier", name, node.parent, node.name != null, descendants, 0f, 0f)
                    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    DisposableEffect(node.id, ordering) { onDispose { bounds.remove(node.id) } }
                    Box(Modifier.fillMaxWidth().parameterDragRow(layout, drag, bounds.values, preview)
                        .onPointerEvent(PointerEventType.Press) { event ->
                            if (event.button == PointerButton.Primary && !drag.isPressed) drag.suppressClick = false
                        }
                        .onGloballyPositioned { row ->
                            rowCoords = row
                            val parent = container
                            if (parent != null && parent.isAttached && row.isAttached) {
                                val top = parent.localPositionOf(row, Offset.Zero).y
                                bounds[node.id] = layout.copy(top = top, bottom = top + row.size.height)
                            }
                        }) {
                        if (node.name != null) ParameterFolderRow(
                            ParameterPanelRow.Folder(ParameterNode.Group(ParameterGroupId(node.id), name, true, emptyList()), depth,
                                collapsed[node.id] != true, node.parent, descendants),
                            renaming = renaming == node.id, renameDraft = renameDraft, onRenameDraft = { renameDraft = it },
                            onStartRename = { if (enabled) { renaming = node.id; renameDraft = name } },
                            onCommitRename = {
                                if (renaming == node.id) { val value = renameDraft.trim(); renaming = null
                                    if (value.isNotEmpty()) organize(nodes.map { if (it.id == node.id) it.copy(name = value) else it }) }
                            }, onCancelRename = { renaming = null },
                            onToggle = { if (drag.suppressClick) drag.suppressClick = false else collapsed[node.id] = collapsed[node.id] != true },
                            onDelete = { organize(nodes.filterNot { it.id == node.id }.map { if (it.parent == node.id) it.copy(parent = node.parent) else it }) },
                            onNewChildParameter = {}, onNewChildFolder = { newFolder(node.id) }, onLabelColor = {}, canCreateParameter = false,
                            menuOpen = menu == node.id, menuOffset = menuOffset,
                            onMenuOpenChange = { open, offset -> menu = if (open && enabled) node.id else null; offset?.let { menuOffset = it } },
                            onDragPress = { position, row -> startDrag(layout, position, row) }, showParameterTools = false)
                        else if (instance != null) {
                            val interaction = remember { MutableInteractionSource() }
                            val hovered by interaction.collectIsHoveredAsState()
                            val status = reports[instance.id]?.get("status")?.jsonPrimitive?.content ?: "loading"
                            Row(Modifier.fillMaxWidth().height(24.dp)
                                .background(when { selectedId == instance.id -> colors.selection; hovered && !drag.isDragging -> colors.controlHover; else -> Color.Transparent })
                                .hoverable(interaction).clickable(interactionSource = interaction, indication = null) {
                                    if (drag.suppressClick) drag.suppressClick = false else onSelect(instance.id)
                                }.padding(start = (4 + depth * 8).dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                CompactCheckbox(instance.enabled, { value -> vm.editModifier("modifier_enable", buildJsonObject {
                                    put("id", instance.id); put("enabled", value)
                                }) }, enabled = enabled)
                                Spacer(Modifier.width(Spacing.Gap))
                                Text(name, style = LocalToolTypography.current.body.copy(fontSize = 11.sp),
                                    color = if (instance.enabled) colors.textPrimary else colors.textMuted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (status != "active") Text(tr("modifier.status.$status"), style = LocalToolTypography.current.caption,
                                    color = if (status == "missing_dependency" || status == "missing_plugin") colors.warning else colors.textMuted)
                                ParameterDragHandle({ position, row -> startDrag(layout, position, row) }, rowCoords)
                                CompactIconButton({ vm.editModifier("modifier_remove", buildJsonObject { put("id", instance.id) }) },
                                    enabled = enabled, tooltip = tr("modifier.remove"), size = 20.dp) { IconClose() }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
            ParameterDragPreview(drag, preview)
        }
    }
}
