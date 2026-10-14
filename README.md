# Trip Ledger

An offline Android app for tracking what you spend while travelling. Kotlin, Jetpack Compose
(Material 3), Room, single activity, MVVM. All data stays on the device; the app requests no
permissions at all.

- Trips with their own dates, home currency, local currencies and hand-entered exchange rates
- Expenses grouped by day with subtotals, shown in your home currency with the original amount underneath
- Statistics: total, daily average, by category (donut + list), per day (bars), biggest expenses, date filter
- Search over title, note and category, with category / date / amount filters, in one trip or all
- Settings for theme, accent, currencies, categories, payment methods, formats, defaults, summary cards
- JSON backup and restore, CSV export, delete-all

See [DECISIONS.md](DECISIONS.md) for the judgement calls, and [screenshots/](screenshots/) for every screen.

## Current state of your machine

- The emulator **`tripledger_api33`** (Pixel 6, Android 13 / API 33) is running as `emulator-5554`.
- The debug APK is installed on it, the app is open, and two sample trips are loaded
  ("Thailand", 35 expenses in THB/USD/EUR, and "Lisbon weekend", 10 expenses).
- Nothing was installed on the phone that was plugged in.

## Build and run

Requirements: JDK 17 and the Android SDK (both already on this machine). `local.properties`
points at the SDK. In PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.6.10-hotspot"
.\gradlew.bat :app:assembleDebug          # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:testDebugUnitTest      # JVM unit tests
```

Start the emulator (if it is not running) and install:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\emulator\emulator.exe" -avd tripledger_api33
& "$sdk\platform-tools\adb.exe" -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
```

Or open the folder in Android Studio and press Run.

Instrumented tests (Git Bash). The script installs on the emulator only:

```bash
tools/run_android_tests.sh                               # everything
tools/run_android_tests.sh app.tripledger.AppFlowTest    # one class
```

**The UI tests wipe the app's data.** Afterwards, reload the demo data with
Settings → Add sample trips (or the link on the welcome screen).

Avoid `gradlew connectedAndroidTest` while a phone is plugged in: it installs on every attached device.

`tools/ui.py` is the small adb driver I used to operate the app by hand (tap by text or test tag,
type, screenshot); `tools/build_install.sh` builds and installs in one step.

## What was tested, and the results

All results below are from the final code, on the API 33 emulator.

| Suite | Where | Tests | Result |
|---|---|---|---|
| `MoneyTest`: parsing, formatting, rates, currency conversion, rounding | JVM | 20 | all pass |
| `StatsTest`: totals, daily average and day counting, day grouping, category shares, per-day series, biggest expenses, search matching, week/date helpers | JVM | 24 | all pass |
| `BackupTest`: backup JSON round trip and rejection of bad files, CSV content and escaping, settings parsing, sample data sanity | JVM | 13 | all pass |
| `DatabaseTest`: every Room DAO and the repository (cascading deletes, ordering, category/payment/currency rules, seeding, backup round trip through the database, delete-all) | emulator | 24 | all pass |
| `AppFlowTest`: Compose UI tests of the real app | emulator | 19 | all pass |

**100 tests, 0 failures** (57 JVM + 43 instrumented).

The UI tests cover: first launch into creating a trip; trip name validation; switching, renaming
and deleting trips; add, edit and delete an expense, checking converted and original amounts,
cards and subtotals; amount validation and the optional title; day grouping and daily average;
a currency with no rate, then fixing it; a very long title; the entry form surviving activity
recreation; statistics including custom and preset ranges and empty states; search by title, note
and category, trip scope, category, amount and date filters; number, currency and date format
changes taking effect on the entries screen; summary card toggles; theme and accent; defaults for
new entries; categories add, rename, delete with reassignment; payment methods; delete-all with
its confirmation; loading sample data.

### Checked by hand through adb, with screenshots

I drove the installed app as a user would and looked at every screenshot in `screenshots/`:

- Welcome, create trip, currency picker, date picker, empty trip (`01`–`06`)
- Add expense with the keyboard up, filled in, live conversion hint (`07`–`09`)
- Entries list with sample data, scrolled (`11`, `12`); statistics top to bottom (`13`–`15`)
- Search with text and filters (`16`, `17`); trips (`18`); settings top to bottom (`19`–`21`)
- **Backup export, CSV export and import through the real system file picker.** I pulled both
  files off the emulator and checked them: the backup held all 45 entries with integer amounts,
  the CSV had 46 rows × 11 columns, a byte-order mark, and its Thailand total (1,107.80) matched
  the app. I then deleted a trip and restored it from the backup (`22`, `23`, `25`, `26`).
