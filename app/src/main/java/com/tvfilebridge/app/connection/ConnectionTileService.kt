package com.tvfilebridge.app.connection

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings toggle for the user-controlled foreground service (see
 * ConnectionForegroundService) - lets the user opt into "hold the TV
 * connection open in the background" only when they actually want it, and
 * drop back to normal (unpredictable background survival) by tapping it off.
 */
class ConnectionTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ConnectionForegroundService::class.java)
        if (isServiceRunning()) {
            stopService(intent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        updateTile()
    }

    private fun updateTile() {
        qsTile?.apply {
            state = if (isServiceRunning()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "TV Connection"
            // Without this the tile renders as a plain, iconless white/gray
            // circle - the manifest's icon and the one passed to
            // requestAddTileService() only cover the "add tile" preview, the
            // live tile needs its own Tile.icon set explicitly.
            //
            // Deliberately NOT Icon.createWithResource(..., R.drawable.ic_tv_tile):
            // on this device (mismatched physical/override density) inflating
            // that vector throws "viewportWidth > 0" even though the compiled
            // resource is verified correct - a platform bug in that specific
            // inflate path. Drawing the same ring+dot shape with raw
            // Canvas/Path primitives sidesteps VectorDrawable inflation
            // entirely and can't hit that bug.
            icon = Icon.createWithBitmap(ringIconBitmap())
            updateTile()
        }
    }

    private fun ringIconBitmap(): Bitmap {
        val sizePx = (24 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val scale = sizePx / 108f
        val cx = 54f * scale
        val cy = 54f * scale
        val radius = 27f * scale
        val strokeWidth = 11f * scale

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
        }
        val ringRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(ringRect, -30f, 300f, false, ringPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val dotRadius = 7f * scale
        val gapAngleRad = Math.toRadians(-45.0)
        val dotX = cx + radius * Math.cos(gapAngleRad).toFloat()
        val dotY = cy + radius * Math.sin(gapAngleRad).toFloat()
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

        return bitmap
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == ConnectionForegroundService::class.java.name }
    }
}
