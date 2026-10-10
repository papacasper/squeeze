package com.papacasper.squeeze

import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The files waiting for a target size. */
@Composable
internal fun BatchFilesCard(files: List<BatchFile>, skipped: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${files.size} files · ${formatSize(files.sumOf { it.bytes })}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            files.forEach { f ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("  " + formatSize(f.bytes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (skipped > 0) {
                Text(
                    "$skipped other file${if (skipped == 1) " was" else "s were"} skipped (not an image or video).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One row per file of a finished batch: the name, how it went, and sizes. */
@Composable
internal fun BatchResultCard(items: List<BatchItem>, targetLabel: String) {
    val dark = isSystemInDarkTheme()
    val good = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val bad = if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
    val allFit = items.all { it.done?.fitsTarget == true }
    val accent = if (allFit) good else bad
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (allFit) Icons.Filled.CheckCircle else Icons.Filled.Error, contentDescription = null, tint = accent)
                Text(
                    BatchResult.summary(items, targetLabel),
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            HorizontalDivider()
            items.forEach { item ->
                val d = item.done
                val ok = d != null && d.fitsTarget
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = if (ok) "Fits" else "Problem",
                        tint = if (ok) good else bad,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.sourceName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                d == null -> item.error ?: "Failed"
                                KeepOriginal.isKept(d.settings) -> "${formatSize(item.originalBytes)} · kept the original (already small)"
                                !d.fitsTarget -> "${formatSize(item.originalBytes)} → ${formatSize(d.resultFile.length())} (still over)"
                                else -> "${formatSize(item.originalBytes)} → ${formatSize(d.resultFile.length())}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d == null) bad else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Every output file of a finished batch with the name to save it under; split videos contribute all their parts. */
internal fun batchOutputs(items: List<BatchItem>): List<Triple<java.io.File, String, String>> = items.mapNotNull { it.done }.flatMap { d ->
    val all = listOf(d.resultFile) + d.extraFiles
    all.mapIndexed { i, f -> Triple(f, if (all.size == 1) d.suggestedName else BitrateMath.partName(d.suggestedName, i, all.size), d.mime) }
}

@Composable
internal fun BatchDoneActions(
    items: List<BatchItem>,
    onSave: (List<Triple<java.io.File, String, String>>) -> Unit,
    onShare: (List<Triple<java.io.File, String, String>>) -> Unit
) {
    val files = batchOutputs(items)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Button(onClick = { onSave(files) }, enabled = files.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Save all")
        }
        Button(onClick = { onShare(files) }, enabled = files.isNotEmpty(), modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Share all")
        }
    }
}
