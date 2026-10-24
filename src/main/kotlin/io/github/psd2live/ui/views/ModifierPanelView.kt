package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.DocumentGenerators
import io.github.psd2live.core.RigComponents
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.handCursor
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import androidx.compose.foundation.clickable
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.PhysicsSection
import io.github.psd2live.ui.views.texture.FloatingMenuRow
import io.github.psd2live.ui.views.texture.FloatingMenuSection
import androidx.compose.ui.input.pointer.isAltPressed
import io.github.psd2live.ui.views.tooloptions.GlidingColumn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel

/** How a setting's fields step and show: step, decimals. */
private val MODIFIER_FIELDS = mapOf("axis" to (1.0 to 1), "amount" to (0.05 to 2), "center" to (1.0 to 1), "depth" to (5.0 to 0),
	"curvature" to (0.05 to 2), "angle" to (1.0 to 1), "distance" to (100.0 to 0), "radius" to (1.0 to 0))

/**
 * The modifiers panel, in two pages. The object page shows the selection as Live2D computes it: its own keyforms, then
 * each parent deformer's, innermost first ([RigStages]) - each stage with who made it and the cards acting on it, in
 * the order they run. The world page holds what belongs to the whole model: every simulation and its bake, the
 * problems of every card, and the generation settings.
 */
@Composable
internal fun ModifierPanelView(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	Column(Modifier.fillMaxSize()) {
		CompactTabBar(listOf(tr("modifiers.page.object"), tr("modifiers.page.world")), vm.modifiers.page, { vm.modifiers.page = it }, height = 24.dp)
		if (vm.modifiers.page == ModifierController.WORLD) WorldPage(vm, state) else ObjectPage(vm, state)
	}
}

/** The problems the modifiers' sampling reports, worked out off the UI thread for the committed document. */
@Composable
private fun rememberModifierIssues(state: PSD2LiveState): List<ModifierSampler.Issue> {
	val preview = state.previewModel
	val issues by produceState(emptyList<ModifierSampler.Issue>(), preview, state.rigEdits.modifiers) {
		value = withContext(Dispatchers.Default) {
			runCatching {
				val overlay = preview!!.config.rigEdits
				if (overlay.modifiers.isEmpty()) emptyList()
				else ModifierSampler.issues(DocumentGenerators.generate(preview.authoredPuppet(overlay), overlay.copy(modifiers = emptyList())), overlay.modifiers)
			}.getOrDefault(emptyList())
		}
	}
	return issues
}

@Composable
private fun ObjectPage(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val preview = state.previewModel
	val target = preview?.rig?.layerIdByDrawableId?.entries?.firstOrNull { it.value == state.selectedLayerId }?.key ?: state.selectedLayerId
	val puppet = preview?.rig?.puppet
	val mesh = puppet?.drawables?.singleOrNull { it.id.raw == target }?.takeIf { it.mesh != null }
	// A deformer picked on the canvas or in the hierarchy shows its own stages; otherwise the selected layer's mesh.
	val picked = vm.canvasEditorFor(state.activeCanvas.id).selectionTarget()
	val key = when (picked?.kind) {
		"warp" -> ModifierTarget.WARP + picked.id
		"rotation" -> RigStages.ROTATION + picked.id
		else -> mesh?.id?.raw
	}
	// The committed model shows what was previewed: the preview steps aside.
	LaunchedEffect(preview) { vm.modifiers.dropPreview() }
	DisposableEffect(key, state.activeCanvas.id) { onDispose { vm.modifiers.dropPreview() } }
	val stages = remember(state.rigEdits, puppet, key) {
		if (key == null || puppet == null) emptyList() else runCatching { RigStages.chain(puppet, state.rigEdits, key) }.getOrDefault(emptyList())
	}
	// The selection's own stage starts open, the parents folded; cards start open and the panel keeps the ones closed.
	var openStages by remember(key) { mutableStateOf(setOfNotNull(key)) }
	var closed by remember(key) { mutableStateOf(emptySet<String>()) }
	var contributedOnly by remember { mutableStateOf(false) }
	val own = stages.firstOrNull()
	val labels = listOf(tr("modifiers.add"), tr("modifiers.contributedOnly"))
	var adding by remember { mutableStateOf(false) }
	PanelToolbar(labels = labels, iconCount = 4) { shown ->
		Box {
			PanelToolButton(labels[0], showLabel = shown > 0, onClick = { adding = true }, enabled = own != null && own.kind != "rotation",
				tooltip = tr("modifiers.addTip")) {
				IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
			}
			if (own != null && puppet != null) PressMenu(expanded = adding, press = Offset(0f, 0f), onDismissRequest = { adding = false }) {
				ModifierAddMenu(vm, state, puppet, own) { adding = false }
			}
		}
		PanelToolSwitch(labels[1], showLabel = shown > 1, checked = contributedOnly, onCheckedChange = { contributedOnly = it }, enabled = stages.isNotEmpty(),
			tooltip = tr("modifiers.contributedOnlyTip"))
		Spacer(Modifier.weight(1f))
		PanelExpandCollapseButtons(onExpandAll = { openStages = stages.map { it.key }.toSet(); closed = emptySet() },
			onCollapseAll = { openStages = emptySet() }, enabled = stages.isNotEmpty())
	}
	if (key == null || puppet == null || preview == null) {
		Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
			Text(tr("modifiers.select"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted, textAlign = TextAlign.Center)
		}
		return
	}
	val issues = rememberModifierIssues(state)
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		// The role components that made what the selection is computed through, before its stages.
		for (component in componentsActingOn(stages)) ComponentCard(vm, state, puppet, component, "component:${component.id}" !in closed) {
			closed = if ("component:${component.id}" in closed) closed - "component:${component.id}" else closed + "component:${component.id}"
		}
		for (stage in stages) {
			if (contributedOnly && !stage.contributed && stage.key != key) continue
			StageSection(vm, state, puppet, stage, stage.key in openStages, { openStages = if (stage.key in openStages) openStages - stage.key else openStages + stage.key },
				closed, { id -> closed = if (id in closed) closed - id else closed + id }, issues)
		}
	}
}

