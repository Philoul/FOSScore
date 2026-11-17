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
| Repeats, endings, segno/coda, D.C./D.S. (playback jumps) | next |
| Slurs, dynamics, tuplet brackets | later |

## Track 2: vector PDFs without fonts ("printed to PDF")

Every symbol is a filled outline with no name; each fill is compared with the reference fonts'
symbols (Bravura and Leipzig, shipped with the app) at the staff's scale.

| Feature | State |
|---|---|
| Music symbols by their look | done: pitches 96–100 %, durations 90–99 % |
| Text drawn as outlines (lyrics, titles, tempo numbers) | planned: OCR with Tesseract (Tesseract4Android, Apache-2.0), not a home-made OCR |

## Track 3: scanned scores (bitmap, 300 dpi and more)

| Step | State |
|---|---|
| Research prototype (Python): staves, deskew, staff-line removal, segmentation, kNN classifier | done: staves 100 %, symbols ≈97 % P/R on degraded renders |
| Test set (300 dpi renders of the private corpus) and free training set (random music engraved by Verovio in its free fonts) | in progress |
| Comparison with Audiveris (AGPL-3.0 OMR engine) as a reference | proposed |
| Kotlin port, system by system | next |

## App and MusicXML format (design to be approved)

- Playback that starts at the user's click, the score being decoded system by system while it
  plays (decoding is much faster than the music).
- The MusicXML kept as a companion of the PDF; the PDF stays the page shown, the MusicXML view only
  when the user asks for it (transposition, re-layout).
- Parts and instruments as written left of the staves; automatic page turns; the metronome only as
  a fallback.
