# MINtv

A small, fast Android TV client for **MAXtv** (Hrvatski Telekom), built for a single
household's own subscription. No analytics, no ads, no Firebase, no telemetry — just the
channels, the EPG and the player.

> Unofficial and unaffiliated with Hrvatski Telekom. It talks to the same public service the
> official web player uses, with your own account. You need a valid MAXtv subscription.

## What works

- **Sign in** with your MAXtv username and password (the native IDM login, password encrypted
  with RSA-OAEP before it leaves the device).
- **EPG** — a TiviMate-style grid: 247 channels, a full day of programmes, day ruler, now
  line, progress on the current show, and channel logos.
- **Live TV** — DASH playback with Widevine for subscription channels, clear DASH for the
  free-to-air ones.
- **Channel zapping** — `CH+`/`CH-` or `UP`/`DOWN` straight from the picture, with a quick
  switch strip when the overlay is open.
- **Catch-up / time shift** — pick a programme that already aired in the EPG and it plays
  from the beginning.
- **Quality control** — `Auto` (fast start, then highest), `Max` (1080p from the first frame,
  no switching) or `720p` (data saver).
- **Fast start** — first frame in about a second on a healthy link, plus a watchdog that
  re-resolves the CDN token when it lands on a slow edge.

## Build

Requires JDK 17+ and the Android SDK (compileSdk 34). No NDK.

```bash
./gradlew assembleRelease
```

The release build is R8-minified and signed with the local debug key, which is fine for
sideloading but not for distribution. Point `signingConfigs` at your own keystore if you
want a proper release key.

Install on a TV over ADB:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Requirements

- Android TV / Google TV, Android 8.0 (API 26) or newer.
- Widevine is required for subscription channels (the TV must have it; virtually all do).
- **MAXtv does not work over a VPN.** The CDN rejects VPN egress; turn it off while watching.

## Notes

- The app is `local.maxtv.lite` on device. Rename the application id before publishing
  anywhere.
- Keys and constants that are published by the service's own web client are bundled so the
  app can talk to it; no private keys of ours are involved.
- DRM is never circumvented: licences are requested from the official server with the
  signed-in user's own session.

## License

Private project, no license granted.
