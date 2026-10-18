package com.matteomagloire.aimobile.measure

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Résultats des mesures, gardés sur le téléphone pour comparer d'une version à l'autre. */
class MeasureStore(context: Context) {
    private val dir = File(context.filesDir, "measures").apply { mkdirs() }
    private val speedFile = File(dir, "speed.json")
    private val enduranceFile = File(dir, "endurance.json")
    private fun benchFile(reasoning: Boolean) = File(dir, if (reasoning) "bench-reasoning.json" else "bench-instruct.json")

    suspend fun loadSpeed(): List<SpeedResult> = read(speedFile) { o ->
        o.optJSONArray("results").objects().map { r ->
            SpeedResult(
                time = r.getLong("time"),
                modelId = r.getString("model"),
                loadMs = r.optLong("loadMs"),
                firstWordMs = r.getLong("firstWordMs"),
                longFirstWordMs = r.optLong("longFirstWordMs"),
                promptTokens = r.optInt("promptTokens"),
                promptTokensPerSecond = r.optDouble("promptTps", 0.0),
                tokensPerSecond = r.optDouble("tps", 0.0),
                rssBytes = r.optLong("rss"),
                peakRssBytes = r.optLong("peakRss"),
                batteryBefore = r.optDouble("batteryBefore", Double.NaN).toFloat(),
                batteryAfter = r.optDouble("batteryAfter", Double.NaN).toFloat(),
                thermalStatus = r.optInt("thermal"),
                threads = r.optInt("threads"),
            )
        }
    } ?: emptyList()

    suspend fun saveSpeed(results: List<SpeedResult>) = write(speedFile) {
        put("results", JSONArray().apply {
            results.takeLast(MAX_HISTORY).forEach { r ->
                put(
                    JSONObject()
                        .put("time", r.time)
                        .put("model", r.modelId)
                        .put("loadMs", r.loadMs)
                        .put("firstWordMs", r.firstWordMs)
                        .put("longFirstWordMs", r.longFirstWordMs)
                        .put("promptTokens", r.promptTokens)
                        .put("promptTps", r.promptTokensPerSecond.finite())
                        .put("tps", r.tokensPerSecond.finite())
                        .put("rss", r.rssBytes)
                        .put("peakRss", r.peakRssBytes)
                        .putFloat("batteryBefore", r.batteryBefore)
                        .putFloat("batteryAfter", r.batteryAfter)
                        .put("thermal", r.thermalStatus)
                        .put("threads", r.threads),
                )
            }
        })
    }

    suspend fun loadEndurance(): List<EnduranceResult> = read(enduranceFile) { o ->
        o.optJSONArray("results").objects().map { r ->
            EnduranceResult(
                time = r.getLong("time"),
                modelId = r.getString("model"),
                points = r.optJSONArray("points").objects().map { p ->
                    EndurancePoint(
                        seconds = p.getInt("s"),
                        tokensPerSecond = p.optDouble("tps", 0.0),
                        batteryC = p.optDouble("battery", Double.NaN).toFloat(),
                    )
                },
                thermalStatus = r.optInt("thermal"),
                threads = r.optInt("threads", 4),
            )
        }
    } ?: emptyList()

    suspend fun saveEndurance(results: List<EnduranceResult>) = write(enduranceFile) {
        put("results", JSONArray().apply {
            results.takeLast(MAX_ENDURANCE).forEach { r ->
                put(
                    JSONObject()
                        .put("time", r.time)
                        .put("model", r.modelId)
                        .put("thermal", r.thermalStatus)
                        .put("threads", r.threads)
                        .put("points", JSONArray().apply {
                            r.points.forEach { p ->
                                put(JSONObject().put("s", p.seconds).put("tps", p.tokensPerSecond.finite()).putFloat("battery", p.batteryC))
                            }
                        }),
                )
            }
        })
    }

    suspend fun loadBench(reasoning: Boolean): BenchState = read(benchFile(reasoning)) { o ->
        if (o.optInt("questions") != BENCH_QUESTIONS.size || o.optInt("version") != VERSION) return@read null
        val runs = o.optJSONArray("runs").objects().associate { r ->
            val id = r.getString("model")
            id to BenchRun(
                modelId = id,
                answers = r.optJSONArray("answers").objects().associate { a ->
                    a.getInt("q") to BenchAnswer(
                        text = a.optString("text"),
                        thinking = a.optString("thinking"),
                        firstWordMs = a.optLong("firstWordMs"),
                        tokensPerSecond = a.optDouble("tps", 0.0),
                        tokens = a.optInt("tokens"),
                        totalMs = a.optLong("totalMs"),
                    )
                },
                loadMs = r.optLong("loadMs"),
                peakRssBytes = r.optLong("peakRss"),
            )
        }
        BenchState(
            reasoning = reasoning,
            runs = runs,
            voters = o.optJSONArray("voters").strings(),
            order = o.optJSONObject("order")?.let { order ->
                order.keys().asSequence().associate { it.toInt() to order.getJSONArray(it).strings() }
            } ?: emptyMap(),
            votes = o.optJSONObject("votes")?.let { votes ->
                votes.keys().asSequence().associate { it.toInt() to votes.getString(it) }
            } ?: emptyMap(),
        )
    } ?: BenchState(reasoning)

    suspend fun saveBench(state: BenchState) = write(benchFile(state.reasoning)) {
        put("version", VERSION)
        put("questions", BENCH_QUESTIONS.size)
        put("runs", JSONArray().apply {
            state.runs.values.forEach { run ->
                put(
                    JSONObject()
                        .put("model", run.modelId)
                        .put("loadMs", run.loadMs)
                        .put("peakRss", run.peakRssBytes)
                        .put("answers", JSONArray().apply {
                            run.answers.toSortedMap().forEach { (q, a) ->
                                put(
                                    JSONObject()
                                        .put("q", q)
                                        .put("text", a.text)
                                        .put("thinking", a.thinking)
                                        .put("firstWordMs", a.firstWordMs)
                                        .put("tps", a.tokensPerSecond.finite())
                                        .put("tokens", a.tokens)
                                        .put("totalMs", a.totalMs),
                                )
                            }
                        }),
                )
            }
        })
        put("voters", JSONArray(state.voters))
        put("order", JSONObject().apply { state.order.forEach { (q, ids) -> put(q.toString(), JSONArray(ids)) } })
        put("votes", JSONObject().apply { state.votes.forEach { (q, v) -> put(q.toString(), v) } })
    }

    private suspend fun <T> read(file: File, parse: (JSONObject) -> T?): T? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            parse(JSONObject(file.readText()))
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun write(file: File, fill: JSONObject.() -> Unit) = withContext(Dispatchers.IO) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().apply(fill).toString())
        tmp.renameTo(file)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { getString(it) }

    // JSON n'accepte ni NaN ni l'infini.
    private fun Double.finite() = if (isFinite()) this else 0.0

    private fun JSONObject.putFloat(name: String, value: Float): JSONObject =
        if (value.isNaN()) this else put(name, value.toDouble())

    private companion object {
        const val VERSION = 1
        const val MAX_HISTORY = 20
        const val MAX_ENDURANCE = 5
    }
}
