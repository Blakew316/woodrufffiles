package com.woodruff.tvexplorer.calendar

import android.content.Context
import android.util.Log
import com.woodruff.tvexplorer.cloud.GoogleAuth
import com.woodruff.tvexplorer.server.CompanionHub
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.util.NetworkWatch
import com.woodruff.tvexplorer.util.backoffMs
import com.woodruff.tvexplorer.util.isNetworkFailure
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
    /**
     * The last refresh couldn't reach Google (the sign-in is fine): [events] are what was fetched
     * earlier today, if anything, and it refreshes by itself as soon as the network is back.
     */
    val offline: Boolean = false,
    /** The day [events] are for ([dayKey]), so a TV offline since yesterday never shows yesterday's. */
    val day: Int = 0,
    /** Offline because the TV had no working network ("Offline"), rather than Google not answering ("Can't reach Google"). */
    val noNetwork: Boolean = false,
    /** Google ended the calendar's sign-in (not signed out on the TV): Home asks to connect it again. */
    val ended: Boolean = false,
)

/** A day as one number (year and day of the year), to tell days apart. */
internal fun dayKey(at: Calendar = Calendar.getInstance()): Int = at.get(Calendar.YEAR) * 1000 + at.get(Calendar.DAY_OF_YEAR)

/**
 * This state after a refresh that couldn't reach Google on day [today]: offline, with no error (the
 * sign-in is fine), keeping today's events and dropping any from an earlier day.
 */
internal fun CalendarState.offlineOn(today: Int, noNetwork: Boolean = false): CalendarState =
    copy(loaded = true, offline = true, error = null, events = if (day == today) events else emptyList(), day = today, noNetwork = noNetwork)

/**
 * Today's Activities from Google Calendar (read-only). Lists every calendar the owner has turned
 * on in Google Calendar, fetches today's events from all of them in parallel, and refreshes every
 * 10 minutes, at midnight, and whenever the network comes back (a TV woken from standby). Sign-in
 * is the usual phone QR code.
 */
object TodayCalendar {
    private const val API = "https://www.googleapis.com/calendar/v3/"
    private const val REFRESH_MS = 10 * 60_000L
    /** For a sign-in Google ended (then Home's calendar row says what to do: never shown as an error). */
    private const val ENDED = "Connect Google Calendar again."
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state
    private lateinit var app: Context
    private var signInJob: Job? = null
    private var loopJob: Job? = null
    @Volatile private var lastRefresh = 0L

