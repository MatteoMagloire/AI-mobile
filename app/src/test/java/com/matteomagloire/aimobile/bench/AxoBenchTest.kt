package com.matteomagloire.aimobile.bench

import com.matteomagloire.aimobile.brain.BrainCommand
import com.matteomagloire.aimobile.brain.BrainCommands
import com.matteomagloire.aimobile.brain.Cue
import com.matteomagloire.aimobile.brain.Recall
import com.matteomagloire.aimobile.chat.AnswerText
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
 * Le rapport est écrit dans build/axo-bench/report.md. Le banc mesure, il ne bloque rien : il ne rate
 * que s'il ne peut pas tourner, et les réponses qui ratent sont dans le rapport.
 */
class AxoBenchTest {
    /** [sameAfterRewrite] : null si rien n'est réécrit après la réponse (voir [sameAsReplay]). */
    private data class Answer(val text: String, val tokens: Int, val ms: Long, val failures: List<String>, val sameAfterRewrite: Boolean? = null)

    @Test
    fun conversations() = runBlocking {
        val path = System.getenv("AXO_BENCH_MODEL")
        assumeTrue("AXO_BENCH_MODEL absent : banc ignoré", !path.isNullOrBlank())
        val file = File(path!!)
        val model = ModelCatalog.byId(System.getenv("AXO_BENCH_MODEL_ID").orEmpty()) ?: ModelCatalog.INSTRUCT_Q40
        assertTrue("Fichier du modèle introuvable : $file", file.isFile)
        val seeds = System.getenv("AXO_BENCH_SEEDS").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(1) }
        // Une autre personnalité à essayer, sans toucher à l'appli (« \n » pour aller à la ligne).
        val trial = System.getenv("AXO_BENCH_PROMPT").orEmpty().replace("\\n", "\n").trim().ifEmpty { null }
        val prompt = trial ?: DEFAULT_SYSTEM_PROMPT
        val settings = Settings()
        // Quelques conversations seulement, pour comparer vite (vide = toutes).
        val only = System.getenv("AXO_BENCH_CASES").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val unknown = only - BENCH_CASES.map { it.id }.toSet()
        assertTrue("Conversations inconnues : ${unknown.joinToString()}", unknown.isEmpty())
        val cases = if (only.isEmpty()) BENCH_CASES else BENCH_CASES.filter { it.id in only }
        // Des rappels à ne pas lire (Cue), pour mesurer ce qu'ils changent, jusque dans les messages d'après.
        val skipped = System.getenv("AXO_BENCH_WITHOUT").orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        val unknownCues = skipped - Cue.entries.map { it.name }.toSet()
        assertTrue("Rappels inconnus : ${unknownCues.joinToString()}", unknownCues.isEmpty())
        val without = Cue.entries.filter { it.name in skipped }.toSet()

        val engine = LlamaEngine()
        engine.setThreads(Runtime.getRuntime().availableProcessors())
        engine.load(model.id, file)
        val state = engine.state.value
        assertTrue("Modèle pas chargé : $state", state is LlamaEngine.State.Ready)
        engine.startConversation(prompt)
        val promptTokens = engine.usage().systemPromptTokens

        val results = LinkedHashMap<BenchCase, List<Answer>>()
        // Le plus long réglage du tirage : DRY relit tout le vocabulaire (l'appli garde le sien d'un message à l'autre).
        var samplerMs = 0L
        for (case in cases) {
            val memories = memories(case, without)
            val memory = memories.last()
            val lasting = memories(case, without, lasting = true).last()
            results[case] = seeds.map { seed ->
                // Comme à la réouverture de l'appli : la personnalité (gardée en mémoire), puis les échanges d'avant.
                engine.startConversation(prompt)
                engine.replay(HistoryWindow.select(HistoryWindow.turns(history(case, memories))))
                val configured = System.nanoTime()
                engine.configureSampler(settings.temperature, settings.topP, seed)
                samplerMs = maxOf(samplerMs, (System.nanoTime() - configured) / 1_000_000)
                val start = System.nanoTime()
                val raw = StringBuilder()
                engine.generate(memory + case.message, settings.maxTokens).collect { raw.append(it) }
                val ms = (System.nanoTime() - start) / 1_000_000
                val text = ThinkParser.parse(raw.toString()).answer.trim()
                val tokens = engine.lastStats().genTokens
                // Vérifié tel que l'appli l'affiche.
                val shown = AnswerText.styled(text).text
                val failures = (Checks.style(case.long) + case.checks).mapNotNull { it.failure(shown) } +
                    listOfNotNull(if (tokens >= settings.maxTokens) "coupée à ${settings.maxTokens} tokens" else null)
                val same = if (lasting == memory) null else sameAsReplay(engine, prompt, case, memories, lasting, text)
                Answer(text, tokens, ms, failures, same)
            }
        }
        engine.unload()

