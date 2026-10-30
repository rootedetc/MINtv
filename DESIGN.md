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

- **Rail:** 96dp at rest, full height, right hairline. A rounded `bg_raised` panel sits 8dp inside the left/top with a hairline; the MINtv typography lockup and five destination icons live inside it, while account, clock and date stay on the bare ground. At rest the destinations are icon-only 34dp tiles in 58dp rows — no labels under the icons. When the rail holds focus it widens to 144dp (the content column shifts with it) and each destination becomes one row: icon tile + 15sp Barlow label. Selected = magenta wash, magenta icon, 3dp tally; focus = full-row `surface_focus` + 2dp white rule, never a tiny pill outline. Order: Početna, Vodič, Kanali, Traži, Postavke.
- **Header:** 68dp, 32dp horizontal padding. Title + displayed day; no clock, no channel total, no quality control. Refresh is an icon button, not a text label.
- **Guide:** 244dp channel column, 40dp ruler, 52dp rows, 4-hour window, 1px half-hour rules. The SADA tab is clock-free; the now line is 2dp magenta. Cell baselines are fixed: title, then time, then a 3dp progress bar with clear air between them.
- **On-air bar:** 152dp, below the guide. 200×112 programme artwork; 48×30 channel logo; channel number/name only (no duplicate channel-meta line); LIVE/state tag as a top-right corner tag; 27sp title; two-line synopsis; a 3dp progress track; time range, duration and remaining time; next programme. There is no “OK · WATCH LIVE” hint — the action is the remote’s OK, not chrome. Artwork is the programme poster, not a generated placeholder; when the endpoint has no poster, a channel-number plate is the fallback.
- **Channels:** 4-up at 960dp, 152dp cards: 90dp logo area, number + name + inline quality label, now line, 2dp progress. The third row intentionally peeks under a 48dp bottom fade so the viewer knows the grid scrolls; the permanent “OK: watch…” footer is gone.
- **Settings:** 236dp category rail + a content sheet that fills the remaining width (540dp at rest). Account, language, playback/quality, guide data, about. The guide has its own refresh action; quality is the playback default and is also changeable in the player. When the source rail expands, the sheet shrinks instead of pushing its focus rings off screen.
- **Home:** 96dp source rail, 80dp header, a category strip of server pages, a 240dp hero, then horizontal rails of 232×196 poster cards. The hero is full-bleed artwork with a left-to-right scrim, an optional live tag, the title, subtitle, two-line synopsis and exactly two actions: magenta Watch + ghost More info. The hero itself is scenery (no focus ring); the actions carry focus. Missing artwork resolves through the programme-details poster (1280×720 rendition) and, when nothing resolves, a quiet brand plate — never a placeholder icon. Continue Watching and My List are real rails; channel rails reuse the channel identity.
- **Search:** 76dp header, one 54dp search bar, a 34dp filter strip, and a single vertical result rundown. Programs use a 148×84 thumbnail row, people a circular-crop identity, channels a logo row. Rows share one width and 8dp vertical gaps. History is a local list under the empty query.
- **Watchlist / Continue Watching:** the same poster wall, 3-up at 960dp, with a 58dp manage footer. Manage mode uses the magenta check disc; delete is one confirmed batch.
- **Details:** a 320dp split hero (copy left, 16:9 artwork right), a single action row, metadata rows on hairlines, then seasons/episodes, cast and related rails. Guest sees Sign in where Watchlist would be.
- **Player:** full-bleed picture. Top-left channel identity (number plate, 31sp name, inline state + **actual produced resolution** such as `1080p`/`720p`, never the preference). One transport plate at the bottom: 58dp magenta play/pause, title/time/next, actual stream readout (`STREAM 1080p`), a settings affordance, progress and one hint line. OK or UP from the picture opens the compact channel switcher; DOWN opens playback settings. Channels are changed only from the switcher, never by zapping straight from the picture. The switcher has no quality row: quality, subtitles and audio language live in the settings sheet.
- **Login:** one centred 760dp panel, app icon, title, two fields, remember switch, sign in / continue without signing in, secure-password note. When the keyboard opens, the panel switches to a compact form (decorative column, subtitle, remember switch and secure note hidden), reserves the measured IME overlap and scrolls title-through-actions above the keyboard; it never pushes the panel off the top.

## 4. Components and states

