package com.allocator.services

import com.allocator.PlanRuns
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Pre-built planning-config presets that seed a case's "knowledge base" — a
 * curated set of plan runs the planning agent uses as comparative baselines
 * when answering "what config should I use?" / "why is X better than Y?"
 * before the user has done any of their own experiments.
 *
 * Bootstrap is invoked from the planning page in batches of 5–6 (chunk size
 * matches the natural compute budget — case-171 takes ~2 min/run, so a
 * chunk is a coffee-break commitment, not a half-hour wait). Each click
 * runs the next-unrun configs from the library; multiple clicks accumulate
 * coverage. After the library is exhausted, the user can ask the agent for
 * ad-hoc explorations.
 *
 * Library design: each preset varies one load-bearing knob off a clean
 * baseline (preference, max=1, leaf-only, fair, no purchase, consolidation
 * on). Generation 1 covers the baseline + max-method sweep + regulation
 * scope; generation 2 covers allocation modes + consolidation + first
 * elaborate; generation 3 fills the elaborate weight space + combined
 * variants. The agent's evidence-grounding tactic depends on having pairs
 * that differ in exactly one knob, so the matrix is intentionally
 * single-axis.
 *
 * Persistence: each plan_run produced by bootstrap carries
 * `metadata.bootstrap = true` along with `preset_id` / `preset_label` /
 * `preset_index` / `generation`, so the agent and UI can filter on or
 * rank by bootstrap origin without a schema change.
 */
data class BootstrapPreset(
    /** Stable identifier; matches against existing plan_runs to detect duplicates. */
    val presetId: String,
    /** Human-readable label; copied into plan_run.name and run history badges. */
    val label: String,
    /** 1-based ordering used as a within-axis tiebreaker. */
    val index: Int,
    /** Primary algorithmic axis this preset varies. Used by the selection
     *  algorithm to round-robin across axes so each chunk spans the
     *  algorithmic dimensions evenly rather than dumping 5 max-method
     *  variations in one click. */
    val primaryAxis: String,
    /** Full PlanningConfig as a JsonObject — exactly what would be POSTed to
     *  /cases/{id}/plan if a user submitted this manually. */
    val config: JsonObject,
)

/** Build a config JsonObject from the few axes we actually vary. Anything not
 *  listed below gets the system default. */
private fun cfg(
    mode: String = "preference",
    maxMethods: Int = 1,
    depth: Int = 1,
    weights: Triple<Double, Double, Double>? = null,   // (commit, inventory, purchase)
    engine: String = "leaf-legacy",                    // "leaf-legacy" | "supply"
    allocationMode: String = "fair",                   // "fair" | "proportional" | "priority_first"
    consolidationEnabled: Boolean = true,
    purchaseAllowed: Boolean = false,
): JsonObject = buildJsonObject {
    putJsonObject("method_selection") {
        put("mode", mode)
        put("depth", depth)
        put("multiple", false)
        put("elaborate", mode == "elaborate")
        put("max_methods", maxMethods)
        put("depth_optimal", false)
        if (weights != null) {
            putJsonObject("score_weights") {
                put("commit_time", weights.first)
                put("inventory_consumed", weights.second)
                put("purchase", weights.third)
            }
        } else {
            // default weights: balanced
            putJsonObject("score_weights") {
                put("commit_time", 0.4)
                put("inventory_consumed", 0.35)
                put("purchase", 0.25)
            }
        }
    }
    putJsonObject("consolidation") {
        put("enabled", consolidationEnabled)
        put("period_days", 0)
        put("allocation_mode", allocationMode)
        put("engine", engine)
    }
    putJsonObject("variant_selection") {
        put("multiple", true)
    }
    put("purchase_allowed", purchaseAllowed)
    put("analyze_criticality", false)
    put("check_soundness", true)
}

object CaseBootstrap {

    // ── Algorithmic axis labels ───────────────────────────────────────────────
    // Each preset belongs to one primary axis. The selection algorithm
    // round-robins across axes so a chunk spans the dimensions evenly.
    private const val AXIS_BASELINE   = "baseline"
    private const val AXIS_MAX        = "max_methods"
    private const val AXIS_SCOPE      = "regulation_scope"
    private const val AXIS_ALLOC      = "allocation_mode"
    private const val AXIS_CONSOLID   = "consolidation"
    private const val AXIS_PURCHASE   = "purchase"
    private const val AXIS_ELABORATE  = "elaborate"

