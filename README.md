# MediaX

**MediaX** is a modern Material Design 3 Android application written in Kotlin and Jetpack Compose for discovering movies & TV shows, browsing configured source sites in an integrated browser, resolving direct media URLs, and managing multi-connection downloads.

- **Repository:** `https://github.com/alonedroid-maker/mixmedia`
- **Package / Application ID:** `com.aistudio.mixmedia.vpxkqm` (preserved for seamless upgrades)

## Core Features

- **MediaX Home Dashboard:** Unified entry point with instant Movie/TV search, quick tool cards, configured source site shortcuts, recent searches, favorites, recently viewed titles, and recently resolved links.
- **Movie & TV Discovery:** Fuzzy title matching, English & Persian metadata enrichment, type filters (`All`, `Movies`, `TV Shows`), cinematic backdrop hero details, horizontal cast cards, and season/episode explorer.
- **Source Discovery & Integrated Browser:** Configurable source catalog (`SourceSitesCatalog.kt` + in-app Settings management), honest metadata badge parsing (`1080p`, `MKV`, `MP4`, `Persian Dub/Sub`, Direct vs Page), HTTPS security indicator, and download prompt interception.
- **Direct Video Link Resolver:** Redirect tracing, stream inspection, VLC external handoff, and social/short-link diagnostics.
- **Multi-Connection Download Manager:** Queued, active, paused, completed, and failed filters with live counts, adjustable queue limit & parallel segments (1–8), SAF directory picker, pause/resume/retry/prioritize, and zero-permission **Share Downloaded File** via `FileProvider` / MediaStore URI.
- **Adaptive Material Design 3 UI:** Edge-to-edge layout, compact bottom `NavigationBar` + wide-screen `NavigationRail`, and persisted **System / Dark / Light** theme switcher.

## Centralized Release Version Management

All version numbers and GitHub Release metadata live in **`/release-info.properties`**:

```properties
VERSION_NAME=1.1
VERSION_CODE=2
RELEASE_TITLE=MediaX UI and Performance Improvements
RELEASE_NOTES=Improved Material Design interface, enhanced usability, and performance optimizations.
```

Both `app/build.gradle.kts` and `.github/workflows/android.yml` read and validate `release-info.properties` automatically.

### Creating a New Signed Release

1. Update `release-info.properties` (`VERSION_NAME`, increment `VERSION_CODE` as a positive integer, and update `RELEASE_TITLE` / `RELEASE_NOTES`).
2. Commit and push to `main`.
3. Create and push a matching Git tag (e.g. `git tag v1.2 && git push origin v1.2`).
4. GitHub Actions automatically builds and signs **`MediaX-v<VERSION_NAME>-release.apk`** using repository secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`), generates a `.sha256` checksum, uploads the artifact `MediaX-v<VERSION_NAME>-Signed-Release`, and publishes a GitHub Release on tag push.
