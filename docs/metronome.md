# Metronome: requirements and implementation proposal

Status: proposal (2026-10-22), to discuss with the maintainer. It details the "Metronome" line of
the roadmap (`feature-roadmap.md`, 3.2 and 3.7). A first version is built on the branch
`feature/metronome` (2026-10-24), see section 6.

## 1. Requirements

From a comparison of common metronome apps:

1. **Tap tempo**: a TAP button; the user taps at the speed they want and the app shows the tempo
   in beats per minute. The value is a moving average over the last 3 or 4 taps, so one uneven
   tap does not make the tempo jump.
2. **Tempo slider** from a minimum (between 20 and 30) to a maximum (about 300), next to the − / +
   buttons and the typed value that playback already has.
3. **Time signature of the score**: C (= 4/4), numerator 2 to 20, denominator 2 (half notes),
   4 (quarter notes) or 8 (eighth notes). A new score detail for PDF scores; read from the file
   for MusicXML scores.
4. **Mode**: audio, visual, or both.
5. **Start / pause** button.
6. **Accent on the first beat** of the bar (follows the time signature).
7. **Subdivision clicks**, with another sound: for example one tick on every eighth note when the
   beat is a quarter note, or three ticks per beat in 6/8 where the beat is a dotted quarter.
8. **Sounds**: classic mechanical click, hi-hat, wood block, digital.
9. **Count-in** before the piece starts.
10. **Automatic page turn for PDF scores**: when on, the user gives the number of bars of each
    page (it usually differs from page to page), so the metronome knows when to turn.
11. **MusicXML**: the metronome follows the score, with its time signature and tempo changes
    during the piece, or not, to practise at a fixed slower speed.
12. **Optional**: the Italian name of the tempo (Adagio, Andante, Allegro, Presto...).
13. **Turn ahead**: an option to turn the page 1 or 2 bars before the music gets there, with the
    metronome and with playback. A musician always reads a little ahead; in half page, in portrait
    above all, the next part of the score is then on screen, drawn, before it is played.

## 2. Proposal: what the user sees

The menu rule of the roadmap stays: a **band** to act while playing, a **panel** for the settings.

### 2.1 The metronome band (PDF scores, or MusicXML when not playing)

"Metronome" in the viewer menu shows the band in the bottom bar, where the playback band sits:

```
 ┌──────────────────────────────────────────────────────────────────┐
 │   ▶    ● ○ ○ ○     −  ♩ = 92  +  Andante     TAP     ⚙          │
 └──────────────────────────────────────────────────────────────────┘
   start  beats of   tempo − / + (repeat while   tap     metronome
   pause  the bar    held; tap the value: type)  tempo   settings
```

- **Beat dots**: one per beat of the bar, the current one lit, the first one bigger (accent).
  They are the visual metronome inside the band; the page border flash is the visual mode for
  when the band is hidden.