        val report = report(model.fileName, seeds, trial, prompt, promptTokens, samplerMs, settings, without, results)
        File("build/axo-bench").apply { mkdirs() }.resolve("report.md").writeText(report)
        val all = results.values.flatten()
        println("Banc d'Axo : ${all.count { it.failures.isEmpty() }} réponses sur ${all.size} passent (build/axo-bench/report.md)")
        val rewritten = all.mapNotNull { it.sameAfterRewrite }
        if (rewritten.isNotEmpty()) println("Réécrites après la réponse : même suite que relues ${rewritten.count { it }} fois sur ${rewritten.size}")
    }

    /**
     * Comme l'appli une fois la réponse écrite : le message est réécrit sans les rappels lus seulement
     * pour répondre (LlamaEngine.rewriteLastExchange). La suite (le message suivant, même graine) doit
     * être celle de la conversation relue sans eux, comme à la réouverture de l'appli.
     */
    private suspend fun sameAsReplay(
        engine: LlamaEngine,
        prompt: String,
        case: BenchCase,
        memories: List<String>,
        lasting: String,
        reply: String,
    ): Boolean {
        val rewritten = engine.rewriteLastExchange(lasting + case.message, reply)
        val afterRewrite = next(engine)
        engine.startConversation(prompt)
        val history = history(case, memories) +
            ChatMessage(fromUser = true, text = case.message, memory = lasting) + ChatMessage(fromUser = false, text = reply)
        engine.replay(HistoryWindow.select(HistoryWindow.turns(history)))
        return rewritten && afterRewrite == next(engine)
    }

    /** La place prise dans le contexte, puis le début de la réponse au message qu'il a suivi au téléphone. */
    private suspend fun next(engine: LlamaEngine): String {
        val used = engine.usage().usedTokens
        val raw = StringBuilder()
        engine.generate("dit moi comment je m'appelle ?", 24).collect { raw.append(it) }
        return "$used $raw"
    }

    /**
     * Ce que le Cerveau glisse devant chacun de ses messages, ceux d'avant puis le dernier, comme dans
     * l'appli (Brain.prepare) : les souvenirs déjà glissés dans les 10 derniers ne sont pas redonnés, et
     * le modèle relit ceux d'avant avec la conversation (ChatMessage.modelText), sans les rappels lus
     * seulement pour leur réponse (Cue.onlyForTheReply). [lasting] : le dernier aussi, tel qu'il reste
     * une fois répondu.
     */
    private fun memories(case: BenchCase, without: Set<Cue>, lasting: Boolean = false): List<String> {
        val messages = case.before.map { it.first } + case.message
        val out = ArrayList<String>()
        for ((i, message) in messages.withIndex()) {
            val r = Recall.recollect(case.notes, message, Recall.alreadyGiven(out.takeLast(10)), case.startOfSession && i == 0, dormant = emptySet())
            val cues = Cue.of(message) - without
            out += Recall.block(
                r.facts,
                remember = BrainCommands.parse(message) is BrainCommand.Remember,
                cues = if (lasting || i < messages.lastIndex) Cue.lasting(cues) else cues,
                tuCest = Recall.writesTuCest(message),
            )
        }
        return out
    }

    private fun history(case: BenchCase, memories: List<String>): List<ChatMessage> =
        case.before.withIndex().flatMap { (i, turn) ->
            listOf(ChatMessage(fromUser = true, text = turn.first, memory = memories[i]), ChatMessage(fromUser = false, text = turn.second))
        }

    private fun report(
        modelFile: String,
        seeds: List<Int>,
        trial: String?,
        prompt: String,
        promptTokens: Int,
        samplerMs: Long,
        settings: Settings,
        without: Set<Cue>,
        results: Map<BenchCase, List<Answer>>,
    ): String = buildString {
        val all = results.values.flatten()
        val passed = all.count { it.failures.isEmpty() }
        appendLine("# Banc d'Axo")
        appendLine()
        appendLine(
            "Modèle : `$modelFile` · créativité ${"%.2f".format(Locale.FRANCE, settings.temperature)} · " +
                "graines ${seeds.joinToString()} · ${settings.maxTokens} tokens au plus · tirage réglé en $samplerMs ms au plus",
        )
        appendLine()
        appendLine("Personnalité ${if (trial == null) "de l'appli" else "à l'essai"} : $promptTokens tokens (${prompt.length} caractères)")
        appendLine()
        if (trial != null) {
            appendLine("```")
            appendLine(trial)
            appendLine("```")
            appendLine()
        }
        if (without.isNotEmpty()) {
            appendLine("Sans les rappels : ${without.joinToString { it.name }}")
            appendLine()
        }
        appendLine("**$passed réponses sur ${all.size} passent.**")
        appendLine()
        val rewritten = all.mapNotNull { it.sameAfterRewrite }
        if (rewritten.isNotEmpty()) {
            appendLine(
                "Réécrites ensuite sans les rappels lus pour la réponse, comme dans l'appli : même suite qu'une " +
                    "conversation relue sans eux ${rewritten.count { it }} fois sur ${rewritten.size}.",
            )
            appendLine()
        }
        appendLine("Les réponses sont vérifiées et montrées comme l'appli les affiche : gras et italique sans astérisques, actions entre astérisques retirées.")
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
            val memories = memories(case, without)
            for ((i, turn) in case.before.withIndex()) {
                appendLine("> **Matteo** : ${turn.first}")
                appendLine(">")
                if (memories[i].isNotEmpty()) {
                    appendLine("> Ce qu'Axo relit avant : ${memories[i].lines().filter { it.isNotBlank() }.joinToString(" · ")}")
                    appendLine(">")
                }
                appendLine("> **Axo** : ${turn.second}")
                appendLine()
            }
            appendLine("**Matteo** : ${case.message}")
            appendLine()
            val memory = memories.last()
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
                val hidden = AnswerText.actions(a.text).takeIf { it.isNotEmpty() }?.let { " · pas affiché : « ${it.joinToString(" », « ")} »" }.orEmpty()
                val rewrite = if (a.sameAfterRewrite == false) " · réécrite : autre suite qu'en la relisant" else ""
                appendLine("**Axo**, graine $seed (${a.tokens} tokens, ${"%.1f".format(Locale.FRANCE, a.ms / 1000.0)} s, $verdict$hidden$rewrite) :")
                appendLine()
                // Le gras et l'italique restent en Markdown : le résumé de GitHub les affiche comme l'appli.
                AnswerText.withoutActions(a.text).lines().forEach { appendLine("> $it") }
                appendLine()
            }
        }
    }
}