- **Source rail:** the rail is a rounded raised panel with icon tiles and 28×28 glyph boxes. At rest it is icon-only and 96dp wide; while it holds focus it widens to 144dp over 140ms and reveals a 15sp Barlow label beside each icon, so content shifts right rather than being covered. Active destination = magenta wash + magenta icon/label + 3dp tally; a focused selected row keeps the magenta wash and adds the white rule; focus = full-row `surface_focus` + 2dp white rule (no scale distortion of text). DOWN/UP stays inside the rail, RIGHT enters content, BACK returns focus to the rail. The top of the rail is the typography lockup, not the raster launcher icon.
- **Guide cell:** live = magenta progress at the foot + UŽIVO; past = dimmed title + PRETHODNO; future = USKORO; focused = 2dp white rule and lifted fill. The state is never carried by colour alone. Text never crosses its block; times are omitted when they cannot fit. A channel outside the account's subscription carries a quiet lock plate on its logo; its schedule stays readable.
- **On-air bar:** states are UŽIVO, OD POČETKA, PRETHODNO, USKORO or NEMA RASPOREDA. The state tag hangs at the top-right, flush with the NEXT column. One metadata line carries time, genre, duration and remaining time; a 1px hairline separates the copy from NEXT, exactly as in the guide mockup. The action is the remote, not a printed hint; no channel-meta duplicate appears under the name. A locked channel adds a quiet lock beside the identity and `Nije u pretplati` to the metadata line; selecting it explains the subscription boundary instead of starting an entitlement call.
- **Channel card:** 152dp; the stream quality is quiet metadata beside the name (`HD`, `FULL HD`, `4K`), not a pill; the favourite heart is visible only when the channel is actually favourite; no green free dot. A subscription channel the account does not receive carries the same lock plate at the top-right of its logo; free-to-air channels never lock. Focus is one 2dp white rule around the whole card, including the logo, plus the standard lift. Free-to-air remains in the spoken description only.
- **Player:** the compact switcher holds channels and their now programme only — no quality row. It opens on OK/UP from the picture (UP or CH+/CH− while the overlay is visible), 5×44dp rows, 360dp wide. The settings sheet holds the quality preference (Auto / Maximum / 720p) with the actual stream resolution underneath, plus the available subtitle and audio tracks. Quality changes apply immediately without re-tuning; the readout always reports what the decoder is producing. Back hides the overlay before it exits.
- **Empty / error:** guide error bar with a focusable retry; channels error panel with retry; player error sheet with retry (overlay hidden); login validation in place. Home hides an empty rail rather than showing a retry row; authenticated rails hide when signed out. Search shows local history, a minimum-length hint, and a no-results line named after the query.
- **Loading:** no centred spinner on Home/Search/Guide. Skeletons use flat `SURFACE_2`/`SURFACE_3` plates that breathe between 55% and 90% opacity over 1.8s; no shimmer, no gradient. The persistent rail and header never move.
- **Cards:** poster cards are flat 8dp surfaces with a 1px hairline, a 2px white focus foreground, a condensed two-line title, 11sp metadata, and a 3dp magenta progress bar only for Continue Watching. Selection is a magenta check disc in manage mode, never just a colour change.
- **Actions:** one magenta filled primary per surface. Details actions are server-driven: anything the BFF does not return is not shown. Watchlist state comes from `actions.pinned`; it is not guessed before the actions call returns.
- **Settings / Login:** category rail and content sheet keep a 6–10dp inset so the 2dp white focus rule is never clipped; selected = flat fill + magenta marker, focus = white rule only (no doubled outlines). The login panel is responsive and switches to a compact IME form; it scrolls the focused field and the sign-in row above the on-screen keyboard without ever scrolling the panel off the top. Inputs focus white, not magenta.

## 5. Motion

Short, decisive, never bouncy:

- Focus: scale 1.02–1.04, 140ms in / 110ms out, decelerate.
- EPG D-pad scroll: 140ms native ease-out; only the affected row bands repaint.
- Rail expansion: 140ms in / 110ms out, decelerate; the panel width and labels move together and content shifts rather than being overlaid.
- Rail labels: 140ms fade/3dp slide inside a fixed-width label box, so the text never reflows mid-animation.
- Overlay: 150ms fade + 6dp rise; drawer 150ms fade + 12dp slide; collapse 110ms.
- Skeleton loading: one quiet 1.8s reverse pulse between 55% and 90% opacity, cancelled when content arrives. This is the only looping motion besides the guide's minute tick and live progress.
- No shimmer, no gradient sweeps, no parallax.

## 6. Truth and scope

