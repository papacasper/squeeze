package com.papacasper.squeeze

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Preset(val label: String, val short: String, val bytes: Long) {
    TEXT_RCS("Text (RCS)", "100 MB", 100L * 1024 * 1024),
    EMAIL("Email", "20 MB", 20L * 1024 * 1024),
    FREE("Discord Free", "20 MB", 20L * 1024 * 1024),
    BASIC("Discord Nitro Basic", "50 MB", 50L * 1024 * 1024),
    NITRO("Discord Nitro", "500 MB", 500L * 1024 * 1024),
    CHAT_APP("WhatsApp / Telegram", "2 GB", 2L * 1024 * 1024 * 1024)
}

// 1MB..2GB on a log scale, since the range spans 3 orders of magnitude.
const val CUSTOM_MIN_BYTES = 1L * 1024 * 1024
const val CUSTOM_MAX_BYTES = 2L * 1024 * 1024 * 1024

@Composable
fun ThumbnailPreview(thumbnail: Bitmap?) {
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = "Preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    }
}

@Composable
fun VideoModeToggle(convertToGif: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Output", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(14.dp)
            val modifier = Modifier.weight(1f).height(48.dp)
            if (!convertToGif) {
                Button(onClick = { onChange(false) }, shape = shape, modifier = modifier) { Text("Compress video") }
                OutlinedButton(onClick = { onChange(true) }, shape = shape, modifier = modifier) { Text("Convert to GIF") }
            } else {
                OutlinedButton(onClick = { onChange(false) }, shape = shape, modifier = modifier) { Text("Compress video") }
                Button(onClick = { onChange(true) }, shape = shape, modifier = modifier) { Text("Convert to GIF") }
            }
        }
    }
}

@Composable
fun PresetButtons(enabled: Boolean, onPick: (Preset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Target size", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Preset.entries.forEach { preset ->
            Button(
                onClick = { onPick(preset) },
                enabled = enabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(preset.label)
                    Text(preset.short, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Log-scale slider from 1MB to 2GB, since a linear slider can't usefully address 3 orders of magnitude. */
@Composable
fun CustomSizeSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    enabled: Boolean,
    onCompress: (Long, String) -> Unit
) {
    val minLog = log10(CUSTOM_MIN_BYTES.toDouble())
    val maxLog = log10(CUSTOM_MAX_BYTES.toDouble())
    val bytes = 10.0.pow(minLog + fraction * (maxLog - minLog)).roundToLong()
        .coerceIn(CUSTOM_MIN_BYTES, CUSTOM_MAX_BYTES)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Custom size", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(formatSize(bytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Slider(
            value = fraction,
            onValueChange = onFractionChange,
            enabled = enabled
        )
        OutlinedButton(
            onClick = { onCompress(bytes, "Custom (${formatSize(bytes)})") },
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Compress to ${formatSize(bytes)}")
        }
    }
}

@Composable
fun InfoCard(label: String, size: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatSize(size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WorkingCard(message: String, progress: Float, indeterminate: Boolean) {
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (indeterminate) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (!indeterminate) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ResultCard(originalBytes: Long, resultBytes: Long, fitsTarget: Boolean, targetLabel: String) {
    val reduction = if (originalBytes > 0) (1.0 - resultBytes.toDouble() / originalBytes.toDouble()) * 100 else 0.0
    val accent = if (fitsTarget) Color(0xFF2E7D32) else Color(0xFFC62828)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (fitsTarget) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = accent
                )
                Text(
                    if (fitsTarget) "Fits under $targetLabel" else "Still over $targetLabel",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                "${formatSize(resultBytes)}  (down %.0f%% from ${formatSize(originalBytes)})".format(reduction),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun DownloadingCard(message: String, progress: Float, onCancel: () -> Unit) {
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "download_progress")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828).copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = Color(0xFFC62828))
                Text("Compression failed", style = MaterialTheme.typography.titleMedium, color = Color(0xFFC62828))
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
