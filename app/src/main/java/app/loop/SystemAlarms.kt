package app.loop

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/** Bridge to the phone's own clock app, so the strap alarm and the system alarm stay in step. */
object SystemAlarms {
    /** The next alarm the phone will ring (from any clock app), as epoch milliseconds, or null when none is set. */
    fun next(context: Context): Long? =
        runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime }.getOrNull()

    /** Creates an alarm in the phone's clock app. Returns false when no clock app handled it. */
    fun addToClock(context: Context, hour: Int, minute: Int, label: String): Boolean = runCatching {
        context.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    fun openClock(context: Context): Boolean = runCatching {
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