- A deliberately truncated backup file was rejected and the data was left intact.
- Categories, category editor, payment methods, currencies, accent and theme dialogs (`27`–`33`)
- Dark theme on settings, entries, statistics, add expense and the date picker (`34`–`38`)
- Landscape (`39`, `40`)
- **Process death:** half-filled the expense form, sent the app to the background, killed the
  process with `am kill`, reopened it. The form came back with amount, title, category and scroll
  position intact (`41`).
- Edit and delete an expense (`42`, `43`); a currency with no rate, in the form, the trip editor
  and the list banner (`44`, `46`, `47`); the date picker with Sunday as first day (`45`)
- Logcat after all of that: no crashes, no exceptions from the app.
- The built APK requests no INTERNET permission (`aapt2 dump permissions`; the only entry is
  AndroidX's own non-exported internal receiver permission), and the sources contain no network code.

### Problems found during testing, and fixed

- Opening search filters inserted the panel above the results but left the list scrolled to the
  first result, hiding the category chips. It now scrolls to the top.
- The search result count scrolled out of view while typing an amount filter. It is now pinned
  above the list.
- Opening search filters left the keyboard covering them. The keyboard is now dismissed.
- Save could be tapped in the instant before a form finished loading and did nothing. It is now
  disabled until the form is ready.
- A custom statistics range with only an end date could include days after that date.
- The category grid in the expense form was tall enough that the keyboard hid a third of it.
- The import error for a damaged file quoted raw parser output including file contents.
- The app and its test APK disagreed on the kotlinx-coroutines version; it is now pinned.

## Unfinished, untested or known to be imperfect

Please read this part.

**Not verified**

- **The CSV was never opened in Excel or Google Sheets.** I validated it with a CSV parser only.
- **The reworded "backup file is damaged" message was not re-checked on screen** after I shortened
  it; `24_import_rejected.png` shows the old, longer wording. The rejection itself is tested.
- **Tested on one device configuration only:** the Pixel 6 emulator, API 33, English locale,
  default font size. Not tested on a real phone, a tablet, a small screen, large font sizes,
  right-to-left languages, TalkBack, or any other Android version (minSdk is 26 but API 26–32 and
  34+ are untested).
- **Follow-system theme** was not tested by toggling the system dark mode; I tested the explicit
  Light and Dark settings. It uses the standard Compose call.
- **Reordering** categories and payment methods is covered by database tests but I did not tap
  the arrows by hand. **Adding a custom currency** and **deleting a currency** were not exercised
  through the UI (the rules behind them are database-tested).
- **Currency symbols** such as ៛ and ₭ depend on the device font; I only looked at €, $, ฿ and ¥.
- Only the debug build was built and run. There is no release build, signing or minification setup.
- Performance with thousands of entries is untested. Each day's entries are laid out as one list
  item and search results as one block, which is fine for a trip but would get slow at that scale.

**Known limitations**

- **Leaving a half-filled form with Back or ✕ discards it without asking.**
- **One rate per currency per trip.** Changing a rate re-values every expense in that currency on
  the trip (see DECISIONS.md). There is no per-expense rate.
- **No negative amounts**, so refunds cannot be recorded.
- **Import replaces all data**; there is no merge.
- **English only.** UI text is hard-coded in the Kotlin sources, not in string resources.
- If the title is left empty it becomes the category name, so the row shows the name twice.
- "Today" and "Yesterday" labels are worked out when the list loads; with the app left open across
  midnight they stay stale until something changes or you leave and return to the screen.
- In landscape the bottom navigation bar takes a large share of the height. Everything scrolls and
  works, but it is cramped.
- A statistics range longer than a year only charts its most recent 366 days (totals still cover it all).
- Reordering is by arrow buttons, not drag-and-drop.
- The debug APK is about 19 MB, mostly the bundled Material icon set, which only release-build
  shrinking would trim.
- Two screenshots are slightly off: `31_currency_edit.png` shows "0.87British Pound" because my
  test script typed a rate into the name field (my mistake, not an app bug; the data was reset
  afterwards), and `02`–`06` were taken with a US-dollar home currency before I switched the demo
  setup to euros.

## Project layout

```
app/src/main/java/app/tripledger/
  data/      Room entities, DAOs, database, Repository, defaults, backup/CSV, sample data
  domain/    Money maths, conversion, statistics, search filter, date helpers (pure Kotlin)
  ui/        Navigation, view models, theme, components, and one file per screen
app/src/test/         JVM unit tests
app/src/androidTest/  Room and Compose UI tests
tools/                adb driver and emulator-only build/test scripts
screenshots/          every screen, light and dark
```
