package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun platformHttpClient(): HttpClient = HttpClient(CIO) { installEdgeDefaults() }
