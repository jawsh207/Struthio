# Struthio

A small, fast Nostr client for Android, built with Kotlin and Jetpack Compose in Material 3 Expressive. Colors come from the system.

## What it does

- Home feeds: Following (outbox model), Trending, Global and your own relays
- Notes with comments, boosts and quotes, reactions and zaps
- Private messages
- Lightning wallet through Nostr Wallet Connect
- Notifications, search and profiles

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
| 40 | Expiration |
| 42 | Relay authentication, only when a relay demands it |
| 47 | Nostr Wallet Connect, NIP-44 only |
| 50 | Search |
| 51 | Mute list, search relays |
| 55 | External signer apps |
| 57 | Zaps, including split tags and receipt validation |
| 65 | Relay lists and the outbox model |
| 92 | Media attachments |

Not used on purpose: NIP-04, NIP-06, NIP-08, NIP-26, NIP-96 and relay lists inside kind 3.

## Staying small

- The only third-party runtime dependency is libsecp256k1, for keys and signatures.
- WebSocket, ChaCha20, Bech32, NIP-44, image loading, navigation and storage on the platform's SQLite are all in this repository.
- Signature checks are skipped only for events already verified in the session.
- Sockets close when the app leaves the screen. Nothing runs in the background.
- One APK per CPU architecture; the launcher icon is vector only.

## Build

```
./gradlew testDebugUnitTest assembleRelease
```

APKs land in `app/build/outputs/apk/release/`. CI also publishes them as an artifact on every push. To sign CI builds, set the secrets `STRUTHIO_KEYSTORE_BASE64`, `STRUTHIO_KEYSTORE_PASSWORD`, `STRUTHIO_KEY_ALIAS` and `STRUTHIO_KEY_PASSWORD`.
