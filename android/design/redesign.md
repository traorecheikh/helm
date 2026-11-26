# Helm redesign contract (Nov 2026)

Source of truth for every agent working on the redesign. The old UI is in `git show HEAD:<path>` and `/tmp/helm-old/ui`.

## Product in one line
Helm = pocket cockpit for a self-hosted Dokploy server: is it fine, what needs me, fix it in two taps.

## World (from Iris DESIGN.md, light first)
Paper (magenta-tinted) ground, white rounded cards, magenta = the one action colour, green/orange/red = state only, yellow accent for one highlight at most. Font Schibsted Grotesk (via `Txt`/`H1`/`H2`, numbers via `DotNum`). Phosphor icons only. Light AND dark by tokens (`H.c.*`), never raw hex in screens.

## Screens (9 max, nothing else gets a route)
HomeR · AppsR · ShieldR(ip) · ServerR · SettingsR · SearchR · ServiceR(kind,id) · DeployR · SetupFlow.
Detail = bottom sheet (`SheetFrame`/`ConfirmSheet`) or inline expansion, never a new screen. Tabs = at most ONE segmented `Tabs` row per screen. No breadcrumb + tabs stacking. No link/button nobody asked for.

## Rules
1. One hero per screen (one big status/number/card). Everything else is quiet, grouped in `Card`s with 12–16dp gaps, 20dp side padding (Screen does it).
2. Visual first: state = colour + `Mk`/`Pill`/`Seg`/`Meter`/`DotNum`/icons. If it needs a sentence to be understood, fix the UI.
3. Text −70%: titles 1–2 words, NO subtitle under titles, NO explanatory paragraphs, labels ≤2 words, empty state = icon + ≤3 words, errors = one short line. Confirm sheets: title + one short line.
4. `H.c.signal` (magenta) = actions/selection only. Failures/danger = `H.c.bad` / `Tone.Signal` (renders red). Warn orange, ok green. Never use `signal` for an error.
5. Every existing capability stays reachable (tab, sheet, row action, long-press menu). Nothing dropped silently; if a feature truly cannot fit, list it in your report.
6. Touch targets ≥48dp, content descriptions on icon-only controls, `Haptics` as before, reduce-motion respected (`LocalReduceMotion`).
7. Strings: `tr(R.string.x)`, add to BOTH `res/values/strings.xml` (EN) and `res/values-fr/strings.xml` (FR). Short. Reuse existing strings when they fit. Do not delete strings other files may use (cleanup is done at the end by the lead). Route/param/identifier names stay English.
8. Data layer (`data/`, `service/`, `widget/` logic) is untouched: reuse `rememberLoad`, `Store`, `Repo`, `Live`, etc. exactly as the old screens did. Behaviour must be identical, only presentation + IA change.
9. Components live in `ui/components` (lead-owned: Card, Pill, Li, Stat, Tabs, Chips, Btn, Field, Toggle(Row), Seg, Meter, MemBars, DotGrid, Banner, Notice, Empty, Failed, ErrorLine, SheetFrame, ConfirmSheet, SlideToAct, HoldButton, Dock, DotNum, IconTile, Mk, LogBox, WorldMap). Do NOT edit them; if you truly need a new shared one, define it `internal`/`private` in your own file.
10. Max ~300 lines per screen file where possible; split into a second file only with a real reason. No generated artefacts, no new dependencies.

## Build
`cd android && ./gradlew --offline :app:compileDebugKotlin -q 2>&1 | grep -E "^e:|FAILED"`.
Other agents are editing other files in parallel: if errors are in files you do not own, wait ~60s and retry; never touch them. Do NOT install to the phone or run adb (one shared device; the lead verifies).
