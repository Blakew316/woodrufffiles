package com.woodruff.tvexplorer.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.data.OpProgress
import com.woodruff.tvexplorer.remote.RemoteInput
import com.woodruff.tvexplorer.server.TransferService
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

/** Shared dialog frame: dark card, title, then content. */
@Composable
fun DialogFrame(title: String, onDismiss: () -> Unit, width: Int = 520, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(width.dp),
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = Palette.surface),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                content()
            }
        }
    }
}

data class MenuAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val run: () -> Unit)

/** The long-press / menu-key action list for a file or app. First entry gets focus. */
@Composable
fun ActionMenu(title: String, subtitle: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    DialogFrame(title, onDismiss, width = 460) {
        if (subtitle != null) Text(subtitle, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(actions) { i, a ->
                ListItem(
                    selected = false,
                    onClick = { onDismiss(); a.run() },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    leadingContent = { TvIcon(a.icon, tint = if (a.destructive) Palette.danger else androidx.tv.material3.LocalContentColor.current) },
                    headlineContent = { Text(a.label, color = if (a.destructive) Palette.danger else androidx.compose.ui.graphics.Color.Unspecified) },
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    DialogFrame(title, onDismiss) {
        Text(body, color = Palette.muted)
        ButtonRow {
            // Focus starts on Cancel so an accidental double-press of OK can't delete anything.
            OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
            Button(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) }
        }
    }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
}

/** Single-line text entry; the on-screen keyboard opens when the field is clicked with OK, or type on the phone. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    secret: Boolean = false,
    hint: String? = null,
) {
    // Pre-select the name without its extension, the part people usually want to change.
    val stemEnd = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    val field = remember { FocusRequester() }
    val submit = { if (value.text.isNotBlank()) { onDismiss(); onConfirm(value.text.trim()) } }
    DialogFrame(title, onDismiss) {
        if (hint != null) Text(hint, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        RemoteTextField(
            value = value,
            onValueChange = { value = it },
            label = title,
            secret = secret,
            onSubmit = submit,
            modifier = Modifier.fillMaxWidth(),
            textModifier = Modifier.focusRequester(field),
        )
        ButtonRow {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            Button(onClick = submit) { Text(confirmLabel) }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

/**
 * A text box the phone can type into (Phone → Keyboard on the phone). While it's on screen it's
 * registered with [RemoteInput]; the newest or most recently focused box receives the typing.
 */
@Composable
fun RemoteTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    singleLine: Boolean = true,
    onSubmit: (() -> Unit)? = null,
    placeholder: String? = null,
    textModifier: Modifier = Modifier,
) {
    val id = remember { RemoteInput.newId() }
    val change by rememberUpdatedState(onValueChange)
    val submit by rememberUpdatedState(onSubmit)
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(id) {
        RemoteInput.register(id, label, secret, !singleLine, value.text)
        onDispose { RemoteInput.unregister(id) }
    }
    LaunchedEffect(value.text) { RemoteInput.updateText(id, value.text) }
    LaunchedEffect(id) {
        RemoteInput.typed.collect { t ->
            if (t.id != id) return@collect
            change(TextFieldValue(t.text, TextRange(t.text.length)))
            if (t.submit) submit?.invoke()
        }
    }
    Box(
        modifier
            .border(2.dp, if (focused) Palette.accent else Palette.surfaceHigh, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        if (value.text.isEmpty() && placeholder != null) Text(placeholder, color = Palette.muted, fontSize = 20.sp)
        BasicTextField(
            value = value,
            onValueChange = { change(it) },
            singleLine = singleLine,
            textStyle = TextStyle(color = Palette.text, fontSize = 20.sp),
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            ),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardActions = KeyboardActions(onDone = { submit?.invoke() }),
            modifier = textModifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) RemoteInput.bringToFront(id)
                },
        )
    }
}


@Composable
fun ProgressDialog(title: String, progress: OpProgress?, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    DialogFrame(title, onDismiss = {}) {
        val fraction = progress?.let { if (it.totalBytes > 0) it.doneBytes.toFloat() / it.totalBytes else 0f } ?: 0f
        Text(progress?.currentName ?: "Preparing…", color = Palette.muted, maxLines = 1)
        UsageBar(fraction)
        if (progress != null && progress.totalBytes > 0) {
            val text = if (progress.label == "Deleting") "${progress.doneBytes} of ${progress.totalBytes} items"
            else "${Format.bytes(progress.doneBytes)} of ${Format.bytes(progress.totalBytes)}"
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
    }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
}
