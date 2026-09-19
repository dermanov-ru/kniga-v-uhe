package dev.mark.knigavuhe.data.repo

import android.content.Context
import dev.mark.knigavuhe.data.remote.ReaderCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small preferences bag. Right now it only remembers the narrator Mark listens to most. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _favoriteReader = MutableStateFlow(read())
    val favoriteReader: StateFlow<FavoriteReader?> = _favoriteReader.asStateFlow()

    fun setFavorite(reader: ReaderCard?) {
        if (reader == null) {
            prefs.edit().remove(KEY_SLUG).remove(KEY_NAME).remove(KEY_PATH).apply()
        } else {
            prefs.edit()
                .putString(KEY_SLUG, reader.slug)
                .putString(KEY_NAME, reader.name)
                .putString(KEY_PATH, reader.path)
                .apply()
        }
        _favoriteReader.value = read()
    }

    fun isFavorite(slug: String): Boolean = _favoriteReader.value?.slug == slug

    private fun read(): FavoriteReader? {
        val slug = prefs.getString(KEY_SLUG, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val path = prefs.getString(KEY_PATH, null) ?: "/reader/$slug/"
        return FavoriteReader(slug, name, path)
    }

    private companion object {
        const val KEY_SLUG = "favorite_reader_slug"
        const val KEY_NAME = "favorite_reader_name"
        const val KEY_PATH = "favorite_reader_path"
    }
}

data class FavoriteReader(val slug: String, val name: String, val path: String) {
    fun asCard() = ReaderCard(slug = slug, path = path, name = name, booksText = "", avatar = null)
}
