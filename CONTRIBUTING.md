# Contributing to Kosh

Thanks for helping make Kosh better. Kosh is a privacy-first app, so a few rules matter more than usual.

## Never share real financial data

Before pasting any SMS into an issue, pull request or test:

- replace **account and card numbers** with `X` (`A/c XX1234` → `A/c XXXXXX`),
- replace **phone numbers, UPI IDs of people, names of people and reference numbers** with placeholders,
- keep the **bank wording, amounts format, dates format and merchant names**: that's what the parser needs.

The app's **Fix uncategorised → Copy masked SMS samples** button does most of this for you; still read the
result before sharing.

## Development setup

1. Install Android Studio (it includes a JDK 17+ and the Android SDK).
2. Clone the repository and open it in Android Studio, or build from the command line:

   ```bash
   ./gradlew test                          # all unit tests (pure Kotlin modules and the app)
   ./gradlew :app:assembleStandardDebug    # standard edition
   ./gradlew :app:assembleOfflineDebug     # offline edition (no INTERNET permission)
   ```
3. Screenshot tests use Paparazzi: `./gradlew :app:verifyPaparazziStandardDebug` (re-record with
   `recordPaparazziStandardDebug` when a UI change is intentional).

Modules: `core:model` (money, types), `core:sms` (SMS parsing and categories), `core:finance` (investments,
planning, insights), `core:designsystem` (Compose UI kit) and `app`.

## Adding a bank SMS format

1. Add a masked sample to a test in `core/sms/src/test/...` (see `BankMessagesTest`) with the expected amount,
   type, account digits, merchant and date.
2. Make it pass in `core/sms/src/main/.../BankParsers.kt` or the generic parser.
3. Run `./gradlew :core:sms:test`.

No code? Open a **"Bank SMS format"** issue with masked samples instead.

## Adding or fixing merchant categories

Edit [`core/sms/src/main/resources/kosh/merchants.json`](core/sms/src/main/resources/kosh/merchants.json)
(format in [docs/DATASET.md](docs/DATASET.md)) and run `./gradlew :core:sms:test`. The tests check that every
category is valid, every pattern compiles and no brand appears twice.

## Pull requests

- Keep each pull request focused on one change and describe what it fixes.
- Add or update tests for behaviour changes; money logic always needs a test.
- No new trackers, analytics, ads or network calls. Kosh adds no dependency on Google Play Services, so
  it can be built and distributed on F-Droid.
- By contributing you agree your work is licensed under the GPL-3.0-or-later.
