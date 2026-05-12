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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
    /**
     * Per-call provider override. When null, uses [AppConfig.llmProvider] (the global default
     * driven by `LLM_PROVIDER` env). Set to pin a specific call site to a fixed provider
     * regardless of the global flag — used by /material-impact-assessment and /planning-copilot
     * to stay on OpenAI even when the global provider is openclaw or anthropic.
     */
    provider: String? = null,
): String {
    val effectiveProvider = (provider ?: config.llmProvider).lowercase()
    val resolvedModel = model ?: defaultModelForProvider(effectiveProvider)
    return try {
        when (effectiveProvider) {
            "openai" -> openAiChat(systemPrompt, messages, maxTokens, temperature, resolvedModel)
            "anthropic" -> anthropicChat(systemPrompt, messages, maxTokens, temperature, resolvedModel)
            else -> openClawChat(systemPrompt, messages, maxTokens, temperature, resolvedModel)
        }
    } catch (e: java.nio.channels.UnresolvedAddressException) {
        // UnresolvedAddressException extends IllegalArgumentException (JDK quirk),
        // which would otherwise surface as a misleading 400 Bad request via StatusPages.
        throw IllegalStateException(
            "LLM provider host unresolved (provider=$effectiveProvider). " +
                "Check OPENCLAW_URL / network reachability.",
            e,
        )
    } catch (e: java.net.ConnectException) {
        throw IllegalStateException(
            "LLM provider connection refused (provider=$effectiveProvider): ${e.message}",
            e,
        )
    }
}

private fun defaultModelForProvider(provider: String): String = when (provider) {
    // When the caller pins a provider, prefer ASSESSMENT_MODEL only if it matches that
    // provider's model id space. The global config.assessmentModel was resolved against
    // config.llmProvider at startup, so it's only safe to reuse when the providers match.
    config.llmProvider -> config.assessmentModel
    "openai" -> "gpt-4o-mini"
    "anthropic" -> "claude-haiku-4-5-20251001"
    // openclaw gateway forwards model ids through to its configured provider. The gateway's
    // agent default is "anthropic/claude-sonnet-4-6" (openclaw.json.template), so we use the
    // same id here — pickable also via ASSESSMENT_MODEL=anthropic/<id>.
    else -> "anthropic/claude-sonnet-4-6"
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

// ── Tool-use API (OpenAI function-calling) ──────────────────────────────────
//
// Sends an OpenAI-shaped chat-completions request with `tools` and consumes
// the `tool_calls` round-trip. Supports two providers:
//   - "openai"   → api.openai.com directly (legacy path; requires OPENAI_API_KEY)
//   - "openclaw" → ${OPENCLAW_URL}/v1/chat/completions (OpenAI-compatible
//                  proxy; ultimately Anthropic-backed per OpenClaw config)
// Anthropic-direct is a stub — set LLM_PROVIDER=openclaw to reach Claude.

/** A function-callable tool advertised to the model. `parameters` is JSON Schema. */
data class LlmTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** One tool invocation requested by the model. `arguments` is a JSON string per OpenAI spec. */
data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/**
 * Conversation message for the tool-use loop. Shape matches OpenAI's wire format:
 *   - role=user/system: `content` set, others null
 *   - role=assistant with text reply: `content` set, `toolCalls` null
 *   - role=assistant requesting tools: `content` may be null, `toolCalls` non-empty
 *   - role=tool (result of a prior tool call): `content` + `toolCallId` set
 */
data class LlmAgentMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<LlmToolCall>? = null,
)

data class LlmToolResponse(
    /** Final assistant text when the model is done calling tools; null when only tool_calls. */
    val text: String?,
    /** Tools the model wants the caller to execute next; empty when the model produced text. */
    val toolCalls: List<LlmToolCall>,
)

/**
 * Single tool-use round-trip: send messages + tools to the model, return its
 * response (either final text or a list of tool calls to execute). The caller
 * runs the agent loop: append tool results as `role=tool` messages and call
 * again until `toolCalls.isEmpty()`.
 *
 * Provider dispatch:
 *   - openai   → api.openai.com directly with OPENAI_API_KEY.
 *   - openclaw → OpenClaw gateway at OPENCLAW_URL/v1/chat/completions with
 *                OPENCLAW_TOKEN. The gateway is OpenAI-compatible and (per
 *                openclaw.json.template) forwards to Anthropic by default.
 *   - anthropic→ Not implemented; throws LlmNotConfiguredException.
 *                Use 'openclaw' to reach Claude.
 */