- **− / +** change the tempo by 1, repeating while held (as in the playback options of #32),
  while stopped or playing; a tap on the value types it.
- **TAP** sets the tempo while stopped or playing.
- With **automatic page turn** on, the band also shows the bar count of the page (`bar 3 / 8`).

### 2.2 With playback (MusicXML today, PDF with a MIDI file later)

No second band. The playback band gets one more button, **♩ click** (on / off): the metronome
clicks along with the music, and the count-in plays before the music starts. Its settings are
the same panel.

### 2.3 The metronome panel (⚙)

```
 Metronome                                              ✕
 ─────────────────────────────────────────────────────────
 Tempo        −   ♩ = 92   +            Andante
              20 ━━━━━━━●━━━━━━━━━━━━━━━━━━━━ 300     TAP
 Time signature     [ 4 ] / [ 4 ]     (C, 2–20 / 2, 4, 8)
 ─────────────────────────────────────────────────────────
 Mode               (•) Sound   ( ) Visual   ( ) Both
 Sound              Click ▾   (Click, Hi-hat, Wood block, Digital)
 Accent the first beat                                [✓]
 Subdivision        (•) None  ( ) 2  ( ) 3  ( ) 4
 Count-in           (•) None  ( ) 1 bar  ( ) 2 bars
 ─────────────────────────────────────────────────────────
 Follow the score (MusicXML)                          [✓]
 Turn pages automatically                             [ ]
     Bars per page (PDF)                         Set… ›
```

- The **Tempo** block (− / +, typed value, slider, TAP, Italian name) is **one shared component**,
  used in this panel and in the playback options: one tempo for the score (roadmap 3.7). The
  playback options of #32 then get the slider and TAP too.
- **Time signature**: for a PDF it is saved as a score detail; for MusicXML it shows the first one
  read from the file, read only, with "changes during the piece" when it does.
- In compound time (6/8, 9/8, 12/8) the beat is a dotted quarter: the tempo shows ♩. = N, and the
  subdivision is 3 by default.

### 2.4 Bars per page (PDF)

Most PDF scores have a different number of bars on each page (one staff less on the first page
because of the title, more or fewer instruments...). "Set…" in the panel closes it and shows a
small band at the bottom, so the page stays visible above it:

```
 ┌──────────────────────────────────────────────────────────────────┐
 │  ◀  Page 1 / 4  ▶     −  6 bars  +     bars 1–6     Copy to all  ✓ │
 └──────────────────────────────────────────────────────────────────┘
```

- **◀ / ▶ change the page shown behind the band too**, so the user checks the number of the last
  bar of the page against the score (`bars 1–6`, then `bars 7–14` on page 2...).
- − / + set the bars of this page; **Copy to all** gives every following page the same number, as
  a starting point to correct page by page.
- ✓ closes the band. The counts are saved with the score.

## 3. Proposal: how it works

### 3.1 Timing

- The clicks are written as sound samples into an `AudioTrack` (streaming), at exact sample
  positions: the timing comes from the audio clock, never from timers that drift. Works on
  Android 6.
- The beat dots and the page border flash follow the audio clock (`playbackHeadPosition`), with
  the output latency taken off, so what is seen matches what is heard.
- A tempo or time signature change takes effect at the next beat, without a gap (as playback does
  since #32).

### 3.2 Sounds

- Made in code (a few lines each, no sound files, so no licence to check): click = short noise
  burst, hi-hat = brighter noise, wood block = short decaying tone, digital = short square beep.
  The accent is higher and louder; subdivisions are softer.
- With MusicXML playback, the click goes **into the MIDI file** on the percussion channel
  (General MIDI: metronome click / bell, closed hi-hat, wood blocks, square click), so it can never
  drift from the music. The sounds are close to the standalone ones.

### 3.3 Tap tempo

- Average of the intervals between the last 4 taps (3 intervals); a tap more than 2 s after the
  previous one starts a new measure. Rounded to a whole BPM and kept in the slider's range.

### 3.4 MusicXML

- The `musicxml` module gives a **meter map**: for each bar, its start in quarter notes, its time
  signature and the tempo in force (from the `<time>` and tempo marks, the same way it already
  reads the first ones). The metronome walks it, so time signature and tempo changes are followed.
- **Follow the score** (on by default): the metronome walks the meter map, so time signature and
  tempo changes are followed, and it knows the bars of each page, so it can turn the pages as
  playback does. The user's tempo stays one factor on the written tempo (as in #32): to practise
  slowly, the user lowers it, and a score that speeds up still speeds up, at the user's pace.
- **Off**: a plain metronome, at the tempo and time signature set in the panel, to practise one
  passage at a fixed speed without the changes of the score. No automatic page turn then.

### 3.5 Automatic page turn

- PDF: from the bars of each page (2.4). When the last bar of a page ends, the page turns; in
  half-page mode the half turns at half the bars of the page. MusicXML: from the layout, when
  following the score.
- Repeats are not followed: that waits for "Links and repeats", which will give the real order of
  the bars.
- **Turn ahead** (0, 1 or 2 bars, the same for every score, for the metronome and for playback):
  on whole pages, the page turns as soon as the bar looked at is on the next page; in half page,
  the upper half shows the next page as soon as the bar looked at is in the lower half, but the
  whole next page waits for the music itself, since the end of the page is still being played.

### 3.6 Italian names

One fixed table, shown as a hint and never stored:

| BPM | Name |
|---|---|
| < 40 | Grave |
| 40–59 | Largo |
| 60–65 | Larghetto |
| 66–75 | Adagio |
| 76–107 | Andante |
| 108–119 | Moderato |
| 120–155 | Allegro |
| 156–175 | Vivace |
| 176–199 | Presto |
| ≥ 200 | Prestissimo |

### 3.7 What is saved

- **Per score**: tempo, time signature (PDF), subdivision, count-in, follow the score (MusicXML),
  automatic page turn and the bars of each page (PDF), the "click" toggle of playback.
- **App-wide** (settings screen, roadmap 3.5): mode and sound, used by every score.

## 4. Suggested pull requests

Small and independent, in this order:

1. **Metronome for PDF scores**: engine, sounds, band, panel, tap tempo, Italian names, count-in;
   time signature and tempo kept in the score's settings for now. Unit tests for the tap average,
   the beat timing and the names.
2. **The shared tempo component** in the playback options (slider, TAP), once #32 is merged.
3. **Time signature and tempo as score details** (database migration), in the details screen.
4. **MusicXML**: the meter map in the module, the metronome following it, the click and count-in
   in playback.
5. **Automatic page turn**: the bars-per-page band (PDF), and turning from the layout (MusicXML).

## 5. Questions for the maintainer

- Cut time (¢ = 2/2) next to C?
- The page border flash: one colour for the accent and another for the other beats?
- Should the count-in also be visual (a big 1-2-3-4 over the page)?

## 6. First version (branch `feature/metronome`)

Built on the MusicXML speed branch (#36) and the half-page fix, tried on the Galaxy Tab 3.

Done:
- Engine on the audio clock (`MetronomeEngine`, `ClickSchedule`), sounds made in code (`Clicks`),
  tap tempo, Italian names, compound time counted in dotted quarters, count-in, subdivisions,
  accent, sound / flash / both, volume.
- Menu › Metronome opens the settings (as Playback does) and shows the band in place of the
  playback band, with the bar heard counted from the start of the piece. The tempo block (name,
  typed value, slider, TAP, as written) is shared with the playback settings; flash / sound and
  the sound are drop-down menus. All three languages.
- The bars of each page (PDF) are counted in the settings, page by page, the page behind changing
  with ◀ ▶.
- Page turns from the bars of each page (PDF) and from the score's own bars (MusicXML), forward
  only, in single page, two-up and half page; turn ahead 0 (default) to 2 bars, for playback too,
  also in the playback settings.
- A MusicXML score has one tempo: the metronome's is the one of playback; its time signature is
  the first one of the file.

Not yet:
- The click inside playback (a percussion track added to the MIDI) and the count-in before
  playback.
- Time signature and tempo changes during a MusicXML piece (the meter map).
- Time signature and tempo as score details in the database (issue #40).
