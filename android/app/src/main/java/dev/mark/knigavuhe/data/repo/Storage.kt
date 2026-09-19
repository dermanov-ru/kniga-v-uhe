package dev.mark.knigavuhe.data.repo

import android.content.Context
import android.os.StatFs
import java.io.File

object Storage {
    /** App-specific external storage when present, internal as the fallback. */
    private fun root(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun bookDir(context: Context, bookId: Int): File =
        File(root(context), "books/$bookId").apply { mkdirs() }

    fun chapterFile(context: Context, bookId: Int, chapterId: Int): File =
        File(bookDir(context, bookId), "$chapterId.mp3")

    /** Downloads land in a `.part` file so a truncated transfer is never mistaken for a full one. */
    fun partFile(context: Context, bookId: Int, chapterId: Int): File =
        File(bookDir(context, bookId), "$chapterId.mp3.part")

    fun bookSizeBytes(context: Context, bookId: Int): Long =
        bookDir(context, bookId).listFiles()?.sumOf { it.length() } ?: 0L

    /** Сколько всего занято книгами приложения. */
    fun totalBytes(context: Context): Long {
        val books = File(root(context), "books")
        return books.listFiles()?.sumOf { dir ->
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        } ?: 0L
    }

    /** Свободно на том томе, где лежат книги. */
    fun freeBytes(context: Context): Long =
        runCatching { StatFs(root(context).absolutePath).availableBytes }.getOrDefault(0L)

    fun deleteBook(context: Context, bookId: Int) {
        File(root(context), "books/$bookId").deleteRecursively()
    }
}
