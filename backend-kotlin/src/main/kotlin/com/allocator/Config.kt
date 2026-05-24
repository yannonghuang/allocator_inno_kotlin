package com.allocator

/**
 * Application configuration loaded from environment variables.
 * Mirrors Python's pydantic-settings Config class.
 *
 * LLM provider: controlled by LLM_PROVIDER (openclaw | anthropic | openai | nanogpt, default openclaw).
 *   openclaw  — route chat completions through OpenClaw's gateway using OPENCLAW_TOKEN.
 *               Reuses whatever provider OpenClaw is configured for (e.g. Anthropic via
 *               an OAuth subscription token) so no separate API key is needed.
 *   anthropic — call api.anthropic.com directly with ANTHROPIC_API_KEY (real sk-ant-api03-* key).
 *   openai    — call api.openai.com directly with OPENAI_API_KEY.
 *   nanogpt   — call NanoGPT's OpenAI-compatible endpoint with NANOGPT_API_KEY. Same upstream
 *               models OpenClaw routes to (minimax-m2.7, deepseek, claude-haiku, etc.) but as a
 *               thin OpenAI-shape passthrough, so the caller's `tools` array isn't merged with
 *               OpenClaw's built-in tool manifest. Used by the planning agent to keep its 35-tool
 *               decision space clean.
 * ASSESSMENT_MODEL overrides the default model for whichever provider is active.
 *
 * ALLOCATOR_LLM_PROVIDER (default nanogpt) is the shared pin used by all three
 * allocator-internal LLM callers (assessment rating + explanation, planning
 * copilot intent classifier, planning agent tool-use loop). It is independent
 * of LLM_PROVIDER above, which only routes the generic llmChat default path.
 * Flip ALLOCATOR_LLM_PROVIDER to switch every allocator LLM call site at once.
 */
data class AppConfig(
    val databaseUrl: String,
    val csvRootPath: String,
    val llmProvider: String,
    val allocatorLlmProvider: String,
    val openAiApiKey: String?,
    val anthropicApiKey: String?,
    val nanogptApiKey: String?,
    val nanogptBaseUrl: String,
    val assessmentModel: String,
    val openClawUrl: String,
    val openClawToken: String?,
    val openClawMaterialAgent: String,
) {
    companion object {
        // Treat empty env values as unset — docker-compose's `${VAR:-}` pattern
        // forwards an empty string when the host env lacks the var, and `?:`
        // alone would accept the empty string and skip the fallback.
        private fun env(name: String): String? = System.getenv(name)?.takeUnless { it.isBlank() }

        fun load(): AppConfig {
            val provider = (env("LLM_PROVIDER") ?: "openclaw").lowercase()
            val defaultModel = when (provider) {
                "openai" -> "gpt-4o-mini"
                "anthropic" -> "claude-haiku-4-5-20251001"
                "nanogpt" -> "minimax/minimax-m2.7"
                // OpenClaw gateway's /v1/chat/completions only accepts "openclaw"
                // (raw default LLM, no agent wrap) or "openclaw/<agentId>".
                // Raw completion is what impact-assessment + planning-copilot want.
                else -> "openclaw"
            }
            return AppConfig(
                databaseUrl = env("DATABASE_URL")
                    ?: "postgresql://postgres:postgres@localhost:5432/allocator",
                csvRootPath = env("CSV_ROOT_PATH") ?: "csv",
                llmProvider = provider,
                allocatorLlmProvider = (env("ALLOCATOR_LLM_PROVIDER") ?: "nanogpt").lowercase(),
                openAiApiKey = env("OPENAI_API_KEY"),
                anthropicApiKey = env("ANTHROPIC_API_KEY"),
                nanogptApiKey = env("NANOGPT_API_KEY"),
                nanogptBaseUrl = env("NANOGPT_BASE_URL")
                    ?: "https://nano-gpt.com/api/subscription/v1",
                assessmentModel = env("ASSESSMENT_MODEL") ?: defaultModel,
                openClawUrl = env("OPENCLAW_URL") ?: "http://openclaw:18789",
                openClawToken = env("OPENCLAW_TOKEN"),
                openClawMaterialAgent = env("OPENCLAW_MATERIAL_AGENT") ?: "openclaw:material",
            )
        }
    }
}

val config = AppConfig.load()
