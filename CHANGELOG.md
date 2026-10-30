# Changelog

All notable changes to MINtv are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- **Catch-up playback** — `/player/playinfo/catchup` returns its `playBackInfoResponse` at the
  top level, not inside `data`. The player was treating every successful catch-up resolve as an
  entitlement failure; both envelopes are now accepted.
- **Catch-up start position** — a catch-up asset is a rolling timeshift window, so Media3 joined
  it near the live edge. Playback now starts at the listing's first frame (or at a saved
  bookmark), which is what the native app does.
- **Catch-up loading ident** — a VOD/catch-up launch without a channel identity reused the old
  demo ident and printed “Javni DASH test · Unified Streaming”. It now shows the programme's own
  title/subtitle and only tags OD POČETKA for replay. The unused public demo player and its
  strings are gone.

### Changed

- **Guide** — ended listings now share the darkest block fill whether or not they can be
  replayed; catch-up is carried by a small replay mark, a bright title and the OD POČETKA tag,
  while an ended listing without catch-up keeps the dimmed title and PRETHODNO. Upcoming
  listings use the lifted surface (`#191E26`) instead of the previous near-focus grey, so the
  future half stays calm. Live stays the only magenta-tinted block.
- **Guide layout** — the channel column shrank from 244dp to 76dp and now shows only the channel
  logo; channel numbers and names are gone, leaving more of the day visible. The on-air
  bar lost its progress bar, channel number and duplicate channel-name row; it now carries the
  poster, programme title, metadata, synopsis and next programme only.
- **Sidebar** — the source rail is now a fixed 96dp icon rail that never widens or paints labels
  on focus. Choosing a destination selects it and moves focus into its content panel; choosing
  the active destination returns focus to the content instead of parking it on the rail.
- **My List removed** — the watchlist header action, Home rail, Settings shortcut and details
  add/remove action are gone, along with the watchlist screen and data layer.

## [1.0.1] - 2026-10-30

A polish and playback-reliability release: the player now reserves and releases its stream
slot correctly, channels change only from the channel switcher, Channels keeps a single
favourites filter, and Home's channel rails use the same card as the channel grid.

### Changed

- **Channels** — the All / Free / HD filter row is gone; favourites is now a single toggle
  chip, and the options menu moved into a settings icon next to refresh.
- **Player** — instant UP/DOWN and CH+/CH− zapping is gone. UP, OK and CH+/CH− open the
  channel switcher, DOWN still opens playback settings, and a channel is tuned only by
  picking it in the switcher.
- **Home** — channel rails now use the channel card: contained logo, channel number, name,
  quality, now line and live progress, instead of a cropped poster.

### Fixed

- **Playback** — the account's CLPP stream lock is now heartbeated and released on every
  channel switch and when the player closes. Switching several channels in a row no longer
  piles up stale stream reservations and trips the concurrency limit on the next tune.
- **Home focus** — UP from the hero now lands on the category strip, UP from the categories
  reaches the header actions, and DOWN returns, instead of jumping to the sidebar.

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
