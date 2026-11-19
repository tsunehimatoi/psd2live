package io.github.psd2live.ui.state

import androidx.compose.runtime.*
import io.github.psd2live.application.*
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.MeshComponentSplit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.BatchSplitItemState
import io.github.psd2live.ui.views.ModifierPresetMenuModel
import io.github.psd2live.ui.views.ModifierPresetSelection
import io.github.psd2live.ui.views.ModifierPresetSelectionStore
import io.github.psd2live.ui.views.presetChoice
import kotlinx.coroutines.*
import java.nio.file.Path

internal enum class PsdGuideStep { SPLIT, BINDING, PRESET, FACE }

private val stepsWithoutBinding = PsdGuideStep.entries - PsdGuideStep.BINDING

internal class PsdGuideState(private val scope: CoroutineScope) {
    var path by mutableStateOf<Path?>(null)
        private set
    var step by mutableStateOf(PsdGuideStep.SPLIT)
        private set
    var reached by mutableStateOf(PsdGuideStep.SPLIT)
        private set
    private var bindingVisited by mutableStateOf(false)
    val steps: List<PsdGuideStep>
        get() = if (!bindingVisited && bindings?.findings?.none { it.layers.isNotEmpty() } == true)
            stepsWithoutBinding else PsdGuideStep.entries
    val previousStep get() = steps.run { getOrNull(indexOf(step) - 1) }
    val nextStep get() = steps.run { getOrNull(indexOf(step) + 1) }
    private var workLabel by mutableStateOf<String?>(null)
    val busy get() = workLabel != null

    private data class StyleProgress(val owner: Any, val completed: Int, val total: Int)
    private var styleProgress by mutableStateOf<StyleProgress?>(null)
    val taskProgress: TaskProgress?
        get() = workLabel?.let { TaskProgress(tr(it)) }
            ?: styleProgress?.takeIf { step == PsdGuideStep.FACE && it.completed < it.total }?.let {
                TaskProgress(tr("turnExplorer.progress", it.completed, it.total), it.completed.toFloat() / it.total)
            }
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var confirmDiscard by mutableStateOf(false)
    private data class Input(val path: Path, val outputDirectory: Path?)
    private var replacement: Input? = null
    var outputDirectory: Path? = null
        private set
    private var retryAction by mutableStateOf<(() -> Unit)?>(null)
    val canRetry get() = retryAction != null
    private var job: Job? = null

    var guide by mutableStateOf<WorkspacePsdGuide?>(null)
        private set
    var source by mutableStateOf<WorkspacePsdGuide.Candidate?>(null)
        private set
    var splitItems by mutableStateOf(emptyList<BatchSplitItemState>())
        private set
    var splitScanned by mutableStateOf(false)
        private set
    var splitFocus by mutableStateOf<String?>(null)
        private set
    var bindingFocus by mutableStateOf<List<String>>(emptyList())
    var bindings by mutableStateOf<PsdGuideBindings.Result?>(null)
        private set
    private var manual = emptyMap<String, LayerClassificationOverride>()

    var selection by mutableStateOf(initialPreset())
        private set
    var preset by mutableStateOf<WorkspacePsdGuide.Candidate?>(null)
        private set
    private var styled: WorkspacePsdGuide.Candidate? = null
    val finalCandidate get() = if (preparedSelection == selection) styled ?: preset else null
    private var preparedSelection by mutableStateOf<ModifierPresetSelection?>(null)
    var appliedStyle by mutableStateOf<TurnStyleCandidates.Option?>(null)
        private set
    var batch by mutableStateOf<List<TurnStyleCandidates.Option>>(emptyList())
        private set
    var selectedStyle by mutableStateOf<TurnStyleCandidates.Option?>(null)
    var turnPose by mutableStateOf<Pair<Float, Float>?>(null)

    fun request(path: Path, outputDirectory: Path?, host: WorkspaceHost<PSD2LiveState>) {
        if (saving) return
        if (this.path != null) {
            if (this.path != path) {
                replacement = Input(path, outputDirectory)
                confirmDiscard = true
            }
        } else begin(Input(path, outputDirectory), host)
    }

    fun requestClose() {
        if (!saving) {
            replacement = null
            confirmDiscard = true
        }
    }

    fun discard(host: WorkspaceHost<PSD2LiveState>?): Boolean {
        val next = replacement
        clear()
        if (next != null && host != null) {
            begin(next, host)
            return false
        }
        return true
    }

