package com.papacasper.squeeze

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * The "Squeeze to last size" share target (activity-alias `.QuickShare`): once the shared file is ready (probed,
 * so the won't-fit check can run) or the batch is listed, compress it to the last preset with no extra tap.
 */
@Composable
internal fun QuickShareEffect(
    vm: CompressorViewModel,
    state: UiState,
    onToast: (String) -> Unit,
    onFile: (UiState.FileSelected, Long, String) -> Unit,
    onBatch: (List<BatchFile>, Long, String) -> Unit
) {
    LaunchedEffect(state, vm.probed, vm.autoCompress) {
        if (!vm.autoCompress) return@LaunchedEffect
        val ready = when (state) {
            is UiState.FileSelected -> vm.probed
            is UiState.BatchSelected -> true
            is UiState.Failed -> { vm.autoCompress = false; false }
            else -> false
        }
        if (!ready) return@LaunchedEffect
        vm.autoCompress = false
        val preset = vm.lastPreset
        if (preset == null) {
            onToast("Pick a size once; next time this share option uses it straight away")
            return@LaunchedEffect
        }
        val label = "${preset.label} (${preset.short})"
        when (state) {
            is UiState.FileSelected -> onFile(state, preset.bytes, label)
            is UiState.BatchSelected -> onBatch(state.files, preset.bytes, label)
            else -> {}
        }
    }
}
