package com.allocator.api

import com.allocator.*
import com.allocator.services.CaseLoader
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate

/**
 * Per-resource utilization over the planning horizon.
 *
 * For each completed work_order that has an applicable operation (same
 * applicability gate as OperationLookup.effectiveLeadDays), the WO occupies
 * [start_time, end_time] and consumes `resource_rate` units of each BOR
 * resource. Daily buckets aggregate concurrent consumption per
 * (resource_id, location_id). Locations that have a resource row but never
 * see any consumption still appear so the UI can show idle capacity.
 */
fun Routing.resourceUtilizationRoutes() {
    get("/cases/{case_id}/runs/{run_id}/resource-utilization") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid case_id"))
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid run_id"))

        val payload = computeResourceUtilization(caseId, runId)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Plan run not found"))
        // kotlinx-serialization can't serialize Map<String, Any?> with mixed value
        // types directly (each `rows` row carries String/Double/List<Double>/List<Map>).
        // Convert the whole tree to JsonElement first — same pattern as
        // WorkOrderImpactRoutes.resultToJson.
        call.respond(toJsonElement(payload))
    }
}

private fun toJsonElement(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject { v.forEach { (k, vv) -> put(k.toString(), toJsonElement(vv)) } }
    is List<*> -> buildJsonArray { v.forEach { add(toJsonElement(it)) } }
    is DoubleArray -> buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }
    else -> JsonPrimitive(v.toString())
}

@Suppress("UNCHECKED_CAST")
private fun computeResourceUtilization(caseId: Int, runId: Int): Map<String, Any?>? {
    val dataset = CaseLoader.load(caseId)

    val resultJson = transaction {
        PlanRuns.selectAll().where {
            (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId)
        }.firstOrNull()?.get(PlanRuns.result)
    } ?: return null

    val resultMap = runCatching {
        jsonElementToNative(Json.parseToJsonElement(resultJson)) as? Map<String, Any?>
    }.getOrNull() ?: return null
    val workOrders = (resultMap["work_orders"] as? List<Map<String, Any?>>) ?: emptyList()

    val operations = dataset["operation"] ?: emptyList()
    val bors = dataset["bor"] ?: emptyList()
    val resources = dataset["resource"] ?: emptyList()
    val productLocations = dataset["productlocation"] ?: emptyList()

    val operationByProdArea = operations.associateBy {
        (it["prod_area"] as? String)?.trim() ?: ""
    }
    val borsById = bors.groupBy { (it["bor_id"] as? String)?.trim() ?: "" }
    // Resource size keyed by (resource_id, location_id) — used both for
    // capacity display and to enforce applicability (resource must exist
    // at the WO's location).
    val sizeByResLoc = resources.associate {
        val rid = (it["resource_id"] as? String)?.trim() ?: ""
        val lid = (it["location_id"] as? String)?.trim() ?: ""
        (rid to lid) to ((it["size"] as? Number)?.toDouble() ?: 0.0)
    }
    val prodAreaByProductLoc = productLocations.associate {
        val pid = (it["product_id"] as? String)?.trim() ?: ""
        val lid = (it["location_id"] as? String)?.trim() ?: ""
        (pid to lid) to ((it["prod_area"] as? String)?.trim() ?: "")
    }

    // First pass: walk WOs to find the horizon and accumulate (resource, location) → date → load.
    val loadMap = mutableMapOf<Pair<String, String>, MutableMap<LocalDate, Double>>()
    var minDate: LocalDate? = null
    var maxDate: LocalDate? = null
    val contributors = mutableMapOf<Pair<String, String>, MutableList<Map<String, Any?>>>()

    for (wo in workOrders) {
        if ((wo["method"] as? String)?.lowercase() != "make") continue
        val productId = (wo["product_id"] as? String)?.trim() ?: continue
        val locationId = (wo["location_id"] as? String)?.trim() ?: continue
        val startDt = parseDateLoose(wo["start_time"] as? String) ?: continue
        val endDt = parseDateLoose(wo["end_time"] as? String) ?: continue
        if (endDt < startDt) continue

        val prodArea = prodAreaByProductLoc[productId to locationId]?.takeIf { it.isNotBlank() } ?: continue
        val op = operationByProdArea[prodArea] ?: continue
        val borId = (op["bor_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
        val borRows = borsById[borId] ?: continue
        if (borRows.isEmpty()) continue

        // Verify every BOR resource has a resource row at this location —
        // identical applicability gate to the planner override.
        val allPresent = borRows.all { br ->
            val rid = (br["resource_id"] as? String)?.trim() ?: return@all false
            sizeByResLoc.containsKey(rid to locationId)
        }
        if (!allPresent) continue

        for (br in borRows) {
            val rid = (br["resource_id"] as? String)?.trim() ?: continue
            val rate = (br["resource_rate"] as? Number)?.toDouble() ?: continue
            val key = rid to locationId
            val bucket = loadMap.getOrPut(key) { mutableMapOf() }
            var d = startDt
            while (d <= endDt) {
                bucket[d] = (bucket[d] ?: 0.0) + rate
                d = d.plusDays(1)
            }
            if (minDate == null || startDt < minDate) minDate = startDt
            if (maxDate == null || endDt > maxDate) maxDate = endDt
            contributors.getOrPut(key) { mutableListOf() }.add(mapOf(
                "wo_group_id" to wo["wo_group_id"],
                "demand_id" to wo["demand_id"],
                "product_id" to productId,
                "location_id" to locationId,
                "quantity" to wo["quantity"],
                "start_time" to wo["start_time"],
                "end_time" to wo["end_time"],
                "rate" to rate,
            ))
        }
    }

    // If no WO touched any resource, fall back to a single-day horizon
    // anchored at today so the UI still renders idle capacity rows.
    val start = minDate ?: LocalDate.now()
    val end = maxDate ?: start
    val buckets = mutableListOf<String>()
    run {
        var d = start
        while (d <= end) {
            buckets.add(d.toString())
            d = d.plusDays(1)
        }
    }
    val bucketIndex = buckets.withIndex().associate { (i, s) -> LocalDate.parse(s) to i }

    val rows = mutableListOf<Map<String, Any?>>()
    for ((key, size) in sizeByResLoc) {
        val (rid, lid) = key
        val load = DoubleArray(buckets.size)
        loadMap[key]?.forEach { (d, v) ->
            val idx = bucketIndex[d] ?: return@forEach
            load[idx] = v
        }
        rows.add(mapOf(
            "resource_id" to rid,
            "location_id" to lid,
            "size" to size,
            "load" to load.toList(),
            "contributors" to (contributors[key] ?: emptyList<Map<String, Any?>>()),
        ))
    }

    rows.sortWith(compareBy({ it["location_id"] as? String ?: "" }, { it["resource_id"] as? String ?: "" }))

    return mapOf(
        "horizon" to mapOf("start" to start.toString(), "end" to end.toString()),
        "buckets" to buckets,
        "rows" to rows,
    )
}

private fun parseDateLoose(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    return try {
        // Accept "yyyy-MM-dd" or longer ISO timestamps; truncate to date.
        LocalDate.parse(s.take(10))
    } catch (_: Exception) {
        null
    }
}
