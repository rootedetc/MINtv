# Changelog

All notable changes to MINtv are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-10-27

First complete release: the whole MAXtv client, from the guide to playback, packaged as one
universal APK.

### Added

- **Guide (EPG)** — a TiviMate-style grid for all 247 channels, with a full day of
  programmes, a 4-hour window, day ruler, now line, live progress, channel logos and
  per-channel subscription locks.
- **Live TV** — DASH playback with Widevine for subscription channels and clear DASH for
  free-to-air channels, with CH+/CH− and D-pad zapping.
- **Catch-up and time shift** — watch a programme that already aired from the beginning, or
  restart the programme currently on air.
- **Channels** — favourites, free-to-air and HD filters, custom channel order, reset to the
  broadcast order, and a lock on every channel outside the signed-in account's subscription.
- **Home** — server-driven rails, hero highlights, on-now, favourite channels, Continue
  Watching and My List.
- **Search** — films, series, people and channels, with a local recent-search history.
- **Content details** — programme and series metadata, actions, seasons, episodes, cast and
  related content.
- **Watchlist and Continue Watching** — add and remove titles, and resume playback from the
  progress synced to the account.
- **Player** — quality preference (Auto / Max / 720p), a readout of the actual produced
  resolution, subtitle and audio-track selection, and a compact channel switcher.
- **Sign-in** — MAXtv account sign-in with optional Keystore-backed password storage. Free
  channels work without signing in.
- **Languages** — Croatian and English app UI; catalogue text stays as the service returns it.
- **Remote-first shell** — permanent source rail, D-pad focus rules, 10-foot typography and a
  low-noise broadcast palette.

### Notes

- First public build, version 1.0.0 (versionCode 1).
- One universal APK: the app contains no native libraries, so the same file runs on
  arm64-v8a, armeabi-v7a, x86 and x86_64 Android TV devices.
- The release APK is signed with the local debug key for sideloading. It is not a Play Store
  artifact.
- Requires Android 8.0 (API 26) or newer, a valid MAXtv subscription and Widevine for
  subscription channels. MAXtv blocks playback over VPN.
- Unofficial and unaffiliated with Hrvatski Telekom.
