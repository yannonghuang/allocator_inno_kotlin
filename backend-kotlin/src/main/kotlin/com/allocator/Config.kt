package com.allocator

/**
 * Application configuration loaded from environment variables.
 * Mirrors Python's pydantic-settings Config class.
 *
 * LLM provider: controlled by LLM_PROVIDER (openclaw | anthropic | openai, default openclaw).
 *   openclaw  — route chat completions through OpenClaw's gateway using OPENCLAW_TOKEN.
 *               Reuses whatever provider OpenClaw is configured for (e.g. Anthropic via
 *               an OAuth subscription token) so no separate API key is needed.
 *   anthropic — call api.anthropic.com directly with ANTHROPIC_API_KEY (real sk-ant-api03-* key).
 *   openai    — call api.openai.com directly with OPENAI_API_KEY.
 * ASSESSMENT_MODEL overrides the default model for whichever provider is active.
 */
data class AppConfig(
    val databaseUrl: String,
    val csvRootPath: String,
    val llmProvider: String,
    val openAiApiKey: String?,
    val anthropicApiKey: String?,
    val assessmentModel: String,
    val openClawUrl: String,
    val openClawToken: String?,
    val openClawMaterialAgent: String,
) {
    companion object {
        fun load(): AppConfig {
            val provider = (System.getenv("LLM_PROVIDER") ?: "openclaw").lowercase()
            val defaultModel = when (provider) {
                "openai" -> "gpt-4o-mini"
                "anthropic" -> "claude-haiku-4-5-20251001"
                // OpenClaw gateway's /v1/chat/completions only accepts "openclaw"
                // (raw default LLM, no agent wrap) or "openclaw/<agentId>".
                // Raw completion is what impact-assessment + planning-copilot want.
                else -> "openclaw"
            }
            return AppConfig(
                databaseUrl = System.getenv("DATABASE_URL")
                    ?: "postgresql://postgres:postgres@localhost:5432/allocator",
                csvRootPath = System.getenv("CSV_ROOT_PATH") ?: "csv",
                llmProvider = provider,
                openAiApiKey = System.getenv("OPENAI_API_KEY"),
                anthropicApiKey = System.getenv("ANTHROPIC_API_KEY"),
                assessmentModel = System.getenv("ASSESSMENT_MODEL") ?: defaultModel,
                openClawUrl = System.getenv("OPENCLAW_URL") ?: "http://openclaw:18789",
                openClawToken = System.getenv("OPENCLAW_TOKEN"),
                openClawMaterialAgent = System.getenv("OPENCLAW_MATERIAL_AGENT") ?: "openclaw:material",
            )
        }
    }
}

val config = AppConfig.load()