- **One clock** in the rail. The date appears in the rail and in the guide header because the header must show the displayed day if the guide ever moves.
- **No total channel count** in the guide, channels header or player channel switcher.
- **Quality** preference (Auto / Maximum / 720p) lives in Settings and in the player's settings sheet. The player readout is the **actual produced** resolution (`1080p`, `720p`, `2160p`), not the preference; the switcher has no quality row at all.
- **Playback settings** show the actual stream resolution, the available audio tracks and the available subtitle tracks. Selecting a track applies through Media3's track-selection overrides; unavailable tracks say so instead of offering a dead option.
- **Programme artwork** comes from the public native details endpoint — `GET /hr-bifrost/details/program/{program_id}` → `poster_image_url` — fetched lazily, cached in memory, and never invented. The schedule page itself only carries the programme id.
- **Implemented from the six feature specs:** Home rails, Watchlist, Continue Watching, favourite channels + custom order, Search, and content details. The rail still ships as the root; Home is a second destination, not a replacement for the guide.
- **Out of scope by decision:** rentals/purchases, recordings, parental PIN management, DVB-C, MQTT/FCM, NPS, tenant switching. The UI never implies them.
- **Auth-gated surfaces tell the truth:** signed out, they show Sign in; they never render a fake empty watchlist or a heart in the "off" state before the favourites call returns. Likewise, channel locks come only from a successful `/epg/channel/subscriptions` response for the current account: before it returns, or if it fails, no channel is marked locked.
- **Language:** Settings carries an explicit Hrvatski / English choice. The app UI, dates and widgets follow it on every activity; MAXtv catalogue text remains whatever the server returns (Croatian on this tenant). There are no mixed Croatian/English app labels.

## 7. Accessibility

- Every target is reachable with four D-pad directions, OK, and Back.
- Focus is always a visible 2px white rule or a full-row focus surface.
- Body contrast ≥4.5:1; large text ≥3:1. The player identity has a soft shadow over bright video.
- Croatian diacritics and long titles ellipsize; no clipping.
- The free-to-air dot has a spoken “free channel” suffix; focusable controls carry content descriptions.

## 8. Evidence and provenance

- **Programme artwork:** observed `2026-10-25` from the guest BFF: `/hr-bifrost/details/program/HRT1-SH5370151-S1E4` returns `poster_image_url` `https://tv-hr-prod.yo-digital.com/prod/images/356/200/img244523-tv5370151.jpg`; the existing `details.program.description` and `runtime_seconds` fill the synopsis and running time. The repository caches up to 160 details per process and marks failures.
- **Hero artwork:** the first Home highlight on `2026-10-25` (`Osijek - Rudeš`, CTA `magiotv://details/MAXSport1-64173878`) returned `"image": ""`; `/details/program/MAXSport1-64173878` returns a poster at `/prod/images/356/200/img246356-tv5389069.jpg`, and the same asset answers at `/prod/images/1280/720/` (HTTP 200, 49 KB). Home now prefers a highlight with artwork, resolves an empty image through details, and upgrades the MAXtv poster path to 1280×720.
- **Actual stream quality:** `Player#getVideoFormat()` is the source of the readout. On the emulator the HRT stream decoded at 640×360 (1036 kbps) and correctly reported `360p` even while the preference was `Maximum`; no preference label is rendered in the overlay.
- **Fonts:** Barlow Condensed, SIL Open Font License, `github.com/google/fonts/ofl/barlowcondensed`, copied into `res/font` on 2026-10-25.
- **Existing rasters:** `drawable-nodpi/ic_launcher.png` and `drawable-xhdpi/tv_banner.png` are the project's pre-existing MINtv assets from the original commit; no new shipping rasters were introduced.
- **Screenshots:** 1920×1080 captures from the Android TV emulator (`emulator-5556`, `wm size 1920x1080`, `wm density 320`), stored under `.impeccable/review/rail-fix/`.
- **Round 2 screenshots (2026-10-26):** `round2/guide-hr-after2.png`, `round2/guide-midnight.png`, `round2/sidebar-hr-expanded.png`, `round2/home-hr-after.png`, `round2/login-rest2-after.png`, `round2/login-ime-after.png`, `round2/login-ime-password-after.png`, `round2/player-switcher-after.png`, `round2/player-settings3.png`, `round2/player-settings4.png`, `round2/player-hrt-after.png`, `round2/settings-expanded-final.png`, `round2/final-release-settings2.png`, `round2/final-release-home.png`, `round2/final-release-player.png` in `.impeccable/review/`. Captured on `emulator-5556` with the app language set to Croatian; the household Philips Google TV was offline during the final capture, so the floating-IME path is covered by the measured-inset implementation and the docked-IME emulator run only.
- **Feature screenshots (2026-10-25):** `final2-guide-skeleton.png`, `final2-guide.png`, `final2-rail-focus.png`, `final2-settings.png`, `final2-login2.png`, `final2-login4.png`, `final2-channels-phone.png`, `final2-channel-focus2-phone.png`, `tv-deployed.png` in `.impeccable/review/`; captured on `emulator-5556` and on the household Philips Google TV TA5 (`192.168.0.124:41193`).
- **Feature endpoints:** transcribed from `/Users/marko/maxtv-feature-specs/00-shared-context.md`, `08-implementation-index.md` and specs 01–06. Guest checks against `/home/root/categories`, `/home/page/{id}`, `/home/component/{id}/highlights` and `/details/program/{id}` returned 200 on 2026-10-25. Auth-gated shapes remain as specified until the first household login.
- **Not shipped:** the decision-page sketches and the review screenshots are design evidence, not app assets.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
