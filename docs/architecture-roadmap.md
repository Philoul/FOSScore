# FOSScore: architecture review and improvement roadmap

> **Status:** proposal for discussion between the maintainer and contributors.
> **Baseline:** `main` at `7b52f7b` (25 commits). Pending pull requests #4–#7 are *not* included.
> **Date:** 2026-10-13

This document reviews the current code base and proposes a step-by-step plan to improve it. Each
step is small, independent where possible, and can become one pull request. The goal is to make the
app safer for users' data and easier to change, without slowing down bug fixing.

---

## 1. Summary

FOSScore is in good shape for a young project. The package layout is clear, the geometry and
paging logic is extracted into pure functions with good unit tests, and the comments explain *why*
decisions were made.

The main weaknesses are:

- **User data safety:** two persistence defects (orphaned ink strokes, non-atomic writes) and a
  database setup that can silently wipe the whole library after a bad migration.
- **One very large screen:** `PdfViewerScreen.kt` holds most of the app's behaviour in a single
  1,722-line composable, which makes it hard to change, review and test.
- **Wiring and tests:** dependencies are fetched from the `Application` inside the UI, there is no
  `ViewModel`, and nothing outside the pure logic is tested (no database, UI or CI checks).

## 2. Code base at a glance

| Area | Files | Notes |
|---|---|---|
| Entry and navigation | `MainActivity.kt`, `FOSScoreApp.kt` | Hand-written `Screen` sealed interface; manual dependency container in `FOSScoreApp` |
| Data (`library/`) | `AppDatabase`, 3 entities, 3 DAOs, 3 repositories, `SheetImporter` | Room 2.7, schema version 4, migrations 1→4 |
| PDF engine (`pdf/`) | `MuPdfDoc.kt` | MuPDF wrapper with bounded caches and a memory ceiling |
| Library UI (`ui/library/`) | `LibraryScreen`, `SheetDetailScreen`, `SheetFilter` | Small and readable |
| Viewer UI (`ui/viewer/`) | `PdfViewerScreen.kt` (1,722 lines) and helpers | Pure helpers: `PageFlow`, `ZoomMath`, `InkGeometry`, `StampGeometry` |
| Tests | 10 unit test classes, 50 tests | All on pure logic; no `androidTest`, no database tests |

Total: about 3,400 lines of Kotlin in `src/main`.

## 3. Strengths to keep

- **Pure logic is separated from UI and tested.** `PageFlow`, `ZoomMath`, `InkGeometry` and
  `StampGeometry` are plain functions with focused tests. `ZoomGestureSimTest` replays gesture
  sequences, which is an excellent way to lock down tricky behaviour. New logic should follow this
  pattern.
- **Room is used properly:** typed DAOs, reactive `Flow`s, real versioned migrations and indexes on
  `(sheetHash, page)`.
- **MuPDF is handled carefully:** bounded LRU caches, a pixel budget, thread-safety documented and
  enforced with a `Mutex`, and the document is closed outside the composition.
- **Comments are useful.** The `ponytail:` notes record trade-offs and the planned upgrade path.

## 4. Findings

Severity: **High** = can lose or corrupt user data · **Medium** = slows development or hides bugs ·
**Low** = polish.

