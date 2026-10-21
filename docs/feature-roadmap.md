# FOSScore: viewer feature roadmap (PDF and MusicXML)

> **Status:** proposal for discussion between the maintainer and contributors.
> **Date:** 2026-10-16, updated 2026-10-20
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
| Page turning by tap zones, swipe, keyboard, pedal | Yes | Same | Today (PDF) · Done on a branch (MusicXML) | Shared code. The key mapping should become a setting (see 3.5). |
| Centre tap shows / hides the menus | Yes | Same | Today (PDF) | Must stay free of other meaning in both formats. |
| One page / two pages (landscape) / half-page turn (portrait) | Yes | Same | Today (PDF) · Done on a branch (MusicXML, plain half cut) | MusicXML: two-up lays out pages for half the screen width. **Half-page turn must behave exactly as for PDF** (same `PageFlow`, same steps, same keys and pedal): it is the only mode where the part being played and the part coming next are both visible at all times, so musicians rely on it. Keep its asymmetry: *next* goes half by half, *previous* goes back to the whole previous page in one step (the user prefers it). MusicXML limit: a page must hold at least 2 systems (lines) for a half to make sense, so the music size has to leave room for 2 systems per page in this mode. Improvement over PDF: cut the page at a system boundary (system positions come from `PageGeometry`) so no system is split between the two halves. |
| Zoom | Bitmap zoom | **Music size** (re-layout, the music reflows) + the same bitmap zoom | Done on a branch ("Note size" in the display options, saved per score) | In the display menu next to the modes. MusicXML zoom is a setting saved per configuration. |
| Rotation | Same page, new fit | Re-layout for the new shape | Today (PDF) · Spike | |
| Parts shown | — | Choose the parts to display; default = staves the author left visible | Spike | MuseScore exports the whole arrangement. |
| Lyrics placement | — | As in file / above / below the staff / under the system | Spike | Done in the MEI, without patching Verovio (section 7 of the study). |
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
| One library for both formats | Yes | Same list, with a format icon | Import done on a branch · icon to do | See 3.6. |
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

A real settings screen replaces the temporary controls added by recent pull requests. It opens from the **menu at the top left of the library** (hamburger ☰ and side drawer, started by the library export / import PR), which also holds the export / import of the library and, later, "About":
- language: the in-app choice (`LocaleHelper`, kept in the code) comes back here; until then the app follows the device language (the temporary menu in the library header was removed);
- keep the screen on (today a ☀ toggle in the viewer);
- page-turn keys: which keys turn pages, for keyboards and pedals that send other keys;
- defaults for new scores: display mode, metronome sound, music size.

### 3.6 Library screen (main screen)

Feedback from testing on a tablet (2026-10-20). The list should be lighter and work for one or
several scores at once:
- **No "Open" / "Edit" buttons on every row.** A tap on a row opens the score.
- **A long press enters selection mode**: a checked box appears on the left of the row, and other
  rows can be ticked. The top bar then shows **edit** (pencil) and **delete** (trash), for the
  selected scores.
- **Editing several scores at once** changes every field except the title (composer, genre, key,
  tags...).
- **A small format icon on each row**: PDF or MusicXML. For a PDF, the icon also shows when a
  MIDI file is attached (see 3.2).
- **After an import**, the title and composer read from the file are confirmed in the score's
  **details screen**, a real full screen, the same for PDF and MusicXML, never drawn over the
  library list. (Today the details screen is drawn over the list without a background since the
  pills rework, so both are mixed up.)
- The score title in the page header of a MusicXML score must stay readable without taking the
  whole width of a tablet in portrait (fixed on a branch: header lines at most 150 % of the lyrics
  size); to check on a phone.

### 3.7 Viewer menu

Agreed with a clickable mockup (2026-10-19/20); to be turned into issues:
- **One menu button** at the top left of the viewer instead of the three pills, opening a vertical
  list: Library, Display | Metronome, Playback (greyed out without MIDI or MusicXML), Links and
  repeats | Annotate, Pages | Print / export PDF, Share.
- **A tap in the top-left corner opens the menu directly** (feedback from tablet use, 2026-10-20),
  where the ☰ button sits when the bars are shown: no need to show the bars first with the centre
  tap. One tap less to reach the settings, and one less to hide everything again before playing.
  The corner zone must stay small enough not to take page turns from the left third.
- **One rule for every entry**: a full page to go somewhere else (Library, Links and repeats,
  Pages, Settings), a panel to change settings (Display, playback options, metronome settings), a
  bottom band to act while playing (Metronome, Playback, Annotate). One band at a time; the page is
  laid out **between** the top bar and the band, never under them.
- **One tempo** (♩ = N), shared by playback and the metronome. Metronome: tick sound and/or a
  coloured flash on the page border (another colour on the first beat).
- **The playback band** sits in the bottom bar, **above the page dots**, when the score has MIDI or
  MusicXML: back to the start (or to A), play / pause, **one loop button** (the usual "repeat"
  icon) and the tempo, which opens the playback options. The loop button cycles as in common
  players: the first tap marks A where the music is, the second marks B and loops between them,
  the third turns the loop off (decided 2026-10-20, done on `feature/musicxml-playback`).
  Still to try: the current bar number, and start / end jump buttons if the tests ask for them.
- **Playback options panel**: configuration (named, see 3.4), source, tempo, transposition, follow
  options, then the **parts list last, in its own scroll box** (it can be long): show (eye) and
  hear (speaker) each part separately, volume.
- **Annotate** (renamed from "Edit", which already means the score details in the library): pen,
  eraser, stamps, and a **text tool** (for example to add lyrics).
- **Links and repeats**: an ordered **play route** made of passages (from a point to a point; the
  end is not always the end of the score). For a PDF the user builds it; for MusicXML it comes from
  the score (Verovio builds the playing order from repeats, voltas, D.C., D.S., coda and fine). A
  link target keeps its vertical position, so half-page mode shows the right half.
- **Pages**: copy, move, remove pages, and insert a blank page (another way to unfold repeats in a
  PDF). The PDF file itself is never changed, only the display order.
- **Settings screen** (app-wide only, nothing that belongs to a score): a vertical list of
  sections that unfold (Display, Metronome sound, Keyboard and pedal, Playback, Language, Backup),
  never a row of chips, which overflows on a phone. Pedals are keyboards: page-turn keys are chosen
  as **pairs** (Down/Up, Right/Left, Page Down/Page Up, Space/Enter, mouse left/right click) plus
  "detect".

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