/** Who made a stage or an axis, as its row names it. */
private fun ownerLabel(overlay: io.github.psd2live.core.RigEditOverlay, owner: RigStages.Owner): String = when (owner.kind) {
	RigStages.Owner.Kind.COMPONENT -> tr("modifiers.component.${owner.id}")
	RigStages.Owner.Kind.SWING -> overlay.swingEdits.firstOrNull { it.id == owner.id }?.name ?: owner.id
	RigStages.Owner.Kind.SIMULATION -> overlay.simEdits.firstOrNull { it.id == owner.id }?.name ?: owner.id
	RigStages.Owner.Kind.INTERIOR -> overlay.interiorEffects.firstOrNull { it.id == owner.id }?.name ?: owner.id
	RigStages.Owner.Kind.MODIFIERS -> tr("modifiers.owner.modifiers")
	RigStages.Owner.Kind.SKIN -> tr("modifiers.system.skin")
	RigStages.Owner.Kind.MANUAL -> tr("modifiers.owner.manual")
}

/**
 * One stage: its row (what it is, who made it, how many cards act on it, a way to select its deformer and to add to it),
 * and when open the keys the base rig and the user gave it, then its cards in the order they run.
 */
@Composable
private fun StageSection(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, stage: RigStages.Stage, open: Boolean, onToggle: () -> Unit,
                         closed: Set<String>, onToggleCard: (String) -> Unit, issues: List<ModifierSampler.Issue>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val caption = typography.caption.copy(fontSize = 9.5.sp)
	var adding by remember { mutableStateOf(false) }
	val trailing: @Composable RowScope.() -> Unit = {
		Text(stage.owner?.let { if (it.kind == RigStages.Owner.Kind.MANUAL) ownerLabel(state.rigEdits, it) else tr("modifiers.stage.by", ownerLabel(state.rigEdits, it)) } ?: "", style = caption, color = colors.textMuted, maxLines = 1,
			overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
		if (stage.kind != "mesh") CompactIconButton(onClick = { vm.selectDeformer(stage.id) }, tooltip = tr("modifiers.stage.select"), size = 18.dp) {
			GridIcon(Modifier.size(11.dp), colors.textMuted) { line(5f, 13f, 13f, 5f); line(8f, 5f, 13f, 5f); line(13f, 5f, 13f, 10f) }
		}
		if (stage.kind != "rotation") Box {
			CompactIconButton(onClick = { adding = true }, tooltip = tr("modifiers.stage.add"), size = 18.dp) {
				IconAdd(modifier = Modifier.size(9.dp), tint = colors.textMuted)
			}
			PressMenu(expanded = adding, press = Offset(0f, 0f), onDismissRequest = { adding = false }) {
				ModifierAddMenu(vm, state, puppet, stage) { adding = false }
			}
		}
	}
	val title = tr("modifiers.stage.${stage.kind}", stage.name)
	PanelSectionRow(title, open, onToggle, count = stage.entries.size.takeIf { it > 0 }, icon = null, trailing = trailing)
	io.github.psd2live.ui.views.physics.PhysicsRowDivider()
	if (!open) return
	// The keys no card stands for: the base rig's generators', the skin's and the user's own, by who made them.
	val plain = stage.axes.filter { it.owner.kind in setOf(RigStages.Owner.Kind.COMPONENT, RigStages.Owner.Kind.SKIN, RigStages.Owner.Kind.MANUAL) }
		.groupBy { it.owner }
	if (plain.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		val names = puppet.parameters.associate { it.id.raw to it.name }
		for ((owner, axes) in plain) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(ownerLabel(state.rigEdits, owner), style = caption, color = colors.textPrimary, modifier = Modifier.width(72.dp), maxLines = 1)
			Text(axes.joinToString(" · ") { names[it.parameter] ?: it.parameter }, style = caption, color = colors.textMuted, maxLines = 2,
				overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
		}
	}
	val ids = stage.entries.map { it.id }
	if (ids.isEmpty() && plain.isEmpty()) Text(tr("modifiers.stage.empty"), style = caption, color = colors.textMuted,
		modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
	stage.entries.forEachIndexed { index, entry ->
		when (entry) {
			is ModifierStack.Entry.Modifier -> ModifierCard(vm, state, puppet, stage.key, ids, index, entry.modifier,
				issues.filter { it.modifier == entry.modifier.id && it.target == stage.key }, entry.id !in closed) { onToggleCard(entry.id) }
			else -> systemOf(entry, state.rigEdits, puppet)?.let { system ->
				GeneratorCard(vm, state, puppet, system, ids, index, entry.id !in closed) { onToggleCard(entry.id) }
			}
		}
	}
}

/** What belongs to the whole model: every simulation and its bake, every card's problems, and the generation settings. */
@Composable
private fun WorldPage(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 10.sp)
	var open by remember { mutableStateOf(setOf("components", "simulations", "issues", "generate")) }
	var closedComponents by remember { mutableStateOf(emptySet<String>()) }
	fun toggle(key: String) { open = if (key in open) open - key else open + key }
	val issues = rememberModifierIssues(state)
	val preview = state.previewModel
	val interior = remember(state.rigEdits, preview) {
		if (preview == null) emptyList() else state.rigEdits.interiorEffects.filter { it.enabled }.mapNotNull { e -> e.issue(preview.authored.rig.puppet)?.let { e to it } }
	}
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		// Every role component the model has, each a card as on the object page.
		val puppet = preview?.rig?.puppet
		val components = remember(puppet) { puppet?.let { RigComponents.present(it).filter { c -> c.parameters.isNotEmpty() } }.orEmpty() }
		PanelSectionRow(tr("modifiers.world.components"), "components" in open, { toggle("components") }, count = components.size, icon = null)
		io.github.psd2live.ui.views.physics.PhysicsRowDivider()
		if ("components" in open && puppet != null) for (component in components) ComponentCard(vm, state, puppet, component, component.id !in closedComponents) {
			closedComponents = if (component.id in closedComponents) closedComponents - component.id else closedComponents + component.id
		}
		Column(Modifier.tutorialTarget(TutorialTargetId.SIMULATION_DOCK)) {
			PanelSectionRow(tr("modifiers.world.simulations"), "simulations" in open, { toggle("simulations") }, count = state.rigEdits.simEdits.size, icon = null)
			io.github.psd2live.ui.views.physics.PhysicsRowDivider()
			if ("simulations" in open) io.github.psd2live.ui.views.simulation.SimulationOverview(vm, state)
		}
		PanelSectionRow(tr("modifiers.world.issues"), "issues" in open, { toggle("issues") }, count = issues.size + interior.size, icon = null)
		io.github.psd2live.ui.views.physics.PhysicsRowDivider()
		if ("issues" in open) Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			if (issues.isEmpty() && interior.isEmpty()) Text(tr("modifiers.world.noIssues"), style = caption, color = colors.textMuted)
			val names = state.rigEdits.modifiers.associate { it.id to it.name }
			for (issue in issues) IssueRow("${names[issue.modifier] ?: issue.modifier} · ${issue.target.removePrefix(ModifierTarget.WARP)}",
				tr("modifiers.issue.${issue.kind.code}", issue.detail.ifEmpty { issue.error?.let { "%.1f".format(it) } ?: "" })) {
				if (issue.target.startsWith(ModifierTarget.WARP)) vm.selectDeformer(issue.target.removePrefix(ModifierTarget.WARP)) else vm.showStackOf(issue.target)
				vm.modifiers.page = ModifierController.OBJECT
			}
			for ((effect, issue) in interior) IssueRow(effect.name, tr(issue)) { vm.showStackOf(effect.target); vm.modifiers.page = ModifierController.OBJECT }
		}
		Column(Modifier.tutorialTarget(TutorialTargetId.MODEL_SETTINGS)) {
			PanelSectionRow(tr("modifiers.world.generate"), "generate" in open, { toggle("generate") }, icon = null)
			io.github.psd2live.ui.views.physics.PhysicsRowDivider()
			if ("generate" in open) ModelPresetsSection(state, vm)
		}
	}
}

