package com.woodruff.tvexplorer.tvs

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The other TVs' health, as this TV (the hub) knows it. Each linked TV is asked for its report with
 * its link key ([TvLinks.health]: about 4 s at most), all at once, and only while someone looks:
 * Settings' first page or a health page on this TV, or a TV's page on the phone. Nothing polls in
 * the background. Each TV's last report is kept, also on disk (it holds no secrets), so a TV that's
 * off, turns down its link or doesn't answer still shows the last things it reported.
 */
object TvHealth {
    /** A report this new shows as the TV's current state while a fresh one is asked for. */
    private const val CURRENT_MS = 10 * 60_000L
    /** Asked again within this, the same answer (several phones and the TV's own pages at once). */
    private const val REUSE_MS = 5_000L

    /** What the hub knows of one TV: [kind] when it was last asked ([askedAt]), its last report and when that arrived. */
    data class Known(val kind: TvHealthKind, val report: HealthReport?, val checkedAt: Long?, val version: String?, val askedAt: Long)

    private val known = ConcurrentHashMap<String, Known>()
    private val running = HashMap<String, Deferred<Known>>()
    private val loadedFromDisk = ConcurrentHashMap.newKeySet<String>()
    private val written = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _changes = MutableStateFlow(0)
    /** Ticks whenever an answer arrives, so an open page shows it. */
    val changes: StateFlow<Int> = _changes

    /** What's known of [tv] without asking: the last answer, else the report kept on disk; null when nothing is. Reads the disk once per TV: call off the main thread the first time. */
    fun known(c: Context, tv: LinkedTv): Known? {
        known[tv.id]?.let { return it }
        if (loadedFromDisk.add(tv.id)) {
            readKept(c, tv.id)?.let { (report, at) -> known.putIfAbsent(tv.id, Known(TvHealthKind.CHECKING, report, at, report.version, 0L)) }
        }
        return known[tv.id]
    }

    /** What's known of [tv] in memory only: safe on the main thread. */
    fun knownNow(tv: LinkedTv): Known? = known[tv.id]

    /**
     * [tv] as a page shows it now: its link stopped working, the last answer (a report under 10
     * minutes old counts as current), or Checking… until it answers.
     */
    fun view(tv: LinkedTv, now: Long = System.currentTimeMillis()): TvHealthView {
        val k = known[tv.id]
        val kind = when {
            TvLinks.refuses(tv.id) -> TvHealthKind.REFUSED
            k == null -> TvHealthKind.CHECKING
            now - k.askedAt < CURRENT_MS -> k.kind
            k.report != null && k.checkedAt != null && now - k.checkedAt < CURRENT_MS -> if (k.report.good) TvHealthKind.GOOD else TvHealthKind.ATTENTION
            else -> TvHealthKind.CHECKING
        }
        return TvHealthView(tv.name, isThis = false, kind = kind, report = k?.report, checkedAt = k?.checkedAt, seen = TvLinks.seenAt(tv.id), version = k?.version)
    }

    /** This TV as a page shows it, from its own [report] (null while it's being made). */
    fun thisView(name: String, report: HealthReport?): TvHealthView = TvHealthView(
        name, isThis = true, kind = report?.let { if (it.good) TvHealthKind.GOOD else TvHealthKind.ATTENTION } ?: TvHealthKind.CHECKING,
        report = report, checkedAt = report?.at,
    )

    /** Asks [tv] for its report now (or reuses an answer under 5 s old unless [fresh]); one ask per TV at a time. */
    suspend fun check(c: Context, tv: LinkedTv, fresh: Boolean = false): Known = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        known(app, tv)?.let { if (!fresh && System.currentTimeMillis() - it.askedAt < REUSE_MS) return@withContext it }
        val ask = synchronized(running) {
            running[tv.id] ?: scope.async { fetch(app, tv) }.also { d ->
                running[tv.id] = d
                d.invokeOnCompletion { synchronized(running) { if (running[tv.id] === d) running.remove(tv.id) } }
            }
        }
        ask.await()
    }

    /** Asks every linked TV at once (see [check]). */
    suspend fun checkAll(c: Context, fresh: Boolean = false) = coroutineScope {
        TvLinks.load(c)
        TvLinks.linked.value.map { tv -> async { runCatching { check(c, tv, fresh) } } }.awaitAll()
        Unit
    }

    private fun fetch(c: Context, tv: LinkedTv): Known {
        val now = System.currentTimeMillis()
        val prior = known(c, tv)
        fun keep(kind: TvHealthKind, version: String? = prior?.version) = Known(kind, prior?.report, prior?.checkedAt, version, now)
        val k = if (TvLinks.refuses(tv.id)) keep(TvHealthKind.REFUSED) else when (val a = TvLinks.health(tv)) {
            is TvLinks.HealthAnswer.Report -> Known(if (a.report.good) TvHealthKind.GOOD else TvHealthKind.ATTENTION, a.report, now, a.report.version, now)
                .also { keepOnDisk(c, tv.id, a.report, now) }
            is TvLinks.HealthAnswer.NoReport -> {
                // A Woodruff from before health reports takes the link key for its status only; a TV
                // that turns the key down takes it for nothing (and is then checked: see TvLinks.lost).
                val status = runCatching { TvLinks.statusOf(tv) }.getOrNull()
                when {
                    status != null && !status.reportsHealth -> keep(TvHealthKind.OLD, status.version)
                    status != null -> keep(TvHealthKind.NO_ANSWER)
                    TvLinks.refuses(tv.id) -> keep(TvHealthKind.REFUSED)
                    else -> keep(TvHealthKind.OFF)
                }
            }
            TvLinks.HealthAnswer.Slow -> keep(TvHealthKind.NO_ANSWER)
            TvLinks.HealthAnswer.Unreachable -> keep(TvHealthKind.OFF)
        }
        if (TvLinks.find(tv.id) != null) known[tv.id] = k
        _changes.update { it + 1 }
        return k
    }

    /** The linked TVs [ids] were unlinked or replaced: what was kept about them goes. */
    fun forget(c: Context, ids: Set<String>) {
        ids.forEach { id ->
            known.remove(id)
            written.remove(id)
            runCatching { keptFile(c, id).delete() }
        }
        _changes.update { it + 1 }
    }

    // ---- The last report of each TV, on disk --------------------------------------------------

    private fun keptFile(c: Context, id: String) =
        File(File(c.applicationContext.filesDir, "health"), id.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "tv" } + ".json")

    /** Writes [report] (as it arrived at [at]) for the TV [id], at most once a minute. */
    private fun keepOnDisk(c: Context, id: String, report: HealthReport, at: Long) {
        if (at - (written[id] ?: 0L) < 60_000L) return
        written[id] = at
        runCatching {
            val f = keptFile(c, id)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("checkedAt", at).put("report", report.toJson()).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w("TvHealth", "couldn't keep a report: $it") }
    }

    private fun readKept(c: Context, id: String): Pair<HealthReport, Long>? = runCatching {
        val o = JSONObject(keptFile(c, id).takeIf { it.isFile }?.readText() ?: return null)
        val report = HealthReport.fromJson(o.optJSONObject("report")) ?: return null
        report to o.optLong("checkedAt")
    }.getOrNull()
}
