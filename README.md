# TrackStuff

Minimal Android app to track movies, series, documentaries and anime. Kotlin, Jetpack Compose, Room.

- **Library**: Continue watching · Start watching · History, with season/episode progress.
- **Discover**: trending, popular and new titles from TMDB, TVDB, IMDb (Top 250, Popular now) and omdb.org.
- **Search & detail pages** with a fallback cascade: TMDB → TVDB → OMDb API → IMDb datasets → omdb.org
  (the last two are imported locally and work offline). Ratings from IMDb, Rotten Tomatoes, Metacritic.
- **Sync** with Trakt and Simkl, automatic and two-way (statuses, progress, removals).
- Everything is cached; explicit refreshes are limited to once an hour. Library export / import as JSON.

## Setup

Put your keys in `local.properties` (git-ignored) or enter them in Settings. All are optional: a
missing source is simply skipped.

```properties
TMDB_API_KEY=…            # https://www.themoviedb.org/settings/api (v3 key or v4 read access token)
TVDB_API_KEY=…            # https://thetvdb.com/dashboard/account/apikey
OMDB_API_KEY=…            # https://www.omdbapi.com/apikey.aspx
TRAKT_CLIENT_ID=…         # https://trakt.tv/oauth/applications (redirect: urn:ietf:wg:oauth:2.0:oob)
TRAKT_CLIENT_SECRET=…
SIMKL_CLIENT_ID=…         # https://simkl.com/settings/developer/
SIMKL_CLIENT_SECRET=…
```

## Build

```
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # signed if RELEASE_STORE_FILE / passwords are set in local.properties
./gradlew :app:testDebugUnitTest
```

On Windows without a JDK on the PATH: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.

## Credits & licenses

Data services: TMDB (this product uses the TMDB API but is not endorsed or certified by TMDB),
TheTVDB (metadata provided by TheTVDB), OMDb API (CC BY-NC 4.0), omdb.org (Open Media Database,
community data under free licenses), IMDb datasets (information courtesy of IMDb, used with
permission, non-commercial use), Rotten Tomatoes / Metacritic (scores via OMDb API), Trakt, Simkl.

Open-source libraries (all Apache License 2.0): Kotlin & kotlinx, AndroidX & Jetpack Compose
(Material 3, Room, DataStore, Navigation 3, Lifecycle), OkHttp, Retrofit, Moshi, Coil, Apache Commons
Compress, Material Components for Android.

TrackStuff is a personal, non-commercial app. Posters and metadata belong to their respective owners.
