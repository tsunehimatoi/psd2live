package io.github.psd2live.ui.state

import androidx.compose.runtime.*
import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.interior.*
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.CanvasEditor
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*

/** Project/canvas-scoped authoring drafts and temporary audition. No document solver lives here. */
internal class InteriorController(
	private val state: () -> PSD2LiveState,
	private val backend: () -> WorkspaceBackend?,
	private val scope: CoroutineScope,
	private val editorFor: (String) -> CanvasEditor,
	private val editVisibility: (CanvasVisibilityIntent) -> Unit,
	private val setView: (Float, Float, Float, String, CanvasMode) -> Unit,
) : AutoCloseable {
	fun put(request: JsonObject) = edit("interior_put", request)

	fun delete(id: String) {
		stopTrial()
		edit("interior_delete", buildJsonObject { put("target", id) })
	}

	fun poseFor(current: PSD2LiveState): Map<ParameterId, Float> =
		if (current.activeCanvas.mode == CanvasMode.EDIT && trialAddress == addressOf(current))
			trialValues
		else emptyMap()

	internal fun acceptsMouse(owner: PSD2LiveState): Boolean {
		if (!mouse || owner.activeCanvas.mode != CanvasMode.EDIT) return false
		val current = state()
		return current.activeCanvas.mode == CanvasMode.EDIT &&
			addressOf(owner) == addressOf(current)
	}

	internal fun pointer(owner: PSD2LiveState, effect: NativeInteriorEffect, x: Float, y: Float) {
		if (!acceptsMouse(owner) || !effect.enabled) return
		poseTarget = effect.motion.pointer(x, y)
		startTrial(effect)
	}

	fun modelFor(current: PSD2LiveState): PuppetModel? =
		if (poseFor(current).isEmpty()) null else trialPuppet

	override fun close() {
		editJob?.cancel()
		stopTrial()
		drafts.clear()
		soloViews.clear()
	}

	private data class Address(val generation: Long, val workspace: String, val canvas: String)

	private fun addressOf(s: PSD2LiveState) =
		Address(s.projectOpenGeneration, s.activeWorkspace.id, s.activeCanvas.id)

	private var trialAddress: Address? = null

	private data class Projection(
		val original: PuppetModel,
		val effect: NativeInteriorEffect,
		val puppet: PuppetModel,
		val address: Address,
	)

	private var draftProjection: Projection? = null

	internal suspend fun previewDraft(
		request: JsonObject,
		draft: InteriorDraft,
		version: Long,
	) {
		val captured = state()
		val preview = captured.previewModel ?: return
		val address = addressOf(captured)
		if (poseTarget == null || captured.activeCanvas.mode != CanvasMode.EDIT) return
		val overlay =
			withContext(Dispatchers.Default) {
				runInterruptible {
					NativeInteriorAuthoring.put(
						preview,
						preview.config.rigEdits,
						request,
					)
				}
			}
		val target = request.getValue("target").jsonPrimitive.content
		val effect = overlay.interiorEffects.single { it.target == target }
		val puppet =
			withContext(Dispatchers.Default) {
				preview.baseRig
					.withRigEdits(
						overlay,
						preview.config.layerVisibility,
						preview.config.drawOrderOverrides,
					)
					.puppet
			}
		val current = state()
		if (
			draft.version != version ||
				poseTarget == null ||
				current.previewModel !== preview ||
				addressOf(current) != address
		)
			return
		draftProjection = Projection(preview.rig.puppet, effect, puppet, address)
		startTrial(effect)
	}

	private val drafts = mutableMapOf<String, InteriorDraft>()

	private fun draftKey(target: String) =
		"${state().projectOpenGeneration}:${state().activeWorkspace.id}:$target"

	internal fun draft(
		target: String,
		effect: NativeInteriorEffect?,
	): InteriorDraft {
		val draft = drafts.getOrPut(draftKey(target)) { InteriorDraft(effect) }
		draft.sync(effect)
		return draft
	}

	internal var statusTarget: String? by mutableStateOf(null)
	internal var statusGeneration = -1L
	internal var busy by mutableStateOf(false)
	internal var error by mutableStateOf<String?>(null)
	internal var frameRequest by mutableStateOf(0)
	internal var mouse by mutableStateOf(false)
	internal var neutralBounds: FloatArray? = null
	internal var poseTarget by mutableStateOf<Pair<Float, Float>?>(null)
	internal var trialValues by mutableStateOf(emptyMap<ParameterId, Float>())
	private var editJob: Job? = null
	private var editRevision = 0L
	private var trialPuppet: PuppetModel? = null
	private var trialBase: PuppetModel? = null
	private var trialEditor: CanvasEditor? = null
	private var trialPhysics: PhysicsEngine? = null
	private var trialAliases = emptyList<Parameter>()
	private var trialEffect: NativeInteriorEffect? = null

	private data class SoloView(
		val generation: Long,
		val workspace: String,
		val canvas: String,
		val mode: CanvasMode,
		val camera: TabCamera,
		val visibility: CanvasVisibility,
	)

	private val soloViews = mutableStateMapOf<Address, SoloView>()

	internal fun soloActiveFor(state: PSD2LiveState) = addressOf(state) in soloViews

	private fun edit(operation: String, request: JsonObject) {
		val revision = ++editRevision
		val target = request.getValue("target").jsonPrimitive.content
		val workspace = backend() ?: return
		val expected = workspace.snapshot()
		statusTarget = target
		statusGeneration = state().projectOpenGeneration
		val originDraftKey = draftKey(target)
		val draft = drafts[originDraftKey]
		val version = draft?.version
		editJob?.cancel()
		error = null
		busy = true
		editJob = scope.launch {
			try {
				delay(180)
				withContext(
					Dispatchers.Default +
						WorkspaceExecution(
							expected.projectId,
							expected.state,
							MutationAuthor.USER,
						)
				) {
					workspace.applyDocumentEdits(
						expected.state,
						"Interior surface",
						listOf(
							WorkspaceDocumentOperation(
								operation,
								request,
							)
						),
						MutationAuthor.USER,
					)
				}
				if (operation == "interior_delete") {
					if (drafts[originDraftKey] === draft && draft?.version == version)
						drafts.remove(originDraftKey)
				} else if (draft?.version == version) draft?.dirty = false
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				if (revision == editRevision) error = e.message ?: e.toString()
			} finally {
				if (revision == editRevision) busy = false
			}
		}
	}

	internal fun startTrial(requested: NativeInteriorEffect) {
		val current = state()
		val original = current.previewModel?.rig?.puppet ?: return
		if (current.activeCanvas.mode != CanvasMode.EDIT) return
		val address = addressOf(current)
		val proposed = draftProjection?.takeIf {
			it.original === original &&
				it.address == address &&
				it.effect.target == requested.target
		}
		val effect = proposed?.effect ?: requested
		val puppet = proposed?.puppet ?: original
		val editor = editorFor(current.activeCanvas.id)
		if (trialAddress == address && trialBase === puppet && trialEffect === effect) return
		if (effect.issue(puppet) != null) return
		val previous = trialPhysics
		val previousAliases = trialAliases
		val previousValues = trialValues
		val previousEffect = trialEffect
		val previousAddress = trialAddress
		stopTrial(clearTarget = false)
		val aliases =
			effect.sources.mapIndexed { i, p -> p.copy(id = ParameterId("${effect.id}Trial$i")) }
		val names = effect.sources.mapIndexed { i, p -> p.id to aliases[i].id }.toMap()
		val projected =
			puppet.copy(
				parameters = puppet.parameters + aliases,
				drawables =
					puppet.drawables.map { d ->
						if (d.id.raw != effect.target) d
						else
							d.copy(
								blendShapes =
									d.blendShapes.mapIndexed { index, b ->
										if (index < d.blendShapes.size - effect.bindings.size) b
										else
											b.copy(
												parameterId = names[b.parameterId] ?: b.parameterId,
												limits =
													b.limits.map {
														it.copy(
															parameterId =
																names[it.parameterId]
																	?: it.parameterId
														)
													},
											)
									}
							)
					},
			)
		val groups =
			effect.groups.map { g ->
				g.copy(
					inputs =
						g.inputs.map {
							it.copy(
								parameter = names[ParameterId(it.parameter)]?.raw ?: it.parameter
							)
						}
				)
			}
		trialAddress = address
		trialAliases = aliases
		trialEffect = effect
		trialBase = puppet
		trialPuppet = projected
		trialEditor = editor
		trialPhysics =
			PhysicsEngine(
				groups,
				PhysicsEngine.ranges(projected.parameters),
				current.rigEdits.physicsFps.toFloat(),
			)
		val world =
			org.umamo.render.eval
				.CpuDeformationEvaluator()
				.evaluate(puppet, emptyMap())
				.worldPositions[DrawableId(effect.target)]
		neutralBounds = world?.let { q ->
			floatArrayOf(
				(q.indices step 2).minOf { q[it] },
				(1 until q.size step 2).minOf { q[it] },
				(q.indices step 2).maxOf { q[it] },
				(1 until q.size step 2).maxOf { q[it] },
			)
		}
		editor.preview = projected
		val carry =
			previousAddress == address &&
				previous != null &&
				previousEffect?.id == effect.id &&
				aliases == previousAliases
		if (carry) {
			trialPhysics!!.carryOver(previous!!)
			trialValues = previousValues
		}
		stepTrial(0f, reset = !carry)
	}

	internal fun stepTrial(dt: Float, reset: Boolean = false) {
		val target = poseTarget ?: return
		val effect = trialEffect ?: return
		val engine = trialPhysics ?: return
		val values =
			trialAliases
				.mapIndexed { i, p ->
					val v = if (i == 0) target.first else target.second
					p.id.raw to
						(p.default + v * (if (v < 0) p.default - p.min else p.max - p.default))
				}
				.toMap()
		val out =
			if (reset)
				engine.settle(
					values,
					maxOf(
						1f,
						(effect.motion.responseSeconds + effect.motion.transmissionSeconds) * 12,
					),
				)
			else engine.step(values, dt.coerceIn(0f, .1f))
		trialValues =
			(values + if (out.isEmpty()) trialValues.mapKeys { it.key.raw } else out).mapKeys {
				ParameterId(it.key)
			}
	}

	internal fun stopTrial(clearTarget: Boolean = true) {
		trialEditor?.let { if (it.preview === trialPuppet) it.preview = null }
		trialAddress = null
		trialEditor = null
		trialPuppet = null
		trialBase = null
		trialEffect = null
		trialPhysics = null
		trialValues = emptyMap()
		if (clearTarget) {
			poseTarget = null
			mouse = false
			draftProjection = null
		}
	}

	internal fun toggleSolo() {
		val current = state()
		val layer = current.selectedLayerId ?: return
		val address = addressOf(current)
		val saved = soloViews[address]
		if (
			saved != null &&
				saved.generation == current.projectOpenGeneration &&
				saved.workspace == current.activeWorkspace.id &&
				saved.canvas == current.activeCanvas.id &&
				saved.mode == current.activeCanvas.mode
		) {
			editVisibility(CanvasVisibilityIntent.Restore(saved.visibility))
			setView(
				saved.camera.zoom,
				saved.camera.panX,
				saved.camera.panY,
				saved.canvas,
				saved.mode,
			)
			soloViews.remove(address)
			frameRequest = 0
		} else {
			soloViews[address] =
				SoloView(
					current.projectOpenGeneration,
					current.activeWorkspace.id,
					current.activeCanvas.id,
					current.activeCanvas.mode,
					TabCamera(current.canvasZoom, current.canvasPanX, current.canvasPanY),
					CanvasVisibility(
						current.layerVisibility,
						current.deformerVisibility,
						current.isolatedLayerId,
						current.isolationSnapshot,
					),
				)
			editVisibility(CanvasVisibilityIntent.Solo(layer))
			frameRequest++
		}
	}
}
