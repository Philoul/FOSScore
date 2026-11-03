package com.musigatto.fosscore.ui.setlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.LibraryRepository
import com.musigatto.fosscore.library.SetlistEntry
import com.musigatto.fosscore.library.SetlistRepository
import com.musigatto.fosscore.library.Sheet
import com.musigatto.fosscore.library.dragStep
import com.musigatto.fosscore.library.moved
import com.musigatto.fosscore.ui.library.IndexedRow
import com.musigatto.fosscore.ui.library.alphabetIndex
import kotlinx.coroutines.launch

/**
 * The setlists: their list, or one of them ([shownId]) with its scores in order. A tap on a score
 * opens it in the setlist: turning the page past its end opens the next one.
 *
 * [playingItemId] is the place open in the viewer, marked in the list.
 */
@Composable
fun SetlistsScreen(
    setlists: SetlistRepository,
    library: LibraryRepository,
    shownId: Long?,
    playingItemId: Long?,
    onShow: (Long?) -> Unit,
    onPlay: (setlistId: Long, entry: SetlistEntry) -> Unit,
    onClose: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    if (shownId == null) {
        SetlistList(setlists, onShow, onClose, onOpenMenu)
    } else {
        SetlistDetail(setlists, library, shownId, playingItemId, onBack = { onShow(null) }, onPlay = { onPlay(shownId, it) })
    }
}

