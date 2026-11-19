# PDF score recognition: roadmap and progress

Module `:pdfscore` reads a score drawn in a PDF (or, later, in a scanned image) and writes it as
MusicXML, so a PDF can be played, followed, transposed and re-laid out like a MusicXML score. The
prototype branch `proto/pdf-to-musicxml` wires it into the app (viewer menu ›
"Convert to MusicXML").

Measurements below come from a private corpus (scores engraved by the maintainer in MuseScore,
exported as PDF and as MusicXML; never committed) and from free fonts. "Staff-measures right" is
the share of (staff, measure) cells whose content equals the reference MusicXML's.

## Track 1: vector PDFs with a music font

Music symbols are font glyphs, named by their SMuFL code points.

| Feature | State |
|---|---|
| Staves, systems, braces, barlines | done |
| Clefs, keys, time signatures, mid-piece changes | done: keys and times 100 % |
| Pitches, accidentals (drawn and in force) | done: 98–100 % of staff-measures |
| Durations, dots, voices, tuplets, grace notes, staccato | done: 98–100 % |
| Beams (begin/continue/end/hooks) | done: 99 % |
| Octave lines (8va/8vb/15ma) | done |
| Ties | done: 99–100 % |
| Double and final barlines | done |
| Arpeggios, fermatas | done |
| Metronome marks (tempo used by playback), tempo/expression words | done |
| Lyrics | done: 92–94 % of sung measures, syllables and hyphens; lyrics written over an accompaniment staff (not under a voice) still poor |
| Part names, title, composer and lyricist | done: read on every SMuFL-font score of the corpus |
| Repeat barlines (played twice) | done: as the references, with and without fonts |
| Endings (1st/2nd time), segno/coda, D.C./D.S./Fine/To Coda (MusicXML sound jumps) | done on synthetic pages (the private corpus has none with fonts); without fonts: segno/coda read, ending numbers wait for OCR; one false segno on one score |
| Slurs, dynamics, tuplet brackets | later |

## Track 2: vector PDFs without fonts ("printed to PDF")

Every symbol is a filled outline with no name; each fill is compared with the reference fonts'
symbols (Bravura and Leipzig, shipped with the app) at the staff's scale.

| Feature | State |
|---|---|
| Music symbols by their look | done: pitches 96–100 %, durations 90–99 %; a head far from the staff counts only on ledger lines (chord symbols drawn as outlines are no notes) |
| Text drawn as outlines (lyrics, titles, tempo numbers) | planned: OCR with Tesseract (Tesseract4Android, Apache-2.0), not a home-made OCR |
| Speed on the Tab 3 (3 pages) | 23.9 s → 9.1 s: MuPDF SVG read by a tag scanner (5 s → 0.8 s a page), reference shapes kept in the cache (6.4 s → 0.1 s) |

## Track 3: scanned scores (bitmap, 300 dpi and more)

