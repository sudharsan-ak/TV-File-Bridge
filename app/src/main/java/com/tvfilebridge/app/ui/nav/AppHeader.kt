package com.tvfilebridge.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared per-screen header: hamburger icon, centered title, trailing actions -
 * all real children of one Box, so they share one vertical center by
 * construction and the title centers on the true screen width regardless of
 * how many trailing action icons a given screen has. Previously the
 * hamburger was a separate element overlaid on top of each screen's own
 * header row, and matching their vertical alignment meant guessing at
 * padding numbers across two unrelated layouts, which kept drifting out of
 * sync as either side changed.
 */
@Composable
fun AppHeader(
    title: String,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        IconButton(onClick = onMenuClick, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
        }
    }
}