    fun init(c: Context) {
        app = c.applicationContext
        NetworkWatch.start(app)
        scope.launch {
            CompanionHub.googleCodes.collect { (code, attempt) ->
                if (attempt.grant != GoogleAuth.Grant.CALENDAR) return@collect
                try {
                    GoogleAuth.exchange(app, code, attempt)
                    _state.update { it.copy(connected = true, ended = false, qrUrl = null, error = null) }
                    startLoop()
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message ?: "Sign-in failed") }
                }
            }
        }
        // Google ended the sign-in (expired or revoked), found out by any renewal: say so at once.
        scope.launch {
            GoogleAuth.expirations.collect { grant ->
                if (grant == GoogleAuth.Grant.CALENDAR) _state.update { it.copy(connected = false, offline = false, ended = true, error = null) }
            }
        }
        scope.launch {
            val (connected, ended) = withContext(Dispatchers.IO) {
                GoogleAuth.isConnected(app, GoogleAuth.Grant.CALENDAR) to GoogleAuth.hasEnded(app, GoogleAuth.Grant.CALENDAR)
            }
            _state.update { it.copy(connected = connected, ended = ended && !connected) }
            if (connected) startLoop()
        }
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            var tries = 0
            while (true) {
                val mark = NetworkWatch.comebacks.value
                val reached = refresh()
                // Signed out (Google ended it): nothing to refresh until the next sign-in.
                if (!_state.value.connected) return@launch
                tries = if (reached) 0 else tries + 1
                // Next in 10 minutes, or right after midnight if that comes first; sooner after a try
                // that couldn't reach Google (15 s, doubling). And at once when the network comes back.
                val next = if (reached) REFRESH_MS else backoffMs(tries - 1, 15_000, REFRESH_MS)
                if (NetworkWatch.awaitComeback(mark, minOf(next, msUntilMidnight() + 5_000))) {
                    // Let the network settle (names resolve a moment after Wi-Fi joins), and take
                    // its second word (Android's internet check passing) with this one.
                    delay(3_000)
                }
            }
        }
    }

    /** Reloads now (e.g. when Home opens), keeping the current list on screen meanwhile. */
    fun refreshSoon() {
        // Home opens often; the 10-minute loop keeps things current, so don't refetch on every visit.
        if (_state.value.connected && System.currentTimeMillis() - lastRefresh > 2 * 60_000L) scope.launch { refresh() }
    }

    /** One refresh. False when Google couldn't be reached (to be tried again soon), true otherwise. */
    private suspend fun refresh(): Boolean {
        lastRefresh = System.currentTimeMillis()
        return try {
            val events = withContext(Dispatchers.IO) { fetchToday() }
            _state.update { it.copy(events = events, loaded = true, error = null, offline = false, day = dayKey()) }
            true
        } catch (e: Exception) {
            Log.w("TodayCalendar", "refresh failed: ${e.message}")
            if (isNetworkFailure(e)) {
                // No network yet (a TV just woken), or Google unreachable: not "sign-in expired".
                _state.update { it.offlineOn(dayKey(), noNetwork = !NetworkWatch.online.value) }
                false
            } else {
                // Only a sign-in that's gone (Google ended it) counts as signed out; its words are
                // Home's calendar row, not an error.
                val (signedOut, ended) = withContext(Dispatchers.IO) {
                    !GoogleAuth.isConnected(app, GoogleAuth.Grant.CALENDAR) to GoogleAuth.hasEnded(app, GoogleAuth.Grant.CALENDAR)
                }
                _state.update {
                    it.copy(
                        loaded = true, offline = false, connected = !signedOut && it.connected, ended = it.ended || (signedOut && ended),
                        error = if (e is GoogleAuth.AuthException.SignedOut) null else e.message,
                    )
                }
                true
            }
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

    /**
     * Connected from the phone app ([com.woodruff.tvexplorer.server.PhoneSignIn] already swapped Google's code for
     * tokens): today's events load, and keep refreshing, with no screen open. Any thread.
     */
    fun signedIn() {
        scope.launch {
            signInJob?.cancel()
            _state.update { it.copy(connected = true, qrUrl = null, error = null) }
            startLoop()
        }
    }

    fun signOut(done: () -> Unit = {}) {
        scope.launch {
            loopJob?.cancel()
            GoogleAuth.signOut(app, GoogleAuth.Grant.CALENDAR)
            _state.value = CalendarState()
            done()
        }
    }

    /**
     * Today's events. A token Google won't take (401: the TV's clock was off when it was saved, or
     * access was just revoked) is renewed once and the fetch tried again; renewing then tells a
     * revoked sign-in apart. Refused again with a token just renewed, it's Google not answering
     * properly ("Can't reach Google right now"), tried again soon, never "sign-in expired".
     */
    private suspend fun fetchToday(): List<DayEvent> = try {
        fetchToday(GoogleAuth.requireToken(app, GoogleAuth.Grant.CALENDAR, ENDED))
    } catch (e: Unauthorized) {
        GoogleAuth.forgetAccess(app, GoogleAuth.Grant.CALENDAR)
        try {
            fetchToday(GoogleAuth.requireToken(app, GoogleAuth.Grant.CALENDAR, ENDED))
        } catch (again: Unauthorized) {
            throw Unreachable()
        }
    }

    /** Google Calendar answered 401: it didn't take the access token. */
    private class Unauthorized : IOException("Google Calendar didn't take the access token")

    /** Google Calendar is busy or failing (5xx, 429), or still refuses a fresh token: offline, tried again soon. */
    private class Unreachable : IOException("Can’t reach Google Calendar right now."), com.woodruff.tvexplorer.util.NetworkFailure

    private suspend fun fetchToday(token: String): List<DayEvent> = coroutineScope {
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
            if (code == 401) throw Unauthorized()
            if (code == 408 || code == 429 || code >= 500) throw Unreachable()
            // No codes or Google's own messages on screen.
            throw IOException(
                when (code) {
                    403 -> "Google didn’t allow Google Calendar. Turn on the Google Calendar API in Google Cloud, then connect again."
                    else -> "Can’t reach Google Calendar right now."
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
