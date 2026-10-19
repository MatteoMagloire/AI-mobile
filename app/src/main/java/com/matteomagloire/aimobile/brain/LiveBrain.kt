package com.matteomagloire.aimobile.brain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Ce qu'Axo fait dans sa mémoire, au moment où il le fait : l'écran le montre en direct. */
sealed interface BrainEvent {
    /** Il se met au travail : il pense à ton message, ou il range ses notes après sa réponse. */
    data class Begin(val work: Work) : BrainEvent

    /** Il a cherché : les notes trouvées, de la plus proche à la moins proche, et celles qu'il garde. */
    data class Searched(val found: List<String>, val kept: List<String>) : BrainEvent

    /** Il lit une note : ses faits passent devant ton message, ou il la compare à ce qu'il va noter. */
    data class Read(val noteId: String) : BrainEvent

    /** Il suit un lien : un fait de [from] nomme [to]. */
    data class Followed(val from: String, val to: String) : BrainEvent

    /** Il écrit dans une note ([created] : une note nouvelle), et les notes qu'elle relie depuis. */
    data class Wrote(val noteId: String, val created: Boolean, val links: List<String> = emptyList()) : BrainEvent

    /** Une note en veille sert de nouveau : elle se réveille. */
    data class Woke(val noteId: String) : BrainEvent

    /** Il a fini de lire et commence sa réponse. */
    data object Answering : BrainEvent

    /**
     * Fin du travail [work]. Une prise de notes interrompue par un message finit parfois après le
     * début de la réponse : sa fin ne touche pas la réponse.
     */
    data class End(val work: Work) : BrainEvent

    enum class Work { ANSWER, NOTES }
}

/** Ce qu'on lit sous le graphe pendant qu'Axo travaille. [noteId] : la note concernée. */
data class Caption(val kind: Kind, val noteId: String? = null) {
    enum class Kind { THINKING, ANSWERING, NOTING, SEARCHING, READING, FOLLOWING, WRITING, CREATING, WAKING }
}

/** Une boule qui voyage sur un lien, ou un lien qui se trace : de [from] vers [to], [progress] de 0 à 1. */
class Moving(val from: String, val to: String, val progress: Float)

/**
 * Une image du Cerveau en direct, à un instant. Les valeurs vont de 0 à 1 : [light], l'éclat d'une
 * note (trouvée, lue) ; [pulse], le battement d'une note en cours de lecture ; [trail], les liens
 * empruntés ; [grow], le gonflement d'une note où il écrit ; [appear], l'apparition d'une note
 * nouvelle (0 : pas encore là) ; [strokes], les liens nouveaux qui se tracent (0 : pas encore là) ;
 * [wake], le passage du givre à l'ambre (0 : encore en veille). [focus] : là où il travaille.
 */
class LiveFrame(
    val light: Map<String, Float>,
    val pulse: Map<String, Float>,
    val balls: List<Moving>,
    val trail: Map<Pair<String, String>, Float>,
    val grow: Map<String, Float>,
    val appear: Map<String, Float>,
    val strokes: List<Moving>,
    val wake: Map<String, Float>,
    val focus: String?,
    val caption: Caption?,
) {
    val isEmpty: Boolean
        get() = light.isEmpty() && pulse.isEmpty() && balls.isEmpty() && trail.isEmpty() && grow.isEmpty() &&
            appear.isEmpty() && strokes.isEmpty() && wake.isEmpty() && caption == null

    companion object {
        val EMPTY = LiveFrame(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyMap(), null, null)
    }
}

/**
 * Le déroulé à l'écran de ce que fait Axo. Chaque geste dure assez pour se voir (une boule met
 * environ 0,4 s par lien), quitte à se finir un peu après le geste réel : l'écran ne fait jamais
 * attendre Axo. « Rejouer » remontre le dernier travail au ralenti. Les temps sont en millisecondes,
 * sur l'horloge de l'écran ; tout se passe sur le fil de l'écran.
 */
class LiveBrain {
    private enum class Kind { FLASH, LIT, PULSE, BALL, TRAIL, GROW, APPEAR, DRAW, WAKE }

    /** Un effet : sur [note] (et [other] pour un lien), de [start] pendant [length], ou jusqu'à [until]. */
    private class Shot(val kind: Kind, val note: String, val other: String?, val start: Long, val length: Long = 0) {
        var until = Long.MAX_VALUE
    }

    /** Ce qu'on lit sous le graphe à partir de [at], et la note à suivre ; [end] : fin d'un travail. */
    private class Mark(val at: Long, val caption: Caption?, val focus: String?, val end: Boolean = false)

