package com.musigatto.fosscore.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.Layer
import kotlinx.coroutines.launch

/**
 * The name of a layer as the user sees it: the one they gave it, or a translated default by its
 * position (a layer created by the migration has no name; SQL cannot write translated text).
 */
fun layerLabel(context: Context, layers: List<Layer>, id: Long): String {
    val index = layers.indexOfFirst { it.id == id }
    val name = layers.getOrNull(index)?.name.orEmpty()
    return name.ifBlank { context.getString(R.string.layer_default, (index + 1).coerceAtLeast(1)) }
}

/** The name a shared copy gets: the score's own, the chosen layer, and the extension. */
fun shareFileName(base: String, choice: String): String = "$base ($choice).pdf"

/**
 * The layer chip of the annotation bar: the layer new marks go to, and the menu that adds, selects,
 * shows/hides, renames and deletes layers. It only draws and reports taps; the viewer owns the state.
 */
@Composable
internal fun LayerChip(
    layers: List<Layer>,
    activeId: Long,
    markCounts: suspend () -> Map<Long, Int>,
    onSelect: (Long) -> Unit,
    onToggleVisible: (Long, Boolean) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onAdd: (String) -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Layer?>(null) }
    var deleting by remember { mutableStateOf<Layer?>(null) }
    var deleteCount by remember { mutableIntStateOf(0) }
    val newName = stringResource(R.string.layer_default, layers.size + 1)
    val activeLabel = layers.firstOrNull { it.id == activeId }
        ?.let { layerLabel(context, layers, it.id) }
        ?: stringResource(R.string.layer_default, 1)
    // the icons alone say nothing to a screen reader
    val showLabel = stringResource(R.string.layer_show)
    val hideLabel = stringResource(R.string.layer_hide)
    val renameLabel = stringResource(R.string.layer_rename)
    val deleteLabel = stringResource(R.string.action_delete)

    LaunchedEffect(deleting) {
        deleting?.let { deleteCount = markCounts()[it.id] ?: 0 }
    }

    Box {
        TextButton(onClick = { open = true }) { Text(activeLabel) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            layers.forEach { layer ->
                val label = layerLabel(context, layers, layer.id)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { onToggleVisible(layer.id, !layer.visible) },
                                modifier = Modifier.semantics {
                                    contentDescription = if (layer.visible) {
                                        hideLabel
                                    } else {
                                        showLabel
                                    }
                                },
                            ) {
                                ToolIcon(ICON_EYE, Modifier.alpha(if (layer.visible) 1f else 0.35f))
                            }
                            IconButton(
                                onClick = { renaming = layer },
                                modifier = Modifier.semantics { contentDescription = renameLabel },
                            ) { ToolIcon(ICON_PENCIL) }
                            IconButton(
                                enabled = LayerLogic.canDelete(layers, layer.id),
                                onClick = { deleting = layer },
                                modifier = Modifier.semantics { contentDescription = deleteLabel },
                            ) { ToolIcon(ICON_TRASH) }
                        }
                    },
                    leadingIcon = {
                        if (layer.id == activeId) ToolIcon(ICON_CHECK) else Spacer(Modifier.width(20.dp))
                    },
                    onClick = {
                        onSelect(layer.id)
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.layer_new)) },
                leadingIcon = { Text("+") },
                onClick = {
                    onAdd(newName)
                    open = false
                },
            )
        }
    }

    renaming?.let { layer ->
        var text by remember(layer.id) { mutableStateOf(layer.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.layer_rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(R.string.layer_default, layers.indexOfFirst { it.id == layer.id } + 1))
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(layer.id, text.trim())
                    renaming = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    deleting?.let { layer ->
        val label = layerLabel(context, layers, layer.id)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.layer_delete_title)) },
            text = { Text(stringResource(R.string.layer_delete_message, label, deleteCount)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(layer.id)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * Share panel: which marks go out, and where the copy is saved. The copy carries them as PDF
 * annotations (comments on a PC); the score in the app and its sidecar stay untouched, so the
 * export can be repeated and nothing depends on it.
 *
 * The counts come from Room for the whole sheet: the viewer's own flows only hold the pages around
 * the one on screen, and a layer with marks on page 40 must still be offered here.
 */
@Composable
internal fun LayerExportSheet(
    editor: AnnotationViewModel,
    /** The score's file name without extension: the copy is named after it and the layer. */
    shareName: String,
    onExport: suspend (layerId: Long?, destination: Uri) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val layers = editor.layerList
    var counts by remember { mutableStateOf<Map<Long, Int>?>(null) }
    LaunchedEffect(Unit) { counts = editor.markCounts() }
    // null = every layer in one file
    var choice by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // the system picker chooses the place (any document provider): no permission of ours and no
    // account of ours
    val pickPlace = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        saving = true
        error = null
        scope.launch {
            val failure = runCatching { onExport(choice, destination) }
                .exceptionOrNull()
                ?.let { (it as? ExportError)?.res ?: R.string.err_export_failed }
            saving = false
            error = failure
            if (failure == null) onDismiss()
        }
    }
    val total = counts?.values?.sum() ?: 0
    val chosen = choice?.let { layerLabel(context, layers, it) } ?: stringResource(R.string.layer_all)

    PanelOverlay(stringResource(R.string.share_title), onDismiss, modifier) {
        when {
            counts == null -> Unit
            total == 0 -> Text(stringResource(R.string.err_no_marks))
            else -> {
                OptionRow(stringResource(R.string.layer_all), choice == null, Role.RadioButton) {
                    choice = null
                }
                // a hidden layer is offered too: hiding it on screen says nothing about sharing it
                layers.filter { (counts?.get(it.id) ?: 0) > 0 }.forEach { layer ->
                    OptionRow(layerLabel(context, layers, layer.id), choice == layer.id, Role.RadioButton) {
                        choice = layer.id
                    }
                }
                error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    enabled = !saving,
                    onClick = { pickPlace.launch(shareFileName(shareName, chosen)) },
                ) { Text(stringResource(R.string.share_save)) }
            }
        }
    }
}