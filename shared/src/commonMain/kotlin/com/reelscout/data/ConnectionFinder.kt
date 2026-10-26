package com.reelscout.data

import com.reelscout.domain.Connection
import com.reelscout.domain.ConnectionLink
import com.reelscout.domain.MediaType
import com.reelscout.domain.Title
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Six Degrees of Kevin Bacon: finds a chain of films linking two people.
 *
 * People and films form a graph (a person links to the films they worked on, in the cast or
 * the crew, a film to its cast and crew). This searches it from both ends at once, a layer
 * at a time, and stops when the two searches reach the same person or film. Each node
 * expanded costs one TMDB call through the relay, so it's bounded two ways:
 * - [maxCalls] caps the whole search, keeping one question under the relay's per-IP limit.
 * - Each layer only expands its [beamWidth] most popular nodes. Popular films and people
 *   are the hubs that connect everyone, so this finds most links quickly, but the chain it
 *   returns is a short one rather than guaranteed shortest, and a link that only runs
 *   through obscure films can be missed.
 *
 * Whichever end has the smaller frontier is expanded next. That end is the cheaper one to
 * grow, so someone with a credit or two (a crew member on one indie film, say) gets searched
 * a few layers out for a handful of calls, leaving most of the budget for the other end.
 *
 * Everything a fetched credit list mentions is still recorded, so the searches meet as soon
 * as either one has seen a node, whether or not it was expanded.
 *
 * Films only, as in the original game: TV casts and crews are large and would dominate the graph.
 */
class ConnectionFinder(
    private val tmdb: TmdbRepository,
    private val maxCalls: Int = 40,
    private val beamWidth: Int = 10
) {
    private sealed interface Node { val id: Int }
    private data class PersonNode(override val id: Int) : Node
    private data class MovieNode(override val id: Int) : Node

    /** One side of the search: how each node it has seen was reached. */
    private class Side(start: Node) {
        val parent = mutableMapOf<Node, Node?>(start to null)
        val depth = mutableMapOf(start to 0)
        var frontier = listOf(start)
    }

    /** Null when no chain turned up within the search limits. */
    suspend fun find(fromPersonId: Int, fromName: String, toPersonId: Int, toName: String): Connection? =
        Search().run(fromPersonId, fromName, toPersonId, toName)

    /** One search's state, so a finder can be reused. */
    private inner class Search {
        private val names = mutableMapOf<Node, String>()
        private val years = mutableMapOf<Node, Int?>()
        private val popularity = mutableMapOf<Node, Double>()
        // What each person did on each film, keyed by (person id, movie id): who they played,
        // and their crew jobs.
        private val characters = mutableMapOf<Pair<Int, Int>, String>()
        private val jobs = mutableMapOf<Pair<Int, Int>, MutableSet<String>>()

        suspend fun run(fromPersonId: Int, fromName: String, toPersonId: Int, toName: String): Connection? {
            if (fromPersonId == toPersonId) return Connection(degrees = 0, links = emptyList())
            val a = Side(PersonNode(fromPersonId))
            val b = Side(PersonNode(toPersonId))
            names[PersonNode(fromPersonId)] = fromName
            names[PersonNode(toPersonId)] = toName

            var calls = 0
            while (calls < maxCalls && a.frontier.isNotEmpty() && b.frontier.isNotEmpty()) {
                val (side, other) = if (a.frontier.size <= b.frontier.size) a to b else b to a
                val batch = side.frontier.sortedByDescending { popularity[it] ?: Double.MAX_VALUE }
                    .take(minOf(beamWidth, maxCalls - calls))
                calls += batch.size

                // Fetched in parallel, recorded afterwards, so only this loop touches the maps.
                val fetched = coroutineScope { batch.map { node -> async { node to fetch(node) } }.awaitAll() }

                val next = mutableListOf<Node>()
                val meetings = mutableListOf<Node>()
                for ((node, credits) in fetched) {
                    for (neighbor in record(node, credits)) {
                        if (neighbor in side.parent) continue
                        side.parent[neighbor] = node
                        side.depth[neighbor] = side.depth.getValue(node) + 1
                        next += neighbor
                        if (neighbor in other.parent) meetings += neighbor
                    }
                }

                meetings.minByOrNull { a.depth.getValue(it) + b.depth.getValue(it) }?.let { return connectionThrough(it, a, b) }
                side.frontier = next
            }
            return null
        }

        private suspend fun fetch(node: Node): List<Any> = when (node) {
            is PersonNode -> tmdb.personCredits(node.id)
                // Documentaries link people who only appear as themselves; not a role.
                .filter { it.mediaType == "movie" && DOCUMENTARY !in it.genreIds }
            is MovieNode -> tmdb.movieCredits(node.id)
        }

        /**
         * Notes names, years, popularity, characters and jobs from a node's credits; returns its
         * neighbors (once per credit, so a person with several jobs on a film appears more than once).
         */
        private fun record(node: Node, credits: List<Any>): List<Node> = credits.map { credit ->
            when (credit) {
                is TmdbPersonCredit -> MovieNode(credit.id).also { movie ->
                    names[movie] = credit.title ?: credit.name ?: "Unknown title"
                    years[movie] = credit.year
                    popularity[movie] = credit.popularity
                    recordRole(node.id, credit.id, credit.character, credit.job)
                }
                is TmdbMovieCredit -> PersonNode(credit.id).also { person ->
                    names[person] = credit.name
                    popularity[person] = credit.popularity
                    recordRole(credit.id, node.id, credit.character, credit.job)
                }
                else -> error("unexpected credit $credit")
            }
        }

        // Both ends of a credit can be fetched, so the same role can be seen twice.
        private fun recordRole(personId: Int, movieId: Int, character: String?, job: String?) {
            val key = personId to movieId
            character?.takeIf { it.isNotBlank() }?.let { characters.getOrPut(key) { it } }
            job?.let { jobs.getOrPut(key) { mutableSetOf() } += it }
        }

        /** Joins the path from [a]'s start to [meeting] with the path from [meeting] to [b]'s start. */
        private fun connectionThrough(meeting: Node, a: Side, b: Side): Connection {
            val path = generateSequence(meeting) { a.parent[it] }.toList().reversed() +
                generateSequence(b.parent[meeting]) { b.parent[it] }.toList()

            // The path alternates person, movie, person, ... starting and ending on a person.
            val links = path.windowed(size = 3, step = 2).map { (from, movie, to) ->
                ConnectionLink(
                    from = names.getValue(from),
                    fromCharacter = characters[from.id to movie.id],
                    movie = Title(
                        tmdbId = movie.id,
                        name = names.getValue(movie),
                        mediaType = MediaType.MOVIE,
                        overview = "",
                        releaseYear = years[movie],
                        posterPath = null
                    ),
                    to = names.getValue(to),
                    toCharacter = characters[to.id to movie.id],
                    fromJobs = jobs[from.id to movie.id].orEmpty().toList(),
                    toJobs = jobs[to.id to movie.id].orEmpty().toList()
                )
            }
            return Connection(degrees = links.size, links = links)
        }
    }

    private companion object {
        const val DOCUMENTARY = 99
    }
}
