package com.woodruff.tvexplorer.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.PowerManager
import com.woodruff.tvexplorer.R

/** A short sound for announcements and the doorbell, so a pop-up gets noticed over a film. */
object Chime {
    enum class Sound { ANNOUNCE, DOORBELL }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("chime", Context.MODE_PRIVATE)

    /** "Chime for announcements" (Settings), on unless turned off on this TV. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply() }

    private var pool: SoundPool? = null
    /** Each sound's sample in [pool], once asked for. */
    private val samples = HashMap<Sound, Int>()
    private val ready = HashSet<Int>()
    /** Asked to play while still loading (the first chime after a start): plays once loaded. */
    private val waiting = HashSet<Int>()
    private val lastPlayed = HashMap<Sound, Long>()

    /** Plays [sound] when chimes are on and the screen is awake. Never takes audio focus. */
    @Synchronized
    fun play(c: Context, sound: Sound) {
        val app = c.applicationContext
        if (!enabled(app)) return
        // In standby nobody is watching, and a chime would only wake someone.
        if (app.getSystemService(PowerManager::class.java)?.isInteractive != true) return
        // The same message arriving twice at once (two relays, a double tap) chimes once.
        val now = System.currentTimeMillis()
        if (now - (lastPlayed[sound] ?: 0L) < 1_500) return
        lastPlayed[sound] = now
        // A sound the TV can't make never stops the message itself.
        runCatching {
            // A sound effect mixed over whatever plays. Audio focus is never asked for, so a film or
            // Netflix carries on underneath instead of pausing.
            val p = pool ?: SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
                .also { sp -> pool = sp; sp.setOnLoadCompleteListener { _, id, status -> loaded(sp, id, status) } }
            val id = samples[sound] ?: p.load(app, resource(sound), 1).also { if (it != 0) samples[sound] = it }
            if (id != 0) {
                if (id in ready) p.play(id, 1f, 1f, 1, 0, 1f) else waiting += id
            }
        }
    }

    @Synchronized
    private fun loaded(sp: SoundPool, id: Int, status: Int) {
        if (status != 0) {
            // Unreadable: forget it, so the next chime tries again.
            samples.entries.removeAll { it.value == id }
            waiting -= id
            return
        }
        ready += id
        if (waiting.remove(id)) runCatching { sp.play(id, 1f, 1f, 1, 0, 1f) }
    }

    /** About a second each and soft; synthesized for Woodruff, so there's no license to follow. */
    private fun resource(sound: Sound) = when (sound) {
        Sound.ANNOUNCE -> R.raw.chime_announce
        Sound.DOORBELL -> R.raw.chime_doorbell
    }
}
