# Household sync protocol (v1)

Household sync lets two phones on the same Wi-Fi share the transactions, budgets and goals each user
marks as **Household**. It needs no server, no account and no internet. The data travels encrypted
end to end over a short-lived TCP connection between the two phones. The feature is missing from the
offline edition: `BuildConfig.NETWORK_ENABLED` is false there, and the manifest strips the
INTERNET, ACCESS_NETWORK_STATE and CAMERA permissions.

Code: `app/src/main/java/com/amitjha/kosh/sync/` (tests are in `app/src/test/.../sync/HouseholdSyncTest.kt`).

## Building blocks

All primitives come from the platform JCA, with no third-party crypto.

| Purpose | Primitive |
|---|---|
| Key agreement | ECDH on P-256 (secp256r1), a fresh key pair for every connection |
| Authentication | HMAC-SHA256 with a pre-shared 32-byte key |
| Key derivation | HKDF-SHA256 (RFC 5869, tested against RFC test case 1) |
| Transport encryption | AES-256-GCM, 128-bit tag |
| Pair key at rest | AES-256-GCM key in the Android Keystore (alias `kosh_household_v1`, not exportable) |

## Pairing

1. Phone A taps **Show my code**. A opens a pairing window of 5 minutes and generates a fresh random
   32-byte `secret`. It shows a QR code:
   `kosh-sync:` + base64url(JSON `{v, id, name, host, port, secret}`).
   `id` is a random per-install UUID. `host:port` is A's Wi-Fi address and its listening socket (an
   ephemeral port).
2. Phone B scans the code, connects to `host:port` and runs the handshake in `PAIR` mode, using
   `authKey = secret`.
3. Both sides derive `pairKey = HKDF(ecdh, salt = secret, info = "kosh pair" ‖ th)`. They store it
   wrapped by the Keystore, together with the peer's id and name.
4. A closes the pairing window right after one successful pairing, after 5 failed attempts, or
   after 5 minutes. The secret is never stored.

**Unpair** deletes the peer, its wrapped key, its last address and every record received from it.
The user's own shared records stay. Pairing with a different phone also clears the previous phone's
records.

## Discovery and transport

- A listening `ServerSocket` and an NSD registration (`_kosh-sync._tcp`, service name `kosh-<id>`)
  exist only while the Household sync screen is open. Nothing runs in the background.
- **Sync now** discovers `kosh-<peerId>` through NSD (6 s). If that fails, it uses the last address
  that worked. The address is only a hint, because the handshake authenticates the peer.
- Connect timeout is 8 s and read timeout is 20 s. Only one exchange runs at a time. An extra
  incoming connection while one is busy is dropped.

## Handshake

Messages are length-prefixed frames (4-byte big-endian length). A handshake frame is at most 16 KiB.

```
C → S  hello1  {v, mode, id, name, pub_c, nonce_c}            (32-byte random nonce)
S → C  hello2  {v, id, name, pub_s, nonce_s, mac_s}
C → S  finish  {mac_c}

th    = SHA-256( lp("kosh-sync") ‖ lp(v) ‖ lp(mode) ‖ lp(id_c) ‖ lp(name_c) ‖ lp(pub_c) ‖ lp(nonce_c)
                 ‖ lp(id_s) ‖ lp(name_s) ‖ lp(pub_s) ‖ lp(nonce_s) )        lp = 4-byte length prefix
mac_s = HMAC(authKey, "kosh-sync server" ‖ th)
mac_c = HMAC(authKey, "kosh-sync client" ‖ th)
```

`authKey` is the QR `secret` in `PAIR` mode and the stored `pairKey` in `SYNC` mode. The server picks
the key from the mode and the client id, and refuses the connection when it has no key for them
(pairing closed or expired, or an unknown device). The client checks `mac_s`, and in `SYNC` mode it
also checks that the server's id is the paired peer. The server checks `mac_c`. Both comparisons run
in constant time.

Session keys, one per direction:

```
k_c2s = HKDF(ecdh, salt = authKey, info = "kosh c2s" ‖ th)
k_s2c = HKDF(ecdh, salt = authKey, info = "kosh s2c" ‖ th)
```

## Secure channel

Each record is `counter (8 bytes) ‖ AES-GCM(k_dir, nonce = dirTag(4) ‖ counter(8), aad = dirTag ‖ counter)`.

