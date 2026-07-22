package com.tvfilebridge.app.remote

import android.util.Log
import com.tvfilebridge.app.connection.AdbConnectionManager
import java.io.File

private const val TAG = "RemoteControlRepository"

class RemoteControlRepository(private val connectionManager: AdbConnectionManager) {

    /**
     * The seek bar's on-screen pixel bounds barely ever change between
     * seeks in the same app/screen (it's a fixed layout position, not
     * content-driven) - `uiautomator dump` itself is consistently the
     * dominant cost of a seek (~2.4s measured live, every time, versus
     * <100ms for the actual keyevent/tap), so re-discovering those bounds
     * on every single tap was pure waste. Cached here after the first
     * successful read; cleared automatically if a tap-time lookup ever
     * misses (covers switching to a different video/app whose seek bar
     * genuinely sits somewhere else) so it self-heals without the user
     * needing to do anything.
     */
    @Volatile private var cachedSeekBarBounds: SeekBarBounds? = null

    suspend fun keyEvent(code: Int): Result<Unit> {
        val result = connectionManager.withDadb { dadb -> dadb.shell("input keyevent $code") }
        result.exceptionOrNull()?.let { Log.e(TAG, "keyEvent($code) failed: ${it.message}", it) }
        return result.map { }
    }

    /**
     * Taps at an absolute screen coordinate via the same `input tap` shell
     * command the D-pad's keyevents already use reliably. Cursor-mode clicks
     * route through here rather than the companion app's AccessibilityService
     * gesture dispatch, which this TV's OS build cancels instantly for
     * synthesized single-point gestures.
     */
    suspend fun tap(x: Int, y: Int): Result<Unit> {
        val result = connectionManager.withDadb { dadb -> dadb.shell("input tap $x $y") }
        result.exceptionOrNull()?.let { Log.e(TAG, "tap($x, $y) failed: ${it.message}", it) }
        return result.map { }
    }

    /**
     * Scrolls/seeks via a plain `input swipe` gesture - same reasoning as
     * `tap()`: this TV's OS build cancels the companion app's
     * AccessibilityService dispatchGesture() for synthesized gestures, so
     * scroll goes through plain ADB shell instead of adding a TV-side
     * command for it.
     */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Result<Unit> {
        val result = connectionManager.withDadb { dadb -> dadb.shell("input swipe $x1 $y1 $x2 $y2 $durationMs") }
        result.exceptionOrNull()?.let { Log.e(TAG, "swipe($x1,$y1 -> $x2,$y2) failed: ${it.message}", it) }
        return result.map { }
    }

    suspend fun sendText(text: String): Result<Unit> {
        // Android's `input text` treats spaces specially and chokes on shell
        // metacharacters, so escape for the shell and swap literal spaces for
        // the %s placeholder input text itself recognizes.
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace(" ", "%s")
        val result = connectionManager.withDadb { dadb -> dadb.shell("input text '$escaped'") }
        result.exceptionOrNull()?.let { Log.e(TAG, "sendText failed: ${it.message}", it) }
        return result.map { }
    }

    suspend fun launchApp(packageName: String): Result<Unit> {
        val result = connectionManager.withDadb { dadb -> dadb.shell("monkey -p $packageName -c android.intent.category.LAUNCHER 1") }
        result.exceptionOrNull()?.let { Log.e(TAG, "launchApp($packageName) failed: ${it.message}", it) }
        return result.map { }
    }

