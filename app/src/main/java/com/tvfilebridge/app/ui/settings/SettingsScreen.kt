package com.tvfilebridge.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvfilebridge.app.R
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.data.SavedDevice
import com.tvfilebridge.app.data.WakeSchedule
import com.tvfilebridge.app.discovery.DiscoveredDevice
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.ui.nav.AppHeader
import androidx.compose.foundation.layout.fillMaxWidth

@Composable
fun SettingsScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(container))
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        AppHeader(title = "Settings", onMenuClick = onMenuClick)
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("General") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Devices") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Scheduler") })
        }
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> GeneralTab()
                1 -> DevicesTab(viewModel = viewModel)
                else -> SchedulerTab(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun GeneralTab(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BatteryOptimizationCard() }
        item { QuickSettingsTileCard() }
    }
}

@Composable
private fun DevicesTab(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxWidth(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add TV") },
            )
        },
    ) { innerPadding ->
        if (uiState.devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No TVs added yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap \"Add TV\" to connect to your Sony TV over Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.devices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        isActive = device.id == uiState.activeDeviceId,
                        connectionState = uiState.connectionStates[device.id] ?: ConnectionState.Disconnected,
                        onConnect = { viewModel.connectTo(device) },
                        onDisconnect = { viewModel.disconnect(device.id) },
                        onSetActive = { viewModel.setActive(device.id) },
                        onDelete = { viewModel.deleteDevice(device.id) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        val discoveryState by viewModel.discoveryState.collectAsState()
        val existingHosts = remember(uiState.devices) { uiState.devices.map { it.host }.toSet() }

        AddDeviceDialog(
            discoveryState = discoveryState,
            existingHosts = existingHosts,
            onScan = { viewModel.scanForDevices() },
            onDismiss = {
                showAddDialog = false
                viewModel.clearDiscovery()
            },
            onConfirm = { name, host, port ->
                viewModel.addDevice(name, host, port)
                showAddDialog = false
                viewModel.clearDiscovery()
            },
        )
    }
}

/**
 * Requesting battery-optimization exemption doesn't make the OS keep the ADB
 * connection alive with any guaranteed duration - it just makes Android less
 * eager to suspend the app in the background compared to its default
 * heuristics (Samsung's especially aggressive ones included). No persistent
 * notification, unlike a foreground service, which would give a real
 * guarantee but require one.
 */
@Composable
private fun BatteryOptimizationCard(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val powerManager = remember {
        context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    }
    var isExempted by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        isExempted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    if (isExempted) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Keep connection alive longer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android may drop the TV connection soon after you leave the app. " +
                    "Exempting this app from battery optimization makes that less aggressive " +
                    "(not guaranteed - no background notification is used).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${context.packageName}"),
                )
                launcher.launch(intent)
            }) {
                Text("Allow")
            }
        }
    }
}

/**
 * requestAddTileService()'s "Add this button?" dialog is drawn by SystemUI,
 * a different process - an Icon.createWithResource() only carries a package
 * name + resource ID, which SystemUI then has to re-resolve and rasterize
 * itself. That cross-process vector resolution is a known fragile path that
 * silently falls back to a generic placeholder rather than erroring, which
 * is why the dialog kept showing the plain Android robot regardless of what
 * the vector drawable actually contained. Rendering it to a real Bitmap here
 * and passing that via Icon.createWithBitmap() sends SystemUI actual pixels,
 * removing the cross-process resolution step entirely.
 *
 * Deliberately does NOT inflate ic_tv_tile.xml via ContextCompat.getDrawable
 * here - on this device (mismatched physical/override density, 450 vs 420,
 * on a very new OS build) VectorDrawable's per-density XML re-inflation path
 * throws "viewportWidth > 0" for a resource that AAPT2 compiles completely
 * correctly (confirmed by dumping the built APK's binary XML directly) - a
 * platform bug in that specific inflate path, not a content error. Drawing
 * the same ring+dot shape with raw Canvas/Path primitives instead sidesteps
 * VectorDrawable inflation entirely.
 */
