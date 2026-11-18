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
