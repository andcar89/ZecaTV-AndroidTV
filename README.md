# IPTV Player for Android TV

Native Kotlin app for Android TV / Google TV. Leanback UI, ExoPlayer (Media3) playback.

> **Note:** This app was built with AI assistance (Claude, by Anthropic) — every feature, fix, and line in this repo was written by Claude, prompted and directed by a human. It's a personal project, provided as-is with no warranty; use it at your own discretion.

## Features
- **Sidebar navigation (TiviMate-style)** — a fixed icon rail (Search, Live TV,
  Favorites, Settings) plus an expandable category panel (Favorites / All channels /
  each playlist group). The category panel collapses to just the icon rail once you
  D-pad right into the channel list, and re-expands when focus returns to it.
- **List or grid channel view** — list rows show the logo on the left with the name
  and group beside it; grid shows a big logo with the name below. Toggle on-screen
  with the button in the content header, or set your default in Settings.
- **Inline preview** — press OK once on a channel to preview it in a small floating
  window (top-right of the channel area) without leaving the list; press OK again
  on that same channel to open it fullscreen. Pressing OK on a *different* channel
  just switches the preview.
- **Long-press actions** — long-press a channel to either add/remove it from
  Favorites directly, or open a small popup with **Favorite** + **Assign channel
  EPG** (pre-selecting that channel) — configurable in Settings.
- **M3U from URL** — paste a playlist link, plus an optional XMLTV EPG URL.
- **M3U from local file** — pick a `.m3u`/`.m3u8` file via the system file picker.
- **Xtream Codes portal** — log in with server / username / password; the app pulls
  live categories + streams via `player_api.php` and builds playable stream URLs.
- **EPG** — parses XMLTV (gzip or plain). Shows "Now / Next" on the player, and a
  full scrolling **TV Guide** grid (channels × time, 6-hour window from now — no
  day paging yet). Accessible from Settings.