@Composable
private fun Header(
    title: String,
    onBack: () -> Unit,
    backLabel: String,
    backSymbol: String = "←",
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
            Text(backSymbol, style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun SetlistList(setlists: SetlistRepository, onShow: (Long?) -> Unit, onClose: () -> Unit, onOpenMenu: () -> Unit) {
    val all by remember { setlists.setlists() }.collectAsState(initial = null)
    var naming by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        // ☰ on the left and ✕ on the right, like the library: the side menu goes to the other views
        Header(stringResource(R.string.library_menu_setlists), onOpenMenu, stringResource(R.string.menu_open), backSymbol = "☰") {
            Button(onClick = { naming = true }, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.setlist_new))
            }
            val closeLabel = stringResource(R.string.action_close)
            TextButton(onClick = onClose, modifier = Modifier.semantics { contentDescription = closeLabel }) {
                Text("✕", style = MaterialTheme.typography.headlineSmall)
            }
        }
        val list = all
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                stringResource(R.string.setlist_none),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { setlist ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onShow(setlist.id) }.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(setlist.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            pluralStringResource(R.plurals.setlist_size, setlist.size, setlist.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (naming) {
        NameDialog(
            title = stringResource(R.string.setlist_new),
            initial = "",
            onDismiss = { naming = false },
            onDone = { name ->
                naming = false
                scope.launch { onShow(setlists.create(name)) }
            },
        )
    }
}

@Composable
private fun SetlistDetail(
    setlists: SetlistRepository,
    library: LibraryRepository,
    id: Long,
    playingItemId: Long?,
    onBack: () -> Unit,
    onPlay: (SetlistEntry) -> Unit,
) {
    val setlist by remember(id) { setlists.setlist(id) }.collectAsState(initial = null)
    val entries by remember(id) { setlists.entries(id) }.collectAsState(initial = emptyList())
    var editing by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (editing) {
        SetlistEditor(setlists, library, id, setlist?.name.orEmpty(), entries, onDone = { editing = false })
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Header(setlist?.name.orEmpty(), onBack, stringResource(R.string.action_back)) {
            TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.setlist_rename)) }
            TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.action_delete)) }
            Button(onClick = { editing = true }, modifier = Modifier.padding(end = 8.dp)) {
                Text(stringResource(R.string.setlist_edit))
            }
        }
        if (entries.isEmpty()) {
            Text(stringResource(R.string.setlist_empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(entries, key = { _, e -> e.itemId }) { index, entry ->
                val playing = entry.itemId == playingItemId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (playing) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                        .clickable { onPlay(entry) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 32.dp),
                    )
                    SheetLines(entry.sheet, bold = playing, modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = stringResource(R.string.setlist_rename),
            initial = setlist?.name.orEmpty(),
            onDismiss = { renaming = false },
            onDone = { name ->
                renaming = false
                scope.launch { setlists.rename(id, name) }
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.setlist_delete_title, setlist?.name.orEmpty())) },
            text = { Text(stringResource(R.string.setlist_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onBack()
                    scope.launch { setlists.delete(id) }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

// title, and the composer under it when there is one
@Composable
private fun SheetLines(sheet: Sheet, bold: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            sheet.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (bold) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sheet.composer.isNotBlank()) {
            Text(sheet.composer, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Editing a setlist, with both sides in sight: the setlist in its order (drag ≡ to move a score,
 * ✕ to take it out) and the library, grouped by letter with an index to jump. A tap on a score of
 * the library puts it at the end of the setlist, even if it is already in it; the scores already
 * in it show on a pale blue background. Side by side on a tablet, one above the other on a phone.
 */
@Composable
private fun SetlistEditor(
    setlists: SetlistRepository,
    library: LibraryRepository,
    id: Long,
    name: String,
    entries: List<SetlistEntry>,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onDone)
    Column(Modifier.fillMaxSize()) {
        Header(name, onDone, stringResource(R.string.action_back)) {
            Button(onClick = onDone, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.setlist_done)) }
        }
        HorizontalDivider()
        val order: @Composable (Modifier) -> Unit = { modifier ->
            EditedOrder(
                entries,
                onReorder = { ids -> scope.launch { setlists.reorder(ids) } },
                onMove = { from, to -> scope.launch { setlists.move(entries, from, to) } },
                onRemove = { itemId -> scope.launch { setlists.remove(itemId) } },
                modifier = modifier,
            )
        }
        val picker: @Composable (Modifier) -> Unit = { modifier ->
            LibraryPicker(
                library,
                present = entries.groupingBy { it.sheet.id }.eachCount(),
                onAdd = { sheet -> scope.launch { setlists.append(id, sheet.id) } },
                modifier = modifier,
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= TWO_COLUMNS) {
                Row(Modifier.fillMaxSize()) {
                    order(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider()
                    picker(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    order(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(thickness = 2.dp)
                    picker(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

// from this width the setlist and the library stand side by side
private val TWO_COLUMNS = 560.dp

/** The setlist being edited: number, ≡ to drag, title and composer, ✕ to take the score out. */
@Composable
private fun EditedOrder(
    entries: List<SetlistEntry>,
    onReorder: (List<Long>) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (itemId: Long) -> Unit,
    modifier: Modifier,
) {
    // the order on screen: follows the setlist, except while a row is dragged
    var order by remember { mutableStateOf(entries) }
    var dragged by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(entries) { if (dragged == null) order = entries }

    // a score just added shows at the end of the list
    val state = rememberLazyListState()
    var shownSize by remember { mutableIntStateOf(entries.size) }
    LaunchedEffect(entries.size) {
        if (entries.size > shownSize) state.animateScrollToItem(entries.size - 1)
        shownSize = entries.size
    }

    fun drop() {
        val ids = order.map { it.itemId }
        if (dragged != null && ids != entries.map { it.itemId }) onReorder(ids)
        dragged = null
        offset = 0f
    }

    Column(modifier) {
        ColumnTitle(pluralStringResource(R.plurals.setlist_size, entries.size, entries.size))
        if (entries.isEmpty()) {
            Text(stringResource(R.string.setlist_empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        val moveUp = stringResource(R.string.setlist_move_up)
        val moveDown = stringResource(R.string.setlist_move_down)
        val moveLabel = stringResource(R.string.setlist_move)
        val removeLabel = stringResource(R.string.setlist_remove)
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            itemsIndexed(order, key = { _, e -> e.itemId }) { index, entry ->
                val isDragged = entry.itemId == dragged
                var height by remember { mutableIntStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) offset else 0f }
                        .background(if (isDragged) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .onSizeChanged { height = it.height }
                        // the drag has no meaning for a screen reader: up and down instead
                        .semantics {
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(moveUp) { onMove(index, index - 1); true }.takeIf { index > 0 },
                                CustomAccessibilityAction(moveDown) { onMove(index, index + 1); true }.takeIf { index < order.size - 1 },
                            )
                        }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 28.dp),
                    )
                    Text(
                        "≡",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier
                            .semantics { contentDescription = moveLabel }
                            .pointerInput(entry.itemId) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragged = entry.itemId
                                        offset = 0f
                                    },
                                    onDragEnd = { drop() },
                                    onDragCancel = { drop() },
                                ) { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val at = order.indexOfFirst { it.itemId == entry.itemId }
                                    val step = dragStep(offset, height.toFloat())
                                    if (step != 0 && at + step in order.indices) {
                                        order = order.moved(at, at + step)
                                        offset -= step * height
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    SheetLines(entry.sheet, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                    TextButton(
                        onClick = { onRemove(entry.itemId) },
                        modifier = Modifier.semantics { contentDescription = removeLabel },
                    ) { Text("✕", style = MaterialTheme.typography.titleLarge) }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * The library to pick from: sorted by title, a narrow bar for each letter, and on the right the
 * letters there are, to tap or slide along. A score already in the setlist is on pale blue, with
 * how many times it is in it ([present]: times by score id).
 */
@Composable
private fun LibraryPicker(library: LibraryRepository, present: Map<Long, Int>, onAdd: (Sheet) -> Unit, modifier: Modifier) {
    val sheets by remember { library.sheets() }.collectAsState(initial = emptyList())
    val index = remember(sheets) { alphabetIndex(sheets) { it.title } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val inSetlist = if (dark) Color(0xFF1E3A5C) else Color(0xFFDCEBFF)

    fun jump(letter: Int) {
        val row = index.letters.getOrNull(letter)?.let { index.rowOf(it) } ?: return
        scope.launch { state.scrollToItem(row) }
    }

    Column(modifier) {
        ColumnTitle(stringResource(R.string.library_title))
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state) {
                items(index.rows, key = { row ->
                    when (row) {
                        is IndexedRow.Letter -> "letter:" + row.letter
                        is IndexedRow.Item -> "sheet:" + row.item.id
                    }
                }) { row ->
                    when (row) {
                        is IndexedRow.Letter -> Text(
                            row.letter,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                        is IndexedRow.Item -> {
                            val times = present[row.item.id] ?: 0
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (times > 0) inSetlist else MaterialTheme.colorScheme.surface)
                                    .clickable { onAdd(row.item) },
                            ) {
                                SheetLines(row.item, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 40.dp, top = 8.dp, bottom = 8.dp))
                                // how many times it is in the setlist, in the top right corner: a
                                // score played twice shows at a glance
                                if (times > 0) {
                                    Text(
                                        stringResource(R.string.setlist_times, times),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp),
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            // the index: from the top, level with the first letter's bar, one letter under the other
            // at a fixed spacing, whatever their number or the orientation; it scrolls on its own
            // when they do not all fit
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(INDEX_WIDTH)
                    .verticalScroll(rememberScrollState()),
            ) {
                index.letters.forEachIndexed { at, letter ->
                    Box(
                        Modifier
                            .height(INDEX_ROW)
                            .fillMaxWidth()
                            .clickable { jump(at) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(letter, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// a letter of the index: high enough to tap without missing it, low enough to show many at once
private val INDEX_ROW = 32.dp
private val INDEX_WIDTH = 32.dp

// the small title over each side of the editor
@Composable
private fun ColumnTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.setlist_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