/** One problem on the world page: what it is on, what is wrong; a click shows the card. */
@Composable
private fun IssueRow(subject: String, problem: String, onClick: () -> Unit) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 10.sp)
	Row(Modifier.fillMaxWidth().handCursor().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(subject, style = caption, color = colors.textPrimary, maxLines = 1, modifier = Modifier.width(110.dp),
			overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
		Text(problem, style = caption, color = colors.warning, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
	}
}

@Composable
private fun ModifierCard(
	vm: PSD2LiveViewModel,
	state: PSD2LiveState,
	puppet: PuppetModel,
	mesh: String,
	ids: List<String>,
	index: Int,
	modifier: RigModifier,
	issues: List<ModifierSampler.Issue>,
	open: Boolean,
	onToggle: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var menu by remember { mutableStateOf(false) }
	val trailing: @Composable RowScope.() -> Unit = {
		PanelToolSwitch(tr("modifiers.enabled"), showLabel = false, checked = modifier.enabled, onCheckedChange = { value ->
			vm.modifiers.put(buildJsonObject { put("id", modifier.id); put("enabled", value) })
		}, enabled = true, tooltip = tr("modifiers.enabled"))
		Box {
			CompactIconButton(onClick = { menu = true }, tooltip = tr("modifiers.menu"), size = 18.dp) {
				GridIcon(Modifier.size(11.dp), colors.textMuted) { dot(4.6f, 9f, 1.3f); dot(9f, 9f, 1.3f); dot(13.4f, 9f, 1.3f) }
			}
			PressMenu(expanded = menu, press = Offset(0f, 0f), onDismissRequest = { menu = false }) {
				ModifierMenu(vm, state, puppet, mesh, ids, index, modifier) { menu = false }
			}
		}
	}
	// A modifier left with its default name is titled by its kind alone.
	val kind = tr("modifiers.kind.${modifier.kind}")
	val title = if (modifier.name == modifier.kind.replaceFirstChar { it.uppercase() }) kind else "${modifier.name} · $kind"
	PhysicsSection(title, open, onToggle, trailing = trailing) {
		for (issue in issues) Text(tr("modifiers.issue.${issue.kind.code}", issue.detail.ifEmpty { issue.error?.let { "%.1f".format(it) } ?: "" }),
			style = typography.caption.copy(fontSize = 10.sp), color = colors.warning)
		val pose: (String) -> Float = { p -> state.parameterValues[ParameterId(p)] ?: puppet.parameters.firstOrNull { it.id.raw == p }?.default ?: 0f }
		for (option in modifier.definition.options) ModifierOptionField(vm, puppet, mesh, modifier, option)
		// How it is written on a mesh: into its keyforms, as a warp of its own, or whichever keeps them fewer.
		if (!mesh.startsWith(ModifierTarget.WARP)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			FieldLabel(tr("modifiers.output"), tooltip = tr("modifiers.outputTip"))
			CompactDropdown(ModifierOutput.entries, modifier.output, { value ->
				if (value != modifier.output) vm.modifiers.put(buildJsonObject { put("id", modifier.id); put("output", value.id) })
			}, Modifier.weight(1f), itemLabel = { tr("modifiers.output.${it.id}") }, height = 22.dp)
		}
		for (setting in modifier.definition.settings.filter { it.field }) ModifierField(vm, state, puppet, mesh, modifier, setting, pose)
	}
}

/** One text choice of [modifier]: a parameter, another mesh or warp, or one of the option's choices; an optional one can be cleared. */
@Composable
private fun ModifierOptionField(vm: PSD2LiveViewModel, puppet: PuppetModel, target: String, modifier: RigModifier, option: ModifierOption) {
	val current = modifier.options[option.key]
	val choices: List<Pair<String, String>> = when (option.type) {
		ModifierOption.Type.PARAMETER -> puppet.parameters.map { it.id.raw to "${it.name} · ${it.id.raw}" }
		ModifierOption.Type.TARGET -> puppet.drawables.filter { it.mesh != null && it.id.raw != target }.map { it.id.raw to it.name } +
			puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().map { ModifierTarget.WARP + it.id.raw }
				.filter { it != target }.map { it to "Warp · ${it.removePrefix(ModifierTarget.WARP)}" }
		ModifierOption.Type.CHOICE -> option.choices.map { it to tr("modifiers.choice.${modifier.kind}.${option.key}.$it") }
	}
	val labels = choices.toMap()
	val entries: List<String?> = (if (option.required) emptyList() else listOf(null)) + choices.map { it.first }
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("modifiers.option.${modifier.kind}.${option.key}"), tooltip = option.description)
		CompactDropdown(entries, current, { value ->
			if (value != current) vm.modifiers.put(buildJsonObject { put("id", modifier.id); putJsonObject("options") { put(option.key, value?.let(::JsonPrimitive) ?: JsonNull) } })
		}, Modifier.weight(1f), itemLabel = { it?.let { v -> labels[v] ?: v } ?: tr("modifiers.option.none") }, height = 22.dp)
	}
}

