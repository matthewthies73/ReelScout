/**
 * Search analytics: every question answered through the relay is recorded in D1
 * (migrations/0001_create_searches.sql) together with the answer and the tools the agent
 * used, and GET /api/stats serves the running total plus the most-asked questions of the
 * past week.
 *
 * Recording happens here, in the relay, rather than via a client "log this" call, so a
 * client can't inflate the numbers. A search is recorded once, when Claude's final answer
 * comes back: that response is the first point where the question, the answer and the
 * tool calls leading up to it are all in hand.
 */

export const MAX_LOGGED_QUERY_CHARS = 500;
export const MAX_LOGGED_ANSWER_CHARS = 8000;

// The top-searches list shows other people's typed text to every visitor, so it only
// includes short, standalone questions asked more than once in the last week.
const TOP_SEARCHES_LIMIT = 5;
const TOP_SEARCHES_MIN_COUNT = 2;
const TOP_SEARCHES_MAX_CHARS = 60;
const TOP_SEARCHES_WINDOW = "-7 days";

export interface CompletedSearch {
  query: string;
  isFollowUp: boolean;
  answer: string;
  outcome: string;
  toolsUsed: string[];
  region: string | null;
}

export interface SearchStats {
  total_searches: number;
  top_searches: { query: string; count: number }[];
}

interface Block {
  type?: unknown;
  text?: unknown;
  name?: unknown;
  input?: unknown;
}

const REGION_TOOLS = new Set(["get_watch_providers", "get_watchmode_sources"]);

interface Message {
  role?: unknown;
  content?: unknown;
}

export function normalize(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, "")
    .replace(/\s+/g, " ")
    .trim();
}

function blocksOf(message: Message): Block[] {
  if (typeof message.content === "string") return [{ type: "text", text: message.content }];
  return Array.isArray(message.content) ? message.content : [];
}

function textOf(blocks: Block[]): string {
  return blocks
    .filter((block) => block?.type === "text" && typeof block.text === "string")
    .map((block) => block.text as string)
    .join("\n")
    .trim();
}

/**
 * The search a Messages API exchange completes, or null if Claude is still mid-loop
 * (stop_reason "tool_use") or there's no user question to attribute it to.
 */
export function completedSearch(requestMessages: unknown[], response: { stop_reason?: unknown; content?: unknown }): CompletedSearch | null {
  if (response.stop_reason === "tool_use") return null;

  // The question is the latest user turn with text in it. Later user turns only carry
  // tool results from the agent loop.
  const messages = requestMessages as Message[];
  let questionIndex = -1;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === "user" && textOf(blocksOf(messages[i])) !== "") {
      questionIndex = i;
      break;
    }
  }
  if (questionIndex < 0) return null;

  const toolsUsed: string[] = [];
  let region: string | null = null;
  for (const message of messages.slice(questionIndex + 1)) {
    if (message?.role !== "assistant") continue;
    for (const block of blocksOf(message)) {
      if (block?.type !== "tool_use" || typeof block.name !== "string") continue;
      if (!toolsUsed.includes(block.name)) toolsUsed.push(block.name);
      // The first region the agent looked up availability for.
      const input = block.input as { region?: unknown } | undefined;
      if (region === null && REGION_TOOLS.has(block.name) && typeof input?.region === "string") {
        region = input.region.toUpperCase().slice(0, 8);
      }
    }
  }

  const responseBlocks = Array.isArray(response.content) ? (response.content as Block[]) : [];
  return {
    query: textOf(blocksOf(messages[questionIndex])).slice(0, MAX_LOGGED_QUERY_CHARS),
    isFollowUp: questionIndex > 0,
    answer: textOf(responseBlocks).slice(0, MAX_LOGGED_ANSWER_CHARS),
    outcome: typeof response.stop_reason === "string" ? response.stop_reason : "unknown",
    toolsUsed,
    region,
  };
}

export async function recordSearch(db: D1Database, search: CompletedSearch): Promise<void> {
  await db
    .prepare(
      "INSERT INTO searches (query, normalized, is_follow_up, answer, outcome, tools_used, region) VALUES (?, ?, ?, ?, ?, ?, ?)"
    )
    .bind(search.query, normalize(search.query), search.isFollowUp ? 1 : 0, search.answer, search.outcome, search.toolsUsed.join(","), search.region)
    .run();
}

export async function readStats(db: D1Database): Promise<SearchStats> {
  const [total, top] = await db.batch<{ n?: number; query?: string; count?: number }>([
    db.prepare("SELECT COUNT(*) AS n FROM searches"),
    // Group by normalized text and label each group with its most common original
    // wording ("Night of the Living Dead", not a stray "night of the living dead?").
    db.prepare(
      `WITH recent AS (
         SELECT id, query, normalized FROM searches
          WHERE is_follow_up = 0
            AND created_at >= strftime('%Y-%m-%dT%H:%M:%fZ', 'now', ?)
            AND length(normalized) BETWEEN 1 AND ?
       ),
       grouped AS (
         SELECT normalized, COUNT(*) AS count, MAX(id) AS latest_id
           FROM recent GROUP BY normalized HAVING COUNT(*) >= ?
       ),
       wordings AS (
         SELECT normalized, query,
                ROW_NUMBER() OVER (PARTITION BY normalized ORDER BY COUNT(*) DESC, MAX(id) DESC) AS rank
           FROM recent GROUP BY normalized, query
       )
       SELECT w.query AS query, g.count AS count
         FROM grouped g JOIN wordings w ON w.normalized = g.normalized AND w.rank = 1
        ORDER BY g.count DESC, g.latest_id DESC
        LIMIT ?`
    ).bind(TOP_SEARCHES_WINDOW, TOP_SEARCHES_MAX_CHARS, TOP_SEARCHES_MIN_COUNT, TOP_SEARCHES_LIMIT),
  ]);

  return {
    total_searches: total.results[0]?.n ?? 0,
    top_searches: top.results.map((row) => ({ query: row.query as string, count: row.count as number })),
  };
}
