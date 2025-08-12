package com.example.llmchat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmchat.net.LlmClient
import com.example.llmchat.net.LlmSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatMessage(val role: Role, val content: String) {
    enum class Role { User, Assistant, System }
}

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

    // === Детекторы признаков ===
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
Когда будет достаточно информации, я отправлю итоговый список «Исполнитель — Название песни».
""".trimIndent()

    private fun initialMessages(settings: LlmSettings): List<ChatMessage> = listOf(
        ChatMessage(ChatMessage.Role.Assistant, greetingText())
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

        // Просто обновляем приветствие, без System Prompt в чате
        val cur = _state.value.messages.toMutableList()
        if (cur.isEmpty() || cur.first().role != ChatMessage.Role.Assistant) {
            cur.clear()
            cur.add(ChatMessage(ChatMessage.Role.Assistant, greetingText()))
        } else {
            cur[0] = ChatMessage(ChatMessage.Role.Assistant, greetingText())
        }
        _state.value = _state.value.copy(messages = cur)
    }

    fun onInputChange(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    fun newChat() {
        _state.value = _state.value.copy(
            messages = initialMessages(_state.value.settings),
            input = "",
            sending = false,
            error = null
        )
    }

    // --- Детекторы сигналов из последних сообщений пользователя ---
    private fun detectSignalsFrom(text: String): Set<Signal> {
        val t = text.lowercase()
        var hasGenre = GENRE_WORDS.any { t.contains(it) }
        var hasMood = MOOD_WORDS.any { t.contains(it) }
        var hasTempo = TEMPO_WORDS.any { t.contains(it) }
        var hasLang = LANGUAGE_WORDS.any { t.contains(it) }

        // Примитивная эвристика «имя артиста»
        val ARTIST_REGEX = Regex("""\b([A-ZА-Я][\p{L}\d'.-]+(?:\s+[A-ZА-Я][\p{L}\d'.-]+){0,2})\b""")
        val commonStarts = setOf("Привет","Здравствуйте","Hey","Hello","Hi")
        val artistCandidates = ARTIST_REGEX.findAll(text)
            .map { it.value.trim() }
            .filter { it.length in 2..40 }
            .filterNot { commonStarts.contains(it) }
            .take(3)
            .toList()
        val hasArtist = artistCandidates.isNotEmpty()

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

    // --- Публичная отправка: добавляет твоё сообщение, ждёт ответ модели ---
    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.sending) return

        val newList = _state.value.messages + ChatMessage(ChatMessage.Role.User, text)
        _state.value = _state.value.copy(messages = newList, input = "", sending = true, error = null)

        viewModelScope.launch {
            try {
                val reply = completeNow()
                _state.value = _state.value.copy(
                    messages = _state.value.messages + ChatMessage(ChatMessage.Role.Assistant, reply),
                    sending = false
                )

                // 1) Достаточно сигналов — финализация без показа служебного промпта
                if (shouldFinalizePlaylist()) {
                    triggerFinalize()
                    return@launch
                }

                // 2) Если модель уже задала вопрос — не дублируем наш
                if (looksLikeQuestion(reply)) return@launch

                // 3) Иначе — добавляем один уточняющий вопрос
                val followUp = buildFollowUpQuestion()
                _state.value = _state.value.copy(
                    messages = _state.value.messages + ChatMessage(ChatMessage.Role.Assistant, followUp)
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(sending = false, error = t.message ?: "Unknown error")
            }
        }
    }

    // --- Скрытая отправка дополнительного запроса (не виден в ленте) ---
    private fun sendInternal(extraUserInput: String? = null) {
        if (_state.value.sending) return
        _state.value = _state.value.copy(sending = true, error = null)

        viewModelScope.launch {
            try {
                val reply = completeNow(overrideInput = extraUserInput)
                _state.value = _state.value.copy(
                    messages = _state.value.messages + ChatMessage(ChatMessage.Role.Assistant, reply),
                    sending = false
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(sending = false, error = t.message ?: "Unknown error")
            }
        }
    }

    private suspend fun completeNow(overrideInput: String? = null): String {
        val systemPrompt = _state.value.settings.systemPrompt.ifBlank { defaultSystemPrompt() }

        val chatPairs = _state.value.messages.filter {
            it.role == ChatMessage.Role.User || it.role == ChatMessage.Role.Assistant
        }.map { (role, text) ->
            val r = if (role == ChatMessage.Role.User) "user" else "assistant"
            r to text
        }

        return client.complete(
            // Передаём только user/assistant для истории
            messages = chatPairs,
            overrideInput = overrideInput,
            systemPrompt = systemPrompt // Новый параметр
        )
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

        sendInternal(extraUserInput = finalizePrompt) // скрытая отправка без отображения промпта в чате
    }
}
