package de.erdinc.reader

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** All personal settings on one page. Every change is saved at once. */
class SettingsScreen(act: MainActivity) : Screen(act) {
    private val prefs = act.prefs
    private var scroll: ScrollView? = null
    private var scrollY = 0

    override fun onLeave() {
        scroll?.let { scrollY = it.scrollY }
    }

    /** Colors changed: build the page again and stay at the same place. */
    private fun rebuild() {
        onLeave()
        act.refresh()
    }

    override fun build(): View {
        val c = act
        val p = act.pal
        val widthDp = c.resources.displayMetrics.widthPixels / c.resources.displayMetrics.density
        val side = if (widthDp > 700) 40 else 18

        val page = Ui.col(c).apply { setPadding(Ui.dp(c, side), Ui.dp(c, 18), Ui.dp(c, side), Ui.dp(c, 60)) }
        page.addView(Ui.row(c).apply {
            addView(Ui.iconButton(c, p, IconView.BACK, 48, false) { act.pop() })
            addView(Ui.space(c, 6, 1))
            addView(Ui.label(c, "Einstellungen", 26f, p.text, true))
        })

        // ---- Look ----
        val look = section(page, "Aussehen", null)
        chips(look, "Farbschema", Ui.themeNames, prefs.theme) { prefs.theme = it; rebuild() }
        look.addView(rowLabel("Akzentfarbe"))
        look.addView(Ui.row(c).apply {
            for ((i, color) in Ui.accents.withIndex()) {
                val selected = i == prefs.accent
                addView(FrameLayout(c).apply {
                    background = Ui.box(Color.TRANSPARENT, Ui.dp(c, 24).toFloat(), if (selected) p.text else Color.TRANSPARENT, Ui.dp(c, 2))
                    addView(View(c).apply { background = Ui.box(color, Ui.dp(c, 17).toFloat()) },
                        FrameLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34), Gravity.CENTER))
                    setOnClickListener { prefs.accent = i; rebuild() }
                }, LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)).apply { rightMargin = Ui.dp(c, 8) })
            }
        })
        chips(look, "Schrift beim Lesen", Ui.fontNames, prefs.font) { prefs.font = it; rebuild() }

        // ---- Speed mode ----
        val speed = section(page, "Speed-Modus", "Ein Wort nach dem anderen, immer an derselben Stelle.")
        slider(speed, "Tempo", 100, 1000, 10, prefs.wpm, { "$it Wörter pro Minute" }) { prefs.wpm = it }
        slider(speed, "Schriftgröße", 32, 140, 2, prefs.rsvpSize, { "$it" }) { prefs.rsvpSize = it }
        chips(speed, "Wörter auf einmal", listOf("1", "2", "3"), prefs.chunk - 1) { prefs.chunk = it + 1 }
        chips(speed, "Pause bei Satzzeichen", listOf("Aus", "Leicht", "Normal", "Stark"), prefs.pause) { prefs.pause = it }
        toggle(speed, "Fokus-Buchstabe", "Ein Buchstabe ist farbig. Schau nur auf ihn.", prefs.orp) { prefs.orp = it }
        toggle(speed, "Hilfslinien", "Zwei feine Linien zeigen, wo der Blick bleibt.", prefs.guides) { prefs.guides = it }
        toggle(speed, "Lange Wörter langsamer", "Lange Wörter und Zahlen bleiben etwas länger stehen.", prefs.longSlow) { prefs.longSlow = it }
        toggle(speed, "Sanfter Start", "Die ersten Wörter kommen langsamer, dann wird es schneller.", prefs.softStart) { prefs.softStart = it }
        toggle(speed, "Zurück zum Satzanfang", "Nach einer Pause geht es am Anfang des Satzes weiter.", prefs.rewind) { prefs.rewind = it }
        toggle(speed, "Satz bei Pause zeigen", "In der Pause siehst du den ganzen Satz zum Wort.", prefs.context) { prefs.context = it }

        // ---- Focus mode ----
        val focus = section(page, "Fokus-Modus", "Für wenig Konzentration: kleine Abschnitte, ein heller Satz, der Rest gedimmt.")
        val preview = TextView(c).apply {
            setTextColor(p.text)
            setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16))
            background = Ui.box(p.raised, Ui.dp(c, 16).toFloat())
        }
        fun updatePreview() {
            val sb = SpannableStringBuilder()
            for (w in "Die fetten Wortanfänge geben deinen Augen einen Halt beim Lesen.".split(' ')) {
                val from = sb.length
                sb.append(w)
                if (prefs.bionic) {
                    val letters = w.count { it.isLetter() }
                    val bold = Math.ceil(letters * prefs.bionicPct / 100.0).toInt().coerceIn(1, w.length)
                    sb.setSpan(StyleSpan(Typeface.BOLD), from, from + bold, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                sb.append(' ')
            }
            preview.text = sb
            preview.typeface = Ui.typeface(prefs.font)
            preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fSize.toFloat())
            preview.setLineSpacing(0f, prefs.fSpacing / 10f)
        }
        updatePreview()
        focus.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 14) })
        toggle(focus, "Fette Wortanfänge", "Anker für die Augen (Bionic Reading).", prefs.bionic) { prefs.bionic = it; updatePreview() }
        slider(focus, "Wie viel vom Wort fett ist", 25, 70, 5, prefs.bionicPct, { "$it %" }) { prefs.bionicPct = it; updatePreview() }
        slider(focus, "Schriftgröße", 16, 48, 1, prefs.fSize, { "$it" }) { prefs.fSize = it; updatePreview() }
        slider(focus, "Zeilenabstand", 11, 24, 1, prefs.fSpacing, { String.format("%.1f", it / 10f).replace('.', ',') }) { prefs.fSpacing = it; updatePreview() }
        slider(focus, "Länge eines Abschnitts", 20, 150, 10, prefs.fBlock, { "etwa $it Wörter" }) { prefs.fBlock = it }
        slider(focus, "Andere Sätze abdunkeln", 0, 85, 5, prefs.fDim, { if (it == 0) "Aus" else "$it %" }) { prefs.fDim = it }
        slider(focus, "Tempo des Lesezeigers", 80, 700, 10, prefs.fWpm, { "$it Wörter pro Minute" }) { prefs.fWpm = it }

        // ---- Sprints and goal ----
        val habit = section(page, "Sprints und Tagesziel", "Kurze Etappen machen das Anfangen leicht.")
        val sprintValues = listOf(0, 3, 5, 10, 15, 25)
        chips(habit, "Länge eines Lese-Sprints", sprintValues.map { if (it == 0) "Aus" else "$it Min" }, sprintValues.indexOf(prefs.sprint)) { prefs.sprint = sprintValues[it] }
        val goalValues = listOf(0, 1000, 2500, 5000, 10000)
        chips(habit, "Tagesziel in Wörtern", goalValues.map { if (it == 0) "Aus" else Ui.number(it) }, goalValues.indexOf(prefs.goal)) { prefs.goal = goalValues[it] }

        val sv = ScrollView(c).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(page)
        }
        scroll = sv
        val y = scrollY
        if (y > 0) sv.post { sv.scrollTo(0, y) }
        return sv
    }

    // ---- building blocks ----

    private fun section(page: LinearLayout, title: String, subtitle: String?): LinearLayout {
        val c = act
        val p = act.pal
        val card = Ui.col(c).apply {
            background = Ui.box(p.surface, Ui.dp(c, 22).toFloat(), p.faint, if (p.dark) 0 else Ui.dp(c, 1))
            setPadding(Ui.dp(c, 22), Ui.dp(c, 20), Ui.dp(c, 22), Ui.dp(c, 22))
            addView(Ui.label(c, title, 20f, p.accent, true))
            if (subtitle != null) {
                addView(Ui.space(c, 1, 6))
                addView(Ui.label(c, subtitle, 14.5f, p.dim).apply { setLineSpacing(0f, 1.25f) })
            }
        }
        // Not wider than a comfortable column on a big tablet.
        val wrap = FrameLayout(c)
        val maxWidth = Ui.dp(c, 760)
        val width = minOf(maxWidth, c.resources.displayMetrics.widthPixels - 2 * page.paddingLeft)
        wrap.addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        page.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(c, 18) })
        return card
    }

    private fun rowLabel(text: String): TextView =
        Ui.label(act, text, 16f, act.pal.text, true).apply { setPadding(0, Ui.dp(act, 22), 0, Ui.dp(act, 10)) }

    private fun chips(parent: LinearLayout, label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val c = act
        val p = act.pal
        parent.addView(rowLabel(label))
        val views = ArrayList<TextView>()
        fun style(current: Int) {
            for ((i, v) in views.withIndex()) {
                val on = i == current
                v.background = Ui.pressable(c, p, if (on) p.accent else p.raised, 21f)
                v.setTextColor(if (on) p.onAccent else p.text)
            }
        }
        val row = Ui.row(c)
        for ((i, name) in options.withIndex()) {
            val chip = Ui.label(c, name, 15f, p.text, true).apply {
                gravity = Gravity.CENTER
                minHeight = Ui.dp(c, 42)
                minWidth = Ui.dp(c, 54)
                setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0)
                setOnClickListener { style(i); onSelect(i) }
            }
            views.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.dp(c, 8) })
        }
        style(selected)
        parent.addView(android.widget.HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, stepSize: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
        val c = act
        val p = act.pal
        val valueLabel = Ui.label(c, format(value), 15f, p.accent, true)
        parent.addView(Ui.row(c).apply {
            setPadding(0, Ui.dp(c, 22), 0, Ui.dp(c, 4))
            addView(Ui.weight(Ui.label(c, label, 16f, p.text, true)))
            addView(valueLabel)
        })
        parent.addView(SeekBar(c).apply {
            this.max = (max - min) / stepSize
            progress = ((value - min) / stepSize).coerceIn(0, this.max)
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.dim)
            setPadding(Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = min + progress * stepSize
                    valueLabel.text = format(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = -Ui.dp(c, 10); rightMargin = -Ui.dp(c, 10)
        })
    }

    private fun toggle(parent: LinearLayout, label: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
        val c = act
        val p = act.pal
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(c).apply {
            isChecked = value
            thumbTintList = ColorStateList(states, intArrayOf(p.accent, p.dim))
            trackTintList = ColorStateList(states, intArrayOf(Ui.alpha(p.accent, 0.45f), p.faint))
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        parent.addView(Ui.row(c).apply {
            setPadding(0, Ui.dp(c, 20), 0, 0)
            addView(Ui.weight(Ui.col(c).apply {
                addView(Ui.label(c, label, 16f, p.text, true))
                addView(Ui.space(c, 1, 5))
                addView(Ui.label(c, hint, 14f, p.dim).apply { setLineSpacing(0f, 1.2f) })
            }))
            addView(Ui.space(c, 12, 1))
            addView(sw)
            setOnClickListener { sw.toggle() }
        })
    }
}
