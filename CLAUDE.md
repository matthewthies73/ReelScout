# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

ReelScout is a Kotlin/Compose Multiplatform app (Android, iOS, Desktop JVM, Web via Kotlin/Wasm) where a Claude tool-use agent finds where to watch a title for free, legally. The backend is a thin Cloudflare Worker relay in `edge/`. README.md covers design decisions; ROADMAP.md covers architecture rationale and locked-in decisions.

## Commands

Requires JDK 17+. Apps point at the live relay by default, so no API keys are needed to run them.

```bash
./gradlew :composeApp:run                              # Desktop
./gradlew :androidApp:assembleDebug                    # Android APK
./gradlew :composeApp:wasmJsBrowserDevelopmentRun      # Web at http://localhost:8080
./gradlew :composeApp:wasmJsBrowserDistribution        # Production web bundle (what CI deploys)
open iosApp/iosApp.xcodeproj                           # iOS (simulator)

./gradlew :shared:desktopTest                          # All Kotlin tests (commonTest, run on the JVM "desktop" target)
./gradlew :shared:desktopTest --tests "com.reelscout.agent.AgentLoopTest"   # One test class
./gradlew :shared:desktopTest --tests "com.reelscout.agent.AgentLoopTest.<method>"

cd edge && npm install
npm run typecheck                                      # tsc --noEmit
npm test                                               # node:test over test/**/*.test.ts (Node 22+)
node --test test/analytics.test.ts                     # One test file
npm run dev                                            # wrangler dev on :8787; needs edge/.dev.vars (see .dev.vars.example)
```

CI (`.github/workflows/ci.yml`) runs `:shared:desktopTest`, `:androidApp:assembleDebug`, `:composeApp:wasmJsBrowserDistribution`, and the edge typecheck + tests. `ios.yml` builds the simulator app on macOS. Pushes to `main` deploy the Worker (`deploy-worker.yml`, applies D1 migrations) and the web bundle to Cloudflare Pages (`deploy-web.yml`), each followed by smoke tests against the live site.

## Architecture

**Modules:** `shared/` (agent, data layer, domain models — no UI), `composeApp/` (Compose UI + Koin wiring + per-platform entry points), `androidApp/` (Android application module, separate because of AGP 9), `iosApp/` (Xcode host for the composeApp framework), `edge/` (Worker relay + D1 analytics). The JVM target is named `desktop`, hence `desktopMain`/`desktopTest`.

**The agent runs in the client, not the server.** `shared/.../agent/AgentLoop.kt` is a hand-written Messages API tool-use loop (no Anthropic SDK — the Java SDK can't target iOS/Wasm). Each turn sends system prompt + history + `Tools.all` via `AnthropicClient`; `tool_use` blocks are dispatched by `ToolExecutor` to `TmdbRepository` / `WatchmodeRepository` / `ArchiveOrgRepository`; results go back as `tool_result` until Claude stops calling tools or `maxTurns` (6) is hit. Assistant content (including thinking blocks) is echoed back unchanged. Prior exchanges are replayed as plain question/answer text only (tool calls dropped), capped at `MAX_HISTORY_EXCHANGES`.

**Tool result trimming is deliberate.** `ToolExecutor` and the repositories cut upstream responses down (e.g. TMDB watch providers to the user's region only, top-N search results, truncated overviews) to keep token usage low. Free vs. library-card (Hoopla, Kanopy) vs. paid classification is done deterministically in `data/FreeSourceRules.kt`, not left to the prompt.

**Region** (`domain/Region`, 15 countries) comes from `RegionStore`, is injected into `SystemPrompt.text(region)` and used as the tools' default region; a region Claude passes explicitly in tool input wins.

**All network traffic goes to the relay.** `EdgeApiConfig.baseUrl` in `shared/.../data/EdgeApiClient.kt` is the only host clients talk to (override to `http://localhost:8787` for `wrangler dev`). `platformHttpClient()` is an `expect` with per-platform engine actuals. JSON uses `commonJson` with a snake_case naming strategy, so `@Serializable` classes stay camelCase without `@SerialName`.

**Relay invariants (`edge/src/index.ts`) that the client must stay in sync with:**
- The relay pins the model (`ANTHROPIC_MODEL`) and caps `max_tokens`; it forwards only `system`, `messages`, `tools` from the client body. `AgentLoop`'s `model`/`maxTokens` defaults should match.
- `MAX_MESSAGES` (40) bounds history: `MAX_HISTORY_EXCHANGES` + current question + tool rounds must fit under it.
- Upstream paths are allowlisted (`TMDB_PATHS`, `WATCHMODE_PATHS`, `ARCHIVE_PATHS`). A new repository endpoint needs a matching allowlist entry, or the relay rejects it.
- CORS `ALLOWED_ORIGINS` includes the live site and `localhost:8080`. Per-IP rate limits are configured in `wrangler.toml`.
- Errors are returned in Anthropic's error shape so the client handles relay and upstream errors uniformly (`AgentLoop.describeFailure`).

**Analytics** are recorded by the relay (`edge/src/analytics.ts`), not the clients: it extracts question/answer/tools/region from completed Messages exchanges into D1 (`edge/migrations/`) and serves `/api/stats`, read by `StatsRepository`. New schema changes go in a new numbered migration file.

**UI/DI:** `composeApp/.../di/AppModule.kt` wires one shared `HttpClient` into all repositories, `AnthropicClient`, `ToolExecutor`, `AgentLoop`, and `ChatViewModel`. Each platform entry point calls `initKoin()`. Favorites and region are persisted with multiplatform-settings (`FavoritesStore`, `RegionStore`), not a database.

**Web cache-busting:** `composeApp/build.gradle.kts` post-processes the Wasm distribution to rewrite `src="reelscout.js"` in `index.html` to `reelscout.js?v=<content hash>`. The build fails if `index.html` stops loading `reelscout.js` by that exact string; `deploy-web.yml` smoke-tests that the live site serves the new hash.

## Testing

Tests live in `shared/src/commonTest`. They use Ktor `MockEngine` as a fake relay: `AgentLoopTest` replays canned Claude response sequences (tool round-trips, thinking blocks, history cap, retry on overload but not on 429, refusals, `max_tokens` truncation, region handling); `ToolExecutorTest` runs each tool against fixture responses.

## Branding

Platform icons and the Play Store feature graphic are generated from `branding/icon-master-1024.png` by `branding/generate_icons.py`; regenerate rather than editing icon files by hand.
