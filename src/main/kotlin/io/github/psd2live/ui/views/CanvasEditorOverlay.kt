package io.github.psd2live.ui.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.DeformPathTools
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.*
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.CanvasEditorOverlay(editor: CanvasEditor, viewport: CanvasViewport, viewModel: PSD2LiveViewModel, focus: ()->Unit) {
    val colors=LocalToolColors.current
    val target=editor.target()
    val pathTool=editor.tool in listOf(CanvasTool.PATH,CanvasTool.PATH_DEFORM)
    Canvas(Modifier.fillMaxSize()) {
        if(editor.objectMode) editor.objects.forEach { id -> editor.target(editor.model,id,null)?.let { item ->
            val points=editor.screen(item.geometry.points,item,viewport)
            if(points.isNotEmpty()) {
                val origin=Offset(points.minOf { it.x },points.minOf { it.y })
                drawRect(colors.accent,origin,Size((points.maxOf { it.x }-origin.x).coerceAtLeast(1f),(points.maxOf { it.y }-origin.y).coerceAtLeast(1f)),style=Stroke(1.2f))
            }
        } }
        if(target!=null && editor.tool!=CanvasTool.SELECT && editor.tool!=CanvasTool.HAND) {
            val pts=editor.screen(target.geometry.points,target,viewport)
            if(!pathTool) {
                val edges=if(target.kind=="mesh") org.umamo.edit.MeshTopology.uniqueEdges(target.indices).map { it.endpointLow to it.endpointHigh } else if(target.kind=="rotation")listOf(0 to 1) else {
                    val columns=target.geometry.columns!!+1
                    pts.indices.flatMap { i -> listOfNotNull(if(i%columns<columns-1)i to i+1 else null,if(i+columns<pts.size)i to i+columns else null) }
                }
                edges.forEach { (a,b) -> drawLine(Color.Black.copy(alpha=0.6f),pts[a],pts[b],3f);drawLine(colors.accent.copy(alpha=0.75f),pts[a],pts[b],1f) }
                pts.forEachIndexed { i,p -> drawCircle(colors.windowBackground,4.5f,p);drawCircle(if(i in editor.vertices)colors.accent else colors.textPrimary,if(i in editor.vertices)3.5f else 2.5f,p) }
                val chosen=pts.filterIndexed { i,_ -> i in editor.vertices }
                if(chosen.isNotEmpty() && editor.tool in listOf(CanvasTool.MOVE,CanvasTool.ROTATE,CanvasTool.SCALE)) {
                    val c=Offset(chosen.map { it.x }.average().toFloat(),chosen.map { it.y }.average().toFloat())
                    drawCircle(colors.accent,5f,c,style=Stroke(1.5f))
                    if(editor.tool==CanvasTool.ROTATE) drawCircle(colors.accent,40f,c,style=Stroke(1.5f))
                    else { drawLine(Color(0xffd97878),c,c+Offset(46f,0f),2f);drawLine(Color(0xff7bbb99),c,c+Offset(0f,-46f),2f) }
                }
            }
            if(pathTool && target.kind=="mesh") {
                editor.paths().forEach { path ->
                    val local=DeformPathTools.positions(path,target.geometry.points)
                    val curve=DeformPathTools.curve(local,path.points.map { it.corner },path.closed)
                    val points=editor.screen(curve.flatMap { listOf(it.first,it.second) }.toFloatArray(),target,viewport)
                    val selected=path.id==editor.activePath
                    points.zipWithNext().forEach { (a,b) -> drawLine(Color.Black.copy(alpha=0.7f),a,b,5f);drawLine(if(selected)colors.accent else colors.textPrimary,a,b,2f) }
                    editor.screen(local.flatMap { listOf(it.first,it.second) }.toFloatArray(),target,viewport).forEachIndexed { i,p ->
                        drawCircle(colors.windowBackground,5.5f,p);drawCircle(if(selected && i==editor.pathPoint) colors.accent else colors.textPrimary,4f,p)
                    }
                }
                val points=editor.screen(editor.draft.flatMap { listOf(it.first,it.second) }.toFloatArray(),target,viewport)
                points.zipWithNext().forEach { (a,b) -> drawLine(colors.accent,a,b,2f) }
                points.forEach { drawCircle(colors.accent,4f,it) }
                if(editor.drawingPath && points.isNotEmpty()) editor.cursor?.let { drawLine(colors.accent.copy(alpha=0.5f),points.last(),it,1f) }
            }
        }
        if(editor.marquee.isNotEmpty()) {
            val points=editor.marquee
            if(editor.tool==CanvasTool.LASSO) {
                val path=Path().apply { moveTo(points[0].x,points[0].y);points.drop(1).forEach { lineTo(it.x,it.y) };close() }
                drawPath(path,colors.accent.copy(alpha=0.12f));drawPath(path,colors.accent,style=Stroke(1f))
            } else { val a=points.first();val b=points.last();val origin=Offset(minOf(a.x,b.x),minOf(a.y,b.y));val extent=Size(kotlin.math.abs(a.x-b.x),kotlin.math.abs(a.y-b.y));drawRect(colors.accent.copy(alpha=0.12f),origin,extent);drawRect(colors.accent,origin,extent,style=Stroke(1f)) }
        }
        if(editor.tool in listOf(CanvasTool.BRUSH,CanvasTool.SMOOTH)) editor.cursor?.let {
            drawCircle(Color.Black.copy(alpha=0.7f),editor.radius,it,style=Stroke(3f));drawCircle(colors.textPrimary,editor.radius,it,style=Stroke(1f));drawCircle(colors.accent.copy(alpha=0.6f),editor.radius*editor.hardness,it,style=Stroke(1f))
        }
    }
    CanvasToolBar(editor = editor, focus = focus)
    Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(colors.panelBackground).border(1.dp,colors.divider)) {
        Row(Modifier.height(32.dp).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            Text(tr("editor.tool.${editor.tool.name.lowercase()}"),color=colors.textPrimary,fontSize=11.sp)
            Text(target?.geometry?.name ?: tr("editor.select"),color=colors.textMuted,fontSize=11.sp)
            if(target!=null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    CompactButton(if(editor.parameter==null) if(target.geometry.axes.isEmpty()) tr("editor.base") else tr("editor.pose") else editor.parameter!!,{ menu=true },height=23.dp)
                    DropdownMenu(menu,{ menu=false }) {
                        DropdownMenuItem({ editor.parameter=null;menu=false;focus() }) { Text(if(target.geometry.axes.isEmpty())tr("editor.base") else tr("editor.pose")) }
                        editor.model.parameters.forEach { p -> DropdownMenuItem({ editor.parameter=p.id.raw;menu=false;focus() }) { Text(p.name) } }
                    }
                }
            }
            if(editor.tool in listOf(CanvasTool.BRUSH,CanvasTool.SMOOTH)) {
                Text(tr("editor.radius"),color=colors.textMuted,fontSize=10.sp)
                CompactNumberSpinner(editor.radius.toDouble(),{ editor.radius=it.toFloat() },Modifier.width(72.dp),min=4.0,max=500.0,unit="px",height=23.dp)
                Text(tr("editor.strength"),color=colors.textMuted,fontSize=10.sp)
                CompactNumberSpinner((editor.strength*100).toDouble(),{ editor.strength=it.toFloat()/100 },Modifier.width(65.dp),min=1.0,max=100.0,unit="%",height=23.dp)
                Text(tr("editor.hardness"),color=colors.textMuted,fontSize=10.sp)
                CompactNumberSpinner((editor.hardness*100).toDouble(),{ editor.hardness=it.toFloat()/100 },Modifier.width(65.dp),min=0.0,max=95.0,unit="%",height=23.dp)
            }
            if(editor.tool in listOf(CanvasTool.MOVE,CanvasTool.ROTATE,CanvasTool.SCALE)) {
                var first by remember(editor.tool) { mutableStateOf(if(editor.tool==CanvasTool.SCALE)100.0 else 0.0) }
                var second by remember(editor.tool) { mutableStateOf(0.0) }
                CompactNumberSpinner(first,{ first=it },Modifier.width(80.dp),min=if(editor.tool==CanvasTool.SCALE)0.1 else -10000.0,max=10000.0,decimals=1,unit=when(editor.tool) { CanvasTool.SCALE -> "%";CanvasTool.ROTATE -> "°";else -> "X" },height=23.dp)
                if(editor.tool==CanvasTool.MOVE)CompactNumberSpinner(second,{ second=it },Modifier.width(80.dp),min=-10000.0,max=10000.0,decimals=1,unit="Y",height=23.dp)
                CompactButton(tr("editor.apply"),{ editor.preciseTransform(viewport,first.toFloat(),second.toFloat());focus() },enabled=editor.editable && target!=null,height=23.dp)
            }
            if(editor.tool==CanvasTool.MESH) {
                listOf("vertex","edge","face").forEachIndexed { i,key -> CompactButton(tr("editor.$key"),{ editor.elementMode=i;focus() },isPrimary=editor.elementMode==i,height=23.dp) }
                listOf("split","connect","merge","delete").forEach { action -> CompactButton(tr("editor.$action"),{ editor.topology(action);focus() },enabled=editor.editable && editor.vertices.isNotEmpty(),height=23.dp) }
            }
            if(editor.tool==CanvasTool.WARP) {
                CompactButton(tr("editor.createWarp"),{ editor.createWarp();focus() },enabled=editor.editable && target?.kind=="mesh",height=23.dp)
                TooltipArea(tooltip={ Surface(color=colors.panelElevated) { Text(tr("editor.rootRotationHint"),color=colors.textPrimary,fontSize=11.sp,modifier=Modifier.padding(6.dp)) } }) {
                    CompactButton(tr("editor.createRotation"),{ editor.createWarp(rotation=true);focus() },enabled=editor.editable && target?.kind=="mesh",height=23.dp)
                }
            }
            if(pathTool) {
                CompactButton(tr("editor.newPath"),{ editor.cancel();editor.drawingPath=true;focus() },enabled=target?.kind=="mesh" && editor.editable,height=23.dp)
                if(editor.drawingPath) CompactButton(tr("editor.finishPath"),{ editor.finishPath();focus() },enabled=editor.draft.size>=2,height=23.dp)
                val active=editor.selectedPath()
                if(active!=null) {
                    if(!active.closed)CompactButton(tr("editor.extend"),{ editor.extendPath();focus() },enabled=editor.editable,height=23.dp)
                    CompactButton(tr("editor.delete"),{ editor.deletePathPoint();focus() },enabled=editor.editable,height=23.dp)
                    CompactButton(tr(if(active.closed)"editor.openPath" else "editor.closePath"),{ editor.changePath { it.copy(closed=!it.closed) };focus() },enabled=editor.editable && active.points.size>=3,height=23.dp)
                    if(editor.pathPoint in active.points.indices) CompactButton(tr("editor.corner"),{ editor.changePath { it.copy(points=it.points.mapIndexed { i,p -> if(i==editor.pathPoint)p.copy(corner=!p.corner) else p }) };focus() },enabled=editor.editable,height=23.dp)
                    Text(tr("editor.width"),color=colors.textMuted,fontSize=10.sp)
                    CompactNumberSpinner(active.width.toDouble(),{ width -> editor.changePath { it.copy(width=width.toFloat()) } },Modifier.width(82.dp),min=0.001,max=10000.0,decimals=3,step=0.01,enabled=editor.editable,height=23.dp)
                    Text(tr("editor.hardness"),color=colors.textMuted,fontSize=10.sp)
                    CompactNumberSpinner((active.hardness*100).toDouble(),{ h -> editor.changePath { it.copy(hardness=h.toFloat()/100) } },Modifier.width(64.dp),min=0.0,max=100.0,enabled=editor.editable,height=23.dp)
                }
                CompactButton("L${editor.pathLevel}",{ editor.pathLevel=if(editor.pathLevel==2)3 else 2;editor.activePath=null;focus() },height=23.dp)
            }
            CompactButton(tr("editor.undo"),{ viewModel.undoHistory();focus() },enabled=editor.editable,height=23.dp)
            CompactButton(tr("editor.redo"),{ viewModel.redoHistory();focus() },enabled=editor.editable,height=23.dp)
        }
    }
    Text(editor.error ?: if(editor.busy)tr("editor.saving") else tr("editor.selectionCount",editor.objects.size,editor.vertices.size)+"   ·   "+tr(if(pathTool)"editor.pathHint" else "editor.hint"),
        color=if(editor.error!=null)colors.error else colors.textMuted,fontSize=10.sp,
        modifier=Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.panelBackground).padding(horizontal=8.dp,vertical=5.dp))
}

