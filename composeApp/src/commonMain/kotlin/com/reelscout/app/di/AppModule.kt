package com.reelscout.app.di

import com.reelscout.agent.AgentLoop
import com.reelscout.agent.AnthropicClient
import com.reelscout.agent.ToolExecutor
import com.reelscout.app.ChatViewModel
import com.reelscout.data.ArchiveOrgRepository
import com.reelscout.data.TmdbRepository
import com.reelscout.data.WatchmodeRepository
import com.reelscout.data.platformHttpClient
import org.koin.core.context.startKoin
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/**
 * One shared HttpClient feeds every repository and the Anthropic client, all wired
 * through to a single AgentLoop instance and, above that, the ChatViewModel the UI reads.
 */
val appModule = module {
    single { platformHttpClient() }
    single { TmdbRepository(get()) }
    single { WatchmodeRepository(get()) }
    single { ArchiveOrgRepository(get()) }
    single { AnthropicClient(get()) }
    single { ToolExecutor(get(), get(), get()) }
    single { AgentLoop(get(), get()) }
    single { ChatViewModel(get()) }
}

/** Called once per process from each platform's entry point - see the *Main source sets. */
fun initKoin(config: KoinAppDeclaration? = null) {
    startKoin {
        config?.invoke(this)
        modules(appModule)
    }
}
