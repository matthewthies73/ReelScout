package com.reelscout.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Starter tests. The higher-value target - feeding AgentLoop a canned sequence of
 * AnthropicResponse fixtures and asserting the right tool fires in the right order -
 * needs AnthropicClient/ToolExecutor to be fakeable (e.g. via a mocked Ktor engine,
 * MockEngine from io.ktor:ktor-client-mock). See ROADMAP.md > Testing strategy, Phase 6.
 */
class ToolsTest {

    @Test
    fun `all four tools are registered with unique names`() {
        val names = Tools.all.map { it.name }
        assertEquals(4, names.toSet().size, "tool names must be unique")
        assertTrue("search_titles" in names)
        assertTrue("get_watch_providers" in names)
        assertTrue("get_watchmode_sources" in names)
        assertTrue("search_public_domain" in names)
    }

    @Test
    fun `system prompt names the free-only sources`() {
        val prompt = SystemPrompt.text
        assertTrue("Tubi" in prompt || "public domain" in prompt)
        assertTrue("search_titles" in prompt)
    }
}
