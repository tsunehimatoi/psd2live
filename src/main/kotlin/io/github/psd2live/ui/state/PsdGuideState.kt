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
    var splitHoveredComponent by mutableStateOf<Pair<String, Int>?>(null)
    private var appliedSplits: List<WorkspacePsdGuide.Split> = emptyList()
    val splitDraftValid get() = splitItems.all { it.isValid }
    val selectedSplitCount get() = splitItems.count { it.isSelected }
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
    var faceFlipGeneration by mutableStateOf(0)
        private set
    var faceFlipKeepIndex by mutableStateOf<Int?>(null)
        private set
    var faceReadyIndices by mutableStateOf<Set<Int>>(emptySet())
    var faceError by mutableStateOf<String?>(null)

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
        resetFields()
    }

    private fun resetFields() {
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
        splitHoveredComponent = null
        appliedSplits = emptyList()
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
        splitHoveredComponent = null
    }

    private fun currentSplitDecisions(): List<WorkspacePsdGuide.Split> =
        splitItems.filter { it.isSelected }.map { item ->
            val (names, sides) = item.generatedNamesAndSides()
            WorkspacePsdGuide.Split(item.offer.layerId, names, sides)
        }

    fun showSplitPreview(layerId: String?) {
        if (!busy && (layerId == null || splitItems.any { it.offer.layerId == layerId })) {
            splitFocus = layerId
            splitHoveredComponent = null
        }
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
            appliedSplits = decisions
            changeSource(candidate, report)
            setSplitItems(candidate, plans)
        }
    }

    fun resetSplits() {
        val guide = guide ?: return
        work("psdGuide.working") {
            val report = guide.bindings(guide.base, manual)
            val plans = guide.splits(guide.base)
            appliedSplits = emptyList()
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
        if (next == PsdGuideStep.FACE && batch.isEmpty()) {
            val defaultValues = preset?.let { candidate ->
                guide?.turn(candidate)?.let { turn -> guide?.turnValues(turn) }
            } ?: TurnStyleCandidates.defaultValues()
            batch = TurnStyleCandidates.initialBatch(defaultValues)
            selectedStyle = batch.firstOrNull()
            faceFlipGeneration = 0
            faceFlipKeepIndex = null
        }
    }

    /**
     * Advances from [PsdGuideStep.SPLIT] by applying any pending draft split selections first,
     * keeping [splitItems] intact so returning to step 1 preserves all user choices.
     */
    fun advanceFromSplit(requested: PsdGuideStep? = null) {
        if (busy || bindings == null || step != PsdGuideStep.SPLIT) return
        if (!splitDraftValid) return
        val guide = guide ?: return
        val decisions = if (splitScanned) currentSplitDecisions() else emptyList()
        if (!splitScanned || decisions == appliedSplits) {
            val target = requested?.takeIf { it in steps } ?: nextStep ?: return
            go(target)
            return
        }
        work("psdGuide.working") {
            val candidate = if (decisions.isEmpty()) guide.base else guide.split(guide.base, decisions)
            val report = guide.bindings(candidate, manual)
            appliedSplits = decisions
            changeSource(candidate, report)
            val target = requested?.takeIf { it in steps } ?: nextStep ?: PsdGuideStep.PRESET
            if (target == PsdGuideStep.BINDING) bindingVisited = true
            if (target > reached) reached = target
            step = target
            if (target == PsdGuideStep.PRESET && finalCandidate == null) {
                val choice = presetChoice(selection, WorkspaceModelPresetLibrary.shared.state.value)
                if (choice != null) {
                    val built = guide.preset(candidate, report, choice.first.id, choice.second)
                    val previous = preset
                    resetStyles()
                    preset = built
                    preparedSelection = selection
                    previous?.releaseIfUnused()
                }
            }
        }
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
            val anchorIndex = batch.indexOf(anchor).takeIf { it >= 0 }
                ?: batch.indexOfFirst { it.values == anchor.values }.takeIf { it >= 0 }
                ?: 0
            batch = TurnStyleCandidates.nearbyBatch(anchor, anchorIndex = anchorIndex)
            selectedStyle = batch[anchorIndex]
            faceFlipKeepIndex = anchorIndex
        } else {
            batch = TurnStyleCandidates.randomBatch()
            selectedStyle = null
            faceFlipKeepIndex = null
        }
        faceFlipGeneration += 1
    }

    fun updateFaceStyleValue(knobId: String, value: Float) {
        if (busy || batch.isEmpty()) return
        val index = selectedStyle?.let { sel ->
            batch.indexOf(sel).takeIf { it >= 0 } ?: batch.indexOfFirst { it.values == sel.values }
        }?.takeIf { it >= 0 } ?: 0
        val current = batch[index]
        val nextValues = current.values + (knobId to value)
        if (nextValues == current.values) return
        val updated = TurnStyleCandidates.Option(nextValues, current.distance)
        batch = batch.toMutableList().also { it[index] = updated }
        selectedStyle = updated
    }

    fun updateFaceStylePreset(presetIndex: Int) {
        if (busy || batch.isEmpty()) return
        val index = selectedStyle?.let { sel ->
            batch.indexOf(sel).takeIf { it >= 0 } ?: batch.indexOfFirst { it.values == sel.values }
        }?.takeIf { it >= 0 } ?: 0
        val current = batch[index]
        val nextValues = TurnStyleCandidates.defaultValues() + ("style" to presetIndex.toFloat())
        if (nextValues == current.values) return
        val updated = TurnStyleCandidates.Option(nextValues, current.distance)
        batch = batch.toMutableList().also { it[index] = updated }
        selectedStyle = updated
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

    fun installDefaults(
        host: WorkspaceHost<PSD2LiveState>,
        autoSplit: Boolean,
        before: suspend () -> WorkspaceExecution,
        onSuccess: () -> Unit,
    ) {
        val draft = guide ?: return
        val current = source ?: draft.base
        if (busy || saving) return
        saving = true
        work("psdGuide.working", retryable = false) {
            try {
                val candidate = prepareDefaultCandidate(draft, current, autoSplit)
                workLabel = "psdGuide.creating"
                withContext(before()) { host.installPsdGuide(draft, candidate, discardUnsaved = true) }
                onSuccess()
            } finally {
                saving = false
            }
        }
    }

    fun importWithDefaults(
        path: Path,
        outputDirectory: Path?,
        host: WorkspaceHost<PSD2LiveState>,
        autoSplit: Boolean,
        before: suspend () -> WorkspaceExecution,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        if (busy || saving || this.path != null) return
        this.path = path
        this.outputDirectory = outputDirectory
        saving = true
        work("psdGuide.reading", retryable = false) {
            var succeeded = false
            try {
                val draft = host.preparePsdGuide(path).also { guide = it; source = it.base }
                val candidate = prepareDefaultCandidate(draft, draft.base, autoSplit)
                workLabel = "psdGuide.creating"
                withContext(before()) { host.installPsdGuide(draft, candidate, discardUnsaved = true) }
                succeeded = true
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onError(workspaceFailureMessage(failure))
            } finally {
                saving = false
                if (!succeeded) {
                    val owners = listOfNotNull(styled, preset, source, guide?.base)
                        .mapNotNull { it.model.modifierBuildOwner }.distinct()
                    resetFields()
                    owners.filterNot { it.published }.forEach { it.close() }
                }
            }
        }
    }

    private suspend fun prepareDefaultCandidate(
        draft: WorkspacePsdGuide,
        base: WorkspacePsdGuide.Candidate,
        autoSplit: Boolean,
    ): WorkspacePsdGuide.Candidate {
        var current = base
        if (current === draft.base && (autoSplit || splitScanned)) {
            val decisions = if (splitScanned) {
                splitItems.map { WorkspacePsdGuide.defaultSplit(it.offer.layerId, it.offer.layerName, it.offer.plan) }
            } else {
                workLabel = "start.splits.scanning"
                draft.defaultSplits(current)
            }
            if (decisions.isNotEmpty()) {
                workLabel = "psdGuide.working"
                val splitCandidate = draft.split(current, decisions)
                val prevSource = source
                source = splitCandidate
                prevSource?.releaseIfUnused()
                splitItems = emptyList()
                splitScanned = true
                splitFocus = null
                current = splitCandidate
            }
        }
        workLabel = "psdGuide.working"
        val report = draft.bindings(current, manual)
        bindings = report
        manual = report.manual
        val entries = WorkspaceModelPresetLibrary.shared.state.value
        val defaultSelection = ModifierPresetMenuModel.initialSelection(null, entries)
        val choice = presetChoice(defaultSelection, entries) ?: error("No default preset available")
        val built = draft.preset(current, report, choice.first.id, choice.second)
        val prevPreset = preset
        resetStyles()
        preset = built
        preparedSelection = defaultSelection
        selection = defaultSelection
        prevPreset?.releaseIfUnused()
        return built
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
        faceFlipGeneration = 0
        faceFlipKeepIndex = null
        faceReadyIndices = emptySet()
        faceError = null
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
