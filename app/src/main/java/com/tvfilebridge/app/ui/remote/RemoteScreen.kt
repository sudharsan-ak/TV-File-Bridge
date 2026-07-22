package com.tvfilebridge.app.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.cursor.RemoteApp
import com.tvfilebridge.app.remote.AndroidKeyCode
import com.tvfilebridge.app.remote.RemoteFab
import kotlinx.coroutines.launch

private enum class RemoteSection { REMOTE, APPS, NOW_PLAYING }

@Composable
fun RemoteScreen(
    container: AppContainer,
    contentPadding: PaddingValues,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RemoteViewModel = viewModel(factory = RemoteViewModelFactory(container))
    val connectionState by viewModel.connectionState.collectAsState()
    var section by remember { mutableStateOf(RemoteSection.REMOTE) }

    Column(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.tvfilebridge.app.ui.nav.AppHeader(title = "Remote", onMenuClick = onMenuClick)

        if (connectionState !is ConnectionState.Connected) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Connect to a TV from Settings to use the remote.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        RemoteSectionTabs(current = section, onSelect = { section = it })

        // Poll only while this tab is actually the one showing - switching
        // away (or leaving the whole Remote screen) stops it so it doesn't
        // burn ADB round trips/battery in the background.
        DisposableEffect(section) {
            if (section == RemoteSection.NOW_PLAYING) viewModel.startPollingNowPlaying() else viewModel.stopPollingNowPlaying()
            onDispose { viewModel.stopPollingNowPlaying() }
        }

        when (section) {
            RemoteSection.REMOTE -> CombinedRemoteSection(viewModel, container)
            RemoteSection.APPS -> AppsSection(viewModel)
            RemoteSection.NOW_PLAYING -> NowPlayingSection(viewModel)
        }
    }
}

@Composable
private fun RemoteSectionTabs(current: RemoteSection, onSelect: (RemoteSection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTab(RemoteSection.REMOTE, current, Icons.Filled.Gamepad, "Remote", onSelect)
        SectionTab(RemoteSection.APPS, current, Icons.Filled.Apps, "Apps", onSelect)
        SectionTab(RemoteSection.NOW_PLAYING, current, Icons.Filled.PlayArrow, "Now Playing", onSelect)
    }
}

