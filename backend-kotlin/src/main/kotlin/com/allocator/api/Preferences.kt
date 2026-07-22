package com.allocator.api

import com.allocator.CasePreferenceConfigs
import com.allocator.CasePreferences
import com.allocator.Cases
import com.allocator.PlanRuns
import com.allocator.services.CaseLoader
import com.allocator.services.PreferenceCandidateRow
import com.allocator.services.PreferenceKb
import com.allocator.services.PreferenceKbEntry
import com.allocator.services.buildPreferenceKb
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.allocator.PreferencesRoute")

// ── Helper: load/persist case_preference rows ────────────────────────────────

data class CasePreferenceRow(
    val productId: String,
    val locationId: String,
    val methodType: String,
    val methodKey: String,
    val preference: Int,
    val inventoryScore: Double?,
    val deliveryScore: Double?,
    val criticalMaterialScore: Double?,
)

data class CasePreferenceConfig(
    val maxBomDepth: Int,
    val deliveryWeight: Double,
    val inventoryWeight: Double,
    val criticalMaterialWeight: Double,
    val generatedAt: String,
)

/** Loads case_preference rows for [caseId]. Returns null when none exist (planning falls back
 *  to raw CSV preference values, per-alternative). */
internal fun loadCasePreferenceRows(caseId: Int): List<CasePreferenceRow>? = transaction {
    val rows = CasePreferences.selectAll()
        .where { CasePreferences.caseId eq caseId }
        .map {
            CasePreferenceRow(
                productId = it[CasePreferences.productId],
                locationId = it[CasePreferences.locationId],
                methodType = it[CasePreferences.methodType],
                methodKey = it[CasePreferences.methodKey],
                preference = it[CasePreferences.preference],
                inventoryScore = it[CasePreferences.inventoryScore],
                deliveryScore = it[CasePreferences.deliveryScore],
                criticalMaterialScore = it[CasePreferences.criticalMaterialScore],
            )
        }
    if (rows.isEmpty()) null else rows
}

/** Loads the full lookup structure threaded into planning: per-alternative KB entries
 *  (canonical preference + raw axis scores) plus the delivery/inventory/critical-material
 *  weights used to build them — the weights are needed at planning time to reconstruct a
 *  candidate's continuous combined score for proportional root-level splitting (see
 *  [com.allocator.services.reconstructNodeScores]). Returns null when no KB exists for
 *  this case (planning falls back to raw CSV preference values, per-alternative). */
internal fun loadPreferenceKb(caseId: Int): PreferenceKb? {
    val rows = loadCasePreferenceRows(caseId) ?: return null
    val cfg = loadCasePreferenceConfig(caseId)
    return PreferenceKb(
        entries = rows.associate { r ->
            Triple(r.productId, r.locationId, r.methodKey) to
                PreferenceKbEntry(r.preference, r.inventoryScore, r.deliveryScore, r.criticalMaterialScore)
        },
        deliveryWeight = cfg?.deliveryWeight ?: 0.5,
        inventoryWeight = cfg?.inventoryWeight ?: 0.5,
        // Neutral guess for reconstructing a KB whose case has rows but no config row at all
        // (e.g. genuinely predates this feature) — deliberately NOT the new feature's own
        // 0.4 UI/API default for fresh generation, so an already-built KB's ranking is never
        // silently reinterpreted as if it had been built with today's defaults.
        criticalMaterialWeight = cfg?.criticalMaterialWeight ?: 0.0,
    )
}

private fun toRows(candidates: List<PreferenceCandidateRow>): List<CasePreferenceRow> = candidates.map {
    CasePreferenceRow(it.productId, it.locationId, it.methodType, it.methodKey, it.preference, it.inventoryScore, it.deliveryScore, it.criticalMaterialScore)
}

