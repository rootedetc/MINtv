# MINtv — Broadcast Rail

**Platform:** Android TV / Google TV (native Views, no Compose)
**Mode:** Operate — the household came to find something and watch it
**Baseline:** 960×540 dp, landscape, D-pad first
**Source of truth:** this file, `PRODUCT.md`, and `.impeccable/surfaces/app-src-main-java-local-mintv-epgactivity-java.md`

## 1. The world

The guide is a live broadcast rundown on a permanent source rail. The rail is the spine; the schedule is the composition; one on-air bar gives a programme its full identity. The world is the control room and the lower third, not a streaming dashboard.

| Role | Token | Value |
|---|---|---|
| Ground | `bg` | `#07080B` |
| Raised panel | `bg_raised` | `#0B0D11` |
| Surface / card | `surface` | `#12151B` |
| Lifted card | `surface_2` | `#191E26` |
| Focus surface | `surface_focus` | `#2A323E` |
| Hairline | `stroke` | `#242B35` |
| Strong hairline | `stroke_strong` | `#39424F` |
| Primary text | `text_primary` | `#F4F6F9` |
| Secondary text | `text_secondary` | `#AEB6C2` |
| Tertiary text | `text_tertiary` | `#7B8492` |
| Signal | `brand` | `#E6007E` |
| Signal bright | `brand_bright` | `#FF3D9A` |
| Live / focus ring | `focus_ring` | `#FFFFFF` |
| Warning / earlier | `warning` | `#FFB454` |
| Success / free | `success` | `#5BD6A0` |
| Error | `danger` | `#FF6B6B` |

Magenta is a **signal**: the SADA tab and now line, the live state, the selected rail destination, and the primary button. Focus is a 2px white rule; magenta is never the focus colour. It is never a background wash. Surfaces are flat; depth comes from a 1px hairline and, on the player, a scrim.

## 2. Type

- **Display/numerals/labels:** Barlow Condensed (bundled OFL: regular, medium, semibold, bold). Used for the wordmark, screen titles, channel names, channel numbers, programme titles, clocks/times, state tags and uppercase labels.
- **Body/controls:** Roboto (system) for synopses, settings descriptions, field text and hints.
- **Numerals:** `fontFeatureSettings="tnum"` on clocks, times and counts.

| Role | Size |
|---|---|
| Guide title / screen title | 32sp display |
| On-air programme title | 27sp display |
| Player programme title | 26sp display |
| Section heading | 27sp display |
| Channel name (guide / bar) | 13.5sp / 15sp display |
| Programme title (grid) | 14sp display |
| Body / synopsis | 13–14sp Roboto |
| Metadata / time | 10.5–12.5sp Roboto |
| Uppercase label | 11.5sp Barlow medium, tracking 0.11em |

## 3. Layout

- **Rail:** 96dp, full height, right hairline. 42dp square app icon, three destinations at 76dp, account at the bottom, then the single clock (21sp) and date (10sp).
- **Header:** 68dp, 32dp horizontal padding. Title + displayed day; no clock, no channel total, no quality control.
- **Guide:** 244dp channel column, 40dp ruler, 52dp rows, 4-hour window, 1px half-hour rules. The SADA tab is clock-free; the now line is 2dp magenta.
- **On-air bar:** 152dp, below the guide. 200×112 programme artwork; 48×30 channel logo; channel number/name/meta; state tag; 27sp title; two-line synopsis; a 3dp progress track; time range, duration and remaining time; next programme and the truthful OK action. Artwork is the programme poster, not a generated placeholder; when the endpoint has no poster, a channel-number plate is the fallback.
- **Channels:** 4-up at 960dp, 174dp cards: 104dp logo area, number + name + free dot, now line, 2dp progress. Filters are 8dp rectangles, not pills.
- **Settings:** 236dp category rail + 540dp content sheet. Account, playback/quality, guide data, about. The guide has its own refresh action; quality is the playback default and is also changeable in the player.
- **Player:** full-bleed picture. Top-left channel identity (number plate, 31sp name, inline state + resolution, no pills). One transport plate at the bottom: 58dp magenta play/pause, title/time/next, quality readout, progress and one hint line. DOWN opens the quick drawer; LEFT focuses its quality row; RIGHT returns to channels.
- **Login:** one centred 620dp panel, app icon, title, two fields, remember switch, sign in / continue without signing in, secure-password note. Scrolls above the on-screen keyboard.

