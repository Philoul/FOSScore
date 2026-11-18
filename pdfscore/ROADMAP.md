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
| Comparison with Audiveris (AGPL-3.0 OMR engine) as a reference | proposed |
| Kotlin port, whole page (`pdfscore.bitmap`, `BitmapPage.read`: gray image + dpi → `PageDrawing`) | done: every stage of the spec, the kNN model shipped in the module's resources (CRC checked); the golden blobs and both golden pages (clean, rough) match the Python reference exactly; ~40 ms on the 1300 × 309 golden crop, 0.45–0.85 s a 300 dpi page on the PC |
| Reading scans with the PDF readers (`ScoreReader` unchanged) | first results: ledger lines and evenly spaced staff lines added to the page model; one private score's clean render 257 / 262 staff-measures with the right pitches, the other renders far lower (octave lines, keys, a missed barline shifts the measures) |
| Streaming, system by system (STREAMING.md), and the app's scan import | next |

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