/**
 * Runs [buildPreferenceKb] for [caseId] and persists the result — full delete + batch-insert
 * into case_preference, plus an upsert of case_preference_config. Called only from the
 * Generate endpoint — deliberately NOT auto-seeded from the plan-run background job, since
 * the Preferences KB is optional-by-design (planning falls back to raw CSV preference when
 * absent; auto-seeding would silently change that fallback behavior without user action).
 */
internal fun generateAndSeedCasePreferences(
    caseId: Int,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    maxBomDepth: Int,
    deliveryWeight: Double,
    inventoryWeight: Double,
    criticalMaterialWeight: Double,
): List<CasePreferenceRow> {
    val rows = toRows(buildPreferenceKb(data, config, maxBomDepth, deliveryWeight, inventoryWeight, criticalMaterialWeight))
    transaction {
        CasePreferences.deleteWhere { CasePreferences.caseId eq caseId }
        if (rows.isNotEmpty()) {
            CasePreferences.batchInsert(rows) { row ->
                this[CasePreferences.caseId] = caseId
                this[CasePreferences.productId] = row.productId
                this[CasePreferences.locationId] = row.locationId
                this[CasePreferences.methodType] = row.methodType
                this[CasePreferences.methodKey] = row.methodKey
                this[CasePreferences.preference] = row.preference
                this[CasePreferences.inventoryScore] = row.inventoryScore
                this[CasePreferences.deliveryScore] = row.deliveryScore
                this[CasePreferences.criticalMaterialScore] = row.criticalMaterialScore
            }
        }
        val existing = CasePreferenceConfigs.selectAll().where { CasePreferenceConfigs.caseId eq caseId }.singleOrNull()
        if (existing != null) {
            CasePreferenceConfigs.update({ CasePreferenceConfigs.caseId eq caseId }) {
                it[CasePreferenceConfigs.maxBomDepth] = maxBomDepth
                it[CasePreferenceConfigs.deliveryWeight] = deliveryWeight
                it[CasePreferenceConfigs.inventoryWeight] = inventoryWeight
                it[CasePreferenceConfigs.criticalMaterialWeight] = criticalMaterialWeight
                it[CasePreferenceConfigs.generatedAt] = kotlinx.datetime.Clock.System.now()
            }
        } else {
            CasePreferenceConfigs.insert {
                it[CasePreferenceConfigs.caseId] = caseId
                it[CasePreferenceConfigs.maxBomDepth] = maxBomDepth
                it[CasePreferenceConfigs.deliveryWeight] = deliveryWeight
                it[CasePreferenceConfigs.inventoryWeight] = inventoryWeight
                it[CasePreferenceConfigs.criticalMaterialWeight] = criticalMaterialWeight
            }
        }
    }
    return rows
}

internal fun loadCasePreferenceConfig(caseId: Int): CasePreferenceConfig? = transaction {
    CasePreferenceConfigs.selectAll().where { CasePreferenceConfigs.caseId eq caseId }.singleOrNull()?.let {
        CasePreferenceConfig(
            maxBomDepth = it[CasePreferenceConfigs.maxBomDepth],
            deliveryWeight = it[CasePreferenceConfigs.deliveryWeight],
            inventoryWeight = it[CasePreferenceConfigs.inventoryWeight],
            criticalMaterialWeight = it[CasePreferenceConfigs.criticalMaterialWeight],
            generatedAt = it[CasePreferenceConfigs.generatedAt].toString(),
        )
    }
}

// ── Routes ────────────────────────────────────────────────────────────────────

