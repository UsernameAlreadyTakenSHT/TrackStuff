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
./gradlew :app:assembleRelease    # signed if RELEASE_STORE_FILE / passwords are set; ships without keys
                                  # (set RELEASE_EMBED_KEYS=true for a personal build with your keys)
./gradlew :app:testDebugUnitTest
```

On Windows without a JDK on the PATH: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.

## Credits & licenses

### Data services

| | Service | Used for | Terms / attribution |
|:-:|---|---|---|
| <img src="docs/logos/tmdb.svg" height="22" alt=""> | [TMDB](https://www.themoviedb.org/) | Posters, metadata, charts | This product uses the TMDB API but is not endorsed or certified by TMDB. |
| <img src="docs/logos/tvdb.png" height="28" alt=""> | [TheTVDB](https://thetvdb.com/) | Posters, metadata, charts | Metadata provided by TheTVDB. Please consider adding missing information or subscribing. |
| <img src="docs/logos/omdb-api.png" height="20" alt=""> | [OMDb API](https://www.omdbapi.com/) | Ratings, fallback posters | The Open Movie Database — [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/). |
| <img src="docs/logos/imdb.svg" height="22" alt=""> | [IMDb datasets](https://developer.imdb.com/non-commercial-datasets/) | Offline ratings, Top 250, credits | Information courtesy of [IMDb](https://www.imdb.com). Used with permission. Non-commercial use only. |
| <img src="docs/logos/omdb-org.png" height="28" alt=""> | [omdb.org](https://www.omdb.org/) | Offline posters, synopses, search | Open Media Database — community data under free licenses; images under their own licenses. |
| <img src="docs/logos/rottentomatoes.svg" height="26" alt=""> <img src="docs/logos/metacritic.svg" height="26" alt=""> | [Rotten Tomatoes](https://www.rottentomatoes.com/) · [Metacritic](https://www.metacritic.com/) | Scores | Values via OMDb API; the chips open the official sites. |
| <img src="docs/logos/trakt.png" height="28" alt=""> | [Trakt](https://trakt.tv/) | Sync | Trakt API terms. |
| <img src="docs/logos/simkl.png" height="28" alt=""> | [Simkl](https://simkl.com/) | Sync | Simkl API terms. |

### Open-source libraries

All under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

| Library | Author |
|---|---|
| [Kotlin & kotlinx](https://github.com/JetBrains/kotlin) | JetBrains |
| [AndroidX & Jetpack Compose](https://developer.android.com/jetpack) (Material 3, Room, DataStore, Navigation 3, Lifecycle) | Android Open Source Project |
| [OkHttp](https://github.com/square/okhttp) · [Retrofit](https://github.com/square/retrofit) · [Moshi](https://github.com/square/moshi) | Square |
| [Coil](https://github.com/coil-kt/coil) | Coil Contributors |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Apache Software Foundation |

TrackStuff is a personal, non-commercial app. Each service is used under its own terms; posters and
metadata belong to their respective owners.