@Composable
private fun RowScope.SectionTab(
    section: RemoteSection,
    current: RemoteSection,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onSelect: (RemoteSection) -> Unit,
) {
    val selected = section == current
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onSelect(section) }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The combined "Remote" tab: a compact D-pad block (pure ADB, always usable,
 * matches the old D-pad tab's exact behavior/keyevents) followed by the
 * touchpad + three draggable floating buttons (Keyboard / Move cursor /
 * Scroll·Seek), which still need the TV companion app the same way the old
 * Cursor tab did. Keeping that split rather than gating the whole screen
 * behind the companion preserves existing behavior: D-pad/Home/Power/Volume/
 * transport worked with zero TV-side install before, and still do here.
 */
@Composable
private fun CombinedRemoteSection(viewModel: RemoteViewModel, container: AppContainer) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        CompanionGate(viewModel) {
            TouchpadFabBlock(viewModel, container)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Home/Power stacked to the left of the D-pad wheel, Volume −/mute/+ stacked
 * to the right, transport row (rewind/play-pause/fast-forward) underneath -
 * same AndroidKeyCode calls as the old D-pad tab, just laid out per the
 * confirmed mockup instead of separate rows above/below a full-width D-pad.
 */
@Composable
private fun CompactDPadBlock(viewModel: RemoteViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoteIconButton(icon = Icons.Filled.Home, contentDescription = "Home", size = 40.dp) {
                    viewModel.sendKeyEvent(AndroidKeyCode.HOME)
                }
                RemoteIconButton(icon = Icons.Filled.PowerSettingsNew, contentDescription = "Power", size = 40.dp) {
                    viewModel.sendKeyEvent(AndroidKeyCode.POWER)
                }
            }

            DPad(
                size = 168.dp,
                onUp = { viewModel.sendKeyEvent(AndroidKeyCode.DPAD_UP) },
                onDown = { viewModel.sendKeyEvent(AndroidKeyCode.DPAD_DOWN) },
                onLeft = { viewModel.sendKeyEvent(AndroidKeyCode.DPAD_LEFT) },
                onRight = { viewModel.sendKeyEvent(AndroidKeyCode.DPAD_RIGHT) },
                onCenter = { viewModel.sendKeyEvent(AndroidKeyCode.DPAD_CENTER) },
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RemoteIconButton(icon = Icons.Filled.Remove, contentDescription = "Volume down", size = 40.dp) {
                    viewModel.sendKeyEvent(AndroidKeyCode.VOLUME_DOWN)
                }
                RemoteIconButton(icon = Icons.Filled.VolumeOff, contentDescription = "Mute", size = 40.dp) {
                    viewModel.sendKeyEvent(AndroidKeyCode.VOLUME_MUTE)
                }
                RemoteIconButton(icon = Icons.Filled.Add, contentDescription = "Volume up", size = 40.dp) {
                    viewModel.sendKeyEvent(AndroidKeyCode.VOLUME_UP)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RemoteIconButton(icon = Icons.Filled.FastRewind, contentDescription = "Rewind") {
                viewModel.sendKeyEvent(AndroidKeyCode.MEDIA_REWIND)
            }
            RemoteIconButton(icon = Icons.Filled.PlayArrow, contentDescription = "Play/Pause") {
                viewModel.sendKeyEvent(AndroidKeyCode.MEDIA_PLAY_PAUSE)
            }
            RemoteIconButton(icon = Icons.Filled.FastForward, contentDescription = "Fast forward") {
                viewModel.sendKeyEvent(AndroidKeyCode.MEDIA_FAST_FORWARD)
            }
        }
    }
}

/**
 * Touchpad, D-pad block, and three freely-draggable floating buttons layered
 * together in one Box so FABs can be dragged anywhere across the whole
 * Remote tab - including over the touchpad - while the D-pad's own rect
 * (Home/Power through Volume, tracked via onGloballyPositioned) is kept off
 * limits: a FAB released inside it is pushed back out to the nearest edge
 * rather than left stranded behind the D-pad where it can't be reached
 * again. Each FAB's position is read from FabPositionStore (falls back to a
 * sensible default alignment/offset the first time) and written back on
 * drag-end, so a dragged spot persists across app restarts until dragged
 * again - or until Reset positions is tapped, which clears all three back to
 * their defaults.
 */
@Composable
private fun TouchpadFabBlock(viewModel: RemoteViewModel, container: AppContainer) {
    var showKeyboardSheet by remember { mutableStateOf(false) }
    var moveExpanded by remember { mutableStateOf(false) }
    var scrollExpanded by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var dPadExclusionRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            .onGloballyPositioned { containerSize = it.size },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Drag to move the cursor, tap to click.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Touchpad(viewModel)
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackPill(viewModel)
                ResetFabPositionsButton { scope.launch { container.fabPositionStore.resetAll() } }
            }
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    // Position relative to the shared Box above, not the screen,
                    // since FAB offsets are also expressed in that coordinate space.
                    val topLeft = coords.positionInParent()
                    dPadExclusionRect = androidx.compose.ui.geometry.Rect(topLeft, coords.size.toSize())
                },
            ) {
                CompactDPadBlock(viewModel)
            }
            Spacer(Modifier.height(16.dp))
        }

        DraggableFab(
            fab = RemoteFab.KEYBOARD,
            fabPositionStore = container.fabPositionStore,
            defaultAlignment = Alignment.TopStart,
            icon = Icons.Filled.Keyboard,
            contentDescription = "Keyboard",
            isPrimary = false,
            containerSize = containerSize,
            exclusionRect = dPadExclusionRect,
            onClick = { showKeyboardSheet = true },
        )
        ExpandableFab(
            fab = RemoteFab.MOVE_CURSOR,
            fabPositionStore = container.fabPositionStore,
            defaultAlignment = Alignment.CenterEnd,
            icon = Icons.Filled.OpenWith,
            contentDescription = "Move cursor",
            expanded = moveExpanded,
            onToggleExpanded = { moveExpanded = it },
            containerSize = containerSize,
            exclusionRect = dPadExclusionRect,
        ) {
            CursorNudgePad(viewModel)
        }
        ExpandableFab(
            fab = RemoteFab.SCROLL_SEEK,
            fabPositionStore = container.fabPositionStore,
            defaultAlignment = Alignment.TopEnd,
            icon = Icons.Filled.SwapVert,
            contentDescription = "Scroll and seek",
            expanded = scrollExpanded,
            onToggleExpanded = { scrollExpanded = it },
            containerSize = containerSize,
            exclusionRect = dPadExclusionRect,
        ) {
            ScrollSeekPad(viewModel)
        }
    }

    if (showKeyboardSheet) {
        KeyboardBottomSheet(viewModel, onDismiss = { showKeyboardSheet = false })
    }
}

