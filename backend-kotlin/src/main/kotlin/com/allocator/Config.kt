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
)

val config = AppConfig()