| Step | State |
|---|---|
| Research prototype (Python): staves, deskew, staff-line removal, segmentation, kNN classifier | done: staves 100 %, symbols ≈97 % P/R on degraded renders |
| Test set (300 dpi renders of the private corpus) and free training set (random music engraved by Verovio in its free fonts) | in progress |
| Comparison with Audiveris (AGPL-3.0 OMR engine) as a reference | done 2026-11-18: not embeddable (AWT/Swing, Java 25, 8 GB heap, 13–115 s a page on the PC); far better on our renders (lucie3 258/262 clean, 258 scan, 256 rough; malade 205/220; msg 66/66). Kept as a PC-side benchmark and second opinion; its staff/barline, head-template, key and octave-line builders are ideas to borrow (same licence); chosen 2026-11-18 as the PC benchmark |
| OCR (text drawn as outlines, scans) | first use done 2026-11-18: the number of a metronome mark printed without its font (`OutlineText` says where, the app draws it in gray and Tesseract reads it; Tab 3: "= 70", "= 66", "= 100", "= 55", "= 63" read, Savoir aimer plays at ♩ = 70). Next: tempo words, measure numbers, the text of scans. Tesseract4Android chosen 2026-11-18 (module `:ocr`, tessdata_fast eng/fra/ita/deu downloaded by the build and checked; the APK grows from 99 to 132 MB with four ABIs: ABI splits to consider); evaluated 2026-11-18: Tesseract4Android 4.9.0 (Apache-2.0, x86 included, minSdk 21, ~7.5 MB a ABI + 9 MB tessdata_fast eng/fra/ita/deu) recommended, in its own module; PaddleOCR (ONNX) better on italic tempo words and scans but ONNX Runtime is 36–43 MB and minSdk 24 |
| Kotlin port, whole page (`pdfscore.bitmap`, `BitmapPage.read`: gray image + dpi → `PageDrawing`) | done: every stage of the spec, the kNN model shipped in the module's resources (CRC checked); the golden blobs and both golden pages (clean, rough) match the Python reference exactly; ~40 ms on the 1300 × 309 golden crop, 0.45–0.85 s a 300 dpi page on the PC |
| Reading scans with the PDF readers (`ScoreReader` unchanged) | first results: ledger lines and evenly spaced staff lines added to the page model; one private score's clean render 257 / 262 staff-measures with the right pitches, the other renders far lower (octave lines, keys, a missed barline shifts the measures) |
| The app's scan reading | done 2026-11-18 (proto): a page holding a picture and no drawn staff is drawn in gray (300 dpi, at most as wide as A4) and read by `BitmapPage`; Halleluya (7 pages, A3, strips): 112 measures in 91 s on the Tab 3, played. Two parts found for one piano (no brace on scans) |
| Streaming, system by system (STREAMING.md) | next |
| Audiveris as the PC benchmark | set up 2026-11-18: `PrivateScansProbeTest -PpdfscoreOut=<folder>` writes our MusicXML, `D:/FOSScore-eval/scripts/bench.py` compares it and Audiveris's with the reference (staff-measures right, strict and after aligning the measures). Baseline on the 9 private renders (3 scores × clean, scan, rough): FOSScore 683 strict / 1123 aligned, Audiveris 1563 / 1566. The gap between strict and aligned says first: barlines missed or added shift every measure after them (lucie3 rough 12 strict, 191 aligned) |

## App and MusicXML format

Decisions (maintainer, 2026-11-18):

1. Only the PDF shows in the library. The MusicXML is its companion, used to play it, for the MIDI
   and MusicXML exports, for a transposition the user asks for, and for a zoom (re-layout) the user
   asks for to read better.
2. Decoding starts at the PDF's import, in the background at low priority. The play button starts
   it at once if it has not started yet; playback then begins immediately, as decoding is much
   faster than the music and the player takes notes as they come.
3. The companion goes into the library export by default (an export setting may come later).
4. Doubts and errors: an editor of the MusicXML (both versions zoomed line by line side by side, to
   compare and correct) is for a second stage, not the first version.
5. PDF annotations kept in the MusicXML view (or a show/hide setting): a later improvement, not in
   the first PR.
6. Files first while developing; once it works, the PR brings the Room schema change for a clean
   integration.

The Score layout panel on a PDF (maintainer, 2026-11-18, later): the panel a MusicXML score
has (parts played or not where several sound together, MIDI sounds, transposition heard and
written, display zoom) for a PDF's music too.

Steps: (1) companion file and playback from it, (2) red bar on the PDF, (3) streaming decode while
playing, (4) the MusicXML view on demand (transposition, zoom), (5) instruments, (6) annotations.

Design notes:

- Playback that starts at the user's click, the score being decoded system by system while it
  plays (decoding is much faster than the music).
- The MusicXML kept as a companion of the PDF; the PDF stays the page shown, the MusicXML view only
  when the user asks for it (transposition, re-layout).
- Parts and instruments as written left of the staves; automatic page turns; the metronome only as
  a fallback.

Progress:

