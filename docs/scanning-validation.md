# SMS scanning: release validation

This checklist applies to the current SMS-only app. Gmail import and Google
sign-in were removed after PR #1; do not run its `:core:mail` tasks or treat its
Gmail checks as current release requirements. The original PR notes and their
109 JVM test results are preserved at
[the PR commit](https://github.com/AIM-IT4/kosh/blob/e297408223db0ac5804622e5f7d22720bf85f326/docs/scanning-validation.md).
They are historical evidence, not validation of a later release.

## Build and test the release candidate

Record the commit SHA, Android versions and installer used. Run with the Android
SDK and JDK required by the project:

```sh
./gradlew test
./gradlew :app:assembleStandardDebug :app:assembleOfflineDebug
./gradlew :app:assembleStandardRelease :app:assembleOfflineRelease --no-build-cache
```

CI runs the tests, builds both debug editions and checks that the offline APK has
no INTERNET permission. The release workflow builds both editions with the
release key, checks their signer and the offline permission, and publishes them.
A successful workflow does not establish that the device checks below passed.
For local release builds, configure the existing Kosh release key and passwords;
verify both APKs' signatures and that the offline APK has no INTERNET permission.

Before an upgrade test, export a backup. Check the application ID and signing
certificate against the installed edition, and check version-code requirements
for the intended distribution channel. Install the signed candidate over the
existing app without uninstalling. Confirm that accounts, transactions, settings
and previously imported data survive, and exercise any applicable database
migrations. The standard and offline editions have different application IDs.

## Physical-device checks

Use representative Android versions and the intended installation method. Record
pass/fail, device and Android version; do not mark a release ready based only on
an emulator, a build or a previous release's result.

1. On a fresh install, allow SMS during onboarding. Confirm the initial inbox
   scan completes and expected bank transactions and bills are displayed.
2. Deny SMS, including repeated denial. From More → Settings → Bank SMS, choose
   **Scan inbox now** and confirm the recovery help appears. Open app settings,
   grant SMS access and return; confirm the requested scan starts.
3. Return from settings without granting access. Confirm recovery help remains
   available, dismiss it, and verify manual entry and the rest of the app work.
4. Check a restricted-permission installation where available. If Android or the
   installer blocks SMS access, verify the guidance is understandable and
   investigate the installer policy. Do not uninstall before exporting a backup:
   app data is local.
5. With automatic import enabled, receive a representative bank SMS and confirm
   live import. Verify READ_SMS and RECEIVE_SMS separately if the device permits
   them to differ. Check that a missed message is caught up and that subsequent
   scans do not duplicate it.
6. Revoke SMS access after a successful scan. Confirm the next scan or catch-up
   reports the problem and that granting access again recovers without data loss.

Gmail batching, token refresh, concurrent Gmail scans and Google sign-in are
outside this checklist unless those features are deliberately restored in the
release candidate. If restored, review and test the historical failure paths
against the new implementation before release.
