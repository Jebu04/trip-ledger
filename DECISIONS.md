# Decisions made while you were asleep

Things that were ambiguous or needed a judgement call, and what I picked.

## The brief

- **No screenshot arrived.** The message mentioned an attached screenshot but none came through. I
  worked from the written description (day-grouped list, coloured round category icons, home
  amount with the original amount underneath, Total and Daily Average cards, a + button, bottom
  navigation). If the look is off from what you had in mind, that is why.
- **Name and identity.** The app is called **Trip Ledger** (`app.tripledger`), with its own icon
  and a teal default accent. No TravelSpend branding, no paywall, unlimited trips.

## Toolchain

- **Versions.** I used what was already cached and working on this machine: Gradle 9.6.0,
  Android Gradle Plugin 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00, plus Room 2.8.5 and KSP 2.3.12.
- **compileSdk 37, targetSdk 33.** Current AndroidX releases refuse to build against anything
  below API 37, so the app *compiles* against 37 but *targets* Android 13 (API 33) as you asked,
  and was tested on an API 33 emulator. minSdk is 26.
- **Emulator.** There was no API 33 image or AVD. I installed
  `system-images;android-33;google_apis;x86_64` and created the AVD `tripledger_api33` (Pixel 6).
  Your existing `wikidev` AVD is untouched.
- **Your phone.** A physical device (`06c11c66`) was attached over adb when I started. Every adb
  command and both helper scripts are pinned to `emulator-5554`, and I ran the instrumented tests
  through `adb -s emulator-5554` instead of Gradle's `connectedAndroidTest` (which installs on
  every attached device). Nothing was installed on the phone.
- **No dependency-injection framework.** The app has one `Repository`; the `Application` class
  holds it. Hilt would have added build time and risk for no benefit at this size.

## Money and exchange rates

- **Integer minor units everywhere.** Amounts are `Long` counts of the currency's smallest unit.
  Parsing, conversion and averaging use `BigDecimal`; there is no `Float` or `Double` in any money
  path. (Category percentages are integer tenths of a percent.)
- **Rate direction.** A rate means "1 home unit = X local units" (1 EUR = 37.85 THB), which is how
  rates are quoted at an exchange counter. Home amount = local ÷ rate, rounded half-up.
- **Rates are stored as the text you typed** (e.g. `37.85`), not as a floating-point number.
- **Rates belong to the trip, and changing one re-values the whole trip.** Entries store their
  original amount and currency only. I did not snapshot the rate onto each entry: with hand-typed
  rates, "I fixed a typo in the rate and everything corrected itself" seemed more useful than
  "old entries keep the wrong rate". The cost: you cannot have two different rates for the same
  currency within one trip.
- **Totals are the sum of individually rounded entries**, so day subtotals always add up to the
  trip total exactly.
- **Missing or zero rate.** The entry is kept and shown in its own currency marked "No rate", it
  is left out of totals and statistics, and a banner on the entries screen says how many entries
  are excluded and links to the rate field. Nothing is silently counted as zero.
- **Typed amounts.** Either `.` or `,` works as the decimal separator regardless of the number
  format setting; extra decimals are rounded half-up; an amount must be greater than zero
  (no negative entries/refunds).
- **Currency decimals.** JPY, KRW, VND, IDR, ISK and CLP have 0 decimals, KWD has 3, the rest 2.
  A currency's decimals cannot be changed after it exists, because stored amounts depend on it.
- **No rates are shipped.** The 43 built-in currencies come without rates, since any number I
  baked in would be stale and you said rates are entered by hand.

## Daily average

- Total ÷ days, where the period runs from the trip's start to today (capped at the trip's end),
  and is widened to include any entry dated outside it (a flight booked weeks earlier counts from
  its date). Minimum one day. In Statistics with a date filter, it is the total ÷ days in the range.

## Settings

- **"Home currency" in Settings is the default for new trips.** Each trip keeps its own home
  currency (editable in the trip), so changing the setting never re-values an existing trip. On
  first launch it is taken from the device locale (EUR if unknown).
- **"Currency list and exchange rates"** is two things: the global currency list (add your own,
  rename, set a *default* rate that pre-fills when you add the currency to a trip) and a shortcut
  to the current trip's actual rates.
