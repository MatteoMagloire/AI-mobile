package com.matteomagloire.aimobile.update

import com.matteomagloire.aimobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lit la dernière GitHub Release du dépôt et dit s'il existe une version plus récente.
 * Le dépôt doit être public : aucun jeton n'est embarqué dans l'app.
 */
class UpdateChecker(
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
) {
    /** Renvoie la mise à jour disponible, ou null si l'app est à jour. Lève une exception si le réseau échoue. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null // aucune Release publiée
                else -> error("GitHub a répondu ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            parseRelease(json)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseRelease(json: JSONObject): UpdateInfo? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name")
        val latest = Version.parse(tag) ?: return null
        val current = Version.parse(currentVersion) ?: return null
        if (latest <= current) return null

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var shaUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            when {
                name.endsWith(".apk.sha256") -> shaUrl = url
                name.endsWith(".apk") -> apkUrl = url
            }
        }
        return UpdateInfo(
            version = latest.toString(),
            notes = json.optString("body").trim(),
            apkUrl = apkUrl ?: return null,
            sha256Url = shaUrl,
            pageUrl = json.optString("html_url"),
        )
    }
}
