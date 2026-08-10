package com.applockFlutter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.*


class ForegroundService : Service() {

    companion object {
        // The single locked package the user has successfully entered the PIN for and is
        // still actively using. Cleared the instant the foreground moves to ANY other
        // package (another app, the launcher, or our own PinCodeActivity while it's
        // still awaiting a correct PIN).
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
        mHomeWatcher.setOnHomePressedListener(object : HomeWatcher.OnHomePressedListener {
            override fun onHomePressed() {
                unlockedPackage = null
            }
            override fun onHomeLongPressed() {
                unlockedPackage = null
            }
        })
        mHomeWatcher.startWatch()
        timerRun()
    }

    override fun onDestroy() {
        timer.cancel()
        mHomeWatcher.stopWatch()
        super.onDestroy()
    }

    private fun timerRun() {
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                isTimerStarted = true
                isServiceRunning()
            }
        }, 0, timerReload)
    }


    fun isServiceRunning() {

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

        if (latestResumedPackage == null) return

        if (lockedAppList.contains(latestResumedPackage)) {
            if (unlockedPackage != latestResumedPackage) {
                // Launch a REAL activity on top of the locked app, rather than a
                // floating overlay. This is the architectural fix for the
                // gesture-navigation "peek at previous app" bypass: a genuine
                // foreground Activity is what Android's own transition animations
                // show a live snapshot of, so the peek gesture now shows OUR pin
                // screen instead of the app underneath -- there's no overlay
                // z-order for the system to shuffle around in the first place.
                // singleInstance launchMode makes repeat calls here (e.g. this
                // same locked app resuming again before it's unlocked) safely
                // bring the existing instance back to front instead of duplicating it.
                val lockIntent = Intent(this, PinCodeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(PinCodeActivity.EXTRA_PACKAGE_TO_UNLOCK, latestResumedPackage)
                }
                startActivity(lockIntent)
            }
        } else {
            // Foreground moved to a different, non-locked package (another app, the
            // launcher, or our own PinCodeActivity while still awaiting a PIN) ->
            // forget the unlocked memory so returning to the locked app always asks again.
            unlockedPackage = null
        }
    }
}
