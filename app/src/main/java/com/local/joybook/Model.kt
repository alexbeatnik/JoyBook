package com.local.joybook

import android.content.Context
import android.net.Uri

data class Chapter(
    val uri: Uri,
    /** Display name; null when the file is just a number ("001.mp3") — then "Chapter N" is shown. */
    val name: String?,
)

/** One audiobook = a folder with ordered audio chapters (loose files in the root form one more book). */
data class Book(
    val id: String,
    val title: String,
    val chapters: List<Chapter>,
    val coverUri: Uri? = null,
) {
    fun chapterName(ctx: Context, index: Int): String =
        chapters.getOrNull(index)?.name ?: ctx.getString(R.string.chapter_n, index + 1)

    /** "Chapter 12 of 80", or "Prologue · 1/80" when the file has a real name. */
    fun chapterLabel(ctx: Context, index: Int): String {
        val name = chapters.getOrNull(index)?.name
        return if (name == null) ctx.getString(R.string.chapter_n_of_m, index + 1, chapters.size)
        else ctx.getString(R.string.named_chapter_of_m, name, index + 1, chapters.size)
    }
}

/** Tags read from the first chapter (author, real title) — cached in [Prefs]. */
data class BookInfo(val author: String?, val album: String?)