    /** Installed apps with a launcher entry, via `pm list packages -3` (third-party only) plus labels. */
    suspend fun listLaunchableApps(): Result<List<InstalledApp>> {
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("pm list packages -3")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            response.output.lineSequence()
                .mapNotNull { line -> line.removePrefix("package:").trim().takeIf { it.isNotBlank() } }
                .map { InstalledApp(packageName = it, label = guessLabel(it)) }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "listLaunchableApps failed: ${it.message}", it) }
        return result
    }

    /**
     * Reads the TV's own MAC address via `ip addr show` (per-interface
     * "cat /sys/class/net/<iface>/address" is permission-denied on this TV's
     * shell, but plain `ip addr show` still prints it). Prefers wlan0 since
     * most TVs are on Wi-Fi; falls back to eth0. Used once after connecting
     * so Wake-on-LAN has a target even while the TV is fully asleep and
     * unreachable over ADB.
     */
    suspend fun fetchMacAddress(): Result<String?> {
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("ip addr show")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            parseMacAddress(response.output, "wlan0") ?: parseMacAddress(response.output, "eth0")
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "fetchMacAddress failed: ${it.message}", it) }
        return result
    }

    /**
     * Ensures the TV companion's cursor accessibility service is enabled -
     * confirmed live that `adb shell settings put secure
     * enabled_accessibility_services` works without root (ADB's shell UID
     * is specifically trusted with WRITE_SECURE_SETTINGS for exactly this
     * kind of setting). The setting is one colon-separated string listing
     * EVERY enabled accessibility service across all apps (confirmed live:
     * this TV had Zank Remote, Projectivy, and Home Button entries besides
     * the companion's own) - read-modify-write, not overwrite, so other
     * apps' accessibility features aren't silently disabled. No-ops if the
     * service is already in the list. Also ensures the global
     * accessibility_enabled flag itself is on.
     */
    suspend fun ensureCursorAccessibilityEnabled(serviceComponent: String): Result<Boolean> {
        val result = connectionManager.withDadb { dadb ->
            val currentList = dadb.shell("settings get secure enabled_accessibility_services").output.trim()
            val services = currentList.split(":").map { it.trim() }.filter { it.isNotBlank() && it != "null" }
            val alreadyEnabled = services.contains(serviceComponent)
            if (!alreadyEnabled) {
                val newList = (services + serviceComponent).joinToString(":")
                dadb.shell("settings put secure enabled_accessibility_services '$newList'")
            }
            val globalEnabled = dadb.shell("settings get secure accessibility_enabled").output.trim()
            if (globalEnabled != "1") {
                dadb.shell("settings put secure accessibility_enabled 1")
            }
            !alreadyEnabled || globalEnabled != "1"
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "ensureCursorAccessibilityEnabled failed: ${it.message}", it) }
        return result
    }

    /**
     * Reads the active media session via `dumpsys media_session` to drive
     * the Now Playing tab. Not every app populates title/position/duration
     * in its session metadata - live/sports/news apps (confirmed directly:
     * Fox One playing a live game reports state=playing but
     * metadata=null, position=-1) commonly only expose play/pause state and
     * the owning package. Callers degrade gracefully: show the app name
     * when there's no title, hide the progress bar when there's no
     * position/duration, but controls (play/pause/stop/volume) work either
     * way since those are plain keyevents, not session-dependent.
     */
    suspend fun fetchNowPlaying(): Result<NowPlayingInfo?> {
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("dumpsys media_session")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            parseNowPlaying(response.output)
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "fetchNowPlaying failed: ${it.message}", it) }
        return result
    }

    /**
     * Scrapes the on-screen current-time/duration text via `uiautomator
     * dump` - confirmed live against SmartTube: dumpsys media_session gives
     * position but NEVER duration on this Android build (checked AOSP
     * source: its dump formatter only ever prints title/subtitle text, no
     * other metadata keys, regardless of what the app actually sets), but
     * the on-screen seek bar always renders duration as literal visible
     * text since the app has to draw it for the user. uiautomator dump
     * requires the UI to be idle (fails outright while a video's actively
     * animating - confirmed directly), so this sends a DPAD_CENTER keyevent
     * first to surface the seek-bar overlay most players show.
     *
     * DPAD_CENTER is confirmed (live testing) to be a genuine play/pause
     * TOGGLE on SmartTube, not a separate "show overlay" gesture - there's
     * no input that surfaces the seek bar without also pausing. This is the
     * ONE place that manages that side effect (resuming if it was playing
     * before): a "Get duration" tap is a discovery action the user didn't
     * intend as "pause," so it shouldn't leave playback paused behind. Every
     * actual seek action (tapSeekBarPosition, called from the seek buttons
     * and drag) deliberately does NOT do this - once the overlay's already
     * up and the target's known, a seek should just move the position and
     * stop, not second-guess play state on every single nudge.
     *
     * The earlier version of this resume check pressed DPAD_CENTER a SECOND
     * time to "undo" the pause - that was the actual bug (DPAD_CENTER's
     * effect isn't reliably a clean toggle back). MEDIA_PLAY_PAUSE is the
     * correct, unambiguous keyevent for resuming, and only fires if the
     * session is specifically isPaused (state=2) after the nudge, not just
     * "not playing" (which also covers buffering right after the nudge).
     */
    suspend fun scrapeDurationFromScreen(): Result<ScrapedOverlayInfo?> {
        val result = connectionManager.withDadb { dadb ->
            val wasPlayingBefore = parseNowPlaying(dadb.shell("dumpsys media_session").output)?.isPlaying
            dadb.shell("input keyevent ${AndroidKeyCode.DPAD_CENTER}")
            kotlinx.coroutines.delay(200)
            // One dump serves the duration text, the seek bar's pixel
            // bounds (cached so later seeks skip this ~2.4s uiautomator
            // round trip entirely - see cachedSeekBarBounds doc), AND
            // title/subtitle text - the latter deliberately scraped here
            // too rather than left to dumpsys media_session's separate
            // metadata field, which was confirmed live to lag behind what's
            // actually on screen (dumpsys said "539K views" while this same
            // overlay showed "541K views" at that exact instant).
            val xml = dumpUiScreen(dadb)
            val timePair = parseTimePairFromUiDump(xml)
            val titleSubtitle = parseTitleSubtitleFromUiDump(xml)
            parseSeekBarBoundsFromUiDump(xml)?.let { cachedSeekBarBounds = it }
            if (wasPlayingBefore == true) {
                val afterInfo = parseNowPlaying(dadb.shell("dumpsys media_session").output)
                if (afterInfo?.isPaused == true) {
                    dadb.shell("input keyevent ${AndroidKeyCode.MEDIA_PLAY_PAUSE}")
                }
            }
            // Confirmed live against Netflix: `screencap` itself returns a
            // genuine 0-byte file and uiautomator dump returns zero text
            // nodes at all - Android's DRM content protection
            // (FLAG_SECURE on the video surface) blocks screen-reading
            // tools outright for protected playback, not something any
            // parsing improvement can work around. Detected by a dump with
            // no `text="..."` attributes anywhere, distinct from an app
            // that has plenty of on-screen text but just no duration
            // specifically (that case returns null below, not this).
            if (!xml.contains("""text="""")) {
                throw DrmProtectedScreenException()
            }
            if (timePair == null) null else ScrapedOverlayInfo(
                positionMs = timePair.first,
                durationMs = timePair.second,
                title = titleSubtitle?.first,
                subtitle = titleSubtitle?.second,
            )
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "scrapeDurationFromScreen failed: ${it.message}", it) }
        return result
    }

    /**
     * Direct seek: taps the on-screen seek bar widget at the exact pixel
     * position corresponding to [targetMs] out of [durationMs] - confirmed
     * live and reliable (tested twice against SmartTube: tapped for a
     * target of ~7:41 and ~1:00 out of 15:22, landed within 1 second both
     * times) that these players handle a plain `input tap` on their seek
     * bar exactly like a real touch, jumping straight there in one action.
     * This replaced an earlier approach that repeatedly pressed
     * fast-forward/rewind and re-checked position, approximating the
     * target through many small nudges - unnecessary once it was confirmed
     * the seek bar itself is a real clickable widget with known bounds
     * (`uiautomator dump` reports its resource-id and pixel bounds
     * directly), not just a read-only progress display.
     *
     * Uses cachedSeekBarBounds when available instead of re-running
     * `uiautomator dump` - measured live, that single command consistently
     * took ~2.4s on its own (versus <100ms each for the keyevent and tap),
     * so it was the entire reason a seek felt slow. The bar's screen
     * position is layout-driven, not content-driven, so it stays valid
     * across different videos in the same app; only falls back to a fresh
     * dump (and re-caches the result) when nothing's cached yet.
     */
    suspend fun tapSeekBarPosition(targetMs: Long, durationMs: Long): Result<Unit> {
        val result = connectionManager.withDadb { dadb ->
            // DPAD_CENTER surfaces the seek bar overlay (confirmed the only
            // reliable way to do so on SmartTube), which may pause playback
            // as a side effect on some apps - deliberately not managed here.
            // Whether/how playback resumes after a seek is entirely up to
            // the app itself (SmartTube auto-resumes, others may not) - not
            // something to compensate for from the phone side.
            dadb.shell("input keyevent ${AndroidKeyCode.DPAD_CENTER}")
            kotlinx.coroutines.delay(200)
            val bounds = cachedSeekBarBounds ?: dumpUiScreenAndParseSeekBarBounds(dadb)?.also { cachedSeekBarBounds = it }
                ?: throw IllegalStateException("Couldn't find the seek bar on screen")
            val fraction = (targetMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val tapX = (bounds.left + fraction * (bounds.right - bounds.left)).toInt()
            val tapY = (bounds.top + bounds.bottom) / 2
            Log.i(TAG, "tapSeekBarPosition($targetMs/$durationMs) -> tap ($tapX, $tapY) within ${bounds.left}..${bounds.right} (cached=${cachedSeekBarBounds != null})")
            dadb.shell("input tap $tapX $tapY")
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "tapSeekBarPosition($targetMs) failed: ${it.message}", it) }
        return result.map { }
    }

    /**
     * Dump, read, and delete in a SINGLE shell call (semicolon-chained)
     * instead of three separate `dadb.shell()` round trips - each one pays
     * its own ADB command overhead, and this was a real, measurable chunk
     * of the ~3s a seek took end to end.
     */
    private suspend fun dumpUiScreen(dadb: dadb.Dadb): String {
        val response = dadb.shell("uiautomator dump /sdcard/tv_file_bridge_ui.xml; cat /sdcard/tv_file_bridge_ui.xml; rm -f /sdcard/tv_file_bridge_ui.xml")
        if (response.output.contains("ERROR: could not")) {
            throw IllegalStateException("uiautomator dump failed: ${response.output.take(200)}")
        }
        // The dump command's own status line ("UI hierchary dumped to: ...")
        // precedes the XML in the combined output - strip it so downstream
        // regex parsing only sees the XML content.
        val xmlStart = response.output.indexOf("<?xml")
        return if (xmlStart >= 0) response.output.substring(xmlStart) else response.output
    }

    private suspend fun dumpUiScreenAndParseTimePair(dadb: dadb.Dadb): Pair<Long, Long>? {
        val xml = dumpUiScreen(dadb)
        val parsed = parseTimePairFromUiDump(xml)
        if (parsed == null) {
            Log.w(TAG, "dumpUiScreenAndParseTimePair: no time pair found, dump length=${xml.length}")
        }
        return parsed
    }

    private suspend fun dumpUiScreenAndParseSeekBarBounds(dadb: dadb.Dadb): SeekBarBounds? {
        val xml = dumpUiScreen(dadb)
        val bounds = parseSeekBarBoundsFromUiDump(xml)
        if (bounds == null) {
            Log.w(TAG, "dumpUiScreenAndParseSeekBarBounds: no seek bar found, dump length=${xml.length}")
        }
        return bounds
    }

    /**
     * Captures the TV's current screen via `screencap -p`, pulls it to
     * [localFile]. DRM-protected video content shows up as a solid black
     * frame in the capture (Prime Video, Netflix) rather than blocking the
     * command - that's let through as a legitimate result rather than
     * flagged as an error, since a genuinely black screen (a dark scene, a
     * loading screen) is indistinguishable from a DRM-blanked one by pixel
     * content alone, and guessing wrong would silently drop a real
     * screenshot the user actually wanted. Only a true capture failure -
     * screencap erroring outright, or producing a genuine 0-byte file
     * (confirmed live: this is what Netflix's screencap does specifically)
     * - is treated as an error.
     */
    suspend fun screenshot(localFile: File): Result<Unit> {
        val remotePath = "/sdcard/tv_file_bridge_screenshot.png"
        val result = connectionManager.withDadb { dadb ->
            val response = dadb.shell("screencap -p $remotePath")
            if (response.exitCode != 0) {
                throw IllegalStateException(response.errorOutput.ifBlank { response.output })
            }
            val sizeCheck = dadb.shell("wc -c < $remotePath")
            val remoteSize = sizeCheck.output.trim().toLongOrNull()
            if (remoteSize == null || remoteSize == 0L) {
                dadb.shell("rm -f $remotePath")
                throw DrmProtectedScreenException()
            }
            dadb.pull(localFile, remotePath)
            dadb.shell("rm -f $remotePath")
        }
        result.exceptionOrNull()?.let { Log.e(TAG, "screenshot failed: ${it.message}", it) }
        return result.map { }
    }
}

