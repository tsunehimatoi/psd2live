package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceBackend
import io.github.psd2live.core.interior.*
import io.github.psd2live.project.WorkspaceMutationResult
import io.github.psd2live.project.WorkspaceProjectSnapshot
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import org.umamo.runtime.model.*

class InteriorControllerTest {
	private fun controller(
		state: () -> PSD2LiveState,
		scope: CoroutineScope,
		backend: WorkspaceBackend? = null,
	) =
		InteriorController(
			state,
			{ backend },
			scope,
			{ error("This test must not create an editor") },
			{},
			{ _, _, _, _, _ -> },
		)

	@Test
	fun aCompletedDeleteCannotClearNewOrUpdatedTargetDrafts() = runBlocking {
		for (switchTo in listOf("project", "workspace", "same")) {
			var current = PSD2LiveState()
			val entered = CompletableDeferred<Unit>()
			val release = CountDownLatch(1)
			val snapshot =
				WorkspaceProjectSnapshot(
					"original",
					"revision",
					loaded = true,
					inputName = null,
					canvasWidth = 1,
					canvasHeight = 1,
					busy = false,
					status = "",
					selectedLayerId = null,
					layers = emptyList(),
					parameters = emptyList(),
					state = "original-state",
				)
			val backend =
				Proxy.newProxyInstance(
					WorkspaceBackend::class.java.classLoader,
					arrayOf(WorkspaceBackend::class.java),
				) { _, method, _ ->
					when (method.name) {
						"snapshot" -> snapshot
						"applyDocumentEdits" -> {
							// The original commit has completed; its response waits while the
							// presentation switches.
							entered.complete(Unit)
							check(release.await(10, TimeUnit.SECONDS))
							WorkspaceMutationResult("node", "revision", summary = "delete")
						}
						else -> error("Unexpected backend call: ${method.name}")
					}
				} as WorkspaceBackend
			val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
			val c = controller({ current }, scope, backend)
			try {
				val originState = current
				val originDraft = c.draft("SameMesh", null)
				originDraft.shape = InteriorSurfaceSettings(horizontal = .3f)
				c.delete("SameMesh")
				withTimeout(10000) { entered.await() }
				current =
					when (switchTo) {
						"project" ->
							current.copy(projectOpenGeneration = current.projectOpenGeneration + 1)
						"workspace" ->
							current.copy(
								activeWorkspaceId =
									current.workspaces
										.first { it.id != current.activeWorkspaceId }
										.id
							)
						else -> current
					}
				val nextDraft = c.draft("SameMesh", null)
				nextDraft.shape = InteriorSurfaceSettings(horizontal = .8f)
				release.countDown()
				withTimeout(10000) { while (c.busy) delay(5) }
				assertNull(c.error)
				assertSame(
					nextDraft,
					c.draft("SameMesh", null),
					"a late original reply must not erase the new scope",
				)
				assertTrue(nextDraft.dirty)
				current = originState
				if (switchTo != "same")
					assertNotSame(
						originDraft,
						c.draft("SameMesh", null),
						"the originating deleted draft still must be removed",
					)
			} finally {
				release.countDown()
				c.close()
				scope.cancel()
			}
		}
	}

	private fun effect(): NativeInteriorEffect {
		val sources = listOf("X", "Y").map { Parameter(ParameterId(it), it, -1f, 1f, 0f) }
		val form = MeshForm(FloatArray(6), 0f, 1f)
		return NativeInteriorEffect(
			"effect",
			"mesh",
			"mesh",
			true,
			InteriorSurfaceSettings(),
			InteriorMotionSettings(sensitivityX = .4f, sensitivityY = .8f),
			sources,
			emptySet(),
			emptyList(),
			listOf(
				BlendShapeBinding(
					sources[0].id,
					floatArrayOf(-1f, 0f, 1f),
					1,
					listOf(form, null, form),
				)
			),
			emptyList(),
			"mesh-stamp",
			emptyList(),
			0.0,
			4.0,
		)
	}

	@Test
	fun pointerRoutingRejectsInactiveCanvasesStaleProjectsAndPreviewMode() {
		val initial = PSD2LiveState()
		val workspace =
			initial.activeWorkspace.copy(
				canvases = listOf(CanvasWindowState("first"), CanvasWindowState("second")),
				activeCanvasId = "first",
			)
		var current = initial.copy(workspaces = listOf(workspace), activeWorkspaceId = workspace.id)
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		val c = controller({ current }, scope)
		val effect = effect()
		try {
			c.mouse = true
			val first = current.forCanvas("first")
			c.pointer(first, effect, .5f, -.5f)
			assertEquals(.2f to -.4f, c.poseTarget)
			c.pointer(current.forCanvas("second"), effect, -1f, 1f)
			assertEquals(.2f to -.4f, c.poseTarget)
			current = current.copy(workspaces = listOf(workspace.copy(activeCanvasId = "second")))
			c.pointer(first, effect, -1f, 1f)
			assertEquals(.2f to -.4f, c.poseTarget)
			val second = current.forCanvas("second")
			c.pointer(second, effect, -1f, 1f)
			assertEquals(-.4f to .8f, c.poseTarget)
			current = current.copy(projectOpenGeneration = current.projectOpenGeneration + 1)
			c.pointer(second, effect, 0f, 0f)
			assertEquals(-.4f to .8f, c.poseTarget)
			val preview =
				current.copy(
					workspaces =
						listOf(
							current.activeWorkspace.copy(
								canvases = listOf(CanvasWindowState("second", CanvasMode.PREVIEW))
							)
						)
				)
			current = preview
			c.pointer(preview, effect, 0f, 0f)
			assertEquals(-.4f to .8f, c.poseTarget)
			assertFalse(c.acceptsMouse(preview))
		} finally {
			c.close()
			scope.cancel()
		}
	}
}
