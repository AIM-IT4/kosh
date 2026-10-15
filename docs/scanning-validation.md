# SMS and Gmail scan fixes

## Automated validation

The core model, SMS, and mail sources and tests were compiled with Kotlin 2.0.21
on JDK 17 and run with JUnit 4.13.2: **109 tests passed**. Eight new regression tests
cover pagination past processed messages, batch limits/progress, interrupted
connections, persistent server errors, deleted messages, cancellation, partial
results at the scan deadline, and repeated pagination tokens.

Android app compilation, Compose UI tests, and physical-device verification have
not been run in the editing environment. Before releasing, run:

```sh
bash gradlew :core:mail:test :app:compileDebugKotlin :app:testDebugUnitTest
bash gradlew :app:assembleRelease
```

Use the existing release signing key to update an installed copy. This change does
not change signing, application ID, database schema, or version code.

## Device checks before release

1. Fresh install: allow SMS in onboarding, then scan and confirm inbox results.
2. Deny SMS, including repeated denial: try Scan inbox now. Confirm recovery help
   appears. Choose Open app settings, grant SMS, and return. The scan should start.
3. Return from settings without granting SMS: confirm help remains available and
   dismissing it leaves the rest of the app usable.
4. Android or the installer can restrict SMS permissions. If SMS cannot be enabled
   in app settings, investigate the installation policy; app code cannot grant
   itself restricted access. Do not uninstall as a troubleshooting step without
   first exporting a backup, since data is local.
5. Connect Gmail while denying SMS: onboarding must still import Gmail and display
   its results/errors. The activity label must switch from SMS to Gmail.
6. Large mailbox: each scan downloads up to 50 new matching messages. Verify the
   progress counter, continuation message, and that another sync skips saved IDs
   and reaches older pages.
7. Turn off network during Gmail sync: verify a visible failure and that completed
   messages are saved. Restore network and retry; no duplicate imports should appear.
8. Expire/revoke Gmail authorization: verify refresh/reconnect or a useful error,
   and that loading ends. A silent Google authorization attempt times out after
   20 seconds. The fetch stage stops starting new HTTP requests after 60 seconds;
   an in-flight call can additionally use its existing 15-second connect and
   30-second read timeouts. This is not a strict 60-second wall-clock guarantee.
9. Trigger a second sync while a background scan runs: verify the app reports an
   existing scan rather than running a second import concurrently.
