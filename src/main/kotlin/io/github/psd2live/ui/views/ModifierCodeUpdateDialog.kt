package io.github.psd2live.ui.views

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.modifier.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path

private enum class CodeUpdateStage {
    IDLE,
    BUILDING,
    EVALUATING,
    APPLYING,
    DONE,
    FAILED,
}

@Composable
internal fun ModifierCodeUpdateDialog(
    instance: RigModifierInstance,
    stack: RigModifierStack,
    state: PSD2LiveState,
    vm: PSD2LiveViewModel,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val scope = rememberCoroutineScope()

    val pluginId = instance.plugin?.id
    val matchingInstances = remember(stack, pluginId, instance.id) {
        if (pluginId == null) listOf(instance)
        else stack.instances.filter { it.plugin?.id == pluginId }
    }
    var applyAllMatching by remember(instance.id) { mutableStateOf(matchingInstances.size > 1) }

    val devSources = remember { PluginDevSourceStore.shared.list() }
    var selectedSourceId by remember(instance.id) {
        mutableStateOf(
            devSources.firstOrNull { pluginId != null && pluginId in it.packageIds }?.sourceId
                ?: devSources.firstOrNull()?.sourceId
        )
    }
    var customSourcePath by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(CodeUpdateStage.IDLE) }
    var diagnostics by remember { mutableStateOf<List<PluginDevDiagnostic>>(emptyList()) }
    var logRef by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    val running = stage == CodeUpdateStage.BUILDING || stage == CodeUpdateStage.EVALUATING || stage == CodeUpdateStage.APPLYING
    val targetInstances = if (applyAllMatching) matchingInstances else listOf(instance)

    fun startUpdate() {
        if (running) return
        diagnostics = emptyList()
        logRef = null
        errorMessage = null
        stage = CodeUpdateStage.BUILDING

        activeJob = scope.launch {
            try {
                val sourceId = withContext(Dispatchers.IO) {
                    if (customSourcePath.isNotBlank()) {
                        PluginDevSourceStore.shared.register(
                            source = Path.of(customSourcePath.trim()),
                            mode = PluginDevSourceMode.MANAGED_BUILD,
                        ).sourceId
                    } else {
                        selectedSourceId ?: throw IllegalArgumentException(tr("modifier.codeUpdate.noSource"))
                    }
                }

                val buildResult = runInterruptible(Dispatchers.IO) {
                    PluginDevBuilder.build(sourceId = sourceId)
                }
                diagnostics = buildResult.diagnostics
                logRef = buildResult.logRef

                stage = CodeUpdateStage.EVALUATING
                val artifact = buildResult.artifact
                val newRef = artifact.packages.firstOrNull { pkg -> pluginId == null || pkg.id == pluginId }
                    ?: artifact.packages.firstOrNull()
                    ?: throw IllegalStateException(tr("modifier.codeUpdate.noPackage"))
                val newAlg = artifact.manifest.algorithms.firstOrNull {
                    it.type == instance.type || instance.type in it.aliases
                } ?: throw IllegalStateException(tr("modifier.codeUpdate.noAlgorithm", instance.type))

                // Validate existing configs against the candidate schema before committing.
                for (target in targetInstances) {
                    ModifierPluginSchema.validate(target.config, newAlg.configSchema)
                }

                stage = CodeUpdateStage.APPLYING
                val expected = vm.modifierEditState()
                val operations = targetInstances.map { target ->
                    io.github.psd2live.application.WorkspaceDocumentOperation(
                        operation = "modifier_rebind",
                        request = buildJsonObject {
                            put("id", target.id)
                            put("plugin", Json.encodeToJsonElement(ModifierPluginReference.serializer(), newRef))
                            put("version", newAlg.version)
                        },
                    )
                }
                val deferred = CompletableDeferred<String?>()
                vm.editModifiers(
                    operations = operations,
                    expectedState = expected,
                    onComplete = { deferred.complete(it) },
                )
                val failure = deferred.await()
                if (failure != null) throw IllegalStateException(failure)
                runInterruptible(Dispatchers.IO) {
                    val projectKey = state.projectFile ?: state.projectId ?: "workspace"
                    PluginDevArtifactStore.adopt(artifact.artifactId, projectKey)
                }
                stage = CodeUpdateStage.DONE
                onDismiss()
            } catch (cancelled: CancellationException) {
                stage = CodeUpdateStage.IDLE
                throw cancelled
            } catch (error: PluginDevException) {
                diagnostics = error.diagnostics
                logRef = error.logRef
                errorMessage = error.message
                stage = CodeUpdateStage.FAILED
            } catch (error: Exception) {
                errorMessage = error.message ?: tr("modifier.codeUpdate.failed")
                stage = CodeUpdateStage.FAILED
            } finally {
                activeJob = null
            }
        }
    }

    ModalDialogFrame(
        width = 480.dp,
        title = tr("modifier.codeUpdate.title"),
        subtitle = "${modifierName(instance.type)} (${instance.id})",
        onDismiss = { if (!running) onDismiss() },
        dismissible = !running,
        tone = when (stage) {
            CodeUpdateStage.FAILED -> ModalTone.ERROR
            CodeUpdateStage.DONE -> ModalTone.SUCCESS
            else -> ModalTone.NONE
        },
        footer = {
            if (running) {
                CompactButton(
                    text = tr("action.cancel"),
                    onClick = { activeJob?.cancel() },
                )
            } else {
                CompactButton(
                    text = tr("action.cancel"),
                    onClick = onDismiss,
                )
                CompactButton(
                    text = tr("modifier.codeUpdate.apply"),
                    onClick = ::startUpdate,
                    enabled = customSourcePath.isNotBlank() || selectedSourceId != null,
                    isPrimary = true,
                )
            }
        },
    ) {
        // Scope display
        InspectorFormRow(tr("modifier.codeUpdate.scope")) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tr("modifier.codeUpdate.scopeSummary", targetInstances.size, targetInstances.joinToString { it.id }),
                    style = typography.caption,
                    color = colors.textPrimary,
                )
                if (matchingInstances.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactCheckbox(
                            checked = applyAllMatching,
                            onCheckedChange = { applyAllMatching = it },
                            enabled = !running,
                        )
                        Text(
                            text = tr("modifier.codeUpdate.applyAllMatching", matchingInstances.size),
                            style = typography.caption,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }

        // Dev source selection
        if (devSources.isNotEmpty()) {
            InspectorFormRow(tr("modifier.codeUpdate.source")) {
                CompactDropdown(
                    items = devSources.map { it.sourceId },
                    selectedItem = selectedSourceId ?: devSources.first().sourceId,
                    onItemSelected = { selectedSourceId = it },
                    itemLabel = { id ->
                        devSources.firstOrNull { it.sourceId == id }?.let {
                            "${it.source.fileName ?: it.source} (${it.mode.name.lowercase()})"
                        } ?: id
                    },
                    enabled = !running && customSourcePath.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        InspectorFormRow(tr("modifier.codeUpdate.customDir")) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactTextField(
                    value = customSourcePath,
                    onValueChange = { customSourcePath = it },
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                )
                CompactButton(
                    text = tr("plugins.browse"),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                NativeFilePicker.chooseDirectory(title = tr("modifier.codeUpdate.title"))
                            }?.let { customSourcePath = it }
                        }
                    },
                    enabled = !running,
                )
            }
        }

        // Progress stage display
        if (stage != CodeUpdateStage.IDLE) {
            val stageKey = when (stage) {
                CodeUpdateStage.BUILDING -> "modifier.codeUpdate.stage.building"
                CodeUpdateStage.EVALUATING -> "modifier.codeUpdate.stage.evaluating"
                CodeUpdateStage.APPLYING -> "modifier.codeUpdate.stage.applying"
                CodeUpdateStage.DONE -> "modifier.codeUpdate.stage.done"
                CodeUpdateStage.FAILED -> "modifier.codeUpdate.stage.failed"
                CodeUpdateStage.IDLE -> "modifier.codeUpdate.stage.idle"
            }
            Text(
                text = tr(stageKey),
                style = typography.body.copy(fontWeight = FontWeight.Medium),
                color = if (stage == CodeUpdateStage.FAILED) colors.error else colors.textPrimary,
            )
        }

        errorMessage?.let { msg ->
            InspectorHint(msg, warning = true)
        }

        if (diagnostics.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (diag in diagnostics) {
                    val file = diag.details["file"]?.jsonPrimitive?.contentOrNull
                    val line = diag.details["line"]?.jsonPrimitive?.contentOrNull
                    val col = diag.details["column"]?.jsonPrimitive?.contentOrNull
                    val location = listOfNotNull(file, line?.let { ":$it" }, col?.let { ":$it" }).joinToString("")
                    val text = if (location.isNotEmpty()) "$location — ${diag.message}" else diag.message
                    Text(
                        text = text,
                        style = typography.caption,
                        color = if (diag.severity == "error") colors.error else colors.warning,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        logRef?.let { ref ->
            InspectorHint(tr("modifier.codeUpdate.logRef", ref))
        }
    }
}
