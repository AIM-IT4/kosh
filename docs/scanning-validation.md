# SMS scanning: release validation

This applies to both SMS-only editions. Gmail import and Google sign-in are removed.
Do not run `:core:mail` tasks. The [original PR notes and 109 JVM results](https://github.com/AIM-IT4/kosh/blob/e297408223db0ac5804622e5f7d22720bf85f326/docs/scanning-validation.md)
are historical evidence, not validation of this release.

## 1. Build one candidate

Use JDK 21, Android SDK 35 and the checked-in Gradle 8.9 wrapper. CI runs all unit
tests, builds both debug editions and instrumentation APKs, inspects the offline
APK's permissions, and runs database migration/encryption tests on API 26 and 35
emulators for both editions. These emulator tests do not replace real SMS tests.

```sh
./gradlew test :app:assembleStandardDebug :app:assembleOfflineDebug
./gradlew :app:connectedStandardDebugAndroidTest :app:connectedOfflineDebugAndroidTest
```

The connected tasks need an attached test device/emulator. They create isolated
test databases, migrate each exported schema 1–9 to 10, check saved account and
transaction data, and exercise plaintext-to-SQLCipher conversion and reopening.
Use an isolated test device; installing a debug edition cannot upgrade a release
installation signed with the Kosh key.

Run **Release candidate** for the intended source commit. It waits for the same
CI suite, builds both release editions without cached outputs, validates the
Kosh signer, application IDs, version metadata and offline permissions, then
uploads `kosh-release-candidate`. It does **not** create a public release or tag.
A version change on main builds a candidate only. Tag pushes do not publish.

For a local build, configure `keystore/kosh-release.jks` (alias `kosh`) and Gradle
properties `KOSH_STORE_PASSWORD` / `KOSH_KEY_PASSWORD`, then run:

```sh
./gradlew clean :app:assembleStandardRelease :app:assembleOfflineRelease --no-build-cache
```

A new binary release needs a new versionName/versionCode and matching changelog.
Candidate 2.6.2 uses code 35; 2.6.1/code 34 is already published. Missing signing
configuration can produce unsigned local APKs, which are not release candidates.

## 2. Verify and retain the exact APKs

Download the candidate artifact and retain its `candidate.json` and `SHA256SUMS`.
The manifest identifies the source SHA, workflow run, versions and each APK hash.

| Edition | APK | Application ID |
| --- | --- | --- |
| Standard | Kosh.apk | com.amitjha.kosh |
| Offline | Kosh-offline.apk | com.amitjha.kosh.offline |

Both APKs must verify with the existing certificate SHA-256:
`02283b8d09a5a1912906e9b5d3abed25ed4445de005f57a79cea0443f0317934`.
Use `scripts/release_checks.py apks <directory> --source <full-SHA> --run-id <run-ID>`
to recheck them. `aapt2` and `apksigner` failures are failures, not evidence of an
absent permission. Both require READ_SMS and RECEIVE_SMS; offline must not request
INTERNET. Preserve artifact hashes when copying to a distribution mirror.

## 3. Physical-device checks, for each edition

Use physical API 26 and API 35+ devices and the intended installer. Copy
`release-validation.example.json` to a new evidence file. Fill in the actual
source/run IDs, hashes, device, Android API, installer, tester, date and previous
installed version code. The template is pending and cannot authorize publishing.

1. **fresh_scan:** Fresh install, grant SMS during onboarding, finish the initial
   scan and verify expected bank transactions, account balances and bills.
2. **denial_recovery:** Deny and repeatedly deny SMS. More → Settings → Bank SMS →
   Scan inbox now must offer recovery. Grant in app settings and return; the
   requested scan must start.
3. **return_without_grant / manual_entry:** Return without granting, verify the
   help remains available, dismiss it and confirm manual entry still works.
4. **restricted_installer / split_permissions:** Check installer-restricted access
   where available and explain unavailable cases. Test READ_SMS without RECEIVE_SMS
   and the reverse if the device allows separate grants. Inbox-only access must
   permit scanning but show that live import remains blocked. Full access clears
   recovery help. No READ_SMS must not be presented as a successful inbox scan.
5. **live_import:** Enable automatic import, receive a representative bank SMS
   while the app is in the background, and confirm it is imported once.
6. **missed_sms_catch_up / no_duplicates:** Deliberately miss a live delivery (for
   example force-stop on a test device, receive the SMS, then reopen). Confirm it
   is caught up and repeated scans do not duplicate it. Foreground catch-up is
   throttled to once per minute; background scheduling is not an exact timer.
7. **empty_inbox_catch_up:** On a fresh test profile with no inbox messages, complete
   the initial scan, then miss a new bank SMS and reopen. Catch-up must work without
   requiring a manual scan first. Never clear someone's real SMS for this test.
   Also upgrade an older completed install whose empty scan left its timestamp
   unset; reopening must recover automatically.
8. **revoke_regrant:** After a successful scan revoke access. The next manual scan
   or catch-up must report the problem; regrant access and verify recovery without
   losing imported data. Failed reads/writes must not advance past unsaved messages.
9. **upgrade_data_preserved:** Export a backup, verify the installed edition's
   application ID and signer, then install the signed candidate over an older
   version without uninstalling. Verify accounts, transactions, bills, settings,
   imported fingerprints and manual entries survive; rescan without duplicates.
   Cover applicable schema migrations and reopen the encrypted database. The
   automated migration suite does not establish installer or Keystore continuity.

Record `pass` only after observing success. Only the two conditional checks may
use `not_available`, with a specific explanation in `exceptions`. All other
checks must pass on both device classes and both editions. Never record emulator
runs as physical devices. Do not uninstall a real installation without a backup.

## 4. Publish the validated candidate

Commit the completed evidence file. Run **Publish validated release** with the
candidate run ID and that file's path on the selected workflow ref. It checks the
candidate run succeeded (including CI and migrations), downloads its original
APKs, rechecks signatures/package/version/permissions, and requires matching
physical-device evidence for both hashes. It publishes those same bytes and the
evidence with a tag pointing to the tested source SHA. Existing releases cannot
be overwritten; make a new version instead. External `release-files` branches no
longer bypass verification.

For F-Droid, also follow [FDROID.md](FDROID.md): reproducibility and channel recipe
updates are separate gates. The checked-in recipes describe the last published
release, not an unvalidated candidate. Any code change after testing requires a
new candidate and evidence. Google/Gmail checks remain out of scope.
