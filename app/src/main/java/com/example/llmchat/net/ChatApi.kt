package com.example.llmchat.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

// --- OpenAI-compatible /v1/chat/completions ---

interface ChatApi {
    @Headers("Content-Type: application/json")
    @POST("/v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") auth: String,
        @Body body: ChatCompletionRequest
    ): ChatCompletionResponse
}

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDTO>,
    val temperature: Double? = 0.7,
    @Json(name = "max_tokens") val maxTokens: Int? = 512
)

@JsonClass(generateAdapter = true)
data class ChatMessageDTO(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class ChatCompletionResponse(
    val id: String?,
    val object_: String? = null,
    val created: Long?,
    val model: String?,
    val choices: List<Choice>,
    val usage: Usage? = null
) {
    @JsonClass(generateAdapter = true)
    data class Choice(
        val index: Int,
        val message: ChatMessageDTO?,
        @Json(name = "finish_reason") val finishReason: String?
    )
    @JsonClass(generateAdapter = true)
    data class Usage(
        @Json(name = "prompt_tokens") val promptTokens: Int?,
        @Json(name = "completion_tokens") val completionTokens: Int?,
        @Json(name = "total_tokens") val totalTokens: Int?
    )
}
