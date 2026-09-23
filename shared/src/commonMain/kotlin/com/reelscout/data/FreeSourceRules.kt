package com.reelscout.data

import com.reelscout.domain.WatchOption

/**
 * What counts as "free" (ROADMAP.md, Phase 5). TMDB and Watchmode both list library
 * services alongside ad-supported ones: TMDB puts Kanopy and Hoopla under "free", Watchmode
 * lists Hoopla as "free". They cost nothing, but only with a participating public library
 * card, so they're reported separately rather than as free for everyone.
 *
 * Paid ad-supported tiers ("Amazon Prime Video with Ads") need no rule: both sources
 * already list them as subscriptions. The truly free Amazon option is "Amazon Prime Video
 * Free with Ads" / "Prime Video Free".
 */
internal object FreeSourceRules {

    private val libraryCardServices = listOf("hoopla", "kanopy")

    fun needsLibraryCard(providerName: String): Boolean =
        libraryCardServices.any { providerName.lowercase().contains(it) }

    /** Splits free options into (free for anyone, free with a library card). */
    fun split(options: List<WatchOption>): Pair<List<WatchOption>, List<WatchOption>> =
        options.partition { !needsLibraryCard(it.providerName) }
}
