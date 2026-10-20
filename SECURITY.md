# Security policy

## Reporting a vulnerability

Please **don't open a public issue** for security problems. Email **jha.8@alumni.iitj.ac.in** with:

- what you found and how to reproduce it,
- the Kosh version and edition (standard or offline) and your Android version.

You'll get a reply within a few days. Once a fix is released, you'll be credited if you wish.

## Threat model (summary)

Kosh holds sensitive financial data, read from bank SMS and entered by the user. It is designed so that
this data never leaves the phone.

**Protections**

- **No servers or accounts:** Kosh has no backend, no analytics and no third-party SDKs.
- **Network:** the standard edition only fetches public NPS NAVs and, once a day, the latest release number from
  the GitHub API (no personal data in either request; the update check is off for app-store installs and can be
  turned off in More › Updates). The offline edition has no INTERNET permission at all.
- **Encryption at rest:** the database is encrypted with SQLCipher (AES-256). Its key is random, generated on
  the device and stored wrapped by a non-exportable AES-GCM key in the Android Keystore.
- **Backups:** user-initiated exports are encrypted with AES-256-GCM and a PBKDF2-SHA256 key (600,000
  iterations) from a password of at least 8 characters. Android cloud backup and device transfer are disabled
  for the database and settings.
- **On the device:** optional biometric/device-credential app lock that re-locks after 60 seconds in the
  background, screenshot and recents-preview blocking, privacy mode, and lock-screen notifications that hide
  amounts and merchants.
- **SMS:** the SMS receiver only accepts broadcasts from the system (`BROADCAST_SMS` permission).
- **Family forwarding:** a message from a personal number is read as a family member's bank SMS only after that
  number sent the member's 6-digit link code (shown in the QR code on the user's phone) or the user typed it in.
  Forwarded messages only ever match that member's accounts, never the user's own, and the user can stop reading
  a number at any time (which also changes the code).

**Out of scope**

- A device that is already compromised (rooted with malware, or unlocked and in an attacker's hands while
  Kosh is unlocked).
- Loss of the Android Keystore key (e.g. a factory reset): the encrypted database then can't be recovered.
  Keep an encrypted backup.