    fun clear() {
        val running = job
        val owners = listOfNotNull(styled, preset, source, guide?.base)
            .mapNotNull { it.model.modifierBuildOwner }.distinct()
        running?.cancel()
        if (running == null) owners.filterNot { it.published }.forEach { it.close() }
        else running.invokeOnCompletion { owners.filterNot { it.published }.forEach { it.close() } }
        job = null
        path = null
        outputDirectory = null
        workLabel = null
        saving = false
        error = null
        confirmDiscard = false
        replacement = null
        retryAction = null
        styled = null
        preset = null
        resetPreset()
        guide = null
        source = null
        splitItems = emptyList()
        splitScanned = false
        splitFocus = null
        bindingFocus = emptyList()
        manual = emptyMap()
        bindings = null
        step = PsdGuideStep.SPLIT
        reached = step
        bindingVisited = false
        selection = initialPreset()
    }

    private fun begin(input: Input, host: WorkspaceHost<PSD2LiveState>) {
        val path = input.path
        this.path = path
        outputDirectory = input.outputDirectory
        work("psdGuide.reading") {
            val prepared = guide ?: host.preparePsdGuide(path).also {
                guide = it
                source = it.base
            }
            if (bindings == null) bindings = prepared.bindings(prepared.base, manual)
            if (AppSettings.autoDetectMeshSplitsOnImport && !splitScanned) {
                workLabel = "start.splits.scanning"
                scanNow(prepared, prepared.base)
            }
        }
    }

    suspend fun awaitIdle() { job?.join() }

    fun retry() = retryAction?.invoke()

    fun scan() {
        val guide = guide ?: return
        val source = source ?: return
        work("start.splits.scanning") { scanNow(guide, source) }
    }

    private suspend fun scanNow(guide: WorkspacePsdGuide, source: WorkspacePsdGuide.Candidate) {
        setSplitItems(source, guide.splits(source))
        splitScanned = true
    }

    private fun setSplitItems(source: WorkspacePsdGuide.Candidate, plans: Map<String, MeshComponentSplit.Plan>) {
        splitItems = plans.map { (id, plan) ->
            val layer = source.model.analysis.layers.first { it.source.id.raw == id }
            BatchSplitItemState(PSD2LiveViewModel.MeshSplitOffer(id, layer.source.name, plan, source.model, null, true))
        }
        splitFocus = null
    }

    fun showSplitPreview(layerId: String?) {
        if (!busy && (layerId == null || splitItems.any { it.offer.layerId == layerId })) splitFocus = layerId
    }

    fun split(items: List<BatchSplitItemState>) {
        val guide = guide ?: return
        val source = source ?: return
        val selected = items.filter { it.isSelected }
        if (selected.isEmpty() || selected.any { !it.isValid }) return
        val decisions = selected.map { item ->
            val (names, sides) = item.generatedNamesAndSides()
            WorkspacePsdGuide.Split(item.offer.layerId, names, sides)
        }
        work("psdGuide.working") {
            val candidate = guide.split(source, decisions)
            val report = guide.bindings(candidate, manual)
            val plans = guide.splits(candidate)
            changeSource(candidate, report)
            setSplitItems(candidate, plans)
        }
    }

    fun resetSplits() {
        val guide = guide ?: return
        work("psdGuide.working") {
            val report = guide.bindings(guide.base, manual)
            val plans = guide.splits(guide.base)
            changeSource(guide.base, report)
            setSplitItems(guide.base, plans)
        }
    }

    private fun changeSource(candidate: WorkspacePsdGuide.Candidate, report: PsdGuideBindings.Result) {
        resetPreset()
        val previous = source
        source = candidate
        previous?.releaseIfUnused()
        bindings = report
        manual = report.manual
        bindingFocus = bindingFocus.filter { id -> report.layers.any { it.source.id.raw == id } }
        reached = PsdGuideStep.SPLIT
        bindingVisited = false
    }

    fun classify(id: String, value: LayerClassificationOverride) {
        val guide = guide ?: return
        val source = source ?: return
        work("psdGuide.working") {
            val report = guide.bindings(source, manual + (id to value))
            resetPreset()
            manual = report.manual
            bindings = report
            reached = PsdGuideStep.BINDING
        }
    }

    fun go(next: PsdGuideStep) {
        val route = steps
        val nextIndex = route.indexOf(next)
        if (busy || bindings == null || nextIndex < 0 || nextIndex > route.indexOf(reached) + 1 ||
            next == PsdGuideStep.FACE && finalCandidate == null) return
        if (next == PsdGuideStep.BINDING) bindingVisited = true
        if (next > reached) reached = next
        step = next
        if (next == PsdGuideStep.PRESET && finalCandidate == null) selectPreset(selection)
        if (next == PsdGuideStep.FACE && batch.isEmpty()) batch = TurnStyleCandidates.randomBatch()
    }

