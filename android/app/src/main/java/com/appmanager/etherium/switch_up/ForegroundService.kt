package com.applockFlutter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
        // package (another app, the launcher, or the recents/systemui overview UI).
        @Volatile
        var unlockedPackage: String? = null

        // The locked package we currently believe SHOULD be gated behind the PIN screen
        // right now (set the moment it's detected, cleared on a correct PIN). As long as
        // this is non-null, the watchdog below keeps re-asserting the overlay every tick
        // -- so even if the OS silently hides/dismisses it during a gesture-navigation
        // "peek" animation (which can happen WITHOUT any actual app switch, meaning our
        // usual detection logic never fires), it gets pulled back on screen almost
        // immediately instead of leaving the real app exposed underneath.
        @Volatile
        var pendingLockedPackage: String? = null
    }

    override fun onBind(intent: Intent): IBinder? {
        throw UnsupportedOperationException("")
    }
    var timer: Timer = Timer()
    var isTimerStarted = false
    var timerReload: Long = 250
    private var mHomeWatcher = HomeWatcher(this)

    override fun onCreate() {
        super.onCreate()
        val channelId = "AppLock-10"
        val channel = NotificationChannel(
            channelId,
            "Channel human readable title",
            NotificationManager.IMPORTANCE_MIN
        )
        channel.setShowBadge(false)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            channel
        )
        val noOpIntent = PendingIntent.getBroadcast(
            this, 0, Intent("com.applockFlutter.NOOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setContentIntent(noOpIntent)
            .setShowWhen(false)
            .build()
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
                pendingLockedPackage = null
                if (window.isOpen()) {
                    window.close()
                }
            }
            override fun onHomeLongPressed() {
                println("onHomeLongPressed")
                unlockedPackage = null
                pendingLockedPackage = null
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
        // ACTIVITY_RESUMED package seen -- the actual current foreground app.
        var latestResumedPackage: String? = null
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestResumedPackage = event.packageName
            }
        }

        if (latestResumedPackage != null) {
            if (lockedAppList.contains(latestResumedPackage)) {
                if (unlockedPackage != latestResumedPackage && pendingLockedPackage != latestResumedPackage) {
                    // Fresh arrival at a locked app that hasn't been unlocked yet on this visit.
                    pendingLockedPackage = latestResumedPackage
                    window.protectedPackage = latestResumedPackage
                    window.txtView?.visibility = View.INVISIBLE
                }
            } else {
                // Foreground genuinely moved to a different, non-locked package
                // (another app or the launcher) -> nothing left to gate.
                unlockedPackage = null
                pendingLockedPackage = null
            }
        }

        // Watchdog: as long as we believe a lock is currently owed, keep (re-)asserting
        // the overlay on every single tick. window.open() is already a safe no-op if the
        // view is still attached, so this costs nothing when everything is fine, but
        // self-heals within one tick (250ms) if the OS silently hid/dismissed the
        // overlay without any real app switch ever happening underneath it.
        if (pendingLockedPackage != null) {
            Handler(Looper.getMainLooper()).post {
                window.open()
            }
        }
    }
}
