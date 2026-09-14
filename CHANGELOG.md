# Changelog

All releases are published on GitHub with a signed APK (same key throughout, so each version
installs over the previous one). The APK ships without any API key: enter yours in Settings or
import a settings file.

## v0.1.7 — 2026-09-14

- **Settings → Cache → "Cache Discover"**: caches the page and poster of every title of every TMDB and TVDB Discover row (about a thousand pages, ~150 MB the first time; already-cached pages are not downloaded again). Progress line and Cancel; at most once an hour.

## v0.1.6 — 2026-09-14

- **Offline search fix**: the IMDb / omdb.org search no longer scans the alias tables (millions of rows with the Full datasets), which took many seconds per keystroke and looked like an endless load. Aliases are prefix-only (indexed); the fallback scan covers the title tables only.
- **Discover offline**: the first pages of each row are now prefetched on mobile data too (8 titles per row; 20 on Wi-Fi), and a line under the chips shows the progress ("Caching pages for offline use… 46/214") — about a minute after a load.

## v0.1.5 — 2026-09-14

- **Offline**: Discover rows are saved to a file and reused when a source is unreachable; on Wi-Fi the first 20 pages of every online row are prefetched (JSON + poster) so they open offline; a request that fails while the system reports connectivity falls back to the cached response.
- **Search**: source chips (Auto, TMDB, TVDB, IMDb, omdb.org) — one database only, or the automatic cascade.
- **Pages**: TVDB link on movies too (id looked up by IMDb id, stored); compact header (poster as tall as the text column, ratings beside it, genres on their own line), Info section (country, seasons, episodes, age rating) above Credits, episode list folded behind the Progress row, one-line where-to-watch.

## v0.1.4 — 2026-09-13

### Library and pages
- **+1** on Continue watching cards, **Next: SxEy** with episode title and air date, **Upcoming** row (next episode to air).
- **Episode lists** on series pages (TMDB, then TVDB, then OMDb API): season chips, tap an episode to set the position; folded behind the Progress row.
- **Where to watch** in your region (TMDB, data by JustWatch): streaming, free, rental, purchase; logos open JustWatch.
- Compact page: ratings beside the poster, Info section (country, seasons, episodes, age rating), synopsis before credits.
- Library **filter** (title, category) and **pull-to-refresh** (sync now); Discover pull-to-refresh.
- **Shared links**: IMDb, TMDB, Trakt and TVDB pages open the matching title (share sheet, or links once "Open supported links" is enabled).

### Discover
- TMDB: **Upcoming** (your region, re-releases filtered) and **Top rated** rows replace In theaters; series gain Top rated.

### Fixes and internals
- Secrets (keys, tokens) kept out of the Android cloud backup; masked in debug logs.
- Discover memo and rendering fixes, TVDB thumbnails, indexed offline search, readable error messages, sync failures shown once.
- Blank strip above the tab bar removed; indigo theme; non-blocking start-up.
- Library database 9 → 11 (migrations, no data loss). Rating and notes dropped.

## v0.1.3 — 2026-09-13

### Sync fixes (important before syncing with an existing Trakt / Simkl account)
- Titles imported from a service are no longer pushed straight back (a first sync used to duplicate the whole watched history).
- Pushes are deltas: only the episodes watched since the last push; moving back un-marks the later episodes; back to Plan to watch clears the plays and returns the title to the watchlist. Verified against real Trakt and Simkl accounts.
- Titles a service cannot resolve stay unsynced instead of being deleted at the next pull; Simkl reconciliation no longer deletes titles that were pushed but never pulled; disconnecting a service forgets its sync state; no reconciliation on the first pull after connecting.
- Series creators (IMDb datasets) now include the `created by` credit.

### Robustness
- Library exports from 0.1.0 import again; imports are cancellable and an interrupted dataset import is flagged (data not used until re-imported); pages are never degraded by a refresh; per-step and overall progress with ETA during imports.

## v0.1.2 — 2026-09-12

- Dataset imports (IMDb, omdb.org) show the current step with its percentage and ETA, plus an overall percentage and ETA for the whole import (download + parsing).
- IMDb: series creators are now also read from the `created by` job (e.g. Breaking Bad); an update of already imported data is allowed once before the monthly limit.
- A cancelled import goes back to idle instead of showing an error.
- Statuses verified on device: movies are Plan to watch / Completed, series switch to Watching on the first episode marked.

## v0.1.1 — 2026-09-12

- Statuses reduced to Plan to watch / Watching / Completed: Watching follows the progress (first episode marked), movies are planned or completed. Existing libraries are migrated.
- Settings export / import: API keys, Trakt / Simkl credentials and sign-in tokens, preferences — set up another phone without re-entering keys or re-pairing.
- A sync that failed offline is retried automatically when the network comes back.

## v0.1.0 — 2026-09-12

First release. Enter your API keys in Settings (TMDB, TVDB, OMDb API, Trakt, Simkl). Installable with Obtainium from this repository.