/** One setting: its components at the current pose, and the key button that keys it on the parameters there. */
@Composable
private fun ModifierField(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, mesh: String, modifier: RigModifier, setting: ModifierSetting,
                          pose: (String) -> Float) {
	val colors = LocalToolColors.current
	val window = androidx.compose.ui.platform.LocalWindowInfo.current
	val stored = modifier.value(setting.key)
	val shown = stored.at(pose)
	// What a drag or typed value holds until the commit it sends comes back as the stored value.
	var draft by remember(modifier, setting.key) { mutableStateOf<FloatArray?>(null) }
	val value = draft ?: shown
	val keyed = stored as? ModifierValue.Keyed
	val at = keyed?.axes?.associate { it.parameter to pose(it.parameter) }.orEmpty()
	fun request(next: FloatArray) = buildJsonObject {
		put("id", modifier.id)
		if (keyed == null) putJsonObject("settings") { put(setting.key, JsonArray(next.map(::JsonPrimitive))) }
		else putJsonArray("keys") { addJsonObject {
			put("setting", setting.key); putJsonObject("at") { at.forEach { (k, v) -> put(k, v) } }; put("value", JsonArray(next.map(::JsonPrimitive)))
		} }
	}
	val onKey = keyed != null && keyed.axes.all { a -> a.keys.any { kotlin.math.abs(it - pose(a.parameter)) <= ModifierValue.EPS } }
	val (step, decimals) = MODIFIER_FIELDS[setting.key] ?: (0.1 to 2)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("modifiers.setting.${setting.key}"), tooltip = setting.description)
		for (c in 0 until setting.size) CompactNumberSpinner(
			value[c].toDouble(),
			{ v ->
				val next = value.copyOf().also { it[c] = v.toFloat() }
				draft = next
				vm.modifiers.preview(request(next))
			},
			Modifier.weight(1f),
			min = setting.min.toDouble().coerceAtLeast(-100_000.0), max = setting.max.toDouble().coerceAtMost(100_000.0),
			step = step, decimals = decimals, height = 22.dp,
			onEditEnd = {
				draft?.let { next ->
					// With Alt held, the same value goes to the first modifier of this kind on every other selected mesh.
					@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
					val alt = window.keyboardModifiers.isAltPressed
					val others = if (!alt) emptyList() else {
						val stacks = ModifierSampler.stacks(state.rigEdits.modifiers, puppet)
						selectedMeshes(state, puppet, mesh).filter { it != mesh }.mapNotNull { m ->
							stacks[m].orEmpty().firstOrNull { it.kind == modifier.kind && it.id != modifier.id }
						}.distinctBy { it.id }
					}
					if (others.isEmpty()) vm.modifiers.put(request(next))
					else vm.modifiers.batch((listOf(modifier) + others).map { m ->
						ModifierAuthoring.PUT to JsonObject(request(next) + ("id" to JsonPrimitive(m.id)))
					})
				}
			},
		)
		val tip = tr(when { onKey -> "modifiers.key.remove"; keyed != null -> "modifiers.key.add"; else -> "modifiers.key.start" })
		CompactIconButton(onClick = {
			when {
				onKey -> vm.modifiers.put(buildJsonObject {
					put("id", modifier.id)
					putJsonArray("keys") { addJsonObject { put("setting", setting.key); putJsonObject("at") { at.forEach { (k, v) -> put(k, v) } }; put("delete", true) } }
				})
				keyed != null -> vm.modifiers.put(request(value))
				else -> {
					// A first key goes on the parameters the modifier is keyed on, else on those moved off their defaults.
					val parameters = modifier.drivers(puppet).ifEmpty {
						puppet.parameters.filter { p -> p.kind == ParameterKind.NORMAL && kotlin.math.abs(pose(p.id.raw) - p.default) > ModifierValue.EPS }.map { it.id.raw }
					}
					if (parameters.isEmpty()) vm.setStatusText(tr("modifiers.key.noParameter"))
					else vm.modifiers.put(buildJsonObject {
						put("id", modifier.id)
						putJsonArray("keys") { addJsonObject {
							put("setting", setting.key); putJsonObject("at") { parameters.forEach { put(it, pose(it)) } }; put("value", JsonArray(value.map(::JsonPrimitive)))
						} }
					})
				}
			}
		}, tooltip = tip, size = 18.dp) {
			GridIcon(Modifier.size(11.dp), if (keyed != null) colors.accent else colors.textMuted) { keyDiamond(if (onKey) 2 else if (keyed != null) 1 else 0) }
		}
	}
}