- **Multiple EPG sources + per-channel mapping** — the provider's own EPG (M3U's
  optional URL, or Xtream's `xmltv.php`) is always loaded as the "primary" source.
  In **Settings**, add any number of extra XMLTV URLs, and hit **Refresh EPG now**
  to re-fetch all of them on demand (handy if a provider's guide data changed).
  In **Assign channel EPG**, point any individual playlist channel at a specific
  guide entry from *any* loaded source.
- **Now/next info in the channel list** — list rows (not grid, for now) show the
  current and next programme titles beneath the channel name, pulled from
  whichever EPG source that channel resolves to. Replaces the old group-name
  subtitle line to make room.
- **Channel logos in the TV Guide** — each row's fixed channel label now shows the
  logo alongside the name, same artwork used everywhere else in the app.
- **In-player channel switching** — while watching fullscreen, **LEFT** opens a
  sidebar of every channel in the active category so you can switch without
  leaving fullscreen; **RIGHT** opens a category picker, and choosing one updates
  the left sidebar's list; **DOWN** shows a channel info bar (logo, name, now/next)
  that auto-hides after a few seconds. A single ExoPlayer instance is reused across
  switches so it feels instant rather than restarting the screen.
- **Editable playlist source** — Settings → Playlist shows a summary of the
  current source and an **Edit playlist source** button that reopens the same
  form used on first setup, pre-filled with the current values, so you can switch
  between M3U/Xtream or update credentials without reinstalling. (Multi-playlist
  support — juggling more than one source at once — isn't built yet.)
- **Custom nav icons** — the sidebar rail's Search/Live/Favorites/TV Guide/Settings
  icons are hand-drawn monochrome vector drawables (`res/drawable/ic_nav_*.xml`) —
  clean outline/flat style, single light-gray color, sized to look crisp at any
  density.
- **Floating rail labels** — when a rail icon has focus, a small blue label
  appears beside it ("Search", "Live TV", "Favorites", "TV Guide", "Settings").
  The rail itself stays the same slim 64dp icon-only strip otherwise.
- **Rebranded to ZecaTV** — app name, launcher icon, Android TV banner, and welcome
  screen logo all use your own artwork: the reference TV image you provided,
  recolored to the app's accent blue (`#1565C0`) with a transparent background,
  paired with your Coolvetica font for the "ZecaTV" wordmark. These three are
  raster PNGs (`res/drawable-nodpi/ic_launcher.png`, `tv_banner.png`,
  `ic_app_logo_splash.png`) generated from your source image, not vector art —
  crisp at the fixed sizes they're shown at, but won't scale infinitely the way
  the hand-drawn nav icons do. That's expected for this kind of asset.
- **Welcome screen** — `StartActivity` is now the actual launcher activity. If no
  playlist source is configured yet, it shows the ZecaTV logo plus **Add
  Playlist** and **Settings** buttons instead of silently jumping into
  `AddSourceActivity`. If a source is already configured, it immediately forwards
  to `MainActivity` — returning users see no change from before.
- **Portuguese translation + language selector** — Settings → Language lets you
  switch between English and Português, using AndroidX's per-app language API
  (`AppCompatDelegate.setApplicationLocales`), which persists the choice and
  recreates activities automatically — no custom locale-storage code needed.
  Every user-facing string in the app (buttons, labels, hints, toasts, error
  messages, category names, sidebar tooltips) was extracted into `strings.xml` /
  `values-pt/strings.xml` and translated — 92 entries, verified to have identical
  keys in both languages. One deliberate gap: the M3U parser's fallback category
  name ("All", used only when a channel's playlist entry has no group-title at
  all) is generated deep in `M3uParser` before any `Context` is available, so
  it's still hardcoded English — a real but narrow edge case, not a general
  translation gap.
- **Search** — the search icon in the sidebar filters channels by name.
- **External player handoff** — press **MENU** on the remote while watching to hand
  the stream off to another installed player (e.g. VLC) via an Intent chooser.

## Project layout
```
app/src/main/java/com/ze/iptvplayer/
  MainActivity.kt               Sidebar + channel list/grid + long-press popup
  AddSourceActivity.kt          Form to configure M3U/Xtream source
  PlayerActivity.kt             ExoPlayer screen + external player handoff
  SearchActivity.kt             Leanback search over the loaded channel list
  SettingsActivity.kt           EPG sources, default view mode, long-press behavior, TV Guide entry
  ChannelMappingActivity.kt     Assign a channel to a specific guide entry/source
  EpgGridActivity.kt            Full channels × time EPG grid
  model/                        Channel, EpgProgramme, PlaylistSource, EpgSource, EpgChannelMapping
  parser/                       M3uParser, XtreamClient, EpgXmlParser
  data/                         PlaylistRepository, Prefs, FavoritesManager, EpgSourceManager,
                                 EpgMappingStore, UiPrefs
  ui/                           ChannelListAdapter (list/grid), SidebarCategoryAdapter,
                                 ChannelCardPresenter (used by Search only)
```

**Note on Search:** `SearchActivity` still uses the Leanback `SearchSupportFragment`
+ `ImageCardView` presenter (`ChannelCardPresenter`), independent of the new
sidebar UI. Its long-press always does a direct favorite toggle — it doesn't yet
respect the Settings "long-press behavior" choice or offer the Assign-EPG popup.
Worth unifying later if that inconsistency bothers you.

## Building
1. Open the `IptvTvApp` folder in **Android Studio** (Hedgehog or newer).
   It will offer to generate the Gradle wrapper and sync dependencies —
   accept both; that needs an internet connection the first time.
2. Set up an Android TV emulator (Tools → Device Manager → create a TV device,
   API 30+ recommended) or connect a real Android TV/Fire TV over `adb connect`.
3. Run the `app` configuration.

There's no `gradlew` checked in (the wrapper jar is a binary), but Android Studio
generates one automatically on first open. If you prefer the command line, run
`gradle wrapper` once inside the project folder (requires Gradle installed
locally), then use `./gradlew installDebug`.

## Known limitations / good next steps
- Channel list and EPG are cached in memory only (cleared on process death) —
  swap `PlaylistRepository`'s in-memory fields for a Room database if you want
  the list to survive without re-parsing every launch.
- The EPG grid covers a fixed 6-hour window starting now; no prev/next day paging yet.
- EPG programme cells have no enforced minimum width, so very short programmes can
  render as a thin sliver — trade-off made to keep every row's timeline pixel-aligned
  with the header.
- `tv_banner.xml` / `ic_launcher.xml` are placeholder solid-color vector drawables —
  swap in real artwork before you publish anywhere.
- HTTP (not just HTTPS) playlists/streams are allowed via
  `android:usesCleartextTraffic="true"`, since most IPTV panels serve plain HTTP.
- The external-player handoff triggers on the remote's **MENU** key; remap it in
  `PlayerActivity.onKeyDown` if your remote doesn't have one.
- `minSdk` is 23 (Android 6.0) — raised from 21 because the EPG grid's synced
  scrolling relies on `View.OnScrollChangeListener`, added in API 23.
- The grid view's span count is a fixed `GRID_SPAN_COUNT = 5` in `MainActivity` —
  fine on a 1080p TV, but worth making density-aware if you test on very
  different screen sizes.
- `showAsDropDown` for the long-press popup doesn't reposition itself near screen
  edges — a channel near the bottom/right of the list could show the popup partly
  off-screen. Worth swapping for a manually-positioned `PopupWindow.showAtLocation`
  if that turns out to be an issue in practice.
- The inline preview builds a brand-new `ExoPlayer` each time you press OK on a
  different channel. It's released in `onStop`, so backgrounding the app or
  navigating away always stops it — but it does mean switching previews rapidly
  briefly buffers each time, there's no pre-buffering/pooling.
- **"Chain validation fail" on some providers** — the app deliberately skips TLS
  certificate chain/hostname validation (see `network/LenientTls.kt`) for its
  playlist, EPG, and stream connections, since several IPTV/Xtream panels serve
  broken or self-signed HTTPS certificate chains that browsers/VLC tolerate but
  Android's stock TLS stack rejects outright. This is scoped to just those
  requests, not the app's TLS trust in general — but it does mean that traffic is
  vulnerable to a MITM presenting any certificate at all. Acceptable for talking to
  a provider you already trust; worth knowing about regardless.
- Now-playing info in the channel list calls `PlaylistRepository.nowNext()` per
  visible row on every bind — fine at normal EPG data sizes, but if a provider's
  guide is unusually large this could be worth caching/memoizing later.
- Grid view doesn't show now/next info, only list view does (grid cells are
  already tight on space for logo + name; happy to explore a compact treatment
  later if wanted).
- In the fullscreen player, long-pressing a channel in the LEFT sidebar does a
  quick favorite toggle only — no popup with an Assign-EPG shortcut there (unlike
  the main screen's long-press menu), to keep that sidebar's interaction simple.
- LEFT/RIGHT in the fullscreen player *always* toggles its sidebar open/closed,
  even if focus is already deep inside that sidebar's list — there's no other use
  for horizontal movement in a vertical list, so this was a deliberate simplification
  rather than an oversight.
- EPG timestamps are now parsed manually (substring + integer math) rather than
  via `SimpleDateFormat`, specifically to fix a bug where "now" programme info was
  frequently missing (while "next" worked) — traced to timezone-offset parsing
  silently falling back to the device's local timezone instead of the feed's
  actual UTC offset, shifting every timestamp. Worth re-testing "now" info
  broadly (list rows, mini-player, fullscreen info bar, TV Guide) to confirm.
- The provided nav icon artwork (photographic-style glowing tiles with baked-in
  text) didn't hold up when cropped down to rail-icon size — replaced with
  hand-drawn monochrome vector icons instead (`res/drawable/ic_nav_*.xml`). If
  you'd like the visual style to more closely echo the original artwork (colors,
  glow), that's a from-scratch redraw rather than a crop adjustment — let me know.
- The language selector only shows English/Português for now — adding more
  languages later just means a new `values-XX/strings.xml` with the same 92 keys
  plus one more radio button in Settings, no architecture changes needed.
- Per-app language switching (`AppCompatDelegate`) works standalone as built, but
  isn't wired into Android 13+'s system-level "App languages" settings screen —
  that's an optional extra (`locales_config.xml` + one manifest entry) I skipped
  to keep this round's scope contained; happy to add it if useful.
