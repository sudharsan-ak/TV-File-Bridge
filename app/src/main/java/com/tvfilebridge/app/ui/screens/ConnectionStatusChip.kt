package com.tvfilebridge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvfilebridge.app.connection.ConnectionState

@Composable
fun ConnectionStatusChip(state: ConnectionState, deviceName: String? = null, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        is ConnectionState.Connected -> "Connected to ${deviceName ?: state.host}" to MaterialTheme.colorScheme.primary
        is ConnectionState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.AwaitingAuthorization -> "Waiting for TV" to MaterialTheme.colorScheme.onSurfaceVariant
        is ConnectionState.Failed -> "Connection failed" to MaterialTheme.colorScheme.error
        is ConnectionState.Disconnected -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    StatusPill(label = label, color = color, modifier = modifier) {
        if (state is ConnectionState.Connecting || state is ConnectionState.AwaitingAuthorization) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(10.dp), tint = color)
        }
    }
}

/** Same pill shape/spacing as ConnectionStatusChip - the "PC: {name}" line under it (AppScaffold) uses this directly so both read as one consistent connection-status style rather than a chip next to plain text. */
@Composable
fun StatusPill(label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
