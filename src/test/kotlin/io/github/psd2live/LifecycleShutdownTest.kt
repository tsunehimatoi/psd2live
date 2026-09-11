package io.github.psd2live

import io.github.psd2live.agent.AgentMcpConfig
import io.github.psd2live.agent.AgentMcpService
import io.github.psd2live.agent.ViewModelAgentWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlin.test.Test
import kotlin.test.assertTrue

class LifecycleShutdownTest {

	@Test
	fun testViewModelCloseIsIdempotentAndClean() {
		val viewModel = PSD2LiveViewModel()
		// First close should succeed
		viewModel.close()
		// Repeated close must be safe and idempotent
		viewModel.close()
	}

	@Test
	fun testWorkspaceAndServiceShutdownCleanly() {
		val viewModel = PSD2LiveViewModel()
		val workspace = ViewModelAgentWorkspace(viewModel)
		viewModel.attachAgentWorkspace(workspace)

		val service = AgentMcpService(workspace, AgentMcpConfig(port = 24991))
		val info = service.start()
		assertTrue(info.endpoint.contains("24991"))

		// Both close calls should be clean and idempotent
		service.close()
		service.close()
		workspace.close()
		workspace.close()
		viewModel.close()
	}
}
