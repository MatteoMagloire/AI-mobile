package com.matteomagloire.aimobile.model

import android.content.Context
import java.io.File

/** Emplacement des modèles sur le téléphone (stockage interne de l'app). */
class ModelStore(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(model: ModelInfo) = File(dir, model.fileName)

    fun partialFileFor(model: ModelInfo) = File(dir, model.fileName + ".part")

    fun isDownloaded(model: ModelInfo) = fileFor(model).let { it.exists() && it.length() > 0 }

    fun delete(model: ModelInfo) {
        fileFor(model).delete()
        partialFileFor(model).delete()
    }
}
