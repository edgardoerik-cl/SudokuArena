package com.sudokuarena

import android.app.Application
import com.sudokuarena.audio.GlobalAudioManager

class MultiArenaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalAudioManager.initialize(this)
    }
}
