package com.musigatto.fosscore.ui.viewer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.musigatto.fosscore.FOSScoreApp
import com.musigatto.fosscore.library.Stamp
import com.musigatto.fosscore.library.StampStore
import com.musigatto.fosscore.library.Stroke
import com.musigatto.fosscore.library.StrokeStore
import kotlinx.coroutines.launch

/** Undo levels kept per score (a new change throws the redo branch away, like any editor). */
private const val UNDO_LEVELS = 10

/**
 * The pages as they were when the pen was picked up: ✕ (Cancel, issue #50) puts each one back.
 * The undo history keeps the last [UNDO_LEVELS] changes and would not reach the start of a long
 * session, so the session keeps its own copy: the first state of a page wins.
 */
internal class AnnotationSession {
    private val starts = LinkedHashMap<Int, PageEdit>()

    val hasChanges: Boolean get() = starts.isNotEmpty()

    fun note(page: Int, before: PageEdit) {
        starts.getOrPut(page) { before }
    }

    fun pages(): List<Pair<Int, PageEdit>> = starts.toList()

    fun clear() = starts.clear()
}

/**
 * Everything that changes what is written on the page: the undo/redo stack, the ink and stamps the
 * DB has not confirmed yet, the selection and the object being moved. The viewer owns the page
 * data and the tools; this owns the edits, and it is the only place that writes them.
 *
 * The state is Compose state on purpose: the canvas draws it every frame (a drag preview, the
 * optimistic delete) and a flow round trip would only add a hop.
 */
