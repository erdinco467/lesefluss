package de.erdinc.reader

import android.content.Context
import java.util.TimeZone

/** All personal settings. Every value is saved at once and read again when a screen is built. */
class Prefs(ctx: Context) {
    val sp = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    // Look
    var theme get() = int("theme", 0); set(v) = put("theme", v)
    var accent get() = int("accent", 0); set(v) = put("accent", v)
    var font get() = int("font", 0); set(v) = put("font", v)

    // Speed mode (one word at a time)
    var wpm get() = int("wpm", 300); set(v) = put("wpm", v.coerceIn(100, 1000))
    var chunk get() = int("chunk", 1); set(v) = put("chunk", v)
    var rsvpSize get() = int("rsvpSize", 64); set(v) = put("rsvpSize", v)
    /** 0 = off, 1 = light, 2 = normal, 3 = strong */
    var pause get() = int("pause", 2); set(v) = put("pause", v)
    var longSlow get() = bool("longSlow", true); set(v) = put("longSlow", v)
    var softStart get() = bool("softStart", true); set(v) = put("softStart", v)
    var orp get() = bool("orp", true); set(v) = put("orp", v)
    var guides get() = bool("guides", true); set(v) = put("guides", v)
    var context get() = bool("context", true); set(v) = put("context", v)
    var rewind get() = bool("rewind", true); set(v) = put("rewind", v)

    // Focus mode (ADHD reader)
    var fWpm get() = int("fWpm", 220); set(v) = put("fWpm", v.coerceIn(80, 700))
    var fSize get() = int("fSize", 26); set(v) = put("fSize", v)
    /** Line spacing times 10 (16 = 1.6) */
    var fSpacing get() = int("fSpacing", 16); set(v) = put("fSpacing", v)
    var fBlock get() = int("fBlock", 60); set(v) = put("fBlock", v)
    var bionic get() = bool("bionic", true); set(v) = put("bionic", v)
    var bionicPct get() = int("bionicPct", 45); set(v) = put("bionicPct", v)
    /** How much the sentences around the active one fade, in percent */
    var fDim get() = int("fDim", 65); set(v) = put("fDim", v)

    // Sprints and daily goal
    /** Minutes, 0 = off */
    var sprint get() = int("sprint", 5); set(v) = put("sprint", v)
    /** Words per day, 0 = off */
    var goal get() = int("goal", 2500); set(v) = put("goal", v)
}

/** Words read today and days in a row. Buffered so the reader does not write to disk for every word. */
object Stats {
    private var buffer = 0

    private fun dayNow(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L
    }

    fun add(p: Prefs, n: Int) {
        if (n <= 0) return
        buffer += n
        if (buffer >= 40) flush(p)
    }

    fun flush(p: Prefs) {
        if (buffer == 0) return
        val day = dayNow()
        val last = p.sp.getLong("statDay", -1)
        var today = p.sp.getInt("statToday", 0)
        var streak = p.sp.getInt("statStreak", 0)
        if (day != last) {
            streak = if (day == last + 1) streak + 1 else 1
            today = 0
        }
        p.sp.edit()
            .putLong("statDay", day)
            .putInt("statToday", today + buffer)
            .putInt("statStreak", streak)
            .putLong("statTotal", p.sp.getLong("statTotal", 0) + buffer)
            .apply()
        buffer = 0
    }

    fun today(p: Prefs): Int = (if (p.sp.getLong("statDay", -1) == dayNow()) p.sp.getInt("statToday", 0) else 0) + buffer

    fun streak(p: Prefs): Int {
        val last = p.sp.getLong("statDay", -1)
        val day = dayNow()
        return if (last == day || last == day - 1) p.sp.getInt("statStreak", 0) else if (buffer > 0) 1 else 0
    }
}
