# TrackStuff — movies, series, documentaries & anime tracker

Android app (Kotlin, Jetpack Compose, Room) to track what you watch, with pages enriched from
several databases, large offline datasets, and Trakt / Simkl sync. Minimalist by design.

## Features

- **Library** (offline) in three rows — **Continue watching** (watching / on hold), **Start watching**
  (planned), **History** (completed / dropped). Categories Movie / Series / Documentary / Anime
  (detected automatically), season/episode progress with automatic season rollover and completion.
  No personal ratings or notes — deliberately minimal.
- **Discover**: pick a source (TMDB / TVDB / IMDb / omdb.org) and a type (Movies / Series); all of the
  source's lists show up:
  - TMDB — trending this week, popular, in theaters / on the air (100 titles, paginated);
  - TVDB — popular (TVDB score), new this year (100 titles);
  - IMDb — **Popular now** (titles that gained the most votes since the previous import, an offline
    approximation of MovieMeter / TVMeter; recent releases by votes until a second import exists) and
    **Top 250** movies / series (IMDb's weighted formula);
  - omdb.org — most voted, top rated (community votes).
- **Search** with a fallback cascade: **TMDB → TVDB → OMDb API → IMDb datasets → omdb.org**; the last
  two work offline.
- **Detail page**: poster and synopsis from TMDB first, then TVDB, then OMDb API, then IMDb datasets,
  then omdb.org (each field shows its source); dead poster links (OMDb API) and TVDB "missing image"
  placeholders are skipped so the next source can fill in; full release date, countries, runtime,
  seasons, age rating; **ratings** IMDb, Rotten Tomatoes, Metacritic (values via OMDb API, or IMDb
  datasets for the IMDb rating) — each chip opens the site; **credits** (director, or creator for
  series — never episode directors; writer, producer, studio / network, 5 leading actors with roles);
  next episode date for series; links IMDb · TMDB · TVDB · OMDB.
- **omdb.org offline** (~26 MB, free license): ~95,000 movies/series, translated titles, IMDb ids,
  posters, genres, synopses, cast and roles, directors, writers, countries, runtimes, community votes.
- **IMDb datasets offline** (official datasets, once a month at most — no incremental updates exist),
  filtered to movies/series with ≥ 1,000 votes (~65,000):
  - *Standard* (~280 MB): `title.ratings`, `title.basics`, `title.episode` → ratings and vote counts
    without any key, episodes per season, Top 250, Popular now;
  - *Full* (~1.9 GB, 30–45 min): + `title.crew`, `title.principals`, `name.basics`, `title.akas` →
    cast and roles, directors, writers, series creators, translated titles (offline search in your
    language).
  - Data priority offline: IMDb datasets before omdb.org.
- **Cache**: JSON responses (24 h) and posters (30 days) in a shared OkHttp cache, served offline; size
  tiers 200 MB → 5 GB or unlimited (1 GB by default). Trakt and Simkl are never cached.
- **Trakt.tv and Simkl sync** (device-code / PIN OAuth, no server), two-way and automatic: statuses,
  progress and removals pushed about a minute after a local change; remote changes pulled when the app
  opens (at most every 15 min). Each sync starts with the activities endpoint and only fetches deltas
  (`date_from` on Simkl), following both services' published rules — no background polling. A title
  changed locally since its last sync wins; when both services are connected, the one chosen in Settings
  is trusted first. Imported titles are enriched automatically.
- **Localized**: English by default, French translation; page language and region follow the phone.
- **Credits & licenses** dialog at the bottom of Settings (data services attributions, open-source
  libraries).

## API keys

Everything is optional; each missing source is simply skipped in the cascade. Keys are entered in
**Settings** (saved automatically) or put in `local.properties` (git-ignored) as defaults:

```properties
TMDB_API_KEY=…            # https://www.themoviedb.org/settings/api (v3 key or v4 read token)
TVDB_API_KEY=…            # https://thetvdb.com/dashboard/account/apikey
OMDB_API_KEY=…            # https://www.omdbapi.com/apikey.aspx (OMDb API: ratings, poster/synopsis fallback)
TRAKT_CLIENT_ID=…         # https://trakt.tv/oauth/applications (redirect: urn:ietf:wg:oauth:2.0:oob)
TRAKT_CLIENT_SECRET=…
SIMKL_CLIENT_ID=…         # https://simkl.com/settings/developer/
SIMKL_CLIENT_SECRET=…
```

Without any key, search and pages still work on the omdb.org and IMDb data imported locally.

> omdb.org ≠ OMDb API: the first is the Open Media Database (omdb.org, free data dumps); the second is
> omdbapi.com (an IMDb aggregator, source of the Rotten Tomatoes / Metacritic scores).

## Build

```
./gradlew :app:assembleDebug      # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # unit tests (API parsing, CSV reader, TVDB adapters, null lists)
```

On Windows without a JDK on the PATH: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.

## Structure

```
app/src/main/java/com/example/trackstuff/
├── domain/Models.kt              domain models (MediaKind, WatchStatus, MediaDetails, Credits…)
├── data/local/                   Room: library (AppDatabase), omdb.org (OmdbOrgDatabase), IMDb (ImdbDatabase)
├── data/remote/{tmdb,tvdb,omdb,trakt,simkl}/   Retrofit clients + Moshi models
├── data/remote/Network.kt        shared OkHttp client with disk cache (JSON + images), offline mode
├── data/omdborg/                 omdb.org CSV dumps import (MySQL-style CSV reader) and local queries
├── data/imdb/                    IMDb datasets import (Standard / Full), Top 250, Popular now
├── data/repository/MetadataRepository.kt       search / discover / detail cascade across all sources
├── data/repository/LibraryRepository.kt        local library
├── data/sync/{Trakt,Simkl}SyncService.kt       auth + sync
├── data/settings/SettingsRepository.kt         DataStore (keys, tokens, language, region, cache size)
└── ui/{library,discover,search,detail,settings,navigation,components}/   Compose + Navigation 3
```

## Status mapping with sync services

| Local          | Trakt                          | Simkl        |
|----------------|--------------------------------|--------------|
| Plan to watch  | watchlist                      | plantowatch  |
| Watching       | history of watched episodes    | watching     |
| Completed      | history (movie / whole show)   | completed    |
| On hold        | removed from watchlist         | hold         |
| Dropped        | removed from watchlist         | dropped      |

Removals: a title deleted here is removed from both services; a title removed on one service is
removed here and from the other service.

Conflicts: a title changed locally since its last sync is pushed and not overwritten by the remote
version; otherwise the remote version wins.

## Credits & licenses

Data services: TMDB (this product uses the TMDB API but is not endorsed or certified by TMDB),
TheTVDB (metadata provided by TheTVDB), OMDb API (CC BY-NC 4.0), omdb.org (Open Media Database,
community data under free licenses), IMDb datasets (information courtesy of IMDb, used with
permission, non-commercial use), Rotten Tomatoes / Metacritic (scores via OMDb API), Trakt, Simkl.

Open-source libraries (all Apache License 2.0): Kotlin & kotlinx, AndroidX & Jetpack Compose
(Material 3, Room, DataStore, Navigation 3, Lifecycle), OkHttp, Retrofit, Moshi, Coil, Apache Commons
Compress, Material Components for Android.

TrackStuff is a personal, non-commercial app. Posters and metadata belong to their respective owners.