    /** The canonical library. Order within an axis = priority within that axis.
     *  Append-only — bootstrap-tagged plan_runs reference these presetIds, so
     *  renaming a presetId would invalidate match-up against historical KB. */
    val LIBRARY: List<BootstrapPreset> = listOf(
        BootstrapPreset("baseline",          "baseline",           1, AXIS_BASELINE,  cfg(maxMethods = 1)),
        BootstrapPreset("max=2",             "max=2",              2, AXIS_MAX,       cfg(maxMethods = 2)),
        BootstrapPreset("max=3",             "max=3",              3, AXIS_MAX,       cfg(maxMethods = 3)),
        BootstrapPreset("max=4",             "max=4",              4, AXIS_MAX,       cfg(maxMethods = 4)),
        BootstrapPreset("all-levels",        "all-levels",         5, AXIS_SCOPE,     cfg(maxMethods = 2, engine = "supply")),
        BootstrapPreset("priority-first",    "priority-first",     6, AXIS_ALLOC,     cfg(maxMethods = 2, allocationMode = "priority_first")),
        BootstrapPreset("proportional",      "proportional",       7, AXIS_ALLOC,     cfg(maxMethods = 2, allocationMode = "proportional")),
        BootstrapPreset("no-consolidation",  "no-consolidation",   8, AXIS_CONSOLID,  cfg(maxMethods = 2, consolidationEnabled = false)),
        BootstrapPreset("purchase-on",       "purchase-on",        9, AXIS_PURCHASE,  cfg(maxMethods = 2, purchaseAllowed = true)),
        BootstrapPreset("elaborate-commit",  "elaborate-commit",  10, AXIS_ELABORATE, cfg(mode = "elaborate", maxMethods = 2, weights = Triple(1.0, 0.0, 0.0))),
        BootstrapPreset("elaborate-inventory","elaborate-inventory",11,AXIS_ELABORATE, cfg(mode = "elaborate", maxMethods = 2, weights = Triple(0.0, 1.0, 0.0))),
        BootstrapPreset("elaborate-purchase","elaborate-purchase",12, AXIS_ELABORATE, cfg(mode = "elaborate", maxMethods = 2, weights = Triple(0.0, 0.0, 1.0))),
        BootstrapPreset("elaborate-balanced","elaborate-balanced",13, AXIS_ELABORATE, cfg(mode = "elaborate", maxMethods = 2)),
        BootstrapPreset("elaborate-depth-2", "elaborate-depth-2", 14, AXIS_ELABORATE, cfg(mode = "elaborate", maxMethods = 2, depth = 2)),
        BootstrapPreset("elaborate-all-levels","elaborate-all-levels",15, AXIS_ELABORATE, cfg(mode = "elaborate", maxMethods = 2, engine = "supply")),
    )

    /**
     * Return the next batch of presets to run for this case.
     *
     * Strategy:
     *   1. Filter library to presets NOT yet covered (matched against
     *      every plan_run for this case — bootstrap-tagged AND user-driven —
     *      via config signature, see [signatureFor]).
     *   2. Round-robin across primary axes so each chunk spans the
     *      algorithmic dimensions evenly. If the user clicks bootstrap once
     *      and gets 5 max-method variations, that's bad coverage — they'd
     *      learn nothing about scope, allocation, elaborate, etc. With
     *      round-robin, click 1 covers 5 different axes.
     *   3. Within an axis, pick by [index] (lowest first).
     *   4. If axes run out, fill remaining slots from any remaining presets.
     *
     * Empty result = library exhausted for this case.
     */
    fun selectNextBatch(caseId: Int, batchSize: Int = 5): List<BootstrapPreset> {
        val coveredSignatures = listCoveredSignatures(caseId)
        val candidates = LIBRARY.filter { signatureFor(it.config) !in coveredSignatures }
        if (candidates.isEmpty()) return emptyList()

        // Group remaining by axis, sorted within each axis by index.
        val byAxis = candidates.groupBy { it.primaryAxis }
            .mapValues { (_, list) -> list.sortedBy { it.index }.toMutableList() }
        // Visit axes in canonical order (so the chunk's first pick is always a
        // baseline-ish reference if available).
        val axisOrder = listOf(
            AXIS_BASELINE, AXIS_MAX, AXIS_SCOPE, AXIS_ALLOC,
            AXIS_CONSOLID, AXIS_PURCHASE, AXIS_ELABORATE,
        )
        val picked = mutableListOf<BootstrapPreset>()
        // Pass 1: one preset per axis, round-robin.
        for (axis in axisOrder) {
            if (picked.size >= batchSize) break
            byAxis[axis]?.removeFirstOrNull()?.let { picked += it }
        }
        // Pass 2: backfill from any remaining axis's leftovers (axis order again).
        if (picked.size < batchSize) {
            val leftover = axisOrder.flatMap { byAxis[it].orEmpty() }
            for (p in leftover) {
                if (picked.size >= batchSize) break
                picked += p
            }
        }
        return picked
    }

