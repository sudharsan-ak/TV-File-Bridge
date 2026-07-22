package com.tvfilebridge.app.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.ui.files.ROOT_PATH
import com.tvfilebridge.app.ui.files.SHORTCUTS

@Composable
fun ShareUploadScreen(
    container: AppContainer,
    uriCount: Int,
    onConfirm: (destinationPath: String) -> Unit,
    onCancel: () -> Unit,
) {
    val connectionState by container.connectionManager.state.collectAsState()
    var selectedPath by remember { mutableStateOf("$ROOT_PATH/Download") }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            if (uriCount == 1) "Send 1 file to TV" else "Send $uriCount files to TV",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))

        when (connectionState) {
            is ConnectionState.Connected -> {
                Text(
                    "Choose a folder on the TV:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SHORTCUTS) { shortcut ->
                        DestinationRow(
                            label = shortcut.label,
                            selected = selectedPath == shortcut.path,
                            onClick = { selectedPath = shortcut.path },
                        )
                    }
                    item {
                        DestinationRow(
                            label = "Internal storage (root)",
                            selected = selectedPath == ROOT_PATH,
                            onClick = { selectedPath = ROOT_PATH },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { onConfirm(selectedPath) }, modifier = Modifier.weight(1f)) { Text("Send") }
                }
            }
            else -> {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Not connected to a TV",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Open TV File Bridge and connect first, then try sharing again.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

@Composable
private fun DestinationRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp, 12.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