- **Default currency** defaults to "last used on the trip", which is fastest in practice.
- **Reordering uses up/down arrows**, not drag-and-drop.
- **Deleting a category** asks which category its expenses should move to; the last category
  cannot be deleted. **Deleting a payment method** keeps the expenses, without a method.
- **First day of week** drives the date picker (written by hand for this reason: the Material
  date picker ignores such a setting) and the "This week" statistics range.
- **Summary cards**: Total and Daily average (on by default), plus Today and Number of entries.
- **Accent colour** is a palette of ten; the light and dark colour schemes are derived from it.
- **Extra: "Add sample trips"** in Settings and on the welcome screen. That is how the demo data
  on the emulator was loaded.

## Backup and export

- **Backup is one JSON file** written and read through the system file picker, so the app needs
  no storage permission. **Import replaces everything** (after a confirmation) rather than merging,
  and an invalid file changes nothing.
- **CSV** has one row per entry across all trips with ISO dates and plain `.` decimals (so
  spreadsheets parse it regardless of your display settings), a UTF-8 byte-order mark for Excel,
  and text that starts with `=`, `+`, `-` or `@` is prefixed with `'` so it cannot run as a formula.
- **Android cloud backup is switched off** (`allowBackup=false`): you asked for all data to stay
  on the device.

## Tests

- **Room DAO tests are instrumented**, running against an in-memory SQLite database on the
  emulator, rather than JVM tests with Robolectric. They exercise the real database engine.
- **UI tests wipe the app's database** before each test. That is why sample data has to be
  re-loaded after running them (see README).
- I changed test code several times while getting the UI tests to pass, always to make the
  *selector or gesture* match what a user does (scrolling a control out from behind the keyboard,
  reading text that Compose merges into its parent row). No assertion was removed or loosened.
  Where a failing test pointed at a real weakness, I changed the app instead; those are listed in
  the README.

## Second round (fixes from the phone test and the improvement ideas)

- **English only.** All dates, month and weekday names are formatted in English regardless of the
  phone's language. Only the number format and first day of week follow the phone's region, and
  only until you choose them yourself.
- **Region defaults also apply after an upgrade**, as long as the setting was never chosen. That is
  why your phone switched to `1 234,56` after the update.
- **Budget** is stored per trip in home minor units, either for the whole trip or per day. A
  per-day budget times the trip length gives the trip budget; a trip budget divided by the length
  gives "Left today".
- **Shared bills** store the full amount and "my share" separately; totals, statistics and search
  use my share. **Refunds** are stored as negative amounts and shown as `-€5.00`.
- **Spreading** divides the home amount evenly over the days, giving leftover cents to the first
  days so the parts always add up. The total still counts the expense once.
- **A per-expense rate** overrides the trip rate for that expense only. CSV import keeps a file's
  rate as a per-expense rate when it differs from the trip's.
- **Receipt photos** are shrunk to at most 1600 px and stored in the app's private storage. The
  camera writes through a FileProvider and the gallery uses the system photo picker, so the app
  still requests no permissions. Photos are not put inside backup files (kept small and text-only).
- **Automatic backup** runs when the app is opened or left, not in the background: a background
  scheduler would need WorkManager and still can't promise timing. One file per day, newest 10 kept.
- **Merge import** matches trips by name, start and end date and home currency, and categories and
  payment methods by name; expenses already present are skipped, so merging twice is harmless.
- **Undo** is offered for deleted expenses through a snackbar. Trips and categories keep their
  confirmation dialogs.
- **A duplicate** is dated today and does not copy the receipt photo.
- **Wide screens** (landscape, tablets) get a side navigation rail and content capped at 720 dp wide.

## Release build

- **"Empty from the presets"** (your answer): release builds start with no trips and hide the
  sample-trips options; the default categories, payment methods and currency list stay, so the
  app is usable straight away. Debug builds still offer sample trips for testing.
- **Signing key** generated for this app (RSA 4096, valid about 27 years), stored in `release/`
  outside git together with a random password. Losing it means future updates can't be installed
  over the existing app.
- **Code and resource shrinking on**, with line numbers kept in stack traces.
