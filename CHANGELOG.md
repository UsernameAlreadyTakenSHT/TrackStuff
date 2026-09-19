## v0.1.10 — 2026-09-19

### New application id — read before installing
The package is now `io.github.usernamealreadytakensht.trackstuff` (was `com.example.trackstuff`). Android sees it as a **new app**: it installs next to 0.1.9 and starts empty.
1. In 0.1.9: Settings → Backup → **Export** the library and the settings (two JSON files).
2. Install 0.1.10, then Settings → Backup → **Import** both files.
3. Uninstall the old TrackStuff; re-download the IMDb / omdb.org datasets if you use them; in Obtainium, add the repository again.

### Fixes
- **Offline posters**: the Discover prefetch never actually stored the posters (responses were closed unread; OkHttp only caches a body while it is read). Fixed — "Cache Discover" and the automatic prefetch now keep the posters.
- **"Connected, no internet"** (Wi-Fi without uplink, no mobile signal, captive portal) counts as offline: the cache answers at once instead of every request timing out; timeouts shortened.

Same signing key. Debug keys are not embedded: set your API keys in Settings or import your settings file.

## v0.1.9 — 2026-09-19

- **Offline search**: full-text index on the IMDb and omdb.org datasets (titles and translated titles, every word as a prefix) — instant whatever the position of the word. Existing data is indexed by a migration in the background at the first start (a minute or two with the Full datasets), no re-import needed.
- **Trakt / Simkl sign-in**: the Trakt token is renewed a week before it expires, a 401 during a sync triggers one renewal and a retry, and a refused renewal (or a revoked Simkl token) disconnects the account with a "connect again in Settings" message instead of failing silently.
- **Landscape / tablets**: the tabs move to a rail on the left on wide screens.
- **Themed icon** (Android 13+): the film-frame holes are cut out so they stay visible when the icon is tinted.

IMDb and omdb.org databases 4 → 5 (migrations, no data loss). Install over 0.1.8 (same signing key). Debug keys are not embedded: set your API keys in Settings or import your settings file.

## v0.1.8 — 2026-09-18

- **Discover**: the automatic page prefetch really runs once every 12 h (the stamp is now stored, so a cold start no longer walks the ~200 cached pages with the "Caching pages…" line); the saved rows appear at once on start-up while the fresh load runs in the background; the pull-to-refresh indicator only shows for an explicit refresh.
- **Pages**: the Sources line names where the ratings and the credits came from (TMDB, TVDB, OMDb API, IMDb datasets, omdb.org), not only the poster and description.
- **Settings**: realistic duration for the Full IMDb import (5–10 min on a recent phone).

Library database 11 → 12 (migration, no data loss). Install over 0.1.7 (same signing key). Debug keys are not embedded: set your API keys in Settings or import your settings file.

## v0.1.7 — 2026-09-14

## v0.1.7 — 2026-09-14

One thing, for people who want Discover to work with no network at all.

### Added
- **Settings → Cache → "Cache Discover"** caches the page and poster of every title of every TMDB and TVDB Discover row — about a thousand pages, ~150 MB the first time; pages already cached are not downloaded again. Progress line and Cancel; at most once an hour.

## v0.1.6 — 2026-09-14

## v0.1.6 — 2026-09-14

A search that looked like an endless load, and the offline prefetch extended to mobile data.

### Fixed
- **Offline search scanned the alias tables.** With the Full datasets that is millions of rows, so the IMDb / omdb.org search took many seconds per keystroke and looked like it never finished. Aliases are now prefix-only (indexed); the fallback scan covers the title tables only.

### Changed
- **Discover prefetches on mobile data too**, 8 titles per row (20 on Wi-Fi), and a line under the chips shows the progress ("Caching pages for offline use… 46/214") — about a minute after a load.

## v0.1.5 — 2026-09-14

## v0.1.5 — 2026-09-14

Offline mode, source chips in search, and a more compact title page.

### Added
- **Offline.** Discover rows are saved to a file and reused when a source is unreachable; on Wi-Fi the first 20 pages of every online row are prefetched (JSON + poster) so they open offline; a request that fails while the system reports connectivity falls back to the cached response.
- **Source chips in search** (Auto, TMDB, TVDB, IMDb, omdb.org): one database only, or the automatic cascade.
- **TVDB link on movies too**, the id looked up by IMDb id and stored.