@Composable
private fun ToolIcon(tool: CanvasTool, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val s=size.width/18f
        fun p(x: Float,y: Float)=Offset(x*s,y*s)
        fun line(x: Float,y: Float,a: Float,b: Float)=drawLine(color,p(x,y),p(a,b),1.3f*s)
        when(tool) {
            CanvasTool.SELECT -> { val path=Path().apply { moveTo(3*s,2*s);lineTo(14*s,10*s);lineTo(9*s,11*s);lineTo(7*s,16*s);close() };drawPath(path,color,style=Stroke(s*1.3f)) }
            CanvasTool.BOX -> drawRect(color,p(3f,3f),Size(12*s,12*s),style=Stroke(1.3f*s))
            CanvasTool.LASSO -> { drawOval(color,p(2f,3f),Size(14*s,10*s),style=Stroke(1.3f*s));line(5f,12f,4f,16f) }
            CanvasTool.MOVE -> { line(2f,9f,16f,9f);line(9f,2f,9f,16f);line(2f,9f,5f,6f);line(2f,9f,5f,12f);line(9f,2f,6f,5f);line(9f,2f,12f,5f) }
            CanvasTool.ROTATE -> { drawArc(color,30f,290f,false,p(3f,3f),Size(12*s,12*s),style=Stroke(1.3f*s));line(15f,3f,15f,8f);line(15f,8f,11f,7f) }
            CanvasTool.SCALE -> { drawRect(color,p(3f,9f),Size(6*s,6*s),style=Stroke(1.3f*s));line(8f,10f,15f,3f);line(10f,3f,15f,3f);line(15f,3f,15f,8f) }
            CanvasTool.MESH -> { line(3f,14f,8f,3f);line(8f,3f,16f,14f);line(16f,14f,3f,14f);line(8f,3f,9f,10f);line(9f,10f,3f,14f);line(9f,10f,16f,14f);drawCircle(color,2*s,p(9f,10f)) }
            CanvasTool.WARP -> { for(i in listOf(3f,9f,15f)) { line(i,3f,i,15f);line(3f,i,15f,i) } }
            CanvasTool.BRUSH,CanvasTool.SMOOTH -> { line(6f,11f,14f,3f);line(9f,14f,17f,6f);line(14f,3f,17f,6f);drawCircle(color,3*s,p(5f,14f),style=Stroke(1.3f*s));if(tool==CanvasTool.SMOOTH)line(1f,4f,7f,4f) }
            CanvasTool.PATH,CanvasTool.PATH_DEFORM -> { val path=Path().apply { moveTo(2*s,14*s);cubicTo(6*s,-2*s,12*s,20*s,16*s,4*s) };drawPath(path,color,style=Stroke(1.3f*s));drawCircle(color,2*s,p(2f,14f));drawCircle(color,2*s,p(16f,4f));if(tool==CanvasTool.PATH_DEFORM)drawCircle(color,2*s,p(9f,9f)) }
            CanvasTool.HAND -> { line(4f,9f,4f,14f);line(4f,14f,8f,17f);line(8f,17f,13f,15f);line(13f,15f,15f,6f);for(i in 6..12 step 2)line(i.toFloat(),3f,i.toFloat(),10f) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.CanvasToolBar(
    editor: CanvasEditor,
    focus: () -> Unit,
) {
    val colors = LocalToolColors.current
    val toolbarInteractionSource = remember { MutableInteractionSource() }
    val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
    var isHoveredByEvent by remember { mutableStateOf(false) }
    val isToolbarHovered = isHoveredBySource || isHoveredByEvent

    val animatedWidth by animateDpAsState(
        targetValue = if (isToolbarHovered) 156.dp else 34.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (isToolbarHovered) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isToolbarHovered) 150 else 80,
            delayMillis = if (isToolbarHovered) 40 else 0,
            easing = FastOutSlowInEasing,
        ),
    )
    val textOffset by animateDpAsState(
        targetValue = if (isToolbarHovered) 0.dp else (-6).dp,
        animationSpec = tween(
            durationMillis = if (isToolbarHovered) 180 else 80,
            delayMillis = if (isToolbarHovered) 30 else 0,
            easing = FastOutSlowInEasing,
        ),
    )
    val elevation by animateDpAsState(
        targetValue = if (isToolbarHovered) 8.dp else 2.dp,
        animationSpec = tween(durationMillis = 200),
    )

    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 44.dp)
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(4.dp),
                clip = false,
            )
            .width(animatedWidth)
            .background(colors.panelBackground.copy(alpha = 0.95f), RoundedCornerShape(4.dp))
            .border(
                1.dp,
                if (isToolbarHovered) colors.borderHover else colors.border,
                RoundedCornerShape(4.dp)
            )
            .hoverable(toolbarInteractionSource)
            .onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
            .onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
            .verticalScroll(rememberScrollState())
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CanvasTool.entries.forEach { tool ->
            ToolItemRow(
                tool = tool,
                isSelected = editor.tool == tool,
                isToolbarExpanded = animatedWidth > 42.dp,
                textAlpha = textAlpha,
                textOffset = textOffset,
                isBusy = editor.busy,
                onClick = {
                    editor.activateTool(tool)
                    focus()
                },
            )
        }
    }
}

