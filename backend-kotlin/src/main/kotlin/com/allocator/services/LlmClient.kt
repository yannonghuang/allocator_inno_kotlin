package com.allocator.services

import com.allocator.config
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Provider-agnostic chat wrapper. Provider is selected by LLM_PROVIDER env:
 *   openclaw (default) → ${OPENCLAW_URL}/v1/chat/completions + OPENCLAW_TOKEN
 *                        (OpenAI-compatible; reuses OpenClaw's configured provider/credentials)
 *   anthropic          → https://api.anthropic.com/v1/messages + ANTHROPIC_API_KEY
 *   openai             → https://api.openai.com/v1/chat/completions + OPENAI_API_KEY
 *
 * To switch providers, flip LLM_PROVIDER; call sites don't change.
 */

data class LlmMessage(val role: String, val content: String)

class LlmNotConfiguredException(msg: String) : IllegalStateException(msg)

private val llmHttpClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 90_000 }
    }
}

// ── OpenAI wire types ────────────────────────────────────────────────────────

@Serializable
private data class OpenAiMsg(val role: String, val content: String)

@Serializable
private data class OpenAiReq(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double,
    val messages: List<OpenAiMsg>,
)

@Serializable
private data class OpenAiChoice(val message: OpenAiMsg)

@Serializable
private data class OpenAiResp(val choices: List<OpenAiChoice>)

// ── Anthropic wire types ─────────────────────────────────────────────────────

@Serializable
private data class AnthropicMsg(val role: String, val content: String)

@Serializable
private data class AnthropicReq(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double,
    val system: String? = null,
    val messages: List<AnthropicMsg>,
)

@Serializable
private data class AnthropicContent(val type: String, val text: String? = null)

@Serializable
private data class AnthropicResp(val content: List<AnthropicContent>)

// ── Public API ───────────────────────────────────────────────────────────────

suspend fun llmChat(
    systemPrompt: String? = null,
    messages: List<LlmMessage>,
    maxTokens: Int = 512,
    temperature: Double = 0.2,
    model: String? = null,
): String {
    val resolvedModel = model ?: config.assessmentModel
    return when (config.llmProvider) {
        "openai" -> openAiChat(systemPrompt, messages, maxTokens, temperature, resolvedModel)
        "anthropic" -> anthropicChat(systemPrompt, messages, maxTokens, temperature, resolvedModel)
        else -> openClawChat(systemPrompt, messages, maxTokens, temperature, resolvedModel)
    }
}

private suspend fun openClawChat(
    systemPrompt: String?,
    messages: List<LlmMessage>,
    maxTokens: Int,
    temperature: Double,
    model: String,
): String {
    val token = config.openClawToken
        ?: throw LlmNotConfiguredException("OPENCLAW_TOKEN is not configured")

    val all = buildList {
        if (!systemPrompt.isNullOrBlank()) add(OpenAiMsg("system", systemPrompt))
        messages.forEach { add(OpenAiMsg(it.role, it.content)) }
    }

    val resp = llmHttpClient.post("${config.openClawUrl}/v1/chat/completions") {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(OpenAiReq(model = model, maxTokens = maxTokens, temperature = temperature, messages = all))
    }

    if (!resp.status.isSuccess()) {
        throw IllegalStateException("OpenClaw gateway error ${resp.status.value}: ${resp.bodyAsText()}")
    }
    return resp.body<OpenAiResp>().choices.firstOrNull()?.message?.content
        ?: throw IllegalStateException("OpenClaw response contained no content")
}

private suspend fun openAiChat(
    systemPrompt: String?,
    messages: List<LlmMessage>,
    maxTokens: Int,
    temperature: Double,
    model: String,
): String {
    val apiKey = config.openAiApiKey
        ?: throw LlmNotConfiguredException("OPENAI_API_KEY is not configured")

    val all = buildList {
        if (!systemPrompt.isNullOrBlank()) add(OpenAiMsg("system", systemPrompt))
        messages.forEach { add(OpenAiMsg(it.role, it.content)) }
    }

    val resp = llmHttpClient.post("https://api.openai.com/v1/chat/completions") {
        header("Authorization", "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(OpenAiReq(model = model, maxTokens = maxTokens, temperature = temperature, messages = all))
    }

    if (!resp.status.isSuccess()) {
        throw IllegalStateException("OpenAI API error ${resp.status.value}: ${resp.bodyAsText()}")
    }
    return resp.body<OpenAiResp>().choices.firstOrNull()?.message?.content
        ?: throw IllegalStateException("OpenAI response contained no content")
}

private suspend fun anthropicChat(
    systemPrompt: String?,
    messages: List<LlmMessage>,
    maxTokens: Int,
    temperature: Double,
    model: String,
): String {
    val apiKey = config.anthropicApiKey
        ?: throw LlmNotConfiguredException("ANTHROPIC_API_KEY is not configured")

    // Anthropic: system is a top-level field; messages list must contain only user/assistant.
    val userAssistant = messages
        .filter { it.role == "user" || it.role == "assistant" }
        .map { AnthropicMsg(it.role, it.content) }

    val resp = llmHttpClient.post("https://api.anthropic.com/v1/messages") {
        header("x-api-key", apiKey)
        header("anthropic-version", "2023-06-01")
        contentType(ContentType.Application.Json)
        setBody(
            AnthropicReq(
                model = model,
                maxTokens = maxTokens,
                temperature = temperature,
                system = systemPrompt?.takeIf { it.isNotBlank() },
                messages = userAssistant,
            )
        )
    }

    if (!resp.status.isSuccess()) {
        throw IllegalStateException("Anthropic API error ${resp.status.value}: ${resp.bodyAsText()}")
    }
    return resp.body<AnthropicResp>().content.firstOrNull { it.type == "text" }?.text
        ?: throw IllegalStateException("Anthropic response contained no text content")
}
