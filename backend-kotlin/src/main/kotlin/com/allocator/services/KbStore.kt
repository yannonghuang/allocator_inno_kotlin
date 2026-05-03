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

    /** Stats returned by [resyncForCase]. */
    data class ResyncStats(
        val candidateRuns: Int,    // sound + successful plan_runs scanned
        val uniqueSignatures: Int, // distinct signatures among the candidates
        val added: Int,            // new kb_records created (signature absent before)
        val skipped: Int,          // signatures already in KB (no-op)
        val totalAfter: Int,       // kb_record row count after the resync
    )

    /**
     * One-off sync: walk every status='success' + soundness='sound' plan_run
     * for this case, dedup by signature (pick the latest plan_run per
     * signature), and upsert any signatures missing from kb_records. Same
     * gating as [backfillForCase] — KB only stores sound runs. Idempotent.
     * Use when KB has drifted out of sync (e.g. older chat-driven runs
     * that never auto-saved before the hook landed).
     */
    fun resyncForCase(caseId: Int): ResyncStats = transaction {
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
        // Dedup candidates by signature; keep the latest plan_run id per
        // signature so the kb_record reflects the most recent execution
        // (KPIs, override snapshot, soundness state).
        val sigToLatestRun = mutableMapOf<String, Int>()
        for (row in candidateRuns) {
            val configRaw = row[PlanRuns.config] ?: continue
            val configJson = runCatching { json.parseToJsonElement(configRaw).jsonObject }
                .getOrNull() ?: continue
            val sig = CaseBootstrap.signatureFor(configJson)
            val rid = row[PlanRuns.id]
            sigToLatestRun.merge(sig, rid) { existing, candidate -> maxOf(existing, candidate) }
        }
        var added = 0
        var skipped = 0
        for ((sig, runId) in sigToLatestRun) {
            if (sig in existingSigs) {
                skipped++
                continue
            }
            if (upsertFromPlanRun(runId) != null) added++
        }
        val after = KbRecords.selectAll().where { KbRecords.caseId eq caseId }.count().toInt()
        ResyncStats(
            candidateRuns = candidateRuns.size,
            uniqueSignatures = sigToLatestRun.size,
            added = added,
            skipped = skipped,
            totalAfter = after,
        )
    }

    /** Stats returned by [cleanForCase]. */
    data class CleanStats(
        val kbBefore: Int,
        val orphanedKept: Int,  // rows with no live source plan_run — preserved (durable testimony)
        val refreshed: Int,     // rows whose source plan_run is gone but still sound+success exists → re-sourced
        val deleted: Int,       // rows whose source is currently NOT sound+success AND no other sound source exists
        val kbAfter: Int,
    )

    /**
     * Removal pass: enforce trustworthiness without violating KB dissociation.
     * Each KB row falls into one of these buckets:
     *
     *   - **Orphaned** (`source_plan_run_id IS NULL`): the source plan_run
     *     was deleted at some point. KB is dissociated from plan_run
     *     lifecycle, so we KEEP these rows — they're durable testimony
     *     captured at the moment the source was sound. Counted as
     *     `orphanedKept`.
     *
     *   - **Currently sound** (source plan_run exists AND status='success'
     *     AND soundness='sound'): the row is trustworthy. KEEP, no-op.
     *
     *   - **Stale source, refreshable**: the source plan_run is no longer
     *     sound+success (status changed or soundness re-check failed), but
     *     ANOTHER sound+success plan_run with the same signature exists.
     *     Re-upsert from that canonical run so the row reflects current
     *     trusted state. Counted as `refreshed`.
     *
     *   - **Stale source, not refreshable**: source is no longer sound+
     *     success, AND no other sound+success run shares the signature.
     *     The row's testimony is no longer trustworthy by current rules.
     *     DELETE. Counted as `deleted`.
     *
     * Pair with [resyncForCase] for full reconcile: resync adds missing,
     * clean removes stale (without touching orphans).
     */
    fun cleanForCase(caseId: Int): CleanStats = transaction {
        val before = KbRecords.selectAll().where { KbRecords.caseId eq caseId }.count().toInt()

        // Build map: signature → latest sound+success plan_run_id (canonical source).
        val soundRuns = PlanRuns.selectAll()
            .where {
                (PlanRuns.caseId eq caseId)
                    .and(PlanRuns.status eq "success")
                    .and(PlanRuns.soundnessStatus eq "sound")
            }
            .toList()
        val signatureToCanonicalRun = mutableMapOf<String, Int>()
        for (row in soundRuns) {
            val configRaw = row[PlanRuns.config] ?: continue
            val configJson = runCatching { json.parseToJsonElement(configRaw).jsonObject }
                .getOrNull() ?: continue
            val sig = CaseBootstrap.signatureFor(configJson)
            val rid = row[PlanRuns.id]
            signatureToCanonicalRun.merge(sig, rid) { existing, candidate -> maxOf(existing, candidate) }
        }

        var orphanedKept = 0
        var refreshed = 0
        var deleted = 0
        val kbRows = KbRecords.selectAll().where { KbRecords.caseId eq caseId }.toList()
        for (row in kbRows) {
            val kbId = row[KbRecords.id]
            val sig = row[KbRecords.signature]
            val sourceId = row[KbRecords.sourcePlanRunId]

            // Orphaned: no live source. Preserve — KB is dissociated.
            if (sourceId == null) {
                orphanedKept++
                continue
            }

            // Source still exists. Check its CURRENT trust status.
            val sourceRow = PlanRuns.selectAll()
                .where { PlanRuns.id eq sourceId }
                .firstOrNull()
            val sourceCurrentlySound = sourceRow != null &&
                sourceRow[PlanRuns.status] == "success" &&
                sourceRow[PlanRuns.soundnessStatus] == "sound"
            if (sourceCurrentlySound) {
                // Trustworthy — leave it.
                continue
            }

            // Source is gone or no longer sound+success. Try to refresh.
            val canonicalRunId = signatureToCanonicalRun[sig]
            if (canonicalRunId != null) {
                upsertFromPlanRun(canonicalRunId)
                refreshed++
            } else if (sourceRow == null) {
                // Source row missing entirely (stale source_id, plan_run was
                // deleted without markPlanRunDeleted firing). Convert to
                // orphan rather than delete — preserves the testimony.
                KbRecords.update({ KbRecords.id eq kbId }) {
                    it[KbRecords.sourcePlanRunId] = null
                    it[KbRecords.sourcePlanRunDeleted] = true
                    it[KbRecords.updatedAt] = Clock.System.now()
                }
                orphanedKept++
            } else {
                // Source still exists but is unsound, AND no other sound run
                // shares this signature. The KB testimony is no longer
                // trusted by current rules — drop.
                KbRecords.deleteWhere { KbRecords.id eq kbId }
                deleted++
            }
        }

        val after = KbRecords.selectAll().where { KbRecords.caseId eq caseId }.count().toInt()
        CleanStats(
            kbBefore = before,
            orphanedKept = orphanedKept,
            refreshed = refreshed,
            deleted = deleted,
            kbAfter = after,
        )
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

    // ── Query / Pareto frontier (planning-agent KB tools) ────────────────────
    //
    // Pure-function variants (applyFilterAndSort, paretoFrontierPure) are
    // exposed as internal so they can be unit-tested without a DB fixture.

    /** Filter for [query] / [paretoFrontier]. Each non-null field is a constraint. */
    data class KbQueryFilter(
        val soundnessStatus: String? = null,
        val minFillRate: Double? = null,
        val maxGini: Double? = null,
        val minP10Fill: Double? = null,
        val primaryAxis: String? = null,
        val presetId: String? = null,
    )

    enum class KbSortBy {
        FILL_RATE_DESC, GINI_ASC, P10_FILL_DESC, STARVATION_ASC, NEWEST_FIRST
    }

    /** KPI keys agents are allowed to reference in maximize/minimize lists. */
    val KPI_ALLOWLIST: Set<String> = setOf(
        "fill_rate_pct", "gini", "p10_fill_ratio", "median_fill_ratio",
        "starvation_pct", "on_time_count", "total_committed", "total_requested",
        "manufacturing_total_quantity", "inventory_consumed_total",
    )

    data class KbQueryResult(val rows: List<KbRecord>, val totalInKb: Int)
    data class ParetoResult(
        val frontier: List<KbRecord>,
        val totalInKb: Int,
        val errors: List<String>,
    )

    /** Parse a KB record's KPI snapshot JSON into a flat numeric map. */
    internal fun parseKpiSnapshot(snapshotJson: String): Map<String, Double> {
        if (snapshotJson.isBlank()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(snapshotJson).jsonObject }.getOrNull()
            ?: return emptyMap()
        val out = mutableMapOf<String, Double>()
        for ((k, v) in obj) {
            val n = (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: continue
            out[k] = n
        }
        return out
    }

    /** Pure filter+sort over an in-memory KB record list. */
    internal fun applyFilterAndSort(
        records: List<KbRecord>,
        filter: KbQueryFilter,
        sortBy: KbSortBy,
        limit: Int,
    ): List<KbRecord> {
        val parsed = records.map { it to parseKpiSnapshot(it.kpisSnapshotJson) }
        val filtered = parsed.filter { (r, k) ->
            if (filter.soundnessStatus != null && r.soundnessStatus != filter.soundnessStatus) return@filter false
            if (filter.primaryAxis != null && r.primaryAxis != filter.primaryAxis) return@filter false
            if (filter.presetId != null && r.presetId != filter.presetId) return@filter false
            if (filter.minFillRate != null) {
                val v = k["fill_rate_pct"] ?: return@filter false
                if (v < filter.minFillRate) return@filter false
            }
            if (filter.maxGini != null) {
                val v = k["gini"] ?: return@filter false
                if (v > filter.maxGini) return@filter false
            }
            if (filter.minP10Fill != null) {
                val v = k["p10_fill_ratio"] ?: return@filter false
                if (v < filter.minP10Fill) return@filter false
            }
            true
        }
        val sorted = when (sortBy) {
            KbSortBy.FILL_RATE_DESC -> filtered.sortedByDescending { it.second["fill_rate_pct"] ?: Double.NEGATIVE_INFINITY }
            KbSortBy.GINI_ASC       -> filtered.sortedBy { it.second["gini"] ?: Double.POSITIVE_INFINITY }
            KbSortBy.P10_FILL_DESC  -> filtered.sortedByDescending { it.second["p10_fill_ratio"] ?: Double.NEGATIVE_INFINITY }
            KbSortBy.STARVATION_ASC -> filtered.sortedBy { it.second["starvation_pct"] ?: Double.POSITIVE_INFINITY }
            KbSortBy.NEWEST_FIRST   -> filtered.sortedByDescending { it.first.id }
        }
        return sorted.take(limit.coerceAtLeast(1)).map { it.first }
    }

    /** Pure Pareto frontier over an in-memory list. Drops records missing any
     *  requested KPI; for the rest, returns non-dominated points sorted by the
     *  first maximize axis desc (or first minimize axis asc when no maximize). */
    internal fun paretoFrontierPure(
        records: List<KbRecord>,
        maximize: List<String>,
        minimize: List<String>,
    ): List<KbRecord> {
        if (records.isEmpty()) return emptyList()
        if (maximize.isEmpty() && minimize.isEmpty()) return records.toList()
        val parsed = records.map { it to parseKpiSnapshot(it.kpisSnapshotJson) }
            .filter { (_, k) ->
                maximize.all { k.containsKey(it) } && minimize.all { k.containsKey(it) }
            }
        val frontier = mutableListOf<Pair<KbRecord, Map<String, Double>>>()
        for ((a, ka) in parsed) {
            var dominated = false
            for ((b, kb) in parsed) {
                if (a.id == b.id) continue
                val maxOk = maximize.all { (kb[it] ?: 0.0) >= (ka[it] ?: 0.0) }
                val minOk = minimize.all { (kb[it] ?: 0.0) <= (ka[it] ?: 0.0) }
                val strict = maximize.any { (kb[it] ?: 0.0) > (ka[it] ?: 0.0) } ||
                    minimize.any { (kb[it] ?: 0.0) < (ka[it] ?: 0.0) }
                if (maxOk && minOk && strict) {
                    dominated = true
                    break
                }
            }
            if (!dominated) frontier.add(a to ka)
        }
        return when {
            maximize.isNotEmpty() -> frontier.sortedByDescending { it.second[maximize[0]] ?: Double.NEGATIVE_INFINITY }
            else -> frontier.sortedBy { it.second[minimize[0]] ?: Double.POSITIVE_INFINITY }
        }.map { it.first }
    }

    /** Parametric query over the case's KB. Returns matching rows + total KB size. */
    fun query(caseId: Int, filter: KbQueryFilter, sortBy: KbSortBy, limit: Int): KbQueryResult {
        val all = listForCase(caseId).values.toList()
        val out = applyFilterAndSort(all, filter, sortBy, limit)
        return KbQueryResult(rows = out, totalInKb = all.size)
    }

    /** Pareto frontier over the case's KB. Validates KPI names against the
     *  allowlist; returns errors instead of throwing so the agent can recover. */
    fun paretoFrontier(
        caseId: Int,
        maximize: List<String>,
        minimize: List<String>,
        prefilter: KbQueryFilter,
    ): ParetoResult {
        val errors = mutableListOf<String>()
        for (k in maximize) if (k !in KPI_ALLOWLIST) errors.add("unknown KPI in maximize: $k")
        for (k in minimize) if (k !in KPI_ALLOWLIST) errors.add("unknown KPI in minimize: $k")
        if (maximize.isEmpty() && minimize.isEmpty()) {
            errors.add("must specify at least one of maximize or minimize")
        }
        if (errors.isNotEmpty()) return ParetoResult(emptyList(), 0, errors)
        val all = listForCase(caseId).values.toList()
        val filtered = applyFilterAndSort(all, prefilter, KbSortBy.NEWEST_FIRST, Int.MAX_VALUE)
        val frontier = paretoFrontierPure(filtered, maximize, minimize)
        return ParetoResult(frontier = frontier, totalInKb = all.size, errors = emptyList())
    }
}
