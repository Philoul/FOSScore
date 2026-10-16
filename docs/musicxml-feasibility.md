# MusicXML support in FOSScore: feasibility study

*Status: proposal for discussion. Written with Claude Code at the request of a contributor (Philoul). Section 6 reports the results of the M0 prototype, section 7 what was tried in it afterwards, section 8 how to integrate it cleanly. No FOSScore code has been changed. The user-facing features for both PDF and MusicXML are listed in [`feature-roadmap.md`](feature-roadmap.md).*

## 1. Summary

FOSScore only reads PDF. A PDF is a fixed picture of the music: the app can zoom and annotate it, but cannot reflow it for the screen, transpose it, or play it.

[MusicXML](https://github.com/w3c/musicxml) (current version 4.0, W3C Community Group) describes the music itself: notes, measures, parts. An app that reads it can lay the score out for the screen size and orientation, change the number of measures per line, transpose it, and turn it into MIDI to hear it. MuseScore, Finale, Sibelius, Dorico and most score apps export it.

**Recommendation:**

- Use **[Verovio](https://www.verovio.org)** as the engine, compiled natively for Android. One library covers almost the whole target: MusicXML import, layout for a given page size, SVG output, **transposition**, **MIDI export** and a **timemap** (which note sounds at which time, for a playback cursor). FOSScore would only add a thin layer of Kotlin: the screen, the settings and the audio player.
- Do **not** write a MusicXML engine in FOSScore. Music engraving is a very large problem; it would make the app anything but light.
- Keep **OpenSheetMusicDisplay (OSMD)** as the fallback only, if Verovio's rendering of real-world MusicXML proves not good enough.
- Treat **Audiveris** (PDF → MusicXML recognition) as an **external desktop tool**, not as part of the app.
- For the musician, a MusicXML score must **look and behave like a PDF** (same viewer, gestures, menus and page turning), with extra options only where MusicXML allows more. The MusicXML renderer should therefore plug into the **same viewer shell** as PDF, once the viewer is split (phase 5 of the architecture roadmap). See section 8 and [`feature-roadmap.md`](feature-roadmap.md).

Everything works **100% offline**: Verovio, its fonts and the MIDI player are bundled in the APK.

## 2. Constraints

| Constraint | Consequence |
|---|---|
| Offline only | No web service, no CDN. Every library, font and sound bank ships in the APK. |
| Light app | Prefer one library that does a lot over several small ones; measure APK size at each step. |
| License AGPL-3.0 | Dependencies must be compatible: LGPL, BSD, MIT, Apache 2.0 and AGPL are fine. |
| Android versions | Upstream `minSdk` is 26; a pending branch lowers it to 23. The engine must work on both. |
| CPU architectures | arm64-v8a and x86_64 upstream (plus armeabi-v7a and x86 on the minSdk 23 branch). A native library must be built for each ABI. MuPDF already costs ~11 MB per ABI. |
| Old hardware | Test devices include a 2013 x86 tablet (Android 7.1.2). Layout time must stay acceptable there. |

## 3. Candidates

### 3.1 Verovio (recommended)

- **What it is:** a C++ engraving library from the RISM Digital Center. Native format is MEI; MusicXML, Humdrum, ABC and others are converted on the fly. Output is SVG.
- **License:** LGPL-3.0-or-later. Compatible with AGPL-3.0. The project asks for visible credit.
- **Android:** officially documented. The [Java/Android guide](https://book.verovio.org/installing-or-building-from-sources/java-and-android.html) generates Java bindings with SWIG inside the Gradle build, and the [verovio-android-demo](https://github.com/rism-digital/verovio-android-demo) app compiles `libverovio-android.so` with CMake and the NDK (API 21+). The demo shows the SVG in a WebView. The `data` folder (music fonts) is copied into the APK assets.
- **Features built in** ([toolkit options](https://book.verovio.org/toolkit-reference/toolkit-options.html), [output formats](https://book.verovio.org/toolkit-reference/output-formats.html), [MIDI](https://book.verovio.org/interactive-notation/playing-midi.html)):
  - layout: `pageWidth`, `pageHeight`, `scale`, `breaks` (`auto`, `none`, `line`, `smart`, `encoded`), `adjustPageHeight`, `breaksNoWidow`;
  - transposition: `transpose` (interval such as `M2`, or a key), `transposeToSoundingPitch` (for transposing instruments);
  - audio: `renderToMIDI()` returns a MIDI file; `midiTempoAdjustment` changes the tempo;
  - synchronisation: `renderToTimemap()`, `getElementsAtTime(ms)`, `getTimesForElement(id)`, used to highlight the notes being played and turn pages automatically;
  - fonts: Leipzig (default) and Bravura; `fontTextLiberation` because Times does not exist on Android.
- **Risks (to verify):**
  - the Java SWIG binding is reported as not extensively tested;
  - the MusicXML importer converts to MEI internally: some MusicXML details may be lost or misplaced. It needs a test corpus of real scores;
  - native library size per ABI (unknown, to measure);
  - layout speed on the old x86 tablet.

### 3.2 OpenSheetMusicDisplay (fallback)

- **What it is:** a TypeScript library that reads MusicXML directly and draws it with VexFlow. BSD-3-Clause (VexFlow: MIT).
- **Android:** only through a WebView loading local HTML/JS assets. Works offline, but every interaction goes through a JavaScript bridge, and performance depends on the device's WebView version (old on Android 7 ROMs).
- **Features:** good MusicXML fidelity, automatic layout for the container width.
- **Main drawback:** according to the [osmd-extended README](https://unpkg.com/osmd-extended@1.9.2/README.md), **playback and transposition are in "osmd-extended", reserved to sponsors**, under terms that forbid redistributing the non-minified code. That is not acceptable for an AGPL app. The community [osmd-audio-player](https://classic.yarnpkg.com/en/package/osmd-audio-player) (MIT) is no longer maintained. FOSScore would have to write transposition and MIDI generation itself.

### 3.3 Other options considered

| Option | Verdict |
|---|---|
| Write our own MusicXML renderer | No. Engraving (spacing, beams, slurs, collisions) is years of work. |
| MuseScore engine | No. GPL, C++/Qt, far too heavy to embed. |
| alphaTab | Has a Kotlin/Android target and built-in playback, but focuses on guitar tablature; MusicXML support is partial. Could be looked at if Verovio fails. |
| Audiveris inside the app | No. Desktop Java application (Java 17–21+), heavy in memory, no Android port. See phase M6. |

## 4. How it would fit in FOSScore

### 4.1 Rendering path

Verovio produces SVG pages. Three ways to show them, to compare in the prototype (M0):

1. **Through MuPDF (to verify first).** `MuPdfDoc` already opens documents with `Document.openDocument(stream, mimeType)`, and MuPDF has an SVG document handler. If MuPDF renders Verovio's SVG correctly (it uses `<symbol>`/`<use>` for glyphs), each SVG page can go through the **existing** bitmap, cache and zoom code with almost no new code and no new dependency. MuPDF's SVG support is documented as incomplete, so this must be tested.
2. **AndroidSVG** (Apache 2.0): a small Java library that draws SVG on a Canvas. One more dependency, but predictable.
3. **WebView**, as in the Verovio demo. Simplest to start, but heavy, and it fits poorly with the app's Compose gestures and annotations.

### 4.2 Code changes

- **Import:** accept `.musicxml`, `.xml` and compressed `.mxl` in the file picker (today `arrayOf("application/pdf")` in `MainActivity.kt`). The existing MD5 hashing in `SheetImporter` works unchanged.
- **Database:** the `Sheet` table needs a `format` column (PDF or MusicXML). This is a schema change: it must be done with a **real Room migration**, not with the current destructive fallback (roadmap items D3/D4), or users lose their library.
- **Viewer:** a MusicXML page source plugged into the shared viewer shell (page turning, display modes, menus), not branches added to the 1 700-line `PdfViewerScreen.kt` and not a second copy of the viewer. This needs the viewer split first (section 8).
- **Engine wrapper:** a `VerovioDoc` class next to `MuPdfDoc`, with the same rules (one thread, a mutex, explicit `close()`).
- **Settings per score:** several named configurations per score (music size, transposition, parts shown and heard, lyrics placement, tempo, instruments), stored in the database next to the sheet (section 8).

### 4.3 Annotations: the hard part

Stamps and ink strokes are stored today with positions on a fixed PDF page. With MusicXML the page changes whenever the layout changes (rotation, zoom, transposition), so a position on a page has no stable meaning.

- **First step:** no annotations on MusicXML scores (or annotations tied to a fixed layout, with a warning).
- **Target:** anchor annotations to a musical element (measure or note ID from Verovio) plus an offset, and redraw them after each layout. This is new data model work and should be designed carefully.

## 5. Roadmap

Each phase is a separate PR, usable on its own.

| Phase | Goal | Content | Size |
|---|---|---|---|
| **M0** | Prototype (throwaway branch) | Build Verovio for all ABIs; render one MusicXML page on the x86 Android 7 tablet and on a recent device; measure APK size and layout time; test the three rendering paths of 4.1; test a corpus of 10–20 real MusicXML files exported from MuseScore. **Go / no-go decision.** | M |
| **M1** | Static display | Import MusicXML/MXL; database migration with a `format` column and the new sheet details read from the file (title, composer, lyricist, key, time signature, tempo); display in the shared viewer shell with pages laid out for the screen; **choice of the parts to show** and the **hidden-note cleanup** (see 6); lyrics placement; page turning (touch, keyboard, pedal); **named configurations per score**. No annotations. | L |
| **M2** | Adaptive layout | Relayout on rotation and music size (changes `scale`, not the bitmap); the display modes of PDF (two pages, half page); options for measures per line / systems per page. | M |
| **M3** | Transposition | Transpose by interval or target key; concert / written pitch for transposing instruments. | S |
| **M4** | Listening | `renderToMIDI()` → Android's built-in MIDI player (`MediaPlayer`, no extra dependency, basic sound quality); play / pause / stop; tempo in BPM; parts heard chosen separately from parts shown; instrument per part; cursor on the current notes with the timemap; automatic page turn, with the next page rendered ahead. The playback bar is shared with PDF scores that have a MIDI file attached. Later, better sound with a small SoundFont synthesizer (for example TinySoundFont, MIT) if APK size allows. | M |
| **M5** | Annotations on MusicXML | Annotations anchored to measures or notes (see 4.3). | L |
| **M6** | PDF → MusicXML | Document a desktop workflow: convert the PDF with **Audiveris** (AGPL, Java), fix recognition errors in its editor or in MuseScore, then import the MusicXML into FOSScore, linked to the original PDF. On-device recognition is not realistic today. | S (docs) |

**Order with the architecture roadmap** (`docs/architecture-roadmap.md` on its own branch): M1 depends on proper Room migrations (phase 2 there). M0 does not depend on anything and can start now.

## 6. M0 prototype results (2026-10-15)

A throwaway app module (local branch `spike/m0-musicxml`, separate application ID, never merged) built Verovio 6.3.0 natively and measured it on the slowest target device.

**Setup:** Galaxy Tab 3 10.1 (Intel Atom x86, 2013), LineageOS 7.1.2 (API 25). Landscape viewport 1280 × 688 px, Verovio scale 61 (staff height about 7 mm). 19 public sample files (W3C examples, OSMD test files) plus 4 real songs exported from MuseScore as `.mxl`.

### Build

- Verovio compiles with NDK r27 and CMake 3.22 for arm64-v8a, armeabi-v7a, x86_64 and x86, with `minSdk 23`.
- **No SWIG needed:** a hand-written JNI bridge of about 120 lines over `vrv::Toolkit` is enough (load, options, page count, SVG, MIDI, timemap, log).
- First build: about 30 minutes for the 4 ABIs on a 12-thread PC. Later builds only recompile what changed (seconds).

### Size (per ABI, arm64-v8a)

| Library | Uncompressed | Compressed (gzip -9) |
|---|---|---|
| `libverovio.so`, default options | 15.9 MB | 5.5 MB |
| `libverovio.so`, `NO_HUMDRUM_SUPPORT` + `MinSizeRel` | **8.4 MB** | **2.4 MB** |
| `libc++_shared.so` (needed with two .so files) | 1.3 MB | 0.4 MB |
| For comparison: `libmupdf_java.so` (already in FOSScore) | 10.8 MB | 5.5 MB |
| Music fonts (Leipzig + Bravura, assets, shared by all ABIs) | 1.6 MB | — |

Humdrum is a format FOSScore does not need; disabling it halves the library and the build time.

### Speed on the 2013 tablet

| Step | Small files | Classical pieces (2–7 pages) | Real songs (15–24 pages) | Full 11-part arrangement |
|---|---|---|---|---|
| Parse the file (Kotlin DOM) | < 0.1 s | 0.1–0.7 s | 0.6–1.2 s | 2.4 s |
| Verovio load and layout | 0.2–0.4 s | 0.4–2.4 s | 2.2–4.7 s | 10.6 s |
| One page to SVG | 20–100 ms | 80–240 ms | 60–270 ms | 110 ms |
| SVG to bitmap, MuPDF | 50–110 ms | 150–320 ms | 100–230 ms | 190 ms |
| SVG to bitmap, AndroidSVG | 60–490 ms | 400–950 ms | 240–850 ms | 550 ms |
| MIDI file | 0.1–0.2 s | 0.2–1.2 s | 0.9–1.9 s | 4.5 s |

- **Page turns are fast enough:** 0.1–0.6 s.
- **Opening a large file is slow** on this device. It needs a progress indicator, and the laid-out result should be kept while the score stays open.

All 23 files opened. The 4 real songs gave 6 to 38 landscape pages.

### Rendering path

- **MuPDF (recommended).** It renders Verovio's SVG once three small text changes are applied to it (`forMuPdf()` in the spike):
  - give the nested `<svg class="definition-scale">` a 100% width and height (otherwise nothing is drawn);
  - turn the CSS rule `stroke: currentColor` into attributes (otherwise staff lines, stems and bar lines are missing);
  - drop the `<style>` blocks.

  It is 2–3 times faster than AndroidSVG. Its Times-compatible text font matches Verovio's layout metrics, so lyrics are spaced correctly. No new dependency, and the existing bitmap, zoom and cache code can be reused.
- **AndroidSVG.** Works without changes, but is slower, and lyrics overlap because Android's serif font has different metrics.
- **WebView.** Kept as a manual option in the spike; not needed.

### Findings that change the roadmap

1. **Part selection is needed from M1, not M3.** MuseScore exports contain the whole arrangement. One real song has 11 parts (voice, piano, guitar, strings, drums...) where the musician reads only the piano: 98 portrait pages instead of about 30.

   Removing `<score-part>`/`<part>` elements before loading works well (`MusicXml.kt` in the spike). It needs no Verovio feature.

   The default selection should follow the file: MuseScore writes `<staff-details print-object="no">` for the staves the author hid. Parts whose staves are all hidden start unselected, so each real song opened directly with the part the author reads.
2. **MuseScore's hidden notes must be cleaned up before loading (needed in M1).** In all 4 real songs, the vocal line is kept only for its lyrics: its notes have no notehead and no stem (`<notehead>none</notehead>`, `<stem>none</stem>`), and some notes and rests have `print-object="no"`. Verovio still drew their beams, ledger lines, ties, accidentals and augmentation dots, so the page looked dirty, which blocked real use.

   The fix is a DOM pass before loading (`hideInvisibleNotes()` in the spike): mark such notes `print-object="no"` and remove their `<beam>`, `<tie>`, `<tied>`, `<slur>` and `<accidental>`. Verovio then draws nothing for them but their lyrics. Durations, dots and tuplet ratios are kept, so the alignment does not change. After this pass, the musician found the result clean and close to the MuseScore PDF.
3. **Use `densityDpi`, not `xdpi`, to size the staff.** The tablet's ROM reports a wrong `xdpi`, which gave a 2.5× too large default zoom.
4. **The SMuFL text font is not available to MuPDF.** The note symbol in tempo marks (♩ = 96) is missing. This is to be solved by mapping the glyph to the music font, or by drawing tempo marks without it.
5. **Some files have import problems.** One OSMD test file (The Entertainer) produces Verovio import errors (beams inside chords, unmatched ties) and shows some wrong beams.
6. **Lyrics marked `placement="above"` are drawn below the staff.** Minor: the MuseScore PDF shows them above the piano staff.
7. **Verovio's log is not returned on Android** (it goes to logcat). The import warnings must be read from logcat until this is wired.

### Not measured yet

- Memory use (two toolkits are loaded when the parts heard differ from the parts shown).
- Transposition correctness, checked note by note.
- A recent device (all measurements are on the 2013 tablet).

### Re-layout after zoom, rotation or part change

The first manual test felt slow: about 5 s between a tap and the new page. A zoom cost, on the tablet: layout 2.0 s, SVG 0.7 s, MuPDF 0.6 s, plus 1.4 s to re-parse the file. Four changes made zoom feel immediate in the second manual test:
- `redoLayout({"resetCache": false})` when only the page size or the scale changes: Verovio keeps the measure widths computed at load time and only breaks lines and pages again (the output was checked to be identical, apart from the order of glyph definitions);
- part selection detaches and re-attaches DOM nodes instead of parsing the file again;
- `svgFormatRaw: true` halves the SVG size, so MuPDF parses it faster (the bitmap is pixel-identical);
- repeated taps are debounced (300 ms), so only the last one is laid out.

Transposition still reloads the file, because Verovio applies it at load time.

### Manual test by a musician

On the tablet, with their own songs: rendering clean after the hidden-note fix, re-layout on rotation and zoom fast, transposition and MIDI playback working, and "really usable". MuPDF fonts clearly better than AndroidSVG. The WebView path showed a blank page (not investigated: that path is not needed).

### Verdict

**Go**, with these choices:
- Verovio built without Humdrum and with `MinSizeRel`;
- one shared library if possible, to avoid `libc++_shared.so`;
- MuPDF rasterisation of the adapted SVG;
- part selection in M1, defaulting to the parts the author left visible;
- the hidden-note cleanup in M1.

The main cost is the APK size: about +10 MB per ABI. That argues for publishing per-ABI APKs or an App Bundle.

## 7. Prototype log after M0 (throwaway branch)

After the go decision, the prototype (local branch `spike/m0-musicxml`, application ID `org.fosscore.spike.musicxml`, installed next to FOSScore) was used to try the features a musician asked for, on their own MuseScore songs. Each round was tested on the tablet by that musician. The code is not meant to be merged: it is one screen, settings are kept in `SharedPreferences` and there are no tests. What is worth keeping is listed in section 8.

| Round | Tried | Result and lessons |
|---|---|---|
| 1 | Hidden-note cleanup, parts hidden by the author unselected at opening, faster re-layout | See section 6. "Really usable." |
| 2 | **Playback cursor and automatic page turn**: the timemap gives, for each onset, its time and the notes that start; a translucent bar and dots mark them on the bitmap, and the page turns when the notes are no longer on it. The next page is rendered ahead while playing. | Works and was the most appreciated feature. Score positions in quarter notes (`qstamp`) link audio and display, so the sound can come from other parts than the ones shown. |
| 2 | **Tempo factor** (`midiTempoAdjustment`) | Verovio applies it to both the MIDI and the timemap, so the cursor stays in sync. |
| 2 | **Parts heard separately from parts shown**, **instrument per part** (`<midi-program>` rewritten before loading) | A second toolkit loads the parts to hear. Musicians use it to play their part with the others as accompaniment, which is why several configurations per score are needed. Drums (MIDI channel 10) keep their kit. |
| 2 | **Lyrics placement**: as in file, above, below the staff, under the system | Verovio's MusicXML import ignored `<lyric placement>`: one-line patch. "Under the system" moves the hidden vocal voice to the lowest staff of its part (its notes are invisible, so only the lyrics move). |
| 2 | **Tempo kept when the voice part is hidden** | MuseScore writes the tempo mark in the first part only, often the hidden voice: without it, playback fell back to 120. The tempo directions are copied into the first part shown, at the same measure and beat. |
| 2 | **Settings saved per score** | Restored when the file is opened again. |
| 3 | **Several named configurations per score** (switch, copy, rename, delete) | Asked for by the musician: one to practise alone, one to rehearse with the band. |
| 3 | **Tempo in BPM** (♩ = 96, steps of 2, reset) instead of a percentage | A percentage was not intuitive. |
| 3 | **Tempo note symbol** (♩) | Verovio writes it as text in the SMuFL font, which MuPDF does not have. It is now drawn as a path taken from Verovio's font data (`SmuflGlyphs.kt`). |
| 3 | **Wider lyrics** (`lyricWordSpace` 2.5 instead of 1.2) | Words touched in narrow measures. |
| 3 | **Bug: two players at once** after quick tempo and part changes, impossible to stop | A playback start being prepared is now cancelled by the next one, and tempo changes are debounced. Keep this in mind for the real playback controller. |
| 4 | **Title size** | Verovio's import ignored the `<credit-words>` font size: second patch, the size relative to the file's default word font. |

**Verovio patches** (`spike-musicxml/verovio-patches/musicxml-import.patch` on the spike branch): lyric placement and credit font size, both in `iomusxml.cpp`. They should be proposed to the Verovio project rather than kept as a fork.

## 8. Integration plan

The prototype stays a test bench for the user interface. The integration is **new, clean code**, in small pull requests that the maintainer can review one by one, starting only once the maintainer agrees (question 1).

**Prerequisites from the architecture roadmap:**
- phase 2 (exported schema, migration tests, no destructive fallback): MusicXML adds columns and a table;
- phase 5 (viewer split into a shell, a page flow and a `ViewerViewModel`): the MusicXML page source plugs into that shell, so both formats share page turning, display modes and menus.

**Code layout:**
- a Gradle module `musicxml` holding Verovio (Git submodule at a pinned release, built without Humdrum, `MinSizeRel`), the JNI bridge, the engine (one thread, explicit `close()`, like `MuPdfDoc`) and the MusicXML preparation (hidden notes, parts, tempo copy, lyrics, instruments). The app sees a small interface: load, lay out, render a page, play;
- a **page source** interface in the viewer, implemented by MuPDF for PDF and by the `musicxml` module for MusicXML;
- a **playback controller** and a **metronome** that do not depend on the format (see `feature-roadmap.md`, section 4).

**Data:**
- `sheets`: a `format` column and the extra details read from MusicXML files;
- a new table of **named configurations per sheet** (name, last used, and the settings of `feature-roadmap.md` section 3.4).

**What to reuse from the prototype** (copied, reviewed and tested, not merged): the JNI bridge, `MusicXml.kt` (DOM preparation with undo), `SmuflGlyphs.kt`, `PageGeometry.kt` (note and system positions for the cursor), the `Playback` time maps, the MuPDF SVG fixes in `Rasterizers.forMuPdf()`, and the measurements of section 6.

**Distribution:** about +10 MB per ABI, so per-ABI APKs or an App Bundle (question 3). Verovio is LGPL-3.0, compatible with FOSScore's AGPL-3.0.

## 9. Open questions for the maintainer

1. Is MusicXML support wanted in FOSScore, or should FOSScore stay a PDF reader?
2. Is a native C++ dependency (Verovio, built with the NDK and CMake) acceptable in the build? The prototype showed SWIG is not needed, but the first build takes about 30 minutes.
3. What is the APK size budget? Should the project publish one APK per ABI (or an App Bundle) to keep downloads small?
4. Should MusicXML scores live in the same library as PDFs (one list, a format icon), or in a separate section?

## 10. Sources

- MusicXML 4.0: <https://github.com/w3c/musicxml>
- Verovio, Java and Android: <https://book.verovio.org/installing-or-building-from-sources/java-and-android.html>
- Verovio Android demo: <https://github.com/rism-digital/verovio-android-demo>
- Verovio toolkit options: <https://book.verovio.org/toolkit-reference/toolkit-options.html>
- Verovio toolkit methods: <https://book.verovio.org/toolkit-reference/toolkit-methods.html>
- Verovio output formats and MIDI: <https://book.verovio.org/toolkit-reference/output-formats.html>, <https://book.verovio.org/interactive-notation/playing-midi.html>
- Verovio licensing: <https://book.verovio.org/introduction/licensing.html>
- osmd-extended README (sponsor features): <https://unpkg.com/osmd-extended@1.9.2/README.md>
- Audiveris: <https://github.com/Audiveris/audiveris>
- MIDI playback with Android MediaPlayer (quality note): <https://www.seescore.co.uk/?p=517>
