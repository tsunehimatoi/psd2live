package io.github.psd2live.ui.state

import kotlin.test.*

class BatchMeshSplitViewModelTest {

    @Test
    fun autoDetectPreferenceSettingCanBeToggledAndReset() {
        val original = AppSettings.autoDetectMeshSplitsOnImport
        try {
            PSD2LiveViewModel().use { vm ->
                vm.setAutoDetectMeshSplitsOnImport(false)
                assertFalse(AppSettings.autoDetectMeshSplitsOnImport)
                assertFalse(vm.state.value.autoDetectMeshSplitsOnImport)

                vm.setAutoDetectMeshSplitsOnImport(true)
                assertTrue(AppSettings.autoDetectMeshSplitsOnImport)
                assertTrue(vm.state.value.autoDetectMeshSplitsOnImport)

                vm.setAutoDetectMeshSplitsOnImport(false)
                vm.resetInteractionPrefs()
                assertTrue(AppSettings.autoDetectMeshSplitsOnImport)
                assertTrue(vm.state.value.autoDetectMeshSplitsOnImport)
            }
        } finally {
            AppSettings.autoDetectMeshSplitsOnImport = original
        }
    }

    @Test
    fun dismissAllMeshSplitsClearsAllPendingOffersAndQueue() {
        PSD2LiveViewModel().use { vm ->
            vm.dismissAllMeshSplits()
            assertNull(vm.pendingMeshSplit)
            assertNull(vm.pendingBatchMeshSplit)
        }
    }

    @Test
    fun disabledAutoDetectSkipsImportScanning() {
        val original = AppSettings.autoDetectMeshSplitsOnImport
        try {
            AppSettings.autoDetectMeshSplitsOnImport = false
            PSD2LiveViewModel().use { vm ->
                vm.offerImportMeshSplit(listOf("layer-1", "layer-2"))
                assertNull(vm.pendingMeshSplit)
                assertNull(vm.pendingBatchMeshSplit)
            }
        } finally {
            AppSettings.autoDetectMeshSplitsOnImport = original
        }
    }
}