suspend fun llmChatWithTools(
    systemPrompt: String? = null,
    messages: List<LlmAgentMessage>,
    tools: List<LlmTool>,
    maxTokens: Int = 1024,
    temperature: Double = 0.2,
    model: String? = null,
    provider: String? = null,
): LlmToolResponse {
    val effectiveProvider = (provider ?: config.llmProvider).lowercase()
    val resolvedModel = model ?: defaultModelForProvider(effectiveProvider)
    val body = buildOpenAiChatCompletionsBody(
        systemPrompt = systemPrompt,
        messages = messages,
        tools = tools,
        model = resolvedModel,
        maxTokens = maxTokens,
        temperature = temperature,
    )
    return when (effectiveProvider) {
        "openai" -> {
            val apiKey = config.openAiApiKey
                ?: throw LlmNotConfiguredException("OPENAI_API_KEY is not configured")
            postChatCompletionsAndParse(
                url = "https://api.openai.com/v1/chat/completions",
                authHeaderValue = "Bearer $apiKey",
                body = body,
                providerLabel = "OpenAI",
            )
        }
        "openclaw" -> {
            val token = config.openClawToken
                ?: throw LlmNotConfiguredException("OPENCLAW_TOKEN is not configured")
            postChatCompletionsAndParse(
                url = "${config.openClawUrl}/v1/chat/completions",
                authHeaderValue = "Bearer $token",
                body = body,
                providerLabel = "OpenClaw",
            )
        }
        "anthropic" -> throw LlmNotConfiguredException(
            "llmChatWithTools(anthropic) is not implemented — use LLM_PROVIDER=openclaw to reach Claude via the gateway.",
        )
        else -> throw LlmNotConfiguredException(
            "llmChatWithTools: unknown provider '$effectiveProvider'. Supported: openai, openclaw.",
        )
    }
}

/** Build the OpenAI-shaped chat-completions request body (with tools + tool_calls round-trip).
 *  Reused for both the openai-direct and openclaw-gateway branches since OpenClaw speaks the
 *  same wire format. */
private fun buildOpenAiChatCompletionsBody(
    systemPrompt: String?,
    messages: List<LlmAgentMessage>,
    tools: List<LlmTool>,
    model: String,
    maxTokens: Int,
    temperature: Double,
): JsonObject = buildJsonObject {
    put("model", model)
    put("max_tokens", maxTokens)
    put("temperature", temperature)
    put("messages", buildJsonArray {
        if (!systemPrompt.isNullOrBlank()) {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        messages.forEach { m ->
            add(buildJsonObject {
                put("role", m.role)
                // OpenAI requires `content` field even when null for tool-call assistant messages.
                put("content", m.content?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                m.toolCallId?.let { put("tool_call_id", it) }
                m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                    put("tool_calls", buildJsonArray {
                        calls.forEach { c ->
                            add(buildJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", c.name)
                                    put("arguments", c.arguments)
                                }
                            })
                        }
                    })
                }
            })
        }
    })
    put("tools", buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parameters)
                }
            })
        }
    })
    put("tool_choice", "auto")
}

/** POST an OpenAI-compatible chat-completions body and parse the (text, toolCalls) response.
 *  Used by both the openai and openclaw branches. */
private suspend fun postChatCompletionsAndParse(
    url: String,
    authHeaderValue: String,
    body: JsonObject,
    providerLabel: String,
): LlmToolResponse {
    val resp = try {
        llmHttpClient.post(url) {
            header("Authorization", authHeaderValue)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    } catch (e: java.nio.channels.UnresolvedAddressException) {
        throw IllegalStateException("$providerLabel host unresolved", e)
    } catch (e: java.net.ConnectException) {
        throw IllegalStateException("$providerLabel connection refused: ${e.message}", e)
    }

    if (!resp.status.isSuccess()) {
        throw IllegalStateException("$providerLabel API error ${resp.status.value}: ${resp.bodyAsText()}")
    }

    val json = resp.body<JsonElement>().jsonObjectOrNull()
        ?: throw IllegalStateException("$providerLabel response was not a JSON object")
    val choices = (json["choices"] as? kotlinx.serialization.json.JsonArray)
        ?: throw IllegalStateException("$providerLabel response missing 'choices' array")
    val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
        ?: throw IllegalStateException("$providerLabel response missing message in first choice")

    val text = (message["content"] as? JsonPrimitive)?.let {
        if (it.isString) it.content else null
    }

    val toolCalls = ((message["tool_calls"] as? kotlinx.serialization.json.JsonArray) ?: emptyList<JsonElement>())
        .mapNotNull { tc ->
            val obj = tc as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val fn = obj["function"] as? JsonObject ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val args = (fn["arguments"] as? JsonPrimitive)?.content ?: "{}"
            LlmToolCall(id = id, name = name, arguments = args)
        }

    return LlmToolResponse(text = text, toolCalls = toolCalls)
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
