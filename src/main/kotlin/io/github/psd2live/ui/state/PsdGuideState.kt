package io.github.psd2live.ui.state

import androidx.compose.runtime.*
import io.github.psd2live.application.*
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.MeshComponentSplit
import io.github.psd2live.ui.components.BatchSplitItemState
import io.github.psd2live.ui.views.ModifierPresetMenuModel
import io.github.psd2live.ui.views.ModifierPresetSelection
import io.github.psd2live.ui.views.ModifierPresetSelectionStore
import io.github.psd2live.ui.views.presetChoice
import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.nio.file.Path

internal enum class PsdGuideStep { SPLIT, BINDING, PRESET, FACE }

internal class PsdGuideState(private val scope: CoroutineScope) {
    var path by mutableStateOf<Path?>(null)
        private set
    var step by mutableStateOf(PsdGuideStep.SPLIT)
        private set
    var reached by mutableStateOf(PsdGuideStep.SPLIT)
        private set
    var busy by mutableStateOf(false)
        private set
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
    var bindingFocus by mutableStateOf<List<String>>(emptyList())
    var bindings by mutableStateOf<PsdGuideBindings.Result?>(null)
        private set
    private var manual = emptyMap<String, LayerClassificationOverride>()

    var selection by mutableStateOf(initialPreset())
        private set
    var preset by mutableStateOf<WorkspacePsdGuide.Candidate?>(null)
        private set
    var presetImage by mutableStateOf<BufferedImage?>(null)
        private set
    private var styled: WorkspacePsdGuide.Candidate? = null
    val finalCandidate get() = styled ?: preset
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
        busy = false
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
        selection = initialPreset()
    }

    private fun begin(input: Input, host: WorkspaceHost<PSD2LiveState>) {
        val path = input.path
        this.path = path
        outputDirectory = input.outputDirectory
        work {
            val prepared = guide ?: host.preparePsdGuide(path).also {
                guide = it
                source = it.base
            }
            if (bindings == null) bindings = prepared.bindings(prepared.base, manual)
            if (AppSettings.autoDetectMeshSplitsOnImport && !splitScanned) scanNow(prepared, prepared.base)
        }
    }

    suspend fun awaitIdle() { job?.join() }

    fun retry() = retryAction?.invoke()

    fun scan() {
        val guide = guide ?: return
        val source = source ?: return
        work { scanNow(guide, source) }
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
        splitFocus = splitItems.firstOrNull()?.offer?.layerId
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
        work {
            val candidate = guide.split(source, decisions)
            val report = guide.bindings(candidate, manual)
            val plans = guide.splits(candidate)
            changeSource(candidate, report)
            setSplitItems(candidate, plans)
        }
    }

    fun resetSplits() {
        val guide = guide ?: return
        work {
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
    }

    fun classify(id: String, value: LayerClassificationOverride) {
        val guide = guide ?: return
        val source = source ?: return
        work {
            val report = guide.bindings(source, manual + (id to value))
            resetPreset()
            manual = report.manual
            bindings = report
            reached = PsdGuideStep.BINDING
        }
    }

    fun go(next: PsdGuideStep) {
        if (busy || bindings == null || next.ordinal > reached.ordinal + 1 ||
            next == PsdGuideStep.FACE && preset == null) return
        if (next > reached) reached = next
        step = next
        if (next == PsdGuideStep.PRESET && preset == null) selectPreset(selection)
        if (next == PsdGuideStep.FACE && batch.isEmpty()) batch = TurnStyleCandidates.randomBatch()
    }

    fun selectPreset(value: ModifierPresetSelection) {
        val guide = guide ?: return
        val source = source ?: return
        val report = bindings ?: return
        val choice = presetChoice(value, WorkspaceModelPresetLibrary.shared.state.value) ?: return
        if (selection == value && preset != null) return
        selection = value
        resetPreset()
        reached = minOf(reached, PsdGuideStep.PRESET)
        work {
            val candidate = guide.preset(source, report, choice.first.id, choice.second)
            try {
                presetImage = guide.modelImage(candidate)
                preset = candidate
            } finally {
                if (preset !== candidate) candidate.releaseIfUnused()
            }
        }
    }

    fun newStyles(nearby: Boolean) {
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
        work {
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
        work(retryable = false) {
            try {
                withContext(before()) { host.installPsdGuide(guide, candidate, discardUnsaved = true) }
                onSuccess()
            } finally {
                saving = false
            }
        }
    }

    private fun resetPreset() {
        keepPresetStyle()
        val previous = preset
        preset = null
        previous?.releaseIfUnused()
        presetImage = null
        batch = emptyList()
        selectedStyle = null
        turnPose = null
    }

    private fun WorkspacePsdGuide.Candidate.releaseIfUnused() {
        val owner = model.modifierBuildOwner ?: return
        val active = listOfNotNull(styled, preset, source, guide?.base)
        if (!owner.published && active.none { it.model.modifierBuildOwner === owner }) owner.close()
    }

    private fun work(retryable: Boolean = true, action: suspend () -> Unit) {
        if (busy) return
        busy = true
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
                    if (retryable) retryAction = { work(action = action) }
                }
            } finally {
                if (job === currentCoroutineContext()[Job]) {
                    busy = false
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