- The counter starts at 0 in each direction. The receiver requires exactly `previous + 1`, so a
  replayed, dropped, reordered or truncated stream fails.
- `dirTag` is a different constant for each direction, so a frame can't be reflected back to its sender.
- Frames are at most 4 MiB. Any authentication failure ends the connection, and nothing received
  before the failure is applied.

## Data exchange and merge

The client sends `{"type":"items","items":[...]}` with all of its own household records, including
tombstones. The server merges them and replies with its own records, and the client merges those.
Each item is `{uuid, kind, ownerId, ownerName, payload, updatedAt, deleted}`.

Rules (`HouseholdMerge`):

- **Ownership**: an incoming item is accepted only if `ownerId == peerId`, the authenticated peer.
  A phone can never create, change or delete a record owned by the other phone.
- **Last writer wins** per UUID, by `updatedAt`. When timestamps are equal, a tombstone wins, and
  after that the larger payload wins. Every rule is deterministic, so both phones converge.
- Merging is idempotent: applying the same batch twice changes nothing.
- Limits: at most 20,000 items, a payload of at most 16 KiB, and only the kinds TXN, BUDGET and GOAL.
  Items over a limit are rejected and counted.
- Received records live only in `household_items` with `localId = NULL`. They never enter the
  receiving phone's own transactions, balances, budgets or stats.
- Payloads carry only what the household view needs: amount, type, category, merchant, note and time
  for a transaction; category and limit for a budget; name, target, saved and deadline for a goal.
  Account names and SMS text are never shared.

## Threat model

| Threat | Mitigation |
|---|---|
| **Same-Wi-Fi attacker (eavesdropping)** | All data goes through AES-256-GCM with keys derived from ephemeral ECDH, so a passive attacker learns only packet sizes and timing. Every connection uses a new key pair, which gives forward secrecy: a pair key stolen later can't decrypt recorded sessions. |
| **Same-Wi-Fi attacker (MITM, spoofed NSD, ARP or DNS)** | The handshake is authenticated with a key the attacker doesn't have (the QR secret or the pair key), and the MAC covers the whole transcript, including both public keys. An attacker who swaps keys or answers in place of the real peer fails the MAC check. NSD results are untrusted hints. |
| **Attacker connects to the open port** | Without a pairing window or a matching device id and pair key, the server sends nothing and refuses. During pairing, 5 failed attempts close the window. |
| **QR shoulder-surfing or photo** | The secret works only during the 5-minute window, only once, and only on the same network, and it is never stored. An attacker who wins the race becomes the paired device, but the screen shows the paired device's name, and pairing again or unpairing removes them. They receive only records marked as Household. Show the code only to the partner, and cancel it when done. |
| **Replay** | The counter is part of the nonce and the AAD and must increase by exactly one. A replayed or spliced frame from another session fails, because each session has new keys bound to random nonces from both sides. |
| **Downgrade** | Only `v = 1` is accepted, and the version and mode are inside the MAC'd transcript, so an attacker can't make a peer believe the other side asked for anything else. A future version must refuse older ones, not fall back to them. |
| **Reflection** | Each direction has its own key and direction tag, and client and server MACs use different labels. |
| **Malicious or compromised partner phone** | The ownership rule stops it from overwriting the user's own records. Size and count limits bound memory use, and received data never touches personal figures. It can still see everything the user shared, which is the purpose of sharing. |
| **Stolen phone or backup** | The pair key is wrapped by the Keystore and can't be used off the device. The household preferences are excluded from backups. Received records sit inside the encrypted app database. |
| **Denial of service** | Both phones need the screen open, frames and items are capped, connections time out, and only one exchange runs at a time. A local attacker can still jam Wi-Fi or keep the port busy; that is out of scope. |

## Known limitations

- Both phones need the Household sync screen open, or one of them opens it while the other taps
  **Sync now**. There is no background sync, by design.
- Exactly one partner. A household of more than two phones is not supported.
- Last writer wins uses the phones' clocks. A phone with a badly wrong clock can only lose or win
  against its own edits, because records are owned by a single phone.
- `household_items` isn't part of the encrypted backup. After a restore, re-mark items or sync again.
- Decoy (sandbox) mode syncs the sandbox database.
- Wi-Fi networks with client isolation (many guest and office networks) block phone-to-phone
  connections.
