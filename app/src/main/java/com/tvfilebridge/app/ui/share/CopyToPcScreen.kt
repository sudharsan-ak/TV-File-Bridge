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
import androidx.compose.runtime.LaunchedEffect
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
import com.tvfilebridge.app.clipboard.PcDevice

@Composable
fun CopyToPcScreen(
    container: AppContainer,
    isImage: Boolean,
    onConfirm: (PcDevice) -> Unit,
    onCancel: () -> Unit,
    imageCount: Int = 1,
) {
    val devices by container.pcDeviceStore.devices.collectAsState(initial = emptyList())
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(devices) {
        if (selectedDeviceId == null) selectedDeviceId = devices.firstOrNull()?.id
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            when {
                !isImage -> "Copy text to PC"
                imageCount > 1 -> "Copy $imageCount images to PC"
                else -> "Copy image to PC"
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))

        if (devices.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No PC added yet",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add your PC's IP and port from Settings, once the PC companion app is installed and running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        } else {
            Text(
                "Choose a PC:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.id }) { device ->
                    DeviceRow(
                        device = device,
                        selected = selectedDeviceId == device.id,
                        onClick = { selectedDeviceId = device.id },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { devices.find { it.id == selectedDeviceId }?.let(onConfirm) },
                    enabled = selectedDeviceId != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Copy") }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: PcDevice, selected: Boolean, onClick: () -> Unit) {
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
        Column {
            Text(
                device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${device.host}:${device.port}",
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
