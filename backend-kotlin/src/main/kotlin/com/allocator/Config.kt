package com.allocator

/**
 * Application configuration loaded from environment variables.
 * Mirrors Python's pydantic-settings Config class.
 */
data class AppConfig(
    val databaseUrl: String = System.getenv("DATABASE_URL")
        ?: "postgresql://postgres:postgres@localhost:5432/allocator",
    val csvRootPath: String = System.getenv("CSV_ROOT_PATH") ?: "csv",
    val openAiApiKey: String? = System.getenv("OPENAI_API_KEY"),
    val assessmentModel: String = System.getenv("ASSESSMENT_MODEL") ?: "gpt-4o-mini",
    val openClawUrl: String = System.getenv("OPENCLAW_URL") ?: "http://openclaw:18789",
    val openClawToken: String? = System.getenv("OPENCLAW_TOKEN"),
    val openClawMaterialAgent: String = System.getenv("OPENCLAW_MATERIAL_AGENT") ?: "openclaw:material",
)

val config = AppConfig()