private fun tileIconBitmap(context: android.content.Context): android.graphics.Bitmap {
    val sizePx = (24 * context.resources.displayMetrics.density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    val scale = sizePx / 108f
    val cx = 54f * scale
    val cy = 54f * scale
    val radius = 27f * scale
    val strokeWidth = 11f * scale

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val ringRect = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
    // Same broken-ring gap as the launcher icon: full circle minus a ~90°
    // gap, rotated so the opening reads toward the upper-right (-45°).
    canvas.drawArc(ringRect, -30f, 300f, false, paint)

    val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.FILL
    }
    val dotRadius = 7f * scale
    val gapAngleRad = Math.toRadians(-45.0)
    val dotX = cx + radius * Math.cos(gapAngleRad).toFloat()
    val dotY = cy + radius * Math.sin(gapAngleRad).toFloat()
    canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

    return bitmap
}

/**
 * Samsung's own Quick Panel editor ("Available buttons", reached via the
 * pencil icon after long-pressing the quick toggles area) only lists
 * Samsung's built-in system toggles - third-party app tiles don't appear
 * there at all, which is what made this tile seem to not exist. Android 13+
 * has a direct API for this that sidesteps hunting through OEM menus
 * entirely: it pops the system's own "Add tile" confirmation dialog.
 */
