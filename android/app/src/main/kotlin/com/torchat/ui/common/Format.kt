package com.torchat.ui.common

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.torchat.R
import java.util.Calendar

/** Dates and times the way a chat app shows them. */
object Format {

    fun time(context: Context, ts: Long): String = DateFormat.getTimeFormat(context).format(ts)

    /** Chat-list timestamp: a time today, "Yesterday", a weekday, then a date. */
    fun short(context: Context, ts: Long): String {
        if (ts <= 0) return ""
        val days = daysAgo(ts)
        return when {
            days == 0 -> time(context, ts)
            days == 1 -> context.getString(R.string.yesterday)
            days < 7 -> DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            else -> DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
        }
    }

    /** The separator between days in a thread. */
    fun day(context: Context, ts: Long): String = when (daysAgo(ts)) {
        0 -> context.getString(R.string.today)
        1 -> context.getString(R.string.yesterday)
        else -> DateUtils.formatDateTime(
            context, ts,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

    /** "last seen 5 minutes ago", or "not connected yet". */
    fun lastSeen(context: Context, ts: Long): String =
        if (ts <= 0) {
            context.getString(R.string.never_connected)
        } else {
            context.getString(
                R.string.last_seen,
                DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }

    fun sameDay(a: Long, b: Long): Boolean {
        val first = Calendar.getInstance().apply { timeInMillis = a }
        val second = Calendar.getInstance().apply { timeInMillis = b }
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }

    /** A TorChat ID without its last group, for tight spaces. */
    fun shortId(id: String): String = id.split('-').take(3).joinToString("-")

    private fun daysAgo(ts: Long): Int {
        val then = Calendar.getInstance().apply {
            timeInMillis = ts
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return ((today.timeInMillis - then.timeInMillis) / DateUtils.DAY_IN_MILLIS).toInt()
    }
}
