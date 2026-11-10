package de.erdinc.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

class Book(val id: String, var title: String, var words: Int, var pos: Int, val added: Long, var last: Long)

/** The saved texts: a small index (books.json) and one text file per book, all inside the app's own storage. */
object Library {
    private var books: MutableList<Book>? = null
    private var cacheId: String? = null
    private var cacheDoc: Doc? = null

    private fun dir(c: Context) = File(c.filesDir, "books").apply { mkdirs() }
    private fun index(c: Context) = File(c.filesDir, "books.json")

    fun all(c: Context): MutableList<Book> {
        books?.let { return it }
        val list = ArrayList<Book>()
        try {
            val arr = JSONArray(index(c).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Book(o.getString("id"), o.getString("title"), o.getInt("words"), o.getInt("pos"), o.getLong("added"), o.getLong("last")))
            }
        } catch (_: Exception) {
            // first start or broken index: start empty
        }
        books = list
        return list
    }

    fun save(c: Context) {
        val arr = JSONArray()
        for (b in all(c)) {
            arr.put(JSONObject().put("id", b.id).put("title", b.title).put("words", b.words).put("pos", b.pos).put("added", b.added).put("last", b.last))
        }
        val tmp = File(c.filesDir, "books.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index(c))
    }

    /** Returns null when the text has no words. */
    fun add(c: Context, title: String, text: String): Book? {
        val doc = Text.parse(text)
        if (doc.size == 0) return null
        val now = System.currentTimeMillis()
        val name = title.trim().ifEmpty { doc.words.take(6).joinToString(" ") }.take(90)
        val book = Book(now.toString(36), name, doc.size, 0, now, now)
        File(dir(c), book.id + ".txt").writeText(text)
        all(c).add(book)
        save(c)
        cacheId = book.id
        cacheDoc = doc
        return book
    }

    fun delete(c: Context, b: Book) {
        File(dir(c), b.id + ".txt").delete()
        all(c).remove(b)
        if (cacheId == b.id) { cacheId = null; cacheDoc = null }
        save(c)
    }

    fun doc(c: Context, b: Book): Doc {
        if (cacheId == b.id) cacheDoc?.let { return it }
        val text = try { File(dir(c), b.id + ".txt").readText() } catch (_: Exception) { "" }
        val doc = Text.parse(text.ifBlank { "Dieser Text ist leer." })
        cacheId = b.id
        cacheDoc = doc
        return doc
    }

    fun ensureSample(c: Context, p: Prefs) {
        if (p.sp.getBoolean("sampleDone", false)) return
        p.sp.edit().putBoolean("sampleDone", true).apply()
        if (all(c).isEmpty()) add(c, "Willkommen bei Lesefluss", SAMPLE)
    }

    private const val SAMPLE = """Willkommen bei Lesefluss. Diese App hat zwei Arten zu lesen, und du kannst jederzeit zwischen ihnen wechseln.

Der Speed-Modus zeigt dir immer nur ein Wort, immer an derselben Stelle. Deine Augen müssen nicht mehr über die Zeile springen, und genau das spart die meiste Zeit. Ein Buchstabe ist farbig: Schau nur auf ihn, dann erkennt dein Gehirn das ganze Wort von selbst. Tippe in die Mitte für Start und Pause. Tippe links, um einen Satz zurückzuspringen, und rechts, um einen Satz zu überspringen.

Fang langsam an. 250 bis 300 Wörter pro Minute sind ein guter Start. Wenn es sich leicht anfühlt, geh in kleinen Schritten höher. Nach Punkten und Kommas macht die App von selbst eine kurze Pause, damit der Satz bei dir ankommt.

Der Fokus-Modus ist für Tage, an denen die Konzentration schnell weg ist. Du siehst nur einen kleinen Abschnitt, nie eine ganze volle Seite. Ein Satz ist hell, der Rest ist gedimmt. Tippe einfach, und der nächste Satz wird hell. Die fetten Wortanfänge geben deinen Augen einen Halt, damit sie nicht in der Zeile verrutschen. Wenn du magst, läuft ein Lesezeiger in deinem Tempo durch den Text und zieht dich mit.

Lies in Sprints. Fünf Minuten sind kurz genug, um einfach anzufangen. Danach sagt dir die App, wie viel du geschafft hast, und du entscheidest, ob du weitermachst. Kleine Etappen sind leichter als ein großer Berg.

In den Einstellungen kannst du alles an dich anpassen: Farben, Schrift, Größe, Tempo, Pausen und dein Tagesziel. Eigene Texte fügst du in der Bibliothek ein, oder du öffnest eine Datei als TXT, EPUB oder PDF. Viel Spaß beim Lesen."""
}

/** Reads a file the user picked or shared and turns it into plain text. Runs on a background thread. */
object Importer {
    class Result(val title: String, val text: String)

    private const val MAX_BYTES = 40L * 1024 * 1024

