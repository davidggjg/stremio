package com.stremio.lite.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stremio.lite.data.addon.AddonSource
import com.stremio.lite.data.addon.DefaultAddons
import com.stremio.lite.ui.AddonsViewModel

@Composable
fun AddonsScreen() {
    val viewModel: AddonsViewModel = viewModel()
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncError by viewModel.syncError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Addons", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (viewModel.signedIn) {
                        "Synced with your Stremio account — installs and removals show up on every device."
                    } else {
                        "Signed out: addons are stored on this device only. Sign in to use your account's collection."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                syncError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (viewModel.signedIn) {
                    TextButton(onClick = { viewModel.syncNow() }) { Text("Sync from account") }
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Addon manifest URL or stremio:// link") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        viewModel.install(url)
                        url = ""
                    },
                    enabled = !state.busy && url.isNotBlank(),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(if (state.busy) "Installing…" else "Install addon")
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Quick install", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    DefaultAddons.suggestions.take(2).forEach { (label, manifestUrl) ->
                        AssistChip(onClick = { viewModel.install(manifestUrl) }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    DefaultAddons.suggestions.drop(2).forEach { (label, manifestUrl) ->
                        AssistChip(onClick = { viewModel.install(manifestUrl) }, label = { Text(label) })
                    }
                }
            }
        }

        item { SectionHeader("Installed (${addons.size})") }

        items(addons.size) { index ->
            val addon = addons[index]
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(addon.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        buildString {
                            append(addon.manifest.normalizedResources.joinToString(", ") { it.name })
                            if (addon.manifest.types.isNotEmpty()) {
                                append(" · ${addon.manifest.types.joinToString(", ")}")
                            }
                            append(if (addon.source == AddonSource.ACCOUNT) " · account" else " · local")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    addon.manifest.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (!addon.protected) {
                        TextButton(onClick = { viewModel.uninstall(addon) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}
