package com.tvfilebridge.app.ui.mirror

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.mirror.MirrorState
import kotlinx.coroutines.launch

@Composable
fun TvMirrorScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = container.mirrorSessionManager
    val mirrorState by manager.state.collectAsState()
    val connectionState by container.connectionManager.state.collectAsState()
    val isFullscreen by manager.isFullscreen.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var stopServerMessage by remember { mutableStateOf<String?>(null) }

    // Only detaches the Surface (about to be destroyed by leaving
    // composition) - the session itself (sockets, server on the TV) stays
    // alive across navigation. manager.stop() (explicit "Stop mirroring")
    // is the only thing that actually ends it.
    DisposableEffect(Unit) {
        onDispose {
            manager.detachSurface()
            manager.setFullscreen(false) // don't leave the phone stuck in forced landscape/hidden-bars if navigating away while fullscreen
        }
    }

    // Exits fullscreen on the phone's own back button rather than letting it
    // fall through to normal navigation - matches "back returns to the
    // portrait mirror view first" (a second back press then navigates away
    // as usual, same as any other screen).
    BackHandler(enabled = isFullscreen) {
        manager.setFullscreen(false)
    }

    DisposableEffect(isFullscreen) {
        applyFullscreenWindowState(context, isFullscreen)
        onDispose {}
    }

    Column(modifier = modifier.fillMaxSize().padding(if (isFullscreen) PaddingValues(0.dp) else contentPadding)) {
        if (!isFullscreen) {
            com.tvfilebridge.app.ui.nav.AppHeader(title = "Screen Mirror", onMenuClick = onMenuClick) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (mirrorState is MirrorState.Active) {
                            DropdownMenuItem(
                                text = { Text("Stop mirroring") },
                                leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    manager.stop()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Stop mirroring server on TV") },
                            onClick = {
                                showMenu = false
                                scope.launch {
                                    val result = manager.stopServer()
                                    stopServerMessage = if (result.isSuccess) "Server stopped" else result.exceptionOrNull()?.message ?: "Failed to stop server"
                                }
                            },
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                connectionState !is ConnectionState.Connected -> MirrorMessage("Not connected to a TV. Connect from Settings first.")
                mirrorState is MirrorState.Idle -> StartMirrorButton { manager.start() }
                mirrorState is MirrorState.Starting -> MirrorLoading()
                mirrorState is MirrorState.Failed -> MirrorMessage((mirrorState as MirrorState.Failed).message, showRetry = true) {
                    manager.start()
                }
                else -> MirrorSurface(container = container, state = mirrorState as MirrorState.Active)
            }
        }

        if (mirrorState is MirrorState.Active) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                IconButton(onClick = { manager.pressBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { manager.pressHome() }) {
                    Icon(Icons.Filled.Home, contentDescription = "Home")
                }
                IconButton(onClick = { manager.setFullscreen(!isFullscreen) }) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                    )
                }
            }
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            ) {
                DirectionPad(
                    label = "Scroll",
                    onUp = { manager.scroll(-0.4f) },
                    onDown = { manager.scroll(0.4f) },
                    onLeft = { manager.scrollHorizontal(-0.4f) },
                    onRight = { manager.scrollHorizontal(0.4f) },
                )
                DirectionPad(
                    label = "Navigate",
                    onUp = { manager.pressDpad(com.tvfilebridge.app.mirror.KEYCODE_DPAD_UP) },
                    onDown = { manager.pressDpad(com.tvfilebridge.app.mirror.KEYCODE_DPAD_DOWN) },
                    onLeft = { manager.pressDpad(com.tvfilebridge.app.mirror.KEYCODE_DPAD_LEFT) },
                    onRight = { manager.pressDpad(com.tvfilebridge.app.mirror.KEYCODE_DPAD_RIGHT) },
                )
            }
        }
    }

    stopServerMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { stopServerMessage = null },
            title = { Text("Screen Mirror") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { stopServerMessage = null }) { Text("OK") } },
        )
    }
}

