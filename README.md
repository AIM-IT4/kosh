
<h1 align="center">Kosh</h1>
<p align="center"><b>A private, offline-first personal finance tracker for Android, built for Indian banking.</b></p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-059669" alt="GPL-3.0"></a>
  <img src="https://img.shields.io/badge/price-free-111111" alt="Free">
  <img src="https://img.shields.io/badge/trackers-none-111111" alt="No trackers">
  <img src="https://img.shields.io/badge/Android-8.0%2B-111111" alt="Android 8.0+">
</p>

**Free and open source.** No ads, no account, no servers, no analytics. Kosh never sells data or loans,
and anyone can read the code to check that your SMS and finances never leave the phone.

Kosh (Sanskrit: *treasury*) tracks income, expenses, credit cards, bills and investments. It reads bank
SMS automatically, and turns your data into insights, all on the phone.

## Download

**[Download Kosh for Android](https://github.com/AIM-IT4/kosh-site/releases/latest/download/Kosh.apk)**:
the standard edition, Android 8.0+.
[Kosh-offline.apk](https://github.com/AIM-IT4/kosh-site/releases/latest/download/Kosh-offline.apk) has no
internet permission at all. Website: [aim-it4.github.io/kosh-site](https://aim-it4.github.io/kosh-site/).

Kosh isn't on the Play Store, so Android may ask you to allow the install. If Play Protect blocks it
(it flags every app that reads SMS), pause scanning in **Play Store → Play Protect → ⚙**, install,
then turn scanning back on.

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
  - **Bank SMS:** ICICI, HDFC, Kotak, DBS, SBI and Axis messages in detail; PNB, Bank of Baroda, Canara, Union Bank, IDFC FIRST, IndusInd, Yes Bank, AU Small Finance Bank and Federal Bank recognised by sender; and a generic parser for other banks. UPI AutoPay, e-mandate/NACH and standing-instruction debits are marked as auto-debits. New messages are picked up live, de-duplicated and sorted into categories.
  - **Credit-card bills:** read from card SMS, with due-date reminders.
  - **Family members:** their bank SMS too, without Kosh on their phone. They scan a QR code once (it opens their Messages app with a link code addressed to you), then forward their bank alerts; Kosh files them under their own accounts and, if you choose, counts them in your totals.
  - **Review inbox:** confirm or fix imported transactions. Recategorising a merchant can remember that category for future imports.
- **Accounts and cards:** balances that reconcile with what bank SMS report, card outstanding and utilisation, and "mark paid" recording the transfer from your bank account.
- **Investments that update themselves:**
  - **FD:** interest accrues daily with quarterly compounding. Payout FDs show every interest credit (monthly, quarterly, half-yearly or yearly; monthly at the bank's discounted rate), the next one's date and amount, and tick each off against your bank SMS.
  - **EPF:** monthly contributions plus EPFO-style interest.
  - **NPS:** valued from live NAVs.
  - **Net worth:** tracked over time.
- **Savings interest:** estimated daily on each account's closing balance, using each bank's verified rate, with tiered slabs (e.g. DBS).
- **Recurring & subscriptions:** repeating payments found in your spending (subscriptions, EMIs, UPI AutoPay / NACH mandates), what's due in the next 30 days, price-change alerts, and scheduled entries (rent, SIPs) that add themselves on their dates.
- **Loans & EMIs:** amortisation schedule, what's still owed (counted in net worth), and a prepayment calculator (fewer EMIs or a lower EMI). Home-loan interest and principal flow into the tax view under 24(b) and 80C.
- **Tags, splits and IOUs:** tag transactions (`#goa-trip`), split one payment across categories, and track money lent to or borrowed from people.
- **Receipts and warranties:** attach a bill photo or PDF to a transaction, encrypted on the phone, with a reminder before a warranty ends.
- **Reports:** monthly and yearly PDF reports, a year-in-review, and a calendar of daily spending and upcoming payments.
- **Bank statement import:** CSV statements from net banking (HDFC, ICICI, SBI, Axis, Kotak and others), skipping rows already imported from SMS.
- **Planning:**
  - **Safe to spend:** what you can spend each day until payday, after card bills and repeating payments due before it.
  - Monthly budgets that follow your salary cycle, with a pace projection, optional rollover of unspent money, and an envelope (zero-based) mode.
  - Goals with the monthly saving they need, an optional loan at purchase (EMI and total interest), and a **success probability** from a Monte Carlo simulation ("82% chance by Mar 2029"), with the saving needed for 80% confidence.
- **India tax view:** old vs new regime comparison with the current slabs. Deductions are filled from your data where possible: 80C, 80CCD(1B) NPS, 80D, 80TTA savings interest, HRA and FD interest.
- **Export:** CSV (opens in Excel), Beancount and Ledger, so your data is never locked in.
- **Everyday tools:** home-screen widgets (this month, quick add), a quick-add tile in Quick Settings, and an optional daily digest notification.
- **Household sync (beta):** pair two phones with a QR code and share household transactions, budgets and goals over the same Wi-Fi, end-to-end encrypted, with no cloud. See [docs/SYNC_PROTOCOL.md](docs/SYNC_PROTOCOL.md).
- **Insights:** 17 offline rules, including budget pace, spending spikes, savings rate, subscriptions, card utilisation, emergency-fund cover, FD maturity and unusual spends.
- **Privacy and security:**
  - **Encrypted database:** SQLCipher (AES-256), with the key kept in the Android Keystore.
  - **Offline edition:** a build with no INTERNET permission at all, so Android itself guarantees nothing can be sent.
  - **Biometric lock, privacy mode and screenshot blocking.**
  - **Decoy PIN:** opens an empty profile, never your real data.
  - **Local-only data:** it stays on the device and is excluded from Google's cloud backup.
  - **Encrypted backups:** AES-256-GCM with a password-derived key (PBKDF2, 600k iterations).
  - **Automatic backups:** daily or weekly encrypted backups to a folder you pick (a cloud-synced folder keeps a copy off the phone), keeping the newest few.
- **Languages:** English and Hindi (हिन्दी).

## Architecture

| Module | Contents |
| :-- | :-- |
| `core:model` | Money (paise, Indian formatting), shared types |
| `core:sms` | Bank SMS parsers, merchant normalisation, category guessing |
| `core:finance` | FD/EPF/NPS valuation, NAV client, budgets and goals, insight engine |
| `core:designsystem` | Compose theme, charts, components, logo |
| `app` | Room database, repositories, SMS import, WorkManager jobs, UI screens |

Kotlin, Jetpack Compose with Material 3, Room with SQLCipher, WorkManager and DataStore. No Google Play Services.
The engine modules are plain Kotlin with unit tests.

## Building

```bash
./gradlew test                          # unit tests for every module
./gradlew :app:assembleStandardRelease  # standard edition (can fetch public NPS prices)
./gradlew :app:assembleOfflineRelease   # offline edition (no INTERNET permission)
```

The two editions have different application IDs (`com.amitjha.kosh` and `com.amitjha.kosh.offline`), so both
can be installed side by side. CI builds both on every push (see `.github/workflows/ci.yml`).

Release signing reads `keystore/kosh-release.jks` and the `KOSH_STORE_PASSWORD` / `KOSH_KEY_PASSWORD`
Gradle properties from `~/.gradle/gradle.properties`. Neither is committed.

Pushing a version tag (`git tag v2.0.1 && git push origin v2.0.1`), or running **Release** from the Actions tab
(which tags the commit with `v<versionName>`), runs `.github/workflows/release.yml`, which
builds both editions with the release key and publishes them as a GitHub release (`Kosh.apk`, `Kosh-offline.apk`).
It reads the key from the `KOSH_KEYSTORE_BASE64`, `KOSH_STORE_PASSWORD` and `KOSH_KEY_PASSWORD` repository secrets.

## Contributing

Contributions are welcome, especially **bank SMS formats** and **merchant categories**. The merchant rules
live in an open dataset, [`core/sms/src/main/resources/kosh/merchants.json`](core/sms/src/main/resources/kosh/merchants.json)
(format in [docs/DATASET.md](docs/DATASET.md)). See [CONTRIBUTING.md](CONTRIBUTING.md), and always mask
account numbers before sharing an SMS. Security issues: see [SECURITY.md](SECURITY.md).

## License

Kosh is free software under the [GNU General Public License v3.0 or later](LICENSE).