    /** Un travail gardé pour « Rejouer » : ses gestes, dans l'ordre. */
    private class Take(val work: BrainEvent.Work, val events: List<BrainEvent>)

    private val shots = ArrayList<Shot>()
    private val marks = ArrayList<Mark>()
    private var cursor = 0L
    private var work: BrainEvent.Work? = null
    private var recording: MutableList<BrainEvent>? = null

    // Le dernier échange : ce qu'il a cherché pour répondre, puis la prise de notes qui a suivi.
    private var exchange = ArrayList<Take>()
    private var replaying = false
    private var replayUntil = Long.MIN_VALUE
    private var speed = 1.0
    private val _involved = LinkedHashSet<String>()

    private val _revision = MutableStateFlow(0)

    /** Change à chaque nouveau geste : l'écran se remet à dessiner image par image. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Les notes du travail en cours, ou du dernier, dans l'ordre où il les a touchées. */
    val involved: Set<String> get() = _involved

    /** Un travail d'Axo est en cours. */
    val working: Boolean get() = work != null

    /** Axo prépare sa réponse, ou l'écrit : les notes de [involved] sont celles qui servent à répondre. */
    val answering: Boolean get() = work == BrainEvent.Work.ANSWER

    /** Le dernier échange peut être remontré (pas pendant qu'Axo travaille). */
    val canReplay: Boolean get() = exchange.isNotEmpty() && work == null

    fun push(event: BrainEvent, now: Long) {
        // Axo se remet au travail pendant un « Rejouer » : le direct passe avant.
        if (now < replayUntil) clear(now)
        play(event, now)
        _revision.value++
    }

    /** Remontre le dernier échange (la recherche, la réponse, puis les notes), deux fois plus lentement. */
    fun replay(now: Long) {
        if (!canReplay) return
        val takes = exchange.toList()
        clear(now)
        replaying = true
        speed = REPLAY_SPEED
        takes.forEachIndexed { i, take ->
            if (i > 0) cursor += d(BEAT)
            play(BrainEvent.Begin(take.work), now)
            // « Axo pense à ton message… », « Axo range ses notes… » : le temps de le lire.
            cursor = max(cursor, now) + d(LEAD)
            take.events.forEach { play(it, now) }
            if (take.work == BrainEvent.Work.ANSWER) {
                // La réponse elle-même : un temps, le temps de lire « Axo te répond… ».
                play(BrainEvent.Answering, now)
                cursor += d(BEAT)
            }
            play(BrainEvent.End(take.work), now)
        }
        speed = 1.0
        replaying = false
        replayUntil = cursor + TRAIL_FADE
        _revision.value++
    }

    private fun clear(now: Long) {
        shots.clear()
        marks.clear()
        work = null
        recording = null
        replayUntil = Long.MIN_VALUE
        cursor = now
    }

    private fun d(ms: Long) = (ms / speed).toLong()

    private fun mark(at: Long, caption: Caption?, focus: String?, end: Boolean = false) {
        marks += Mark(at, caption, focus, end)
    }

    // La note suivie jusqu'ici : la caméra y reste pendant la réponse, et le temps que tout s'éteigne.
    private fun lastFocus(): String? = marks.lastOrNull()?.focus

