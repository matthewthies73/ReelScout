import { test } from "node:test";
import assert from "node:assert/strict";
import { PERSONAL_LINKS } from "../src/personalLinks.ts";

// Entries are added by hand, so check them before a typo reaches the live relay.
test("every personal link names a real-looking TMDB person and how they met", () => {
  for (const link of PERSONAL_LINKS.links) {
    assert.ok(Number.isInteger(link.tmdb_id) && link.tmdb_id > 0, `${link.name}: tmdb_id must be a TMDB person id`);
    assert.ok(link.name.trim(), `${link.tmdb_id}: name is empty`);
    assert.ok(link.how.trim(), `${link.name}: how is empty`);
  }
});

test("each person is listed once, and not as their own link", () => {
  const ids = PERSONAL_LINKS.links.map((link) => link.tmdb_id);
  assert.equal(new Set(ids).size, ids.length, "a person is listed twice");
  assert.ok(!ids.includes(PERSONAL_LINKS.person.tmdb_id), `${PERSONAL_LINKS.person.name} is listed as their own link`);
});
