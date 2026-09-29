package io.github.channelramble.multiappaudio

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Helper.init(this)
    }
}
