package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.interior.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.ParameterKeyMarks
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.LocalToolColors
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*

/** Native dock: authoring settings persist; audition and view isolation are temporary. */
@Composable
internal fun InteriorPanelView(vm: PSD2LiveViewModel,state: PSD2LiveState) {
    val colors=LocalToolColors.current
    val preview=state.previewModel
    val target=preview?.rig?.layerIdByDrawableId?.entries?.firstOrNull { it.value==state.selectedLayerId }?.key?:state.selectedLayerId
    val drawable=preview?.rig?.puppet?.drawables?.singleOrNull { it.id.raw==target }
    if(drawable?.mesh==null) { Text(tr("interior.select"),color=colors.textMuted,modifier=Modifier.padding(12.dp));return }
    val effect=state.rigEdits.interiorEffects.singleOrNull { it.target==target }
    val draft=vm.interiorDraft(drawable.id.raw,effect)
    val issue=remember(effect,preview) { effect?.issue(preview.authored.rig.puppet) }
    var details by remember(target) { mutableStateOf(false) }
    var binding by remember(target) { mutableStateOf(false) }
    var guideOpen by remember(target) { mutableStateOf(false) }
    fun request()=buildJsonObject {
        put("target",target);put("enabled",effect?.enabled?:true);put("settings",NativeInteriorAuthoring.settingsJson(draft.shape));put("motion",draft.motion.toJson())
        put("parameters",JsonArray(draft.sources.map(::JsonPrimitive)))
        put("guides",JsonArray(draft.guides.map { g -> buildJsonObject {
            put("id",g.id);put("purpose",g.purpose.name);put("radius",g.radius);put("amount",g.amount);put("pitchOnly",g.pitchOnly)
            put("points",JsonArray(g.points.map { JsonArray(listOf(JsonPrimitive(it.x),JsonPrimitive(it.y))) }))
        } }))
    }
    fun save()=vm.putInterior(request())
    LaunchedEffect(target,draft.version,draft.dirty,vm.interiorTarget!=null) {
        if(effect?.enabled==true && draft.dirty && vm.interiorTarget!=null) {
            kotlinx.coroutines.delay(90)
            try { vm.previewInteriorDraft(request(),draft,draft.version) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { vm.interiorError=e.message }
        }
    }
    DisposableEffect(target,state.projectOpenGeneration,state.activeWorkspace.id,state.activeCanvas.id,state.activeCanvas.mode) { onDispose { vm.stopInteriorTrial() } }
    LaunchedEffect(effect,preview) {
        if(effect?.enabled!=true || issue!=null || vm.interiorTarget==null) { vm.stopInteriorTrial();return@LaunchedEffect }
        vm.startInteriorTrial(effect)
    }
    LaunchedEffect(target,effect,vm.interiorTarget!=null) {
        if(effect?.enabled!=true || issue!=null || vm.interiorTarget==null) { vm.stopInteriorTrial();return@LaunchedEffect }
        vm.startInteriorTrial(effect)
        var last=withFrameNanos { it }
        while(true) { val now=withFrameNanos { it };vm.stepInteriorTrial((now-last)/1_000_000_000f);last=now }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(9.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            CompactCheckbox(effect?.enabled==true,{ value -> if(!value)vm.stopInteriorTrial();vm.putInterior(buildJsonObject { put("target",target);put("enabled",value) }) },label=tr("interior.enabled"))
            CompactButton(tr(if(vm.interiorSoloActiveFor(state))"interior.restoreView"else"interior.solo"),{ vm.toggleInteriorSolo() })
        }
        Text(drawable.name,color=colors.textPrimary)
        vm.interiorError?.takeIf { vm.interiorStatusTarget==target && vm.interiorStatusGeneration==state.projectOpenGeneration }?.let { Text(it,color=colors.accent) }
        if(vm.interiorBusy && vm.interiorStatusTarget==target && vm.interiorStatusGeneration==state.projectOpenGeneration)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(effect?.enabled!=true)return@Column
        if(issue!=null) { Text(tr(issue),color=colors.accent);CompactButton(tr("interior.regenerate"),::save) }
        if(state.activeCanvas.mode!=CanvasMode.EDIT) {
            CompactButton(tr("interior.editAudition"),{ vm.setCanvasMode(state.activeCanvas.id,CanvasMode.EDIT) })
        }
        val axes=effect.sources
        val value=vm.interiorTarget?: (0f to 0f)
        fun parameterValue(p: Parameter,v: Float)=p.default+v*(if(v<0)p.default-p.min else p.max-p.default)
        fun normalized(p: Parameter,v: Float)=(v-p.default)/(if(v<p.default)p.default-p.min else p.max-p.default)
        ParameterPad2D(axes[0],axes[1],{ parameterValue(axes[0],value.first) },{ parameterValue(axes[1],value.second) },issue!=null || state.activeCanvas.mode!=CanvasMode.EDIT,issue!=null || state.activeCanvas.mode!=CanvasMode.EDIT,
            ParameterKeyMarks(listOf(axes[0].min,axes[0].default,axes[0].max),emptyList()),emptyList(),emptyList(),
            ParameterKeyMarks(listOf(axes[1].min,axes[1].default,axes[1].max),emptyList()),Modifier.fillMaxWidth().height(106.dp),
            onChange={ x,y -> vm.interiorMouse=false;vm.interiorTarget=normalized(axes[0],x) to normalized(axes[1],y);vm.startInteriorTrial(effect) },reverseY=true)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            CompactButton(tr("interior.neutral"),{ vm.interiorTarget=0f to 0f;vm.startInteriorTrial(effect) })
            CompactButton(tr("interior.stop"),{ vm.stopInteriorTrial() })
            CompactCheckbox(vm.interiorMouse,{ vm.interiorMouse=it;if(it){ vm.interiorTarget=0f to 0f;vm.startInteriorTrial(effect) } },label=tr("interior.mouse"))
        }
        InteriorAmount("interior.horizontal",draft.shape.horizontal,0f..2f,{ draft.shape=draft.shape.copy(horizontal=it) },::save)
        InteriorAmount("interior.vertical",draft.shape.vertical,0f..2f,{ draft.shape=draft.shape.copy(vertical=it) },::save)
        InteriorAmount("interior.bulge",draft.shape.bulge,0f..2f,{ draft.shape=draft.shape.copy(bulge=it) },::save)
        InteriorAmount("interior.press",draft.shape.press,0f..2f,{ draft.shape=draft.shape.copy(press=it) },::save)
        InteriorAmount("interior.folds",draft.shape.folds,0f..2f,{ draft.shape=draft.shape.copy(folds=it) },::save)
        InteriorAmount("interior.contour",draft.shape.contour,0f..1f,{ draft.shape=draft.shape.copy(contour=it) },::save)
        CompactCheckbox(draft.motion.enabled,{ draft.motion=draft.motion.copy(enabled=it);save() },label=tr("interior.dynamics"))
        if(draft.motion.enabled) {
            InteriorAmount("interior.response",draft.motion.responseSeconds,0f.. .6f,{ draft.motion=draft.motion.copy(responseSeconds=it) },::save)
            InteriorAmount("interior.transmission",draft.motion.transmissionSeconds,0f.. .5f,{ draft.motion=draft.motion.copy(transmissionSeconds=it) },::save)
            InteriorAmount("interior.strength",draft.motion.transmissionStrength,0f..1f,{ draft.motion=draft.motion.copy(transmissionStrength=it) },::save)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                for((title,a,b)in listOf(Triple("↓",InteriorPoint(.5f,.05f),InteriorPoint(.5f,.95f)),Triple("↑",InteriorPoint(.5f,.95f),InteriorPoint(.5f,.05f)),Triple("→",InteriorPoint(.05f,.5f),InteriorPoint(.95f,.5f)),Triple("←",InteriorPoint(.95f,.5f),InteriorPoint(.05f,.5f))))CompactButton(title,{ draft.motion=draft.motion.copy(from=a,to=b);save() })
            }
        }
        CompactButton(tr("interior.more"),{ details=!details })
        if(details) {
            InteriorAmount("interior.sensitivityX",draft.motion.sensitivityX,.1f..1f,{ draft.motion=draft.motion.copy(sensitivityX=it) },::save)
            InteriorAmount("interior.sensitivityY",draft.motion.sensitivityY,.1f..1f,{ draft.motion=draft.motion.copy(sensitivityY=it) },::save)
            for(end in 0..1)for(axis in 0..1) {
                val p=if(end==0)draft.motion.from else draft.motion.to
                InteriorAmount("interior.${if(end==0)"from" else "to"}${if(axis==0)"X" else "Y"}",if(axis==0)p.x else p.y,0f..1f,{ v ->
                    val next=if(axis==0)p.copy(x=v) else p.copy(y=v)
                    if(kotlin.math.hypot((next.x-(if(end==0)draft.motion.to else draft.motion.from).x).toDouble(),(next.y-(if(end==0)draft.motion.to else draft.motion.from).y).toDouble())>=.05)draft.motion=if(end==0)draft.motion.copy(from=next)else draft.motion.copy(to=next)
                },::save)
            }
        }
        CompactButton(tr("interior.binding"),{ binding=!binding })
        if(binding)for(axis in 0..1) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                CompactButton("${if(axis==0)"X" else "Y"}: "+(effect.sources.getOrNull(axis)?.name?:tr("interior.newParameter")),{ expanded=true })
                DropdownMenu(expanded,{ expanded=false }) {
                    DropdownMenuItem({ draft.sources=draft.sources.toMutableList().also { it[axis]="" };expanded=false;save() }) { Text(tr("interior.newParameter")) }
                    preview.rig.puppet.parameters.filter { it.kind==ParameterKind.NORMAL && !it.repeat && it.min<it.default && it.default<it.max }.forEach { p ->
                        DropdownMenuItem({ draft.sources=draft.sources.toMutableList().also { it[axis]=p.id.raw };expanded=false;save() }) { Text(p.name) }
                    }
                }
            }
        }
        CompactButton(tr("interior.guides"),{ guideOpen=!guideOpen })
        if(guideOpen) {
            val raster=preview.analysis.layers.singleOrNull { it.source.id.raw==state.selectedLayerId }?.source?.raster
            draft.guides.forEachIndexed { i,g ->
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${tr("interior.fold")} ${i+1}",color=colors.textPrimary)
                    CompactCheckbox(g.pitchOnly,{ v -> draft.guides=draft.guides.toMutableList().also { it[i]=g.copy(pitchOnly=v) };save() },label=tr("interior.pitchOnly"))
                    CompactButton("×",{ draft.guides=draft.guides.filterIndexed { n,_ -> n!=i };save() })
                }
                InteriorAmount("interior.foldAmount",g.amount,0f..2f,{ v -> draft.guides=draft.guides.toMutableList().also { it[i]=g.copy(amount=v) } },::save)
                InteriorAmount("interior.radius",g.radius,1f..maxOf(2f,(raster?.width?:128).toFloat()),{ v -> draft.guides=draft.guides.toMutableList().also { it[i]=g.copy(radius=v) } },::save)
                g.points.forEachIndexed { j,p -> for(a in 0..1)InteriorAmount(if(a==0)"interior.pointX" else "interior.pointY",if(a==0)p.x else p.y,0f..maxOf(1f,(if(a==0)raster?.width else raster?.height)?.toFloat()?:1f),{ v ->
                    val next=if(a==0)p.copy(x=v)else p.copy(y=v)
                    val pts=g.points.toMutableList().also { it[j]=next }
                    if(pts.zipWithNext().all { (x,y)->x!=y })draft.guides=draft.guides.toMutableList().also { it[i]=g.copy(points=pts) }
                },::save) }
            }
            CompactButton(tr("interior.addFold"),{
                if(raster!=null) { draft.guides=draft.guides+InteriorGuide("fold"+System.nanoTime(),listOf(InteriorPoint(raster.width*.5f,raster.height*.2f),InteriorPoint(raster.width*.5f,raster.height*.8f)),InteriorGuidePurpose.FOLD);save() }
            })
        }
    }
}

@Composable
private fun InteriorAmount(key: String,value: Float,range: ClosedFloatingPointRange<Float>,change: (Float)->Unit,finish: ()->Unit) {
    val colors=LocalToolColors.current
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(tr(key),color=colors.textPrimary,modifier=Modifier.width(100.dp))
        CompactSlider(value,change,onValueChangeFinished=finish,modifier=Modifier.weight(1f),valueRange=range)
        Text("%.2f".format(value),color=colors.textMuted,modifier=Modifier.width(38.dp))
    }
}
