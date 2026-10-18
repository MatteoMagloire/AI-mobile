package com.matteomagloire.aimobile.model

/** Modèles proposés. Téléchargés une seule fois depuis Hugging Face, hors des Releases de l'app. */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxSizeBytes: Long,
    val reasoning: Boolean,
    /** Format du fichier (quantification 4 bits), affiché dans la comparaison. */
    val quant: String,
    /** Qui a préparé le fichier. */
    val source: String,
)

object ModelCatalog {
    private const val INSTRUCT_NAME = "Ministral-3-3B-Instruct-2512"
    private const val REASONING_NAME = "Ministral-3-3B-Reasoning-2512"

    private fun mistral(base: String, reasoning: Boolean, id: String) = ModelInfo(
        id = id,
        displayName = if (reasoning) "Ministral 3 3B Reasoning" else "Ministral 3 3B Instruct",
        fileName = "$base-Q4_K_M.gguf",
        url = "https://huggingface.co/mistralai/$base-GGUF/resolve/main/$base-Q4_K_M.gguf",
        approxSizeBytes = 2_150_000_000L,
        reasoning = reasoning,
        quant = "Q4_K_M",
        source = "Mistral AI",
    )

    private fun unsloth(base: String, reasoning: Boolean, id: String, quant: String, size: Long) = ModelInfo(
        id = id,
        displayName = if (reasoning) "Ministral 3 3B Reasoning" else "Ministral 3 3B Instruct",
        fileName = "$base-$quant.gguf",
        url = "https://huggingface.co/unsloth/$base-GGUF/resolve/main/$base-$quant.gguf",
        approxSizeBytes = size,
        reasoning = reasoning,
        quant = quant,
        source = "Unsloth",
    )

    /** Au quotidien : répond directement, donc rapide. Fichier officiel, celui de la v0.1. */
    val INSTRUCT = mistral(INSTRUCT_NAME, reasoning = false, id = "ministral-3-3b-instruct-q4km")
    val INSTRUCT_UD = unsloth(INSTRUCT_NAME, false, "ministral-3-3b-instruct-ud-q4kxl", "UD-Q4_K_XL", 2_190_000_000L)
    val INSTRUCT_Q40 = unsloth(INSTRUCT_NAME, false, "ministral-3-3b-instruct-q40", "Q4_0", 2_050_000_000L)

    /** Mode réflexion : réfléchit entre [THINK]...[/THINK] avant de répondre. */
    val REASONING = mistral(REASONING_NAME, reasoning = true, id = "ministral-3-3b-reasoning-q4km")
    val REASONING_UD = unsloth(REASONING_NAME, true, "ministral-3-3b-reasoning-ud-q4kxl", "UD-Q4_K_XL", 2_190_000_000L)
    val REASONING_Q40 = unsloth(REASONING_NAME, true, "ministral-3-3b-reasoning-q40", "Q4_0", 2_050_000_000L)

    /** Les trois fichiers comparés dans « Comparer les fichiers », pour chaque modèle. */
    fun variants(reasoning: Boolean): List<ModelInfo> =
        if (reasoning) listOf(REASONING, REASONING_UD, REASONING_Q40) else listOf(INSTRUCT, INSTRUCT_UD, INSTRUCT_Q40)

    val all = variants(reasoning = false) + variants(reasoning = true)

    fun byId(id: String): ModelInfo? = all.firstOrNull { it.id == id }
}
