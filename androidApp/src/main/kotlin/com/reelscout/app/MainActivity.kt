package com.reelscout.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars; App's Scaffold and input bar apply the insets,
        // including the keyboard's, so the input stays above it.
        enableEdgeToEdge()
        setContent { App() }
    }
}
