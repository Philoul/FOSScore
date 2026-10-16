# FOSScore: viewer feature roadmap (PDF and MusicXML)

> **Status:** proposal for discussion between the maintainer and contributors.
> **Date:** 2026-10-16
> **Related:** [`musicxml-feasibility.md`](musicxml-feasibility.md) (engine choice, prototype
> results, technical integration plan) and `docs/architecture-roadmap.md` (code health, on its own
> branch).

This document lists the user-facing features the viewer should offer, for PDF and MusicXML scores,
and what the user interface must allow for **now** so that these features fit later without a
redesign. It is a to-do list, not a commitment: each line becomes an issue or a PR when someone
picks it up.

---

## 1. Principle: one viewer, two formats

For the musician, opening a PDF or a MusicXML score should feel the same: same library, same
viewer, same gestures, same menus, same page-turning. MusicXML only **adds** options where the
format allows more (reflowing the music, transposing, playing it).

Concretely:
- **One viewer shell** (toolbars, page turning, display modes, keyboard and pedal, per-score
  settings, playback bar, metronome), and **two page sources** plugged into it: a PDF source
  (MuPDF, fixed pages) and a MusicXML source (Verovio, pages laid out for the screen).
- **No format-specific menu** unless the option really exists for one format only. An option that
  does not apply is hidden, not greyed out without explanation.
- **Same settings model** for both: per-score configurations stored in the database (see 4).

## 2. What the PDF viewer does today (baseline)