@Composable
private fun QuickSettingsTileCard(modifier: Modifier = Modifier) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Quick Settings tile", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Add a toggle to your Quick Settings panel to hold the TV connection " +
                    "open in the background for as long as you want, with a visible " +
                    "notification while it's on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val statusBarManager = context.getSystemService(android.app.StatusBarManager::class.java)
                statusBarManager?.requestAddTileService(
                    android.content.ComponentName(context, com.tvfilebridge.app.connection.ConnectionTileService::class.java),
                    "TV Connection",
                    android.graphics.drawable.Icon.createWithBitmap(tileIconBitmap(context)),
                    {},
                    {},
                )
            }) {
                Text("Add tile")
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: SavedDevice,
    isActive: Boolean,
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        "${device.host}:${device.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Every device's own status shows now, not just the active one -
            // a TV and a Fire Stick can both be genuinely connected at once,
            // independent of which is active for Remote's commands.
            StatusRow(connectionState)
            Spacer(Modifier.height(8.dp))

            when {
                connectionState is ConnectionState.Connected && isActive ->
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                connectionState is ConnectionState.Connected ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSetActive, modifier = Modifier.weight(1f)) { Text("Make active") }
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                    }
                connectionState is ConnectionState.Connecting ->
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Connecting…") }
                else ->
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            }

            if (connectionState is ConnectionState.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    hintForFailure(connectionState.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(state: ConnectionState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            is ConnectionState.Connected -> {
                Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Connected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            is ConnectionState.Connecting -> {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
            }
            is ConnectionState.AwaitingAuthorization -> {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Waiting for TV authorization - check \"Always allow\" on the TV", style = MaterialTheme.typography.bodyMedium)
            }
            is ConnectionState.Failed -> {
                Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Failed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            is ConnectionState.Disconnected -> {
                Icon(Icons.Filled.Circle, contentDescription = null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Disconnected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddDeviceDialog(
    discoveryState: DiscoveryUiState,
    existingHosts: Set<String>,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String, port: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("5555") }

    LaunchedEffect(Unit) { onScan() }

    val newResults = discoveryState.results.filterNot { it.host in existingHosts }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DiscoverySection(
                    discoveryState = discoveryState,
                    results = newResults,
                    onRescan = onScan,
                    onPick = { device ->
                        host = device.host
                        portText = "5555"
                    },
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Living Room Sony") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("TV IP address") },
                    placeholder = { Text("192.168.1.42") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull() ?: 5555
                    if (name.isNotBlank() && host.isNotBlank()) {
                        onConfirm(name.trim(), host.trim(), port)
                    }
                },
                enabled = name.isNotBlank() && host.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DiscoverySection(
    discoveryState: DiscoveryUiState,
    results: List<DiscoveredDevice>,
    onRescan: () -> Unit,
    onPick: (DiscoveredDevice) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Nearby devices",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (discoveryState.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRescan) { Text("Rescan") }
            }
        }

        Spacer(Modifier.height(4.dp))

        when {
            discoveryState.isScanning ->
                Text(
                    "Scanning your Wi-Fi network for devices with ADB open…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            results.isEmpty() && discoveryState.hasScanned ->
                Text(
                    "No devices found. Make sure the TV is on, ADB debugging is enabled, and enter its IP manually below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                results.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(device) }
                            .padding(vertical = 8.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(device.host, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private val DAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_SHORT_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** One card per group of WakeSchedule rows sharing a groupId - what the UI treats as a single multi-day schedule. */
private data class WakeScheduleGroup(val groupId: String, val rows: List<WakeSchedule>) {
    val hour get() = rows.first().hour
    val minute get() = rows.first().minute
    val enabled get() = rows.first().enabled
    val daysOfWeek get() = rows.map { it.dayOfWeek }.toSet()
}

@Composable
private fun SchedulerTab(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<WakeScheduleGroup?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val groups = remember(uiState.wakeSchedules) {
        uiState.wakeSchedules
            .groupBy { it.groupId }
            .map { (groupId, rows) -> WakeScheduleGroup(groupId, rows) }
            .sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    Scaffold(
        modifier = modifier.fillMaxWidth(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (viewModel.canScheduleExactAlarms()) {
                        showAddDialog = true
                    } else {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}"),
                        )
                        context.startActivity(intent)
                    }
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add schedule") },
            )
        },
    ) { innerPadding ->
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Tv,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No wake schedules yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add a schedule to have your phone wake the active TV at a set time every week, even from the background.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(groups, key = { it.groupId }) { group ->
                    WakeScheduleCard(
                        group = group,
                        onClick = { editingGroup = group },
                        onToggle = { enabled -> viewModel.setWakeScheduleGroupEnabled(group.groupId, group.rows, enabled) },
                        onDelete = { viewModel.deleteWakeScheduleGroup(group.groupId, group.rows) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        WakeScheduleDialog(
            title = "Add wake schedule",
            initialDays = setOf(java.time.LocalDate.now().dayOfWeek.value),
            initialHour = java.time.LocalTime.now().hour,
            initialMinute = java.time.LocalTime.now().minute,
            onDismiss = { showAddDialog = false },
            onConfirm = { days, hour, minute ->
                viewModel.addWakeScheduleGroup(days, hour, minute)
                showAddDialog = false
            },
        )
    }

    editingGroup?.let { group ->
        WakeScheduleDialog(
            title = "Edit wake schedule",
            initialDays = group.daysOfWeek,
            initialHour = group.hour,
            initialMinute = group.minute,
            onDismiss = { editingGroup = null },
            onConfirm = { days, hour, minute ->
                viewModel.updateWakeScheduleGroup(group.groupId, group.rows, days, hour, minute)
                editingGroup = null
            },
        )
    }
}

@Composable
private fun WakeScheduleCard(
    group: WakeScheduleGroup,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val daysLabel = remember(group.daysOfWeek) {
        when {
            group.daysOfWeek == setOf(1, 2, 3, 4, 5) -> "Every weekday"
            group.daysOfWeek == setOf(6, 7) -> "Weekends"
            group.daysOfWeek.size == 7 -> "Every day"
            else -> group.daysOfWeek.sorted().joinToString(", ") { DAY_SHORT_NAMES[it - 1] }
        }
    }
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("%02d:%02d".format(group.hour, group.minute), style = MaterialTheme.typography.titleMedium)
                Text(
                    daysLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = group.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Recurring weekly, Samsung Clock-style: a time (dial, with a keyboard-entry
 * toggle) plus weekday chips - any combination of days, fires every week on
 * each selected day until turned off or deleted. No calendar date involved,
 * so there's nothing here that can be "in the past" the way a specific date
 * could - the alarm scheduler already only arms the next future occurrence
 * of whichever days are picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WakeScheduleDialog(
    title: String,
    initialDays: Set<Int>,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (days: Set<Int>, hour: Int, minute: Int) -> Unit,
) {
    val selectedDays = remember { androidx.compose.runtime.mutableStateListOf(*initialDays.toTypedArray()) }
    var useKeyboardInput by remember { mutableStateOf(false) }
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = { useKeyboardInput = !useKeyboardInput }) {
                        Icon(
                            if (useKeyboardInput) Icons.Filled.Schedule else Icons.Filled.Keyboard,
                            contentDescription = if (useKeyboardInput) "Switch to dial input" else "Switch to keyboard input",
                        )
                    }
                }
                if (useKeyboardInput) {
                    androidx.compose.material3.TimeInput(state = timePickerState)
                } else {
                    TimePicker(state = timePickerState)
                }

                Text(
                    "Repeat on",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LABELS.forEachIndexed { index, label ->
                        val dayOfWeek = index + 1
                        val isSelected = dayOfWeek in selectedDays
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable {
                                    if (isSelected) selectedDays.remove(dayOfWeek) else selectedDays.add(dayOfWeek)
                                }
                                .size(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedDays.toSet(), timePickerState.hour, timePickerState.minute) },
                enabled = selectedDays.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