data class InstalledApp(val packageName: String, val label: String)

/** Thrown when a uiautomator dump comes back with zero on-screen text at all - confirmed live to mean Android's DRM content protection (FLAG_SECURE) is blocking screen-reading tools for this app's video surface (Netflix), not a missing-duration case that a retry could fix. */
class DrmProtectedScreenException : Exception("This app's screen can't be read - DRM-protected video content blocks screen capture on Android.")

/** Everything scrapeDurationFromScreen reads from one on-screen overlay dump - title/subtitle included since dumpsys media_session's own metadata is confirmed slower to update than what's actually rendered. */
data class ScrapedOverlayInfo(
    val positionMs: Long,
    val durationMs: Long,
    val title: String?,
    val subtitle: String?,
)

data class NowPlayingInfo(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    /** Specifically state=2 (paused) - distinct from isPlaying being false, since "not playing" also covers buffering (state=6) right after a seek, which must NOT be treated as paused-and-needing-resume. */
    val isPaused: Boolean,
    /** Milliseconds, null when the app doesn't report a real position (e.g. live streams). */
    val positionMs: Long?,
    val durationMs: Long?,
)

/**
 * Parses the top (most recently active) session out of `dumpsys
 * media_session`'s "Sessions Stack" block. Real sample line shapes this was
 * built against (captured live from a Sony Bravia):
 *   package=com.fox.foxone
 *   state=PlaybackState {state=3, position=-1, buffered position=-1, ...}
 *   metadata: null
 * state codes follow PlaybackState: 3=playing, 2=paused, 1=stopped, 6=buffering.
 * When metadata is present it looks like:
 *   metadata: size=3, description=..., mediaId=null, ...
 * but the exact metadata dump format varies enough across Android versions
 * that title/artist are pulled from the simpler `description=<title>, ...`
 * portion rather than a strict field-by-field parse.
 */
