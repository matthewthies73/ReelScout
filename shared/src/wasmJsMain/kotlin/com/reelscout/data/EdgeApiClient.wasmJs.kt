package com.reelscout.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

actual fun platformHttpClient(): HttpClient = HttpClient(Js) { installEdgeDefaults() }
