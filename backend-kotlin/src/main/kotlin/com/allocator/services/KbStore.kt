package com.allocator.services

import com.allocator.KbRecords
import com.allocator.PlanRuns
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Independent persistence layer for the planning knowledge base.
 *
 * KB records are derived from plan_runs at completion time but persist
 * independently — deleting a plan_run flips `sourcePlanRunDeleted` rather
 * than cascading. This keeps the KB stable as a design surface even as
 * the underlying plan_runs come and go.
 *
 * One row per (case, config-signature). Re-running the same configuration
 * upserts the row in place rather than accumulating duplicates.
 */
object KbStore {

    private val json = Json { ignoreUnknownKeys = true }

    /** Snapshot of a KB record returned to readers. */
    data class KbRecord(
        val id: Int,
        val caseId: Int,
        val signature: String,
        val presetId: String?,
        val presetLabel: String?,
        val primaryAxis: String?,
        val configJson: String,
        val kpisSnapshotJson: String,
        val sourcePlanRunId: Int?,
        val sourcePlanRunDeleted: Boolean,
        val soundnessStatus: String,
    )

    /** Headline KPI snapshot extracted from a plan_run.result JSON tree.
     *  Exposed so the public listPlanRuns route can compute KPIs on-the-fly
     *  for runs not yet represented in kb_records (unsound, in-flight, etc.). */
    fun extractKpisFromResult(resultRaw: String?): JsonObject = extractKpis(resultRaw)

    /** Headline KPI snapshot extracted from a plan_run.result JSON tree. */
    private fun extractKpis(resultRaw: String?): JsonObject {
        if (resultRaw.isNullOrBlank()) return buildJsonObject { }
        val root = runCatching { json.parseToJsonElement(resultRaw).jsonObject }.getOrNull()
            ?: return buildJsonObject { }
        val kpis = root["plan_kpis"] as? JsonObject ?: return buildJsonObject { }
        val delivery = kpis["delivery"] as? JsonObject
        val fairness = kpis["fairness"] as? JsonObject
        val mfg = kpis["manufacturing"] as? JsonObject
        val inv = kpis["inventory"] as? JsonObject
        fun num(o: JsonObject?, k: String): Double? = o?.get(k)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        fun int(o: JsonObject?, k: String): Int? = o?.get(k)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        return buildJsonObject {
            num(delivery, "fill_rate_pct")?.let          { put("fill_rate_pct", JsonPrimitive(it)) }
            num(fairness, "gini")?.let                   { put("gini", JsonPrimitive(it)) }
            num(fairness, "p10_fill_ratio")?.let         { put("p10_fill_ratio", JsonPrimitive(it)) }
            num(fairness, "median_fill_ratio")?.let      { put("median_fill_ratio", JsonPrimitive(it)) }
            num(fairness, "starvation_pct")?.let         { put("starvation_pct", JsonPrimitive(it)) }
            int(delivery, "on_time_count")?.let          { put("on_time_count", JsonPrimitive(it)) }
            num(delivery, "total_committed")?.let        { put("total_committed", JsonPrimitive(it)) }
            num(delivery, "total_requested")?.let        { put("total_requested", JsonPrimitive(it)) }
            num(mfg, "total_quantity")?.let              { put("manufacturing_total_quantity", JsonPrimitive(it)) }
            num(inv, "consumed_total")?.let              { put("inventory_consumed_total", JsonPrimitive(it)) }
        }
    }