    fun read(c: Context, uri: Uri): Result {
        var name = "Text"
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> name = n }
            }
        } catch (_: Exception) {
            // some providers give no name
        }
        val mime = c.contentResolver.getType(uri) ?: ""
        val lower = name.lowercase()
        val title = name.substringBeforeLast('.')
        val input = c.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Datei lässt sich nicht öffnen")
        input.use {
            return when {
                lower.endsWith(".pdf") || mime == "application/pdf" -> Result(title, pdf(c, it))
                lower.endsWith(".epub") || mime == "application/epub+zip" -> epub(it, title)
                lower.endsWith(".html") || lower.endsWith(".htm") || mime == "text/html" -> Result(title, html(decode(bytes(it))))
                else -> Result(title, decode(bytes(it)))
            }
        }
    }

    private fun bytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IllegalStateException("Die Datei ist zu groß")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decode(b: ByteArray): String {
        val utf8 = String(b, Charsets.UTF_8)
        // Old German text files are often Windows-1252: then UTF-8 decoding leaves replacement characters.
        return if (utf8.count { it == '\uFFFD' } > 2) String(b, charset("windows-1252")) else utf8
    }

    private val dropBlocks = Regex("(?is)<(head|script|style)\\b.*?</\\1>")

    private fun html(source: String): String =
        Html.fromHtml(dropBlocks.replace(source, ""), Html.FROM_HTML_MODE_LEGACY).toString().replace("\uFFFC", "")

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)

    /** EPUB = zip with XHTML chapters. The .opf file lists them (manifest) and gives the reading order (spine). */
    private fun epub(input: InputStream, fallbackTitle: String): Result {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name.lowercase()
                if (!e.isDirectory && (n.endsWith(".opf") || n.endsWith(".xml") || n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"))) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_BYTES * 2) throw IllegalStateException("Das Buch ist zu groß")
                    files[e.name] = data
                }
            }
        }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        val opfPath = container?.let { attr(it, "full-path") } ?: files.keys.firstOrNull { it.lowercase().endsWith(".opf") }
        ?: throw IllegalStateException("Das ist kein lesbares EPUB")
        val opf = files[opfPath]?.toString(Charsets.UTF_8) ?: throw IllegalStateException("Das ist kein lesbares EPUB")
        val base = opfPath.substringBeforeLast('/', "")
        val hrefs = HashMap<String, String>()
        for (m in Regex("(?s)<(?:\\w+:)?item\\b[^>]*>").findAll(opf)) {
            val id = attr(m.value, "id") ?: continue
            hrefs[id] = attr(m.value, "href") ?: continue
        }
        val sb = StringBuilder()
        for (m in Regex("(?s)<(?:\\w+:)?itemref\\b[^>]*>").findAll(opf)) {
            val href = hrefs[attr(m.value, "idref") ?: continue] ?: continue
            val clean = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
            val path = if (base.isEmpty()) clean else "$base/$clean"
            val data = files[path] ?: continue
            val text = html(data.toString(Charsets.UTF_8)).trim()
            if (text.isNotEmpty()) sb.append(text).append("\n\n")
        }
        if (sb.isBlank()) throw IllegalStateException("In diesem EPUB wurde kein Text gefunden (vielleicht kopiergeschützt)")
        val title = Regex("(?s)<dc:title[^>]*>(.*?)</dc:title>").find(opf)?.groupValues?.get(1)?.let { html(it).trim() }
        return Result(title?.ifEmpty { null } ?: fallbackTitle, sb.toString())
    }

    private fun pdf(c: Context, input: InputStream): String {
        PDFBoxResourceLoader.init(c.applicationContext)
        val raw = PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) throw IllegalStateException("Dieses PDF ist geschützt")
            PDFTextStripper().getText(doc)
        }
        val text = unwrap(raw)
        if (text.isBlank()) throw IllegalStateException("In diesem PDF steht kein Text (vermutlich nur Bilder)")
        return text
    }

    /**
     * A PDF gives one text line per printed line. Join them back to paragraphs: a paragraph ends at an empty
     * line, or at a line that ends a sentence and is clearly shorter than a full line.
     */
    private fun unwrap(raw: String): String {
        val lines = raw.replace("\r", "").split('\n').map { it.trim() }
        val lengths = lines.map { it.length }.filter { it > 20 }.sorted()
        val full = if (lengths.isEmpty()) 60 else lengths[lengths.size * 3 / 4]
        val sb = StringBuilder()
        var open = false // inside a paragraph
        for (line in lines) {
            if (line.isEmpty()) {
                if (open) { sb.append("\n\n"); open = false }
                continue
            }
            if (open) {
                val joinsWord = sb.length > 1 && sb[sb.length - 1] == '-' && sb[sb.length - 2].isLetter() && line[0].isLowerCase()
                if (joinsWord) sb.setLength(sb.length - 1) else sb.append(' ')
            }
            sb.append(line)
            open = true
            if (line.length < full * 0.72 && (Text.endsSentence(line.substringAfterLast(' ')) || line.length < full * 0.4)) {
                sb.append("\n\n")
                open = false
            }
        }
        return sb.toString()
    }
}
