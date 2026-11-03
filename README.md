# Struthio

A small, fast Nostr client for Android, built with Kotlin and Jetpack Compose in Material 3 Expressive. Colors come from the system.

## What it does

- Home feeds: Following (outbox model), Trending, Global and your own relays
- Notes with comments, boosts and quotes, reactions and zaps
- Private messages, including encrypted photos and files
- Lightning wallet through Nostr Wallet Connect
- Image uploads, QR scanning and emoji reactions
- Notifications, search, profiles, and follower and following lists
- Background notification checks every 15 minutes by default, through Android's own scheduler
- Bookmarks, drafts, content warnings, @mentions, reporting, video and link previews
- A follows-of-follows filter for Global and Trending

## Protocol

Struthio implements the current form of each specification and writes no deprecated formats.

| NIP | Use |
|---|---|
| 01, 02 | Events, relays, follow lists |
| 05 | Verified addresses |
| 09 | Honoring deletions |
| 10 | Threads with marked `e` tags |
| 17, 44, 59 | Private messages: gift wraps and NIP-44 v2 encryption |
| 18 | Boosts and `q` tag quotes |
| 19, 21, 27 | Bech32 entities, `nostr:` links, inline references |
| 22 | Comments on kinds other than notes |
| 24 | Metadata fields |
| 25 | Reactions |
| 36 | Content warnings |
| 40 | Expiration |
| 42 | Relay authentication, only when a relay demands it |
| 45 | Follower counts |
| 47 | Nostr Wallet Connect, NIP-44 only |
| 50 | Search |
| 51 | Mute list, bookmarks, search relays |
| 55 | External signer apps |
| 56 | Reports |
| 57 | Zaps, including split tags and receipt validation |
| 65 | Relay lists and the outbox model |
| 92 | Media attachments |
| B7 | Blossom uploads and media server lists |

Not used on purpose: NIP-04, NIP-06, NIP-08, NIP-26, NIP-96 and relay lists inside kind 3.

## Staying small

- Beyond Compose, the only third-party libraries are libsecp256k1 for keys and signatures and the ZXing core for QR codes.
- WebSocket, ChaCha20, Bech32, NIP-44, the camera code, image loading, navigation and storage on the platform's SQLite are all in this repository.
- Google Sans Flex is reduced to its weight axis, from 3.9 MB to 280 KB.
- Signature checks are skipped only for events already verified in the session.
- Photos are re-encoded before upload, which removes EXIF data such as location.
- Sockets close shortly after the app leaves the screen. Between scheduled checks, nothing runs in the background.
- One APK per CPU architecture; the launcher icon is vector only.

## Testing on a device

`scripts/smoke.sh`, run by hand from Actions, signs in read-only with a public npub, so it publishes nothing.

## Build

```
./gradlew testDebugUnitTest assembleRelease
```

APKs land in `app/build/outputs/apk/release/`. CI also publishes them as an artifact on every push. To sign CI builds, set the secrets `STRUTHIO_KEYSTORE_BASE64`, `STRUTHIO_KEYSTORE_PASSWORD`, `STRUTHIO_KEY_ALIAS` and `STRUTHIO_KEY_PASSWORD`.
