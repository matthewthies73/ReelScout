/**
 * ReelScout relay - the entire backend for v1 (see ROADMAP.md, "Backend on Cloudflare").
 *
 * Deliberately dumb: every route just attaches the real secret and forwards the request.
 * All the agent reasoning (the tool-use loop) lives in shared/src/commonMain/kotlin/
 * com/reelscout/agent and runs client-side - this Worker only exists so the Anthropic/
 * TMDB/Watchmode API keys never ship inside a mobile/desktop/web binary.
 *
 * "Dumb" still has to mean "safe to leave on the public internet": the relay pins the
 * Claude model and output cap, only forwards the upstream endpoints the app actually
 * uses, and rate-limits per client IP. Otherwise anyone who found this URL could spend
 * the Anthropic key on any model at any size, or burn the Watchmode quota.
 */

export interface Env {
  ANTHROPIC_API_KEY: string;
  TMDB_API_KEY: string;
  WATCHMODE_API_KEY: string;
  ANTHROPIC_LIMITER: RateLimit;
  DATA_LIMITER: RateLimit;
}

// Pinned here, not trusted from the client - see the header comment.
const ANTHROPIC_MODEL = "claude-sonnet-5";
// Thinking counts toward max_tokens, so this is a cost ceiling, not a target length.
const MAX_TOKENS_CAP = 8192;
const MAX_BODY_CHARS = 256 * 1024;
const MAX_MESSAGES = 40;
// Everything else in the client's body (model, thinking, output_config, ...) is dropped.
const FORWARDED_ANTHROPIC_FIELDS = ["system", "messages", "tools"] as const;

// Browsers only - native clients send no Origin header, so CORS is not authentication.
// The rate limiters and the caps above are what actually bound abuse.
const ALLOWED_ORIGINS = new Set([
  "https://reelscout.bitterinfantproductions.com",
  "http://localhost:8080", // ./gradlew :composeApp:wasmJsBrowserDevelopmentRun
]);

const TMDB_PATHS = [/^search\/multi$/, /^(movie|tv)\/\d+\/watch\/providers$/];
const WATCHMODE_PATHS = [/^search\/?$/, /^title\/[\w-]+\/sources\/?$/];
const ARCHIVE_PATHS = [/^advancedsearch\.php$/];

function corsHeaders(origin: string | null): Record<string, string> {
  const headers: Record<string, string> = {
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    Vary: "Origin",
  };
  if (origin && ALLOWED_ORIGINS.has(origin)) headers["Access-Control-Allow-Origin"] = origin;
  return headers;
}

function withCors(response: Response, origin: string | null): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(corsHeaders(origin))) headers.set(key, value);
  return new Response(response.body, { status: response.status, headers });
}

/** Same shape as an Anthropic API error, so the client can handle relay and upstream errors alike. */
function errorResponse(status: number, type: string, message: string, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify({ type: "error", error: { type, message } }), {
    status,
    headers: { "content-type": "application/json", ...extraHeaders },
  });
}

async function rateLimited(limiter: RateLimit, request: Request): Promise<Response | null> {
  const key = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const { success } = await limiter.limit({ key });
  return success
    ? null
    : errorResponse(429, "rate_limit_error", "Too many requests - try again in a minute.", { "Retry-After": "60" });
}

async function proxyAnthropic(request: Request, env: Env): Promise<Response> {
  const raw = await request.text();
  if (raw.length > MAX_BODY_CHARS) {
    return errorResponse(413, "request_too_large", "Conversation too long for this relay.");
  }

  let incoming: Record<string, unknown>;
  try {
    incoming = JSON.parse(raw);
  } catch {
    return errorResponse(400, "invalid_request_error", "Body must be JSON.");
  }
  if (!Array.isArray(incoming.messages) || incoming.messages.length === 0 || incoming.messages.length > MAX_MESSAGES) {
    return errorResponse(400, "invalid_request_error", `messages must have 1-${MAX_MESSAGES} entries.`);
  }

  const requestedMaxTokens = typeof incoming.max_tokens === "number" ? Math.floor(incoming.max_tokens) : MAX_TOKENS_CAP;
  const body: Record<string, unknown> = {
    model: ANTHROPIC_MODEL,
    max_tokens: Math.min(Math.max(requestedMaxTokens, 1), MAX_TOKENS_CAP),
  };
  for (const field of FORWARDED_ANTHROPIC_FIELDS) {
    if (incoming[field] !== undefined) body[field] = incoming[field];
  }

  return fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": env.ANTHROPIC_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify(body),
  });
}

/** Forwards a GET to an allowlisted upstream path, copying the query string and adding `extraParams`. */
async function proxyGet(
  request: Request,
  upstreamBase: string,
  path: string,
  allowedPaths: RegExp[],
  extraParams: Record<string, string> = {}
): Promise<Response> {
  if (request.method !== "GET" || !allowedPaths.some((pattern) => pattern.test(path))) {
    return errorResponse(404, "not_found_error", "Not found");
  }

  const incoming = new URL(request.url);
  const upstreamUrl = new URL(`${upstreamBase}/${path}`);
  // append, not set: Archive.org takes repeated keys (fl[]=identifier&fl[]=title).
  incoming.searchParams.forEach((value, key) => upstreamUrl.searchParams.append(key, value));
  // set, not append: replaces any client-supplied copy of the secret params.
  for (const [key, value] of Object.entries(extraParams)) upstreamUrl.searchParams.set(key, value);

  return fetch(upstreamUrl);
}

async function route(request: Request, env: Env, url: URL): Promise<Response> {
  // Cheap way to confirm a deploy actually landed: GET /api/health
  if (url.pathname === "/api/health") {
    return new Response(JSON.stringify({ status: "ok", service: "reelscout-relay" }), {
      headers: { "content-type": "application/json" },
    });
  }

  if (url.pathname === "/api/anthropic/messages" && request.method === "POST") {
    return (await rateLimited(env.ANTHROPIC_LIMITER, request)) ?? (await proxyAnthropic(request, env));
  }

  const dataRoutes: [string, (path: string) => Promise<Response>][] = [
    // TMDB v3 auth: the API Key as a query param (as opposed to the v4 Read Access
    // Token, which would go as a Bearer header instead).
    ["/api/tmdb/", (path) => proxyGet(request, "https://api.themoviedb.org/3", path, TMDB_PATHS, { api_key: env.TMDB_API_KEY })],
    ["/api/watchmode/", (path) => proxyGet(request, "https://api.watchmode.com/v1", path, WATCHMODE_PATHS, { apiKey: env.WATCHMODE_API_KEY })],
    // No secret needed - still proxied for a consistent client surface + rate limiting.
    ["/api/archive/", (path) => proxyGet(request, "https://archive.org", path, ARCHIVE_PATHS)],
  ];
  for (const [prefix, proxy] of dataRoutes) {
    if (url.pathname.startsWith(prefix)) {
      return (await rateLimited(env.DATA_LIMITER, request)) ?? (await proxy(url.pathname.slice(prefix.length)));
    }
  }

  return errorResponse(404, "not_found_error", "Not found");
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const origin = request.headers.get("Origin");

    if (origin && !ALLOWED_ORIGINS.has(origin)) {
      return errorResponse(403, "permission_error", "Origin not allowed");
    }
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders(origin) });
    }

    try {
      return withCors(await route(request, env, new URL(request.url)), origin);
    } catch (err) {
      return withCors(errorResponse(502, "api_error", `Relay error: ${(err as Error).message}`), origin);
    }
  },
};
