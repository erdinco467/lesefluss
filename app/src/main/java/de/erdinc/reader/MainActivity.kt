package de.erdinc.reader

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast

/** One screen of the app. build() is called every time the screen becomes visible (also after settings changed). */
abstract class Screen(val act: MainActivity) {
    abstract fun build(): View
    /** The screen gets hidden or the app goes to the background: stop timers, save the position. */
    open fun onLeave() {}
    /** Hide the system bars (reading screens). */
    open val immersive = false
}

class MainActivity : Activity() {
    lateinit var prefs: Prefs
    private lateinit var content: FrameLayout
    private lateinit var busy: FrameLayout
    private val stack = ArrayList<Screen>()
    private var wasPaused = false

    val pal get() = Ui.palette(prefs)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Library.ensureSample(this, prefs)
        content = FrameLayout(this)
        busy = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        setContentView(FrameLayout(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(busy, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        stack.add(LibraryScreen(this))
        refresh()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ---- navigation ----

    fun push(s: Screen) {
        stack.last().onLeave()
        stack.add(s)
        refresh()
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).onLeave()
        refresh()
    }

    fun replaceTop(s: Screen) {
        stack.removeAt(stack.size - 1).onLeave()
        stack.add(s)
        refresh()
    }

    /** Back to the library, whatever is open. */
    fun home() {
        while (stack.size > 1) stack.removeAt(stack.size - 1).onLeave()
        refresh()
    }

    fun refresh() {
        val top = stack.last()
        content.removeAllViews()
        content.addView(top.build(), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        applyBars()
    }

    @Suppress("DEPRECATION")
    private fun applyBars() {
        val p = pal
        val top = stack.last()
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        window.decorView.setBackgroundColor(p.bg)
        var flags = 0
        if (!p.dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (top.immersive) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.decorView.systemUiVisibility = flags
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && stack.isNotEmpty()) applyBars()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (busy.visibility == View.VISIBLE) return
        if (stack.size > 1) pop() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (stack.isNotEmpty()) stack.last().onLeave()
        Stats.flush(prefs)
        wasPaused = true
    }

    override fun onResume() {
        super.onResume()
        // Build the screen again so its timers start again and the numbers are fresh.
        if (wasPaused && busy.visibility != View.VISIBLE) refresh()
        wasPaused = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (stack.isNotEmpty()) {
            stack.last().onLeave()
            refresh()
        }
    }

    // ---- getting texts into the app ----

    fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/pdf", "application/epub+zip", "application/octet-stream"))
        }
        try {
            startActivityForResult(i, 1)
        } catch (_: Exception) {
            toast("Keine Dateien-App gefunden")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { importUri(it) }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null || i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                if (stream != null) importUri(stream)
                else if (!text.isNullOrBlank()) added(Library.add(this, i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "", text))
            }
            Intent.ACTION_VIEW -> i.data?.let { importUri(it) }
        }
        i.action = Intent.ACTION_MAIN // do not import the same thing again
    }

    private fun importUri(uri: Uri) {
        showBusy("Text wird eingelesen …")
        Thread {
            var result: Importer.Result? = null
            var error: String? = null
            try {
                result = Importer.read(this, uri)
            } catch (e: OutOfMemoryError) {
                error = "Die Datei ist zu groß für dieses Tablet"
            } catch (e: Throwable) {
                error = (e as? IllegalStateException)?.message ?: "Die Datei konnte nicht gelesen werden"
            }
            val book = result?.let { Library.add(this, it.title, it.text) }
            runOnUiThread {
                busy.visibility = View.GONE
                if (error != null) toast(error) else added(book)
            }
        }.start()
    }

    private fun added(book: Book?) {
        if (book == null) {
            toast("Darin steht kein Text")
            return
        }
        toast("„${book.title}“ hinzugefügt")
        home()
    }

    private fun showBusy(message: String) {
        val p = pal
        busy.removeAllViews()
        busy.setBackgroundColor(Ui.alpha(p.bg, 0.9f))
        busy.addView(
            Ui.label(this, message, 20f, p.text, true),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        busy.visibility = View.VISIBLE
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
