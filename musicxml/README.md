# `musicxml` module

MusicXML support for FOSScore. The module reads MusicXML files, lays them out for the screen with
[Verovio](https://www.verovio.org), draws the pages with MuPDF (the engine FOSScore already ships
for PDF) and prepares playback (MIDI and a cursor).

The module does not depend on the app. It does not include any screen, database or settings code:
the app decides how scores are stored, shown and played.

## What it does

| Feature | Where |
|---|---|
| Read `.musicxml`, `.xml` and compressed `.mxl` files, and recognize MusicXML among other files | `MusicXmlFiles` |
| Read the parts (name, instrument, hidden by the author) and the score details (title, composer, lyricist, arranger, key, time signature, tempo, number of measures) | `MusicXmlScore.parts`, `MusicXmlScore.info` |
| Clean up the notes the author hid (MuseScore voice lines kept only for their lyrics) | `MusicXmlScore.parse` |
| Show only some parts, keeping the tempo marks of the hidden ones | `ScoreFilter.parts` |
| Place lyrics as in the file, above or below the staff, or under the system | `ScoreFilter.lyrics` |
| Change the instrument of a part (General MIDI program) | `ScoreFilter.programs`, `SoundSpec.programs` |
| Lay out pages for a viewport, a music size and a transposition | `ScoreEngine.layout(LayoutSpec)` |
| Draw a page as a bitmap, with the note positions for a cursor | `ScoreEngine.renderPage` → `RenderedPage` |
| MIDI file, tempo factor, parts heard different from parts shown, cursor positions | `ScoreEngine.playback(SoundSpec)` → `Playback` |

## Using it

```kotlin
val engine = ScoreEngine(context)            // one per open score
val score = engine.open(bytes)               // parts and details
val scale = LayoutSpec.defaultScale(viewW, viewH, displayMetrics.densityDpi)
val pages = engine.layout(LayoutSpec(width = viewW, height = viewH, scale = scale))
val page = engine.renderPage(1, viewW, viewH)  // page.bitmap, page.geometry
val playback = engine.playback(SoundSpec(tempo = 0.8))  // playback.midi, onsets, quartersAt(ms)
engine.close()
```

All `ScoreEngine` functions are `suspend` and safe to call from any coroutine: every Verovio call
runs on the engine's own thread. Changing the viewport or the music size is fast (the measure widths
are kept). Changing the transposition, the parts shown or the lyrics placement loads the score again.

**Cursor during playback.** `Playback.quartersAt(ms)` turns the player position into a score
position, `onsetIndexAt(quarters)` gives the notes that started last, `ScoreEngine.pageOf(noteId)`
their page, and `RenderedPage.geometry.head(noteId)` where they are on the bitmap.

## Verovio

Verovio (LGPL-3.0, compatible with FOSScore's AGPL-3.0) is a Git submodule in `verovio/`, pinned to
release **6.3.0**, and built **without any change** by `src/main/cpp/CMakeLists.txt` (without the
Humdrum and DARMS input formats, which FOSScore does not need). The JNI bridge is hand-written
(`verovio_jni.cpp`, no SWIG).

Two things Verovio's MusicXML import drops are put back without patching it: the score is loaded
once, exported as MEI (Verovio's own format), given the lyrics placement and the size of the title
lines, then loaded again (`MeiFixes`). Fixing these two gaps in Verovio itself would make this step
unnecessary.

Only the music fonts Leipzig (default) and Bravura (fallback) and the Liberation text font are
shipped, copied from `verovio/data` into the assets at build time, and copied to the app's files
on first use. All three are under the SIL Open Font License 1.1 (see `verovio/fonts/README.md`).

**Build:**
- `git submodule update --init` once after cloning;
- the Android NDK 27.2.12479018 and CMake 3.22.1 (Android Studio's SDK Manager);
- the first native build takes 15 to 30 minutes depending on the computer; later builds reuse
  `musicxml/.cxx`.

**Updating Verovio:** check out the new release tag in `verovio/`, commit the new submodule
pointer, change `DATA_VERSION` in `Verovio.kt`, and run the tests below.

**Size** (release build): about 10 MB of native code per ABI (arm64: Verovio 8.9 MB, C++ runtime
1.3 MB) and 1.1 MB of fonts. The module builds the four Android ABIs; the app keeps only the ones
it ships (its `abiFilters`).

## Tests

- `./gradlew :musicxml:testDebugUnitTest`: file reading, part selection, tempo copy, lyrics, MEI and
  SVG fixes, page geometry, playback maps, Verovio options (plain JVM, no device).
- `./gradlew :musicxml:connectedDebugAndroidTest`: the whole chain on a device or emulator
  (Verovio, MEI round trip, MuPDF, MIDI). It saves the first page as `page1.png` in the test
  package's external files folder.

The test score `au-clair-de-la-lune.musicxml` is a traditional tune arranged for these tests and
released as CC0.

## Not here yet

- **Annotations** on MusicXML scores. The page layout changes with the screen, so ink and stamps
  must be anchored to measures or notes instead of page positions.
- **Converting to MEI once at import**, to save the second load on each opening.
- The two-page and half-page display modes; options for measures per line.

See `docs/musicxml-feasibility.md` (prototype results and integration plan) and
`docs/feature-roadmap.md`, proposed on the branch `docs/musicxml-feasibility`.