@Composable
private fun ResetFabPositionsButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Undo,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text("Reset positions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Pushes [rect] fully outside [exclusion] by moving it to whichever edge requires the smallest shift, clamped back into [containerSize]. */
private fun repelFromExclusionZone(
    rect: androidx.compose.ui.geometry.Rect,
    exclusion: androidx.compose.ui.geometry.Rect,
    containerSize: androidx.compose.ui.unit.IntSize,
): androidx.compose.ui.geometry.Offset {
    if (!rect.overlaps(exclusion)) return rect.topLeft

    val margin = 4f
    val pushLeft = exclusion.left - rect.width - margin
    val pushRight = exclusion.right + margin
    val pushUp = exclusion.top - rect.height - margin
    val pushDown = exclusion.bottom + margin

    val fitsLeft = pushLeft >= 0f
    val fitsRight = pushRight + rect.width <= containerSize.width
    val fitsUp = pushUp >= 0f
    val fitsDown = pushDown + rect.height <= containerSize.height

    // Candidate shift distances for each direction that actually fits within
    // the container - pick whichever requires moving the FAB the least.
    val candidates = buildList {
        if (fitsLeft) add(Triple(rect.left - pushLeft, pushLeft, rect.top))
        if (fitsRight) add(Triple(pushRight - rect.left, pushRight, rect.top))
        if (fitsUp) add(Triple(rect.top - pushUp, rect.left, pushUp))
        if (fitsDown) add(Triple(pushDown - rect.top, rect.left, pushDown))
    }

    val best = candidates.minByOrNull { it.first }
        ?: return androidx.compose.ui.geometry.Offset(
            rect.left.coerceIn(0f, (containerSize.width - rect.width).coerceAtLeast(0f)),
            (exclusion.bottom + margin).coerceIn(0f, (containerSize.height - rect.height).coerceAtLeast(0f)),
        )

    return androidx.compose.ui.geometry.Offset(
        best.second.coerceIn(0f, (containerSize.width - rect.width).coerceAtLeast(0f)),
        best.third.coerceIn(0f, (containerSize.height - rect.height).coerceAtLeast(0f)),
    )
}

/**
 * A single floating button that can be long-press-dragged anywhere within
 * its parent Box and locks to that spot (persisted via FabPositionStore)
 * until dragged again - a plain tap still fires [onClick] normally, drag
 * only engages past a small touch-slop so taps aren't swallowed as
 * zero-distance drags.
 */
@Composable
private fun BoxScope.DraggableFab(
    fab: RemoteFab,
    fabPositionStore: com.tvfilebridge.app.remote.FabPositionStore,
    defaultAlignment: Alignment,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isPrimary: Boolean,
    containerSize: androidx.compose.ui.unit.IntSize,
    exclusionRect: androidx.compose.ui.geometry.Rect?,
    onClick: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val savedPosition by fabPositionStore.position(fab).collectAsState(initial = null)
    val density = androidx.compose.ui.platform.LocalDensity.current

    var offsetPx by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var defaultOffsetPx by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var hasLoadedSaved by remember { mutableStateOf(false) }

    // Seed offsetPx from DataStore only once (first non-null emission), never
    // again after - otherwise a delayed/echoed Flow emission from this FAB's
    // own setPosition write (or from another FAB's write touching the same
    // preferences file) can land mid-drag or right after a drag and snap the
    // button back to a stale position. Reset positions is handled separately
    // below, not by reacting to every savedPosition change.
    androidx.compose.runtime.LaunchedEffect(savedPosition) {
        if (savedPosition != null && !hasLoadedSaved) {
            offsetPx = with(density) { androidx.compose.ui.geometry.Offset(savedPosition!!.x.dp.toPx(), savedPosition!!.y.dp.toPx()) }
            hasLoadedSaved = true
        } else if (savedPosition == null && hasLoadedSaved) {
            // Reset positions was tapped - drop the live offset so this FAB
            // falls back to defaultOffsetPx again.
            offsetPx = null
            hasLoadedSaved = false
        }
    }

    val sizePx = with(density) { 60.dp.toPx() }

    androidx.compose.runtime.LaunchedEffect(containerSize, savedPosition) {
        if (savedPosition == null && containerSize.width > 0 && defaultOffsetPx == null) {
            val padding = with(density) { 8.dp.toPx() }
            val x = when (defaultAlignment) {
                Alignment.CenterEnd, Alignment.BottomEnd, Alignment.TopEnd -> containerSize.width - sizePx - padding
                else -> padding
            }
            val y = when (defaultAlignment) {
                Alignment.BottomEnd, Alignment.BottomStart, Alignment.BottomCenter -> containerSize.height - sizePx - padding
                Alignment.CenterEnd, Alignment.CenterStart -> (containerSize.height - sizePx) / 2
                else -> padding
            }
            defaultOffsetPx = androidx.compose.ui.geometry.Offset(x, y)
        }
    }

    val effectiveOffsetPx = offsetPx ?: defaultOffsetPx

    Box(
        modifier = Modifier
            .offset {
                if (effectiveOffsetPx == null) return@offset androidx.compose.ui.unit.IntOffset.Zero
                androidx.compose.ui.unit.IntOffset(effectiveOffsetPx.x.toInt(), effectiveOffsetPx.y.toInt())
            }
            .size(60.dp)
            .clip(CircleShape)
            .background(if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(fab) {
                val touchSlop = 6.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startOffset = effectiveOffsetPx ?: androidx.compose.ui.geometry.Offset.Zero
                    // pastSlop gates tap-vs-drag classification so sub-pixel
                    // jitter on a plain tap doesn't get swallowed as a
                    // zero-distance drag (which would call setPosition instead
                    // of onClick). Once past slop, the button is repositioned
                    // to align exactly under the finger in that same frame -
                    // no separate "catch up" jump on a later frame.
                    var pastSlop = false
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.positionChanged()) {
                            val totalDelta = change.position - down.position
                            if (!pastSlop && totalDelta.getDistance() > touchSlop) {
                                pastSlop = true
                            }
                            if (pastSlop) {
                                var x = startOffset.x + totalDelta.x
                                var y = startOffset.y + totalDelta.y
                                if (containerSize.width > 0) {
                                    x = x.coerceIn(0f, (containerSize.width - sizePx).coerceAtLeast(0f))
                                    y = y.coerceIn(0f, (containerSize.height - sizePx).coerceAtLeast(0f))
                                }
                                offsetPx = androidx.compose.ui.geometry.Offset(x, y)
                                change.consume()
                            }
                        }
                        pressed = change.pressed
                    }
                    if (pastSlop) {
                        offsetPx?.let { dropped ->
                            var final = dropped
                            if (exclusionRect != null) {
                                val rect = androidx.compose.ui.geometry.Rect(dropped, androidx.compose.ui.geometry.Size(sizePx, sizePx))
                                final = repelFromExclusionZone(rect, exclusionRect, containerSize)
                            }
                            offsetPx = final
                            val xDp = with(density) { final.x.toDp().value }
                            val yDp = with(density) { final.y.toDp().value }
                            scope.launch { fabPositionStore.setPosition(fab, xDp, yDp) }
                        }
                    } else {
                        onClick()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (isPrimary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * A floating button that morphs in-place into its expanded content (a wheel)
 * instead of opening a separate dialog/popover next to an unchanged button -
 * the FAB itself grows, centered on the same point it occupied when
 * collapsed, and a small × (only visible while expanded) shrinks it back.
 * No scrim, no dismiss-on-outside-tap: staying open until the × is tapped is
 * deliberate, so the touchpad and the expanded wheel can both be used in the
 * same session without one closing the other.
 */
@Composable
private fun BoxScope.ExpandableFab(
    fab: RemoteFab,
    fabPositionStore: com.tvfilebridge.app.remote.FabPositionStore,
    defaultAlignment: Alignment,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    expanded: Boolean,
    onToggleExpanded: (Boolean) -> Unit,
    containerSize: androidx.compose.ui.unit.IntSize,
    exclusionRect: androidx.compose.ui.geometry.Rect?,
    expandedContent: @Composable () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val savedPosition by fabPositionStore.position(fab).collectAsState(initial = null)
    val density = androidx.compose.ui.platform.LocalDensity.current

    var offsetPx by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // Resolved once containerSize is known and no drag/save has happened yet,
    // so the default-position case goes through the exact same top-left-based
    // offset + clamping math as the dragged case, instead of a separate
    // Alignment-based branch that couldn't be clamped the same way (an
    // Alignment resolves its pixel position at layout time, not in code).
    var defaultOffsetPx by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var hasLoadedSaved by remember { mutableStateOf(false) }

    // See DraggableFab's identical guard: only seed offsetPx from DataStore
    // once, never again, so a delayed/echoed Flow emission can't snap a
    // freshly-dragged position back to a stale value.
    androidx.compose.runtime.LaunchedEffect(savedPosition) {
        if (savedPosition != null && !hasLoadedSaved) {
            offsetPx = with(density) { androidx.compose.ui.geometry.Offset(savedPosition!!.x.dp.toPx(), savedPosition!!.y.dp.toPx()) }
            hasLoadedSaved = true
        } else if (savedPosition == null && hasLoadedSaved) {
            // Reset positions was tapped - drop the live offset so this FAB
            // falls back to defaultOffsetPx again.
            offsetPx = null
            hasLoadedSaved = false
        }
    }

    val collapsedSize = 60.dp
    val expandedSize = 148.dp
    val collapsedSizePx = with(density) { collapsedSize.toPx() }
    val expandedSizePx = with(density) { expandedSize.toPx() }
    val centerCompensationPx = (expandedSizePx - collapsedSizePx) / 2

    androidx.compose.runtime.LaunchedEffect(containerSize, savedPosition) {
        if (savedPosition == null && containerSize.width > 0 && defaultOffsetPx == null) {
            val padding = with(density) { 8.dp.toPx() }
            val x = when (defaultAlignment) {
                Alignment.CenterEnd, Alignment.BottomEnd, Alignment.TopEnd -> containerSize.width - collapsedSizePx - padding
                else -> padding
            }
            val y = when (defaultAlignment) {
                Alignment.BottomEnd, Alignment.BottomStart, Alignment.BottomCenter -> containerSize.height - collapsedSizePx - padding
                Alignment.CenterEnd, Alignment.CenterStart -> (containerSize.height - collapsedSizePx) / 2
                else -> padding
            }
            defaultOffsetPx = androidx.compose.ui.geometry.Offset(x, y)
        }
    }

    val effectiveOffsetPx = offsetPx ?: defaultOffsetPx

    Box(
        modifier = Modifier
            .offset {
                if (effectiveOffsetPx == null) return@offset androidx.compose.ui.unit.IntOffset.Zero
                val comp = if (expanded) centerCompensationPx else 0f
                var x = effectiveOffsetPx.x - comp
                var y = effectiveOffsetPx.y - comp
                if (expanded && containerSize.width > 0) {
                    x = x.coerceIn(0f, (containerSize.width - expandedSizePx).coerceAtLeast(0f))
                    y = y.coerceIn(0f, (containerSize.height - expandedSizePx).coerceAtLeast(0f))
                }
                androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt())
            }
            .size(if (expanded) expandedSize else collapsedSize),
        contentAlignment = Alignment.Center,
    ) {
        if (expanded) {
            expandedContent()
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onToggleExpanded(false) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .pointerInput(fab) {
                        val touchSlop = 6.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startOffset = effectiveOffsetPx ?: androidx.compose.ui.geometry.Offset.Zero
                            var pastSlop = false
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.positionChanged()) {
                                    val totalDelta = change.position - down.position
                                    if (!pastSlop && totalDelta.getDistance() > touchSlop) {
                                        pastSlop = true
                                    }
                                    if (pastSlop) {
                                        var x = startOffset.x + totalDelta.x
                                        var y = startOffset.y + totalDelta.y
                                        if (containerSize.width > 0) {
                                            x = x.coerceIn(0f, (containerSize.width - collapsedSizePx).coerceAtLeast(0f))
                                            y = y.coerceIn(0f, (containerSize.height - collapsedSizePx).coerceAtLeast(0f))
                                        }
                                        offsetPx = androidx.compose.ui.geometry.Offset(x, y)
                                        change.consume()
                                    }
                                }
                                pressed = change.pressed
                            }
                            if (pastSlop) {
                                offsetPx?.let { dropped ->
                                    var final = dropped
                                    if (exclusionRect != null) {
                                        val rect = androidx.compose.ui.geometry.Rect(dropped, androidx.compose.ui.geometry.Size(collapsedSizePx, collapsedSizePx))
                                        final = repelFromExclusionZone(rect, exclusionRect, containerSize)
                                    }
                                    offsetPx = final
                                    val xDp = with(density) { final.x.toDp().value }
                                    val yDp = with(density) { final.y.toDp().value }
                                    scope.launch { fabPositionStore.setPosition(fab, xDp, yDp) }
                                }
                            } else {
                                onToggleExpanded(true)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardBottomSheet(viewModel: RemoteViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                "Mirrors live to whatever's focused on the TV as you type.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    viewModel.onKeyboardTextChanged(it)
                },
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    // Once the phone-side field is already empty, further
                    // backspaces produce no onValueChange (nothing left to
                    // delete locally) even though the TV field may still have
                    // leftover text from before the keyboard was opened. Send a
                    // raw DEL keyevent straight to the TV in that case so
                    // backspace always keeps clearing the TV field, independent
                    // of what this field displays.
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && text.isEmpty()) {
                        viewModel.sendKeyEvent(AndroidKeyCode.DEL)
                        true
                    } else {
                        false
                    }
                },
                placeholder = { Text("Search, message, etc.") },
                trailingIcon = {
                    IconButton(onClick = {
                        viewModel.resetKeyboardText()
                        text = ""
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "Clear")
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CompanionGate(viewModel: RemoteViewModel, content: @Composable () -> Unit) {
    val status by viewModel.companionStatus.collectAsState()

    LaunchedEffect(Unit) {
        if (status == CompanionStatus.UNKNOWN) viewModel.checkCompanionStatus()
    }

    when (status) {
        CompanionStatus.UNKNOWN, CompanionStatus.CHECKING -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        CompanionStatus.NOT_INSTALLED, CompanionStatus.INSTALL_FAILED -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mouse, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Companion app needed", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cursor mode and a real app list need a small helper app installed on the TV once. " +
                        "It draws the cursor and reads the TV's app list.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (status == CompanionStatus.INSTALL_FAILED) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Install failed - check the TV is connected and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { viewModel.installCompanion() }) {
                    Text("Install on TV")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "After installing, open \"TV Bridge Cursor\" on the TV once to enable its accessibility service and overlay permission.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        CompanionStatus.INSTALLING -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Installing on TV…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        CompanionStatus.INSTALLED -> content()
    }
}

@Composable
private fun BackPill(viewModel: RemoteViewModel) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { viewModel.sendKeyEvent(AndroidKeyCode.BACK) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text("Back", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Content-navigation version of the ADB-Mouse-style pad: up/down trigger a
 * scroll swipe, left/right seek backward/forward. Same AdbMouseWheel shape
 * as CursorNudgePad, just different actions per quadrant.
 */
@Composable
private fun ScrollSeekPad(viewModel: RemoteViewModel) {
    AdbMouseWheel(
        onUp = { viewModel.onScrollUp() },
        onDown = { viewModel.onScrollDown() },
        onLeft = { viewModel.onSeekBackward() },
        onRight = { viewModel.onSeekForward() },
        onCenter = null,
    )
}

private const val CURSOR_NUDGE_STEP = 24f

/**
 * Deliberately a different shape from ScrollSeekPad's continuous ADB-Mouse
 * wheel - four separate rounded-square buttons in a cross (with real gaps
 * between them, not touching) plus a round center dot, so Move-cursor and
 * Scroll/Seek are unmistakable by silhouette alone, not just color/icon
 * (both wheels looked identical before this and were easy to mix up).
 */
@Composable
private fun CursorNudgePad(viewModel: RemoteViewModel) {
    val buttonColor = MaterialTheme.colorScheme.surfaceVariant
    val arrowTint = MaterialTheme.colorScheme.primary
    val centerColor = MaterialTheme.colorScheme.primary
    val buttonSize = 40.dp
    val gap = 6.dp

    Box(modifier = Modifier.size(148.dp), contentAlignment = Alignment.Center) {
        CrossButton(Icons.Filled.KeyboardArrowUp, Alignment.TopCenter, buttonSize, gap, buttonColor, arrowTint) {
            viewModel.onCursorPadEntered()
            viewModel.onCursorMove(0f, -CURSOR_NUDGE_STEP)
        }
        CrossButton(Icons.Filled.KeyboardArrowDown, Alignment.BottomCenter, buttonSize, gap, buttonColor, arrowTint) {
            viewModel.onCursorPadEntered()
            viewModel.onCursorMove(0f, CURSOR_NUDGE_STEP)
        }
        CrossButton(Icons.Filled.KeyboardArrowLeft, Alignment.CenterStart, buttonSize, gap, buttonColor, arrowTint) {
            viewModel.onCursorPadEntered()
            viewModel.onCursorMove(-CURSOR_NUDGE_STEP, 0f)
        }
        CrossButton(Icons.Filled.KeyboardArrowRight, Alignment.CenterEnd, buttonSize, gap, buttonColor, arrowTint) {
            viewModel.onCursorPadEntered()
            viewModel.onCursorMove(CURSOR_NUDGE_STEP, 0f)
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(centerColor)
                .clickable {
                    viewModel.onCursorPadEntered()
                    viewModel.onCursorClick()
                },
        )
    }
}

@Composable
private fun BoxScope.CrossButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    alignment: Alignment,
    size: androidx.compose.ui.unit.Dp,
    gapFromCenter: androidx.compose.ui.unit.Dp,
    background: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val offset = size / 2 + gapFromCenter + 18.dp
    val (offsetX, offsetY) = when (alignment) {
        Alignment.TopCenter -> 0.dp to -offset
        Alignment.BottomCenter -> 0.dp to offset
        Alignment.CenterStart -> -offset to 0.dp
        Alignment.CenterEnd -> offset to 0.dp
        else -> 0.dp to 0.dp
    }
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = offsetX, y = offsetY)
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Four curved annular segments around a solid center disc - ported from the
 * confirmed ADB-Mouse-style SVG mockup (real donut-wedge shapes with rounded
 * outer/inner arcs meeting at diagonal cuts), not a plain circle with
 * decorative divider lines drawn on top (an earlier version of this looked
 * like a giant X because the dividers didn't actually separate real wedge
 * shapes). Each wedge is built as outer arc forward + inner arc backward,
 * closed into one Path, so hit-testing and the drawn shape are the same
 * region - tapping anywhere in a wedge (not just near its arrow) triggers it.
 */
@Composable
private fun AdbMouseWheel(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: (() -> Unit)?,
) {
    val wedgeColor = MaterialTheme.colorScheme.primary
    val dividerColor = MaterialTheme.colorScheme.background
    val arrowColor = MaterialTheme.colorScheme.background
    val centerColor = MaterialTheme.colorScheme.surface
    val diameter = 148.dp

    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onUp, onDown, onLeft, onRight, onCenter) {
                    detectWedgeTap(
                        outerRadiusFraction = 1f,
                        innerRadiusFraction = 31f / 74f,
                        onUp = onUp,
                        onDown = onDown,
                        onLeft = onLeft,
                        onRight = onRight,
                        onCenter = onCenter,
                    )
                },
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val center = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)
                val outerRadius = this.size.minDimension / 2f
                val innerRadius = outerRadius * (31f / 74f)
                val gapDegrees = 6f

                // Each wedge spans 90deg minus a small gap on each side, centered
                // on up(270)/right(0)/down(90)/left(180) in Compose's clockwise-
                // from-3-o'clock convention, so the cuts land on the diagonals and
                // each arrow sits centered in its own quadrant - matching the
                // confirmed mockup, not a plus-shaped division.
                val sweeps = listOf(270f, 0f, 90f, 180f) // up, right, down, left
                for (centerAngle in sweeps) {
                    drawAnnularWedge(
                        center = center,
                        outerRadius = outerRadius,
                        innerRadius = innerRadius,
                        startAngle = centerAngle - 45f + gapDegrees / 2f,
                        sweepAngle = 90f - gapDegrees,
                        color = wedgeColor,
                    )
                }

                drawCircle(color = centerColor, radius = innerRadius, center = center)
                drawCircle(color = dividerColor, radius = innerRadius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            }

            val arrowInset = (diameter.value * (17f / 148f)).dp
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = arrowColor, modifier = Modifier.align(Alignment.TopCenter).padding(top = arrowInset).size(22.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = arrowColor, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = arrowInset).size(22.dp))
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = null, tint = arrowColor, modifier = Modifier.align(Alignment.CenterStart).padding(start = arrowInset).size(22.dp))
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = arrowColor, modifier = Modifier.align(Alignment.CenterEnd).padding(end = arrowInset).size(22.dp))
        }
    }
}

/** Draws one donut-wedge: outer arc forward, then inner arc backward, closed into one filled region. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnularWedge(
    center: androidx.compose.ui.geometry.Offset,
    outerRadius: Float,
    innerRadius: Float,
    startAngle: Float,
    sweepAngle: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val path = androidx.compose.ui.graphics.Path().apply {
        val outerRect = androidx.compose.ui.geometry.Rect(center.x - outerRadius, center.y - outerRadius, center.x + outerRadius, center.y + outerRadius)
        val innerRect = androidx.compose.ui.geometry.Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius)
        arcTo(outerRect, startAngle, sweepAngle, true)
        arcTo(innerRect, startAngle + sweepAngle, -sweepAngle, false)
        close()
    }
    drawPath(path, color = color)
}

/** Hit-tests a tap's polar position against the same wedge geometry drawAnnularWedge renders, plus the center circle if [onCenter] is non-null. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectWedgeTap(
    outerRadiusFraction: Float,
    innerRadiusFraction: Float,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: (() -> Unit)?,
) {
    val boxSize = size
    val center = androidx.compose.ui.geometry.Offset(boxSize.width / 2f, boxSize.height / 2f)
    val outerRadius = minOf(boxSize.width, boxSize.height) / 2f * outerRadiusFraction
    val innerRadius = outerRadius * innerRadiusFraction

    detectTapGestures { tapOffset ->
        val delta = tapOffset - center
        val distance = delta.getDistance()
        if (distance > outerRadius) return@detectTapGestures

        if (distance <= innerRadius) {
            onCenter?.invoke()
            return@detectTapGestures
        }

        // atan2 with Compose's y-down coordinate space, normalized to
        // [0, 360) with 0 = right, 90 = down, 180 = left, 270 = up.
        var angle = Math.toDegrees(kotlin.math.atan2(delta.y, delta.x).toDouble()).toFloat()
        if (angle < 0f) angle += 360f

        when {
            angle >= 315f || angle < 45f -> onRight()
            angle in 45f..<135f -> onDown()
            angle in 135f..<225f -> onLeft()
            else -> onUp()
        }
    }
}

@Composable
private fun Touchpad(viewModel: RemoteViewModel) {
    var isActive by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { if (isActive) viewModel.onCursorPadExited() }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                // Single gesture block: a drag detector and a tap detector on
                // two separate pointerInput modifiers each install their own
                // awaitPointerEventScope and race over the same down/up
                // stream, so one silently starves the other (taps stopped
                // registering). Track everything through one down/move/up
                // loop instead so a short tap and a drag are both always seen.
                val touchSlop = 18.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    isActive = true
                    viewModel.onCursorPadEntered()

                    // A "tap" still reports a few sub-pixel-jitter position
                    // changes even on a perfectly still finger, so treating
                    // any nonzero delta as a drag meant every tap looked like
                    // a drag and CLICK never fired. Only count it as a real
                    // drag once total displacement from the down point
                    // clears a normal touch-slop threshold.
                    var draggedPastSlop = false
                    var lastPosition = down.position
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.positionChanged()) {
                            val delta = change.position - lastPosition
                            val totalFromDown = change.position - down.position
                            if (draggedPastSlop || totalFromDown.getDistance() > touchSlop) {
                                draggedPastSlop = true
                                viewModel.onCursorMove(delta.x, delta.y)
                            }
                            lastPosition = change.position
                            change.consume()
                        }
                        pressed = change.pressed
                    }

                    if (!draggedPastSlop) {
                        viewModel.onCursorClick()
                    }
                    isActive = false
                    viewModel.onCursorPadExited()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mouse,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}

/**
 * Shows the TV's active media session: title/artist when the app reports
 * them, otherwise just the app name (live/sports/news apps commonly expose
 * play/pause state but no metadata at all - confirmed directly against Fox
 * One playing a live game: state=playing, metadata=null, position=-1).
 * Progress bar only renders when both position and duration are real
 * numbers; it's display-only, not draggable, since there's no generic
 * cross-app "seek to position" ADB command. Position ticks forward locally
 * between the ~2.5s polls (interpolated from elapsed wall-clock time) so it
 * doesn't visibly freeze between syncs, then snaps to the next real value
 * once a fresh poll lands.
 */
@Composable
private fun NowPlayingSection(viewModel: RemoteViewModel) {
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val scrapedDurationMs by viewModel.scrapedDurationMs.collectAsState()
    val isScrapingDuration by viewModel.isScrapingDuration.collectAsState()
    val scrapeDurationError by viewModel.scrapeDurationError.collectAsState()
    val info = nowPlaying

    if (info == null) {
        Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Nothing playing",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    var interpolatedPositionMs by remember(info.positionMs) { mutableStateOf(info.positionMs) }
    LaunchedEffect(interpolatedPositionMs) {
        interpolatedPositionMs?.let { viewModel.updateKnownPosition(it) }
    }
    LaunchedEffect(info.positionMs, info.isPlaying) {
        if (info.positionMs == null || !info.isPlaying) return@LaunchedEffect
        val basePosition = info.positionMs
        val startTime = System.currentTimeMillis()
        while (true) {
            kotlinx.coroutines.delay(500)
            interpolatedPositionMs = basePosition + (System.currentTimeMillis() - startTime)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            info.appLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            info.title ?: "Playing",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (info.artist != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                info.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // dumpsys media_session never reports duration on this Android build
        // (confirmed against AOSP source), so scrapedDurationMs - read once
        // from the on-screen seek bar's visible text via "Get duration" - is
        // the only source for it in practice; info.durationMs is kept as a
        // fallback in case some app someday does report it through the
        // session API directly.
        val effectiveDurationMs = info.durationMs ?: scrapedDurationMs
        if (effectiveDurationMs != null && interpolatedPositionMs != null) {
            Spacer(Modifier.height(24.dp))
            val isSeeking by viewModel.isSeeking.collectAsState()
            // Local drag state: while the user is actively dragging, the
            // slider shows the drag position (not the live polled one) so it
            // doesn't fight the finger; on release, fires the approximate
            // seek and clears back to following the real position again.
            var dragPositionMs by remember { mutableStateOf<Float?>(null) }
            val sliderValue = dragPositionMs ?: interpolatedPositionMs!!.toFloat()

            androidx.compose.material3.Slider(
                value = sliderValue.coerceIn(0f, effectiveDurationMs.toFloat()),
                onValueChange = { dragPositionMs = it },
                onValueChangeFinished = {
                    val target = dragPositionMs
                    dragPositionMs = null
                    if (target != null) viewModel.onSeekTo(target.toLong())
                },
                valueRange = 0f..effectiveDurationMs.toFloat(),
                enabled = !isSeeking,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (isSeeking) "Seeking…" else formatDuration((dragPositionMs?.toLong() ?: interpolatedPositionMs!!)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(formatDuration(effectiveDurationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SeekNudgeButton("-10s", enabled = !isSeeking) { viewModel.onSeekRelative(-10_000) }
                SeekNudgeButton("-5s", enabled = !isSeeking) { viewModel.onSeekRelative(-5_000) }
                SeekNudgeButton("+5s", enabled = !isSeeking) { viewModel.onSeekRelative(5_000) }
                SeekNudgeButton("+10s", enabled = !isSeeking) { viewModel.onSeekRelative(10_000) }
                // Get Duration only shows before duration is first known -
                // once the slider's up, this is the only way left to pull
                // fresh title/subtitle/duration again (e.g. after switching
                // to a different video without leaving the tab).
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = !isScrapingDuration) { viewModel.onGetDuration() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isScrapingDuration) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh duration", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (info.positionMs != null) {
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !isScrapingDuration) { viewModel.onGetDuration() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isScrapingDuration) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isScrapingDuration) "Reading…" else "Get duration",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (scrapeDurationError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    scrapeDurationError!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteIconButton(icon = Icons.Filled.FastRewind, contentDescription = "Previous") { viewModel.onNowPlayingPrevious() }
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                    .clickable { viewModel.onNowPlayingPlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (info.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (info.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            RemoteIconButton(icon = Icons.Filled.FastForward, contentDescription = "Next") { viewModel.onNowPlayingNext() }
        }
        Spacer(Modifier.height(12.dp))
        RemoteIconButton(icon = Icons.Filled.Close, contentDescription = "Stop", size = 40.dp) { viewModel.onNowPlayingStop() }

        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteIconButton(icon = Icons.Filled.Remove, contentDescription = "Volume down") { viewModel.onNowPlayingVolumeDown() }
            RemoteIconButton(icon = Icons.Filled.VolumeOff, contentDescription = "Mute") { viewModel.onNowPlayingMute() }
            RemoteIconButton(icon = Icons.Filled.Add, contentDescription = "Volume up") { viewModel.onNowPlayingVolumeUp() }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun SeekNudgeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppsSection(viewModel: RemoteViewModel) {
    CompanionGate(viewModel) {
        val appListState by viewModel.appListState.collectAsState()

        var isListView by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { viewModel.loadApps() }

        when {
            appListState.isLoading -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            appListState.error != null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    appListState.error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            else -> Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = { isListView = !isListView }) {
                        Icon(
                            if (isListView) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = if (isListView) "Switch to grid view" else "Switch to list view",
                        )
                    }
                }
                if (isListView) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(
                            count = appListState.apps.size,
                            key = { index -> appListState.apps[index].packageName },
                        ) { index ->
                            val app = appListState.apps[index]
                            AppRow(app = app, onClick = { viewModel.launchApp(app.packageName) })
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(appListState.apps, key = { it.packageName }) { app ->
                            AppTile(app = app, onClick = { viewModel.launchApp(app.packageName) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: RemoteApp, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(app.packageName, app.iconBase64) {
        app.iconBase64?.let { encoded ->
            runCatching {
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    } else {
        Icon(
            Icons.Filled.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun AppTile(app: RemoteApp, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(app = app, size = 40.dp)
            Spacer(Modifier.height(6.dp))
            Text(
                app.label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AppRow(app: RemoteApp, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app = app, size = 36.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DPad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onCenter: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 220.dp,
) {
    val centerSize = size * (72f / 220f)
    val arrowTouchSize = size * (64f / 220f)
    val arrowIconSize = size * (36f / 220f)
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )

        DPadArrow(Icons.Filled.KeyboardArrowUp, Alignment.TopCenter, arrowTouchSize, arrowIconSize, onUp)
        DPadArrow(Icons.Filled.KeyboardArrowDown, Alignment.BottomCenter, arrowTouchSize, arrowIconSize, onDown)
        DPadArrow(Icons.Filled.KeyboardArrowLeft, Alignment.CenterStart, arrowTouchSize, arrowIconSize, onLeft)
        DPadArrow(Icons.Filled.KeyboardArrowRight, Alignment.CenterEnd, arrowTouchSize, arrowIconSize, onRight)

        Box(
            modifier = Modifier
                .size(centerSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onCenter),
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BoxScope.DPadArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    alignment: Alignment,
    touchSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .size(touchSize)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