@Composable
private fun ModifierMenu(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, mesh: String, ids: List<String>, index: Int, modifier: RigModifier,
                         close: () -> Unit) {
	val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
	fun add(enabled: Boolean, danger: Boolean = false, label: String, hint: String? = null, action: () -> Unit) {
		rows += Triple(enabled, danger, @Composable { FloatingMenuRow(label, { close(); action() }, enabled = enabled, hint = hint, danger = danger) })
	}
	val stacks = ModifierSampler.stacks(state.rigEdits.modifiers, puppet)
	val meshes = modifier.target.keys(puppet)
	val alone = meshes.all { stacks[it].orEmpty().firstOrNull()?.id == modifier.id }
	val pose = modifier.drivers(puppet).associateWith { p -> state.parameterValues[ParameterId(p)] ?: puppet.parameters.firstOrNull { it.id.raw == p }?.default ?: 0f }
	add(alone && modifier.drivers(puppet).isNotEmpty(), label = tr("modifiers.applyKeyforms"), hint = tr(if (alone) "modifiers.applyKeyformsTip" else "modifiers.applyAlone")) {
		vm.modifiers.apply(modifier.id, "keyforms")
	}
	add(true, label = tr("modifiers.applyRest"), hint = tr("modifiers.applyRestTip")) { vm.modifiers.apply(modifier.id, "rest", pose) }
	if (meshes.size > 1) {
		val here = stacks[mesh].orEmpty().firstOrNull()?.id == modifier.id
		add(true, label = tr("modifiers.applyMesh"), hint = tr("modifiers.applyMeshTip")) {
			vm.modifiers.applyTo(modifier.id, mesh, if (here && modifier.drivers(puppet).isNotEmpty()) "keyforms" else "rest", pose)
		}
		add(true, label = tr("modifiers.split"), hint = tr("modifiers.splitTip")) { vm.modifiers.split(modifier.id, mesh) }
	}
	val others = selectedMeshes(state, puppet, mesh).filter { it !in meshes }
	add(others.isNotEmpty(), label = tr("modifiers.copyToSelected"), hint = tr("modifiers.copyToSelectedTip", others.size)) { vm.modifiers.copy(modifier.id, others) }
	addMoves(vm.modifiers, ids, index) { label, enabled, action -> add(enabled, label = label, action = action) }
	add(true, danger = true, label = tr("modifiers.delete")) { vm.modifiers.delete(modifier.id) }
	GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, dangers = { rows[it].second }) { rows[it].third() }
}

