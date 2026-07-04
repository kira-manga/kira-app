package me.manga.yamiapk.core.util.date

import android.content.Context
import me.manga.yamiapk.R
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object Date {

    fun LocalDate.toRelativeString(context: Context, now: LocalDate = LocalDate.now()): String =
        when {
            this == now            -> context.getString(R.string.date_today)
            this == now.minusDays(1) -> context.getString(R.string.date_yesterday)
            else                   -> this.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
        }

    fun LocalDate.daysSince(): Long {
        return this.until(LocalDate.now(), ChronoUnit.DAYS)
    }

    fun LocalDateTime.timeAgo(context: Context): String {
        val now = LocalDateTime.now()
        val seconds = ChronoUnit.SECONDS.between(this, now)
        return when {
            seconds < 0 -> ""  // in the future?
            seconds < 60 -> context.getString(R.string.time_just_now)
            seconds < 3_600 -> {
                val m = seconds / 60
                context.resources.getQuantityString(R.plurals.time_minutes_ago, m.toInt(), m)
            }
            seconds < 86_400 -> {
                val h = seconds / 3_600
                context.resources.getQuantityString(R.plurals.time_hours_ago, h.toInt(), h)
            }
            seconds < 172_800 -> context.getString(R.string.time_yesterday)
            seconds < 604_800 -> {
                val d = seconds / 86_400
                context.resources.getQuantityString(R.plurals.time_days_ago, d.toInt(), d)
            }
            seconds < 2_592_000 -> {
                val w = seconds / 604_800
                context.resources.getQuantityString(R.plurals.time_weeks_ago, w.toInt(), w)
            }
            seconds < 31_536_000 -> {
                val mo = seconds / 2_592_000
                context.resources.getQuantityString(R.plurals.time_months_ago, mo.toInt(), mo)
            }
            else -> {
                val y = seconds / 31_536_000
                context.resources.getQuantityString(R.plurals.time_years_ago, y.toInt(), y)
            }
        }
    }
}