    private fun play(event: BrainEvent, now: Long) {
        prune(now)
        val at = max(now, cursor)
        when (event) {
            is BrainEvent.Begin -> {
                if (work != null) close(at)
                work = event.work
                if (!replaying) {
                    recording = ArrayList()
                    // Une nouvelle réponse ouvre un nouvel échange : « Rejouer » montrera celui-ci.
                    if (event.work == BrainEvent.Work.ANSWER) exchange = ArrayList()
                }
                _involved.clear()
                mark(at, Caption(if (event.work == BrainEvent.Work.ANSWER) Caption.Kind.THINKING else Caption.Kind.NOTING), null)
            }
            BrainEvent.Answering -> if (work == BrainEvent.Work.ANSWER) {
                shots.filter { it.kind == Kind.PULSE && it.until == Long.MAX_VALUE }.forEach { it.until = at }
                mark(at, Caption(Caption.Kind.ANSWERING), lastFocus())
            }
            is BrainEvent.End -> if (work == event.work) close(at)
            is BrainEvent.Searched -> {
                recording?.add(event)
                // La caméra va vers la meilleure trouvaille plutôt que de sauter d'une note à l'autre.
                val best = event.kept.firstOrNull() ?: event.found.firstOrNull()
                event.found.forEachIndexed { i, id ->
                    shots += Shot(Kind.FLASH, id, null, at + d(STAGGER * i), d(FLASH))
                    mark(at + d(STAGGER * i), Caption(Caption.Kind.SEARCHING), best)
                }
                _involved += event.found
                val settled = at + d(STAGGER * event.found.size + SETTLE)
                event.kept.forEach { light(it, settled) }
                cursor = settled
            }
            is BrainEvent.Read -> {
                recording?.add(event)
                _involved += event.noteId
                light(event.noteId, at)
                val pulse = Shot(Kind.PULSE, event.noteId, null, at)
                // Pendant la prise de notes, il compare puis passe à la suite : la lecture ne dure pas.
                if (work == BrainEvent.Work.NOTES) pulse.until = at + d(READ_WHILE_NOTING)
                shots += pulse
                mark(at, Caption(Caption.Kind.READING, event.noteId), event.noteId)
                cursor = at + d(READ)
            }
            is BrainEvent.Followed -> {
                recording?.add(event)
                _involved += event.from
                _involved += event.to
                shots += Shot(Kind.BALL, event.from, event.to, at, d(BALL))
                shots += Shot(Kind.TRAIL, event.from, event.to, at + d(BALL))
                mark(at, Caption(Caption.Kind.FOLLOWING, event.to), event.to)
                cursor = at + d(BALL)
            }
            is BrainEvent.Wrote -> {
                recording?.add(event)
                _involved += event.noteId
                if (event.created) shots += Shot(Kind.APPEAR, event.noteId, null, at, d(APPEAR))
                shots += Shot(Kind.GROW, event.noteId, null, at, d(GROW))
                light(event.noteId, at)
                mark(at, Caption(if (event.created) Caption.Kind.CREATING else Caption.Kind.WRITING, event.noteId), event.noteId)
                var end = at + d(GROW)
                if (event.links.isNotEmpty()) {
                    // Les liens nouveaux se tracent pendant que la note finit de gonfler.
                    val from = at + d(GROW) / 2
                    event.links.forEach { shots += Shot(Kind.DRAW, event.noteId, it, from, d(DRAW)) }
                    end = max(end, from + d(DRAW))
                }
                cursor = end
            }
            is BrainEvent.Woke -> {
                recording?.add(event)
                _involved += event.noteId
                shots += Shot(Kind.WAKE, event.noteId, null, at, d(WAKE))
                mark(at, Caption(Caption.Kind.WAKING, event.noteId), event.noteId)
                cursor = at + d(WAKE_STEP)
            }
        }
    }

    // Une note qui reste éclairée jusqu'à la fin du travail.
    private fun light(id: String, at: Long) {
        if (shots.none { it.kind == Kind.LIT && it.note == id && it.until == Long.MAX_VALUE }) shots += Shot(Kind.LIT, id, null, at)
    }

    /** Fin du travail à [at] : les notes éclairées s'éteignent doucement, les liens empruntés plus lentement. */
    private fun close(at: Long) {
        for (s in shots) {
            if (s.until == Long.MAX_VALUE && (s.kind == Kind.LIT || s.kind == Kind.PULSE || s.kind == Kind.TRAIL)) s.until = at
        }
        mark(at, null, lastFocus(), end = true)
        val done = work
        work = null
        val events = recording
        recording = null
        if (done != null && !events.isNullOrEmpty()) {
            exchange += Take(done, events.toList())
            // Une longue reprise de notes en retard ne se rejoue pas en entier.
            if (exchange.size > MAX_TAKES) exchange.removeAt(0)
        }
    }

    // Ce qui est fini depuis longtemps ne sert plus.
    private fun prune(now: Long) {
        shots.removeAll { end(it) < now - 1_000 }
        val current = marks.indexOfLast { it.at <= now }
        if (current > 0) marks.subList(0, current).clear()
    }

    private fun end(s: Shot): Long = when (s.kind) {
        Kind.LIT -> if (s.until == Long.MAX_VALUE) Long.MAX_VALUE else s.until + LIT_FADE
        Kind.TRAIL -> if (s.until == Long.MAX_VALUE) Long.MAX_VALUE else s.until + TRAIL_FADE
        Kind.PULSE -> s.until
        else -> s.start + s.length
    }

    private fun moving(s: Shot, now: Long): Boolean = when (s.kind) {
        Kind.LIT -> now < s.start + LIT_IN || (s.until != Long.MAX_VALUE && now < s.until + LIT_FADE)
        Kind.TRAIL -> s.until != Long.MAX_VALUE && now < s.until + TRAIL_FADE
        Kind.PULSE -> now < s.until
        else -> now < s.start + s.length
    }

