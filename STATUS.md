# Project status

As of 14 October 2026.

## Summary

Trip Ledger is feature-complete against the original brief and works on both the Android 13
emulator and a real phone. All 100 automated tests pass. There are no known crashes or data bugs.
What remains is usability polish (several items found on the phone, below), localisation, and
release packaging.

| Area | State |
|---|---|
| Trips, entries, statistics, search, settings | Done and tested |
| Backup export / import, CSV export, delete-all | Done and tested |
| Offline, no permissions, integer money | Done and verified |
| Launcher icon (adaptive + monochrome) | Done; monochrome layer not seen in a themed launcher |
| Automated tests | 57 JVM + 43 instrumented, all passing (emulator) |
| Real-device check | Done on one phone, see below |
| Localisation | Not started (English only) |
| Release build (signing, shrinking) | Not started; only the debug build exists |

## Test on a real phone

Device: OnePlus Nord CE (EB2103), Android 13, arm64, 1080×2400 at 480 dpi (360 dp wide, narrower
than the emulator's 411 dp), Finnish locale, system dark mode, 3-button navigation.

I installed the debug APK over USB and drove the app through adb, the same way as on the emulator.
Screenshots are in `screenshots/phone/`.

**Worked**

- Install and first launch; the welcome screen; loading the sample trips
- "Follow system" theme picked up the phone's dark mode (not verified before)
- Home currency defaulted to EUR from the Finnish locale
- Adding an expense with a comma decimal (`185,5` THB became €4.90), saving, editing, deleting
- Date picker, with Finnish month and weekday names and Monday first
- Statistics (donut, per-day bars, biggest expenses), search and its filter panel, trips, settings
- **Backup export through OnePlus's own file picker**: the file landed in Downloads, and I pulled
  it off and checked it (46 entries, integer amounts)
- **Process death**: a half-filled expense form survived `am kill` and came back intact
- Launcher icon under OnePlus's squircle mask: not clipped (`p14_launcher_icon.png`)
- Logcat: no crashes or exceptions from the app

**Not tried on the phone:** backup import, CSV export, landscape, light theme, the settings
sub-screens, and the automated tests (the UI tests wipe the app's data, so I ran them on the
emulator only).

### Problems found on the phone

None of these lose data or crash; they are usability issues. In rough order of importance:

1. **The keyboard hides most of the category grid.** On this phone only the first row of four
   categories is visible while typing an amount (`p04_add_amount.png`); the emulator showed all
   three rows. To pick any other category you must close the keyboard or scroll first, which
   undercuts the "fast, standing in a shop" goal. This is the one I would fix first.
2. **Mixed languages.** Dates, months and weekdays follow the phone's language (`ke, 14 lokak.
   2026`, `lokakuu`), while everything else is English (`Today · ke, 14 lokak. 2026`).
3. **Number format ignores the locale.** It starts as `1,234.56` on a Finnish phone, where
   `1 234,56` would be expected. It can be changed in Settings, but the default is wrong here.
4. **Text is cut short on the narrower screen.** "Accommod…" in the category grid; in Statistics
   and Search rows the long date leaves little room, so the rest reads "Flights …" or "Accomm…"
   (`p08_stats_chart.png`). Expense titles are also truncated sooner than on the emulator.
5. **The search filter panel fills the whole screen**, so results are only visible after
   scrolling (`p09_search_filters.png`). The result count stays visible above it.
6. Minor: in the expense form the date chips wrap onto two lines; Android logs that predictive
   back is not enabled for the app.

The limitations already listed in the README still apply (Back discards a half-filled form
without asking, one rate per currency per trip, no negative amounts, import replaces rather than
merges, cramped landscape).

### What is on the phone now

- Trip Ledger (debug build, signed with the debug key) is installed and open.
- The two sample trips are loaded. To start clean: Settings → Delete all data, or uninstall.
- The test expense I added was deleted again, and the backup file I exported to Downloads was
  removed. Nothing else on the phone was changed.

## Ideas for improving the app

### Quick wins

- **Make categories reachable while typing.** A single horizontally scrolling row of categories
  directly under the amount (most-used first), with the full grid below, fixes problem 1.
- **Take defaults from the phone's locale** on first launch: number format and first day of week.
- **Ask before discarding** a half-filled expense or trip.
- **Undo after delete**: a short "Expense deleted · Undo" bar instead of only a confirmation.
- **Shorter dates in list rows** (e.g. `14.10.`) and hide the subtitle's category when it equals
  the title; fixes most of problem 4.
- **Duplicate an expense**, for the coffee you buy every morning.
- Collapse the search filters into chips that each open a small sheet (problem 5).
- Enable predictive back.

### Features worth adding

- **Budget.** A trip or daily budget, with a "left today / left for the trip" summary card. This
  is the feature travellers tend to miss most in a tracker without one.
- **Spread an expense over several days.** A €619 flight on day one makes that day's bar and the
  daily average misleading; let it count across the whole trip, or exclude it from the average.
- **A rate per expense or per date.** Cash changed at one rate and card payments at another is
  normal on a trip. Keep the trip rate as the default and allow an override.
- **Refunds and shared costs.** Negative amounts, and "my share" of a split bill.
- **Faster entry from outside the app**: a home-screen widget or an app shortcut that opens
  straight into the amount field.
- **Finnish (and other) translations.** Text would first have to move from the Kotlin sources
  into string resources.
- **Receipt photos**, stored locally.
- **More statistics**: by payment method, by currency, cumulative spending against a budget line,
  trip-to-trip comparison.
- **Safer data**: automatic periodic backup to a folder you choose, merge on import, CSV import.

### Engineering

- **A release build** with code shrinking and a signing key. The debug APK is about 31 MB, mostly
  the bundled icon set; a shrunk release build would be a small fraction of that.
- Run the automated tests on a second screen size and on a non-English locale, since both turned
  up issues here.
- An accessibility pass (TalkBack labels, large font sizes), and a tablet layout.
- Lazy lists for a day's entries and for search results, in case a trip grows to thousands of rows.
- Drag-and-drop reordering in place of the up/down arrows.