/**
 * Compact cross-shaped 4-direction pad (no center button - tapping the
 * mirrored screen itself already acts as select/OK) - used twice side by
 * side for Scroll (synthesized swipe gestures) and Navigate (real D-pad key
 * events), which are functionally different actions on the TV side despite
 * sharing the same up/down/left/right shape.
 */
@Composable
private fun DirectionPad(label: String, onUp: () -> Unit, onDown: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "$label up")
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLeft) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "$label left")
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onRight) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "$label right")
            }
        }
        IconButton(onClick = onDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "$label down")
        }
    }
}

/** Forces landscape + hides system bars for a fullscreen mirror view, or reverts both back to normal. */
private fun applyFullscreenWindowState(context: android.content.Context, fullscreen: Boolean) {
    val activity = context.findActivity() ?: return
    activity.requestedOrientation = if (fullscreen) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (fullscreen) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
private fun StartMirrorButton(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onStart) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Start mirroring", modifier = Modifier)
            }
            Text("Tap to view TV screen", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MirrorLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Starting…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun MirrorMessage(message: String, showRetry: Boolean = false, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (showRetry && onRetry != null) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/**
 * Hosts the decode Surface and translates touch gestures into scrcpy control
 * messages. Coordinates are scaled from the TextureView's on-screen size to
 * the video's own frame dimensions (state.width/height) - the control
 * protocol expects positions in video-frame space, not view-space.
 */
@Composable
private fun MirrorSurface(container: AppContainer, state: MirrorState.Active) {
    val manager = container.mirrorSessionManager
    var viewWidth by remember { mutableStateOf(1) }
    var viewHeight by remember { mutableStateOf(1) }

    fun toVideoSpace(x: Float, y: Float): Pair<Int, Int> {
        val scaledX = (x / viewWidth * state.width).toInt().coerceIn(0, state.width - 1)
        val scaledY = (y / viewHeight * state.height).toInt().coerceIn(0, state.height - 1)
        return scaledX to scaledY
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .aspectRatio(state.width.toFloat() / state.height.toFloat())
            .pointerInput(state) {
                detectTapGestures { offset ->
                    val (x, y) = toVideoSpace(offset.x, offset.y)
                    manager.tap(x, y)
                }
            }
            .pointerInput(state) {
                var lastPoint: Pair<Int, Int>? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val point = toVideoSpace(offset.x, offset.y)
                        lastPoint = point
                        manager.dragStart(point.first, point.second)
                    },
                    onDrag = { change, _ ->
                        val point = toVideoSpace(change.position.x, change.position.y)
                        lastPoint = point
                        manager.dragMove(point.first, point.second)
                    },
                    onDragEnd = {
                        lastPoint?.let { manager.dragEnd(it.first, it.second) }
                        lastPoint = null
                    },
                )
            },
        factory = { context ->
            android.util.Log.i("TvMirrorScreen", "factory: creating TextureView")
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        android.util.Log.i("TvMirrorScreen", "onSurfaceTextureAvailable: ${width}x$height")
                        viewWidth = width
                        viewHeight = height
                        manager.attachSurface(Surface(surfaceTexture))
                    }
                    override fun onSurfaceTextureSizeChanged(surfaceTexture: android.graphics.SurfaceTexture, width: Int, height: Int) {
                        viewWidth = width
                        viewHeight = height
                    }
                    override fun onSurfaceTextureDestroyed(surfaceTexture: android.graphics.SurfaceTexture): Boolean {
                        android.util.Log.i("TvMirrorScreen", "onSurfaceTextureDestroyed")
                        return true
                    }
                    private var updateCount = 0
                    override fun onSurfaceTextureUpdated(surfaceTexture: android.graphics.SurfaceTexture) {
                        updateCount++
                        if (updateCount <= 3 || updateCount % 60 == 0) {
                            android.util.Log.i("TvMirrorScreen", "onSurfaceTextureUpdated: #$updateCount")
                        }
                    }
                }
            }
        },
    )
    }
}
