package com.allocator.api

import com.allocator.Cases
import com.allocator.MaterialEvents
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class MaterialEventRequest(
    val supplyId: String,
    val delayDays: Int = 0,
    val qtyDecreasePct: Double = 0.0,
    /** Absolute qty reduction. When set and > 0, takes precedence over qtyDecreasePct. */
    val qtyDecreaseAbs: Double? = null,
    val note: String? = null,
)

@Serializable
data class MaterialEventResponse(
    val id: Int,
    val caseId: Int,
    val supplyId: String,
    val delayDays: Int,
    val qtyDecreasePct: Double,
    val qtyDecreaseAbs: Double? = null,
    val note: String?,
    val createdAt: String,
)

private fun rowToResponse(row: ResultRow) = MaterialEventResponse(
    id             = row[MaterialEvents.id],
    caseId         = row[MaterialEvents.caseId],
    supplyId       = row[MaterialEvents.supplyId],
    delayDays      = row[MaterialEvents.delayDays],
    qtyDecreasePct = row[MaterialEvents.qtyDecreasePct],
    qtyDecreaseAbs = row[MaterialEvents.qtyDecreaseAbs],
    note           = row[MaterialEvents.note],
    createdAt      = row[MaterialEvents.createdAt].toString(),
)

fun Routing.materialEventRoutes() {
    route("/cases/{case_id}/material-events") {

        // List all events for a case
        get {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            transaction {
                Cases.selectAll().where { Cases.id eq caseId }.firstOrNull()
                    ?: throw NoSuchElementException("Case $caseId not found")
            }
            val rows = transaction {
                MaterialEvents.selectAll()
                    .where { MaterialEvents.caseId eq caseId }
                    .orderBy(MaterialEvents.createdAt, SortOrder.DESC)
                    .map { rowToResponse(it) }
            }
            call.respond(rows)
        }

        // Create a new event
        post {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            transaction {
                Cases.selectAll().where { Cases.id eq caseId }.firstOrNull()
                    ?: throw NoSuchElementException("Case $caseId not found")
            }
            val req = call.receive<MaterialEventRequest>()
            val created = transaction {
                val newId = MaterialEvents.insert {
                    it[MaterialEvents.caseId]         = caseId
                    it[MaterialEvents.supplyId]       = req.supplyId.trim()
                    it[MaterialEvents.delayDays]      = req.delayDays
                    it[MaterialEvents.qtyDecreasePct] = req.qtyDecreasePct
                    it[MaterialEvents.qtyDecreaseAbs] = req.qtyDecreaseAbs
                    it[MaterialEvents.note]           = req.note?.trim()
                }[MaterialEvents.id]
                MaterialEvents.selectAll()
                    .where { MaterialEvents.id eq newId }
                    .map { rowToResponse(it) }
                    .first()
            }
            call.respond(HttpStatusCode.Created, created)
        }

        // Update an existing event
        put("/{event_id}") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val eventId = call.parameters["event_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid event_id")
            val req = call.receive<MaterialEventRequest>()
            transaction {
                val updated = MaterialEvents.update(
                    { (MaterialEvents.id eq eventId) and (MaterialEvents.caseId eq caseId) }
                ) {
                    it[supplyId]       = req.supplyId.trim()
                    it[delayDays]      = req.delayDays
                    it[qtyDecreasePct] = req.qtyDecreasePct
                    it[qtyDecreaseAbs] = req.qtyDecreaseAbs
                    it[note]           = req.note?.trim()
                }
                if (updated == 0) throw NoSuchElementException("Event $eventId not found for case $caseId")
            }
            val updated = transaction {
                MaterialEvents.selectAll()
                    .where { (MaterialEvents.id eq eventId) and (MaterialEvents.caseId eq caseId) }
                    .map { rowToResponse(it) }
                    .first()
            }
            call.respond(updated)
        }

        // Delete an event
        delete("/{event_id}") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val eventId = call.parameters["event_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid event_id")
            transaction {
                val deleted = MaterialEvents.deleteWhere {
                    (MaterialEvents.id eq eventId) and (MaterialEvents.caseId eq caseId)
                }
                if (deleted == 0) throw NoSuchElementException("Event $eventId not found for case $caseId")
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
