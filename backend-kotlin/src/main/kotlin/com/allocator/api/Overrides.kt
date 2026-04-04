package com.allocator.api

import com.allocator.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Manual override CRUD routes — port of api/overrides.py.
 * Prefix: /cases/{case_id}/overrides
 */
fun Routing.overrideRoutes() {

    route("/cases/{case_id}/overrides") {

        fun requireCase(caseId: Int) = transaction {
            Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                ?: throw NoSuchElementException("Case not found")
        }

        fun rowToResponse(row: ResultRow) = ManualOverrideResponse(
            id = row[ManualOverrides.id],
            caseId = row[ManualOverrides.caseId],
            entityType = row[ManualOverrides.entityType],
            entityKey = row[ManualOverrides.entityKey],
            payload = Json.parseToJsonElement(row[ManualOverrides.payload]),
        )

        // GET /cases/{case_id}/overrides
        get {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            requireCase(caseId)
            val rows = transaction {
                ManualOverrides.selectAll().where { ManualOverrides.caseId eq caseId }.map { rowToResponse(it) }
            }
            call.respond(rows)
        }

        // POST /cases/{case_id}/overrides — add one
        post {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            requireCase(caseId)
            val body = call.receive<ManualOverrideCreate>()
            val response = transaction {
                val insertedId = ManualOverrides.insert {
                    it[ManualOverrides.caseId] = caseId
                    it[entityType] = body.entityType
                    it[entityKey] = body.entityKey
                    it[payload] = body.payload.toString()
                }[ManualOverrides.id]
                rowToResponse(ManualOverrides.selectAll().where { ManualOverrides.id eq insertedId }.single())
            }
            call.respond(HttpStatusCode.Created, response)
        }

        // PUT /cases/{case_id}/overrides — replace all
        put {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            requireCase(caseId)
            val overrides = call.receive<List<ManualOverrideCreate>>()
            transaction {
                ManualOverrides.deleteWhere { ManualOverrides.caseId eq caseId }
                overrides.forEach { body ->
                    ManualOverrides.insert {
                        it[ManualOverrides.caseId] = caseId
                        it[entityType] = body.entityType
                        it[entityKey] = body.entityKey
                        it[payload] = body.payload.toString()
                    }
                }
            }
            call.respond(mapOf("status" to "ok", "count" to overrides.size))
        }

        // DELETE /cases/{case_id}/overrides/{override_id}
        delete("/{override_id}") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val overrideId = call.parameters["override_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid override_id")
            transaction {
                val deleted = ManualOverrides.deleteWhere {
                    (ManualOverrides.id eq overrideId) and (ManualOverrides.caseId eq caseId)
                }
                if (deleted == 0) throw NoSuchElementException("Override not found")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