/** The meshes of the selected layers, [current] first: what a copy or a batch edit goes to. */
internal fun selectedMeshes(state: PSD2LiveState, puppet: PuppetModel, current: String): List<String> {
	val byLayer = state.previewModel?.rig?.layerIdByDrawableId.orEmpty()
	val selected = (state.selectedLayerIds + listOfNotNull(state.selectedLayerId)).toSet()
	val meshes = puppet.drawables.filter { it.mesh != null && (byLayer[it.id.raw] ?: it.id.raw) in selected }.map { it.id.raw }
	return listOf(current) + (meshes - current)
}

/**
 * What can be added to [stage]: the kinds, those suiting the layer's role first, then the saved presets and saving the
 * stage's modifiers as one; on a mesh's own stage also a simulation of the selected meshes and an interior effect. A
 * mesh stage's modifiers go on every selected mesh, as Alt edits do.
 */
@Composable
private fun ModifierAddMenu(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, stage: RigStages.Stage, close: () -> Unit) {
	val colors = LocalToolColors.current
	val rows = mutableListOf<Pair<Boolean, @Composable () -> Unit>>()
	val mesh = stage.kind == "mesh"
	val meshes = if (mesh) selectedMeshes(state, puppet, stage.key) else listOf(stage.key)
	val stack = state.rigEdits.modifiers.filter { stage.key in it.target.keys(puppet) }
	rows += false to @Composable { FloatingMenuSection(tr("modifiers.addTo", tr("modifiers.stage.${stage.kind}", stage.name))) }
	val role = if (!mesh) null else state.previewModel?.analysis?.layers?.firstOrNull { it.source.id.raw == state.selectedLayerId }?.semantic?.tag
	// By category, those suiting the layer's role first within each.
	val kinds = ModifierKinds.forRole(role)
	for (category in ModifierCategory.entries) {
		val shown = kinds.filter { it.category == category }
		if (shown.isEmpty()) continue
		rows += false to @Composable { FloatingMenuSection(tr("modifiers.category.${category.id}")) }
		for (kind in shown) rows += true to @Composable {
			FloatingMenuRow(tr("modifiers.kind.${kind.id}"), { close(); vm.modifiers.add(kind.id, meshes) }, hint = tr("modifiers.kindTip.${kind.id}"))
		}
	}
	if (mesh) {
		rows += false to @Composable { FloatingMenuSection(tr("modifiers.category.dynamics")) }
		for (kind in io.github.psd2live.core.sim.SimKind.entries) rows += !state.canvasEditBusy to @Composable {
			FloatingMenuRow(tr("modifiers.addSimulation", tr("sim.kind.${kind.jsonName}")), { close(); vm.createSimulationFromSelection(kind) },
				enabled = !state.canvasEditBusy, hint = tr("sim.newTip"))
		}
		rows += false to @Composable { FloatingMenuSection(tr("modifiers.category.shape")) }
		val has = state.rigEdits.interiorEffects.any { it.target == stage.key }
		rows += !has to @Composable {
			FloatingMenuRow(tr("modifiers.system.interior"), { close(); vm.interior.put(buildJsonObject { put("target", stage.key); put("enabled", true) }) },
				enabled = !has, hint = tr("interior.enabledTip"))
		}
	}
	rows += false to @Composable { FloatingMenuSection(tr("modifiers.presets")) }
	val parameters = puppet.parameters.mapTo(HashSet()) { it.id.raw } + state.rigEdits.modifiers.flatMap { m -> m.created.map { it.id.raw } }
	for (preset in vm.modifiers.presets) rows += true to @Composable {
		FloatingMenuRow(preset.name, { close(); vm.modifiers.applyPreset(preset, meshes, parameters) }, hint = tr("modifiers.presetCount", preset.modifiers.size),
			control = {
				CompactIconButton(onClick = { vm.modifiers.deletePreset(preset.name) }, tooltip = tr("modifiers.presetDelete"), size = 18.dp) {
					IconTrash(modifier = Modifier.size(10.dp), tint = colors.textMuted)
				}
			})
	}
	rows += stack.isNotEmpty() to @Composable {
		FloatingMenuRow(tr("modifiers.presetSave"), { close(); vm.modifiers.presetDialog = meshes.first() }, enabled = stack.isNotEmpty(), hint = tr("modifiers.presetSaveTip"))
	}
	GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }) { rows[it].second() }
}

