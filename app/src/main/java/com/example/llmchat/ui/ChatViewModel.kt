package com.example.llmchat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmchat.net.LlmClient
import com.example.llmchat.net.LlmSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val role: Role,
    val content: String,
    val agentType: AgentType? = null
) {
    enum class Role { User, Assistant, System }
}
enum class AgentType { MUSIC, MOVIE }

data class UiState(
    val settings: LlmSettings,
    val messages: List<ChatMessage>,
    val input: String = "",
    val sending: Boolean = false,
    val error: String? = null
)

class ChatViewModel(
    initialSettings: LlmSettings
) : ViewModel() {

    private var client = LlmClient(initialSettings)

    // ===== Диалоговые фазы и управление вопросами =====
    private enum class Phase { GATHERING, FEEDBACK_PENDING, MOVIE_DONE }
    private var phase: Phase = Phase.GATHERING

    /** Разрешён ли следующий уточняющий вопрос (true ставим только ПОСЛЕ ответа пользователя). */
    private var followUpPending: Boolean = false

    // Небольшая история 2-го агента
    private val agent2History = mutableListOf<Pair<String, String>>() // role -> text

    // ===== Детектирование признаков =====
    private enum class Signal { GENRE, ARTIST, MOOD, LANGUAGE, TEMPO }

    private val GENRE_WORDS = setOf(
        "pop","поп","indie","инди","rock","рок","metal","метал","punk","панк","jazz","джаз",
        "blues","блюз","r&b","soul","соул","hip-hop","hiphop","хип-хоп","рэп","rap",
        "edm","electronic","электронная","house","хаус","deep house","progressive house",
        "techno","техно","trance","транс","d&b","drum and bass","драм-н-бейс",
        "ambient","эмбиент","lofi","лоуфай","synthwave","синтвейв",
        "folk","фолк","country","кантри","classical","классическая",
        "k-pop","kpop","кей-поп","j-pop","jpop","джей-поп","reggaeton","реггетон","latin","латино"
    ).map { it.lowercase() }.toSet()

    private val MOOD_WORDS = setOf(
        "меланхолия","меланхоличный","грустный","душевный","атмосферный","темный","тёмный",
        "мрачный","dreamy","мечтательный","веселый","весёлый","энергичный","спокойный",
        "uplifting","chill","chillout","романтичный","танцевальный","медитативный"
    ).map { it.lowercase() }.toSet()

    private val TEMPO_WORDS = setOf(
        "медленный","спокойный","умеренный","средний","быстрый","энергичный",
        "uptempo","downtempo","midtempo","120bpm","128bpm","140bpm","slow","fast"
    ).map { it.lowercase() }.toSet()

    private val LANGUAGE_WORDS = setOf(
        "русский","на русском","english","английский","на английском","spanish","испанский",
        "korean","корейский","japanese","японский","french","французский","german","немецкий",
        "italian","итальянский","portuguese","португальский"
    ).map { it.lowercase() }.toSet()

    // ===== Промпты и приветствие =====
    private fun defaultSystemPrompt() = """
Ты — музыкальный редактор и эксперт по подбору треков.
Наша цель — составить для пользователя идеальный плейлист из 10 песен.

Правила общения:
1) Сначала уточняй вкусы пользователя (жанры, артисты, настроение, язык, темп). После каждого его ответа задавай один уточняющий вопрос, пока данных недостаточно.
2) Когда информации достаточно, выдай финальный ответ строго в формате НУМЕРОВАННОГО списка из 10 строк:
   1. Исполнитель — Название песни
   2. Исполнитель — Название песни
   ...
   10. Исполнитель — Название песни
3) Не добавляй НИЧЕГО, кроме этого списка: никаких вступлений, пояснений, эмодзи и т.п.
""".trimIndent()

    private fun greetingText() = """
Привет! Составлю для тебя идеальный плейлист из 10 треков.
Расскажи, пожалуйста, какие жанры/настроения тебе ближе, любимых артистов, язык вокала и темп (спокойный/энергичный).
Когда будет достаточно информации, я отправлю итоговый НУМЕРОВАННЫЙ список «Исполнитель — Название песни».
""".trimIndent()

    private fun agent2SystemPrompt() = """
Ты — кинокуратор. Работаешь на русском.
Тебе дают: (1) финальный музыкальный плейлист пользователя (нумерованный список из 10 строк «Исполнитель — Песня»),
(2) реакцию пользователя на плейлист и/или ответы про настроение.

Алгоритм:
- Определи, понравился плейлист (да/нет) и настроение пользователя.
- Если понравился: СРАЗУ предложи 5 фильмов, соответствующих настроению/вайбу плейлиста.
- Если НЕ понравился: задай максимум 1 короткий уточняющий вопрос о желаемом настроении/жанрах/темпе, затем предложи 5 фильмов.

Формат рекомендаций строго:
- Одна краткая строка резюме настроения.
- Затем НУМЕРОВАННЫЙ список из 5 строк:
  1. Название (Год) — Режиссёр
  2. ...
  5. ...
Никаких лишних блоков до/после списка. Вопросы — отдельным коротким сообщением.
""".trimIndent()

    private fun initialMessages(settings: LlmSettings): List<ChatMessage> = listOf(
        // System скрыт из ленты — только приветствие от музыкального агента (с бейджем)
        ChatMessage(ChatMessage.Role.Assistant, greetingText(), agentType = AgentType.MUSIC)
    )

    private val _state = MutableStateFlow(
        UiState(
            settings = initialSettings,
            messages = initialMessages(initialSettings)
        )
    )
    val state: StateFlow<UiState> = _state

    fun updateSettings(newSettings: LlmSettings) {
        _state.value = _state.value.copy(settings = newSettings)
        client.updateSettings(newSettings)

        // Перезаписываем приветствие (System не показываем)
        val cur = mutableListOf<ChatMessage>()
        cur.add(ChatMessage(ChatMessage.Role.Assistant, greetingText(), agentType = AgentType.MUSIC))
        _state.value = _state.value.copy(messages = cur)
    }

    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    fun newChat() {
        phase = Phase.GATHERING
        followUpPending = false
        agent2History.clear()

        _state.value = _state.value.copy(
            messages = initialMessages(_state.value.settings),
            input = "",
            sending = false,
            error = null
        )
    }

    // ===== Детекция признаков и вопросы =====
    private fun detectSignalsFrom(text: String): Set<Signal> {
        val t = text.lowercase()
        val hasGenre = GENRE_WORDS.any { t.contains(it) }
        val hasMood = MOOD_WORDS.any { t.contains(it) }
        val hasTempo = TEMPO_WORDS.any { t.contains(it) }
        val hasLang = LANGUAGE_WORDS.any { t.contains(it) }

        val ARTIST_REGEX = Regex("""\b([A-ZА-Я][\p{L}\d'.-]+(?:\s+[A-ZА-Я][\p{L}\d'.-]+){0,2})\b""")
        val commonStarts = setOf("Привет","Здравствуйте","Hey","Hello","Hi")
        val hasArtist = ARTIST_REGEX.findAll(text)
            .map { it.value.trim() }
            .any { it.length in 2..40 && it !in commonStarts }

        val out = mutableSetOf<Signal>()
        if (hasGenre) out += Signal.GENRE
        if (hasMood) out += Signal.MOOD
        if (hasTempo) out += Signal.TEMPO
        if (hasLang) out += Signal.LANGUAGE
        if (hasArtist) out += Signal.ARTIST
        return out
    }

    private fun collectSignalsFromRecentUserMessages(k: Int = 6): Set<Signal> {
        val msgs = _state.value.messages.asReversed().filter { it.role == ChatMessage.Role.User }.take(k)
        val union = mutableSetOf<Signal>()
        for (m in msgs) union += detectSignalsFrom(m.content)
        return union
    }

    private fun missingSignals(): Set<Signal> {
        val s = collectSignalsFromRecentUserMessages(6)
        val all = setOf(Signal.GENRE, Signal.ARTIST, Signal.MOOD, Signal.LANGUAGE, Signal.TEMPO)
        return all - s
    }

    private fun buildFollowUpQuestion(): String {
        val miss = missingSignals()
        return when {
            Signal.GENRE in miss -> "Какие жанры или стили тебе ближе? Можешь привести 2–3 примера."
            Signal.ARTIST in miss -> "Есть ли любимые артисты/группы, чьё звучание хочется поймать?"
            Signal.MOOD in miss -> "Какое настроение плейлиста ты хочешь: спокойное, меланхоличное, энергичное?"
            Signal.LANGUAGE in miss -> "Есть предпочтения по языку вокала: русский, английский или смешанный?"
            Signal.TEMPO in miss -> "Какой темп предпочтительнее: медленный, умеренный или быстрый?"
            else -> "Есть ли ещё пожелания по звучанию или атмосфере плейлиста?"
        }
    }

    private fun shouldFinalizePlaylist(): Boolean {
        val signals = collectSignalsFromRecentUserMessages(6)
        return signals.size >= 3
    }

    private fun buildSummaryFromSignals(): String {
        val msgs = _state.value.messages.filter { it.role == ChatMessage.Role.User }.takeLast(6)
        val allText = msgs.joinToString(" \n ") { it.content }
        val s = collectSignalsFromRecentUserMessages(6)
        val parts = mutableListOf<String>()
        if (Signal.GENRE in s) parts += "жанры указаны"
        if (Signal.ARTIST in s) parts += "артисты указаны"
        if (Signal.MOOD in s) parts += "настроение указано"
        if (Signal.LANGUAGE in s) parts += "язык указан"
        if (Signal.TEMPO in s) parts += "темп указан"

        return """
Данные пользователя: ${parts.joinToString(", ")}.
Ответы для анализа:
$allText
""".trimIndent()
    }

    private fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("?")) return true
        val qMarks = listOf("что","какие","какой","какая","каких","кто","куда","нужно ли",
            "would you","could you","which","what","who","how","when","where","why","do you")
        return qMarks.any { t.lowercase().contains("$it ") }
    }

    private fun containsFiveNumberedLines(text: String): Boolean {
        val lines = text.trim().lines().map { it.trim() }
        val numbered = lines.count { it.matches(Regex("""^\d+\.\s+.+""")) }
        return numbered >= 5
    }

    // ===== Новый универсальный детектор плейлиста и запуск киноагента =====
    private fun isTenTrackNumberedPlaylist(text: String): Boolean {
        val lines = text.trim().lines().map { it.trim() }
        val numbered = lines.count { it.matches(Regex("""^\d+\.\s+.+""")) }
        return numbered >= 10
    }

    /** Единая точка старта киноагента — вызываем после любого ответа музыкального агента */
    private fun maybeStartMovieAgent(latestMusicReply: String) {
        if (phase != Phase.GATHERING) return
        if (!isTenTrackNumberedPlaylist(latestMusicReply)) return

        phase = Phase.FEEDBACK_PENDING
        val ask = "Понравился ли тебе этот плейлист? Если да — в двух словах опиши настроение; если нет — расскажи, какого настроения хочется."
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                ChatMessage.Role.Assistant,
                ask,
                agentType = AgentType.MOVIE
            )
        )
        agent2History.clear()
        agent2History += "assistant" to ask
        // Один вопрос от 2-го агента задан — ждём ответ пользователя
        followUpPending = true
    }

    // ===== Публичная отправка =====
    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return

        // Пользователь ответил → можно задать следующий follow-up при необходимости
        followUpPending = false

        val newList = _state.value.messages + ChatMessage(ChatMessage.Role.User, text)
        _state.value = _state.value.copy(messages = newList, input = "", sending = true, error = null)

        viewModelScope.launch {
            try {
                // Ветка киноагента
                if (phase == Phase.FEEDBACK_PENDING && _state.value.messages.isNotEmpty()) {
                    val playlistText = _state.value.messages
                        .asReversed()
                        .firstOrNull {
                            it.role == ChatMessage.Role.Assistant &&
                                    it.agentType == AgentType.MUSIC &&
                                    isTenTrackNumberedPlaylist(it.content)
                        }
                        ?.content ?: ""

                    val agent2Reply = runAgent2(playlistText, userUtterance = text).trim()
                    if (agent2Reply.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + ChatMessage(
                                ChatMessage.Role.Assistant,
                                agent2Reply,
                                agentType = AgentType.MOVIE
                            ),
                            sending = false
                        )
                    } else {
                        _state.value = _state.value.copy(sending = false)
                    }

                    if (containsFiveNumberedLines(agent2Reply)) {
                        phase = Phase.MOVIE_DONE
                        followUpPending = false
                    } else {
                        // Задан вопрос/уточнение 2-м агентом → ждём ответ пользователя
                        followUpPending = true
                    }
                    return@launch
                }

                // Обычный музыкальный диалог
                val reply = completeNow().trim()
                if (reply.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + ChatMessage(
                            ChatMessage.Role.Assistant,
                            reply,
                            agentType = AgentType.MUSIC
                        ),
                        sending = false
                    )
                } else {
                    _state.value = _state.value.copy(sending = false)
                }

                // Если модель уже выдала плейлист — сразу запустить киноагента
                maybeStartMovieAgent(reply)
                if (phase == Phase.FEEDBACK_PENDING) return@launch

                // Хвостовая логика: либо финализация, либо один follow-up
                if (shouldFinalizePlaylist()) {
                    triggerFinalize()
                    return@launch
                }

                // Если модель уже задала вопрос — ждём ответ, свой follow-up не добавляем
                if (looksLikeQuestion(reply)) {
                    followUpPending = true
                    return@launch
                }

                // Если follow-up ещё не задан — один уточняющий вопрос
                if (!followUpPending) {
                    val followUp = buildFollowUpQuestion().trim()
                    if (followUp.isNotEmpty()) {
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + ChatMessage(
                                ChatMessage.Role.Assistant,
                                followUp,
                                agentType = AgentType.MUSIC
                            )
                        )
                        followUpPending = true
                    }
                }

            } catch (t: Throwable) {
                _state.value = _state.value.copy(sending = false, error = t.message ?: "Unknown error")
            }
        }
    }

    // ===== Скрытая отправка (служебные промпты не показываем) =====
    private fun sendInternal(extraUserInput: String? = null) {
        if (_state.value.sending) return
        _state.value = _state.value.copy(sending = true, error = null)

        viewModelScope.launch {
            try {
                val reply = completeNow(overrideInput = extraUserInput).trim()
                if (reply.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + ChatMessage(
                            ChatMessage.Role.Assistant,
                            reply,
                            agentType = if (phase == Phase.GATHERING) AgentType.MUSIC else AgentType.MOVIE
                        ),
                        sending = false
                    )
                } else {
                    _state.value = _state.value.copy(sending = false)
                }

                // После скрытого финального вызова тоже проверяем и запускаем киноагента
                maybeStartMovieAgent(reply)

            } catch (t: Throwable) {
                _state.value = _state.value.copy(sending = false, error = t.message ?: "Unknown error")
            }
        }
    }

    private suspend fun completeNow(overrideInput: String? = null): String {
        val systemPrompt = _state.value.settings.systemPrompt.ifBlank { defaultSystemPrompt() }
        val base = _state.value.messages
            .filter { it.role == ChatMessage.Role.User || it.role == ChatMessage.Role.Assistant }
            .map {
                val r = if (it.role == ChatMessage.Role.User) "user" else "assistant"
                r to it.content
            }
        return client.complete(base, overrideInput, systemPrompt)
    }

    private fun triggerFinalize() {
        val summary = buildSummaryFromSignals()
        val finalizePrompt = """
Сформируй плейлист из 10 песен на основе предпочтений ниже.
Ответ ДОЛЖЕН быть только в виде нумерованного списка от 1 до 10, где каждая строка — пара:
"<номер>. <Исполнитель> — <Название песни>".
Не добавляй ничего, кроме этого списка.

$summary
""".trimIndent()

        sendInternal(extraUserInput = finalizePrompt)
    }

    private suspend fun runAgent2(playlistText: String, userUtterance: String): String {
        agent2History += "user" to userUtterance

        val tail = agent2History.takeLast(6).joinToString("\n") { (role, txt) ->
            val r = if (role == "user") "Пользователь" else "Куратор"
            "$r: $txt"
        }

        val hiddenInput = """
Это плейлист пользователя:
$playlistText

Диалог:
$tail
""".trimIndent()

        val out = client.complete(
            messages = emptyList(),
            overrideInput = hiddenInput,
            systemPrompt = agent2SystemPrompt()
        ).trim()

        if (out.isNotEmpty()) {
            if (!containsFiveNumberedLines(out)) {
                agent2History += "assistant" to out
            }
        }
        return out
    }
}
