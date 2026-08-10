package com.applockFlutter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import androidx.core.app.NotificationCompat
import java.util.*


class ForegroundService : Service() {

    companion object {
        // The single locked package the user has successfully entered the PIN for and is
        // still actively using. Cleared the instant the foreground moves to ANY other
        // package (including the launcher/recents UI during a quick swipe-peek), so a
        // fast up-then-down recents gesture can no longer skip the PIN screen.
        @Volatile
        var unlockedPackage: String? = null
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("")
    }
    var timer: Timer = Timer()
    var isTimerStarted = false
    var timerReload: Long = 500
    private var mHomeWatcher = HomeWatcher(this)

    override fun onCreate() {
        super.onCreate()
        val channelId = "AppLock-10"
        val channel = NotificationChannel(
            channelId,
            "Channel human readable title",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            channel
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("").build()
        startForeground(1, notification)
        startMyOwnForeground()

    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startMyOwnForeground() {
        val window = Window(this)
        mHomeWatcher.setOnHomePressedListener(object : HomeWatcher.OnHomePressedListener {
            override fun onHomePressed() {
                println("onHomePressed")
                unlockedPackage = null
                if (window.isOpen()) {
                    window.close()
                }
            }
            override fun onHomeLongPressed() {
                println("onHomeLongPressed")
                unlockedPackage = null
                if (window.isOpen()) {
                    window.close()
                }
            }
        })
        mHomeWatcher.startWatch()
        timerRun(window)
    }

    override fun onDestroy() {
        timer.cancel()
        mHomeWatcher.stopWatch()
        super.onDestroy()
    }

    private fun timerRun(window: Window) {
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                isTimerStarted = true
                isServiceRunning(window)
            }
        }, 0, timerReload)
    }


    fun isServiceRunning(window: Window) {

        val saveAppData: SharedPreferences = applicationContext.getSharedPreferences("save_app_data", Context.MODE_PRIVATE)
        val lockedAppList: List<String> = saveAppData.getString("app_data", "AppList")!!
            .replace("[", "").replace("]", "").split(",").map { it.trim() }

        val mUsageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()

        val usageEvents = mUsageStatsManager.queryEvents(time - timerReload, time)
        val event = UsageEvents.Event()

        // Walk every event in this polling window and remember only the LAST
        // ACTIVITY_RESUMED package seen. That is the actual current foreground app,
        // regardless of whether the previous app ever received a matching
        // ACTIVITY_STOPPED event (a fast recents swipe can pause without stopping it).
        var latestResumedPackage: String? = null
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestResumedPackage = event.packageName
            }
        }

        // Nothing changed foreground this tick -> nothing to do.
        if (latestResumedPackage == null) return

        if (lockedAppList.contains(latestResumedPackage)) {
            if (unlockedPackage != latestResumedPackage) {
                // Arriving at a locked app that hasn't been unlocked yet on this visit
                // (fresh open, OR returning from anywhere else, including a recents peek).
                window.protectedPackage = latestResumedPackage
                window.txtView!!.visibility = View.INVISIBLE
                Handler(Looper.getMainLooper()).post {
                    window.open()
                }
            }
            // else: still inside the same app the user already unlocked -> don't re-prompt.
        } else {
            // Foreground moved to a different package entirely (another app, the
            // launcher, or the recents/systemui overview UI) -> forget the unlocked
            // memory so returning to the locked app always asks again.
            unlockedPackage = null
        }
    }
}