/** Names the selected mesh's stack and saves it as a preset. */
@Composable
internal fun ModifierPresetDialog(vm: PSD2LiveViewModel, state: PSD2LiveState) {
	val mesh = vm.modifiers.presetDialog ?: return
	val puppet = state.previewModel?.rig?.puppet
	val stack = state.rigEdits.modifiers.filter { mesh in it.target.keys(puppet) }
	val default = puppet?.drawables?.firstOrNull { it.id.raw == mesh }?.name ?: mesh
	var name by remember(mesh) { mutableStateOf(default) }
	val taken = vm.modifiers.presets.any { it.name == name.trim() }
	val save = { if (name.isNotBlank() && stack.isNotEmpty()) { vm.modifiers.savePreset(name.trim(), stack); vm.modifiers.presetDialog = null } }
	ModalDialogFrame(
		title = tr("modifiers.presetSave"),
		onDismiss = { vm.modifiers.presetDialog = null },
		width = 360.dp,
		onConfirm = save,
		footer = {
			CompactButton(tr("action.cancel"), { vm.modifiers.presetDialog = null })
			CompactButton(tr(if (taken) "modifiers.presetReplace" else "modifiers.presetSaveAction"), save, enabled = name.isNotBlank() && stack.isNotEmpty(), isPrimary = true)
		},
	) {
		CompactTextField(name, { name = it }, Modifier.fillMaxWidth())
		Text(tr("modifiers.presetSaveHint", stack.size), style = LocalToolTypography.current.caption, color = LocalToolColors.current.textMuted)
	}
}

/** Moving entry [index] of [ids] (the stack as one mesh shows it) up or down, through [vm]. */
private fun addMoves(vm: io.github.psd2live.ui.state.ModifierController, ids: List<String>, index: Int, add: (String, Boolean, () -> Unit) -> Unit) {
	add(tr("modifiers.moveUp"), index > 0) { vm.moveBefore(ids[index], ids[index - 1]) }
	add(tr("modifiers.moveDown"), index in 0 until ids.lastIndex) {
		if (index + 2 <= ids.lastIndex) vm.moveBefore(ids[index], ids[index + 2]) else vm.move(ids[index], "last")
	}
}

/**
 * A swing, simulation or interior effect as an entry of a mesh's stack: what it is, the document command that switches
 * it, applies it and deletes it, and the panel its settings are edited in.
 */
internal class SystemModifier(
	val kind: String, val id: String, val name: String, val module: String?, val apply: Pair<String, JsonObject>?, val note: String? = null,
	val enabled: Boolean? = null, val toggle: ((Boolean) -> Pair<String, JsonObject>)? = null, val delete: Pair<String, JsonObject>? = null,
	/** The swing, simulation or interior effect its card edits. */
	val source: Any? = null,
	/** Why it cannot be applied, when [apply] is null. */
	val applyNote: String? = null,
	/** [note] needs attention: a stale or missing bake. */
	val warn: Boolean = false,
)

/** A swing's, simulation's or interior effect's [entry] as its card shows it: what switches, applies and deletes it, and why it cannot be applied. */
internal fun systemOf(entry: ModifierStack.Entry, overlay: io.github.psd2live.core.RigEditOverlay, puppet: PuppetModel): SystemModifier? = when (entry) {
	is ModifierStack.Entry.Modifier -> null
	is ModifierStack.Entry.Swing -> entry.swing.takeIf { !it.baked }?.let { swing ->
		SystemModifier("swing", entry.id, swing.name, "physics", "swing_delete" to buildJsonObject { put("id", swing.id); put("bake", true) },
			delete = "swing_delete" to buildJsonObject { put("id", swing.id) }, source = swing)
	}
	is ModifierStack.Entry.Interior -> entry.effect.let { effect ->
		SystemModifier("interior", entry.id, effect.name, null, null, enabled = effect.enabled,
			toggle = { on -> "interior_put" to buildJsonObject { put("target", effect.target); put("enabled", on) } },
			delete = "interior_delete" to buildJsonObject { put("target", effect.target) }, source = effect, applyNote = tr("modifiers.system.applyNone"))
	}
	is ModifierStack.Entry.Simulation -> entry.simulation.let { sim ->
		val baked = sim.bake != null && sim.enabled
		// Modes written as blend shapes have no keyform axes to apply onto.
		val shapes = baked && io.github.psd2live.core.sim.SimGenerator.usesBlendShapes(puppet, sim)
		val stale = baked && runCatching { io.github.psd2live.core.sim.SimBake.stale(puppet, sim, overlay) }.getOrDefault(false)
		SystemModifier("simulation", entry.id, sim.name, null,
			if (baked && !shapes) io.github.psd2live.core.sim.SimApply.OP to buildJsonObject { put("id", sim.id) } else null,
			note = tr(when { sim.bake == null -> "modifiers.system.unbaked"; stale -> "modifiers.system.stale"; else -> "modifiers.system.baked" }),
			enabled = sim.enabled,
			toggle = { on -> "simulation_put" to buildJsonObject { put("id", sim.id); put("enabled", on) } },
			delete = "simulation_delete" to buildJsonObject { put("id", sim.id) }, source = sim,
			applyNote = tr(if (shapes) "modifiers.system.applyBlendShapes" else "modifiers.system.applyUnbaked"), warn = sim.enabled && (sim.bake == null || stale))
	}
}

