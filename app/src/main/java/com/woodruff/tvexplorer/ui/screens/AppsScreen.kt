package com.woodruff.tvexplorer.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.AppEntry
import com.woodruff.tvexplorer.data.AppRepository
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.ToolButton
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

    BackHandler { vm.show(Screen.HOME) }

    fun report(r: OpenResult) { if (r is OpenResult.Failed) vm.say(r.message) }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 24.dp)) {
        Text("Apps", style = MaterialTheme.typography.headlineMedium)
        Text(
            apps?.let { list -> "${list.size} apps · ${Format.bytes(list.sumOf { it.apkSize })} of APKs" } ?: "Loading…",
            color = Palette.muted, style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolButton(
                if (includeSystem) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                if (includeSystem) "Hide system apps" else "Show system apps",
                { includeSystem = !includeSystem },
            )
            ToolButton(Icons.Filled.Refresh, null, { generation++ })
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(apps.orEmpty(), key = { it.packageName }) { app ->
                ListItem(
                    selected = false,
                    onClick = { menuFor = app },
                    onLongClick = { menuFor = app },
                    colors = ListItemDefaults.colors(focusedContainerColor = Palette.surfaceHigh, focusedContentColor = Palette.text),
                    scale = ListItemDefaults.scale(focusedScale = 1.01f),
                    leadingContent = { AppIcon(repo, app.packageName) },
                    headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${app.packageName} · v${app.versionName} · ${Format.bytes(app.apkSize)}" + if (app.isSystem) " · System" else "",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.muted,
                        )
                    },
                )
            }
        }
    }

    menuFor?.let { app ->
        ActionMenu(
            title = app.label,
            subtitle = app.packageName,
            onDismiss = { menuFor = null },
            actions = buildList {
                if (app.launchable) add(MenuAction("Open", Icons.Filled.Launch) {
                    repo.launchIntent(app.packageName)?.let { context.startActivity(it) } ?: vm.say("This app has no launcher screen")
                })
                add(MenuAction("Back up APK to Downloads", Icons.Filled.Backup) {
                    scope.launch {
                        runCatching { repo.backup(app) }
                            .onSuccess { vm.say("Saved ${it.absolutePath}") }
                            .onFailure { vm.say("Backup failed: ${it.message}") }
                    }
                })
                add(MenuAction("App info", Icons.Filled.Info) { report(Opener.appDetails(context, app.packageName)) })
                if (!app.isSystem) add(MenuAction("Uninstall", Icons.Filled.Delete, destructive = true) {
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
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 64.dp, height = 36.dp)) }
}

