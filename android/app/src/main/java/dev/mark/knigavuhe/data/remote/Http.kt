package dev.mark.knigavuhe.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

const val SITE = "https://knigavuhe.org"

/**
 * The site sits behind ddos-guard, which hands out `__ddg*` cookies. A plain request with a browser
 * UA is served straight away today, but keeping the cookies makes us look like the browser it
 * expects if that ever changes.
 */
class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.topPrivateDomain() ?: url.host
        val list = store.getOrPut(host) { mutableListOf() }
        synchronized(list) {
            cookies.forEach { fresh ->
                list.removeAll { it.name == fresh.name }
                list.add(fresh)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.topPrivateDomain() ?: url.host
        val list = store[host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(list) { list.filter { it.expiresAt > now } }
    }
}

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(MemoryCookieJar())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
