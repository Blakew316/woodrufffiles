package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.calendar.DayEvent
import com.woodruff.tvexplorer.calendar.TodayCalendar
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.TvOutlinedButton
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.ui.theme.glassPanel
import com.woodruff.tvexplorer.util.rememberQrBitmap
import java.text.DateFormat
import java.util.Date

/**
 * "Today's Activities:" on Home: a glanceable row of today's Google Calendar events. Cards are
 * display-only (not focusable), so the remote moves straight past them to the shelves.
 */
@Composable
fun TodayActivities() {
    val cal by TodayCalendar.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { TodayCalendar.refreshSoon() }
    if (!cal.connected) return
    Column {
        Text(
            "Today's Activities:", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
        )
        when {
            !cal.loaded -> Text("Loading…", color = Palette.muted, modifier = Modifier.padding(start = 48.dp))
            cal.events.isEmpty() -> Text(
                cal.error ?: "Nothing on the calendar today.", color = Palette.muted, modifier = Modifier.padding(start = 48.dp),
            )
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(cal.events) { EventCard(it) }
            }
        }
    }
}

@Composable
private fun EventCard(e: DayEvent) {
    val now = System.currentTimeMillis()
    val happening = e.start != null && e.end != null && now in e.start..e.end
    val past = e.end != null && now > e.end
    Row(
        Modifier.width(250.dp).height(76.dp).glassPanel(RoundedCornerShape(18.dp), fill = if (happening) Palette.accentSoft else null),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(Color(e.color)))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                eventTime(e) + if (happening) " · Now" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (happening) Palette.accent else Palette.muted,
            )
            Text(
                e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (past) Palette.muted else Palette.text,
            )
            e.location?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

fun eventTime(e: DayEvent): String {
    val start = e.start ?: return "All day"
    val f = DateFormat.getTimeInstance(DateFormat.SHORT)
    return f.format(Date(start)) + (e.end?.let { " – " + f.format(Date(it)) } ?: "")
}

/** Phone QR sign-in for Google Calendar. */
@Composable
fun CalendarConnectDialog(onDismiss: () -> Unit) {
    val cal by TodayCalendar.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { TodayCalendar.startSignIn() }
    LaunchedEffect(cal.connected) { if (cal.connected) onDismiss() }
    val close = { TodayCalendar.cancelSignIn(); onDismiss() }
    DialogFrame("Google Calendar", close, width = 680) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                val url = cal.qrUrl
                if (url != null) {
                    // Made off the main thread; the white card shows for the moment it takes.
                    rememberQrBitmap(url, 600)?.let { Image(it, contentDescription = "QR code", modifier = Modifier.size(220.dp)) }
                } else Text("Preparing…", color = Color.Black)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scan with your phone and sign in to Google.")
                cal.error?.let { Text(it, color = Palette.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        ButtonRow { TvOutlinedButton(onClick = close) { Text("Close") } }
    }
}
