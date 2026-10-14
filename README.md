# Trip Ledger

An offline Android app for tracking what you spend while travelling. Kotlin, Jetpack Compose
(Material 3), Room, single activity, MVVM. All data stays on the device; the app requests no
permissions at all. The whole interface is in English.

- Trips with their own dates, home currency, local currencies, hand-entered exchange rates and an
  optional budget (for the whole trip or per day)
- Expenses grouped by day with subtotals, in your home currency with the original amount
  underneath. An expense can be a refund, a shared bill (only your share counts), spread over
  several days, left out of the daily average, have its own exchange rate, and carry a receipt photo
- Fast entry: the most-used categories sit in a row under the amount, above the keyboard; an
  "Add expense" launcher shortcut and home-screen widget open straight into the form
- Undo after deleting an expense; a prompt before discarding an unsaved expense or trip; duplicate
  an expense
- Statistics: total, daily average, by category, per day, running total against the budget, by
  payment method, by currency, biggest expenses, and every trip compared, with a date filter
- Search over title, note and category, with category / date / amount filters, in one trip or all
- Settings for theme, accent, currencies, categories and payment methods (drag to reorder),
  formats, defaults and summary cards
- Backup to a file (replace or merge on import), automatic backups to a folder you choose,
  CSV export and import, delete-all

See [STATUS.md](STATUS.md) for the current state and what is left, [DECISIONS.md](DECISIONS.md)
for the judgement calls, and [screenshots/](screenshots/) for the screens.

## Current state of your machine

- The emulator **`tripledger_api33`** (Pixel 6, Android 13 / API 33) is running as
  `emulator-5554`, with default display settings. The current debug build is installed and open,
  with the two sample trips loaded ("Thailand", 35 expenses with a €1,500 budget, a shared bill and
  a flight spread over the trip; "Lisbon weekend", 10 expenses). An "Add expense" widget is on its
  home screen.
- Your **OnePlus Nord CE** has the **release build** (version 1.0). The development build and its
  sample data were removed first, so the app starts empty.
- The release APK is kept at `release/TripLedger-1.0-release.apk` (1.8 MB).

## The release build

