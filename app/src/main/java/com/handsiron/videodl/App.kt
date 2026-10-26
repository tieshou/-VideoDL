package com.handsiron.videodl

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            YoutubeDL.init(this)
            FFmpeg.init(this)
            initError = null
        } catch (e: Throwable) {
            Log.e("VideoDL", "init failed", e)
            initError = e.message ?: e.toString()
        }
    }

    companion object {
        @Volatile var initError: String? = null
    }
}