    /** Signatures considered "covered" for next-batch dedup. Two sources:
     *
     *   1. kb_records — the dissociated KB store. A row here means we have a
     *      KPI snapshot regardless of whether the source plan_run still exists.
     *   2. Non-failed plan_runs — user-driven runs that match a preset
     *      signature should also dedup (don't re-queue what the user already
     *      tried manually) even if they haven't been promoted to a kb_record.
     *
     *  Failed plan_runs do NOT count: bootstrap should retry them next click.
     */
    private fun listCoveredSignatures(caseId: Int): Set<String> = transaction {
        val planRunSigs = PlanRuns.selectAll()
            .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status neq "failed") }
            .toList()
            .mapNotNull { row ->
                val configRaw = row[PlanRuns.config] ?: return@mapNotNull null
                val cfg = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(configRaw).jsonObject }
                    .getOrNull() ?: return@mapNotNull null
                signatureFor(cfg)
            }
        val kbSigs = com.allocator.KbRecords.selectAll()
            .where { com.allocator.KbRecords.caseId eq caseId }
            .map { it[com.allocator.KbRecords.signature] }
        (planRunSigs + kbSigs).toSet()
    }

    /** Canonical config signature: a stable string of the load-bearing axes.
     *  Two configs share a signature iff they would produce the same plan
     *  (modulo non-config inputs like inventory state). Defaults match the
     *  system defaults so old plan_runs without a particular field still
     *  hash consistently. */
    fun signatureFor(config: JsonObject): String {
        val ms = (config["method_selection"] as? JsonObject) ?: JsonObject(emptyMap())
        val cs = (config["consolidation"] as? JsonObject) ?: JsonObject(emptyMap())
        val sw = (ms["score_weights"] as? JsonObject) ?: JsonObject(emptyMap())
        val mode = ms["mode"]?.toString()?.trim('"') ?: "preference"
        val maxM = ms["max_methods"]?.toString()?.trim('"') ?: "1"
        val depth = ms["depth"]?.toString()?.trim('"') ?: "1"
        val depthOpt = ms["depth_optimal"]?.toString()?.trim('"') ?: "false"
        val wC = sw["commit_time"]?.toString() ?: "0.4"
        val wI = sw["inventory_consumed"]?.toString() ?: "0.35"
        val wP = sw["purchase"]?.toString() ?: "0.25"
        val engine = cs["engine"]?.toString()?.trim('"') ?: "leaf-legacy"
        val alloc = cs["allocation_mode"]?.toString()?.trim('"') ?: "fair"
        val consEnabled = cs["enabled"]?.toString()?.trim('"') ?: "true"
        val period = cs["period_days"]?.toString()?.trim('"') ?: "0"
        val purch = config["purchase_allowed"]?.toString()?.trim('"') ?: "false"
        return "m=$mode|max=$maxM|d=$depth|dopt=$depthOpt|w=$wC,$wI,$wP|" +
            "eng=$engine|alloc=$alloc|cons=$consEnabled|p=$period|purch=$purch"
    }

    /** Wrap a preset's metadata bundle for plan_run.metadata. The signature is
     *  also captured here as an explicit field so a future KB-query tool can
     *  filter by signature without re-parsing every plan_run.config; bootstrap
     *  selection itself doesn't use it (it computes signatures on-the-fly so
     *  user-driven runs without metadata.signature still match). */
    fun metadataFor(preset: BootstrapPreset): JsonObject = buildJsonObject {
        put("bootstrap", true)
        put("preset_id", preset.presetId)
        put("preset_label", preset.label)
        put("preset_index", preset.index)
        put("primary_axis", preset.primaryAxis)
        put("signature", signatureFor(preset.config))
    }

    /** Public-shape JSON for a preset (frontend confirm dialog). */
    fun toJson(preset: BootstrapPreset): JsonObject = buildJsonObject {
        put("preset_id", preset.presetId)
        put("preset_label", preset.label)
        put("preset_index", preset.index)
        put("primary_axis", preset.primaryAxis)
        put("config", preset.config)
    }

    /** Same as [toJson] but enriched with the matching KB record — id (for
     *  the KB delete endpoint), full KPI snapshot, soundness, and the source
     *  plan_run_id (or its tombstone). Reads from `kb_records` rather than
     *  `plan_runs`, so coverage survives plan_run deletion. */
    fun toJsonWithRun(preset: BootstrapPreset, kb: KbStore.KbRecord?): JsonObject = buildJsonObject {
        put("preset_id", preset.presetId)
        put("preset_label", preset.label)
        put("preset_index", preset.index)
        put("primary_axis", preset.primaryAxis)
        put("config", preset.config)
        put("library", true)
        if (kb != null) {
            put("kb_record_id", kb.id)
            put("soundness_status", kb.soundnessStatus)
            // Source plan_run pointer (may be null if the run was deleted —
            // sourcePlanRunDeleted=true distinguishes "never had one" from
            // "had one, gone now"). Frontend uses this for an optional
            // "go to plan run" affordance.
            kb.sourcePlanRunId?.let { put("plan_run_id", it) }
            put("source_plan_run_deleted", kb.sourcePlanRunDeleted)
            // Inline the KPI snapshot so the dialog doesn't need a second fetch.
            val kpis = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(kb.kpisSnapshotJson).jsonObject }
                .getOrNull() ?: buildJsonObject { }
            kpis.entries.forEach { (k, v) -> put(k, v) }
        }
    }

    /** JSON shape for an off-library KB record (config doesn't match any
     *  LIBRARY preset). Synthesizes preset-shaped fields from the kb_record
     *  itself so the frontend can render it in the same table. The `library`
     *  flag tells the UI to badge it differently. */
    private fun toJsonOffLibrary(kb: KbStore.KbRecord): JsonObject = buildJsonObject {
        // No library preset to anchor to; use the source plan_run's name
        // (carried in kb.presetLabel for bootstrap-tagged rows, null otherwise)
        // or a truncated signature suffix as a deterministic fallback.
        val displayLabel = kb.presetLabel
            ?: ("custom-" + kb.signature.takeLast(6))
        put("preset_id", kb.presetId ?: kb.signature)
        put("preset_label", displayLabel)
        put("preset_index", -1)
        put("primary_axis", kb.primaryAxis ?: "user-driven")
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(kb.configJson).jsonObject }
            .getOrNull()?.let { put("config", it) }
        put("library", false)
        put("kb_record_id", kb.id)
        put("soundness_status", kb.soundnessStatus)
        kb.sourcePlanRunId?.let { put("plan_run_id", it) }
        put("source_plan_run_deleted", kb.sourcePlanRunDeleted)
        val kpis = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(kb.kpisSnapshotJson).jsonObject }
            .getOrNull() ?: buildJsonObject { }
        kpis.entries.forEach { (k, v) -> put(k, v) }
    }

    /** Whole-KB status snapshot for a case: every KB record (library + off-
     *  library) plus the next batch of uncovered library presets. Calls
     *  KbStore.backfillForCase first so historical successful + sound runs
     *  surface even if they predate the kb_records table.
     *
     *  - already_run         : ALL KB records (library and user-driven configs).
     *  - already_run_count   : library-aligned subset (preserved for the
     *                          "expand library coverage" hint).
     *  - kb_record_count     : total KB rows on this case.
     *  - next_batch          : library presets not yet covered.
     */
    fun statusFor(caseId: Int, batchSize: Int = 5): JsonObject {
        KbStore.backfillForCase(caseId)
        val kbRecordsBySig = KbStore.listForCase(caseId)
        val librarySigs = LIBRARY.associateBy { signatureFor(it.config) }
        val coveredPresets = LIBRARY.filter { signatureFor(it.config) in kbRecordsBySig.keys }
        val offLibraryRecords = kbRecordsBySig.filterKeys { sig -> sig !in librarySigs.keys }.values
        val nextBatch = selectNextBatch(caseId, batchSize)
        return buildJsonObject {
            put("library_size", LIBRARY.size)
            put("already_run_count", coveredPresets.size)
            put("remaining_count", LIBRARY.size - coveredPresets.size)
            put("kb_record_count", kbRecordsBySig.size)
            put("batch_size", batchSize)
            putJsonArray("already_run") {
                coveredPresets.forEach { preset ->
                    val sig = signatureFor(preset.config)
                    add(toJsonWithRun(preset, kbRecordsBySig[sig]))
                }
                offLibraryRecords.forEach { add(toJsonOffLibrary(it)) }
            }
            putJsonArray("next_batch") {
                nextBatch.forEach { add(toJson(it)) }
            }
        }
    }
}
