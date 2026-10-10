package com.papacasper.squeeze

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * A target the selected video can't reach at the current floors. [minBytes] is the smallest it can get;
 * [parts] > 1 is a split that would fit; [lowerHeight] is the next resolution floor down, if any.
 */
internal data class UnreachableTarget(
    val file: UiState.FileSelected,
    val targetBytes: Long,
    val targetLabel: String,
    val minBytes: Long,
    val parts: Int,
    val lowerHeight: Int?
)

/**
 * Asked before encoding instead of after: running a target the floors can't reach costs every pass (minutes on a
 * long clip) only to end "Still over". Offers what could actually make it fit, or running it anyway.
 */
@Composable
internal fun UnreachableDialog(
    u: UnreachableTarget,
    minHeight: Int,
    onSplit: () -> Unit,
    onLowerFloor: (Int) -> Unit,
    onAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This probably won't fit") },
        text = {
            Text(
                "At ${minHeight}p the smallest this video can get is about ${formatSize(u.minBytes)}, " +
                    (if (u.minBytes > u.targetBytes) "over ${u.targetLabel}." else "too close to ${u.targetLabel} to land under it reliably.") +
                    " Encoding would take several passes and most likely end up just over."
            )
        },
        confirmButton = {
            Column {
                if (u.parts > 1) TextButton(onClick = onSplit) { Text("Split into ${u.parts} parts") }
                u.lowerHeight?.let { h -> TextButton(onClick = { onLowerFloor(h) }) { Text("Allow down to ${h}p") } }
                TextButton(onClick = onAnyway) { Text("Try anyway") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/** One-line warning for a target the floors (720p, 24 fps, audio steps, 100 kbps) can't meet or can only meet roughly. */
internal fun feasibilityHint(vm: CompressorViewModel, file: UiState.FileSelected, targetBytes: Long): String? {
    if (!file.mime.startsWith("video")) return null
    val parts = vm.partsFor(targetBytes, file.originalBytes)
    if (parts > 1) return "Will be split into $parts parts, each under this size"
    val a = vm.assessTarget(targetBytes, file.originalBytes) ?: return null
    return when (a.level) {
        BitrateMath.Feasibility.UNREACHABLE -> "Can't reach this size — the smallest possible at ${vm.floors.minHeight}p is about ${formatSize(a.minBytes)}"
        BitrateMath.Feasibility.ROUGH -> "Will fit, but the video will look rough (about ${a.videoBitrate / 1000} kbps)"
        BitrateMath.Feasibility.OK -> null
    }
}

/** Start-screen notice that a newer Squeeze is out; tapping opens the release page. */
@Composable
internal fun UpdateBanner(version: String, onOpen: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onOpen, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) {
        Text("Squeeze $version is out — tap to get it")
    }
}
