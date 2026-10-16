# Publishing Kosh on F-Droid

Kosh has no proprietary dependencies (no Google Play Services, Firebase or trackers), so it qualifies for
the main F-Droid repository. F-Droid builds the app from source and signs it with its own key.

## Before you submit

1. **Make the GitHub repository public** (`gh repo edit AIM-IT4/kosh --visibility public`). F-Droid can only
   build from public source.
2. **Release first**: bumping `versionName`/`versionCode` on main makes the Release workflow tag `v<versionName>`
   and attach `Kosh.apk` and `Kosh-offline.apk`. Update the build entry in `metadata/com.amitjha.kosh.yml` (and the
   `.offline.yml` copy) to that version. After inclusion F-Droid picks up new tags on its own.
3. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for that release. F-Droid shows the
   title, descriptions, icon and screenshots from `fastlane/metadata/android/en-US/`.

## Submitting

1. Create an account on [GitLab](https://gitlab.com) and fork
   [fdroid/fdroiddata](https://gitlab.com/fdroid/fdroiddata).
2. In your fork, add `metadata/com.amitjha.kosh.yml` (copy from this repository's `metadata/` folder).
   Optionally add `metadata/com.amitjha.kosh.offline.yml` for the offline edition as a second app.
3. Optional local check (needs Docker or Linux):
   `fdroid readmeta && fdroid lint com.amitjha.kosh && fdroid build -v -l com.amitjha.kosh`.
4. Commit with the message `New app: Kosh` and open a merge request against `fdroid/fdroiddata`.
   Fill in the template; mention that the app reads SMS to import bank transactions and that all
   processing is on-device.
5. Reviewers may ask questions in the merge request; answer them there. After it's merged, the app appears
   in F-Droid within a few days, and new tags are picked up automatically (`UpdateCheckMode: Tags`).

## Notes

- **Signing (reproducible builds):** the recipes set `Binaries` (the APK on the GitHub release) and
  `AllowedAPKSigningKeys` (the Kosh release key). F-Droid builds the tag from source, checks that its unsigned
  APK is identical to the release APK, and then publishes the release APK with the Kosh signature. F-Droid and
  GitHub updates therefore install over each other. If a rebuild ever differs, F-Droid publishes nothing for
  that version, so check before tagging:

  ```
  ./gradlew :app:assembleStandardRelease        # without keystore/ → unsigned APK
  apksigcopier compare Kosh.apk --unsigned app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk
  ```

  Builds with JDK 17 and 21 produce the same APK. Native libraries are never stripped (`keepDebugSymbols`), so
  the NDK on the build machine doesn't matter. Release builds skip the Gradle build cache (`--no-build-cache`):
  a cached Kotlin compile can differ from a clean one (Compose stability markers), and F-Droid always builds
  clean.
- **Anti-features:** none expected. The standard edition fetches public NPS prices from npsnav.in; the offline
  edition never uses the network.
