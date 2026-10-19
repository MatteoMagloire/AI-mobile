package com.matteomagloire.aimobile.bench

import com.matteomagloire.aimobile.brain.BrainCommand
import com.matteomagloire.aimobile.brain.BrainCommands
import com.matteomagloire.aimobile.brain.Recall
import com.matteomagloire.aimobile.chat.ChatMessage
import com.matteomagloire.aimobile.chat.HistoryWindow
import com.matteomagloire.aimobile.llm.LlamaEngine
import com.matteomagloire.aimobile.llm.ThinkParser
import com.matteomagloire.aimobile.model.ModelCatalog
import com.matteomagloire.aimobile.settings.DEFAULT_SYSTEM_PROMPT
import com.matteomagloire.aimobile.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Banc d'Axo : de vraies conversations avec le vrai modèle, par le code de l'appli (pont JNI compilé
 * pour l'ordinateur, personnalité par défaut, souvenirs du Cerveau, réglages par défaut). Ne tourne
 * que si AXO_BENCH_MODEL donne le fichier du modèle (workflow « Banc d'Axo ») ; sinon il est ignoré.
 * Le rapport est écrit dans build/axo-bench/report.md.
 */
class AxoBenchTest {
    private data class Answer(val text: String, val tokens: Int, val ms: Long, val failures: List<String>)

    @Test
    fun conversations() = runBlocking {
        val path = System.getenv("AXO_BENCH_MODEL")
        assumeTrue("AXO_BENCH_MODEL absent : banc ignoré", !path.isNullOrBlank())
        val file = File(path!!)
        val model = ModelCatalog.byId(System.getenv("AXO_BENCH_MODEL_ID").orEmpty()) ?: ModelCatalog.INSTRUCT_Q40
        assertTrue("Fichier du modèle introuvable : $file", file.isFile)
        val seeds = System.getenv("AXO_BENCH_SEEDS").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(1) }
        val settings = Settings()

        val engine = LlamaEngine()
        engine.setThreads(Runtime.getRuntime().availableProcessors())
        engine.load(model.id, file)
        val state = engine.state.value
        assertTrue("Modèle pas chargé : $state", state is LlamaEngine.State.Ready)
        engine.startConversation(DEFAULT_SYSTEM_PROMPT)
        val promptTokens = engine.usage().systemPromptTokens

        val results = LinkedHashMap<BenchCase, List<Answer>>()
        for (case in BENCH_CASES) {
            val memory = memoryFor(case)
            results[case] = seeds.map { seed ->
                // Comme à la réouverture de l'appli : la personnalité (gardée en mémoire), puis les échanges d'avant.
                engine.startConversation(DEFAULT_SYSTEM_PROMPT)
                engine.replay(HistoryWindow.select(HistoryWindow.turns(history(case))))
                engine.configureSampler(settings.temperature, settings.topP, seed)
                val start = System.nanoTime()
                val raw = StringBuilder()
                engine.generate(memory + case.message, settings.maxTokens).collect { raw.append(it) }
                val ms = (System.nanoTime() - start) / 1_000_000
                val text = ThinkParser.parse(raw.toString()).answer.trim()
                val tokens = engine.lastStats().genTokens
                val failures = (Checks.style(case.long) + case.checks).mapNotNull { it.failure(text) } +
                    listOfNotNull(if (tokens >= settings.maxTokens) "coupée à ${settings.maxTokens} tokens" else null)
                Answer(text, tokens, ms, failures)
            }
        }
        engine.unload()

        val report = report(model.fileName, seeds, promptTokens, settings, results)
        File("build/axo-bench").apply { mkdirs() }.resolve("report.md").writeText(report)
        val failed = results.flatMap { (case, answers) -> answers.filter { it.failures.isNotEmpty() }.map { case.id } }
        assertTrue("Réponses qui ratent : ${failed.joinToString()} (voir build/axo-bench/report.md)", failed.isEmpty())
    }

    /** Ce que le Cerveau glisse devant le message, comme dans l'appli (Brain.prepare). */
    private fun memoryFor(case: BenchCase): String {
        val given = Recall.alreadyGiven(emptyList())
        val r = Recall.recollect(case.notes, case.message, given, case.startOfSession, dormant = emptySet())
        return Recall.block(r.facts, remember = BrainCommands.parse(case.message) is BrainCommand.Remember)
    }

    private fun history(case: BenchCase): List<ChatMessage> =
        case.before.flatMap { (mine, axo) -> listOf(ChatMessage(fromUser = true, text = mine), ChatMessage(fromUser = false, text = axo)) }

    private fun report(
        modelFile: String,
        seeds: List<Int>,
        promptTokens: Int,
        settings: Settings,
        results: Map<BenchCase, List<Answer>>,
    ): String = buildString {
        val all = results.values.flatten()
        val passed = all.count { it.failures.isEmpty() }
        appendLine("# Banc d'Axo")
        appendLine()
        appendLine(
            "Modèle : `$modelFile` · créativité ${"%.2f".format(Locale.FRANCE, settings.temperature)} · " +
                "graines ${seeds.joinToString()} · ${settings.maxTokens} tokens au plus",
        )
        appendLine()
        appendLine("Personnalité : $promptTokens tokens (${DEFAULT_SYSTEM_PROMPT.length} caractères)")
        appendLine()
        appendLine("**$passed réponses sur ${all.size} passent.**")
        appendLine()
        appendLine("| Cas | Ce qu'on vérifie | " + seeds.joinToString(" | ") { "Graine $it" } + " |")
        appendLine("|---|---|" + seeds.joinToString("") { "---|" })
        for ((case, answers) in results) {
            val cells = answers.joinToString(" | ") { a -> if (a.failures.isEmpty()) "ok" else "**rate** : " + a.failures.joinToString(" ; ") }
            appendLine("| ${case.id} | ${case.about} | $cells |")
        }
        for ((case, answers) in results) {
            appendLine()
            appendLine("## ${case.id}")
            appendLine()
            appendLine("${case.about}.")
            appendLine()
            for ((mine, axo) in case.before) {
                appendLine("> **Matteo** : $mine")
                appendLine(">")
                appendLine("> **Axo** : $axo")
                appendLine()
            }
            appendLine("**Matteo** : ${case.message}")
            appendLine()
            val memory = memoryFor(case)
            if (memory.isNotEmpty()) {
                appendLine("Ce qu'Axo relit avant :")
                appendLine()
                appendLine("```")
                append(memory)
                appendLine("```")
                appendLine()
            }
            for ((seed, a) in seeds.zip(answers)) {
                val verdict = if (a.failures.isEmpty()) "ok" else "rate : " + a.failures.joinToString(" ; ")
                appendLine("**Axo**, graine $seed (${a.tokens} tokens, ${"%.1f".format(Locale.FRANCE, a.ms / 1000.0)} s, $verdict) :")
                appendLine()
                a.text.lines().forEach { appendLine("> $it") }
                appendLine()
            }
        }
    }
}