    /** Parse the bootstrap-tagged metadata from a plan_run if any. */
    private fun parseBootstrapMetadata(metadataRaw: String?): Triple<String, String?, String?>? {
        if (metadataRaw.isNullOrBlank()) return null
        val md = runCatching { json.parseToJsonElement(metadataRaw).jsonObject }.getOrNull() ?: return null
        val isBootstrap = md["bootstrap"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
        if (!isBootstrap) return null
        val presetId = md["preset_id"]?.jsonPrimitive?.contentOrNull
        val presetLabel = md["preset_label"]?.jsonPrimitive?.contentOrNull
        val primaryAxis = md["primary_axis"]?.jsonPrimitive?.contentOrNull
        return Triple(presetId ?: return null, presetLabel, primaryAxis)
    }

    /**
     * Read a plan_run and upsert the corresponding KB record. Idempotent;
     * safe to call repeatedly. Returns the row id, or null if the plan_run
     * isn't suitable for the KB (no config, parse failure).
     *
     * Caller is expected to provide a plan_run that has finished and has a
     * result JSON populated; the soundness_status is read at upsert time so
     * a later re-check can refresh by calling this again.
     */
    fun upsertFromPlanRun(planRunId: Int): Int? = transaction {
        val row = PlanRuns.selectAll().where { PlanRuns.id eq planRunId }.firstOrNull() ?: return@transaction null
        val caseId = row[PlanRuns.caseId]
        val configRaw = row[PlanRuns.config] ?: return@transaction null
        val configJson = runCatching { json.parseToJsonElement(configRaw).jsonObject }.getOrNull()
            ?: return@transaction null
        val signature = CaseBootstrap.signatureFor(configJson)
        val kpisSnapshot = extractKpis(row[PlanRuns.result]).toString()
        val configString = configJson.toString()
        val soundness = row[PlanRuns.soundnessStatus]
        val bootstrapMeta = parseBootstrapMetadata(row[PlanRuns.metadata])

        val existing = KbRecords.selectAll()
            .where { (KbRecords.caseId eq caseId) and (KbRecords.signature eq signature) }
            .firstOrNull()
        if (existing == null) {
            KbRecords.insert {
                it[KbRecords.caseId] = caseId
                it[KbRecords.signature] = signature
                it[KbRecords.presetId] = bootstrapMeta?.first
                it[KbRecords.presetLabel] = bootstrapMeta?.second
                it[KbRecords.primaryAxis] = bootstrapMeta?.third
                it[KbRecords.config] = configString
                it[KbRecords.kpisSnapshot] = kpisSnapshot
                it[KbRecords.sourcePlanRunId] = planRunId
                it[KbRecords.sourcePlanRunDeleted] = false
                it[KbRecords.soundnessStatus] = soundness
            }[KbRecords.id]
        } else {
            val existingId = existing[KbRecords.id]
            KbRecords.update({ KbRecords.id eq existingId }) {
                // Refresh the snapshot in place. Preset metadata only updated when
                // the new run is bootstrap-tagged — preserves bootstrap provenance
                // when a user-driven re-run lands on the same signature.
                it[KbRecords.config] = configString
                it[KbRecords.kpisSnapshot] = kpisSnapshot
                it[KbRecords.sourcePlanRunId] = planRunId
                it[KbRecords.sourcePlanRunDeleted] = false
                it[KbRecords.soundnessStatus] = soundness
                if (bootstrapMeta != null) {
                    it[KbRecords.presetId] = bootstrapMeta.first
                    it[KbRecords.presetLabel] = bootstrapMeta.second
                    it[KbRecords.primaryAxis] = bootstrapMeta.third
                }
                it[KbRecords.updatedAt] = Clock.System.now()
            }
            existingId
        }
    }

    /**
     * Backfill: for any successful + sound plan_run on this case that doesn't
     * yet have a corresponding kb_record, create one. Idempotent. Called
     * lazily on KB read so historical successful runs surface in the KB
     * (the agent and dedup both rely on the entire KB).
     */
    fun backfillForCase(caseId: Int) = transaction {
        val existingSigs = KbRecords.selectAll()
            .where { KbRecords.caseId eq caseId }
            .map { it[KbRecords.signature] }
            .toSet()
        val candidateRuns = PlanRuns.selectAll()
            .where {
                (PlanRuns.caseId eq caseId)
                    .and(PlanRuns.status eq "success")
                    .and(PlanRuns.soundnessStatus eq "sound")
            }
            .toList()
        for (row in candidateRuns) {
            val configRaw = row[PlanRuns.config] ?: continue
            val configJson = runCatching { json.parseToJsonElement(configRaw).jsonObject }.getOrNull() ?: continue
            val sig = CaseBootstrap.signatureFor(configJson)
            if (sig in existingSigs) continue
            upsertFromPlanRun(row[PlanRuns.id])
        }
    }

    /** All KB records for a case, keyed by signature. */
    fun listForCase(caseId: Int): Map<String, KbRecord> = transaction {
        KbRecords.selectAll()
            .where { KbRecords.caseId eq caseId }
            .associate { row ->
                val rec = KbRecord(
                    id = row[KbRecords.id],
                    caseId = row[KbRecords.caseId],
                    signature = row[KbRecords.signature],
                    presetId = row[KbRecords.presetId],
                    presetLabel = row[KbRecords.presetLabel],
                    primaryAxis = row[KbRecords.primaryAxis],
                    configJson = row[KbRecords.config],
                    kpisSnapshotJson = row[KbRecords.kpisSnapshot],
                    sourcePlanRunId = row[KbRecords.sourcePlanRunId],
                    sourcePlanRunDeleted = row[KbRecords.sourcePlanRunDeleted],
                    soundnessStatus = row[KbRecords.soundnessStatus],
                )
                rec.signature to rec
            }
    }

    /** Mark all KB records sourced from this plan_run as having lost their
     *  source. Called from the plan_run delete handler so KB rows survive. */
    fun markPlanRunDeleted(planRunId: Int) = transaction {
        KbRecords.update({ KbRecords.sourcePlanRunId eq planRunId }) {
            it[KbRecords.sourcePlanRunDeleted] = true
            it[KbRecords.sourcePlanRunId] = null
            it[KbRecords.updatedAt] = Clock.System.now()
        }
    }

    /** Hard-delete a KB record. Returns true if a row was removed. */
    fun deleteRecord(caseId: Int, recordId: Int): Boolean = transaction {
        KbRecords.deleteWhere { (KbRecords.caseId eq caseId) and (KbRecords.id eq recordId) } > 0
    }
}
