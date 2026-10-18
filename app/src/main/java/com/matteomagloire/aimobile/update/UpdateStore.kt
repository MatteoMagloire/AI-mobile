package com.matteomagloire.aimobile.update

import android.content.Context
import android.content.SharedPreferences
import com.matteomagloire.aimobile.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Garde la mise à jour trouvée pour la proposer dès que l'app est ouverte. */
class UpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun save(info: UpdateInfo?) {
        prefs.edit().apply {
            if (info == null) {
                remove(KEY_VERSION)
            } else {
                putString(KEY_VERSION, info.version)
                putString(KEY_NOTES, info.notes)
                putString(KEY_APK, info.apkUrl)
                putString(KEY_SHA, info.sha256Url)
                putString(KEY_PAGE, info.pageUrl)
            }
        }.apply()
    }

    fun pending(): UpdateInfo? {
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val latest = Version.parse(version)
        val current = Version.parse(BuildConfig.VERSION_NAME)
        // Déjà installée : on oublie.
        if (latest == null || current == null || latest <= current) return null
        return UpdateInfo(
            version = version,
            notes = prefs.getString(KEY_NOTES, "") ?: "",
            apkUrl = prefs.getString(KEY_APK, null) ?: return null,
            sha256Url = prefs.getString(KEY_SHA, null),
            pageUrl = prefs.getString(KEY_PAGE, "") ?: "",
        )
    }

    /** Version déjà signalée par notification (pour ne pas notifier deux fois). */
    var notifiedVersion: String?
        get() = prefs.getString(KEY_NOTIFIED, null)
        set(value) = prefs.edit().putString(KEY_NOTIFIED, value).apply()

    fun observe(): Flow<UpdateInfo?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(pending()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(pending())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_VERSION = "version"
        const val KEY_NOTES = "notes"
        const val KEY_APK = "apk_url"
        const val KEY_SHA = "sha_url"
        const val KEY_PAGE = "page_url"
        const val KEY_NOTIFIED = "notified_version"
    }
}
