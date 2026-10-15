
<h1 align="center">Kosh</h1>
<p align="center"><b>A private, offline-first personal finance tracker for Android, built for Indian banking.</b></p>

Kosh (Sanskrit: *treasury*) tracks income, expenses, credit cards, bills and investments. It reads bank
SMS automatically, and turns your data into insights, all on the phone.

<p align="center"><img src="docs/screenshots/hero.png" alt="Kosh app screens" width="100%"></p>

## Screens

<table>
<tr>
<td align="center"><img src="docs/screenshots/home.png" width="230"><br><sub><b>Home</b>: net worth, month, spending pace</sub></td>
<td align="center"><img src="docs/screenshots/charts.png" width="230"><br><sub><b>Charts</b>: income vs spending, daily heatmap</sub></td>
<td align="center"><img src="docs/screenshots/insights.png" width="230"><br><sub><b>Insights</b>: monthly score and advice</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/activity.png" width="230"><br><sub><b>Activity</b>: SMS imports, filters, review</sub></td>
<td align="center"><img src="docs/screenshots/add.png" width="230"><br><sub><b>Add</b>: calculator keypad entry</sub></td>
<td align="center"><img src="docs/screenshots/goals.png" width="230"><br><sub><b>Goals</b>: monthly saving, optional loan</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/budgets.png" width="230"><br><sub><b>Budgets</b>: pace and daily allowance</sub></td>
<td align="center"><img src="docs/screenshots/wealth.png" width="230"><br><sub><b>Wealth</b>: FD, EPF, NPS, savings interest</sub></td>
<td align="center"><img src="docs/screenshots/networth.png" width="230"><br><sub><b>Net worth</b>: how every number is calculated</sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/bills.png" width="230"><br><sub><b>Card bills</b>: due dates and reminders</sub></td>
<td align="center"><img src="docs/screenshots/dark.png" width="230"><br><sub><b>Dark mode</b></sub></td>
<td align="center"><img src="docs/screenshots/settings.png" width="230"><br><sub><b>Settings</b>: app lock, privacy, backup</sub></td>
</tr>
</table>

<sub>Screens are rendered from the app's own Compose UI with sample data (see the Paparazzi tests in <code>app/src/test</code>).</sub>

## Features

- **Automatic import:**
  - **Bank SMS:** ICICI, HDFC, Kotak and DBS messages, with a generic parser for other banks. New messages are picked up live, de-duplicated and sorted into categories.
  - **Credit-card bills:** read from card SMS, with due-date reminders.
  - **Review inbox:** confirm or fix imported transactions. Recategorising a merchant can remember that category for future imports.
- **Accounts and cards:** balances that reconcile with what bank SMS report, card outstanding and utilisation, and "mark paid" recording the transfer from your bank account.
- **Investments that update themselves:**
  - **FD:** interest accrues daily with quarterly compounding.
  - **EPF:** monthly contributions plus EPFO-style interest.
  - **NPS:** valued from live NAVs.
  - **Net worth:** tracked over time.
- **Planning:** monthly budgets with pace projection, and goals with the monthly saving they need.
- **Insights:** 17 offline rules, including budget pace, spending spikes, savings rate, subscriptions, card utilisation, emergency-fund cover, FD maturity and unusual spends.
- **Privacy:**
  - **Biometric lock and privacy mode** that hides amounts.
  - **Local-only data:** it stays on the device and is excluded from Google's cloud backup.
  - **Encrypted backups:** AES-256-GCM with a password-derived key.

## Architecture

| Module | Contents |
| :-- | :-- |
| `core:model` | Money (paise, Indian formatting), shared types |
| `core:sms` | Bank SMS parsers, merchant normalisation, category guessing |
| `core:finance` | FD/EPF/NPS valuation, NAV client, budgets and goals, insight engine |
| `core:designsystem` | Compose theme, charts, components, logo |
| `app` | Room database, repositories, SMS import, WorkManager jobs, UI screens |

Kotlin, Jetpack Compose with Material 3, Room, WorkManager, DataStore and Google Identity Services.
The engine modules are plain Kotlin with unit tests.

## Building

```bash
./gradlew test             # unit tests for every module
./gradlew :app:assembleRelease
```

Release signing reads `keystore/kosh-release.jks` and the `KOSH_STORE_PASSWORD` / `KOSH_KEY_PASSWORD`
Gradle properties from `~/.gradle/gradle.properties`. Neither is committed.