| ID | Severity | Finding | Where |
|---|---|---|---|
| D1 | High | Deleting a sheet removes its stamps but **not its ink strokes**. Re-importing the same PDF (same hash) brings the old strokes back. | `LibraryRepository.delete` |
| D2 | High | Multi-step writes are **not transactional**. Undo deletes a page, then re-inserts it, in two tables, in four separate statements. Sheet deletion is also several steps. An interruption in between loses annotations. | `StampRepository.restorePage`, `StrokeRepository.restorePage`, `LibraryRepository.delete`, undo in `PdfViewerScreen` |
| D3 | High | `fallbackToDestructiveMigration(dropAllTables = true)`: if a future version ships without the right migration, Room **silently deletes the whole library**. | `AppDatabase.get` |
| D4 | Medium | The Room schema is not exported, so migrations cannot be tested automatically. | `app/build.gradle.kts` |
| A1 | Medium | `PdfViewerScreen` is one composable of 1,722 lines with 58 pieces of `remember` state. It mixes gestures, zoom, rendering, editing, undo, toolbars and persistence. It is the main source of merge conflicts and cannot be tested in isolation. | `ui/viewer/PdfViewerScreen.kt` |
| A2 | Medium | No dependency injection at the screen level: the viewer reaches into `context.applicationContext as FOSScoreApp` (5 places), while the library screens receive their repository as a parameter. Repositories call `AppDatabase.get(context)` themselves instead of receiving DAOs. There are no interfaces, so nothing can be replaced by a fake in tests. | `PdfViewerScreen`, `*Repository`, `FOSScoreApp` |
| A3 | Medium | No `ViewModel`. Database writes run in `rememberCoroutineScope()` (12 launches), which is cancelled when the screen leaves composition, so a write started just before leaving can be dropped. | `PdfViewerScreen` |
| A4 | Low | Settings are read and written straight from composables through a global `Settings` object (`SharedPreferences`). | `ui/viewer/Settings.kt` |
| T1 | Medium | Tests cover pure logic only: no DAO or migration tests, no `ViewModel`, navigation or UI tests, no end-to-end import test. D1 and D2 would have been caught by a simple in-memory database test. | `app/src/test` |
| Q1 | Medium | No continuous integration for build, tests and lint, so regressions are found by hand. | `.github/` (absent) |
| Q2 | Low | 13 temporary diagnostic logs (`TEMPORAL`, `Log.d`) remain in production code in the viewer. | `PdfViewerScreen` |
| Q3 | Low | User-facing strings are hard-coded in Kotlin (also in error results such as `ImportResult.Error`). | Several files (addressed by PR #4) |
| Q4 | Low | No formatter or static analysis (ktlint or detekt) to keep the style consistent across contributors. | Project root |

## 5. Roadmap

The phases are ordered by risk to users first, then by how much they make later work easier.
Inside a phase, steps are independent unless stated.

Each step lists its size (**S** < 1 day, **M** 1–3 days, **L** > 3 days for a part-time
contributor) and its conflict risk with ongoing viewer bug fixing.

### Phase 0: Coordinate (before any refactoring)

| Step | What | Size |
|---|---|---|
| 0.1 | Review and merge, or decline, the pending PRs: #4 (string resources and language picker), #5 (rotation), #6 (negative mode), #7 (keyboard page turns). They are independent and based on `main`. Merging them first avoids rebasing them over refactors. | S each |
| 0.2 | Agree on conventions: language for comments and commits, branch naming, "one topic per PR", and a target PR size of about 300 changed lines. Record them in the root `CLAUDE.md` (proposed in this PR) so that contributors and AI assistants follow the same rules. | S |
| 0.3 | List the open viewer bugs (see commit `7b52f7b`, "bugs ABIERTOS") as GitHub issues, so contributors know which areas are being changed. | S |

### Phase 1: Protect user data (small fixes, do first)

**1.1 Delete strokes with their sheet (fixes D1).** Size S · conflict risk low.
- Call `strokeDao.deleteForSheet(sheet.hash)` in `LibraryRepository.delete`.
- Done when: deleting then re-importing a PDF shows no old ink, and a test covers it (see 1.3).

**1.2 Make multi-step writes atomic (fixes D2).** Size S–M · conflict risk low.
- Add one DAO method or repository method running in a single `@Transaction` / `withTransaction`
  that restores a page's stamps **and** strokes together. Use it from undo.
- Wrap sheet deletion (row, stamps, strokes) in one transaction. Delete the PDF file only after
  the transaction succeeds.
- Done when: undo and delete each perform one transaction, and the tests from 1.3 cover both.

**1.3 Add database tests.** Size M · conflict risk none.
- Add in-memory Room tests for the DAOs and repositories, running on the JVM with Robolectric (no
  device needed, works in CI).
- Start with: sheet delete cascade, page restore, observe-by-sheet ordering.

### Phase 2: Make the schema safe (before any import/export or backup feature)

**2.1 Export the schema (fixes D4).** Size S.
- Configure `room { schemaDirectory("$projectDir/schemas") }` with the Room Gradle plugin, or the
  `room.schemaLocation` KSP argument.
- Commit the generated JSON for version 4.

**2.2 Test migrations.** Size M.
- Add `MigrationTestHelper` tests for 1→2, 2→3 and 3→4, plus a test that opens version 4 from
  scratch.

**2.3 Remove the destructive fallback (fixes D3).** Size S, after 2.2.
- Remove `fallbackToDestructiveMigration`. A missing migration should then fail loudly in testing
  instead of wiping users' libraries.

**2.4 Design backup / import-export.** Size M (design).
- With a tested schema, define an export format: the PDFs plus a JSON or SQLite dump of sheets,
  stamps, strokes and settings.
- This lets users move to a new device or reinstall without losing their library.

### Phase 3: Tooling (can run in parallel with Phase 1)

**3.1 CI on every PR (fixes Q1).** Size S.
- Add a GitHub Actions workflow running `assembleDebug`, `testDebugUnitTest` and `lintDebug` on
  pull requests.

**3.2 Formatting and static analysis (fixes Q4).** Size S.
- Add ktlint, or detekt with a baseline so existing code does not fail.
- Format files only when they are touched anyway, to avoid huge diffs.

**3.3 Remove or gate temporary logs (fixes Q2).** Size S · conflict risk medium.
- Do this together with the viewer bug fixes that the logs were added for.

### Phase 4: Wiring and testability (prepares the viewer refactor)

**4.1 Constructor-inject the DAOs.** Size S.
- Repositories receive their DAO(s) instead of calling `AppDatabase.get(context)`.
- `FOSScoreApp` builds the single database and passes the DAOs.

**4.2 Pass dependencies into the viewer (fixes part of A2).** Size S · conflict risk medium.
- `PdfViewerScreen` receives `StampRepository` and `StrokeRepository` as parameters, like
  `LibraryScreen` already does, and no longer casts `applicationContext`.

**4.3 Introduce interfaces where tests need fakes.** Size S.
- Start with the repositories. A DI framework such as Hilt is not needed at this size; manual
  wiring in `FOSScoreApp` is enough.

**4.4 Wrap settings in a small `SettingsRepository` (fixes A4).** Size S.
- Same API as today, injected instead of global, so it can be faked in tests.

### Phase 5: Split the viewer (fixes A1 and A3), incrementally

> Start this phase **after** the open viewer bugs from 0.3 are fixed. Refactoring and bug fixing
> in the same 1,700-line file at the same time will produce painful conflicts.

Rules for every step in this phase:
- **No behaviour change.** Refactor PRs only move code.
- One extraction per PR.
- Existing tests must pass, and a short manual checklist must be run: open a sheet, zoom, pan, turn
  pages in every mode, draw, add a stamp, undo, rotate.

| Step | What | Size |
|---|---|---|
| 5.1 | Extract the toolbars (mode bar, page bar, edit palette, dim slider) into their own composables and files, passing state and callbacks as parameters. | M |
| 5.2 | Extract the drawing code (strokes, stamps, selection handles) into pure `DrawScope` functions in a separate file. | M |
| 5.3 | Extract the gesture handling into a dedicated modifier or class, keeping the logic testable like `ZoomGestureSimTest`. | M–L |
| 5.4 | Introduce `ViewerViewModel`. Move the page state, edit state, undo stack and all database writes into it, with writes in `viewModelScope` (fixes A3). The composable keeps only UI state such as transient drag previews. | L |
| 5.5 | Move document opening and rendering orchestration (MuPDF lifecycle, render requests) into a separate class owned by the ViewModel. | M |

Target: no file in `ui/viewer` above about 400 lines, and the viewer composable mostly lays out
smaller components.

### Phase 6: Broaden tests (continuous)

- `ViewerViewModel` unit tests: undo, page changes, persistence calls, using fake repositories.
- A few Compose UI tests for the critical flows: open a sheet, turn pages (buttons and keys),
  rotate, toggle negative mode.
- An end-to-end import test: duplicate detection, title extraction, file copied.

## 6. Suggested order at a glance

```
Phase 0  coordinate, merge pending PRs, list open viewer bugs
   │
   ├── Phase 1  data fixes (1.1 → 1.2, with 1.3 tests)   ─┐
   ├── Phase 3  CI + lint (in parallel)                   ├─ low conflict, high value
   │                                                      ─┘
Phase 2  schema export → migration tests → remove destructive fallback → backup design
   │
Phase 4  DI and interfaces (small, prepares Phase 5)
   │
   └── (after viewer bugs are fixed)
Phase 5  split the viewer, one extraction per PR
   │
Phase 6  broaden tests as each piece becomes testable
```

## 7. Working together

- **Small PRs, one topic each.** A bug fix, a refactor and a feature should be separate PRs. This
  keeps reviews quick and makes regressions easy to find.
- **Refactors do not change behaviour.** Say so in the PR description and include the manual
  checklist from Phase 5.
- **Tests come with fixes.** A bug fix should add a test that fails before the fix, whenever the
  code is testable. Phases 1, 4 and 5 make more code testable.
- **Watch the hot spot.** Until Phase 5 is done, almost every change touches `PdfViewerScreen.kt`.
  Announce work on it in an issue to avoid two people editing the same area.
- **Ask early.** For larger steps (2.4, 5.3, 5.4), open an issue with a short design sketch before
  writing code.
