package com.musigatto.fosscore.ui.viewer

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.R
import com.musigatto.fosscore.score.SheetFormat
import java.io.File

/**
 * How a PDF whose music is read is shown: as itself, or as that music laid out as a MusicXML
 * score ([musicXml]), which its layout panel then sets (size, transposition written, lyrics,
 * parts). [onChoose] switches from one to the other; the choice is kept per score.
 */
class ScoreView(val musicXml: Boolean, val onChoose: (Boolean) -> Unit)

/** The choice between the PDF and the MusicXML view of it, at the top of the layout panel. */
@Composable
internal fun ViewChoice(view: ScoreView) {
    SectionLabel(stringResource(R.string.view_section_label))
    OptionRow(stringResource(R.string.view_pdf), !view.musicXml, Role.RadioButton) { if (view.musicXml) view.onChoose(false) }
    OptionRow(stringResource(R.string.view_musicxml), view.musicXml, Role.RadioButton) { if (!view.musicXml) view.onChoose(true) }
}

/**
 * The layout panel of a PDF: the PDF is laid out as printed, so the panel offers its MusicXML
 * view, where the layout settings apply.
 */
@Composable
fun PdfLayoutOverlay(view: ScoreView, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    PanelOverlay(stringResource(R.string.menu_layout), onDismiss, modifier) {
        ViewChoice(view)
        Text(
            stringResource(R.string.view_layout_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The file the viewer shows for the score at [path]: the score itself, or the music read from it
 * ([companion]) when the MusicXML view is chosen ([musicView]) and that music is there. A
 * MusicXML score is always itself.
 */
internal fun viewedFile(path: String, musicView: Boolean, companion: File): File =
    if (musicView && !SheetFormat.of(path).isMusicXml && companion.isFile) companion else File(path)