| Area | Behaviour |
|---|---|
| Page turning | Tap zones: left third = previous, right third = next. Horizontal swipe when not zoomed. Keyboard and pedal keys (PR #7): Page Up/Down, arrows, Space / Shift+Space, Enter. |
| Toolbars | A tap in the centre third shows or hides the navigation bar. |
| Display modes | One page; two pages side by side in landscape; half-page turn in portrait (the lower half of the next page replaces the top half first). |
| Zoom | Pinch zoom and pan on the page bitmap. |
| Annotations | Ink strokes and stamps, stored per page; undo. |
| Comfort | Theme, dim slider, negative (inverted) mode, keep screen on (branch `feature/keep-screen-on`, issue #10), last page remembered. |
| Sheet details | Title (from the file name), composer, genre, key, tags. |

## 3. Feature list

Status: **Today** = exists for PDF · **Spike** = tried in the MusicXML prototype (see
`musicxml-feasibility.md`, section 7) · **To do** = not started.

### 3.1 Reading

| Feature | PDF | MusicXML | Status | Notes |
|---|---|---|---|---|
| Page turning by tap zones, swipe, keyboard, pedal | Yes | Same | Today (PDF) · To do (MusicXML) | Shared code. The key mapping should become a setting (see 3.5). |
| Centre tap shows / hides the menus | Yes | Same | Today (PDF) | Must stay free of other meaning in both formats. |
| One page / two pages (landscape) / half-page turn (portrait) | Yes | Same | Today (PDF) · To do (MusicXML) | MusicXML: two-up lays out pages for half the screen width; half-page turn uses the same rendered pages as PDF. |
| Zoom | Bitmap zoom | **Music size** (re-layout, the music reflows) + the same bitmap zoom | Spike | In the display menu next to the modes. MusicXML zoom is a setting saved per configuration. |
| Rotation | Same page, new fit | Re-layout for the new shape | Today (PDF) · Spike | |
| Parts shown | — | Choose the parts to display; default = staves the author left visible | Spike | MuseScore exports the whole arrangement. |
| Lyrics placement | — | As in file / above / below the staff / under the system | Spike | Needs a Verovio patch (section 7 of the study). |
| Transposition | — | ♭ / ♯ by semitone, later by target key | Spike | |
| Dark / negative / dim | Yes | Same | Today (PDF) | MusicXML can also draw in colours directly (later). |
| Annotations | Yes | Anchored to measures or notes | Today (PDF) · To do (MusicXML, milestone M5) | Positions on a reflowed page have no stable meaning. |

### 3.2 Listening and practising

| Feature | PDF | MusicXML | Status | Notes |
|---|---|---|---|---|
| Play the score | Only if the user attaches a **MIDI file** to the score | Generated from the score | Spike (MusicXML) · To do (PDF) | Same playback bar for both. |
| Cursor on the notes being played | — (no note positions in a PDF) | Yes | Spike | |
| Automatic page turn while playing | Possible later with **page timings** (the user marks when each page starts) | Yes, synchronised with the music | Spike (MusicXML) · To do (PDF) | |
| Tempo | Shown as ♩ = N; the user sets the written tempo in the sheet details | Read from the file; ♩ = N, adjustable, reset | Spike | Tempo is shown in beats per minute, never as a percentage. |
| Parts heard, separately from parts shown | — | Mute / unmute each part | Spike | Lets a musician play their part with the others as accompaniment. |
| Instrument per part | — | General MIDI instrument per part | Spike | |
| **Metronome**, audible and / or visual | Uses the tempo and time signature from the sheet details | Follows the score: tempo and time signature changes from Verovio's timemap | To do | Count-in before playing; visual beat (flash or bar) usable in silence on stage. |
| Better sound | — | Small SoundFont synthesizer instead of Android's basic MIDI player | To do | Only if the APK size allows. |

### 3.3 Library and score details

| Feature | PDF | MusicXML | Status | Notes |
|---|---|---|---|---|
| One library for both formats | Yes | Same list, with a format icon | To do | |
| Details filled in automatically | Title from the file name | **Title, subtitle, composer, lyricist, arranger, key, time signature, tempo, parts**, read from the file | To do | The user can still edit them. |
| New detail fields, for both formats | — | — | To do | Lyricist / performer, time signature, tempo (BPM), approximate duration. Needs a database migration. |
| Link a PDF and a MusicXML of the same piece | — | — | To do | For example after converting the PDF with Audiveris (milestone M6). |
| Attach a MIDI file to a PDF | — | — | To do | See 3.2. |

### 3.4 Per-score configurations

Every setting that changes how a score is read or heard is saved **with the score**, and a score
can have **several named configurations** (for example "Practice alone" and "Band rehearsal",
or one per key). Tried in the spike with all the settings below.

| Setting | PDF | MusicXML |
|---|---|---|
| Display mode (one page, two pages, half page) | Yes | Yes |
| Zoom / music size | — (bitmap zoom is transient) | Yes |
| Transposition | — | Yes |
| Parts shown | — | Yes |
| Lyrics placement | — | Yes |
| Tempo | Yes (metronome, attached MIDI) | Yes |
| Parts heard, instruments | — | Yes |
| Metronome on / off, sound / visual | Yes | Yes |

The menu: choose a configuration, copy the current one, rename it, delete it. The last used
configuration opens by default.

### 3.5 Settings screen (app-wide)

A real settings screen replaces the temporary controls added by recent pull requests:
- language (today a dropdown at the top of the library);
- keep the screen on (today a ☀ toggle in the viewer);
- page-turn keys: which keys turn pages, for keyboards and pedals that send other keys;
- defaults for new scores: display mode, metronome sound, music size.

## 4. What the user interface and code must allow for now

These are not features, but choices to make early so the features above fit without a redesign.

1. **Split the viewer into a shell and page sources** before adding MusicXML to it. This is phase
   5 of the architecture roadmap (extract toolbars, gestures, page flow and a `ViewerViewModel`).
   The MusicXML page source then plugs into the same shell instead of a second copy of the viewer.
2. **A page source interface** that covers both formats: page count, render a page for a size, and,
   for MusicXML only, re-layout for a new size, music size, transposition or part selection.
   `PageFlow` (modes, half page, two-up) stays format-independent.
3. **Keep the centre tap for showing and hiding the menus**, and the left and right thirds for
   page turning, in every mode and both formats. New controls (playback bar, metronome) go in the
   menus that the centre tap shows, never in a new gesture.
4. **Per-score settings in the database**, keyed by the sheet, not in `SharedPreferences` by file
   name (the spike did the latter only to test). One table of named configurations per sheet.
   This needs the Room migration work of phase 2 of the architecture roadmap first.
5. **A playback bar and a metronome independent of the format.** The playback bar takes a "time
   source" (generated MIDI for MusicXML, an attached MIDI file for PDF); the metronome takes a
   tempo and time-signature map (one value for a PDF, the timemap for MusicXML).
6. **Room for more sheet details**: plan the extra columns (lyricist, time signature, tempo,
   duration, format, linked file) in one migration rather than one per feature.
7. **Keep the screen awake while playing**, whatever the "keep screen on" setting.

## 5. Suggested order

```
Architecture roadmap phase 2 (safe migrations)   Phase 5 (viewer split)
          │                                              │
          ├── sheet details migration (3.3, 4.6)         │
          ├── per-score configurations table (3.4, 4.4)  │
          │                                              │
          └──────────────┬───────────────────────────────┘
                         │
   MusicXML M1: import, display in the shared shell, parts, page turning, configurations
   MusicXML M2: music size and rotation re-layout, display modes
   MusicXML M3: transposition
   MusicXML M4: playback bar, cursor, automatic page turn, tempo, parts heard, instruments
   Metronome (both formats) · MIDI file attached to a PDF · settings screen
   MusicXML M5: annotations · M6: Audiveris workflow (docs)
```

Items that do not depend on MusicXML (settings screen, metronome for PDF, new sheet details,
page-turn key setting) can start at any time after the architecture steps they need.