    fun reviewAutomaticBindings() {
        if (busy || step != PsdGuideStep.PRESET || bindings?.automatic?.isNotEmpty() != true) return
        bindingVisited = true
        go(PsdGuideStep.BINDING)
    }

    fun selectPreset(value: ModifierPresetSelection) {
        val guide = guide ?: return
        val source = source ?: return
        val report = bindings ?: return
        val choice = presetChoice(value, WorkspaceModelPresetLibrary.shared.state.value) ?: return
        if (busy || value == preparedSelection && value == selection) return
        selection = value
        reached = minOf(reached, PsdGuideStep.PRESET)
        work("psdGuide.working") {
            if (value != preparedSelection) {
                val candidate = guide.preset(source, report, choice.first.id, choice.second)
                val previous = preset
                resetStyles()
                preset = candidate
                preparedSelection = value
                previous?.releaseIfUnused()
            }
        }
    }

    fun trackStyleProgress(owner: Any, options: List<TurnStyleCandidates.Option>, completed: Int) {
        if (step == PsdGuideStep.FACE && batch === options) {
            styleProgress = StyleProgress(owner, completed, options.size).takeIf { completed < options.size }
        }
    }

    fun clearStyleProgress(owner: Any) {
        if (styleProgress?.owner === owner) styleProgress = null
    }

    fun newStyles(nearby: Boolean) {
        styleProgress = null
        val anchor = selectedStyle
        if (nearby && anchor != null) {
            batch = TurnStyleCandidates.nearbyBatch(anchor)
            selectedStyle = batch[4]
        } else {
            batch = TurnStyleCandidates.randomBatch()
            selectedStyle = null
        }
    }

    fun applyStyle() {
        val guide = guide ?: return
        val candidate = preset ?: return
        val turn = guide.turn(candidate) ?: return
        val choice = selectedStyle ?: return
        if (choice == appliedStyle) return
        work("turnExplorer.applying") {
            val next = guide.withStyle(candidate, turn, choice.values)
            val previous = styled
            styled = next
            previous?.releaseIfUnused()
            appliedStyle = choice
        }
    }

    fun keepPresetStyle() {
        val previous = styled
        styled = null
        previous?.releaseIfUnused()
        appliedStyle = null
    }

    fun install(host: WorkspaceHost<PSD2LiveState>, before: suspend () -> WorkspaceExecution, onSuccess: () -> Unit) {
        val guide = guide ?: return
        val candidate = finalCandidate ?: return
        if (busy || saving) return
        saving = true
        work("psdGuide.creating", retryable = false) {
            try {
                withContext(before()) { host.installPsdGuide(guide, candidate, discardUnsaved = true) }
                onSuccess()
            } finally {
                saving = false
            }
        }
    }

    private fun resetPreset() {
        resetStyles()
        val previous = preset
        preset = null
        preparedSelection = null
        previous?.releaseIfUnused()
    }

    private fun resetStyles() {
        styleProgress = null
        keepPresetStyle()
        batch = emptyList()
        selectedStyle = null
        turnPose = null
    }

    private fun WorkspacePsdGuide.Candidate.releaseIfUnused() {
        val owner = model.modifierBuildOwner ?: return
        val active = listOfNotNull(styled, preset, source, guide?.base)
        if (!owner.published && active.none { it.model.modifierBuildOwner === owner }) owner.close()
    }

    private fun work(label: String, retryable: Boolean = true, action: suspend () -> Unit) {
        if (busy) return
        workLabel = label
        error = null
        retryAction = null
        val running = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (job === currentCoroutineContext()[Job]) {
                    error = workspaceFailureMessage(failure)
                    if (retryable) retryAction = { work(label, action = action) }
                }
            } finally {
                if (job === currentCoroutineContext()[Job]) {
                    workLabel = null
                    job = null
                }
            }
        }
        job = running
        running.start()
    }

    private fun initialPreset(): ModifierPresetSelection {
        val entries = WorkspaceModelPresetLibrary.shared.state.value
        val saved = ModifierPresetSelectionStore.shared.load()
        return saved?.takeIf { presetChoice(it, entries) != null } ?: ModifierPresetMenuModel.initialSelection(null, entries)
    }
}