private fun parseNowPlaying(dumpOutput: String): NowPlayingInfo? {
    val lines = dumpOutput.lines()
    val packageIndex = lines.indexOfFirst { it.trimStart().startsWith("package=") }
    if (packageIndex == -1) return null
    val packageName = lines[packageIndex].substringAfter("package=").trim().takeIf { it.isNotBlank() } ?: return null

    val stateLine = lines.drop(packageIndex).firstOrNull { it.trimStart().startsWith("state=PlaybackState") } ?: return null
    val stateCode = Regex("state=(\\d+)").find(stateLine)?.groupValues?.get(1)?.toIntOrNull()
    val isPlaying = stateCode == 3
    val isPaused = stateCode == 2
    val position = Regex("position=(-?\\d+)").find(stateLine)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it >= 0 }

    val metadataLine = lines.drop(packageIndex).firstOrNull { it.trimStart().startsWith("metadata:") }
    var title: String? = null
    var artist: String? = null
    var duration: Long? = null
    if (metadataLine != null && !metadataLine.contains("metadata: null")) {
        // The "description=" field is actually MediaDescription's own
        // toString(): "title, subtitle, description" comma-joined (there's
        // no separate "subtitle=" key in this dump format - confirmed live
        // against SmartTube: "description=Video Title, Channel • 538K
        // views • Premiered..., null"). Split on the FIRST two commas only,
        // since the subtitle/description text itself commonly contains
        // commas (e.g. "538K views, premiered..."), which would otherwise
        // truncate it.
        val descriptionMatch = Regex("description=(.+)").find(metadataLine)?.groupValues?.get(1)
        if (descriptionMatch != null) {
            val parts = descriptionMatch.split(",", limit = 3)
            title = parts.getOrNull(0)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            artist = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        }
        duration = Regex("duration=(\\d+)").find(metadataLine)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    }

    return NowPlayingInfo(
        packageName = packageName,
        appLabel = guessLabel(packageName),
        title = title,
        artist = artist,
        isPlaying = isPlaying,
        isPaused = isPaused,
        positionMs = position,
        durationMs = duration,
    )
}

