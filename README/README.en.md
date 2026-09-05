<div align="center">

<a href="https://github.com/normalwindow/Pixiv-Shaft-Win">
  <img src="../docs/svg/hero-en.svg" alt="PixShaft-Win — The whole of Pixiv, on your Windows desktop" width="100%">
</a>

<br>

[![GitHub Release](https://img.shields.io/github/v/release/normalwindow/Pixiv-Shaft-Win?include_prereleases&style=for-the-badge&logo=windows&color=0078d4)](https://github.com/normalwindow/Pixiv-Shaft-Win/releases)
[![GitHub Stars](https://img.shields.io/github/stars/normalwindow/Pixiv-Shaft-Win?style=for-the-badge&logo=github&color=f5c842)](https://github.com/normalwindow/Pixiv-Shaft-Win/stargazers)
[![License](https://img.shields.io/github/license/normalwindow/Pixiv-Shaft-Win?style=for-the-badge&color=blue)](../LICENSE)

<sub>Windows 10+ x64 · Kotlin + Compose Multiplatform · free & open source · no ads</sub>

[简体中文](../README.md) · **English** · [日本語](README.ja.md)

The Android app lives upstream at [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft).

</div>

> [!NOTE]
> An unofficial third-party [Pixiv](https://www.pixiv.net) client, forked from [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) (`classic` branch). All artworks belong to their creators and Pixiv. For learning and communication only.

---

## What is this

**PixShaft-Win** is the **Windows desktop branch** of Pixiv-Shaft, the well-known open-source Android Pixiv client. The upstream Android app stays as-is on the `classic` branch; this repo adds a `desktop/` module — a native Windows client written with **Kotlin + Compose Multiplatform**. Not a web wrapper, not Electron: a plain Win32 process with a GPU-rendered Compose UI.

Sign-in uses the system WebView2 (same approach as Pixeval). Networking defaults to **direct connection in mainland China** (Chromium QUIC + no-SNI image channel) — no proxy needed.

## How it differs from upstream (Android)

| | Upstream Android app | This desktop client |
|---|---|---|
| Platform | Android 7.0+ | Windows 10+ x64 |
| Stack | View + XML (classic) | Compose Multiplatform desktop |
| Sidebar | Bottom bar / tablet rail | Hover-expanding rail (60dp strip → 196dp overlay, zero content shift) |
| Browsing | Single pane navigation | Waterfall jump / two-pane with one-click pane swap |
| Zoom | Fixed columns | **Ctrl+wheel / pinch infinite zoom** (40%–300%, with ruler & reset) |
| Continuity | Reload on back | Page data + scroll position cached — no refresh on back/switch |
| Rankings | 19 boards + date | Same 19 boards + date picker |
| Following | All/public/private | Illust·Manga / Novels × All/Public/Private |
| Search | Full filters | History + Discover + sort/match/bookmark-count/R-18 (persisted) |
| Image preview | In-app viewer | Full-window preview: wheel zoom, drag pan, multi-page, Esc |
| Download mgmt | Service + notifications | Queue page: pause/resume, per-job retry/cancel, open folder |
| Shortcuts | Few | `Ctrl+F` search, `Esc` back, `1–5` tabs, arrows in preview |

Upstream Android features and screenshots: [upstream README](https://github.com/CeuiLiSA/Pixiv-Shaft#readme).

## Desktop features

- **Home** — recommendations (illust/manga) + live trending-tag entry + one-tap refresh
- **Ranking** — 19 boards with a calendar date picker for historical charts
- **Following** — illust·manga / novels × all/public/private
- **Search** — history + discover + sort / match / bookmark-count / R-18 filters (persisted)
- **Artwork** — multi-page pager, ugoira autoplay, manga reader, paginated related-works waterfall, comments (read & post), bookmark/download/follow automations
- **User** — Illustrations / Manga / Bookmarks, collapsing author header, follow button
- **Feature columns** — pin a search / author / following / related / ranking feed; they survive restart; click the card to open
- **Downloads** — filename templates, per-author / R18 / AI folders, overwrite policy, concurrency, queue manager; feature columns can enqueue everything at once
- **Extras** — multi-account, reverse image search, network self-check, FANBOX, pixiv COMIC, local novels, local library, dark mode, accent colors, custom title bar

## Install

Grab from [GitHub Releases](https://github.com/normalwindow/Pixiv-Shaft-Win/releases):

| File | Notes |
|---|---|
| `PixShaft-Win-x.y.z.msi` | Windows installer (recommended), Start Menu + uninstall |
| `PixShaft-Win-x.y.z.exe` | Same installer as EXE |
| `PixShaft-Win-x.y.z-portable.zip` | Portable; data lives next to the exe |

Sign in with **WebView2** on first launch (WebView2 Runtime required, preinstalled on Windows 11).

## Build

```bat
git clone https://github.com/normalwindow/Pixiv-Shaft-Win.git
cd Pixiv-Shaft-Win
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat :desktop:packageReleaseDistributionForCurrentOS
```

Requires **JDK 17**. `gradle.properties` still contains the upstream author's macOS toolchain paths — on other machines point `JAVA_HOME` at a local JDK 17, or pass `-Dorg.gradle.java.installations.paths=`. Artifacts land in `desktop/build/compose/binaries/main-release/`. To just run: `gradlew.bat :desktop:run`.

## Shortcuts

| Key | Action |
|---|---|
| `Ctrl+F` / `4` | Search |
| `1` / `2` / `3` / `5` | Home / Ranking / Following / Me |
| `Esc` | Close preview / search / drawer / pane, else go back |
| `←` / `→` | Page in image preview |
| `Ctrl+wheel` | Infinite waterfall zoom |

## Screenshots

| | |
|---|---|
| ![home](../docs/screenshots/home.webp) | ![detail](../docs/screenshots/detail.webp) |
| ![ranking](../docs/screenshots/ranking.webp) | ![search](../docs/screenshots/search.webp) |
| ![user](../docs/screenshots/user.webp) | ![download](../docs/screenshots/download.webp) |

## Layout

```
app/            Upstream Android app (kept as-is, tracks classic)
shared/         Shared Pixiv App-API client, models, OAuth
desktop/        Windows desktop client (main development)
  ui/           Compose UI: nav, waterfall, detail, preview, settings…
  net/          Network diagnostics
  webview-auth/ WebView2 login helper (.NET 8)
docs/           Protocol / architecture notes from upstream
```

## Syncing with upstream

This repo's `classic` branch tracks upstream `classic`. Upstream commits are merged periodically; `shared/` and `app/` avoid breaking changes so merges stay possible. Desktop code lives in `desktop/` plus a few increments in `shared/`.

## Disclaimer

Not affiliated with Pixiv. Respect the terms of service and creators' copyright; downloads are for personal offline viewing only.

## License

[GPL-2.0](../LICENSE) (inherited from upstream)

## Credits

- [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) — the foundation
- [Pixeval](https://github.com/Pixeval/Pixeval) — WebView2 sign-in reference
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) · [Coil](https://github.com/coil-kt/coil) · [OkHttp](https://github.com/square/okhttp) · [Retrofit](https://github.com/square/retrofit)
