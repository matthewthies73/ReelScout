package com.reelscout.app

import android.app.Application
import com.reelscout.app.di.initKoin

class ReelScoutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}