/**
 * Scans a `uiautomator dump` XML string for text nodes shaped like a
 * timestamp ("3:55", "1:23:45") and returns the first TWO as
 * (currentMs, totalMs) - generic across apps rather than matching a
 * specific app's resource-id (SmartTube's are current_time/total_time,
 * other apps use different ones), since the "current / total" pair
 * convention for a seek bar's time labels is near-universal even when the
 * exact view IDs aren't. Confirmed live against SmartTube's dump:
 * text="3:55" then text="/" then text="15:22", in that document order.
 */
private fun parseTimePairFromUiDump(dumpXml: String): Pair<Long, Long>? {
    val timePattern = Regex("""text="(\d{1,2}(?::\d{2}){1,2})"""")
    val matches = timePattern.findAll(dumpXml).map { it.groupValues[1] }.toList()
    if (matches.size < 2) return null
    val current = parseTimestamp(matches[0]) ?: return null
    val total = parseTimestamp(matches[1]) ?: return null
    if (total <= current) return null
    return current to total
}

private fun parseTimestamp(text: String): Long? {
    val parts = text.split(":").mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty() || parts.size > 3) return null
    var seconds = 0L
    for (part in parts) seconds = seconds * 60 + part
    return seconds * 1000
}

data class SeekBarBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Finds the on-screen seek bar's real tappable pixel bounds in a
 * `uiautomator dump`. Generic across apps: rather than matching a specific
 * resource-id (SmartTube's is "playback_progress"), looks for any
 * clickable node whose class name contains "SeekBar" - confirmed live
 * against SmartTube: `class="...misc.SeekBar" clickable="true"
 * bounds="[112,842][1808,898]"`, and a direct `input tap` within those
 * bounds (mapped from a target fraction of the bar's width) reliably
 * jumped playback straight to that position - a real seek, not an
 * approximation via repeated fast-forward/rewind presses.
 */
private fun parseSeekBarBoundsFromUiDump(dumpXml: String): SeekBarBounds? {
    // Attribute order within a <node .../> tag isn't guaranteed, so match
    // each whole node tag first, then pull class/clickable/bounds out of it
    // independently rather than relying on one fixed attribute sequence.
    val nodeTagPattern = Regex("""<node\b[^>]*/>""")
    for (nodeMatch in nodeTagPattern.findAll(dumpXml)) {
        val tag = nodeMatch.value
        val isSeekBarClass = Regex("""class="[^"]*SeekBar[^"]*"""").containsMatchIn(tag)
        val isClickable = tag.contains("""clickable="true"""")
        if (!isSeekBarClass || !isClickable) continue
        val boundsMatch = Regex("""bounds="\[(\d+),(\d+)]\[(\d+),(\d+)]"""").find(tag) ?: continue
        val (left, top, right, bottom) = boundsMatch.destructured
        return SeekBarBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
    return null
}

/**
 * Scrapes title/subtitle text directly from the on-screen overlay - a
 * DIFFERENT, fresher source than dumpsys media_session's metadata line.
 * Confirmed live: dumpsys reported "539K views" while the on-screen overlay
 * (captured in the exact same uiautomator dump used for duration/seek-bar
 * bounds) showed "541K views" at that same instant - SmartTube's own
 * session metadata publish lags behind what it actually renders. Generic
 * across apps: takes the first two substantial (>3 char, non-timestamp)
 * text nodes in document order, matching the title-then-subtitle layout
 * convention rather than a fixed resource-id.
 */
private fun parseTitleSubtitleFromUiDump(dumpXml: String): Pair<String, String?>? {
    val textPattern = Regex("""text="([^"]+)"""")
    val timestampPattern = Regex("""^\d{1,2}(?::\d{2}){1,2}$""")
    val candidates = textPattern.findAll(dumpXml)
        .map { unescapeXml(it.groupValues[1]) }
        .filter { it.length > 3 && !timestampPattern.matches(it) && it != "Remaining" }
        .toList()
    val title = candidates.getOrNull(0) ?: return null
    val subtitle = candidates.getOrNull(1)
    return title to subtitle
}

/**
 * uiautomator dump writes plain XML, so any non-ASCII character in an
 * app's on-screen text (emoji like 👍/👎 in SmartTube's like/dislike
 * counts, confirmed live) comes through as a numeric character reference
 * ("&#128077;") rather than the literal glyph - decodes both numeric
 * (&#NNNN; / &#xHHHH;) and the standard named XML entities.
 */
private fun unescapeXml(text: String): String {
    return Regex("&#x([0-9a-fA-F]+);|&#(\\d+);|&(amp|lt|gt|quot|apos);").replace(text) { match ->
        val (hex, dec, named) = match.destructured
        when {
            hex.isNotEmpty() -> String(Character.toChars(hex.toInt(16)))
            dec.isNotEmpty() -> String(Character.toChars(dec.toInt()))
            named == "amp" -> "&"
            named == "lt" -> "<"
            named == "gt" -> ">"
            named == "quot" -> "\""
            named == "apos" -> "'"
            else -> match.value
        }
    }
}

/** Extracts the `link/ether XX:XX:XX:XX:XX:XX` MAC line that follows an interface's own header line (e.g. `13: wlan0: <...>`) in `ip addr show` output. */
private fun parseMacAddress(ipAddrOutput: String, interfaceName: String): String? {
    val lines = ipAddrOutput.lines()
    val headerIndex = lines.indexOfFirst { it.trimStart().let { line -> line.contains(": $interfaceName:") || line.contains(": $interfaceName@") } }
    if (headerIndex == -1) return null
    for (i in (headerIndex + 1) until minOf(headerIndex + 4, lines.size)) {
        val macMatch = Regex("link/ether ([0-9a-fA-F:]{17})").find(lines[i])
        if (macMatch != null) return macMatch.groupValues[1].lowercase()
    }
    return null
}

/**
 * ADB shell has no clean way to read an app's compiled string-resource label
 * (that lives in resources.arsc, not something `pm`/`dumpsys` prints as text
 * without root/aapt on the TV) - so labels are best-effort, derived from the
 * package name. Good enough for a launcher grid; not meant to be exact.
 */
private val KNOWN_SUFFIXES = listOf(
    "livingroomplus", "androidtv", "android_tv", "androidtvunplugged",
    "tvunplugged", "android", "tv", "app",
)

// Build-flavor/variant names some apps (commonly sideloaded ones, e.g.
// SmartTube's "org.smarttube.stable") use as their actual LAST package
// segment - naively taking substringAfterLast('.') on those produces a
// meaningless label ("Stable") instead of the real app name, so those
// segments are skipped in favor of the one before them.
private val VARIANT_SEGMENTS = setOf("stable", "beta", "debug", "release", "free", "pro", "lite", "nightly", "dev")

private fun guessLabel(packageName: String): String {
    val segments = packageName.split(".")
    val nameSegment = segments.lastOrNull { it.lowercase() !in VARIANT_SEGMENTS } ?: segments.last()
    val cleaned = KNOWN_SUFFIXES.fold(nameSegment.lowercase()) { acc, suffix ->
        if (acc.endsWith(suffix) && acc.length > suffix.length) acc.removeSuffix(suffix) else acc
    }.ifBlank { nameSegment }
    return cleaned
        .replace(Regex("[_-]"), " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        .ifBlank { packageName }
}
