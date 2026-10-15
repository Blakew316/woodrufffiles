package com.woodruff.tvexplorer.weather

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Today's weather for the Home briefing, from Open-Meteo (free, no key, no account). The place
 * is set once from the phone app (a city or ZIP), turned into coordinates, and remembered.
 */
object Weather {
    data class Now(
        val place: String, val tempF: Int, val highF: Int, val lowF: Int, val rainChance: Int, val code: Int, val at: Long,
    ) {
        val summary: String get() = describe(code)
    }

    private const val PREFS = "weather"
    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now

    fun place(c: Context): String? = prefs(c).getString("place", null)
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Looks up [query] ("Austin, TX", "78701", "London") and saves where it is. Empty clears. */
    suspend fun setPlace(c: Context, query: String) = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) { prefs(c).edit().clear().apply(); _now.value = null; return@withContext }
        // The geocoder matches place names; for "Austin, TX" it wants just "Austin".
        val name = q.substringBefore(',').trim()
        val res = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&name=" + URLEncoder.encode(name, "UTF-8")))
        val results = res.optJSONArray("results") ?: throw IOException("Couldn't find “$q”")
        val hint = q.substringAfter(',', "").trim().lowercase()
        val pick = (0 until results.length()).map { results.getJSONObject(it) }.let { list ->
            list.firstOrNull { hint.isNotEmpty() && (it.optString("admin1").lowercase().startsWith(hint) || it.optString("country_code").lowercase() == hint || stateCode(it.optString("admin1")) == hint) }
                ?: list.first()
        }
        val label = listOf(pick.optString("name"), pick.optString("admin1")).filter { it.isNotBlank() }.joinToString(", ")
        prefs(c).edit().putString("place", label).putFloat("lat", pick.getDouble("latitude").toFloat()).putFloat("lon", pick.getDouble("longitude").toFloat()).apply()
        _now.value = null
        refresh(c, force = true)
    }

    /** Fetches the forecast if it's older than 30 minutes (or [force]). Quiet on failure. */
    suspend fun refresh(c: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val p = prefs(c)
        if (!p.contains("lat")) return@withContext
        val cur = _now.value
        if (!force && cur != null && System.currentTimeMillis() - cur.at < 30 * 60_000L) return@withContext
        runCatching {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.getFloat("lat", 0f)}&longitude=${p.getFloat("lon", 0f)}" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1"
            val o = JSONObject(get(url))
            val current = o.getJSONObject("current")
            val daily = o.getJSONObject("daily")
            _now.value = Now(
                place = p.getString("place", "") ?: "",
                tempF = current.getDouble("temperature_2m").toInt(),
                highF = daily.getJSONArray("temperature_2m_max").getDouble(0).toInt(),
                lowF = daily.getJSONArray("temperature_2m_min").getDouble(0).toInt(),
                rainChance = daily.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0,
                code = current.optInt("weather_code"),
                at = System.currentTimeMillis(),
            )
        }.onFailure { Log.w("Weather", "refresh failed", it) }
    }

    /** WMO weather codes, as Open-Meteo reports them. Visible for tests. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorms"
        else -> "—"
    }

    private fun stateCode(state: String): String = US_STATES[state.lowercase()] ?: ""
    private val US_STATES = mapOf(
        "alabama" to "al", "alaska" to "ak", "arizona" to "az", "arkansas" to "ar", "california" to "ca", "colorado" to "co",
        "connecticut" to "ct", "delaware" to "de", "florida" to "fl", "georgia" to "ga", "hawaii" to "hi", "idaho" to "id",
        "illinois" to "il", "indiana" to "in", "iowa" to "ia", "kansas" to "ks", "kentucky" to "ky", "louisiana" to "la",
        "maine" to "me", "maryland" to "md", "massachusetts" to "ma", "michigan" to "mi", "minnesota" to "mn",
        "mississippi" to "ms", "missouri" to "mo", "montana" to "mt", "nebraska" to "ne", "nevada" to "nv",
        "new hampshire" to "nh", "new jersey" to "nj", "new mexico" to "nm", "new york" to "ny", "north carolina" to "nc",
        "north dakota" to "nd", "ohio" to "oh", "oklahoma" to "ok", "oregon" to "or", "pennsylvania" to "pa",
        "rhode island" to "ri", "south carolina" to "sc", "south dakota" to "sd", "tennessee" to "tn", "texas" to "tx",
        "utah" to "ut", "vermont" to "vt", "virginia" to "va", "washington" to "wa", "west virginia" to "wv",
        "wisconsin" to "wi", "wyoming" to "wy",
    )

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000 }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Weather service answered ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
