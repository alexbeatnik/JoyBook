package com.local.joybook

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

object Prefs {
    private const val NAME = "JoyBook"
    private const val KEY_IGNORE_TOUCH = "ignore_touch"
    private const val KEY_SPEED = "speed"
    private const val KEY_SKIP_SEC = "skip_sec"
    private const val KEY_BOOKS_FOLDER = "books_folder_uri"
    private const val KEY_BOOKS_FOLDER_NAME = "books_folder_name"
    private const val KEY_CURRENT_BOOK = "current_book_id"
    private const val KEY_CURRENT_TITLE = "current_book_title"
    private const val KEY_CURRENT_COVER = "current_book_cover"
    private const val KEY_PLAYLIST = "playlist"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun ignoreTouch(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_IGNORE_TOUCH, true)
    fun setIgnoreTouch(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_IGNORE_TOUCH, v).apply()

    fun speed(ctx: Context): Float = sp(ctx).getFloat(KEY_SPEED, 1f)
    fun setSpeed(ctx: Context, v: Float) = sp(ctx).edit().putFloat(KEY_SPEED, v).apply()

    fun skipSec(ctx: Context): Int = sp(ctx).getInt(KEY_SKIP_SEC, 15)
    fun setSkipSec(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_SKIP_SEC, v).apply()

    /** Empty = no folder chosen. */
    fun booksFolderUri(ctx: Context): String = sp(ctx).getString(KEY_BOOKS_FOLDER, "") ?: ""
    fun booksFolderName(ctx: Context): String = sp(ctx).getString(KEY_BOOKS_FOLDER_NAME, "") ?: ""

    fun setBooksFolder(ctx: Context, uri: String, name: String) {
        sp(ctx).edit().putString(KEY_BOOKS_FOLDER, uri).putString(KEY_BOOKS_FOLDER_NAME, name).apply()
    }

    fun clearBooksFolder(ctx: Context) = setBooksFolder(ctx, "", "")

    fun currentBookId(ctx: Context): String = sp(ctx).getString(KEY_CURRENT_BOOK, "") ?: ""

    /** Remember the open book so playback can resume after the process died (headset key, lock screen). */
    fun saveCurrentBook(ctx: Context, book: Book?) {
        val e = sp(ctx).edit()
        if (book == null) {
            e.putString(KEY_CURRENT_BOOK, "").remove(KEY_PLAYLIST).remove(KEY_CURRENT_TITLE).remove(KEY_CURRENT_COVER)
        } else {
            e.putString(KEY_CURRENT_BOOK, book.id)
                .putString(KEY_CURRENT_TITLE, book.title)
                .putString(KEY_CURRENT_COVER, book.coverUri?.toString() ?: "")
                .putString(KEY_PLAYLIST, book.chapters.joinToString("\n") { it.uri.toString() + "\t" + (it.name ?: "") })
        }
        e.apply()
    }

    fun loadCurrentBook(ctx: Context): Book? {
        val p = sp(ctx)
        val id = p.getString(KEY_CURRENT_BOOK, "") ?: ""
        val title = p.getString(KEY_CURRENT_TITLE, null)
        val raw = p.getString(KEY_PLAYLIST, null)
        if (id.isBlank() || title == null || raw.isNullOrBlank()) return null
        val chapters = raw.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("\t")
            Chapter(Uri.parse(parts[0]), parts.getOrNull(1)?.ifBlank { null })
        }
        val cover = p.getString(KEY_CURRENT_COVER, "")?.ifBlank { null }?.let(Uri::parse)
        return Book(id, title, chapters, cover)
    }

    /** Per-book resume point: chapter index (== chapter count when finished) and position. */
    fun bookProgress(ctx: Context, bookId: String): Pair<Int, Int> {
        val raw = sp(ctx).getString("prog_" + bookId.hashCode(), null) ?: return 0 to 0
        val p = raw.split("\t")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) to (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun setBookProgress(ctx: Context, bookId: String, chapter: Int, posMs: Int) {
        sp(ctx).edit().putString("prog_" + bookId.hashCode(), "$chapter\t$posMs").apply()
    }

    fun bookInfo(ctx: Context, bookId: String): BookInfo? {
        val raw = sp(ctx).getString("info_" + bookId.hashCode(), null) ?: return null
        val p = raw.split("\t")
        return BookInfo(p.getOrNull(0)?.ifBlank { null }, p.getOrNull(1)?.ifBlank { null })
    }

    fun setBookInfo(ctx: Context, bookId: String, info: BookInfo) {
        sp(ctx).edit().putString("info_" + bookId.hashCode(), "${info.author ?: ""}\t${info.album ?: ""}").apply()
    }
}
