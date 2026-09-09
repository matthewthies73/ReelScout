package com.reelscout.app

import androidx.compose.ui.window.ComposeUIViewController
import com.reelscout.app.di.initKoin
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    initKoin()
    return ComposeUIViewController { App() }
}
