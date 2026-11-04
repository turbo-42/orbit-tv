package dev.walker.tvhome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class Sky { Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Thunder }

data class Weather(
    val place: String,
    val temperature: Int,
    val high: Int,
    val low: Int,
    val sky: Sky,
    val isDay: Boolean,
    val description: String,
    /** Short rain outlook for the next 12 hours, e.g. "Rain likely from 15:00". */
    val outlook: String,
)

private const val RAIN_FROM = 50
private const val DRY_BELOW = 30

private data class Location(val latitude: Double, val longitude: Double, val place: String)

/** Current conditions from Open-Meteo; location from local.properties, else IP geolocation. */
class WeatherSource(private val is24Hour: Boolean) {

    private var location: Location? = null

    suspend fun current(): Weather = withContext(Dispatchers.IO) {
        val loc = location ?: locate().also { location = it }
        val json = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=precipitation_probability" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto",
        )
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val code = current.getInt("weather_code")
        Weather(
            place = loc.place,
            temperature = current.getDouble("temperature_2m").roundToInt(),
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            sky = skyFor(code),
            isDay = current.getInt("is_day") == 1,
            description = describe(code),
            outlook = rainOutlook(
                now = LocalDateTime.parse(current.getString("time")),
                hours = json.getJSONObject("hourly").let { hourly ->
                    val times = hourly.getJSONArray("time")
                    val chances = hourly.getJSONArray("precipitation_probability")
                    List(times.length()) { i -> LocalDateTime.parse(times.getString(i)) to chances.optInt(i, 0) }
                },
                rainingNow = skyFor(code) in setOf(Sky.Drizzle, Sky.Rain, Sky.Snow, Sky.Thunder),
            ),
        )
    }

    /** Hourly chances are for the hour starting at each timestamp, in the location's local time. */
    private fun rainOutlook(now: LocalDateTime, hours: List<Pair<LocalDateTime, Int>>, rainingNow: Boolean): String {
        val window = hours.filter { (time, _) -> time > now.minusHours(1) && time <= now.plusHours(12) }
        if (window.isEmpty()) return ""
        val clock = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "ha")
        fun format(time: LocalDateTime) = time.format(clock).lowercase()
        return if (rainingNow) {
            // The current hour is already wet, so only later hours can say when it eases.
            val dryFrom = window.firstOrNull { (time, chance) -> time > now && chance < DRY_BELOW }?.first
            if (dryFrom != null) "Easing around ${format(dryFrom)}" else "Wet for the next 12 hours"
        } else {
            val wetFrom = window.firstOrNull { (_, chance) -> chance >= RAIN_FROM }?.first
            when {
                wetFrom == null -> "Dry for the next 12 hours"
                wetFrom <= now -> "Rain likely soon"
                else -> "Rain likely from ${format(wetFrom)}"
            }
        }
    }

    private fun locate(): Location {
        BuildConfig.WEATHER_LOCATION.split(",").takeIf { it.size >= 2 }?.let { parts ->
            return Location(parts[0].trim().toDouble(), parts[1].trim().toDouble(), parts.getOrNull(2)?.trim().orEmpty())
        }
        val ip = getJson("https://ipapi.co/json/")
        return Location(ip.getDouble("latitude"), ip.getDouble("longitude"), ip.optString("city"))
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "tv-home")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            conn.disconnect()
        }
    }
}

// WMO weather interpretation codes, as documented by Open-Meteo.
private fun skyFor(code: Int): Sky = when (code) {
    0, 1 -> Sky.Clear
    2 -> Sky.PartlyCloudy
    3 -> Sky.Cloudy
    45, 48 -> Sky.Fog
    51, 53, 55, 56, 57 -> Sky.Drizzle
    61, 63, 65, 66, 67, 80, 81, 82 -> Sky.Rain
    71, 73, 75, 77, 85, 86 -> Sky.Snow
    95, 96, 99 -> Sky.Thunder
    else -> Sky.Cloudy
}

private fun describe(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61 -> "Light rain"
    63 -> "Rain"
    65 -> "Heavy rain"
    66, 67 -> "Freezing rain"
    71 -> "Light snow"
    73 -> "Snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80, 81 -> "Showers"
    82 -> "Heavy showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm, hail"
    else -> "—"
}