/**
 * A swing's, simulation's or interior effect's card: opened, its settings as that generator's panel had them; its
 * header the switch and a menu to bake, apply, move or delete it, or show it in its own panel.
 */
@Composable
private fun GeneratorCard(vm: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, system: SystemModifier, ids: List<String>, index: Int,
                          open: Boolean, onToggle: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var menu by remember { mutableStateOf(false) }
	val trailing: @Composable RowScope.() -> Unit = {
		system.note?.let { Text(it, style = typography.caption.copy(fontSize = 9.5.sp), color = if (system.warn) colors.warning else colors.textMuted, maxLines = 1) }
		val toggle = system.toggle
		if (system.enabled != null && toggle != null) PanelToolSwitch(tr("modifiers.enabled"), showLabel = false, checked = system.enabled,
			onCheckedChange = { vm.modifiers.batch(listOf(toggle(it))) }, enabled = true, tooltip = tr("modifiers.enabled"))
		Box {
			CompactIconButton(onClick = { menu = true }, tooltip = tr("modifiers.menu"), size = 18.dp) {
				GridIcon(Modifier.size(11.dp), colors.textMuted) { dot(4.6f, 9f, 1.3f); dot(9f, 9f, 1.3f); dot(13.4f, 9f, 1.3f) }
			}
			PressMenu(expanded = menu, press = Offset(0f, 0f), onDismissRequest = { menu = false }) {
				GeneratorMenu(vm, system, ids, index) { menu = false }
			}
		}
	}
	PhysicsSection("${tr("modifiers.system.${system.kind}")} · ${system.name}", open, onToggle, trailing = trailing) {
		when (val source = system.source) {
			is io.github.psd2live.core.RigSwingEdit -> SwingSettings(vm, state, puppet, source)
			is io.github.psd2live.core.sim.RigSimEdit -> io.github.psd2live.ui.views.simulation.SimulationSettings(vm, state, puppet, source)
			is io.github.psd2live.core.interior.NativeInteriorEffect -> {
				val preview = state.previewModel
				if (!source.enabled) Text(tr("interior.disabledHint"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
				else if (preview != null) InteriorSettings(vm, state, preview, source.target, source)
			}
		}
	}
}

@Composable
private fun GeneratorMenu(vm: PSD2LiveViewModel, system: SystemModifier, ids: List<String>, index: Int, close: () -> Unit) {
	val rows = mutableListOf<Triple<Boolean, Boolean, @Composable () -> Unit>>()
	fun add(enabled: Boolean, danger: Boolean = false, label: String, hint: String? = null, action: () -> Unit) {
		rows += Triple(enabled, danger, @Composable { FloatingMenuRow(label, { close(); action() }, enabled = enabled, hint = hint, danger = danger) })
	}
	when (val source = system.source) {
		is io.github.psd2live.core.sim.RigSimEdit -> add(source.enabled, label = tr(if (source.bake == null) "sim.bakeAction" else "sim.rebake")) { vm.bakeSimulation(source.id) }
		is io.github.psd2live.core.RigSwingEdit -> add(true, label = tr("modifiers.editOnCanvas"), hint = tr("swing.editOnCanvasTip")) { vm.beginSwing(source.targets) }
		is io.github.psd2live.core.interior.NativeInteriorEffect -> {
			add(source.enabled, label = tr("modifiers.editOnCanvas"), hint = tr("interior.canvasEditTip")) { editInteriorOnCanvas(vm, vm.state.value) }
			add(source.enabled, label = tr(if (vm.interior.soloActiveFor(vm.state.value)) "interior.restoreView" else "interior.solo"), hint = tr("interior.soloTip")) {
				vm.interior.toggleSolo()
			}
		}
	}
	val apply = system.apply
	add(apply != null, label = tr("modifiers.applyKeyforms"), hint = if (apply != null) tr("modifiers.system.applyTip") else system.applyNote ?: tr("modifiers.system.applyNone")) {
		apply?.let { vm.modifiers.batch(listOf(it)) }
	}
	system.module?.let { module -> add(true, label = tr("modifiers.system.open.${system.kind}")) { vm.requestSelectDockModule(module) } }
	addMoves(vm.modifiers, ids, index) { label, enabled, action -> add(enabled, label = label, action = action) }
	system.delete?.let { op -> add(true, danger = true, label = tr("modifiers.delete")) { vm.modifiers.batch(listOf(op)) } }
	GlidingColumn(Modifier.width(236.dp), rows.size, { rows[it].first }, dangers = { rows[it].second }) { rows[it].third() }
}
