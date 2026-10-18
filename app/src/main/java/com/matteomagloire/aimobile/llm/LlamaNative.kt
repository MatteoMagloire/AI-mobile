package com.matteomagloire.aimobile.llm

/** Fonctions natives de llama_jni.cpp. À n'appeler que depuis [LlamaEngine]. */
internal object LlamaNative {
    init {
        System.loadLibrary("aimobile")
    }

    external fun init()
    external fun load(path: String, nCtx: Int, nThreads: Int): Int
    external fun configureSampler(temperature: Float, topP: Float, seed: Int)
    external fun startConversation(systemPrompt: String): Int
    external fun addHistoryMessage(fromUser: Boolean, text: String): Int
    external fun sendUserMessage(text: String, nPredict: Int): Int
    external fun nextToken(): String?
    external fun stopGeneration()
    external fun saveSession(path: String): Long
    external fun restoreSession(
        path: String,
        expectedBytes: Long,
        systemPrompt: String,
        systemPromptTokens: Int,
        fromUser: BooleanArray,
        texts: Array<String>,
    ): Int
    external fun contextUsage(): IntArray
    external fun lastStats(): LongArray
    external fun modelSize(): Long
    external fun unload()
    external fun systemInfo(): String
    external fun buildRef(): String
}
