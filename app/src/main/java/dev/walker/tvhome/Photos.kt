package dev.walker.tvhome

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private const val LAST_URL = "photo_url"
private const val LAST_CAPTION = "photo_caption"

data class Photo(
    val url: String,
    val caption: String,
    /** Unsplash asks apps to ping this when a photo is used. */
    val downloadPing: String? = null,
)

/**
 * Supplies background photos: Unsplash landscapes when an access key is configured,
 * otherwise Google's Earth View collection bundled in assets.
 */
class PhotoSource(private val context: Context) {

    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** The photo last on screen, so start-up can show it from the image cache straight away. */
    fun lastShown(): Photo? = prefs.getString(LAST_URL, null)?.let { Photo(it, prefs.getString(LAST_CAPTION, "").orEmpty()) }

    suspend fun nextBatch(): List<Photo> = withContext(Dispatchers.IO) {
        val key = BuildConfig.UNSPLASH_ACCESS_KEY
        if (key.isNotEmpty()) {
            runCatching { unsplash(key) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        }
        earthView()
    }

    suspend fun reportUsed(photo: Photo) = withContext(Dispatchers.IO) {
        prefs.edit().putString(LAST_URL, photo.url).putString(LAST_CAPTION, photo.caption).apply()
        val ping = photo.downloadPing ?: return@withContext
        runCatching { get(ping).disconnect() }
    }

    private fun earthView(): List<Photo> {
        val json = context.assets.open("earthview.json").bufferedReader().use { it.readText() }
        val entries = JSONArray(json)
        return List(entries.length()) { i ->
            val entry = entries.getJSONObject(i)
            Photo(
                url = "https://www.gstatic.com/prettyearth/assets/full/${entry.getInt("id")}.jpg",
                caption = entry.getString("l"),
            )
        }.shuffled()
    }

    private fun unsplash(key: String): List<Photo> {
        val conn = get(
            "https://api.unsplash.com/photos/random" +
                "?query=landscape%20nature&orientation=landscape&content_filter=high&count=30",
            key,
        )
        val photos = conn.inputStream.bufferedReader().use { JSONArray(it.readText()) }
        conn.disconnect()
        return List(photos.length()) { i ->
            val photo = photos.getJSONObject(i)
            val raw = photo.getJSONObject("urls").getString("raw")
            Photo(
                url = "$raw&w=1920&h=1080&fit=crop&q=80&fm=jpg",
                caption = "${photo.getJSONObject("user").getString("name")} · Unsplash",
                downloadPing = photo.getJSONObject("links").getString("download_location"),
            )
        }
    }

    private fun get(url: String, key: String = BuildConfig.UNSPLASH_ACCESS_KEY): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            if (url.startsWith("https://api.unsplash.com")) {
                setRequestProperty("Authorization", "Client-ID $key")
                setRequestProperty("Accept-Version", "v1")
            }
            if (responseCode !in 200..299) error("HTTP $responseCode for $url")
        }
}
