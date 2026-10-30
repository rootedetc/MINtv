# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Existing native Android TV app. Java + Android Views, RecyclerView, Media3/ExoPlayer; no Compose, no AppCompat/Material dependency. compileSdk 34, minSdk 26.

## Users

One Croatian household watching its own MAXtv subscription on a television, across the room, with a D-pad remote. No touch, no mouse, no keyboard in the normal case. Sessions are short and frequent: open the app, find something, watch. *(Inferred from the brief and repository; confirmed by the private single-household framing in the README.)*

## Product Purpose

A fast, quiet client for MAXtv live television: the full channel line-up, the day's EPG, and playback without the official app's weight. Success is time-to-picture and the ability to answer "what is on now, and what else could I watch?" from the couch without friction.

## Positioning

A private, telemetry-free MAXtv client whose EPG is the front door rather than a settings deep-link. It talks to the same public service the official web player uses, with the household's own account. No ads, no analytics, no Firebase, no DRM circumvention.

## Operating Context

- 10-foot viewing, 1920×1080 and 4K TVs, Android TV / Google TV, API 26+.
- Input: D-pad, OK/Enter, Back, channel up/down, media keys. Touch is secondary and unsupported in the product (leanback required).
- Network: MAXtv CDN, Widevine for subscription channels, clear DASH for free-to-air. VPN blocks playback.
- Croatian is the default language; an English translation exists.
- EPG is one local day, fetched as three-hour pages and cached on disk; the player reuses the same cache for now/next.

## Capabilities and Constraints

- Sign in with a MAXtv account; remember password through Android Keystore.
- Channel line-up: 247 channels with logos and quality labels; free-to-air and subscription channels.
- EPG grid: one day, programme blocks, now line, live progress, catch-up metadata.
- Live playback via Media3 DASH + Widevine; catch-up/time-shift from an EPG programme.
- Channel switching from the player's channel switcher (UP/OK or CH+/CH−); no picture-interrupting zapping.
- Quality preference: Auto / Max / 720p, applied immediately without re-tuning; the player readout reports the actual produced resolution (e.g. 1080p or 720p).
- Player settings: quality, subtitle and audio-language selection live in the bottom overlay; the channel switcher carries no quality control.
- Home rails, Continue Watching, favourite channels/custom order, Search and content details are implemented from the six feature specs. Rentals, recordings, parental PIN, multi-day EPG and programme notifications remain out of scope.
- No phone layout ships. The app is landscape TV only.

## Brand Commitments

- Name: **MINtv**. Wordmark is "MIN" in near-white condensed bold and "tv" in magenta, with a short magenta underscore beneath. App icon and TV banner use the same lockup on near-black.
- Brand colour: magenta `#E6007E` (bright variant `#FF3D9A`) taken from the icon and banner. Near-black ground.
- Voice: plain, Croatian, understated, no marketing hype. Independent client; unaffiliated with Hrvatski Telekom.
- Assets on hand: `app/src/main/res/drawable-nodpi/ic_launcher.png` (512×512), `app/src/main/res/drawable-xhdpi/tv_banner.png` (320×180).
- The user explicitly requires a native-feeling Google TV / Android TV app, in the spirit of TiviMate, with EPG as the main surface.

## Evidence on Hand

- Real channel and EPG data through the guest endpoints; real logos on the CDN.
- Existing working flows: channel grid, EPG, live player, catch-up, login.
- No marketing copy, no testimonials, no benchmark data. Do not fabricate any.

## Product Principles

1. The guide is the product. Opening the app answers "what is on?" before it asks anything else.
2. Remote first. Every surface is reachable, readable, and operable with four D-pad directions, OK, and Back.
3. Picture fast. Chrome and navigation never delay the first frame.
4. Broadcast calm. Dark, legible, low-noise surfaces that recede behind content; the brand is a signal, not wallpaper.
5. Truthful controls. Never show a feature the app cannot perform.

## Accessibility & Inclusion

- D-pad focus must always be visible, never trapped, and never land on a non-action.
- All text at TV viewing distance: no functional label below 12sp, body at 14–16sp, generous contrast on dark surfaces.
- Colour is never the only carrier of state: LIVE, NOW, catch-up and focus also carry a label, shape, or position.
- Croatian diacritics and long programme titles must ellipsize cleanly, not clip.
