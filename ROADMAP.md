# ReelScout — Project Roadmap

## What this project demonstrates

Two things, deliberately paired:

1. **Kotlin Multiplatform / Compose Multiplatform breadth** — one shared codebase targeting Android, iOS, Desktop (JVM), and Web (Kotlin/Wasm).
2. **Real agentic AI integration** — Claude reasoning over a user query via a genuine multi-step tool-use loop (search → check availability → answer), grounded in live data, not a single prompt-and-response wrapper.

Both should be visible at a glance in the README: architecture diagram, a demo GIF per platform, and a "design decisions" section explaining the non-obvious calls (see Phase 7).

## Product concept

User asks something like *"sci-fi movies like Interstellar that I can watch for free right now"*. The agent:

- resolves candidate titles via TMDB search,
- checks where each is streaming via TMDB `watch/providers` (which is itself backed by JustWatch licensing data) and Watchmode as a cross-check, filtered to ad-supported/free flags,
- falls back to Archive.org's public-domain film collection for older titles not covered by modern catalogs,
- returns a grounded, cited answer — never answering from the model's own memory of "what's on Netflix," since that goes stale immediately.

Region defaults to **US** (both for TMDB/Watchmode provider lookups and for which ad-supported services are even considered) unless the user changes it in-app. US is the simplest region to source complete data for, so it's the right default for the initial build — a region picker is Phase 5 polish, not a blocker.

## Content & data sources (legal-only, by design)

| Source | Use | Notes |
|---|---|---|
| TMDB API | title search, metadata, `watch/providers` | free API key, well documented, effectively includes JustWatch's provider data |
| Watchmode API | cross-check + explicit free/ad-supported flags | free tier available |
| Archive.org advancedsearch API | true public-domain titles | no auth needed |

No scraping, no torrent/indexer integration — everything above is an official, ToS-compliant API. Worth a line in the README explaining this was a deliberate scope choice, not an oversight.

## Architecture

### Module layout

```
/androidApp        — Android application module (MainActivity, Application class) - AGP 9 keeps it separate from the KMP modules
/composeApp        — Compose Multiplatform UI (androidMain, iosMain, desktopMain, wasmJsMain, commonMain)
/shared             — domain models, repositories, Ktor HTTP client, the agent tool-use loop (commonMain, used by composeApp)
/edge               — Cloudflare Worker(s): thin secret-holding relay (TypeScript)
```

### Client side (KMP/CMP)

All four targets share `shared`: domain models, the TMDB/Watchmode/Archive.org repository layer (Ktor multiplatform client), and — importantly — **the agent tool-use loop itself lives here**, not on the server. Each client builds the message history, sends it to Claude, inspects `tool_use` blocks, executes the corresponding tool call, and feeds `tool_result` back in, repeating until Claude returns a final answer. This is the part that actually demonstrates "integrating an AI agent," and keeping it in shared Kotlin means it's demonstrated once, on every platform, instead of reimplemented per-target.

### Why not the Anthropic Java SDK

It's JVM-only — fine for Android/Desktop, dead on iOS and Wasm/Web. Calling the Messages API directly over Ktor in `commonMain` is what makes this a true multiplatform artifact, and it's a better talking point anyway ("I implemented the tool-use protocol myself" beats "I imported a library").

### Backend on Cloudflare — thin relay, not a rewritten server

The client never talks to Anthropic/TMDB/Watchmode directly — those API keys can't live in a mobile/desktop/web binary, they're all trivially extractable. Instead, a small **Cloudflare Worker** (TypeScript, a few dozen lines) sits in front and does exactly one job per route: attach the real secret header and forward the request.

- `POST /api/anthropic/messages` → forwards to `api.anthropic.com/v1/messages`, injecting `ANTHROPIC_API_KEY` from Worker secrets
- `GET /api/tmdb/*` → forwards to TMDB, injecting the TMDB key
- `GET /api/watchmode/*` → forwards to Watchmode, injecting the Watchmode key
- `GET /api/archive/*` → forwards to Archive.org (no key needed, but still proxied for a consistent client surface + rate limiting)

Deliberately dumb on purpose: no business logic, no agent reasoning on the server. That keeps the Worker tiny, stable, and easy to deploy from day one, while the actual "agent" stays where the interesting Kotlin code is.