### Changed
- **Compact title page**: poster as tall as the text column, ratings beside it, genres on their own line, Info section (country, seasons, episodes, age rating) above Credits, episode list folded behind the Progress row, one-line where-to-watch.

## v0.1.4 — 2026-09-13

## v0.1.4 — 2026-09-13

Episode-level tracking, where-to-watch, shared links, and a batch of fixes and internals.

### Added
- **+1 on Continue watching cards**, **Next: SxEy** with episode title and air date, and an **Upcoming** row (next episode to air).
- **Episode lists on series pages** (TMDB, then TVDB, then OMDb API): season chips, tap an episode to set the position; folded behind the Progress row.
- **Where to watch in your region** (TMDB, data by JustWatch): streaming, free, rental, purchase; logos open JustWatch.
- **Library filter** (title, category) and **pull-to-refresh** (sync now); Discover pull-to-refresh.
- **Shared links**: IMDb, TMDB, Trakt and TVDB pages open the matching title — from the share sheet, or directly once "Open supported links" is enabled.
- **Discover, TMDB**: Upcoming (your region, re-releases filtered) and Top rated rows; series gain Top rated.

### Changed
- **Compact page**: ratings beside the poster, Info section (country, seasons, episodes, age rating), synopsis before credits.
- **Discover, TMDB**: Upcoming and Top rated replace In theaters.
- **Indigo theme**, blank strip above the tab bar removed, non-blocking start-up.
- **Library database 9 → 11** (migrations, no data loss).

### Fixed
- **Secrets (keys, tokens) are kept out of the Android cloud backup** and masked in debug logs.
- **Discover memo and rendering fixes**, TVDB thumbnails, indexed offline search, readable error messages, sync failures shown once.

### Removed
- **Rating and notes** on titles.

## v0.1.3 — 2026-09-13

## v0.1.3 — 2026-09-13

Sync fixes — important before syncing with an existing Trakt / Simkl account — and a sturdier
import.

### Fixed
- **A first sync duplicated the whole watched history**: titles imported from a service were pushed straight back. They no longer are.
- **Pushes are deltas**: only the episodes watched since the last push; moving back un-marks the later episodes; back to Plan to watch clears the plays and returns the title to the watchlist. Verified against real Trakt and Simkl accounts.
- **Titles a service cannot resolve stay unsynced** instead of being deleted at the next pull; Simkl reconciliation no longer deletes titles that were pushed but never pulled; disconnecting a service forgets its sync state; no reconciliation on the first pull after connecting.
- **Library exports from 0.1.0 import again.**
- **Pages are never degraded by a refresh.**

### Changed
- **Imports are cancellable**, an interrupted dataset import is flagged (its data is not used until re-imported), and each step shows its own progress and ETA alongside the overall one.
- **Series creators from the IMDb datasets** now include the `created by` credit.

## v0.1.2 — 2026-09-12

## v0.1.2 — 2026-09-12

Import progress you can read, and a few things verified on a real phone.

### Added
- **Dataset imports (IMDb, omdb.org) show the current step** with its percentage and ETA, plus an overall percentage and ETA for the whole import (download + parsing).

### Changed
- **IMDb series creators** are also read from the `created by` job (e.g. Breaking Bad); an update of already imported data is allowed once before the monthly limit.

### Fixed
- **A cancelled import goes back to idle** instead of showing an error.
- **Statuses verified on device**: movies are Plan to watch / Completed, series switch to Watching on the first episode marked.

## v0.1.1 — 2026-09-12

## v0.1.1 — 2026-09-12

Three statuses instead of many, and a way to move to another phone without re-entering keys.

### Added
- **Settings export / import**: API keys, Trakt / Simkl credentials and sign-in tokens, preferences — set up another phone without re-entering keys or re-pairing.
- **A sync that failed offline is retried** automatically when the network comes back.

### Changed
- **Statuses reduced to Plan to watch / Watching / Completed.** Watching follows the progress (first episode marked); movies are planned or completed. Existing libraries are migrated.

## v0.1.0 — 2026-09-12

## v0.1.0 — 2026-09-12

First release.

### Added
- **Track movies, series, documentaries and anime** from TMDB, TVDB, OMDb API and the IMDb / omdb.org datasets, with Trakt and Simkl sync.

