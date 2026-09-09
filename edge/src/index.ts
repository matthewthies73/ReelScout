/**
 * ReelScout relay - the entire backend for v1 (see ROADMAP.md, "Backend on Cloudflare").
 *
 * Deliberately dumb: every route just attaches the real secret and forwards the request.
 * All the agent reasoning (the tool-use loop) lives in shared/src/commonMain/kotlin/
 * com/reelscout/agent and runs client-side - this Worker only exists so the Anthropic/
 * TMDB/Watchmode API keys never ship inside a mobile/desktop/web binary.
 */

export interface Env {
  ANTHROPIC_API_KEY: string;
  TMDB_API_KEY: string;
  WATCHMODE_API_KEY: string;
}

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*", // TODO: lock this down to your deployed web origin
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function withCors(response: Response): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(CORS_HEADERS)) headers.set(key, value);
  return new Response(response.body, { status: response.status, headers });
}

async function proxyAnthropic(request: Request, env: Env): Promise<Response> {
  const body = await request.text();
  const upstream = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body,
  });
  return withCors(upstream);
}

async function proxyTmdb(request: Request, env: Env, tmdbPath: string): Promise<Response> {
  const incoming = new URL(request.url);
  const upstreamUrl = new URL(`https://api.themoviedb.org/3/${tmdbPath}`);
  incoming.searchParams.forEach((value, key) => upstreamUrl.searchParams.set(key, value));
  // TMDB v3 auth: the API Key as a query param (as opposed to the v4 Read Access
  // Token, which would go as a Bearer header instead).
  upstreamUrl.searchParams.set("api_key", env.TMDB_API_KEY);

  const upstream = await fetch(upstreamUrl);
  return withCors(upstream);
}

async function proxyWatchmode(request: Request, env: Env, watchmodePath: string): Promise<Response> {
  const incoming = new URL(request.url);
  const upstreamUrl = new URL(`https://api.watchmode.com/v1/${watchmodePath}`);
  incoming.searchParams.forEach((value, key) => upstreamUrl.searchParams.set(key, value));
  upstreamUrl.searchParams.set("apiKey", env.WATCHMODE_API_KEY);

  const upstream = await fetch(upstreamUrl);
  return withCors(upstream);
}

async function proxyArchiveOrg(request: Request, archivePath: string): Promise<Response> {
  const incoming = new URL(request.url);
  const upstreamUrl = new URL(`https://archive.org/${archivePath}`);
  incoming.searchParams.forEach((value, key) => upstreamUrl.searchParams.set(key, value));

  // No secret needed - still proxied for a consistent client surface + future rate limiting.
  const upstream = await fetch(upstreamUrl);
  return withCors(upstream);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);

    try {
      // Cheap way to confirm a deploy actually landed: GET /api/health
      if (url.pathname === "/api/health") {
        return withCors(
          new Response(JSON.stringify({ status: "ok", service: "reelscout-relay" }), {
            headers: { "content-type": "application/json" },
          })
        );
      }

      if (url.pathname === "/api/anthropic/messages" && request.method === "POST") {
        return await proxyAnthropic(request, env);
      }

      if (url.pathname.startsWith("/api/tmdb/")) {
        return await proxyTmdb(request, env, url.pathname.replace("/api/tmdb/", ""));
      }

      if (url.pathname.startsWith("/api/watchmode/")) {
        return await proxyWatchmode(request, env, url.pathname.replace("/api/watchmode/", ""));
      }

      if (url.pathname.startsWith("/api/archive/")) {
        return await proxyArchiveOrg(request, url.pathname.replace("/api/archive/", ""));
      }

      return new Response("Not found", { status: 404 });
    } catch (err) {
      return new Response(`Relay error: ${(err as Error).message}`, { status: 502 });
    }
  },
};