**Confirmed as v2, not part of the initial build:** Cloudflare **Containers** (GA'd April 2026) could eventually run a full Kotlin/Ktor JVM server in a Docker container behind a Worker, for anyone who wants the backend logic in Kotlin too. That's real extra ops surface (Docker build, container registry, cold starts) that isn't needed to hit the project's goals, so it's explicitly out of scope for v1 — the thin-relay Worker above is the whole backend for launch.

### Web pages

The `wasmJsMain` target of `composeApp` builds to a static bundle (HTML/JS/Wasm). That gets deployed to **Cloudflare Pages** as static assets — no server-side rendering needed, it's a client-rendered Compose app hitting the Worker API.

## CI/CD: GitHub → Cloudflare

The reliable pattern (and the one Cloudflare's own docs recommend for non-trivial build toolchains) is: **build in GitHub Actions, where you control the JDK/Gradle/Kotlin toolchain, then push the already-built output to Cloudflare** — rather than asking Cloudflare's own git-integration build servers to run a Gradle/Kotlin-Wasm build they weren't really designed for.

**Secrets, two places:**

- **GitHub Actions secrets** (repo settings → Secrets and variables → Actions): `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` — these authenticate the deploy step, nothing else.
- **Cloudflare Worker secrets** (set once via `wrangler secret put ANTHROPIC_API_KEY`, etc., or the dashboard): `ANTHROPIC_API_KEY`, `TMDB_API_KEY`, `WATCHMODE_API_KEY`. These never touch GitHub at all.

**Two GitHub Actions workflows:**

```yaml
# .github/workflows/deploy-worker.yml
name: Deploy Worker
on:
  push:
    branches: [main]
    paths: ['edge/**']
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: cloudflare/wrangler-action@v3
        with:
          apiToken: ${{ secrets.CLOUDFLARE_API_TOKEN }}
          accountId: ${{ secrets.CLOUDFLARE_ACCOUNT_ID }}
          workingDirectory: edge
          command: deploy
```

```yaml
# .github/workflows/deploy-web.yml
name: Deploy Web
on:
  push:
    branches: [main]
    paths: ['composeApp/**', 'shared/**']
jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - run: ./gradlew :composeApp:wasmJsBrowserDistribution
      - uses: cloudflare/wrangler-action@v3
        with:
          apiToken: ${{ secrets.CLOUDFLARE_API_TOKEN }}
          accountId: ${{ secrets.CLOUDFLARE_ACCOUNT_ID }}
          command: pages deploy composeApp/build/dist/wasmJs/productionExecutable --project-name=reelscout-web
```

(Paths/task names are approximate — Kotlin/Wasm output directories have moved before between Kotlin releases, confirm the exact path once the project scaffolds.) Both workflows are independent, so a Worker-only change doesn't rebuild the Wasm bundle and vice versa. Add `pull_request` triggers later for preview deploys once the happy path works.

## Repo structure (target state)

```
reelscout/
├── androidApp/
├── composeApp/
│   ├── src/androidMain/
│   ├── src/iosMain/
│   ├── src/desktopMain/
│   ├── src/wasmJsMain/
│   └── src/commonMain/
├── shared/
│   └── src/commonMain/kotlin/
│       ├── domain/         # models
│       ├── data/           # TMDB/Watchmode/Archive repositories, Ktor client
│       └── agent/          # tool-use loop, tool definitions, Anthropic client
├── edge/
│   ├── src/index.ts        # Worker routes
│   └── wrangler.toml
├── .github/workflows/
├── ROADMAP.md
└── README.md
```

## Phased roadmap

- **Phase 0 — Scaffold.** KMP wizard project, four targets building "hello world," GitHub repo up, CI running builds (no deploy yet).
- **Phase 1 — Data layer, no AI.** `shared` repository layer for TMDB/Watchmode, plain search UI in Compose. Already a legitimate multiplatform demo on its own — good first milestone/screenshot.
- **Phase 2 — Cloudflare relay.** Stand up the `edge` Worker with the four proxy routes, set Worker secrets, deploy manually once via `wrangler deploy` to confirm it works end to end before wiring CI.
- **Phase 3 — CI/CD.** Add the two GitHub Actions workflows above; confirm a push to `main` actually redeploys the Worker and the web bundle.
- **Phase 4 — Agent loop.** Implement the tool-use loop in `shared/agent`, wire a chat UI (message list, tool-call indicators like "searching TMDB…" — good for demo video), test against all four proxy routes.
- **Phase 5 — Content polish.** Region selection, free-source filtering rules, SQLDelight-backed watchlist/favorites.
- **Phase 6 — Platform packaging.** Android release build, iOS via Xcode/TestFlight or simulator recording, Desktop packaging (jpackage/Conveyor), confirm Cloudflare Pages URL is stable and linkable.
- **Phase 7 — Portfolio polish.** README with architecture diagram, per-platform demo GIFs, a "design decisions" section (Ktor-over-SDK, client-side agent loop + dumb relay instead of server-side agent, legal-only data sources), license.

## Testing strategy

- `commonTest` with a mocked Ktor engine for TMDB/Watchmode/Archive.org fixtures.
- Dedicated tests around the agent loop: feed canned Claude tool-call response sequences, assert the right tool executes and the final answer formats correctly. This is the part most likely to get scrutinized — worth the most test coverage.
- A smoke test for the Worker routes (can be as simple as a `curl`/fetch check in CI post-deploy).

## Decisions locked in

- **Project name:** ReelScout — used for the repo, `wrangler.toml` names, and the Cloudflare Pages project (`reelscout-web`).
- **Backend for v1:** thin-relay Cloudflare Worker only. Containers (full Kotlin/Ktor backend) is a confirmed v2 item, not part of the initial build.
- **Default region:** US, for both provider lookups and which free/ad-supported services are considered. A region picker is Phase 5 polish.
- **Reelgood data source:** left out. Sticking with TMDB + Watchmode + Archive.org as the fully self-serve, reproducible data layer.
- **Search analytics:** the relay logs each answered question (question, answer, tools
  used, outcome; no IP or user id) to Cloudflare D1 and serves a public total + weekly top
  five at `/api/stats`. Recording lives in the relay, not the clients, so the numbers can't
  be inflated with a fake "log this" call. Top-five is raw user text shown publicly, so
  it's limited to repeated, short, standalone questions; if it's ever abused, switch it to
  the TMDB titles Claude actually looked up.
- **Live site:** `reelscout.bitterinfantproductions.com` is the `reelscout-web` Cloudflare
  Pages project's custom domain (the Wasm app); the Worker's `/api/*` route on the same
  hostname takes precedence, so the app and relay are same-origin. All three secrets
  (Anthropic, TMDB, Watchmode) are set. Both deploy workflows end with a smoke test
  against the live hostname.
