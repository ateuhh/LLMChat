package com.example.llmchat.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface ResponsesApi {
    @Headers("Content-Type: application/json")
    @POST("/v1/responses")
    suspend fun createResponse(
        @Header("Authorization") auth: String,
        @Body body: ResponsesRequest
    ): ResponsesResult
}

/* ---------- REQUEST MODELS ---------- */

@JsonClass(generateAdapter = true)
data class ResponsesRequest(
    val model: String,
    /** input: либо строка, либо список сообщений */
    val input: Any,
    /** Системная инструкция (вместо role=system) */
    val instructions: String? = null,
    val temperature: Double? = 0.7,
    @Json(name = "max_output_tokens") val maxOutputTokens: Int? = 512,
    /** Новая схема форматирования выхода: text.format (как объект) */
    val text: TextOptions? = null
)

/** Сообщение для input при многосообщенческом вводе */
@JsonClass(generateAdapter = true)
data class SimpleMessage(
    val role: String,          // "user" | "assistant"
    val content: String
)

/** Параметры текстового выхода */
@JsonClass(generateAdapter = true)
data class TextOptions(
    /** format — ОБЪЕКТ, а не строка */
    val format: TextFormat? = null
)

@JsonClass(generateAdapter = true)
data class TextFormat(
    val type: String,                  // "json_schema" | "json"
    val name: String? = null,          // требуется для json_schema
    val strict: Boolean? = null,       // опционально
    val schema: Map<String, Any>? = null  // JSON Schema при type="json_schema"
)

/* ---------- RESPONSE MODELS ---------- */

@JsonClass(generateAdapter = true)
data class ResponsesResult(
    val id: String?,
    val status: String?,
    /** Удобное поле со сверстанным текстом (если сервер заполняет) */
    @Json(name = "output_text") val outputText: String? = null,
    /** Детализация; используем как фоллбэк */
    val output: List<ResponseItem>? = null
) {
    @JsonClass(generateAdapter = true)
    data class ResponseItem(
        val role: String? = null,
        val type: String? = null,              // например, "message"
        val content: List<ResponseContent>? = null
    )
    @JsonClass(generateAdapter = true)
    data class ResponseContent(
        val type: String? = null,              // ожидаем "output_text"
        val text: String? = null
    )
}