fun Routing.preferenceRoutes() {

    fun requireCase(caseId: Int) = transaction {
        Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
            ?: throw NoSuchElementException("Case not found")
    }

    // Read-only context joined onto each row for display/filtering — not persisted, mirrors how
    // Demand Ordering surfaces request_due_time/priority/product_id/customer_id as context.
    fun prodAreaByProductLocation(caseId: Int): Map<Pair<String, String>, String?> {
        val data = transaction { CaseLoader.load(caseId) }
        return (data["productlocation"] ?: emptyList()).associate { pl ->
            val pid = pl["product_id"]?.toString() ?: ""
            val lid = pl["location_id"]?.toString() ?: ""
            (pid to lid) to (pl["prod_area"] as? String)
        }
    }

    fun rowJson(row: CasePreferenceRow, prodAreaByPl: Map<Pair<String, String>, String?> = emptyMap()): JsonObject = buildJsonObject {
        put("product_id", row.productId)
        put("location_id", row.locationId)
        put("method_type", row.methodType)
        put("method_key", row.methodKey)
        put("preference", row.preference)
        if (row.inventoryScore != null) put("inventory_score", row.inventoryScore) else put("inventory_score", JsonNull)
        if (row.deliveryScore != null) put("delivery_score", row.deliveryScore) else put("delivery_score", JsonNull)
        if (row.criticalMaterialScore != null) put("critical_material_score", row.criticalMaterialScore) else put("critical_material_score", JsonNull)
        val prodArea = prodAreaByPl[row.productId to row.locationId]
        if (prodArea != null) put("prod_area", prodArea) else put("prod_area", JsonNull)
    }

    fun configJson(cfg: CasePreferenceConfig): JsonObject = buildJsonObject {
        put("max_bom_depth", cfg.maxBomDepth)
        put("delivery_weight", cfg.deliveryWeight)
        put("inventory_weight", cfg.inventoryWeight)
        put("critical_material_weight", cfg.criticalMaterialWeight)
        put("generated_at", cfg.generatedAt)
    }

    // ── GET /cases/{case_id}/preferences ──────────────────────────────────────
    get("/cases/{case_id}/preferences") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val rows = loadCasePreferenceRows(caseId)
        if (rows == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            val cfg = loadCasePreferenceConfig(caseId)
            val prodAreaByPl = prodAreaByProductLocation(caseId)
            call.respond(buildJsonObject {
                put("rows", JsonArray(rows.map { rowJson(it, prodAreaByPl) }))
                if (cfg != null) put("config", configJson(cfg)) else put("config", JsonNull)
            })
        }
    }

    // ── POST /cases/{case_id}/preferences/generate ────────────────────────────
    // Body: { "max_bom_depth": N, "delivery_weight": D, "inventory_weight": I,
    //         "critical_material_weight": C, "config": {...} }
    post("/cases/{case_id}/preferences/generate") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val data = transaction { CaseLoader.load(caseId) }
        if ((data["demand"] ?: emptyList()).isEmpty()) throw IllegalArgumentException("No demand data for case $caseId")

        val body = runCatching { call.receiveText() }.getOrElse { "" }
        val payload = if (body.isBlank()) null else runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val maxBomDepth = payload?.get("max_bom_depth")?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 3
        val deliveryWeight = payload?.get("delivery_weight")?.jsonPrimitive?.doubleOrNull ?: 0.3
        val inventoryWeight = payload?.get("inventory_weight")?.jsonPrimitive?.doubleOrNull ?: 0.3
        val criticalMaterialWeight = payload?.get("critical_material_weight")?.jsonPrimitive?.doubleOrNull ?: 0.4

        // Config: prefer caller-supplied; fall back to latest plan run config — needed for the
        // critical-material axis (isRawCriticalPosition's purchase_allowed/purchasable_materials
        // criterion). Mirrors Allocation.kt's generate route exactly.
        val config: Map<String, Any?>? = run {
            val configJson = payload?.get("config")
            if (configJson != null && configJson !is JsonNull) {
                @Suppress("UNCHECKED_CAST")
                runCatching { jsonElementToNative(configJson) as? Map<String, Any?> }.getOrNull()
            } else {
                val latestConfigJson = transaction {
                    PlanRuns.select(PlanRuns.config)
                        .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                        .orderBy(PlanRuns.id to SortOrder.DESC)
                        .limit(1)
                        .singleOrNull()
                        ?.get(PlanRuns.config)
                }
                latestConfigJson?.let {
                    @Suppress("UNCHECKED_CAST")
                    runCatching { jsonElementToNative(Json.parseToJsonElement(it)) as? Map<String, Any?> }.getOrNull()
                }.also { if (it != null) log.info("[preferences] using config from latest plan run for case {}", caseId) }
            }
        }

        log.info("[preferences] generating for case {} (max_bom_depth={}, delivery_weight={}, inventory_weight={}, critical_material_weight={})",
            caseId, maxBomDepth, deliveryWeight, inventoryWeight, criticalMaterialWeight)
        val newRows = generateAndSeedCasePreferences(caseId, data, config, maxBomDepth, deliveryWeight, inventoryWeight, criticalMaterialWeight)
        log.info("[preferences] generated {} rows for case {}", newRows.size, caseId)

        val prodAreaByPl = prodAreaByProductLocation(caseId)
        call.respond(buildJsonObject { put("rows", JsonArray(newRows.map { rowJson(it, prodAreaByPl) })) })
    }

    // ── PUT /cases/{case_id}/preferences ──────────────────────────────────────
    // Body: { "rows": [{ "product_id", "location_id", "method_type", "method_key", "preference" }] }
    // Upserts by natural key (product_id, location_id, method_type, method_key); scores are
    // read-only/derived and not settable here.
    put("/cases/{case_id}/preferences") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val body = call.receiveText()
        val payload = Json.parseToJsonElement(body).jsonObject
        val rowsJson = payload["rows"]?.jsonArray ?: throw IllegalArgumentException("Missing 'rows'")
        val edits = rowsJson.map { el ->
            val obj = el.jsonObject
            Triple(
                Triple(
                    obj["product_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing product_id"),
                    obj["location_id"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing location_id"),
                    obj["method_type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing method_type"),
                ),
                obj["method_key"]?.jsonPrimitive?.content ?: "",
                obj["preference"]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Missing preference"),
            )
        }
        transaction {
            for ((key, methodKey, preference) in edits) {
                val (pid, lid, mtype) = key
                CasePreferences.update({
                    (CasePreferences.caseId eq caseId) and
                    (CasePreferences.productId eq pid) and
                    (CasePreferences.locationId eq lid) and
                    (CasePreferences.methodType eq mtype) and
                    (CasePreferences.methodKey eq methodKey)
                }) { it[CasePreferences.preference] = preference }
            }
        }
        call.respond(HttpStatusCode.OK, buildJsonObject { put("updated", edits.size) })
    }

    // ── DELETE /cases/{case_id}/preferences ───────────────────────────────────
    delete("/cases/{case_id}/preferences") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val deleted = transaction {
            CasePreferenceConfigs.deleteWhere { CasePreferenceConfigs.caseId eq caseId }
            CasePreferences.deleteWhere { CasePreferences.caseId eq caseId }
        }
        call.respond(buildJsonObject { put("deleted", deleted) })
    }

    // ── POST /cases/{case_id}/preferences/import ──────────────────────────────
    // Body: CSV text, header: product_id,location_id,method_type,method_key,preference[,inventory_score,delivery_score,critical_material_score]
    post("/cases/{case_id}/preferences/import") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val csvText = call.receiveText()
        val rows = parseCsvPreferences(csvText)
        log.info("[preferences] importing {} rows for case {}", rows.size, caseId)
        transaction {
            CasePreferences.deleteWhere { CasePreferences.caseId eq caseId }
            if (rows.isNotEmpty()) {
                CasePreferences.batchInsert(rows) { row ->
                    this[CasePreferences.caseId] = caseId
                    this[CasePreferences.productId] = row.productId
                    this[CasePreferences.locationId] = row.locationId
                    this[CasePreferences.methodType] = row.methodType
                    this[CasePreferences.methodKey] = row.methodKey
                    this[CasePreferences.preference] = row.preference
                    this[CasePreferences.inventoryScore] = row.inventoryScore
                    this[CasePreferences.deliveryScore] = row.deliveryScore
                    this[CasePreferences.criticalMaterialScore] = row.criticalMaterialScore
                }
            }
        }
        val prodAreaByPl = prodAreaByProductLocation(caseId)
        call.respond(buildJsonObject { put("rows", JsonArray(rows.map { rowJson(it, prodAreaByPl) })) })
    }

    // ── GET /cases/{case_id}/preferences/export ───────────────────────────────
    get("/cases/{case_id}/preferences/export") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        requireCase(caseId)
        val rows = transaction {
            CasePreferences.selectAll()
                .where { CasePreferences.caseId eq caseId }
                .orderBy(CasePreferences.productId to SortOrder.ASC, CasePreferences.locationId to SortOrder.ASC, CasePreferences.preference to SortOrder.ASC)
                .map {
                    CasePreferenceRow(
                        it[CasePreferences.productId], it[CasePreferences.locationId],
                        it[CasePreferences.methodType], it[CasePreferences.methodKey],
                        it[CasePreferences.preference], it[CasePreferences.inventoryScore], it[CasePreferences.deliveryScore],
                        it[CasePreferences.criticalMaterialScore],
                    )
                }
        }
        val sb = StringBuilder("product_id,location_id,method_type,method_key,preference,inventory_score,delivery_score,critical_material_score\n")
        for (row in rows) {
            sb.append(csvEscape(row.productId)).append(',')
            sb.append(csvEscape(row.locationId)).append(',')
            sb.append(csvEscape(row.methodType)).append(',')
            sb.append(csvEscape(row.methodKey)).append(',')
            sb.append(row.preference).append(',')
            sb.append(row.inventoryScore ?: "").append(',')
            sb.append(row.deliveryScore ?: "").append(',')
            sb.append(row.criticalMaterialScore ?: "")
            sb.append('\n')
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"preferences_case_$caseId.csv\"")
        call.respondText(sb.toString(), ContentType.Text.CSV)
    }
}

