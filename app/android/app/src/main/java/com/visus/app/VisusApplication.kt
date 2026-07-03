package com.visus.app

import android.app.Application

class VisusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Global crash handler — prevents immediate crash from unhandled exceptions
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            android.util.Log.e("VisusCrash", "Uncaught: ${ex.message}", ex)
            defaultHandler?.uncaughtException(thread, ex)
        }
    }
}
