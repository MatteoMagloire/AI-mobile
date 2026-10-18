package com.matteomagloire.aimobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.update.UpdateCheckWorker

class AiMobileApp : Application() {

    /** Un seul moteur pour toute l'app : le modèle reste chargé entre deux écrans. */
    val engine by lazy { LlamaEngine() }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        UpdateCheckWorker.schedule(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_UPDATES = "updates"
    }
}
