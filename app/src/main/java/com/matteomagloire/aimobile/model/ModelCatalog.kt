package com.matteomagloire.aimobile.model

/** Modèles proposés. Téléchargés une seule fois depuis Hugging Face, hors des Releases de l'app. */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxSizeBytes: Long,
    val reasoning: Boolean,
)

object ModelCatalog {
    private const val HF = "https://huggingface.co/mistralai"

    /** Au quotidien : répond directement, donc rapide. */
    val INSTRUCT = ModelInfo(
        id = "ministral-3-3b-instruct-q4km",
        displayName = "Ministral 3 3B Instruct",
        fileName = "Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        url = "$HF/Ministral-3-3B-Instruct-2512-GGUF/resolve/main/Ministral-3-3B-Instruct-2512-Q4_K_M.gguf",
        approxSizeBytes = 2_150_000_000L,
        reasoning = false,
    )

    /** Mode réflexion : réfléchit entre [THINK]...[/THINK] avant de répondre. */
    val REASONING = ModelInfo(
        id = "ministral-3-3b-reasoning-q4km",
        displayName = "Ministral 3 3B Reasoning",
        fileName = "Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
        url = "$HF/Ministral-3-3B-Reasoning-2512-GGUF/resolve/main/Ministral-3-3B-Reasoning-2512-Q4_K_M.gguf",
        approxSizeBytes = 2_150_000_000L,
        reasoning = true,
    )

    val all = listOf(INSTRUCT, REASONING)

    fun byId(id: String): ModelInfo? = all.firstOrNull { it.id == id }
}