@Composable
private fun ToolItemRow(
    tool: CanvasTool,
    isSelected: Boolean,
    isToolbarExpanded: Boolean,
    textAlpha: Float,
    textOffset: androidx.compose.ui.unit.Dp,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val itemInteractionSource = remember { MutableInteractionSource() }
    val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

    val label = tr("editor.tool.${tool.name.lowercase()}")

    val bg = when {
        isSelected -> colors.accent.copy(alpha = 0.24f)
        isItemHovered -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .semantics { contentDescription = "$label  ${tool.shortcut}" }
            .clickable(
                interactionSource = itemInteractionSource,
                indication = null,
                enabled = !isBusy,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            ToolIcon(
                tool = tool,
                color = when {
                    isSelected -> colors.accent
                    isItemHovered -> colors.textPrimary
                    else -> colors.textMuted
                },
            )
        }

        if (isToolbarExpanded) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .offset(x = textOffset)
                    .alpha(textAlpha)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = label,
                    color = when {
                        isSelected -> colors.textPrimary
                        isItemHovered -> colors.textPrimary
                        else -> colors.textMuted
                    },
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tool.shortcut.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) colors.accent.copy(alpha = 0.18f) else colors.panelElevated,
                                RoundedCornerShape(3.dp)
                            )
                            .border(
                                0.5.dp,
                                if (isSelected) colors.accent.copy(alpha = 0.4f) else colors.border.copy(alpha = 0.6f),
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tool.shortcut,
                            color = if (isSelected) colors.accent else colors.textDisabled,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
