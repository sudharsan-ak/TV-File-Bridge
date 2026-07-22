package com.tvfilebridge.app

import android.app.Application
import android.os.Looper
import android.util.Log

class TvFileBridgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installBackgroundThreadSafetyNet()
        container = AppContainer(this)
    }

    /**
     * dadb's TcpForwarder (used for cursor mode) runs its own background
     * thread pool that we don't control; a transient failure there (e.g. a
     * stream closing mid-forward while reconnecting) throws on that pool's
     * thread with no app-level try/catch reachable, which otherwise crashes
     * the whole process. This only swallows exceptions from dadb's own
     * package on non-main threads - anything on the main thread, or from
     * our own code, still crashes normally so real bugs stay visible.
     */
    private fun installBackgroundThreadSafetyNet() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isMainThread = thread === Looper.getMainLooper().thread
            val isFromDadb = throwable.stackTrace.any { it.className.startsWith("dadb.") }
            if (!isMainThread && isFromDadb) {
                Log.e("TvFileBridgeApp", "swallowed background dadb exception on ${thread.name}: ${throwable.message}", throwable)
            } else {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
