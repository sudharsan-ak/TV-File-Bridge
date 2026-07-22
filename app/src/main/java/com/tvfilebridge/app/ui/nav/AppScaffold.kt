package com.tvfilebridge.app.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.ui.clipboard.ClipboardScreen
import com.tvfilebridge.app.ui.files.FilesScreen
import com.tvfilebridge.app.ui.remote.RemoteScreen
import com.tvfilebridge.app.ui.screens.ConnectionStatusChip
import com.tvfilebridge.app.ui.settings.SettingsScreen
import com.tvfilebridge.app.ui.sync.SyncFoldersScreen
import com.tvfilebridge.app.ui.transfers.TransfersScreen
import kotlinx.coroutines.launch

private const val SETTINGS_ROUTE = "settings"
private const val CLIPBOARD_ROUTE = "clipboard"
private const val SYNC_FOLDERS_ROUTE = "sync_folders"

/** Routes that render full-screen, hiding both the bottom nav and the drawer's hamburger button. */
private val FULL_SCREEN_ROUTES = setOf(SYNC_FOLDERS_ROUTE)

@Composable
fun AppScaffold(container: AppContainer) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(240.dp),
                drawerContainerColor = androidx.compose.material3.MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
            ) {
                Text(
                    "TV File Bridge",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable {
                            drawerScope.launch { drawerState.close() }
                            navController.navigate(AppDestination.FILES.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        .padding(16.dp),
                )
                val connectionState by container.connectionManager.state.collectAsState()
                ConnectionStatusChip(
                    connectionState,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                val isOffline by container.connectionManager.offlineMode.collectAsState(initial = false)
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        if (isOffline) "Offline" else "Online",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = if (isOffline) androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                    androidx.compose.material3.Switch(
                        checked = !isOffline,
                        onCheckedChange = { online ->
                            if (online) container.connectionManager.setOnline() else container.connectionManager.setOffline()
                        },
                    )
                }
                val devices by container.deviceStore.devices.collectAsState(initial = emptyList())
                val activeDeviceId by container.deviceStore.activeDeviceId.collectAsState(initial = null)
                val activeDevice = devices.find { it.id == activeDeviceId }
                if (activeDevice != null) {
                    var isWaking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var showPairingDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    NavigationDrawerItem(
                        label = { Text(if (isWaking) "Waking…" else "Wake TV") },
                        icon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
                        selected = false,
                        onClick = {
                            isWaking = true
                            drawerScope.launch {
                                // Sony's IRCC-IP power-on, not generic Wake-on-LAN -
                                // this TV's router/hardware combo doesn't honor plain
                                // magic packets (confirmed via direct testing), but
                                // "Remote start: Powered on by apps" keeps this SOAP
                                // endpoint reachable even while fully asleep.
                                container.sonyIrccWaker.wake(activeDevice.host)
                                kotlinx.coroutines.delay(1500)
                                isWaking = false
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Pair for Wake TV") },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        selected = false,
                        onClick = { showPairingDialog = true },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    if (showPairingDialog) {
                        SonyPairingDialog(
                            host = activeDevice.host,
                            sonyIrccWaker = container.sonyIrccWaker,
                            onDismiss = { showPairingDialog = false },
                        )
                    }
                    var isFixingCursor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var fixCursorMessage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                    NavigationDrawerItem(
                        label = { Text(fixCursorMessage ?: if (isFixingCursor) "Checking…" else "Fix cursor") },
                        icon = { Icon(Icons.Filled.Mouse, contentDescription = null) },
                        selected = false,
                        onClick = {
                            isFixingCursor = true
                            fixCursorMessage = null
                            drawerScope.launch {
                                val installed = container.tvCompanionInstaller.isInstalled()
                                fixCursorMessage = if (!installed) {
                                    "Companion not installed"
                                } else {
                                    // Purely additive - only writes when the
                                    // service is genuinely missing from the
                                    // list, so tapping this when the cursor
                                    // already works is a safe no-op, never
                                    // toggles anything off.
                                    val fixed = container.remoteControlRepository
                                        .ensureCursorAccessibilityEnabled(com.tvfilebridge.app.cursor.TV_COMPANION_ACCESSIBILITY_SERVICE)
                                        .getOrNull()
                                    if (fixed == true) "Re-enabled" else "Already enabled"
                                }
                                kotlinx.coroutines.delay(1500)
                                fixCursorMessage = null
                                isFixingCursor = false
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                // Not gated on activeDevice like Wake TV/Fix cursor above -
                // the PC option here has nothing to do with TV connection
                // state, only the TV option (disabled below when there's no
                // active TV) does.
                var isCapturingScreenshot by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                var screenshotMessage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                var showScreenshotPicker by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                fun runScreenshot(action: suspend () -> Result<*>) {
                    isCapturingScreenshot = true
                    screenshotMessage = null
                    drawerScope.launch {
                        action()
                            .onSuccess { screenshotMessage = "Screenshot saved" }
                            .onFailure { screenshotMessage = it.message ?: "Screenshot failed" }
                        kotlinx.coroutines.delay(2000)
                        screenshotMessage = null
                        isCapturingScreenshot = false
                    }
                }

                NavigationDrawerItem(
                    label = { Text(screenshotMessage ?: if (isCapturingScreenshot) "Capturing…" else "Screenshot") },
                    icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    selected = false,
                    onClick = { showScreenshotPicker = true },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                if (showScreenshotPicker) {
                    val pcDevices by container.pcDeviceStore.devices.collectAsState(initial = emptyList())
                    val primaryPc = pcDevices.find { it.isPrimary }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showScreenshotPicker = false },
                        title = { Text("Screenshot from") },
                        text = {
                            Column {
                                Text(
                                    if (activeDevice != null) "TV" else "TV (not connected)",
                                    color = if (activeDevice != null) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(enabled = activeDevice != null) {
                                            showScreenshotPicker = false
                                            runScreenshot { container.tvScreenshotSaver.captureAndSave() }
                                        }
                                        .padding(vertical = 12.dp),
                                )
                                Text(
                                    if (primaryPc != null) "PC (${primaryPc.name})" else "PC (no primary PC set up)",
                                    color = if (primaryPc != null) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable(enabled = primaryPc != null) {
                                            showScreenshotPicker = false
                                            runScreenshot { container.pcScreenshotRequester.requestAndSave(primaryPc!!) }
                                        }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { showScreenshotPicker = false }) { Text("Cancel") }
                        },
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                NavigationDrawerItem(
                    label = { Text("PC Sync") },
                    icon = { Icon(Icons.Filled.ContentPaste, contentDescription = null) },
                    selected = false,
                    onClick = {
                        drawerScope.launch { drawerState.close() }
                        navController.navigate(CLIPBOARD_ROUTE) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    selected = false,
                    onClick = {
                        drawerScope.launch { drawerState.close() }
                        navController.navigate(SETTINGS_ROUTE) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            bottomBar = {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route

                // Full-screen sub-destinations (pushed from a tab, not a tab
                // themselves) hide the bottom nav rather than sharing its frame.
                if (currentRoute !in FULL_SCREEN_ROUTES) {
                    NavigationBar {
                        AppDestination.entries.forEach { destination ->
                            val selected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            val onMenuClick: () -> Unit = { drawerScope.launch { drawerState.open() } }
            NavHost(
                navController = navController,
                startDestination = AppDestination.FILES.route,
            ) {
                composable(AppDestination.FILES.route) {
                    FilesScreen(container = container, contentPadding = innerPadding, onMenuClick = onMenuClick)
                }
                composable(AppDestination.TRANSFERS.route) {
                    TransfersScreen(
                        container = container,
                        contentPadding = innerPadding,
                        onOpenSyncFolders = { navController.navigate(SYNC_FOLDERS_ROUTE) },
                        onMenuClick = onMenuClick,
                    )
                }
                composable(SYNC_FOLDERS_ROUTE) {
                    SyncFoldersScreen(container = container, onBack = { navController.popBackStack() })
                }
                composable(AppDestination.REMOTE.route) {
                    RemoteScreen(container = container, contentPadding = innerPadding, onMenuClick = onMenuClick)
                }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen(container = container, contentPadding = innerPadding)
                }
                composable(CLIPBOARD_ROUTE) {
                    ClipboardScreen(container = container, contentPadding = innerPadding, onMenuClick = onMenuClick)
                }
            }
        }
    }
}

/**
 * One-time pairing flow for Sony's IRCC-IP wake API: request registration,
 * the TV shows a PIN on-screen, submit it back here to get a session cookie
 * that authorizes future Wake TV requests (including while fully asleep,
 * when the TV can no longer show a PIN prompt at all). Has to run while the
 * TV is on and its screen is visible.
 */
@Composable
private fun SonyPairingDialog(
    host: String,
    sonyIrccWaker: com.tvfilebridge.app.remote.SonyIrccWaker,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("start") }
    var pin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var errorText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with TV") },
        text = {
            Column {
                when (stage) {
                    "start" -> Text("Make sure the TV is on and its screen is visible, then tap Start. A PIN will appear on the TV.")
                    "pin" -> {
                        Text("Enter the PIN shown on the TV screen.")
                        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it },
                            label = { Text("PIN") },
                            singleLine = true,
                        )
                    }
                    "success" -> Text("Paired. Wake TV should now work even while the TV is fully asleep.")
                }
                if (errorText != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    Text(errorText!!, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (stage) {
                "start" -> androidx.compose.material3.TextButton(onClick = {
                    scope.launch {
                        errorText = null
                        val result = sonyIrccWaker.startRegistration(host)
                        result.onSuccess { outcome ->
                            stage = when (outcome) {
                                is com.tvfilebridge.app.remote.RegistrationResult.PinRequired -> "pin"
                                is com.tvfilebridge.app.remote.RegistrationResult.Success -> "success"
                            }
                        }.onFailure { errorText = it.message }
                    }
                }) { Text("Start") }
                "pin" -> androidx.compose.material3.TextButton(onClick = {
                    scope.launch {
                        errorText = null
                        val result = sonyIrccWaker.submitPin(host, pin)
                        result.onSuccess { outcome ->
                            stage = when (outcome) {
                                is com.tvfilebridge.app.remote.RegistrationResult.PinRequired -> {
                                    errorText = "Incorrect PIN, try again."
                                    "pin"
                                }
                                is com.tvfilebridge.app.remote.RegistrationResult.Success -> "success"
                            }
                        }.onFailure { errorText = it.message }
                    }
                }) { Text("Submit") }
                else -> androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
