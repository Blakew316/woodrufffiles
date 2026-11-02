package com.woodruff.tvexplorer.nest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the TV's screen is on (it isn't in standby), for the doorbell: a TV that's asleep shows
 * nothing, so one that passes alerts on to no other TV stops asking Google for them until it wakes.
 * Android's screen on/off broadcasts, registered once per process (they can't be declared in the
 * manifest). If a TV refuses the receiver, [on] stays true and nothing pauses.
 */
internal object Awake {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    @Volatile private var started = false

    fun start(c: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = c.applicationContext
        runCatching {
            // These broadcasts come only from the system, so the receiver isn't exported.
            ContextCompat.registerReceiver(
                app,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            Intent.ACTION_SCREEN_ON -> _on.value = true
                            Intent.ACTION_SCREEN_OFF -> _on.value = false
                        }
                    }
                },
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            // Where things stand now (after registering, so a change in between isn't missed).
            _on.value = app.getSystemService(PowerManager::class.java)?.isInteractive != false
        }.onFailure { Log.w("Doorbell", "can't tell when the screen sleeps on this TV", it) }
    }
}
