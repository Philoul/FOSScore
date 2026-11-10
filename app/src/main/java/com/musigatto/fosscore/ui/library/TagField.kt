package com.musigatto.fosscore.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.library.distinctTagNames
import com.musigatto.fosscore.library.normalize
import com.musigatto.fosscore.library.sameTag
import com.musigatto.fosscore.ui.viewer.LINE_CLOSE
import com.musigatto.fosscore.ui.viewer.LineIcon

/** Keeps a list of tags across a rotation; null (not read yet) stays null. */
internal val TagListSaver = Saver<List<String>?, ArrayList<String>>(
    save = { tags -> tags?.let { ArrayList(it) } },
    restore = { it }
)

/** A space separates tags as they are typed; `,` and `;` too, for a pasted list. */
private val SEPARATORS = Regex("[\\s,;]+")

/** [chosen] with [added] after it, each tag once (case and accents fold, the first spelling stays). */
internal fun withTags(chosen: List<String>, added: String): List<String> =
    distinctTagNames(chosen + added.split(SEPARATORS))

/**
 * What typing [typed] does: everything before its last separator is finished and joins [chosen],
 * the rest is still being typed. A paste can bring several tags at once.
 */
internal fun typedTags(chosen: List<String>, typed: String): Pair<List<String>, String> {
    val cut = typed.indexOfLast { it.isWhitespace() || it == ',' || it == ';' }
    return if (cut < 0) chosen to typed else withTags(chosen, typed.substring(0, cut)) to typed.substring(cut + 1)
}

/** The library's tags the score does not have yet, those holding [draft] when something is typed. */
internal fun suggestTags(known: List<String>, chosen: List<String>, draft: String): List<String> {
    val typed = normalize(draft.trim())
    return known.filter { tag -> chosen.none { sameTag(it, tag) } && normalize(tag).contains(typed) }
}

/**
 * Tags shown as chips: a space (or Done) turns what is typed into a chip and ✕ removes one. Under
 * the field, the library's other tags ([known]): a tap puts one on the score, so a tag is picked
 * rather than typed again. [draft] is the tag still being typed, so the caller can save it even
 * without the final space.
 */
@Composable
fun TagField(
    tags: List<String>,
    draft: String,
    known: List<String>,
    onTagsChange: (List<String>) -> Unit,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    fun commit() {
        onTagsChange(withTags(tags, draft))
        onDraftChange("")
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEachIndexed { index, tag ->
                    TagChip(tag) {
                        onTagsChange(tags.filterIndexed { i, _ -> i != index })
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { typed ->
                val (chosen, rest) = typedTags(tags, typed)
                if (chosen != tags) onTagsChange(chosen)
                onDraftChange(rest)
            },
            label = { Text(stringResource(R.string.field_tags)) },
            placeholder = { Text(stringResource(R.string.tag_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth()
        )
        val offered = suggestTags(known, tags, draft)
        if (offered.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                offered.forEach { tag ->
                    KnownTagChip(tag) {
                        onTagsChange(withTags(tags, tag))
                        onDraftChange("")
                    }
                }
            }
        }
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

// a tag of the library the score does not have: outlined, "+ name", a tap adds it
@Composable
private fun KnownTagChip(tag: String, onAdd: () -> Unit) {
    val addLabel = stringResource(R.string.tag_add, tag)
    Surface(
        onClick = onAdd,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .heightIn(min = 32.dp)
            .semantics { contentDescription = addLabel }
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+ $tag", style = MaterialTheme.typography.labelLarge)
        }
    }
}
