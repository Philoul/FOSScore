package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.ui.viewer.LINE_CLOSE
import com.musigatto.fosscore.ui.viewer.LineIcon

/** A space separates tags; `,` and `;` stay so old free text still reads as separate tags. */
private val SEPARATORS = Regex("[\\s,;]+")

internal fun parseTags(text: String): List<String> = text.split(SEPARATORS).filter { it.isNotBlank() }

/**
 * Tags as one space-separated string: [added] is appended to what was there, dropping repeats
 * (same comparison as the search: case and accents fold).
 *
 * ponytail: old tags with a space inside are split on the next merge; the space is the delimiter now.
 */
internal fun mergeTags(existing: String, added: String): String =
    (parseTags(existing) + parseTags(added)).distinctBy(::normalize).joinToString(" ")

/**
 * Tags shown as chips: a space (or Done) turns what is typed into a chip and ✕ removes one.
 * [draft] is the tag still being typed, so the caller can save it even without the final space.
 */
@Composable
fun TagField(
    tags: String,
    draft: String,
    onTagsChange: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val parsed = parseTags(tags)

    fun commit() {
        onTagsChange(mergeTags(tags, draft))
        onDraftChange("")
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parsed.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                parsed.forEachIndexed { index, tag ->
                    TagChip(tag) {
                        onTagsChange(parsed.filterIndexed { i, _ -> i != index }.joinToString(" "))
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { typed ->
                // everything before the last separator is finished (a paste can bring several)
                val cut = typed.indexOfLast { it.isWhitespace() || it == ',' || it == ';' }
                if (cut < 0) {
                    onDraftChange(typed)
                } else {
                    onTagsChange(mergeTags(tags, typed.substring(0, cut)))
                    onDraftChange(typed.substring(cut + 1))
                }
            },
            label = { Text(stringResource(R.string.field_tags)) },
            placeholder = { Text(stringResource(R.string.tag_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TagChip(tag: String, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tag, style = MaterialTheme.typography.labelLarge)
            val removeLabel = stringResource(R.string.tag_remove)
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = removeLabel }
            ) {
                LineIcon(LINE_CLOSE, size = 14.dp)
            }
        }
    }
}