## 4. Components and states

- **Source rail:** active destination = magenta label + 3dp tally; focus = 2dp white outline; account shows sign-in state.
- **Guide cell:** live = magenta progress at the foot + UŽIVO; past = dimmed title + PRETHODNO; future = USKORO; focused = 2dp white rule and lifted fill. The state is never carried by colour alone.
- **On-air bar:** states are UŽIVO, OD POČETKA, PRETHODNO, USKORO or NEMA RASPOREDA. The action line mirrors what OK will actually do (watch live, watch from the start, watch the channel).
- **Channel card:** free-to-air = green dot + accessible “free channel” description; HD chip; focused = white outline + slight lift; now/progress hidden if the guide cache is empty.
- **Player:** the quick drawer holds channels, their now programme, and the quality row. Quality changes apply immediately by re-tuning the same channel. Back hides the overlay before it exits.
- **Empty / error:** guide error bar with a focusable retry; channels error panel with retry; player error sheet with retry (overlay hidden); login validation in place.

## 5. Motion

Short, decisive, never bouncy:

- Focus: scale 1.02–1.035, 140ms in / 110ms out, decelerate.
- Overlay: 150ms fade + 6dp rise; drawer 150ms fade + 12dp slide; collapse 110ms.
- No looping decoration, no shimmer, no parallax. The only continuous motion is the guide's minute tick and live progress.

## 6. Truth and scope

- **One clock** in the rail. The date appears in the rail and in the guide header because the header must show the displayed day if the guide ever moves.
- **No total channel count** in the guide, channels header or quick drawer.
- **Quality** lives in the player drawer; the guide has no quality control. Settings carries the default preference.
- **Programme artwork** comes from the public native details endpoint — `GET /hr-bifrost/details/program/{program_id}` → `poster_image_url` — fetched lazily, cached in memory, and never invented. The schedule page itself only carries the programme id.
- No search, watchlist, continue-watching, favourites, recordings or multi-day guide are claimed. The six specs in `/Users/marko/maxtv-feature-specs/` are future work; nothing in this build implies them.

## 7. Accessibility

- Every target is reachable with four D-pad directions, OK, and Back.
- Focus is always a visible 2px white rule or a full-row focus surface.
- Body contrast ≥4.5:1; large text ≥3:1. The player identity has a soft shadow over bright video.
- Croatian diacritics and long titles ellipsize; no clipping.
- The free-to-air dot has a spoken “free channel” suffix; focusable controls carry content descriptions.

## 8. Evidence and provenance

- **Programme artwork:** observed `2026-10-25` from the guest BFF: `/hr-bifrost/details/program/HRT1-SH5370151-S1E4` returns `poster_image_url` `https://tv-hr-prod.yo-digital.com/prod/images/356/200/img244523-tv5370151.jpg`; the existing `details.program.description` and `runtime_seconds` fill the synopsis and running time. The repository caches up to 160 details per process and marks failures.
- **Fonts:** Barlow Condensed, SIL Open Font License, `github.com/google/fonts/ofl/barlowcondensed`, copied into `res/font` on 2026-10-25.
- **Existing rasters:** `drawable-nodpi/ic_launcher.png` and `drawable-xhdpi/tv_banner.png` are the project's pre-existing MINtv assets from the original commit; no new shipping rasters were introduced.
- **Screenshots:** 1920×1080 captures from the Android TV emulator (`emulator-5556`, `wm size 1920x1080`, `wm density 320`), stored under `.impeccable/review/rail-fix/`.
- **Not shipped:** the decision-page sketches and the review screenshots are design evidence, not app assets.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
