package com.papacasper.squeeze

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Log in to sites so downloads can reach their age-restricted and sensitive videos. */
@Composable
fun AccountsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Bumped on resume so the status updates after returning from LoginActivity.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Site logins") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Some posts are only shown to logged-in accounts, such as sensitive posts on X and age-restricted " +
                        "YouTube videos. Log in here and downloads use that account. Your login stays on this phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                SiteLogins.Site.entries.forEach { site ->
                    val loggedIn = remember(refresh) { SiteLogins.isLoggedIn(site) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(site.label, style = MaterialTheme.typography.bodyMedium)
                            Text(if (loggedIn) "Logged in" else "Not logged in", style = MaterialTheme.typography.bodySmall)
                        }
                        if (loggedIn) TextButton(onClick = { SiteLogins.logOut(context, site); refresh++ }) { Text("Log out") }
                        else TextButton(onClick = { LoginActivity.start(context, site) }) { Text("Log in") }
                    }
                }
            }
        }
    )
}
