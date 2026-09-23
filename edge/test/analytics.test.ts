import { test } from "node:test";
import assert from "node:assert/strict";
import { completedSearch, normalize } from "../src/analytics.ts";

const question = { role: "user", content: [{ type: "text", text: "Night of the Living Dead?" }] };
const toolTurn = (id: string, ...names: string[]) => ({
  role: "assistant",
  content: [
    { type: "thinking", thinking: "", signature: "sig" },
    ...names.map((name, i) => ({ type: "tool_use", id: `${id}${i}`, name, input: {} })),
  ],
});
const toolResults = (id: string, count: number) => ({
  role: "user",
  content: Array.from({ length: count }, (_, i) => ({ type: "tool_result", tool_use_id: `${id}${i}`, content: "{}" })),
});
const answer = { stop_reason: "end_turn", content: [{ type: "text", text: "Free on [Tubi](https://tubitv.com/x)." }] };

test("normalize groups questions that differ only in case, punctuation and spacing", () => {
  assert.equal(normalize("  Night of the LIVING dead?! "), "night of the living dead");
  assert.equal(normalize("Amélie"), "amélie");
});

test("mid-loop responses are not a completed search", () => {
  assert.equal(completedSearch([question], { stop_reason: "tool_use", content: [] }), null);
});

test("a finished search records the question, answer, outcome and tools in order", () => {
  const messages = [
    question,
    toolTurn("a", "search_titles"),
    toolResults("a", 1),
    toolTurn("b", "get_watch_providers", "get_watchmode_sources"),
    toolResults("b", 2),
    toolTurn("c", "search_titles"), // repeated tool is listed once
    toolResults("c", 1),
  ];

  assert.deepEqual(completedSearch(messages, answer), {
    query: "Night of the Living Dead?",
    isFollowUp: false,
    answer: "Free on [Tubi](https://tubitv.com/x).",
    outcome: "end_turn",
    toolsUsed: ["search_titles", "get_watch_providers", "get_watchmode_sources"],
  });
});

test("a question after earlier exchanges is a follow-up, and only its own tools count", () => {
  const messages = [
    question,
    { role: "assistant", content: [{ type: "text", text: "Free on Tubi." }] },
    { role: "user", content: [{ type: "text", text: "What about the 1990 remake?" }] },
    toolTurn("a", "search_titles"),
    toolResults("a", 1),
  ];

  const search = completedSearch(messages, answer);
  assert.equal(search?.query, "What about the 1990 remake?");
  assert.equal(search?.isFollowUp, true);
  assert.deepEqual(search?.toolsUsed, ["search_titles"]);
});

test("string content and refusals are handled", () => {
  const search = completedSearch([{ role: "user", content: "hi" }], { stop_reason: "refusal", content: [] });
  assert.equal(search?.query, "hi");
  assert.equal(search?.outcome, "refusal");
  assert.equal(search?.answer, "");
});

test("no user text means nothing to record", () => {
  assert.equal(completedSearch([toolResults("a", 1)], answer), null);
});
