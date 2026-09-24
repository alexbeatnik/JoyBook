package com.local.joybook

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.Executors

/**
 * In-memory library plus slow per-book data (tags, cover, chapter durations) filled in the
 * background. Durations are cached on disk forever so book progress / time left work offline.
 * Main-thread API; listeners are told when something new arrives.
 */
object BookData {
    private const val DURATIONS = "JoyBookDurations"

    /** Last scan result. */
    var books: List<Book> = emptyList()

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val covers = HashMap<String, Bitmap?>()
    private val pending = Collections.synchronizedSet(HashSet<String>())

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = listeners.toList().forEach { it() }

    fun find(id: String): Book? = books.firstOrNull { it.id == id }

    // ---- Tags & cover ------------------------------------------------------------------

    fun info(ctx: Context, book: Book): BookInfo? = Prefs.bookInfo(ctx, book.id)

    /** Cover for [book] if already loaded; null otherwise (call [load] to fetch it). */
    fun cover(book: Book): Bitmap? = covers[book.id]

    /** Fetch tags + cover (once per process) for [book]. */
    fun load(ctx: Context, book: Book) {
        if (covers.containsKey(book.id) || !pending.add("book:" + book.id)) return
        val app = ctx.applicationContext
        worker.execute {
            val info = Prefs.bookInfo(app, book.id) ?: BookLibrary.readInfo(app, book).also { Prefs.setBookInfo(app, book.id, it) }
            val art = BookLibrary.readCover(app, book)
            main.post {
                covers[book.id] = art
                pending.remove("book:" + book.id)
                if (info.author != null || info.album != null || art != null) changed()
            }
        }
    }

    // ---- Durations ---------------------------------------------------------------------

    private fun durations(ctx: Context) = ctx.getSharedPreferences(DURATIONS, Context.MODE_PRIVATE)

    fun duration(ctx: Context, uri: Uri): Long = durations(ctx).getLong(uri.toString(), 0L)

    fun putDuration(ctx: Context, uri: Uri, ms: Long) {
        if (ms > 0 && duration(ctx, uri) != ms) durations(ctx).edit().putLong(uri.toString(), ms).apply()
    }

    /** Total length, or null while some chapter lengths are still unknown. */
    fun total(ctx: Context, book: Book): Long? {
        var t = 0L
        for (c in book.chapters) {
            val d = duration(ctx, c.uri)
            if (d <= 0) return null
            t += d
        }
        return t
    }

    /** Listened time up to [chapter]/[posMs], or null while lengths are unknown. */
    fun elapsed(ctx: Context, book: Book, chapter: Int, posMs: Int): Long? {
        var t = 0L
        for (i in 0 until chapter.coerceAtMost(book.chapters.size)) {
            val d = duration(ctx, book.chapters[i].uri)
            if (d <= 0) return null
            t += d
        }
        return t + posMs
    }

    /** Measure missing chapter lengths of [book] in the background. */
    fun fillDurations(ctx: Context, book: Book) {
        val app = ctx.applicationContext
        val todo = book.chapters.filter { duration(app, it.uri) <= 0 && pending.add(it.uri.toString()) }
        if (todo.isEmpty()) return
        worker.execute {
            todo.forEachIndexed { i, c ->
                val d = BookLibrary.readDuration(app, c.uri)
                if (d > 0) durations(app).edit().putLong(c.uri.toString(), d).apply()
                pending.remove(c.uri.toString())
                if (i % 10 == 9 || i == todo.lastIndex) main.post { changed() }
            }
        }
    }
}
