package com.matteomagloire.aimobile.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Moteur LLM local (llama.cpp). Le modèle reste chargé entre deux questions ;
 * un seul modèle à la fois pour tenir dans le budget RAM du S21 (~3 Go).
 */
class LlamaEngine {

    sealed interface State {
        data object Empty : State
        data class Loading(val modelId: String) : State
        data class Ready(val modelId: String) : State
        data class Failed(val message: String) : State
    }

    // llama.cpp n'est pas thread-safe : les appels natifs passent un par un.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaThread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    private var initialized = false

    suspend fun load(
        modelId: String,
        file: File,
        contextSize: Int = DEFAULT_CONTEXT,
        threads: Int = DEFAULT_THREADS,
    ) = withContext(llamaThread) {
        val current = _state.value
        if (current is State.Ready && current.modelId == modelId) return@withContext
        _state.value = State.Loading(modelId)
        if (!initialized) {
            LlamaNative.init()
            initialized = true
        }
        if (current is State.Ready) LlamaNative.unload()
        val code = LlamaNative.load(file.absolutePath, contextSize, threads)
        _state.value = if (code == 0) State.Ready(modelId)
        else State.Failed("Impossible de charger le modèle (code $code)")
    }

    suspend fun startConversation(systemPrompt: String, temperature: Float, topP: Float) =
        withContext(llamaThread) {
            check(_state.value is State.Ready) { "Aucun modèle chargé" }
            LlamaNative.configureSampler(temperature, topP)
            val code = LlamaNative.startConversation(systemPrompt)
            check(code == 0) { "Échec du prompt système (code $code)" }
        }

    suspend fun updateSampler(temperature: Float, topP: Float) = withContext(llamaThread) {
        if (_state.value is State.Ready) LlamaNative.configureSampler(temperature, topP)
    }

    /** Envoie le message et émet la réponse morceau par morceau (streaming). */
    fun generate(message: String, maxTokens: Int): Flow<String> = flow {
        check(_state.value is State.Ready) { "Aucun modèle chargé" }
        val code = LlamaNative.sendUserMessage(message, maxTokens)
        check(code == 0) { "Échec de l'envoi du message (code $code)" }
        try {
            while (currentCoroutineContext().isActive) {
                val piece = LlamaNative.nextToken() ?: break
                if (piece.isNotEmpty()) emit(piece)
            }
        } finally {
            // Si l'utilisateur arrête la génération, on garde ce qui a été écrit dans l'historique.
            LlamaNative.stopGeneration()
        }
    }.flowOn(llamaThread)

    suspend fun unload() = withContext(llamaThread) {
        if (_state.value is State.Ready) LlamaNative.unload()
        _state.value = State.Empty
    }

    companion object {
        /** Contexte limité à 4096 tokens pour économiser la RAM. */
        const val DEFAULT_CONTEXT = 4096

        /** 4 threads = cœurs X1 + A78 ; les petits A55 ralentissent. */
        const val DEFAULT_THREADS = 4
    }
}
