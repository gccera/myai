package com.gccera.myai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.res.Configuration as AndroidConfig
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.gccera.myai.engine.ModelSessionManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyAiApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionManager: ModelSessionManager

    // WorkManager is initialised on demand with Hilt so workers can receive injected dependencies.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // If the OS is about to kill us for memory while in the background, free the loaded model
        // first (models are 0.3–2.6 GB). It is reloaded on demand next time it's needed.
        registerComponentCallbacks(object : ComponentCallbacks2 {
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE ||
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                ) sessionManager.releaseIfIdle()
            }
            override fun onConfigurationChanged(newConfig: AndroidConfig) = Unit
            @Deprecated("Deprecated in Java") override fun onLowMemory() = sessionManager.releaseIfIdle()
        })
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "model_downloads"
    }
}
