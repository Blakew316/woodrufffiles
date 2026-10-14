package com.woodruff.tvexplorer.calendar

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One event today. [start] is null for all-day events. */
data class DayEvent(val title: String, val start: Long?, val end: Long?, val location: String?, val color: Long)

data class CalendarState(
    val connected: Boolean = false,
    val events: List<DayEvent> = emptyList(),
    val loaded: Boolean = false,
    val qrUrl: String? = null,
    val error: String? = null,
)

/**
 * Today's Activities from Google Calendar (read-only). Lists every calendar the owner has turned
 * on in Google Calendar, fetches today's events from all of them in parallel, and refreshes every
 * 10 minutes and at midnight. Sign-in is the usual phone QR code.
 */
object TodayCalendar {
    private const val API = "https://www.googleapis.com/calendar/v3/"
    private const val REFRESH_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state
    private lateinit var app: Context
    private var signInJob: Job? = null
    private var loopJob: Job? = null

    fun init(c: Context) {
        app = c.applicationContext
        scope.launch {
            CompanionHub.googleCodes.collect { (code, attempt) ->
                if (attempt.grant != GoogleAuth.Grant.CALENDAR) return@collect
                try {
                    GoogleAuth.exchange(app, code, attempt)
                    _state.update { it.copy(connected = true, qrUrl = null, error = null) }
                    startLoop()
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Sign-in failed") }
                }
            }
        }
        scope.launch {
            val connected = withContext(Dispatchers.IO) { GoogleAuth.isConnected(app, GoogleAuth.Grant.CALENDAR) }
            _state.update { it.copy(connected = connected) }
            if (connected) startLoop()
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (true) {
                refresh()
                // Next refresh in 10 minutes, or right after midnight if that comes first.
                delay(minOf(REFRESH_MS, msUntilMidnight() + 5_000))
            }
        }
    }

    /** Reloads now (e.g. when Home opens), keeping the current list on screen meanwhile. */
    fun refreshSoon() { if (_state.value.connected) scope.launch { refresh() } }

    private suspend fun refresh() {
        try {
            val events = withContext(Dispatchers.IO) { fetchToday() }
            _state.update { it.copy(events = events, loaded = true, error = null) }
        } catch (e: Exception) {
            Log.w("TodayCalendar", "refresh failed", e)
            val signedOut = !GoogleAuth.isConnected(app, GoogleAuth.Grant.CALENDAR)
            _state.update { it.copy(loaded = true, error = e.message, connected = !signedOut && it.connected) }
        }
    }

    fun startSignIn() {
        signInJob?.cancel()
        signInJob = scope.launch {
            _state.update { it.copy(qrUrl = null, error = null) }
            if (!GoogleAuth.isConfigured(app)) {
                _state.update { it.copy(error = "Set up the Google keys first (Settings → Google Drive).") }
                return@launch
            }
            TransferService.start(app)
            val server = TransferService.state.first { it.running || it.error != null }
            val base = server.base ?: return@launch _state.update { it.copy(error = server.error ?: "The phone connection couldn't start.") }
            val a = GoogleAuth.newAttempt(app, base, GoogleAuth.Grant.CALENDAR)
            CompanionHub.expect(a)
            _state.update { it.copy(qrUrl = a.authUrl) }
        }
    }

    fun cancelSignIn() { signInJob?.cancel(); _state.update { it.copy(qrUrl = null) } }

    fun signOut(done: () -> Unit = {}) {
        scope.launch {
            loopJob?.cancel()
            GoogleAuth.signOut(app, GoogleAuth.Grant.CALENDAR)
            _state.value = CalendarState()
            done()
        }
    }

    private suspend fun fetchToday(): List<DayEvent> = coroutineScope {
        val token = GoogleAuth.blockingToken(app, GoogleAuth.Grant.CALENDAR)
            ?: throw IOException("Google Calendar sign-in expired. Connect again in Settings.")
        val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val dayEnd = (dayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val timeMin = utc.format(dayStart.time)
        val timeMax = utc.format(dayEnd.time)

        val list = get(token, "users/me/calendarList?minAccessRole=reader&fields=${enc("items(id,backgroundColor,selected,hidden)")}")
        val cals = list.optJSONArray("items") ?: return@coroutineScope emptyList()
        val wanted = (0 until cals.length()).map { cals.getJSONObject(it) }
            .filter { it.optBoolean("selected", false) && !it.optBoolean("hidden", false) }
        // All calendars at once: one slow calendar doesn't hold the others up.
        wanted.map { cal ->
            async(Dispatchers.IO) {
                val color = parseColor(cal.optString("backgroundColor"))
                val q = "calendars/${enc(cal.getString("id"))}/events?singleEvents=true&orderBy=startTime&maxResults=25" +
                    "&timeMin=${enc(timeMin)}&timeMax=${enc(timeMax)}&fields=${enc("items(summary,start,end,location,status)")}"
                val items = runCatching { get(token, q).optJSONArray("items") }.getOrNull() ?: return@async emptyList<DayEvent>()
                (0 until items.length()).mapNotNull { i ->
                    val e = items.getJSONObject(i)
                    if (e.optString("status") == "cancelled") return@mapNotNull null
                    val start = e.optJSONObject("start")
                    val end = e.optJSONObject("end")
                    DayEvent(
                        title = e.optString("summary").ifBlank { "Busy" },
                        start = start?.optString("dateTime")?.let(::parseTime),
                        end = end?.optString("dateTime")?.let(::parseTime),
                        location = e.optString("location").ifBlank { null },
                        color = color,
                    )
                }
            }
        }.awaitAll().flatten()
            .sortedWith(compareBy<DayEvent> { it.start != null }.thenBy { it.start ?: 0L })
    }

    private fun get(token: String, path: String): JSONObject {
        val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return JSONObject(text)
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
            throw IOException(
                when (code) {
                    401 -> "Google Calendar sign-in expired. Connect again in Settings."
                    403 -> "Google refused ($msg). Enable the Google Calendar API in Google Cloud."
                    else -> "Google Calendar error $code"
                },
            )
        } finally {
            conn.disconnect()
        }
    }

    /** "2026-10-14T09:30:00-05:00" or "...Z". The 'X' pattern handles both (Android 7+). */
    private fun parseTime(s: String): Long? = if (s.isBlank()) null else runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s.replace(Regex("\\.\\d+"), ""))?.time
    }.getOrNull()

    private fun parseColor(hex: String): Long =
        hex.removePrefix("#").toLongOrNull(16)?.let { 0xFF000000L or it } ?: 0xFF5592BFL

    private fun msUntilMidnight(): Long {
        val next = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return next.timeInMillis - System.currentTimeMillis()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
