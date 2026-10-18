package com.matteomagloire.aimobile.model

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.matteomagloire.aimobile.AiMobileApp
import com.matteomagloire.aimobile.R
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Télécharge un modèle GGUF (~2 Go) en arrière-plan, avec reprise si la connexion coupe.
 * Le fichier est d'abord écrit en .part puis renommé quand il est complet.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val model = ModelCatalog.byId(inputData.getString(KEY_MODEL_ID) ?: "")
            ?: return Result.failure()
        val store = ModelStore(applicationContext)
        if (store.isDownloaded(model)) return Result.success()

        setForeground(foregroundInfo(model, 0))
        val part = store.partialFileFor(model)
        return try {
            val already = if (part.exists()) part.length() else 0L
            val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (already > 0) setRequestProperty("Range", "bytes=$already-")
            }
            val code = conn.responseCode
            if (code == 416 && already > 0) {
                // Le fichier partiel est déjà complet.
                conn.disconnect()
                return if (part.renameTo(store.fileFor(model))) Result.success() else Result.failure()
            }
            val resumed = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resumed) {
                conn.disconnect()
                return Result.retry()
            }
            val start = if (resumed) already else 0L
            val total = start + conn.contentLengthLong.coerceAtLeast(0)
            var done = start
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (isStopped) return Result.retry()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            setProgress(workDataOf(KEY_PROGRESS to percent))
                            setForeground(foregroundInfo(model, percent))
                        }
                    }
                }
            }
            if (total > 0 && done < total) return Result.retry()
            if (!part.renameTo(store.fileFor(model))) return Result.failure()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun foregroundInfo(model: ModelInfo, percent: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, AiMobileApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.downloading_model, model.displayName))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        private const val NOTIFICATION_ID = 1001

        fun workName(model: ModelInfo) = "download-${model.id}"

        fun enqueue(context: Context, model: ModelInfo, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_ID to model.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request)
        }
    }
}
