package com.tvfilebridge.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    FILES("files", "Files", Icons.Filled.Folder, Icons.Outlined.Folder),
    TRANSFERS("transfers", "Transfers", Icons.Filled.SwapVert, Icons.Outlined.SwapVert),
    REMOTE("remote", "Remote", Icons.Filled.SettingsRemote, Icons.Outlined.SettingsRemote),
}
