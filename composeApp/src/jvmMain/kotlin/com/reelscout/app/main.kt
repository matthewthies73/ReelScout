package com.reelscout.app

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.reelscout.app.di.initKoin

fun main() {
    initKoin()
    application {
        Window(onCloseRequest = ::exitApplication, title = "ReelScout") {
            App()
        }
    }
}
