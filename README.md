# ReelScout

A Kotlin Multiplatform / Compose Multiplatform app (Android, iOS, Desktop, Web via
Kotlin/Wasm) with an AI agent that helps find where a movie or TV show can be watched
for **free** — public-domain sources and ad-supported streamers only, via official APIs
(TMDB, Watchmode, Archive.org). No scraping, no torrent/indexer integration.

The agent is a genuine multi-step **tool-use loop**: it decides which lookups to make,
executes them against live data, and only then answers — it never guesses from its own
training knowledge of what's on which service. See `shared/src/commonMain/kotlin/com/
reelscout/agent/AgentLoop.kt`.

Full architecture, rationale, and the phased build plan are in **[ROADMAP.md](./ROADMAP.md)**.

## Module layout

```
androidApp/   Android application module - MainActivity + Application class, hosts composeApp
composeApp/   Compose Multiplatform UI - androidMain, iosMain, desktopMain, wasmJsMain, commonMain
shared/       domain models, TMDB/Watchmode/Archive.org repositories, the agent tool-use loop
edge/         Cloudflare Worker - the entire v1 backend, a thin secret-holding relay (TypeScript)
iosApp/       Xcode project that hosts the composeApp iOS framework
```

## Status

**Live (web): https://reelscout.bitterinfantproductions.com**

Phases 0-4 done: all four Gradle targets build, the chat UI drives the real agent
tool-use loop in `shared` (TMDB/Watchmode/Archive.org, not stubs), and pushes to `main`
deploy both the `edge` Worker relay and the Wasm web app, each followed by a smoke test.
Desktop and web are tested end to end; Android and iOS build but haven't been run on a
device yet. See ROADMAP.md's phased plan for what's next (Phase 5 onward).

## Building

Requires JDK 17+. First run needs network access — the Gradle wrapper downloads Gradle
9.6, and Kotlin/Native downloads iOS toolchain components.

```bash
# Android (debug APK)
./gradlew :androidApp:assembleDebug

# Desktop
./gradlew :composeApp:run

# Web (Kotlin/Wasm) - serves at http://localhost:8080
./gradlew :composeApp:wasmJsBrowserDevelopmentRun

# iOS - open iosApp/iosApp.xcodeproj in Xcode and run.
# First set your own Team ID in iosApp/Configuration/Config.xcconfig (TEAM_ID=...),
# otherwise it will build for the simulator but won't code-sign for a device.

# Run shared-module tests (JVM)
./gradlew :shared:desktopTest
```

Note: Kotlin/Wasm's output directory path has moved between Kotlin releases in the past
(referenced in the GitHub Actions workflows as `composeApp/build/dist/wasmJs/
productionExecutable`) — if a build fails to find it, check the actual path Gradle
produces for the version pinned in `gradle/libs.versions.toml`.

## Backend / deployment

Deployed under `reelscout.bitterinfantproductions.com`: the Worker (`edge/`) owns the
`/api/*` route on that subdomain (see `edge/wrangler.toml`), and a Cloudflare Pages
project serves everything else — the Compose Multiplatform Wasm/Web build — with that
subdomain as its custom domain. Both live under the same `bitterinfantproductions.com`
Cloudflare zone, which has to be Active (nameservers pointed at Cloudflare) before either
can be wired up.

To stand up the relay yourself:

```bash
cd edge
npm install
CLOUDFLARE_API_TOKEN=... wrangler secret put ANTHROPIC_API_KEY
CLOUDFLARE_API_TOKEN=... wrangler secret put TMDB_API_KEY
CLOUDFLARE_API_TOKEN=... wrangler secret put WATCHMODE_API_KEY
CLOUDFLARE_API_TOKEN=... npm run deploy
```

`EdgeApiConfig.baseUrl` in `shared/src/commonMain/kotlin/com/reelscout/data/
EdgeApiClient.kt` already points at `https://reelscout.bitterinfantproductions.com` —
override it to `http://localhost:8787` for local `wrangler dev` testing.

Once confirmed working, the two GitHub Actions workflows (`deploy-worker.yml`,
`deploy-web.yml`) take over — they need `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` as repo secrets. See ROADMAP.md for the full CI/CD writeup.

## Data sources

TMDB (search + `watch/providers`, itself backed by JustWatch licensing data), Watchmode
(cross-check + explicit free/ad-supported flags), Archive.org (public-domain fallback).
All official, self-serve, ToS-compliant APIs — see ROADMAP.md > "Content & data sources"
for why that was a deliberate scope choice.

## License

MIT — see [LICENSE](./LICENSE).
