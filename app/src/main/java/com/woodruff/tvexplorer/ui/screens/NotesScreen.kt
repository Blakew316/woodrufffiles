package com.woodruff.tvexplorer.ui.screens

import androidx.compose.foundation.border
import com.woodruff.tvexplorer.ui.theme.Shapes
import com.woodruff.tvexplorer.ui.motion.TvCard
import com.woodruff.tvexplorer.ui.icons.Sym
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.woodruff.tvexplorer.notes.Note
import com.woodruff.tvexplorer.notes.Notes
import com.woodruff.tvexplorer.ui.MainViewModel
import com.woodruff.tvexplorer.ui.Screen
import com.woodruff.tvexplorer.ui.components.ButtonRow
import com.woodruff.tvexplorer.ui.components.DialogFrame
import com.woodruff.tvexplorer.ui.components.RemoteTextField
import com.woodruff.tvexplorer.ui.components.ToolButton
import com.woodruff.tvexplorer.ui.components.TvButton
import com.woodruff.tvexplorer.ui.components.TvIcon
import com.woodruff.tvexplorer.ui.components.TvOutlinedButton
import com.woodruff.tvexplorer.ui.motion.Pivot
import com.woodruff.tvexplorer.ui.motion.PivotScroll
import com.woodruff.tvexplorer.ui.theme.Palette
import com.woodruff.tvexplorer.util.Format

/** Sentinel for "new note" in the editor. */
private val NEW = Note("", "", "", 0)

/** Household notes: shopping lists, Wi-Fi passwords for guests, reminders. Also editable from the phone. */
@Composable
fun NotesScreen(vm: MainViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Notes.load(context) }
    val notes by Notes.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Note?>(null) }
    val openSidebar = com.woodruff.tvexplorer.ui.LocalOpenSidebar.current
    BackHandler { openSidebar() }

    Column(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notes", style = MaterialTheme.typography.headlineLarge)
            }
            ToolButton(Sym.Add, "New note", { editing = NEW })
        }
        if (notes.isEmpty()) {
            EmptyState("No notes")
            return@Column
        }
        // The focused row of notes comes to rest a third of the way down, as on Apple TV.
        val grid = rememberLazyGridState()
        PivotScroll(grid, Pivot(fraction = 0.35f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(250.dp),
                state = grid,
                contentPadding = PaddingValues(top = 20.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(notes, key = { it.id }) { n -> NoteCard(n) { editing = n } }
            }
        }
    }

    editing?.let { n ->
        NoteEditor(
            note = n.takeIf { it !== NEW },
            onDismiss = { editing = null },
            onSave = { title, body, pinned ->
                Notes.save(context, n.id.ifEmpty { null }, title, body, pinned)
                editing = null
            },
            onDelete = { Notes.delete(context, n.id); editing = null },
        )
    }
}

@Composable
private fun NoteCard(n: Note, onClick: () -> Unit) {
    // CardStyle's glass and lit rim, the focus ring the card's own (as Drive's tiles).
    TvCard(
        onClick = onClick,
        shape = Shapes.card,
        modifier = Modifier.fillMaxWidth().height(170.dp),
        focusedScale = 1.04f,
        color = Palette.surface, focusedColor = Palette.surfaceHigh,
        borderWidth = 2.dp, focusedBorderColor = Palette.focusRim,
    ) {
        Column(Modifier.fillMaxSize().border(1.dp, Palette.rim, Shapes.card).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    n.title.ifBlank { n.body.lineSequence().firstOrNull().orEmpty() },
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (n.pinned) TvIcon(Sym.PushPin, size = 18.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                n.body, style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(Format.date(n.updated), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
    }
}

@Composable
private fun NoteEditor(note: Note?, onDismiss: () -> Unit, onSave: (String, String, Boolean) -> Unit, onDelete: () -> Unit) {
    var title by remember { mutableStateOf(TextFieldValue(note?.title.orEmpty(), TextRange(note?.title?.length ?: 0))) }
    var body by remember { mutableStateOf(TextFieldValue(note?.body.orEmpty(), TextRange(note?.body?.length ?: 0))) }
    var pinned by remember { mutableStateOf(note?.pinned ?: false) }
    val first = remember { FocusRequester() }
    DialogFrame(if (note == null) "New note" else "Edit note", onDismiss, width = 760) {
        RemoteTextField(
            title, { title = it }, label = "Note title",
            placeholder = "Title", modifier = Modifier.fillMaxWidth(), textModifier = Modifier.focusRequester(first),
        )
        RemoteTextField(
            body, { body = it }, label = "Note", singleLine = false,
            placeholder = "Write something…", modifier = Modifier.fillMaxWidth(),
            textModifier = Modifier.heightIn(min = 140.dp, max = 240.dp),
        )
        ButtonRow {
            TvOutlinedButton(onClick = onDismiss) { Text("Cancel") }
            TvOutlinedButton(onClick = { pinned = !pinned }) { Text(if (pinned) "Unpin" else "Pin") }
            if (note != null) TvOutlinedButton(onClick = onDelete) { Text("Delete", color = Palette.danger) }
            TvButton(onClick = { onSave(title.text, body.text, pinned) }) { Text("Save") }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
