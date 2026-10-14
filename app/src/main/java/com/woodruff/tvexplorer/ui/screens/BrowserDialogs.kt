package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Wallpaper
import com.woodruff.tvexplorer.screensaver.Appearance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.FileItem
import com.woodruff.tvexplorer.data.FileKind
import com.woodruff.tvexplorer.data.FileRepository
import com.woodruff.tvexplorer.data.FileTypes
import com.woodruff.tvexplorer.data.SortField
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.components.ActionMenu
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.ConfirmDialog
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.MenuAction
import com.woodruff.tvexplorer.ui.components.TextInputDialog
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format
import com.woodruff.tvexplorer.util.OpenResult
import com.woodruff.tvexplorer.util.Opener
import java.io.File

/** Every popup the browser can show. Only one is visible at a time. */
sealed interface BrowserDialog {
    data class Menu(val item: FileItem) : BrowserDialog
    data class Rename(val item: FileItem) : BrowserDialog
    data class ConfirmDelete(val files: List<File>) : BrowserDialog
    data class Zip(val files: List<File>) : BrowserDialog
    data class Details(val item: FileItem) : BrowserDialog
    data object NewFolder : BrowserDialog
    data object Sort : BrowserDialog
    data object Search : BrowserDialog
    data object InstallPermission : BrowserDialog
}

