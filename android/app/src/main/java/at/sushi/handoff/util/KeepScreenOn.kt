package at.sushi.handoff.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import at.sushi.handoff.KeepScreenOnMode

/** Issue #218 -- how the "keep screen on" setting maps to the footer toggle's value. */
object KeepScreenOn {
    /** The value the toggle takes at service start and whenever the setting is saved. */
    fun initialValue(mode: KeepScreenOnMode, charging: Boolean): Boolean = when (mode) {
        KeepScreenOnMode.OFF -> false
        KeepScreenOnMode.ON -> true
        KeepScreenOnMode.CHARGING -> charging
    }

    /** The toggle's value after a charger connects. Only [KeepScreenOnMode.CHARGING] reacts to
     *  it; Off/On are fixed overrides, so plugging in leaves the current value (including the
     *  pilot's own footer flip) alone. */
    fun onPowerConnected(mode: KeepScreenOnMode, current: Boolean): Boolean =
        if (mode == KeepScreenOnMode.CHARGING) true else current

    /** ACTION_BATTERY_CHANGED is a sticky broadcast -- registering for it with a null receiver
     *  returns the current battery state synchronously instead of waiting for the next change,
     *  which is what makes this usable as a one-shot "is it charging right now" check.
     *  EXTRA_PLUGGED is 0 when on battery, non-zero for AC/USB/wireless. */
    fun isCharging(context: Context): Boolean {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged != 0
    }
}
