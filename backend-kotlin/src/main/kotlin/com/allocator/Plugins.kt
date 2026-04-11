package com.allocator

import com.allocator.api.allocateRoutes
import com.allocator.api.bomGraphRoutes
import com.allocator.api.caseRoutes
import com.allocator.api.explanationRoutes
import com.allocator.api.overrideRoutes
import com.allocator.api.peggingRoutes
import com.allocator.api.productRoutes
import com.allocator.api.supplyRoutes
import com.allocator.api.viewRoutes
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.configurePlugins() {
    // JSON serialization — lenient to tolerate unknown keys from clients
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        })
    }

    // CORS — mirrors Python's CORSMiddleware allow_origins list
    install(CORS) {
        allowHost("localhost:3000")
        allowHost("127.0.0.1:3000")
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowCredentials = true
    }

    // Request logging
    install(CallLogging) {
        level = Level.INFO
    }

    // Global error handling
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("detail" to (cause.message ?: "Bad request")))
        }
        exception<NoSuchElementException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, mapOf("detail" to (cause.message ?: "Not found")))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("detail" to (cause.message ?: "Internal server error"))
            )
        }
    }
}

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        caseRoutes()
        overrideRoutes()
        allocateRoutes()
        peggingRoutes()
        viewRoutes()
        explanationRoutes()
        bomGraphRoutes()
        productRoutes()
        supplyRoutes()
    }
}