@Composable
fun BrowserDialogs(dialog: BrowserDialog, vm: MainViewModel, onDismiss: () -> Unit, show: (BrowserDialog) -> Unit) {
    val context = LocalContext.current
    fun report(r: OpenResult) = when (r) {
        is OpenResult.Failed -> vm.say(r.message)
        OpenResult.NeedsInstallPermission -> show(BrowserDialog.InstallPermission)
        OpenResult.Opened -> Unit
    }

    when (dialog) {
        is BrowserDialog.Menu -> {
            val item = dialog.item
            val targets = vm.targets(item)
            val many = targets.size > 1
            val actions = buildList {
                if (!many && !item.isDirectory) {
                    when (item.kind) {
                        FileKind.APK -> add(MenuAction("Install", Icons.Filled.InstallMobile) { report(Opener.installApk(context, item.file)) })
                        FileKind.ARCHIVE -> if (FileTypes.extension(item.name) == "zip") add(MenuAction("Extract here", Icons.Filled.Unarchive) { vm.extract(item.file) })
                        FileKind.VIDEO, FileKind.AUDIO -> add(MenuAction("Play", Icons.Filled.PlayArrow) { report(Opener.open(context, item, vm.browser.value.items)) })
                        FileKind.IMAGE -> add(MenuAction("Set as app background", Icons.Filled.Wallpaper) {
                            Appearance.setBackground(context, item.file.absolutePath); vm.say("Background set")
                        })
                        else -> Unit
                    }
                    add(MenuAction("Open with…", Icons.Filled.OpenInNew) { report(Opener.openExternal(context, item.file)) })
                }
                if (!many && item.isDirectory) add(MenuAction("Use for photo slideshow", Icons.Filled.Slideshow) {
                    Appearance.setSlideshowFolder(context, item.file.absolutePath)
                    vm.say("Slideshow will show photos from ${item.name}")
                })
                add(MenuAction("Copy", Icons.Filled.ContentCopy) { vm.copyToClipboard(targets, move = false) })
                add(MenuAction("Move", Icons.Filled.ContentCut) { vm.copyToClipboard(targets, move = true) })
                if (!many) add(MenuAction("Rename", Icons.Filled.DriveFileRenameOutline) { show(BrowserDialog.Rename(item)) })
                add(MenuAction("Compress to zip", Icons.Filled.FolderZip) { show(BrowserDialog.Zip(targets)) })
                if (!vm.browser.value.selecting) add(MenuAction("Select", Icons.Filled.CheckBox) { vm.toggleSelected(item) })
                if (!many) add(MenuAction("Details", Icons.Filled.Info) { show(BrowserDialog.Details(item)) })
                add(MenuAction("Delete", Icons.Filled.Delete, destructive = true) { show(BrowserDialog.ConfirmDelete(targets)) })
            }
            ActionMenu(
                title = if (many) "${targets.size} items" else item.name,
                subtitle = if (many) null else item.path,
                actions = actions,
                onDismiss = onDismiss,
            )
        }

        is BrowserDialog.Rename -> TextInputDialog("Rename", dialog.item.name, "Rename", { vm.rename(dialog.item.file, it) }, onDismiss)

        is BrowserDialog.NewFolder -> TextInputDialog("New folder", "New folder", "Create", vm::createFolder, onDismiss)

        is BrowserDialog.Zip -> TextInputDialog(
            "Compress ${dialog.files.size} item(s)",
            (if (dialog.files.size == 1) dialog.files[0].nameWithoutExtension else "Archive") + ".zip",
            "Compress", { vm.compress(dialog.files, it) }, onDismiss,
        )

        is BrowserDialog.Search -> TextInputDialog("Search file names", "", "Search", vm::search, onDismiss)

        is BrowserDialog.ConfirmDelete -> {
            val n = dialog.files.size
            ConfirmDialog(
                title = if (n == 1) "Delete \"${dialog.files[0].name}\"?" else "Delete $n items?",
                body = "This can't be undone. Folders are deleted with everything inside them.",
                confirmLabel = "Delete",
                onConfirm = { vm.delete(dialog.files) },
                onDismiss = onDismiss,
            )
        }

        is BrowserDialog.Details -> DetailsDialog(dialog.item, onDismiss)

        is BrowserDialog.Sort -> {
            val sort = vm.settings.value.sort
            val pick = { field: SortField ->
                // Choosing the active field again flips the direction.
                vm.updateSettings { it.copy(sort = if (sort.field == field) sort.copy(ascending = !sort.ascending) else sort.copy(field = field)) }
            }
            val arrow = if (sort.ascending) "↑" else "↓"
            ActionMenu(
                title = "Sort by",
                subtitle = "Pick the current option again to reverse the order",
                actions = listOf(
                    SortField.NAME to "Name", SortField.DATE to "Date modified", SortField.SIZE to "Size", SortField.TYPE to "Type",
                ).map { (f, label) ->
                    MenuAction(if (f == sort.field) "$label  $arrow" else label, Icons.Filled.Sort) { pick(f) }
                },
                onDismiss = onDismiss,
            )
        }

        is BrowserDialog.InstallPermission -> DialogFrame("Allow app installs", onDismiss) {
            Text(
                "To install APK files, Android needs you to allow Woodruff Films to install unknown apps. " +
                    "Turn it on in the next screen, then press Back and choose the APK again.",
                color = Palette.muted,
            )
            ButtonRow {
                OutlinedButton(onClick = onDismiss) { Text("Not now") }
                Button(onClick = { onDismiss(); report(Opener.openInstallPermissionSettings(context)) }) { Text("Open settings") }
            }
        }
    }
}

@Composable
private fun DetailsDialog(item: FileItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var folderSize by remember { mutableStateOf<String?>(null) }
    if (item.isDirectory) {
        LaunchedEffect(item.path) {
            folderSize = "Calculating…"
            folderSize = runCatching { FileRepository(context).folderSize(item.file) }
                .map { (bytes, files) -> "${Format.bytes(bytes)} in $files files" }
                .getOrDefault("Unknown")
        }
    }
    DialogFrame(item.name, onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Detail("Location", item.file.parent ?: "/")
            Detail("Size", if (item.isDirectory) folderSize ?: "" else "${Format.bytes(item.size)} (${item.size} bytes)")
            Detail("Modified", Format.date(item.lastModified))
            if (!item.isDirectory) Detail("Type", FileTypes.mimeOf(item.name))
            Detail("Permissions", buildString {
                append(if (item.file.canRead()) "Read" else "No read")
                append(if (item.file.canWrite()) " · Write" else " · Read-only")
            })
        }
        Button(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Text(label, color = Palette.muted, style = androidx.tv.material3.MaterialTheme.typography.labelMedium)
    Text(value)
}
