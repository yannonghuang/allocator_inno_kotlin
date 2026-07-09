package com.allocator

import com.allocator.api.allocationRoutes
import com.allocator.api.allocateRoutes
import com.allocator.api.assessmentRoutes
import com.allocator.api.bomGraphRoutes
import com.allocator.api.caseRoutes
import com.allocator.api.explanationRoutes
import com.allocator.api.peggingRoutes
import com.allocator.api.resourceUtilizationRoutes
import com.allocator.api.materialImpactRoutes
import com.allocator.api.materialEventRoutes
import com.allocator.api.workOrderImpactRoutes
import com.allocator.api.woScheduleEventRoutes
import com.allocator.api.workOrderQueryRoutes
import com.allocator.api.resolveRoutes
import com.allocator.api.negotiationRoutes
import com.allocator.api.planRunRoutes
import com.allocator.api.planningAgentRoutes
import com.allocator.api.planningCopilotRoutes
import com.allocator.api.preferenceRoutes
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
        allowHost("localhost:3001")
        allowHost("127.0.0.1:3001")
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
            // Log the full stack trace so callers see the source even when the
            // exception's message is null (bare "Bad request").
            call.application.log.warn(
                "400 ${call.request.local.method.value} ${call.request.local.uri} -> ${cause::class.simpleName}: ${cause.message}",
                cause,
            )
            val detail = cause.message ?: "Bad request (${cause::class.simpleName})"
            call.respond(HttpStatusCode.BadRequest, mapOf("detail" to detail))
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
    // Match request paths whether or not the client appended a trailing slash.
    // Next.js's trailingSlash: true config can rewrite some DELETE/POST URLs
    // with a trailing slash; without this, those would 404/405 instead of
    // hitting the corresponding route declared without a trailing slash.
    install(IgnoreTrailingSlash)
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        caseRoutes()
        allocationRoutes()
        preferenceRoutes()
        allocateRoutes()
        peggingRoutes()
        resourceUtilizationRoutes()
        viewRoutes()
        explanationRoutes()
        bomGraphRoutes()
        materialImpactRoutes()
        materialEventRoutes()
        workOrderImpactRoutes()
        woScheduleEventRoutes()
        workOrderQueryRoutes()
        resolveRoutes()
        assessmentRoutes()
        planRunRoutes()
        negotiationRoutes()
        planningCopilotRoutes()
        planningAgentRoutes()
        productRoutes()
        supplyRoutes()
    }
}