| Step | State |
|---|---|
| 1. Companion and playback | done 2026-11-18 (proto/pdf-to-musicxml): the MusicXML kept in `filesDir/companions`, opened unseen and laid out once; ▶ on a PDF plays it, reads it first when there is none (then plays), keeps the metronome when no music was found (marked per reader version). Tab 3: Savoir aimer (no fonts) read in 8.7 s, Lucie in 6.5 s, Halleluya (scan) in 91 s. Fixed on the way: two live players made at once (bar stuck at 1). Not yet then: tempo of PDFs without fonts (needs OCR), ~17 s from read to sound (Verovio layout + MIDI: step 3 will play from the reading directly) |
| 1b. Reading at import, companion in the export | done 2026-11-18 (proto): the library's PDFs read in the background, newest import first, on a thread at the background priority, page by page while the tablet is left alone and nothing plays; ▶ on a PDF being read waits for that reading and hurries it, on one not read reads it at once. One MuPDF lock for the process (the viewer and the background reading take turns). Companions named by the PDF's MD5 (they survive export/import), in the export by default (`companions/<hash>.musicxml` and `.places`), deleted with their sheet. Tab 3: the 19 PDFs of the library read in about 5 minutes, export with 19 companions |
| 2. Red bar on the PDF, page turns | done 2026-11-18 (proto): the places of the notes on the PDF kept beside the companion (`ScoreReading.followPlaces`, `CompanionPlaces`); the bar and the page turns follow `MusicPlaces` (a MusicXML layout or a PDF's companion). Tab 3: Je suis malade plays from its PDF with the bar on the notes, the half page turns at measure 19 and the page at 32. Seen on the way: a PDF engraved by Verovio (staff lines drawn measure by measure) is read with its first system cut (14 of 16 measures): to fix on track 1 |

## Reading problems reported (maintainer's tests on the Tab 3)

Kept here as they are reported; worked on in the order that helps playback most, not the order
they came in. Each fix gets a test seen failing first, and is measured on the private scores and
the benchmark before it is installed.

| # | Score (kind) | Problem | Cause found | State |
|---|---|---|---|---|
| 1 | J'te l'dis quand même (no fonts) | the bar goes back and forth, notes played late | voices longer than their measure: a rest over a chord put every other note in the chord's voice; lyric hyphens between the piano's staves and flat beam hooks read as whole rests | fixed 2026-11-18 (reader 4): voices by stems, rests out of the staff need a ledger line, rectangles held by a stem are hooks, the writer cuts what goes past the measure. 113 / 114 measures with the right rhythm |
| 2 | Je veux être un homme heureux (no fonts) | measures 8, 14, 18, 30, 36, 40 end on a sixteenth lasting 2-3 beats | same as 1 (false half and whole rests); an open viewer kept the companion of the older reader | fixed 2026-11-18: all 52 measures exactly 4 beats in Verovio; a companion read again is opened at once. To check on the Tab 3 |
| 3 | Je veux être un homme heureux (no fonts) | half page: a system cut by the half-page line; the turn came at the end of 22 instead of 19 (and 35 instead of 33) | the follow counted a note as shown by its head, not by its system | fixed 2026-11-18: a system the cut goes through is in neither part. Next: place the cut between systems with the companion's places when the blank bands miss |
| 4 | Lettre à France (no fonts, LilyPond) | one head with two stems (melody up, sixteenths down) | a head goes to one stem only: the other voice loses the note (also the missing second voice of Je veux être un homme heureux, measure 8) | fixed 2026-11-19: a head with a stem up on its right and one down on its left is a note of both chords, struck together. Lettre à France 71 -> 74 / 92 |
| 5 | Lettre à France | triplet "3" without bracket read as plain eighths; one "3" per six sixteenths (LilyPond) | the "3" is an outline (no font): not read | fixed 2026-11-19: a tuplet's number printed without its font is named out of the staff (classifier: 0.89-0.91 on Lettre's), the beam under it makes the tuplet |
| 6 | Lettre à France | in 4/4 there must be exactly 4 beats between two barlines | consequence of 4 and 5 (the writer now cuts what goes past) | fixed 2026-11-19 (ImpliedTuplets): a voice too long whose beamed groups of 3, 6... equal notes, made triplets, take off exactly the excess has them as triplets (LilyPond prints one "3" for some groups only). Lettre à France 75 -> 82 / 92 |
| 7 | Lettre à France | multiple repeats: "1. 2." (24-28), "3." (29) with To Coda, coda at 31, `:|` at 46, "4." (30) the end | ending numbers and "To Coda" are outline text; and Verovio cannot play this form even from MuseScore's own MusicXML (no To Coda, endings of several passes wrong); MuseScore exports the ending numbers wrong (number="2" for "1. 2.", "3" for "4.", the text is right) | play order done 2026-11-19 (Unfold in :musicxml, MIDI and timing map made from the score unrolled; notes with stable ids): MuseScore's export plays 1-28, 17-28, 17-23, 29, 31-46, 17-23, 30. Endings and words over the music read by OCR on PDFs without fonts (reader 8; Strokes: LilyPond draws brackets as thin fills; endings found up to 9 spaces over the staff, over the lyrics: Lettre's "1. 2.", "3.", "4."). To check on the Tab 3 (it locked at night): D:/FOSScore-builds/fosscore-proto-1119-reader10.apk |
| 8 | Savoir aimer (no fonts) | a repeat and a jump missed | the "2." ending and "3. To Coda" are outline text; a false segno | endings and words read by OCR 2026-11-19 ("2." found on page 2); its two segnos and coda are real; the false segno was J'te l'dis's (0.71): signs named from 0.8 only (reader 11). To check on the Tab 3 |
| 9 | Au clair de la lune (engraved by Verovio) | first system read cut, 14 of 16 measures, bar shifted | Verovio draws staff lines measure by measure: one staff segment seen per system | fixed 2026-11-19 (reader 10): the pieces of a staff line at one height (within 0.3 point) are joined left to right; 16 / 16 measures, 48 / 48 staff-measures right |
| 10 | Halleluya (scan) | two parts for one piano | no brace found on scans | fixed 2026-11-20 (reader 16): ink all along the left of two staves of a system is a brace (the scan model names none); Halleluya, Message personnel and Je suis malade read as one piano |
| 11 | every PDF (Tab 3) | ▶ silent 5 to 10 s, music already read | measured on Lucie: layout 3.3 s, then 13.2 s before the MIDI: the engine makes it after a first page is drawn, and a companion is never drawn (15 s wait); each new reading emptied the cache; ▶ before the companion was open read the PDF again | fixed 2026-11-19: companion opened sound first, prepared after its reading, ▶ waits for it instead of reading again, no layout to play (the timing map comes with the MIDI). From the tap on a score in the library: ready to play 2.1 s, warmed up 4.9 s (about 20 s before) |
| 12 | Halleluya (scan, no MusicXML of it) | the bar goes back, measures too short or too long; the treble clef's loop seems read as a note; first system the worst | to analyse (track 3); Audiveris's reading installed on the Tab 3 as "Halleluya (Audiveris)": judged unusable by the maintainer (2026-11-19: repeats that are not in the score, the bar followed them while the sound went on), not a reference | in progress 2026-11-20: heads named under 0.45 left out (a junk half note at 0.21 made measure 9 two beats long): scan benchmark 699 -> 701 / 1161; 50 -> 49 of 113 measures with a voice not filling it. Seen: 19 half heads without a stem line, 18 of them in measures too long; but leaving them out lost real half notes (benchmark 638): the stems of half notes are often not found on scans. Next: find the stem of a half head from the pixels beside it (as Audiveris checks the ink beside a stem) |
| 13 | J'ai encore rêvé d'elle | ▶ sounds after 4-5 s although the tablet was idle for hours: "at once" means under half a second | tested on the timemap2 build (00:08), without #11's companion opened sound first; even with it, the live player and the sound bank warm up after the tap | 2026-11-20 on the Tab 3 (reader 17, Savoir aimer just read): the bar was already past the first measure 2.5 s after ▶, the music started within about a second. To measure to the tenth (a log at the first note) |
| 14 | J'ai encore rêvé d'elle | measure 7 top staff: notes not read; many other notes missing | letters of the lyrics (no font) read as breve rests: two whole notes of rest pushed the notes into the next measures | fixed 2026-11-19 (reader 12): a breve rest out of the staff needs a ledger line. Voices longer than their measure 45 -> 8 |
| 15 | J'ai encore rêvé d'elle | from measure 9: the bar jumps back and forth quickly near line ends | probably voices that do not fill or overflow their measure (as 1) | fixed 2026-11-20 (reader 13-15): the bar never goes back within a measure (13); the voices that overflowed (letters of the lyrics read as rests or as notes under a ledger line, cue notes read as grace notes): this score 45 -> 0 measures with a voice too long |
| 16 | J'ai encore rêvé d'elle | quarter-note triplets (in the vocal line) read as plain quarters | ImpliedTuplets only makes beamed groups triplets; quarters have no beam, the "3" (with or without bracket) must make them | fixed 2026-11-20 (reader 14): the quarter triplets were read (19, 26, 30) but Verovio played them as quarters: the MusicXML written had no `<tuplet>` start and stop, which Verovio needs; now written for every run of one ratio |
| 17 | J'ai encore rêvé d'elle | tempo 120 where the score says 100 | the mark not read (default 120). Rule (maintainer): a tempo changed during a session holds for the session; a tempo set in the score's settings (if that setting exists) is the default; otherwise the tempo printed at the start | fixed and seen on the Tab 3 2026-11-20 (reader 18): the band shows ♩ = 100 and the music keeps it (8 measures in 18 s). The mark's note, smaller than the font's and before an "=" of two lines, is named; it stands 6.9 spaces over the staff, over the chord symbols: tempos are looked for up to 9. A tempo changed by hand holds for the session only |
| 18 | J'ai encore rêvé d'elle | grace notes (small, slashed eighth flag) not read | small heads and the slashed flag not named | fixed 2026-11-19 (reader 12): small heads on a short stem are named at 70 %; not struck with a normal note, they are grace notes (measure 40 read). To check by ear |
| 19 | J'ai encore rêvé d'elle | measure 41: a snare-drum triplet (other noteheads, "caisse claire" written under it) played on the piano | percussion heads not recognised | later (maintainer) |
| 20 | J'ai encore rêvé d'elle | measure 18: the piano's first eighth comes late | a quarter rest of the synth line (small notes, top) and an eighth rest of the piano (below, same x): the piano voice took the quarter rest instead of its eighth rest | fixed 2026-11-19 (reader 12): small heads on a short stem named at 70 %; small notes struck with normal ones are a cue voice, not grace notes; a lone rest takes the voice of the rests at its height |
| 21 | Savoir aimer (no fonts) | measure 56 goes on to 57 instead of back to 25 for the third pass; on that third pass the jump to 57 is at the end of 39 | still so with reader 11: "To" and a segno sign (56), "3. To" and a coda sign (39) were read as a segno and a coda where they stand; the "2." ending was stretched to the coda's repeat | fixed and seen on the Tab 3 2026-11-20 (reader 17): played from page 3, 52-56 then back to 25 (the bar on page 2), then from 39 on to 57. Causes: "3. To ⊕" and "To 𝄋" were dropped as lyrics (words are told from lyrics by what they say: "To" now counts), their signs are not named and the OCR reads them in the words ("3. To @", "To $": the sign read says where to go) |
| 22 | Lettre à France (Tab 3, timemap2 build) | measure 1's triplet and measure 9's rhythm wrong; notes missing, the bar back and forth; repeats wrong; from 39 the 24 sixteenths per measure cut instead of faster; after the repeat back the tempo stays at 100 (66 until 38, 100 for 39-46 only) | tested on the build before reader 7: triplets (5, 6), play order (7) not installed. Reader 11 on the PC: measure 1's triplet and 39-44 read right (t24); 45-46 still wrong (upper staff empty, lower voices too long). The tempo after a jump was a real fault of Unfold | tempo fixed 2026-11-19 (Unfold: a jump takes the tempo in force where it lands; MIDI cache 4). To check on the Tab 3 with reader 11; 45-46 to do |
| 23 | Lettre à France (Tab 3, reader 11) | eighth triplets of measures 1 and 31 missed; errors from 15 on; after the repeat at 17 the left hand wrong (a clef?); 39-46: 24 sixteenths a measure at ♩ = 100 must be sixtuplets of sixteenths, not eighth triplets, the second half note not under them; measure 45 notes wrong | the PC reading (no OCR) has 1's triplet and 39-44 right: to compare with the tablet's companion (export) | 2026-11-20: triplets of 1 and 31 and the sextuplets of 39-46 were read, but played at their written value (no `<tuplet>` in the MusicXML: fixed, reader 14); 45: the line's low notes went to the whole note's voice (fixed, reader 15; 82 -> 83 / 92). The left hand from 15 on is read right on the PC and the tablet (same reading): to listen to again with reader 15 |
| 24 | every PDF (bar) | the bar glides past the end of a measure followed by a repeat (Savoir aimer 40: to the end of 41, not played); it still goes back and forth in some measures | the bar glided to the end of the line when the next notes are elsewhere; it went to the leftmost note of each onset, later notes drawn more to the left (voices read wrong) pulled it back | fixed 2026-11-19 (reader 13): it stops at the end of the measure (places keep it), never goes back within a measure played. The wrong voices themselves: 14, 15, 23 |
| 25 | Halleluya (Audiveris) MusicXML | crash at the second tap on play (twice) | Verovio 6.3 SIGSEGV in GenerateMIDIFunctor::HandleOctave: two octave lines start, one stops (not reproduced with Verovio 6.4 on the PC) | fixed and seen on the Tab 3 2026-11-20: ▶, ⏸, ▶ again, no crash, the bar follows (octave lines that never stop left out of the unrolled score) |
| 26 | every score (wish) | starting to play from a chosen place is impossible: it always starts from the beginning or the pause, even on another page | not built | in part 2026-11-20: after a pause, ▶ starts at the page shown when the music paused elsewhere (from a stop it already did). To do: start from a measure touched |
| 27 | Je suis malade (MusicXML) | ▶ slow to start; no bar on page 1, then on page 2 only, then gone | regression of 2026-11-19: the pages kept of a layout had Verovio's own note names, the music the names given by MusicXmlScore (unrolled score): the bar found no note on them | fixed and seen on the Tab 3 2026-11-20: the pages' cache name follows the music's version; both MusicXML scores of Je suis malade show the bar from page 1 and on page 3. The first ▶ after an install makes the music and the pages again |

Speed requirement (maintainer, 2026-11-18): reading must go faster than the music at the
score's tempo (the worst case: by the time a score is chosen, looked at and started, a good part
is read already), so playback starts at once on a score not read yet and never pauses to wait
for the reading. Within that, 5 to 10 s a page, or a little more, is fine if it removes nearly all
errors: today's 0.5 to 1 s a page with many errors is not the right trade-off, Audiveris's 13 to
115 s is far too slow.