internal class AnnotationViewModel(
    private val sheetId: Long?,
    private var stamps: StampStore,
    private var strokes: StrokeStore,
) : ViewModel() {

    /**
     * Where the marks are kept: a PDF's pages (the repositories), or a MusicXML score's measures
     * (score.MusicXmlMarks), known once the score is open. The editing is the same.
     */
    fun useStores(stamps: StampStore, strokes: StrokeStore) {
        this.stamps = stamps
        this.strokes = strokes
    }

    // --- the edits, on screen before the DB confirms them ---
    // ink already lifted that the DB has not handed back yet: without this the stroke blinks ~20 ms
    var pendingInk by mutableStateOf<List<Stroke>>(emptyList())
    // the same optimistic layer for stamps: a placed stamp is offered at once, so the undo snapshot
    // that the NEXT edit takes includes it even if Room's flow has not come back yet ( Undo would
    // otherwise eat both stamps at once, since the snapshot was read without them)
    var pendingStamps by mutableStateOf<List<Stamp>>(emptyList())
    // optimistic delete: the view hides what is gone at once and the DB confirms afterward.
    // ponytail: a Set of ids pruned by settle(); enough, because deleting never brings ids back.
    var hiddenStampIds by mutableStateOf<Set<Long>>(emptySet())
    var hiddenStrokeIds by mutableStateOf<Set<Long>>(emptySet())

    // --- selection and the object being dragged (the box and the preview the canvas draw) ---
    var selectedId by mutableStateOf<Long?>(null)
    var selectedStrokeIds by mutableStateOf<Set<Long>>(emptySet())
    var dragStamp by mutableStateOf<Stamp?>(null)
    var dragStrokes by mutableStateOf<List<Stroke>?>(null)

    // --- undo / redo, each snapshot with the page it belongs to ---
    var undoStack by mutableStateOf<List<Pair<Int, PageEdit>>>(emptyList())
        private set
    var redoStack by mutableStateOf<List<Pair<Int, PageEdit>>>(emptyList())
        private set

    // --- the pen-picked-up state of every page touched, for ✕ (issue #50) ---
    private val session = AnnotationSession()

    /**
     * When the last stroke was finished. The next one, written a moment later and next to it, is
     * the next letter of the same word (see [groupForStroke]); the gesture reads it and [addInk]
     * sets it.
     */
    var lastInkAt = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * These rows are gone: the optimistic copy must stop drawing them. settle() cannot prune it,
     * because the DB never hands back a row that no longer exists, so the stroke came back to the
     * page a moment after being deleted or erased.
     */
    private fun forget(ids: Collection<Long>) {
        if (ids.isNotEmpty()) {
            pendingInk = pendingInk.filterNot { it.id in ids }
            pendingStamps = pendingStamps.filterNot { it.id in ids }
        }
    }

    /** After a restore the page is exactly [strokes]: anything else in the optimistic layer is inked
     * over a state that no longer exists (an undone stroke would stay on screen forever). */
    private fun keepOnly(edit: PageEdit) {
        val stampIds = edit.stamps.map { it.id }.toSet()
        pendingStamps = pendingStamps.filter { it.id in stampIds }
        val strokeIds = edit.strokes.map { it.id }.toSet()
        pendingInk = pendingInk.filter { it.id in strokeIds }
    }

    /** Stacks [current] (the state before the change) for [page]. */
    fun pushUndo(current: PageEdit, page: Int) {
        undoStack = (undoStack + (page to current)).takeLast(UNDO_LEVELS)
        redoStack = emptyList()
        // and the same state is where ✕ puts the page back: the first one of the session wins
        session.note(page, current)
    }

    fun clearHistory() {
        undoStack = emptyList()
        redoStack = emptyList()
    }

    /** The session starts when the pen is picked up (issue #50). */
    fun beginSession() = session.clear()

    /** ✓ keeps everything: the session is over, its start is no longer needed. */
    fun clearSession() = session.clear()

    val hasSessionChanges: Boolean get() = session.hasChanges

    /** ✕: every page touched since the pen was picked up goes back to its state then. */
    fun cancelSession() {
        val id = sheetId ?: return
        if (!session.hasChanges) return
        // a text being typed is not written yet: it goes with what is undone
        typing = null
        val pages = session.pages()
        session.clear()
        clearHistory()
        clearSelection()
        clearHidden()
        // the pages are about to be replaced: nothing of the optimistic layer can stay on top
        pendingInk = emptyList()
        pendingStamps = emptyList()
        viewModelScope.launch {
            pages.forEach { (page, edit) ->
                stamps.restorePage(id, page, edit.stamps)
                strokes.restorePage(id, page, edit.strokes)
            }
        }
    }

    fun clearSelection() {
        selectedId = null
        selectedStrokeIds = emptySet()
        dragStamp = null
        dragStrokes = null
    }

    fun clearHidden() {
        hiddenStampIds = emptySet()
        hiddenStrokeIds = emptySet()
    }

    /**
     * The DB caught up: forget what it now holds (the optimistic delete, the drag preview, the
     * freshly written ink).
     */
    fun settle(dbStamps: List<Stamp>, dbStrokes: List<Stroke>) {
        val liveStamps = dbStamps.map { it.id }.toSet()
        val liveStrokes = dbStrokes.map { it.id }.toSet()
        if (hiddenStampIds.any { it !in liveStamps }) hiddenStampIds = hiddenStampIds intersect liveStamps
        if (hiddenStrokeIds.any { it !in liveStrokes }) hiddenStrokeIds = hiddenStrokeIds intersect liveStrokes
        dragStamp?.let { d ->
            if (d in dbStamps) dragStamp = null
        }
        dragStrokes?.let { list ->
            // all or nothing: dropping strokes one by one shrank the box to the rows that had not
            // landed yet, which looked like the box jumping from the word to a single stroke
            val settled = list.all { d ->
                dbStrokes.any { s -> s.id == d.id && s.points == d.points && s.width == d.width }
            }
            if (settled) dragStrokes = null
        }
        // the DB has this ink already: drop it from the optimistic layer
        pendingInk = pendingInk.filter { p -> dbStrokes.none { it.id == p.id && it.points == p.points } }
        pendingStamps = pendingStamps.filter { p ->
            dbStamps.none { it.id == p.id && it.x == p.x && it.y == p.y && it.size == p.size }
        }
    }

    fun undo(page: Int, current: PageEdit) {
        val id = sheetId ?: return
        val (target, edit) = undoStack.lastOrNull() ?: return
        // a text being typed is not written yet: it goes with what is undone
        typing = null
        undoStack = undoStack.dropLast(1)
        // what redo puts back is the state we are leaving behind
        if (target == page) redoStack = (redoStack + (target to current)).takeLast(UNDO_LEVELS)
        clearSelection()
        // the "looks deleted now" cache is stale after a restore: the
        // snapshots go back with their original ids
        clearHidden()
        // a drag preview in flight shows the PRE-undo content: the restored rows win
        dragStamp = null
        keepOnly(edit)
        viewModelScope.launch {
            stamps.restorePage(id, target, edit.stamps)
            strokes.restorePage(id, target, edit.strokes)
        }
    }

    fun redo(page: Int, current: PageEdit) {
        val id = sheetId ?: return
        val (target, edit) = redoStack.lastOrNull() ?: return
        // a text being typed is not written yet: it goes with what is undone
        typing = null
        redoStack = redoStack.dropLast(1)
        if (target == page) undoStack = (undoStack + (target to current)).takeLast(UNDO_LEVELS)
        clearSelection()
        clearHidden()
        dragStamp = null
        keepOnly(edit)
        viewModelScope.launch {
            stamps.restorePage(id, target, edit.stamps)
            strokes.restorePage(id, target, edit.strokes)
        }
    }

    /** The selected stamp and/or strokes, gone at once and gone for good. */
    fun deleteSelection(page: Int, stamp: Stamp?, selection: List<Stroke>, current: PageEdit) {
        if (stamp == null && selection.isEmpty()) return
        pushUndo(current, page)
        // the box and the preview of what was moved go too: they drew an empty square where it was
        clearSelection()
        hiddenStampIds = hiddenStampIds + listOfNotNull(stamp?.id)
        hiddenStrokeIds = hiddenStrokeIds + selection.map { it.id }
        forget(selection.map { it.id } + listOfNotNull(stamp?.id))
        viewModelScope.launch {
            stamp?.let { stamps.delete(it) }
            if (selection.isNotEmpty()) strokes.deleteAll(selection)
        }
    }

    /** A finished stroke: kept on screen until the DB hands it back with its id. */
    fun addInk(page: Int, stroke: Stroke, current: PageEdit) {
        pushUndo(current, page)
        lastInkAt = System.currentTimeMillis()
        viewModelScope.launch {
            val id = strokes.insert(stroke)
            // -1: nowhere to keep it (off the music of a MusicXML page): no ghost of it stays
            if (id > 0) pendingInk = pendingInk + stroke.copy(id = id)
        }
    }

    /** The eraser lifted: [goneStamps] and [goneStrokes] are deleted, [newRuns] are the pieces left. */
    fun erase(
        page: Int,
        goneStamps: List<Stamp>,
        goneStrokes: List<Stroke>,
        newRuns: List<Stroke>,
        current: PageEdit,
    ) {
        // a pass that grazed nothing is not an edit: it must not delete-and-reinsert rows (new ids
        // leave the selection and the bin pointing at rows that no longer exist) nor push an undo
        // level that restores the very state we are in
        if (goneStamps.isEmpty() && goneStrokes.isEmpty()) return
        pushUndo(current, page)
        // the box and the preview of what was moved go too: they drew an empty square where it was
        clearSelection()
        hiddenStampIds = hiddenStampIds + goneStamps.map { it.id }
        hiddenStrokeIds = hiddenStrokeIds + goneStrokes.map { it.id }
        forget(goneStamps.map { it.id } + goneStrokes.map { it.id })
        viewModelScope.launch {
            if (goneStamps.isNotEmpty()) stamps.deleteAll(goneStamps)
            if (goneStrokes.isNotEmpty()) {
                strokes.deleteAll(goneStrokes)
                strokes.insertAll(newRuns)
            }
        }
    }

    /** Moved or stretched ink: the same rows, new points and width. */
    fun updateStrokes(page: Int, updated: List<Stroke>, current: PageEdit) {
        pushUndo(current, page)
        viewModelScope.launch { updated.forEach { strokes.update(it) } }
    }

    /** A moved stamp: the old position is the undo snapshot, only when it actually moved. */
    fun updateStamp(page: Int, moved: Stamp, origin: Stamp?, current: PageEdit) {
        if (origin != null && (moved.x != origin.x || moved.y != origin.y)) pushUndo(current, page)
        viewModelScope.launch { stamps.update(moved) }
    }

    /** A placed stamp: it stays selected so its box shows where it landed. */
    fun placeStamp(page: Int, stamp: Stamp, current: PageEdit) {
        pushUndo(current, page)
        viewModelScope.launch {
            val id = stamps.insert(stamp)
            // -1: nowhere to keep it (off the music of a MusicXML page): no ghost of it stays
            if (id > 0) {
                pendingStamps = pendingStamps + stamp.copy(id = id)
                selectedId = id
            }
        }
    }

    /** The colour panel recolours the selected stamp too, as forScore's selection tool does. */
    fun recolorStamp(page: Int, stamp: Stamp, color: Int?, current: PageEdit) {
        if (stamp.color == color) return
        pushUndo(current, page)
        viewModelScope.launch { stamps.update(stamp.copy(color = color)) }
    }

    /**
     * A typed text or its colour/size changed on a text stamp (#35): the same undo a move gets,
     * only when something actually changed. Inserting or deleting a box goes through placeStamp /
     * deleteSelection; moving through updateStamp — this is only for what the EDITOR produces.
     */
    fun editTextBox(page: Int, origin: Stamp?, updated: Stamp, current: PageEdit) {
        if (origin != null && origin.text == updated.text && origin.color == updated.color &&
            origin.size == updated.size
        ) return
        pushUndo(current, page)
        dragStamp = updated
        viewModelScope.launch { stamps.update(updated) }
    }

    // --- the text being typed on the page (#35): nothing is written until the typing is over ---
    var typing by mutableStateOf<TextDraft?>(null)
        private set
    // a new text, or another one typed in: the field starts again with its own words
    var typingKey by mutableStateOf(0)
        private set

    /** Typing starts on [draft]: a new text, or one already on the page. */
    fun startTyping(draft: TextDraft) {
        clearSelection()
        typing = draft
        typingKey++
    }

    /** The text being typed changed (its words, its size or its colour). */
    fun retype(change: (TextDraft) -> TextDraft) {
        typing = typing?.let(change)
    }

    /** The typing is dropped: nothing of it is written (✕, undo, redo). */
    fun dropTyping() {
        typing = null
    }

    /**
     * The typing is over: [outcome] is written as an edit of [page] would be, one step of undo,
     * through the same doors as the rest (a new text is placed, a changed one edited, an emptied
     * one deleted).
     */
    fun endTyping(page: Int, outcome: TypingOutcome, current: PageEdit) {
        typing = null
        when (outcome) {
            is TypingOutcome.Insert -> placeStamp(page, outcome.stamp, current)
            // its place too: the first line stayed put while the box grew or shrank around it
            is TypingOutcome.Update -> editTextBox(page, outcome.origin, outcome.stamp, current)
            is TypingOutcome.Delete -> deleteSelection(page, outcome.origin, emptyList(), current)
            TypingOutcome.Nothing -> Unit
        }
    }

    companion object {
        /** One editor per score: undo, ink and stamps belong to the score being read. */
        fun factory(context: Context, sheetId: Long?) = viewModelFactory {
            initializer {
                val app = context.applicationContext as FOSScoreApp
                AnnotationViewModel(sheetId, app.stampRepository, app.strokeRepository)
            }
        }
    }
}