    /** Quelque chose bouge encore à [now] : sinon l'image est fixe, inutile de la redessiner. */
    fun busy(now: Long): Boolean = shots.any { moving(it, now) } || marks.any { it.at > now }

    fun frame(now: Long): LiveFrame {
        if (shots.isEmpty() && marks.isEmpty()) return LiveFrame.EMPTY
        val light = HashMap<String, Float>()
        val pulse = HashMap<String, Float>()
        val balls = ArrayList<Moving>()
        val trail = HashMap<Pair<String, String>, Float>()
        val grow = HashMap<String, Float>()
        val appear = HashMap<String, Float>()
        val strokes = ArrayList<Moving>()
        val wake = HashMap<String, Float>()
        fun more(map: HashMap<String, Float>, key: String, v: Float) {
            if (v > (map[key] ?: 0f)) map[key] = v
        }
        for (s in shots) {
            val dt = now - s.start
            when (s.kind) {
                Kind.FLASH -> if (dt >= 0 && dt < s.length) {
                    val rise = s.length / 6
                    more(light, s.note, if (dt < rise) dt.toFloat() / rise else 1f - (dt - rise).toFloat() / (s.length - rise))
                }
                Kind.LIT -> if (dt >= 0) {
                    val fadeIn = (dt.toFloat() / LIT_IN).coerceAtMost(1f)
                    val fadeOut = if (now <= s.until) 1f else 1f - (now - s.until).toFloat() / LIT_FADE
                    if (fadeOut > 0f) more(light, s.note, minOf(fadeIn, fadeOut) * LIT_LEVEL)
                }
                Kind.PULSE -> if (dt >= 0 && now < s.until) {
                    more(pulse, s.note, (0.5 - 0.5 * cos(2 * PI * dt / PULSE_PERIOD)).toFloat())
                }
                Kind.BALL -> if (dt >= 0 && dt < s.length) balls += Moving(s.note, s.other!!, ease(dt.toFloat() / s.length))
                Kind.TRAIL -> if (dt >= 0) {
                    val v = if (now <= s.until) 1f else 1f - (now - s.until).toFloat() / TRAIL_FADE
                    val key = edge(s.note, s.other!!)
                    if (v > (trail[key] ?: 0f)) trail[key] = v
                }
                Kind.GROW -> if (dt >= 0 && dt < s.length) more(grow, s.note, sin(PI * dt / s.length).toFloat())
                Kind.APPEAR -> if (dt < s.length) appear[s.note] = if (dt < 0) 0f else dt.toFloat() / s.length
                Kind.DRAW -> if (dt < s.length) strokes += Moving(s.note, s.other!!, if (dt < 0) 0f else ease(dt.toFloat() / s.length))
                Kind.WAKE -> if (dt < s.length) wake[s.note] = if (dt < 0) 0f else ease(dt.toFloat() / s.length)
            }
        }
        trail.entries.removeAll { it.value <= 0f }
        return LiveFrame(light, pulse, balls, trail, grow, appear, strokes, wake, focus(now), caption(now))
    }

    /** La note où Axo travaille à [now] : la caméra peut la suivre. */
    fun focus(now: Long): String? {
        val mark = marks.lastOrNull { it.at <= now } ?: return null
        return mark.focus?.takeIf { !mark.end || now < mark.at + LIT_FADE }
    }

    /** Ce qu'il fait à [now], en une ligne. */
    fun caption(now: Long): Caption? = marks.lastOrNull { it.at <= now }?.caption

    companion object {
        const val STAGGER = 110L
        const val FLASH = 520L
        const val SETTLE = 260L
        const val READ = 320L
        const val READ_WHILE_NOTING = 1_400L
        const val BALL = 420L
        const val GROW = 700L
        const val APPEAR = 500L
        const val DRAW = 560L
        const val WAKE = 800L
        const val WAKE_STEP = 400L
        const val LIT_IN = 200L
        const val LIT_FADE = 1_500L
        const val TRAIL_FADE = 6_000L
        const val PULSE_PERIOD = 1_100L
        const val LIT_LEVEL = 0.85f

        /** « Rejouer » : deux fois plus lent, avec un temps au début de chaque travail et entre eux. */
        const val REPLAY_SPEED = 0.5
        const val LEAD = 300L
        const val BEAT = 800L
        const val MAX_TAKES = 3

        /** L'horloge de l'écran (celle des images), en millisecondes. */
        fun now(): Long = System.nanoTime() / 1_000_000

        /** Un lien, quel que soit son sens (comme dans le graphe). */
        fun edge(a: String, b: String): Pair<String, String> = if (a < b) a to b else b to a

        private fun ease(p: Float): Float = p * p * (3 - 2 * p)
    }
}
