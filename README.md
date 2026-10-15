
<h1 align="center">Kosh</h1>
<p align="center"><b>A private, offline-first personal finance tracker for Android, built for Indian banking.</b></p>

Kosh (Sanskrit: *treasury*) tracks income, expenses, credit cards, bills and investments. It reads bank
SMS automatically, and turns your data into insights, all on the phone.

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