- Built with `.\gradlew.bat :app:assembleRelease`; the APK lands in `appuild\outputspkelease\`.
elease\`.
- Code shrinking (R8) and resource shrinking are on, which takes the APK from 31 MB to 1.8 MB.
- Release builds start with no trips and do not offer the sample trips. The built-in categories,
  payment methods (Cash, Credit card, Debit card) and the currency list (no rates) are kept.
- It is signed with a key generated for this app: `release/tripledger-release.jks`, with its
  passwords in `release/keystore.properties`. **Both are deliberately left out of git. Back them
  up somewhere safe** (a password manager or an encrypted drive). Android only installs an update
  over the phone's copy if it is signed with this same key; without it, a future version can only
  be installed by uninstalling, which deletes the app's data. Certificate SHA-256:
  `b9cd50f0fc016136ce0182be34171bbcaeb32dbbce31e96f631ef8a4515db316`.
- For a future version, raise `versionCode` (and `versionName`) in `app/build.gradle.kts` before
  building, then install over the old one with `adb install -r`.
- Before release, the full test suite was run once more on the debug build (82 unit + 68
  instrumented, all passing), and the release APK itself was walked through on the emulator:
  first launch, creating a trip with a currency and budget, adding an expense, statistics,
  search, backup export and merge import, CSV export and import, a camera receipt, delete with
  undo, and the "Add expense" shortcut. No crashes. Screenshots in `screenshots/release/`.

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

From Git Bash, `tools/unit.sh` runs the unit tests with a summary, and
`tools/run_android_tests.sh [class]` runs the instrumented tests on the emulator only.
**The UI tests wipe the app's data**; reload the demo with Settings → Add sample trips.
Avoid `gradlew connectedAndroidTest` while a phone is plugged in: it installs on every attached device.

`tools/ui.py` is the adb driver used to operate the app by hand; `tools/build_install.sh` builds
and installs on the emulator.

## What was tested, and the results

All results are from the final code.

| Suite | Where | Tests |
|---|---|---|
| `MoneyTest`: parsing, formatting (including refunds), rates, per-expense rates, region number formats | JVM | 23 |
| `StatsTest`: totals, shared bills, refunds, daily average and exclusions, spreading, running total, budgets, payment/currency breakdowns, search, English dates | JVM | 41 |
| `BackupTest`: backup round trip and old-format files, CSV export and reading back, CSV error messages | JVM | 18 |
| `DatabaseTest`: every Room DAO and the repository | emulator | 24 |
| `MigrationAndImportTest`: database upgrade from version 1, merge import, CSV import, undo, drag reordering, region defaults | emulator | 11 |
| `AppFlowTest`: Compose UI tests of the original flows | emulator | 19 |
| `NewFeaturesFlowTest`: Compose UI tests of the new features | emulator | 14 |

**150 tests, 0 failures** (82 JVM + 68 instrumented). The instrumented suite passed twice on the
final code: once with the screen narrowed to 360 dp (the width of your phone) and once at the
emulator's default size. An earlier run at 360 dp with the app's language set to Finnish also
passed.

### Checked by hand

On the emulator, with screenshots in `screenshots/v2/`:

- Budget cards, including the red "over budget" state (`01`)
- The quick category row above the keyboard at 360 dp, and "Accommodation" fitting on one line (`02`)
- More options: refund, shared bill with split buttons, spreading, own rate, photo, note (`03`, `04`)
- **Receipt photo through the emulator's real camera app**: captured, shrunk and stored in the
  app's private folder, shown as a thumbnail and full screen, saved with the expense (`05`, `06`)
- New statistics panels and the trip comparison (`07`–`09`)
- Search filter chips and sheet (`10`, `11`)
- **Automatic backup**: chose a folder through the system picker, "Back up now" wrote the file;
  then, with a backup due, simply leaving the app wrote a fresh one (`12`, `13`)
- **Drag-and-drop reordering** of categories, with the new order saved (`14`)
- **The launcher shortcut and the home-screen widget**, both opening straight into the form (`15`–`18`)
- Landscape with the side rail (`19`, `20`); 130% text size (`21`–`23`); dark theme (`24`)
- Logcat: no crashes.

On your phone: the new build installed over the old one, the existing data survived the database
upgrade, the region number format took effect, and there were no crashes
(`screenshots/phone/v2_p01_after_upgrade.png`). Most of the phone walk-through was done on the
previous version (see STATUS.md).

## Unfinished, untested or known to be imperfect

Please read this part; STATUS.md has the full list.

- **The monochrome (themed) launcher icon** has never been seen in a launcher with themed icons on.
- **The CSV export has never been opened in Excel or Google Sheets**; it is validated by a parser
  and by importing it back.
- **The new features were exercised by hand on the emulator only.** On your phone I only checked
  the upgrade and startup before stopping, because you were using it.
- **Receipt photos are not in backups.** The backup records the file name, but the picture itself
  stays on the phone. Restoring on another phone shows "Photo missing".
- **Automatic backup only runs when the app is opened or left**; there is no background service.
  If a folder becomes unavailable (an SD card removed, say) the status line says the backup failed.
- Not tested on a tablet, with TalkBack, with right-to-left text, or on Android versions other
  than 13.
- The automated tests run against the debug build; the shrunk release build was checked by hand only.

## Project layout

```
app/src/main/java/app/tripledger/
  data/      Room entities, DAOs, database and migration, Repository, defaults, backup/CSV,
             sample data, receipt photos, automatic backup
  domain/    Money maths, conversion, statistics, budgets, search filter, dates (pure Kotlin)
  ui/        Navigation, view models, theme, components, and one file per screen
  AddExpenseWidget.kt   the home-screen widget
app/src/test/         JVM unit tests
app/src/androidTest/  Room, migration and Compose UI tests
tools/                adb driver and emulator-only build/test scripts
screenshots/          first version (top level), second version (v2/), your phone (phone/)
```
