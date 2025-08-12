package com.example.llmchat.net

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

data class LlmSettings(
    val provider: Provider = Provider.OpenAI,
    val baseUrl: String = "https://api.openai.com",
    val apiKey: String = "",
    val model: String = "gpt-4.1-mini",
    /** Включать ли строгий JSON-формат через text.format=json_schema */
    val forceJsonSchema: Boolean = true
) {
    enum class Provider { OpenAI, OpenRouter, Custom }
}

class LlmClient(private var settings: LlmSettings) {

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            // Для локальной отладки можно поставить BODY; на проде лучше BASIC/HEADERS
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder().addLast(
                        KotlinJsonAdapterFactory()
                    ).build()
                )
            )
            .build()
    }

    private var retrofit: Retrofit = buildRetrofit(settings.baseUrl)
    private var api: ResponsesApi = retrofit.create(ResponsesApi::class.java)

    fun updateSettings(newSettings: LlmSettings) {
        settings = newSettings
        retrofit = buildRetrofit(settings.baseUrl)
        api = retrofit.create(ResponsesApi::class.java)
    }

    /**
     * messages: список пар (role, content).
     * "system" → instructions (берём последний system),
     * "user"/"assistant" → в input как части с type="input_text".
     */
    suspend fun complete(messages: List<Pair<String, String>>): String = withContext(Dispatchers.IO) {
        val instructions = messages.lastOrNull { it.first == "system" }?.second

        val chatInputList = messages
            .filter { it.first == "user" || it.first == "assistant" }
            .map { (role, text) -> SimpleMessage(role = role, content = text) }

        // Если хотим отправлять только последнюю пользовательскую реплику как строку,
        // можно заменить на: val input: Any = chatInputList.lastOrNull()?.content ?: ""
        val input: Any = if (chatInputList.size == 1) {
            // один элемент — отправим просто строкой (минимальный запрос)
            chatInputList.first().content
        } else {
            // несколько — отправляем как список сообщений
            chatInputList
        }

        // Настройка формата вывода:
        // - строгая схема (json_schema) для объекта { title, description }
        // - либо "json" для любого валидного JSON
        val textOptions = if (settings.forceJsonSchema) {
            val schema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "title" to mapOf("type" to "string"),
                    "description" to mapOf("type" to "string")
                ),
                "required" to listOf("title", "description"),
                "additionalProperties" to false
            )
            TextOptions(
                format = TextFormat(
                    type = "json_schema",
                    name = "answer_object",
                    strict = true,
                    schema = schema
                )
            )
        } else {
            TextOptions(format = TextFormat(type = "json"))
        }

        val body = ResponsesRequest(
            model = settings.model,
            instructions = instructions ?: "Отвечай строго JSON-объектом {\"title\":\"…\",\"description\":\"…\"} без текста вне JSON.",
            input = input,
            temperature = 0.7,
            maxOutputTokens = 512,
            text = textOptions
        )


        try {
            val resp = api.createResponse("Bearer ${settings.apiKey}", body)
            // ...
            resp.outputText ?: run {
                // 2) Фоллбэк: собрать все кусочки output_text
                val parts = resp.output.orEmpty()
                    .flatMap { it.content.orEmpty() }
                    .filter { it.type == "output_text" }
                    .mapNotNull { it.text }
                parts.joinToString("\n").ifBlank { null }
            } ?: error("Empty response")
        } catch (e: retrofit2.HttpException) {
            val code = e.code() // 400
            val raw = e.response()?.errorBody()?.string()
            // Обычно провайдер вернёт JSON вида:
            // {"error":{"message":"...","type":"...","code":"..."}}
            Log.e("LLM", "HTTP $code: $raw")
            ""
        }
    }
}
