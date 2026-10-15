# Publishing Kosh on F-Droid

Kosh has no proprietary dependencies (no Google Play Services, Firebase or trackers), so it qualifies for
the main F-Droid repository. F-Droid builds the app from source and signs it with its own key.

## Before you submit

1. **Make the GitHub repository public** (`gh repo edit AIM-IT4/kosh --visibility public`). F-Droid can only
   build from public source.
2. **Tag a release that contains the flavours**, e.g. `v1.9.0`, and update `versionName`, `versionCode` and
   `commit` in `metadata/com.amitjha.kosh.yml` (and the `.offline.yml` copy) to match.
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

- **Signing:** F-Droid signs with its own key, so the F-Droid build can't be installed over a GitHub APK (and
  the reverse). Users should pick one source, or use an encrypted backup to move between them.
- **Anti-features:** none expected. The standard edition fetches public NPS prices from npsnav.in; the offline
  edition never uses the network.
