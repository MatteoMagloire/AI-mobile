package com.matteomagloire.aimobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.matteomagloire.aimobile.brain.Brain
import com.matteomagloire.aimobile.chat.ConversationStore
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.llm.SessionCache
import com.matteomagloire.aimobile.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AiMobileApp : Application() {

    /** Un seul moteur pour toute l'app : le modèle reste chargé entre deux écrans. */
    val engine by lazy { LlamaEngine(taskCacheDir = File(cacheDir, "notes-kv")) }

    /** Sessions du moteur enregistrées, pour reprendre la conversation sans tout relire. */
    val sessions by lazy { SessionCache(this) }

    val conversation by lazy { ConversationStore(this) }

    /** Les notes d'Axo, et la prise de notes en tâche de fond. */
    val brain by lazy { Brain(this, engine, sessions, appScope) }

    /** Travaux qui doivent aller au bout même si l'écran est fermé (enregistrements). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        UpdateCheckWorker.schedule(this)
        // En arrière-plan, Android peut fermer l'app à tout moment : on enregistre la conversation
        // du moteur pour la retrouver telle quelle au retour.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                brain.resume()
            }

            override fun onStop(owner: LifecycleOwner) {
                // La prise de notes attendra le retour : l'enregistrement passe avant.
                brain.interrupt()
                appScope.launch { sessions.use { save(engine) } }
            }
        })
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
