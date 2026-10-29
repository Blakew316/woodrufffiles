package com.woodruff.tvexplorer.ui.screens

import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.AppEntry
import com.woodruff.tvexplorer.data.AppRepository
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.ListRowShape
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.PlatterListItem
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.motion.TvMotion
import com.woodruff.tvexplorer.ui.motion.focusPlatterHost
import com.woodruff.tvexplorer.ui.motion.rememberFocusPlatter
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.OpenResult
import com.woodruff.tvexplorer.util.Opener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installed apps: launch, back up the APK, open system info, or uninstall. */
@Composable
fun AppsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val repo = remember { AppRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var includeSystem by remember { mutableStateOf(false) }
    // Bumped on every resume so the list is fresh after returning from an uninstall dialog.
    var generation by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { generation++ }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    val apps by produceState<List<AppEntry>?>(null, includeSystem, generation) { value = repo.apps(includeSystem) }

    BackHandler { vm.show(vm.appsReturn) }

    fun report(r: OpenResult) { if (r is OpenResult.Failed) vm.say(r.message) }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp)) {
        Text("Apps", style = MaterialTheme.typography.headlineMedium)
        Text(
            apps?.let { list -> "${list.size} apps · ${Format.bytes(list.sumOf { it.apkSize })} of APKs" } ?: "Loading…",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolButton(
                if (includeSystem) Sym.Visibility else Sym.VisibilityOff,
                if (includeSystem) "Hide system apps" else "Show system apps",
                { includeSystem = !includeSystem },
            )
            ToolButton(Sym.Refresh, null, { generation++ })
        }
        // The focused app comes to rest a third of the way down, as on Apple TV; one glass highlight
        // glides from app to app, the list gliding with it on its spring.
        val list = rememberLazyListState()
        val platter = rememberFocusPlatter()
        PivotScroll(list, Pivot(fraction = 0.35f), TvMotion.Glide) {
            LazyColumn(
                Modifier.focusPlatterHost(platter, ListRowShape, Palette.surfaceHigh),
                state = list,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(apps.orEmpty(), key = { it.packageName }) { app ->
                    // Its words keep their colour on the glass highlight, as they did.
                    PlatterListItem(
                        platter,
                        onClick = { menuFor = app },
                        content = MaterialTheme.colorScheme.onSurface,
                        focusedContent = Palette.text,
                        onLongClick = { menuFor = app },
                        focusedScale = 1.01f,
                        leading = { AppIcon(repo, app.packageName) },
                        headline = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supporting = {
                            Text(
                                "${app.packageName} · v${app.versionName} · ${Format.bytes(app.apkSize)}" + if (app.isSystem) " · System" else "",
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.muted,
                            )
                        },
                    )
                }
            }
        }
    }

    menuFor?.let { app ->
        ActionMenu(
            title = app.label,
            subtitle = app.packageName,
            onDismiss = { menuFor = null },
            actions = buildList {
                if (app.launchable) add(MenuAction("Open", Sym.Launch) {
                    repo.launchIntent(app.packageName)?.let { context.startActivity(it) } ?: vm.say("This app has no launcher screen")
                })
                add(MenuAction("Back up APK to Downloads", Sym.Backup) {
                    scope.launch {
                        runCatching { repo.backup(app) }
                            .onSuccess { vm.say("Saved ${it.absolutePath}") }
                            .onFailure { vm.say("Backup failed: ${it.message}") }
                    }
                })
                add(MenuAction("App info", Sym.Info) { report(Opener.appDetails(context, app.packageName)) })
                if (!app.isSystem) add(MenuAction("Uninstall", Sym.Delete, destructive = true) {
                    report(Opener.uninstall(context, app.packageName))
                })
            },
        )
    }
}

@Composable
private fun AppIcon(repo: AppRepository, packageName: String) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Prefer the 16:9 TV banner; square icons are letterboxed by ContentScale.Fit.
                repo.icon(packageName)?.let { d ->
                    d.toBitmap(d.intrinsicWidth.coerceIn(1, 320), d.intrinsicHeight.coerceIn(1, 180)).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    // At 80 %, as the list item drew its leading picture (here as it's drawn, not through a layer).
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, alpha = 0.8f, modifier = Modifier.size(width = 64.dp, height = 36.dp)) }
}

