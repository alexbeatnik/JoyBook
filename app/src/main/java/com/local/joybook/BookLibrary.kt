package com.local.joybook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.Charset

object BookLibrary {
    private val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "amr", "3gp")
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp")
    private const val MAX_DEPTH = 4
    private const val ART_PX = 320

    private class Entry(val docId: String, val name: String, val isDir: Boolean) {
        val ext: String get() = name.substringAfterLast('.', "").lowercase()
    }

    /** Subfolders of the chosen folder are books; loose audio in the root is one more book. Call off the main thread. */
    fun scan(context: Context): List<Book> {
        val folder = Prefs.booksFolderUri(context)
        if (folder.isBlank()) return emptyList()
        val tree = Uri.parse(folder)
        val root = try {
            list(context, tree, DocumentsContract.getTreeDocumentId(tree))
        } catch (_: Exception) {
            return emptyList()
        }
        val books = ArrayList<Book>()
        for (dir in root) {
            if (!dir.isDir || dir.name.startsWith(".")) continue
            val audio = ArrayList<Pair<String, Uri>>()
            val images = ArrayList<Pair<String, Uri>>()
            try {
                collect(context, tree, dir.docId, "", audio, images, 1)
            } catch (_: Exception) {
            }
            if (audio.isEmpty()) continue
            books += Book(
                id = DocumentsContract.buildDocumentUriUsingTree(tree, dir.docId).toString(),
                title = prettyTitle(dir.name),
                chapters = toChapters(audio),
                coverUri = pickCover(images),
            )
        }
        val loose = root.filter { !it.isDir && it.ext in AUDIO }
        if (loose.isNotEmpty()) {
            books += Book(
                id = "$tree#root",
                title = prettyTitle(Prefs.booksFolderName(context).ifBlank { "Audiobooks" }),
                chapters = toChapters(loose.map { it.name to DocumentsContract.buildDocumentUriUsingTree(tree, it.docId) }),
                coverUri = pickCover(root.filter { !it.isDir && it.ext in IMAGES }
                    .map { it.name to DocumentsContract.buildDocumentUriUsingTree(tree, it.docId) }),
            )
        }
        return books.sortedWith { a, b -> compareNatural(a.title, b.title) }
    }

    private fun list(context: Context, tree: Uri, docId: String): List<Entry> {
        val out = ArrayList<Entry>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out += Entry(id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    private fun collect(
        context: Context,
        tree: Uri,
        docId: String,
        prefix: String,
        audio: MutableList<Pair<String, Uri>>,
        images: MutableList<Pair<String, Uri>>,
        depth: Int,
    ) {
        for (e in list(context, tree, docId)) {
            if (e.name.startsWith(".")) continue
            if (e.isDir) {
                if (depth < MAX_DEPTH) collect(context, tree, e.docId, prefix + e.name + "/", audio, images, depth + 1)
                continue
            }
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
            when (e.ext) {
                in AUDIO -> audio += (prefix + e.name) to uri
                in IMAGES -> images += (prefix + e.name) to uri
            }
        }
    }

    private fun toChapters(files: List<Pair<String, Uri>>): List<Chapter> =
        files.sortedWith { a, b -> compareNatural(a.first, b.first) }.map { (path, uri) ->
            val base = path.substringAfterLast('/').substringBeforeLast('.')
            val folder = path.substringBeforeLast('/', "").replace('/', ' ').trim()
            // "001.mp3" carries no name — let the UI say "Chapter N".
            val name = if (base.all { it in '0'..'9' || it == ' ' || it == '_' || it == '-' }) null else prettyTitle(base)
            Chapter(uri, if (folder.isNotEmpty() && name != null) "$folder · $name" else name)
        }

    private fun pickCover(images: List<Pair<String, Uri>>): Uri? {
        if (images.isEmpty()) return null
        val preferred = images.firstOrNull { (n, _) ->
            val l = n.lowercase()
            "cover" in l || "folder" in l || "front" in l
        }
        return (preferred ?: images.sortedWith { a, b -> compareNatural(a.first, b.first) }.first()).second
    }

    /** "Henry_Lion_Oldie_The_Hero" -> "Henry Lion Oldie The Hero". */
    fun prettyTitle(raw: String): String =
        raw.replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifEmpty { raw }

    // ---- Tags, cover, duration (slow: background only) --------------------------------

    fun readInfo(context: Context, book: Book): BookInfo {
        val first = book.chapters.firstOrNull() ?: return BookInfo(null, null)
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, first.uri)
            val author = listOf(
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                MediaMetadataRetriever.METADATA_KEY_ARTIST,
                MediaMetadataRetriever.METADATA_KEY_AUTHOR,
            ).firstNotNullOfOrNull { k -> r.extractMetadata(k)?.let(::cleanTag) }
            val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let(::cleanTag)
            BookInfo(author, album?.takeIf { !it.equals(book.title, ignoreCase = true) })
        } catch (_: Exception) {
            BookInfo(null, null)
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun readCover(context: Context, book: Book): Bitmap? {
        book.coverUri?.let { uri ->
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) decodeScaled(bytes)?.let { return it }
            } catch (_: Exception) {
            }
        }
        val first = book.chapters.firstOrNull() ?: return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, first.uri)
            r.embeddedPicture?.let(::decodeScaled)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun readDuration(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ART_PX && bounds.outHeight / (sample * 2) >= ART_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun cleanTag(s: String): String? = fixMojibake(s.trim()).ifBlank { null }

    /**
     * Cyrillic ID3 tags are often cp1251 bytes flagged as Latin-1 ("Ãåðîé" instead of "Герой").
     * If most letters land in À..ÿ and nothing is above U+00FF, re-decode as cp1251.
     */
    fun fixMojibake(s: String): String {
        if (s.any { it.code > 0xFF }) return s
        val letters = s.count { it.isLetter() }
        if (letters == 0) return s
        val high = s.count { it.code in 0xC0..0xFF }
        if (high * 2 < letters) return s
        return try {
            String(s.toByteArray(Charsets.ISO_8859_1), Charset.forName("windows-1251"))
        } catch (_: Exception) {
            s
        }
    }

    /** "2 - A" before "10 - B"; case-insensitive elsewhere. */
    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca in '0'..'9' && cb in '0'..'9') {
                val si = i
                val sj = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
