package com.tvfilebridge.app.ui.remote

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.tvfilebridge.app.AppContainer
import com.tvfilebridge.app.connection.AdbConnectionManager
import com.tvfilebridge.app.connection.ConnectionState
import com.tvfilebridge.app.cursor.CursorBridge
import com.tvfilebridge.app.cursor.RemoteApp
import com.tvfilebridge.app.cursor.TvCompanionInstaller
import com.tvfilebridge.app.remote.AndroidKeyCode
import com.tvfilebridge.app.remote.NowPlayingInfo
import com.tvfilebridge.app.remote.RemoteControlRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppListUiState(
    val apps: List<RemoteApp> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

enum class CompanionStatus { UNKNOWN, CHECKING, NOT_INSTALLED, INSTALLING, INSTALLED, INSTALL_FAILED }

class RemoteViewModel(
    private val remoteControlRepository: RemoteControlRepository,
    private val tvCompanionInstaller: TvCompanionInstaller,
    private val cursorBridge: CursorBridge,
    connectionManager: AdbConnectionManager,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionState.Disconnected)

    private val _appListState = MutableStateFlow(AppListUiState())
    val appListState: StateFlow<AppListUiState> = _appListState.asStateFlow()

    private val _companionStatus = MutableStateFlow(CompanionStatus.UNKNOWN)
    val companionStatus: StateFlow<CompanionStatus> = _companionStatus.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<NowPlayingInfo?> = _nowPlaying.asStateFlow()
    private var nowPlayingPollJob: Job? = null

    // The most recent title/subtitle scraped directly from the on-screen
    // overlay via Get Duration - confirmed fresher than dumpsys media_
    // session's own metadata field (dumpsys lagged behind the real
    // on-screen text: "539K views" there vs "541K views" actually
    // rendered at that exact instant). Kept across poll ticks and reapplied
    // to every dumpsys-sourced update below, keyed to the same package, so
    // the regular 2.5s poll doesn't stomp the fresher values back to stale
    // ones the moment it lands right after a Get Duration tap. Cleared
    // whenever the poll sees a DIFFERENT package (a real video/app switch),
    // since a scrape from the old video/app is no longer valid.
    private var scrapedTitle: String? = null
    private var scrapedSubtitle: String? = null
    private var scrapedForPackage: String? = null

    private val _scrapedDurationMs = MutableStateFlow<Long?>(null)
    val scrapedDurationMs: StateFlow<Long?> = _scrapedDurationMs.asStateFlow()
    private val _isScrapingDuration = MutableStateFlow(false)
    val isScrapingDuration: StateFlow<Boolean> = _isScrapingDuration.asStateFlow()
    private val _scrapeDurationError = MutableStateFlow<String?>(null)
    val scrapeDurationError: StateFlow<String?> = _scrapeDurationError.asStateFlow()

    private fun applyScrapedOverlay(info: NowPlayingInfo?): NowPlayingInfo? {
        if (info == null) return null
        if (info.packageName != scrapedForPackage) return info
        return info.copy(
            title = scrapedTitle ?: info.title,
            artist = scrapedSubtitle ?: info.artist,
        )
    }

    /**
     * Starts polling `dumpsys media_session` every 2.5s while the Now
     * Playing tab is visible - stopped via stopPollingNowPlaying() as soon
     * as it isn't, so this doesn't burn ADB round trips or battery in the
     * background. Safe to call repeatedly (e.g. on every recomposition);
     * only starts a new job if one isn't already running.
     */
    fun startPollingNowPlaying() {
        if (nowPlayingPollJob?.isActive == true) return
        nowPlayingPollJob = viewModelScope.launch {
            while (isActive) {
                remoteControlRepository.fetchNowPlaying()
                    .onSuccess { _nowPlaying.value = applyScrapedOverlay(it) }
                delay(2500)
            }
        }
    }

    fun stopPollingNowPlaying() {
        nowPlayingPollJob?.cancel()
        nowPlayingPollJob = null
    }

    /**
     * The TV itself reacts to MEDIA_PLAY_PAUSE instantly, but the app's icon
     * was only updating on the next scheduled poll tick - up to 2.5s later,
     * averaging ~1.25s - since fetchNowPlaying() was only ever called from
     * the timed loop. A short delay then an immediate refresh closes that
     * gap the same way seeks and Get Duration already do; the delay gives
     * the TV's own session state a moment to actually flip before reading
     * it back (reading with zero delay would often just see the old state).
     */
    fun onNowPlayingPlayPause() {
        viewModelScope.launch {
            remoteControlRepository.keyEvent(AndroidKeyCode.MEDIA_PLAY_PAUSE)
            delay(150)
            remoteControlRepository.fetchNowPlaying().onSuccess { _nowPlaying.value = applyScrapedOverlay(it) }
        }
    }

    fun onNowPlayingStop() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.MEDIA_STOP) }
    }

    fun onNowPlayingPrevious() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.MEDIA_REWIND) }
    }

    fun onNowPlayingNext() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.MEDIA_FAST_FORWARD) }
    }

    fun onNowPlayingVolumeUp() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.VOLUME_UP) }
    }

    fun onNowPlayingVolumeDown() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.VOLUME_DOWN) }
    }

    fun onNowPlayingMute() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.VOLUME_MUTE) }
    }

    /**
     * User-triggered only, not polled - see scrapeDurationFromScreen()'s doc
     * for why: dumpsys media_session never exposes duration on this Android
     * build (confirmed against AOSP source - its dump formatter only prints
     * title/subtitle text, no other metadata field, from any app), but the
     * on-screen seek bar always renders it as visible text since the app
     * has to draw it. This nudges that overlay up and scrapes it once.
     */
    fun onGetDuration() {
        viewModelScope.launch {
            _isScrapingDuration.value = true
            _scrapeDurationError.value = null
            val overlayInfo = remoteControlRepository.scrapeDurationFromScreen()
                .onSuccess { info ->
                    if (info != null) {
                        _scrapedDurationMs.value = info.durationMs
                    } else {
                        _scrapeDurationError.value = "Couldn't read a duration from the screen. Make sure the seek bar is visible."
                    }
                }
                .onFailure { _scrapeDurationError.value = it.message }
                .getOrNull()
            // dumpsys media_session's own title/subtitle metadata was
            // confirmed live to lag behind what's actually rendered
            // on-screen (it said "539K views" while this same overlay dump
            // showed "541K views" at that exact instant) - so the fresher
            // scraped title/subtitle overlays onto the dumpsys-sourced
            // NowPlayingInfo once fetchNowPlaying() lands, rather than
            // trusting dumpsys's copy as the source of truth for those two
            // fields specifically.
            val info = remoteControlRepository.fetchNowPlaying().getOrNull()
            // Persist the scraped title/subtitle keyed to this package so
            // the ongoing 2.5s poll (startPollingNowPlaying) keeps reapplying
            // them on every future tick too - without this, the ONE
            // immediate update here looked right for a moment, then the
            // very next poll tick overwrote it straight back to dumpsys's
            // staler copy (the actual bug reported: "reverted back after
            // one second").
            if (info != null && overlayInfo != null) {
                scrapedForPackage = info.packageName
                scrapedTitle = overlayInfo.title
                scrapedSubtitle = overlayInfo.subtitle
            }
            _nowPlaying.value = applyScrapedOverlay(info)
            _isScrapingDuration.value = false
        }
    }

    private val _isSeeking = MutableStateFlow(false)
    val isSeeking: StateFlow<Boolean> = _isSeeking.asStateFlow()
    private var lastKnownPositionMs: Long? = null

    /** Called from the UI as it interpolates position between polls, kept as the base for +5s/-5s/+10s/-10s offsets. */
    fun updateKnownPosition(positionMs: Long) {
        lastKnownPositionMs = positionMs
    }

    /**
     * Nudges playback by a relative offset (+5s/-5s/+10s/-10s buttons) and
     * drag-to-seek on the progress bar both land on the same real
     * mechanism: tapping the on-screen seek bar widget's exact pixel
     * position for the target time - confirmed live and reliable (tested
     * twice against SmartTube, landed within 1 second of two different
     * targets) rather than approximating via repeated fast-forward/rewind
     * presses. Needs the real duration to compute the target fraction, so
     * this only works once "Get duration" has been used at least once.
     */
    fun onSeekRelative(offsetMs: Long) {
        val basePosition = _nowPlaying.value?.positionMs ?: lastKnownPositionMs
        if (basePosition == null) {
            Log.w("RemoteViewModel", "onSeekRelative($offsetMs) ignored - no known position yet")
            return
        }
        onSeekTo(basePosition + offsetMs)
    }

    /** Drag-to-seek on the progress bar: target is an absolute position computed from the drag fraction × duration. */
    fun onSeekTo(targetMs: Long) {
        val durationMs = _scrapedDurationMs.value ?: _nowPlaying.value?.durationMs
        if (durationMs == null) {
            Log.w("RemoteViewModel", "onSeekTo($targetMs) ignored - no known duration yet, use Get duration first")
            return
        }
        viewModelScope.launch {
            _isSeeking.value = true
            Log.i("RemoteViewModel", "onSeekTo($targetMs) of $durationMs starting")
            val result = remoteControlRepository.tapSeekBarPosition(targetMs.coerceIn(0, durationMs), durationMs)
            Log.i("RemoteViewModel", "onSeekTo($targetMs) success=${result.isSuccess}")
            // Refresh immediately so the displayed position reflects where
            // the seek actually landed, rather than waiting up to 2.5s for
            // the next scheduled poll.
            remoteControlRepository.fetchNowPlaying().onSuccess { _nowPlaying.value = it }
            _isSeeking.value = false
        }
    }

    fun sendKeyEvent(code: Int) {
        viewModelScope.launch { remoteControlRepository.keyEvent(code) }
    }

    private var lastSentText: String = ""
    private var textSyncJob: Job? = null

    /**
     * Mirrors the keyboard field live: called on every keystroke, diffs
     * against what was last sent to the TV, and pushes only the delta
     * (appended chars via `input text`, removed chars via repeated DEL)
     * rather than retyping the whole string each time. Debounced ~120ms so a
     * fast typist coalesces into one shell round trip instead of one per key.
     */
    fun onKeyboardTextChanged(newText: String) {
        textSyncJob?.cancel()
        textSyncJob = viewModelScope.launch {
            delay(120)
            syncTextToTv(newText)
        }
    }

    /** Sends immediately (e.g. when the field is cleared after explicit Send). */
    fun resetKeyboardText() {
        textSyncJob?.cancel()
        lastSentText = ""
    }

    private suspend fun syncTextToTv(newText: String) {
        val old = lastSentText
        if (newText == old) return

        val commonPrefixLen = old.commonPrefixWith(newText).length
        val toDelete = old.length - commonPrefixLen
        val toAppend = newText.substring(commonPrefixLen)

        repeat(toDelete) {
            remoteControlRepository.keyEvent(AndroidKeyCode.DEL)
        }
        if (toAppend.isNotEmpty()) {
            remoteControlRepository.sendText(toAppend)
        }
        lastSentText = newText
    }

    fun checkCompanionStatus() {
        viewModelScope.launch {
            _companionStatus.value = CompanionStatus.CHECKING
            val installed = tvCompanionInstaller.isInstalled()
            _companionStatus.value = if (installed) CompanionStatus.INSTALLED else CompanionStatus.NOT_INSTALLED
        }
    }

    fun installCompanion() {
        viewModelScope.launch {
            _companionStatus.value = CompanionStatus.INSTALLING
            val result = tvCompanionInstaller.install()
            _companionStatus.value = if (result.isSuccess) CompanionStatus.INSTALLED else CompanionStatus.INSTALL_FAILED
        }
    }

    fun onCursorPadEntered() {
        viewModelScope.launch { cursorBridge.show() }
    }

    /**
     * Deliberately does NOT hide the cursor - the TV service already runs
     * its own few-seconds auto-hide timer, reset on every MOVE/CLICK/SHOW.
     * Hiding here too meant the cursor vanished the instant a finger lifted,
     * leaving no time to actually tap something after positioning it.
     */
    fun onCursorPadExited() {
    }

    fun onCursorMove(dx: Float, dy: Float) {
        viewModelScope.launch { cursorBridge.move(dx, dy) }
    }

    fun onCursorClick() {
        viewModelScope.launch { cursorBridge.click() }
    }

    /**
     * Scroll/seek pad, next to the cursor nudge pad - up/down scroll the
     * screen vertically (a swipe centered on screen), left/right seek
     * backward/forward in whatever's currently playing. Plain ADB shell
     * commands, not routed through the cursor companion's gesture dispatch
     * (same reasoning as tap() - dispatchGesture() is unreliable here).
     */
    // Screen is 1920x1080 (confirmed via `adb shell wm size` - override
    // size, what `input` coordinates operate against), so 960 is horizontal
    // center. `input roll` (the actual scroll-wheel/trackball input class)
    // produced no visible effect on this TV at all - confirmed dead end, not
    // just untried. A long swipe (500+px) reads as one big, inconsistent
    // fling rather than a single wheel-click-sized tick. A short, quick
    // swipe (150px over 150ms) is what actually reproduces "one scroll
    // notch" reliably - confirmed both directions against the real TV.
    fun onScrollUp() {
        viewModelScope.launch { remoteControlRepository.swipe(960, 450, 960, 600, durationMs = 150) }
    }

    fun onScrollDown() {
        viewModelScope.launch { remoteControlRepository.swipe(960, 600, 960, 450, durationMs = 150) }
    }

    fun onSeekBackward() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.MEDIA_REWIND) }
    }

    fun onSeekForward() {
        viewModelScope.launch { remoteControlRepository.keyEvent(AndroidKeyCode.MEDIA_FAST_FORWARD) }
    }

    fun launchApp(packageName: String) {
        viewModelScope.launch { cursorBridge.launchApp(packageName) }
    }

    fun loadApps() {
        viewModelScope.launch {
            _appListState.value = AppListUiState(isLoading = true)
            val apps = cursorBridge.listApps()
            _appListState.value = if (apps.isEmpty()) {
                AppListUiState(error = "No apps found. Make sure the TV companion is installed and enabled.")
            } else {
                AppListUiState(apps = apps)
            }
        }
    }
}

class RemoteViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return RemoteViewModel(
            container.remoteControlRepository,
            container.tvCompanionInstaller,
            container.cursorBridge,
            container.connectionManager,
        ) as T
    }
}
