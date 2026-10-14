# Project status

As of 14 October 2026, after the second round of work.

## Summary

Everything from the first real-phone test is fixed and every suggested improvement is built,
except the Finnish translation (the app stays English, as asked) and the release build (waiting
for your go-ahead). All 150 automated tests pass. There are no known crashes or data bugs.

## The problems found on the phone

| Problem | What changed |
|---|---|
| The keyboard hid most of the category grid | A row of categories, most used first, now sits directly under the amount, above the keyboard. The full grid is still below it. |
| Dates were half Finnish, half English | All dates, month and weekday names are English, whatever the phone's language. Tested with the app set to Finnish. |
| Number format ignored the region | On first start (or after an upgrade, if never chosen) the number format and first day of week follow the phone's region: `1 234,56` and Monday on your phone. Anything you set yourself is kept. |
| Text cut short on the 360 dp screen | List rows use short dates (`5 Oct`) and drop the category when the title already says it; category names shrink to fit on one line; summary amounts and tab labels shrink rather than cut off. |
| The search filter panel filled the screen | Filters are now chips in one row (Category, Date, Amount), each opening a small sheet. Results keep the screen. |
| Predictive back was not enabled | Enabled. |

## The ideas, as built

**Quick wins:** quick category row; region defaults; "Discard?" prompt for unsaved expenses and
trips (on ✕ and on the Back gesture); "Expense deleted · Undo" after a delete; shorter list dates;
duplicate an expense (the copy is dated today); filter chips with sheets; predictive back.

**Features:**

- **Budget** per trip, for the whole trip or per day. "Budget left" and "Left today" cards turn red
  with "Over budget" / "Over today" when exceeded. Trip cards show the budget position too.
- **Spread an expense over several days** (with a "rest of the trip" button), and **leave an
  expense out of the daily average**. Per-day charts and "today" figures use the spread.
- **Exchange rate per expense**, overriding the trip's rate.
- **Refunds** (subtracted from totals) and **shared bills** (only "my share" counts; split 2, 3 or
  4 ways with one tap).
- **"Add expense" launcher shortcut and home-screen widget.**
- **Receipt photos** from the camera or the photo picker, stored privately on the phone,
  still with no permissions.
- **Statistics:** running total with the budget line, by payment method, by currency (with what
  was paid in each), and all trips compared.
- **Safer data:** automatic backups to a folder you choose (daily or weekly, newest 10 kept),
  import as merge or replace, and CSV import of files in the app's export format.

**Engineering:** tested at a second screen size and with a non-English language; accessibility
headings, merged rows and screen-reader actions for reordering; a side navigation rail and
centred content on landscape and wide screens; each expense is its own list row, so long trips
scroll smoothly; drag-and-drop reordering for categories and payment methods.

The database moved from version 1 to 2. The upgrade is tested automatically and was done for
real on your phone, where the existing data came through intact.

## Tests

82 JVM unit tests and 68 instrumented tests, all passing. The instrumented suite passed at 360 dp
and at the emulator's default size on the final code, and at 360 dp with the app in Finnish on a
build just before the last few layout tweaks.

## Not done, not verified, or imperfect

**Release**

- Version 1.0 is built, signed and installed on your phone, starting empty. See "The release
  build" in the README, and **back up the signing key in `release/`**: without it you cannot
  update the app on your phone without wiping its data.
- The release build was checked by hand on the emulator; the automated tests run on the debug build.

**Not verified**

- **The new features on your phone.** I checked the upgrade and startup on it, then stopped
  because you were using the phone. Everything else was verified on the emulator.
- **Themed (monochrome) icon** never seen with themed icons switched on.
- **CSV in Excel or Sheets** never opened; checked with a parser and by importing it back.
- **Automatic backup to cloud-backed folders** (Google Drive and similar providers in the folder
  picker) not tried; only the phone's own storage.
- **The camera on a real phone.** The camera flow was tested with the emulator's camera app.
  Real camera apps should behave the same, but that has not been seen.
- Tablets, TalkBack, right-to-left text, and Android versions other than 13.

**Known limitations**

- Receipt photos are not inside backup files; restoring on another phone shows "Photo missing".
- Automatic backup runs only when the app is opened or left, never in the background.
- CSV import reads only the layout this app exports (columns are matched by name, so reordered
  columns work); other apps' CSV files are rejected with a message naming the missing column.
- Merging matches trips by name, dates and home currency, so a trip renamed on one phone is
  merged as a separate trip.
- A spread expense counts fully in the total on its date's trip, even if its days run past the
  trip's end; the running-total caption says how much falls after the charted period.
- Undo is offered for deleted expenses only, not for deleted trips, categories or payment methods
  (those keep their confirmation dialogs).
- The widget is a single button; it does not show totals.
- Some screenshots predate small later fixes: `v2/12_auto_backup_dialog.png` shows the buttons
  side by side (now stacked), and `v2/07` and `v2/08` predate the running-total caption change.

## Further ideas, if you want them

- Include receipt photos in backups (as a zip).
- Show today's spend or budget left on the widget.
- Undo for deleted trips.
- A per-trip choice of which summary cards to show.
