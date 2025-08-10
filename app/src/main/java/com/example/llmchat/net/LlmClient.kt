package com.example.llmchat.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

data class LlmSettings(
    val provider: Provider = Provider.OpenAI,
    val baseUrl: String = "https://api.openai.com",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini"
) {
    enum class Provider { OpenAI, OpenRouter, Custom }
}

class LlmClient(private val settings: LlmSettings) {

    private val retrofit by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(settings.baseUrl.trimEnd('/') + "/")
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

    private val api by lazy { retrofit.create(ChatApi::class.java) }

    suspend fun complete(messages: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            val body = ChatCompletionRequest(
                model = settings.model,
                messages = messages.map { (role, content) -> ChatMessageDTO(role, content) }
            )
            val resp = api.chatCompletions(
                auth = "Bearer ${settings.apiKey}",
                body = body
            )
            val msg = resp.choices.firstOrNull()?.message?.content
            msg ?: error("Empty response")
        }
}
