package com.applockFlutter

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.andrognito.pinlockview.IndicatorDots
import com.andrognito.pinlockview.PinLockListener
import com.andrognito.pinlockview.PinLockView

class PinCodeActivity : AppCompatActivity() {

    companion object {
        const val TAG = "PinCodeActivity"
        const val EXTRA_PACKAGE_TO_UNLOCK = "package_to_unlock"
    }

    private var mPinLockView: PinLockView? = null
    private var mIndicatorDots: IndicatorDots? = null
    private var txtView: TextView? = null
    private var pinCode: String = ""
    private var protectedPackage: String? = null

    private val mPinLockListener: PinLockListener = object : PinLockListener {
        override fun onComplete(pin: String) {
            pinCode = pin
            checkPin()
        }
        override fun onEmpty() {}
        override fun onPinChange(pinLength: Int, intermediatePin: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pin_activity)

        protectedPackage = intent.getStringExtra(EXTRA_PACKAGE_TO_UNLOCK)

        mPinLockView = findViewById(R.id.pin_lock_view)
        mIndicatorDots = findViewById(R.id.indicator_dots)
        txtView = findViewById(R.id.alertError)

        mPinLockView!!.attachIndicatorDots(mIndicatorDots)
        mPinLockView!!.setPinLockListener(mPinLockListener)
        mPinLockView!!.pinLength = 6
        mPinLockView!!.textColor = ContextCompat.getColor(this, R.color.ic_launcher_background)
        mIndicatorDots!!.indicatorType = IndicatorDots.IndicatorType.FILL_WITH_ANIMATION
    }

    override fun onResume() {
        super.onResume()
        // Every time this screen becomes visible again (including if the user briefly
        // navigated elsewhere within our own task), refresh which package we're guarding
        // in case a fresh unlock request supersedes an older one.
        intent.getStringExtra(EXTRA_PACKAGE_TO_UNLOCK)?.let { protectedPackage = it }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // singleInstance launchMode means a repeat startActivity() call (e.g. the
        // same locked app resuming again before it's unlocked) reuses this instance
        // and delivers here instead of onCreate. Make sure our tracked intent updates.
        setIntent(intent)
    }

    private fun checkPin() {
        try {
            mPinLockView!!.resetPinLockView()
            val saveAppData: SharedPreferences = getSharedPreferences("save_app_data", Context.MODE_PRIVATE)
            val dta: String = saveAppData.getString("password", "PASSWORD")!!
            if (pinCode == dta) {
                ForegroundService.unlockedPackage = protectedPackage
                ForegroundService.pendingLockedPackage = null
                finish()
            } else {
                txtView?.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            println("$e---------------checkPin")
        }
    }

    // Block the back gesture/button from ever revealing the app underneath. Instead of
    // finishing (which would drop back to whatever this task's previous entry was),
    // send the user to the home screen -- exactly like tapping Home would.
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // This activity intentionally does NOT finish on its own when paused/stopped
    // (e.g. user presses Home from here). ForegroundService's independent polling loop
    // is what decides whether to relaunch this screen the next time the guarded app (or
    // any locked app) resumes -- this activity's only job is to be an unmissable,
    // real foreground task while a PIN is owed, so that Android's gesture-navigation
    // "peek at previous app" animation shows OUR screen, not the app underneath.
}
