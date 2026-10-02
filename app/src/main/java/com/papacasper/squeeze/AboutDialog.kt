package com.papacasper.squeeze

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("About Squeeze") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $version", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Free software under the ${Licenses.APP_LICENSE} license.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { open(Licenses.SOURCE_URL) }) { Text("Source code") }
                HorizontalDivider()
                Text("Built with", fontWeight = FontWeight.SemiBold)
                Licenses.components.forEach { c ->
                    Column {
                        Text(c.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Text("${c.purpose} · ${c.license}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { open(c.url) }) { Text("Website") }
                    }
                }
            }
        }
    )
}
