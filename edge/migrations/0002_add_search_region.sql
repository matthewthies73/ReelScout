-- Country the search was answered for: the region the agent passed to its availability
-- tools (get_watch_providers / get_watchmode_sources). NULL when it never looked one up,
-- e.g. a clarifying question, and for rows recorded before the region picker.
ALTER TABLE searches ADD COLUMN region TEXT;
