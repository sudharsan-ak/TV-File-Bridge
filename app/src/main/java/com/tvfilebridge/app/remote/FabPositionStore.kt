package com.tvfilebridge.app.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fabPositionDataStore by preferencesDataStore(name = "remote_fab_positions")

enum class RemoteFab { KEYBOARD, MOVE_CURSOR, SCROLL_SEEK }

data class FabOffset(val x: Float, val y: Float)

/**
 * Persists where each of the three floating buttons (Keyboard, Move cursor,
 * Scroll/Seek) was last dragged to on the Remote screen - offsets in dp from
 * the controls container's top-left corner. Saved per-button so dragging one
 * doesn't disturb the others, and read back on next launch so a dragged
 * position sticks until manually moved again rather than resetting to a
 * default every time the screen reopens.
 */
class FabPositionStore(private val context: Context) {

    private fun xKey(fab: RemoteFab) = floatPreferencesKey("${fab.name}_x")
    private fun yKey(fab: RemoteFab) = floatPreferencesKey("${fab.name}_y")

    /** Null until the user has dragged this FAB at least once - caller falls back to its own default position. */
    fun position(fab: RemoteFab): Flow<FabOffset?> = context.fabPositionDataStore.data.map { prefs ->
        val x = prefs[xKey(fab)]
        val y = prefs[yKey(fab)]
        if (x != null && y != null) FabOffset(x, y) else null
    }

    suspend fun setPosition(fab: RemoteFab, x: Float, y: Float) {
        context.fabPositionDataStore.edit { prefs ->
            prefs[xKey(fab)] = x
            prefs[yKey(fab)] = y
        }
    }

    /** Clears all saved positions so every FAB falls back to its default spot again. */
    suspend fun resetAll() {
        context.fabPositionDataStore.edit { prefs ->
            RemoteFab.entries.forEach { fab ->
                prefs.remove(xKey(fab))
                prefs.remove(yKey(fab))
            }
        }
    }
}
