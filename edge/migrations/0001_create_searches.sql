-- One row per answered question (see src/analytics.ts). Deliberately no IP address,
-- device or account: just what was asked, what came back, and when.
CREATE TABLE searches (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    query        TEXT    NOT NULL,
    -- Lowercased, punctuation stripped, whitespace collapsed: groups "Nosferatu?" with "nosferatu".
    normalized   TEXT    NOT NULL,
    -- 1 when earlier questions were sent along ("what about the remake?").
    is_follow_up INTEGER NOT NULL DEFAULT 0,
    -- Claude's final answer as the user saw it (Markdown), and how the turn ended:
    -- end_turn (normal), max_tokens (cut short) or refusal.
    answer       TEXT    NOT NULL,
    outcome      TEXT    NOT NULL,
    -- Comma-separated tools the agent ran for this question, in first-use order.
    tools_used   TEXT    NOT NULL DEFAULT ''
);

CREATE INDEX idx_searches_created_at ON searches (created_at);
CREATE INDEX idx_searches_normalized ON searches (normalized);