// ── CSV helpers ───────────────────────────────────────────────────────────────

private fun parseCsvPreferences(csv: String): List<CasePreferenceRow> {
    val lines = csv.trim().split('\n').map { it.trimEnd('\r') }
    if (lines.isEmpty()) return emptyList()
    val header = lines[0].split(',').map { it.trim().lowercase() }
    val pidIdx = header.indexOf("product_id")
    val lidIdx = header.indexOf("location_id")
    val typeIdx = header.indexOf("method_type")
    val keyIdx = header.indexOf("method_key")
    val prefIdx = header.indexOf("preference")
    val invIdx = header.indexOf("inventory_score")
    val delIdx = header.indexOf("delivery_score")
    val critIdx = header.indexOf("critical_material_score")
    if (pidIdx < 0 || lidIdx < 0 || typeIdx < 0 || prefIdx < 0)
        throw IllegalArgumentException("CSV must have product_id, location_id, method_type, and preference columns")
    return lines.drop(1).mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val cols = line.split(',')
        val pid = cols.getOrNull(pidIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val lid = cols.getOrNull(lidIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val mtype = cols.getOrNull(typeIdx)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val mkey = if (keyIdx >= 0) cols.getOrNull(keyIdx)?.trim() ?: "" else ""
        val pref = cols.getOrNull(prefIdx)?.trim()?.toIntOrNull() ?: return@mapNotNull null
        val inv = if (invIdx >= 0) cols.getOrNull(invIdx)?.trim()?.toDoubleOrNull() else null
        val del = if (delIdx >= 0) cols.getOrNull(delIdx)?.trim()?.toDoubleOrNull() else null
        val crit = if (critIdx >= 0) cols.getOrNull(critIdx)?.trim()?.toDoubleOrNull() else null
        CasePreferenceRow(pid, lid, mtype, mkey, pref, inv, del, crit)
    }
}

private fun csvEscape(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n'))
        "\"${value.replace("\"", "\"\"")}\""
    else value