Comparison with Audiveris (maintainer, 2026-11-18; analysis done 2026-11-19, report D:/FOSScore-eval/audiveris-vs-fosscore.md, sources read at development 33e1a77): Audiveris reads much better (1563 against
683 measures right on the benchmark) but takes 13 to 115 s a page on the PC, where ours takes
0.5 to 1 s. To do: a comparative analysis of the structure and the algorithms of both (staff and
barline finding, head templates, symbol graph and its reduction, rhythm per measure), to take what
makes Audiveris right while keeping ours fast enough for the tablet; every borrowed idea measured
with `bench.py`.

What the analysis found, and the order to borrow in (each measured with `bench.py`):
- Our barlines are read staff by staff, one unbroken stroke each, never compared across the
  staves of a system: one missed barline shifts every measure after it. Audiveris projects the ink
  over the staff height (breaks tolerated), checks for ink beside a candidate (a stem with its
  head), then aligns barlines across the staves and keeps or adds them by majority
  (StaffProjector, PeakGraph, BarsRetriever, MeasuresBuilder). To borrow first.
- Eighths missed in 6/8: a flag is only read as a piece of a stemmed blob, so a flag cut from its
  stem in a scan is lost and the note becomes a quarter. Audiveris classifies what is left after
  heads, stems, beams, ledgers and barlines, tries combinations of nearby pieces and links flags
  to stems across gaps.
- A bass clef missed: cut in three pieces by the staff-line removal; Audiveris tries combinations
  of pieces at the staff start with only clef shapes allowed, and votes across the staves.
- A local repair of the measures that do not fill (a barline, a dot, a flag, an eighth rest),
  run only where a measure does not add up: Audiveris tried it globally and found it too slow.
- Keep the classifier's two or three best guesses and choose by context (Audiveris's symbol
  interpretation graph costs 0.4 % of its time); adaptive binarization; head templates on chosen
  candidates only (template matching over every pixel is 35 % of Audiveris's 33 s a page, beams
  22 %).
- The tablet's timing of our own reader is to measure before spending the 5-10 s budget.
- Tried 2026-11-19: barlines a staff misses taken from the other staves of its system (kept: the
  private PDFs and the benchmark unchanged, 699 / 1156); a barline on one staff only dropped in a
  system of three staves or more (no effect: dropped); barlines by projection alone, columns
  inked over 90 % of the staff with no head beside (reverted: "Je suis malade" 53 -> 26, chord
  stems and the system's opening line taken for barlines). Projection needs Audiveris's other
  checks with it: ink beside the candidate, alignment across the staves, the opening line left
  out.