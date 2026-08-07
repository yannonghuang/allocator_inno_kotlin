package com.allocator.services

import com.allocator.PlanRuns
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 * Two independent producers populate this library-based system today:
 *   1. The planning AGENT's own `suggest_next_batch` tool (`PlanningAgentRoutes.kt`) and
 *      `KbStore`'s `novelOnly` auto-seeding branch — both call [selectNextBatch] directly,
 *      round-robining across [AXIS_CATALOG] off the case's current best run.
 *   2. The human-facing "Knowledge Base" dialog, which — as of the seeding redesign — no longer
 *      uses this round-robin system at all; see [SeedForm]/[generateNetNewBatch] for its own,
 *      deliberately different approach (one fixed config + a full max_methods sweep over a
 *      user-given range, actually consulting the case's real external-config state). The two
 *      systems share [signatureFor]/[signatureForBootstrapCandidate] but use DIFFERENT dedup
 *      sets — round-robin's own [selectNextBatch] checks plan_run history too, while the seeding
 *      dialog checks only the KB record set (see [generateNetNewBatch]'s own doc for why).
 *
 * Library design: each preset varies one load-bearing knob off a clean
 * baseline (preference, max=1, leaf-only, fair, no purchase, consolidation
 * on). Generation 1 covers the baseline + max-method sweep; generation 2
 * covers allocation modes + consolidation. (Elaborate/scored method
 * selection and its max_bom_depth admission cap were retired — the planner
 * is preference-ranked waterfall only, so those axes no longer produce a
 * behavioral difference and were dropped from the library.) The agent's
 * evidence-grounding tactic depends on having pairs that differ in exactly
 * one knob, so the matrix is intentionally single-axis.
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
 *  listed below gets the system default. The defaults define the baseline. */
private fun cfg(
    mode: String = "preference",
    maxMethods: Int = 1,
    depth: Int = 1,
    maxBomDepth: Int = 3,                              // make-fallback admission cap (default 3)
    weights: Triple<Double, Double, Double>? = null,   // (commit, inventory, purchase)
    /** null (default/baseline): don't emit make/move/purchase_batch_scale at all, relying on the
     *  backend's own default-to-"weekly" — byte-identical to every preset before consolidation's
     *  `enabled` flag was removed. Non-null: emit all three batch-scale keys set to this value —
     *  used by the "consolidation=off" preset (batchScale = "none"), consolidation's only real
     *  per-type on/off control now. */
    batchScale: String? = null,
    periodDays: Int = 30,
    purchaseAllowed: Boolean = false,
): JsonObject = buildJsonObject {
    putJsonObject("method_selection") {
        put("mode", mode)
        put("depth", depth)
        put("multiple", false)
        put("elaborate", mode == "elaborate")
        put("max_methods", maxMethods)
        put("max_bom_depth", maxBomDepth)
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
        if (batchScale != null) {
            put("make_batch_scale", batchScale)
            put("move_batch_scale", batchScale)
            put("purchase_batch_scale", batchScale)
        }
        put("period_days", periodDays)
    }
    putJsonObject("variant_selection") {
        put("multiple", true)
    }
    put("purchase_allowed", purchaseAllowed)
    put("analyze_criticality", false)
    put("check_soundness", true)
}

/** Build a config JsonObject for one KB-SEEDING run (the human-facing dialog's own generator —
 *  see [CaseBootstrap.SeedForm]/[CaseBootstrap.generateNetNewBatch]): the seeding form's fixed
 *  knobs plus one swept [maxMethods] value. Mirrors exactly what the Planning page's
 *  own manual-run form submits (mode=preference, depth=1, leaf-only, balanced weights) — those
 *  axes were never exposed on that form either, so seeding doesn't expose them now. Distinct
 *  from [cfg] (the round-robin library/agent-tool's own config builder, which varies one of
 *  several DIFFERENT axes off a baseline) — this one only ever varies max_methods. */
private fun seedCfg(
    maxMethods: Int,
    purchaseAllowed: Boolean,
    makeBatchScale: String,
    moveBatchScale: String,
    purchaseBatchScale: String,
    analyzeCriticality: Boolean,
    checkSoundness: Boolean,
    /** Default true — matches the Planning page's own default. NOTE: [maxMethods] only has any
     *  effect when this is false (same "disabled while root_waterfall is true" relationship the
     *  Planning page's own form enforces) — a sweep over [maxMethods] with this left true
     *  produces byte-identical presets. */
    rootWaterfall: Boolean = true,
    equalSplitRawMaterials: Boolean = false,
    horizonStart: String? = null,
    horizonEnd: String? = null,
    /** Per-lot ASC override: supply_id -> date. Each "wip"-dated lot has its own independent
     *  readiness schedule (see PlanningEngine.kt's ASC section doc) — applied uniformly to every
     *  preset in this seeding batch, same as [horizonStart]. */
    wipSupplyDates: Map<String, String> = emptyMap(),
): JsonObject = buildJsonObject {
    putJsonObject("method_selection") {
        put("mode", "preference")
        put("depth", 1)
        put("multiple", false)
        put("elaborate", false)
        put("max_methods", maxMethods)
        put("max_bom_depth", 3)
        put("root_waterfall", rootWaterfall)
        put("raw_material_sourcing", if (equalSplitRawMaterials) "equal_split" else "waterfall")
        if (horizonStart != null) put("horizon_start", horizonStart)
        if (horizonEnd != null) put("horizon_end", horizonEnd)
        putJsonObject("score_weights") {
            put("commit_time", 0.4)
            put("inventory_consumed", 0.35)
            put("purchase", 0.25)
        }
    }
    if (wipSupplyDates.isNotEmpty()) {
        putJsonObject("app_specific_config") {
            putJsonObject("wip_supply_dates") {
                wipSupplyDates.forEach { (sid, date) -> put(sid, date) }
            }
        }
    }
    putJsonObject("consolidation") {
        put("make_batch_scale", makeBatchScale)
        put("move_batch_scale", moveBatchScale)
        put("purchase_batch_scale", purchaseBatchScale)
        // Single shared window derived from the "make" scale — same "last-touched-ish" fallback
        // convention the Planning page's own WO-batch-frequency selects already use.
        put("period_days", seedBatchScaleDays(makeBatchScale))
    }
    putJsonObject("variant_selection") {
        put("multiple", true)
    }
    put("purchase_allowed", purchaseAllowed)
    put("analyze_criticality", analyzeCriticality)
    put("check_soundness", checkSoundness)
}

private fun seedBatchScaleDays(scale: String): Int = when (scale) {
    "weekly" -> 7
    "biweekly" -> 14
    "monthly" -> 30
    else -> 0  // "none" / "all" / unrecognized
}

object CaseBootstrap {

    // ── Algorithmic axis labels ───────────────────────────────────────────────
    // Each preset belongs to one primary axis. The selection algorithm
    // round-robins across axes so a chunk spans the dimensions evenly.
    private const val AXIS_BASELINE   = "baseline"
    private const val AXIS_MAX        = "max_methods"
    private const val AXIS_DEPTH      = "depth"
    private const val AXIS_CONSOLID   = "consolidation"
    private const val AXIS_PURCHASE   = "purchase"

    /**
     * The library — every entry is a **single-axis variation off the
     * baseline** (cfg() defaults: mode=preference, max_methods=1, depth=1,
     * leaf-only, fair, consolidation=on, period=0, purchase=off, balanced
     * weights). Each preset varies exactly ONE knob, so the planning agent
     * can compare any two presets and attribute the KPI delta unambiguously.
     *
     * No fixed cap — the library can grow as we add more single-axis
     * variations along an axis (e.g., more max_methods values, more
     * period_days, more weight points). Pre-existing kb_records that match a
     * library signature will surface in the dialog under their library label;
     * those whose configs have multi-axis variations land in the
     * off-library bucket (rendered uniformly as KB rows).
     *
     * Append-only — once a preset_id is shipped, renaming it would break
     * match-up against historical KB rows that referenced it via the
     * bootstrap metadata.
     */
    val LIBRARY: List<BootstrapPreset> = run {
        val presets = mutableListOf<BootstrapPreset>()
        var index = 1
        fun add(presetId: String, axis: String, config: JsonObject) {
            presets += BootstrapPreset(presetId, presetId, index++, axis, config)
        }

        // Baseline: zero-knob deviation from cfg() defaults — the anchor for diffs.
        add("baseline", AXIS_BASELINE, cfg())

        // max_methods axis: waterfall fallback breadth.
        for (n in listOf(2, 3, 4, 5, 6, 8)) add("max=$n", AXIS_MAX, cfg(maxMethods = n))

        // depth axis: planner recursion depth.
        for (d in listOf(2, 3, 4)) add("depth=$d", AXIS_DEPTH, cfg(depth = d))

        // Consolidation axis.
        add("consolidation=off", AXIS_CONSOLID, cfg(batchScale = "none"))
        for (p in listOf(7, 14, 30, 60)) add("period=$p", AXIS_CONSOLID, cfg(periodDays = p))

        // Purchase axis.
        add("purchase=on", AXIS_PURCHASE, cfg(purchaseAllowed = true))

        presets
    }

    /** The canonical baseline config — the anchor when KB has no successful
     *  runs yet. Once runs exist, suggestions vary one knob off of the
     *  case's *best* run instead (see [bestConfigFor]). */
    val BASELINE: JsonObject = cfg()

    /** Criteria the dialog can pick to drive suggestion seeding. */
    const val CRITERION_FILL_RATE = "fill_rate"
    const val CRITERION_FAIRNESS  = "fairness"
    const val CRITERION_PARETO    = "pareto"

    /** Find the case's "best" config to use as the suggestion seed, per the
     *  selected [criterion]. Falls back to BASELINE when no qualifying rows.
     *
     *  • fill_rate  — highest `fill_rate_pct`, tiebreak lowest `gini`.
     *  • fairness   — lowest `gini`, tiebreak highest `fill_rate_pct`.
     *  • pareto     — balanced winner: max (fill_rate_pct/100 - gini).
     */
    fun bestConfigFor(caseId: Int, criterion: String = CRITERION_FILL_RATE): JsonObject {
        val rows = transaction {
            com.allocator.KbRecords.selectAll()
                .where { (com.allocator.KbRecords.caseId eq caseId)
                    .and(com.allocator.KbRecords.soundnessStatus eq "sound") }
                .toList()
        }
        if (rows.isEmpty()) return BASELINE
        fun fill(row: org.jetbrains.exposed.sql.ResultRow): Double? {
            val kpis = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(row[com.allocator.KbRecords.kpisSnapshot]).jsonObject }.getOrNull()
            return kpis?.get("fill_rate_pct")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        }
        fun gini(row: org.jetbrains.exposed.sql.ResultRow): Double? {
            val kpis = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(row[com.allocator.KbRecords.kpisSnapshot]).jsonObject }.getOrNull()
            return kpis?.get("gini")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        }
        val best = when (criterion) {
            CRITERION_FAIRNESS -> rows.minWithOrNull(
                compareBy<org.jetbrains.exposed.sql.ResultRow> { gini(it) ?: Double.POSITIVE_INFINITY }
                    .thenByDescending { fill(it) ?: Double.NEGATIVE_INFINITY }
            )
            CRITERION_PARETO -> rows.maxWithOrNull(
                compareBy<org.jetbrains.exposed.sql.ResultRow> {
                    val f = fill(it) ?: Double.NEGATIVE_INFINITY
                    val g = gini(it) ?: 1.0
                    (f / 100.0) - g
                }
            )
            else -> rows.maxWithOrNull(
                compareBy<org.jetbrains.exposed.sql.ResultRow> { fill(it) ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { -(gini(it) ?: Double.POSITIVE_INFINITY) }
            )
        } ?: return BASELINE
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(best[com.allocator.KbRecords.config]).jsonObject
        }.getOrNull() ?: BASELINE
    }

    /** Produce a config = `seed` with one axis-knob overridden to [value].
     *  Mirrors [buildConfigForAxisValue] but takes a custom seed (not just
     *  the static cfg() baseline) so suggestions can vary off the current
     *  best instead. */
    private fun applyAxisToSeed(seed: JsonObject, axisName: String, value: JsonElement): JsonObject {
        val parsedValue: Any? = when {
            value is JsonPrimitive && value.isString -> value.content
            value is JsonPrimitive -> value.booleanOrNull
                ?: value.intOrNull
                ?: value.doubleOrNull
                ?: value.contentOrNull
            else -> null
        }
        // Deep-clone the seed via JSON round-trip (small configs; cheap).
        val cloned = kotlinx.serialization.json.Json.parseToJsonElement(seed.toString()).jsonObject
        return buildJsonObject {
            cloned.entries.forEach { (k, v) -> put(k, v) }
            val ms = (cloned["method_selection"] as? JsonObject) ?: buildJsonObject { }
            val cs = (cloned["consolidation"] as? JsonObject) ?: buildJsonObject { }
            when (axisName) {
                "max_methods" -> putJsonObject("method_selection") {
                    ms.entries.forEach { (k, v) -> if (k != "max_methods") put(k, v) }
                    put("max_methods", JsonPrimitive((parsedValue as? Number)?.toInt() ?: 1))
                }
                "depth" -> putJsonObject("method_selection") {
                    ms.entries.forEach { (k, v) -> if (k != "depth") put(k, v) }
                    put("depth", JsonPrimitive((parsedValue as? Number)?.toInt() ?: 1))
                }
                "consolidation_batch_scale" -> putJsonObject("consolidation") {
                    cs.entries.forEach { (k, v) ->
                        if (k !in setOf("make_batch_scale", "move_batch_scale", "purchase_batch_scale")) put(k, v)
                    }
                    val scale = JsonPrimitive((parsedValue as? String) ?: "weekly")
                    put("make_batch_scale", scale)
                    put("move_batch_scale", scale)
                    put("purchase_batch_scale", scale)
                }
                "period_days" -> putJsonObject("consolidation") {
                    cs.entries.forEach { (k, v) -> if (k != "period_days") put(k, v) }
                    put("period_days", JsonPrimitive((parsedValue as? Number)?.toInt() ?: 30))
                }
                "purchase_allowed" -> put("purchase_allowed", JsonPrimitive((parsedValue as? Boolean) ?: false))
            }
        }
    }

    // ── Axis specs (axis-level next-batch UX) ─────────────────────────────────
    // Each AxisSpec describes one knob the user can vary off the baseline,
    // along with metadata the dialog uses to render an editable per-axis
    // entry (value type, default seed, set of values the curated library
    // suggests). The submit handler still receives full configs; the
    // frontend reconstructs them from axis-value pairs against the baseline.

    /** Axis metadata for the dialog's axis-level next-batch view. */
    data class AxisSpec(
        val name: String,                  // canonical knob id, e.g. "max_methods"
        val label: String,                 // display label
        val description: String,           // short help text
        val valueType: String,             // "int" | "bool" | "enum"
        val enumValues: List<String>,      // populated for enum types
        val baselineValue: JsonElement,    // value at baseline (for the "varies from X" hint)
        val defaultSeed: JsonElement,      // suggested initial value when the user checks this axis
        val variations: List<JsonElement>, // values the curated library enumerates (datalist hints)
        /** Group id — axes in the same group are rendered under one header
         *  in the dialog and can be collapsed together. Groups capture
         *  logical dependencies: e.g. period_days is the supply-side bucket
         *  width that pairs with consolidation_batch_scale (the per-type
         *  WO-batch on/off + granularity control), so they share the
         *  "consolidation" group. */
        val group: String,
    )

    /** Group ids used by the dialog to cluster related axes under one
     *  collapsible header. Mental model: method_selection = supply-side
     *  strategy (how demands pick among methods), consolidation =
     *  demand-side strategy (how demands group + split constrained supply). */
    const val GROUP_METHOD     = "method_selection"
    const val GROUP_CONSOLID   = "consolidation"

    /** Static axis catalog. Order = priority order shown in the dialog;
     *  also defines the order axes appear within their group. */
    private val AXIS_CATALOG: List<AxisSpec> = listOf(
        // ── Method selection (supply side) ───────────────────────────────
        AxisSpec(
            name = "max_methods", label = "Max methods",
            description = "Waterfall fallback breadth. Try N methods before giving up on a demand.",
            valueType = "int", enumValues = emptyList(),
            baselineValue = JsonPrimitive(1),
            defaultSeed = JsonPrimitive(2),
            variations = listOf(2, 3, 4, 5, 6, 8).map { JsonPrimitive(it) },
            group = GROUP_METHOD,
        ),
        AxisSpec(
            name = "depth", label = "Depth",
            description = "Planner recursion depth.",
            valueType = "int", enumValues = emptyList(),
            baselineValue = JsonPrimitive(1),
            defaultSeed = JsonPrimitive(2),
            variations = listOf(2, 3, 4).map { JsonPrimitive(it) },
            group = GROUP_METHOD,
        ),
        // ── Consolidation cluster ────────────────────────────────────────
        // consolidation_batch_scale is the cluster's "primary" knob;
        // period_days is a sub-knob that lives nested under consolidation
        // in the planner config (see cfg()). Consolidation always runs now —
        // "none" is the real per-type off-switch, not a separate enabled flag.
        AxisSpec(
            name = "consolidation_batch_scale", label = "Consolidation batch scale",
            description = "Group demands sharing a component into fewer, larger work orders within a date window. \"none\" turns batching off.",
            valueType = "enum", enumValues = listOf("none", "weekly", "biweekly", "monthly", "all"),
            baselineValue = JsonPrimitive("weekly"),
            defaultSeed = JsonPrimitive("none"),
            variations = listOf(JsonPrimitive("none")),
            group = GROUP_CONSOLID,
        ),
        AxisSpec(
            name = "period_days", label = "Period (days)",
            description = "Consolidation window in days. 0 = collapse all dates into one bucket.",
            valueType = "int", enumValues = emptyList(),
            baselineValue = JsonPrimitive(0),
            defaultSeed = JsonPrimitive(7),
            variations = listOf(7, 14, 30, 60).map { JsonPrimitive(it) },
            group = GROUP_CONSOLID,
        ),
        // Purchase is a method-selection knob (it permits purchase orders
        // as a fulfillment method) — supply-side strategy, same as
        // max_methods / depth.
        AxisSpec(
            name = "purchase_allowed", label = "Allow purchase",
            description = "Permit purchase orders as a fulfillment method.",
            valueType = "bool", enumValues = emptyList(),
            baselineValue = JsonPrimitive(false),
            defaultSeed = JsonPrimitive(true),
            variations = listOf(JsonPrimitive(true)),
            group = GROUP_METHOD,
        ),
    )

    /** Build a full PlanningConfig from baseline + a single axis-value override. */
    fun buildConfigForAxisValue(axisName: String, value: JsonElement): JsonObject {
        val parsedValue: Any? = when {
            value is JsonPrimitive && value.isString -> value.content
            value is JsonPrimitive -> value.booleanOrNull
                ?: value.intOrNull
                ?: value.doubleOrNull
                ?: value.contentOrNull
            else -> null
        }
        return when (axisName) {
            "max_methods" -> cfg(maxMethods = (parsedValue as? Number)?.toInt() ?: 1)
            "depth" -> cfg(depth = (parsedValue as? Number)?.toInt() ?: 1)
            "consolidation_batch_scale" -> cfg(batchScale = (parsedValue as? String) ?: "weekly")
            "period_days" -> cfg(periodDays = (parsedValue as? Number)?.toInt() ?: 30)
            "purchase_allowed" -> cfg(purchaseAllowed = (parsedValue as? Boolean) ?: false)
            else -> cfg()  // unknown axis → baseline
        }
    }

    /** Compute axis specs for a case, with the [defaultSeed] overridden to
     *  the next uncovered variation (so the dialog points the user at fresh
     *  data first). When all variations are in KB, falls back to the static
     *  default seed — the user can still edit. */
    fun axisSpecsForCase(caseId: Int): List<AxisSpec> {
        val coveredSigs = listCoveredSignatures(caseId)
        return AXIS_CATALOG.map { spec ->
            val nextUncovered = spec.variations.firstOrNull { value ->
                signatureForBootstrapCandidate(buildConfigForAxisValue(spec.name, value), caseId) !in coveredSigs
            }
            if (nextUncovered != null) spec.copy(defaultSeed = nextUncovered) else spec
        }
    }

    /** Serialize an [AxisSpec] for the dialog's preview payload. */
    fun toJson(spec: AxisSpec): JsonObject = buildJsonObject {
        put("name", spec.name)
        put("label", spec.label)
        put("description", spec.description)
        put("value_type", spec.valueType)
        if (spec.enumValues.isNotEmpty()) {
            putJsonArray("enum_values") { spec.enumValues.forEach { add(JsonPrimitive(it)) } }
        }
        put("baseline_value", spec.baselineValue)
        put("default_seed", spec.defaultSeed)
        putJsonArray("variations") { spec.variations.forEach { add(it) } }
        put("group", spec.group)
    }

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
     *
     * Used by the planning agent's own `suggest_next_batch` tool
     * (`PlanningAgentRoutes.kt`) and `KbStore`'s `novelOnly` auto-seeding branch — NOT by the
     * human-facing "Knowledge Base" dialog anymore (see [generateNetNewBatch] for that).
     */
    fun selectNextBatch(caseId: Int, batchSize: Int = 5, criterion: String = CRITERION_FILL_RATE): List<BootstrapPreset> {
        // Single-knob variations off the case's CURRENT BEST config (per
        // [criterion]) or BASELINE on cold-start. For each (axis, value) in
        // AXIS_CATALOG we apply that knob to the seed and check the
        // resulting signature against KB; any duplicate is skipped at
        // suggestion time so the user only ever sees fresh runs.
        // Round-robin across axes for dimensional breadth.
        val seed = bestConfigFor(caseId, criterion)
        val seedSignature = signatureForBootstrapCandidate(seed, caseId)
        val coveredSignatures = listCoveredSignatures(caseId)

        val candidatesByAxis: MutableMap<String, MutableList<BootstrapPreset>> = linkedMapOf()
        var presetIdx = 1
        for (axis in AXIS_CATALOG) {
            for (value in axis.variations) {
                val config = applyAxisToSeed(seed, axis.name, value)
                val sig = signatureForBootstrapCandidate(config, caseId)
                if (sig == seedSignature) continue            // no-op vs seed
                if (sig in coveredSignatures) continue         // already in KB
                val valueLabel = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
                candidatesByAxis.getOrPut(axis.name) { mutableListOf() } += BootstrapPreset(
                    presetId    = "${axis.name}=$valueLabel",
                    label       = "${axis.label}: $valueLabel",
                    index       = presetIdx++,
                    primaryAxis = axis.name,
                    config      = config,
                )
            }
        }

        val picked = mutableListOf<BootstrapPreset>()
        val axisQueues = candidatesByAxis.values.toMutableList()
        while (picked.size < batchSize && axisQueues.any { it.isNotEmpty() }) {
            for (queue in axisQueues) {
                if (picked.size >= batchSize) break
                queue.removeFirstOrNull()?.let { picked += it }
            }
        }
        return picked
    }

    /** Signatures considered "covered" for the round-robin agent tool's next-batch dedup
     *  ([selectNextBatch]/[axisSpecsForCase] only — the seeding dialog uses a KB-only check
     *  instead, see [generateNetNewBatch]'s own doc for why). Two sources:
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
        // Live-compute kb_record signatures from the stored config rather than
        // trusting the cached signature column — the column was populated
        // under whatever signatureFor was at the time of insert; recomputing
        // on read makes the dedup robust to signatureFor changes (e.g.,
        // canonicalization fixes) without requiring a data migration.
        val kbSigs = com.allocator.KbRecords.selectAll()
            .where { com.allocator.KbRecords.caseId eq caseId }
            .mapNotNull { row ->
                val configRaw = row[com.allocator.KbRecords.config]
                val cfg = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(configRaw).jsonObject }
                    .getOrNull() ?: return@mapNotNull null
                signatureFor(cfg)
            }
        (planRunSigs + kbSigs).toSet()
    }

    /** Canonical config signature: a stable string of the load-bearing axes.
     *  Two configs share a signature iff they would produce the same plan
     *  (modulo non-config inputs like inventory state).
     *
     *  Type-aware parsing: numeric values are normalized via Int / Double
     *  parsers so `1` and `1.0` produce the same signature. Booleans go
     *  through `booleanOrNull` so `true` and `"true"` agree. This survives
     *  variations between raw library configs (Doubles for weights, Ints for
     *  counts), user-edited JSON (whatever they typed), and the stored
     *  plan_run.config (post-resolveEffectiveConfig, all canonical Doubles).
     *
     *  Defaults match cfg() so missing axes still hash consistently.
     */
    fun signatureFor(config: JsonObject): String {
        val ms = (config["method_selection"] as? JsonObject) ?: JsonObject(emptyMap())
        val cs = (config["consolidation"] as? JsonObject) ?: JsonObject(emptyMap())
        val sw = (ms["score_weights"] as? JsonObject) ?: JsonObject(emptyMap())

        fun JsonObject.str(k: String, default: String): String =
            (get(k) as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: default
        fun JsonObject.int(k: String, default: Int): Int {
            val p = get(k) as? kotlinx.serialization.json.JsonPrimitive ?: return default
            return p.intOrNull
                ?: p.longOrNull?.toInt()
                ?: p.doubleOrNull?.toInt()
                ?: default
        }
        fun JsonObject.dbl(k: String, default: Double): Double {
            val p = get(k) as? kotlinx.serialization.json.JsonPrimitive ?: return default
            return p.doubleOrNull
                ?: p.contentOrNull?.toDoubleOrNull()
                ?: default
        }
        fun JsonObject.bool(k: String, default: Boolean): Boolean {
            val p = get(k) as? kotlinx.serialization.json.JsonPrimitive ?: return default
            return p.booleanOrNull
                ?: p.contentOrNull?.lowercase()?.toBooleanStrictOrNull()
                ?: default
        }
        // Canonical Double formatting: "1.0" rather than "1", "0.4" rather than "0.40000000000000002".
        // Uses a trimmed scientific format so identical numeric values produce identical strings.
        fun fmtDbl(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return v.toString()
            // Round to 9 significant digits to absorb tiny floating-point drift; keep canonical form.
            val s = "%.9g".format(v)
            // Trim trailing zeros and orphan decimal point unless we're in scientific notation.
            return if ('e' in s || 'E' in s) s
                else s.trimEnd('0').trimEnd('.').ifBlank { "0" }
        }

        val mode = ms.str("mode", "preference")
        val maxM = ms.int("max_methods", 1)
        val depth = ms.int("depth", 1)
        val bomDepth = ms.int("max_bom_depth", 3)
        val wC = fmtDbl(sw.dbl("commit_time", 0.4))
        val wI = fmtDbl(sw.dbl("inventory_consumed", 0.35))
        val wP = fmtDbl(sw.dbl("purchase", 0.25))
        // `enabled` used to gate consolidation on/off — removed (consolidation always runs now;
        // the per-type batch scales below are the only real on/off control, "none" being off for
        // that type). Historical KB rows from before this change carry `enabled` but no explicit
        // batch-scale keys, so they now hash identically to a "weekly" run on this axis — a
        // one-time, unavoidable loss of that specific historical distinction.
        val bsMake = cs.str("make_batch_scale", "weekly")
        val bsMove = cs.str("move_batch_scale", "weekly")
        val bsPurchase = cs.str("purchase_batch_scale", "weekly")
        val period = cs.int("period_days", 0)
        val purch = config.bool("purchase_allowed", false)
        // Fingerprint segments — see KbFingerprint.buildFingerprint's own doc. Read verbatim
        // from an already-persisted config (real run: real hash/"none" values); "legacy" when
        // the key is missing entirely — a config that predates this whole fingerprint scheme
        // (no data migration for these: allocation/preference/demand-order state at the time an
        // old run executed was never captured anywhere and can't be reconstructed after the
        // fact — see this file's own module doc and CaseBootstrap's git history for the
        // incident that motivated this).
        val fp = (config["_kb_fingerprint"] as? JsonObject)
        val caseAlloc = fp?.get("casealloc")?.jsonPrimitive?.contentOrNull ?: "legacy"
        val pref = fp?.get("pref")?.jsonPrimitive?.contentOrNull ?: "legacy"
        val ord = fp?.get("ord")?.jsonPrimitive?.contentOrNull ?: "legacy"
        val purchMat = fp?.get("purchmat")?.jsonPrimitive?.contentOrNull ?: "legacy"
        val constr = fp?.get("constr")?.jsonPrimitive?.contentOrNull ?: "legacy"
        return "m=$mode|max=$maxM|d=$depth|bom=$bomDepth|w=$wC,$wI,$wP|" +
            "bs=$bsMake,$bsMove,$bsPurchase|p=$period|purch=$purch|casealloc=$caseAlloc|pref=$pref|ord=$ord|" +
            "purchmat=$purchMat|constr=$constr"
    }

    /**
     * Signature for a NOT-YET-SUBMITTED bootstrap/seeding candidate config (library preset, axis
     * variation, a seed cloned from the case's current best, or a random seeding draw). Reads
     * the same 5 `*_version_id` keys `Allocate.kt`'s `resolveEffectiveConfig` reads directly off
     * [config] (falling back to the case's current default when a key is absent) so this preview
     * signature always matches what submission will actually produce — every run now genuinely
     * consults the case's Critical Material Allocation / Supply Preferences / Demand Ordering /
     * Purchasable Materials / Constraints state (see `runOneBootstrapPreset`'s own doc), so
     * there is no "na"-sentinel/non-consulting path left to special-case here.
     */
    fun signatureForBootstrapCandidate(config: JsonObject, caseId: Int): String {
        fun explicitVersionId(key: String): Int? = (config[key] as? JsonPrimitive)?.intOrNull
        val fp = KbFingerprint.buildFingerprint(
            caseAllocVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.CASEALLOC, explicitVersionId("case_alloc_version_id")),
            prefVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.PREF, explicitVersionId("pref_version_id")),
            ordVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.ORD, explicitVersionId("demand_order_version_id")),
            purchMatVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.PURCHMAT, explicitVersionId("purchasable_material_version_id")),
            constrVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.CONSTR, explicitVersionId("constraint_version_id")),
        )
        return signatureFor(embedFingerprint(config, fp))
    }

    /** Overlay already-resolved fingerprint [fp] segments onto [config]'s `_kb_fingerprint` key.
     *  Factored out of [signatureForBootstrapCandidate] so a caller sweeping many candidate
     *  configs that share the same 5 external-config version ids (e.g. [generateNetNewBatch],
     *  which only varies max_methods) can resolve the fingerprint ONCE instead of once per
     *  candidate — each resolution is a handful of DB round-trips, so doing it per-candidate
     *  turned a sweep over N values into an O(N) multiple of that cost for no reason. */
    private fun embedFingerprint(config: JsonObject, fp: KbFingerprint.Segments): JsonObject = buildJsonObject {
        config.entries.forEach { (k, v) -> if (k != "_kb_fingerprint") put(k, v) }
        putJsonObject("_kb_fingerprint") {
            put("casealloc", fp.casealloc)
            put("pref", fp.pref)
            put("ord", fp.ord)
            put("purchmat", fp.purchmat)
            put("constr", fp.constr)
        }
    }

    /** Wrap a preset's metadata bundle for plan_run.metadata. The signature is
     *  also captured here as an explicit field so a future KB-query tool can
     *  filter by signature without re-parsing every plan_run.config; bootstrap
     *  selection itself doesn't use it (it computes signatures on-the-fly so
     *  user-driven runs without metadata.signature still match). */
    fun metadataFor(preset: BootstrapPreset, caseId: Int): JsonObject = buildJsonObject {
        put("bootstrap", true)
        put("preset_id", preset.presetId)
        put("preset_label", preset.label)
        put("preset_index", preset.index)
        put("primary_axis", preset.primaryAxis)
        put("signature", signatureForBootstrapCandidate(preset.config, caseId))
    }

    /** Public-shape JSON for a preset (frontend confirm dialog). */
    fun toJson(preset: BootstrapPreset): JsonObject = buildJsonObject {
        put("preset_id", preset.presetId)
        put("preset_label", preset.label)
        put("preset_index", preset.index)
        put("primary_axis", preset.primaryAxis)
        put("config", preset.config)
    }

    /** Which version of each of the 5 external config objects [kb]'s source run used (see
     *  CaseConfigVersions' own doc) — lets the frontend's ConfigDetailView drilldown fetch the
     *  EXACT historical version instead of "whatever is live now". Omits a key entirely when
     *  null (legacy record, predates versioning) rather than emitting a JSON null. */
    private fun JsonObjectBuilder.putVersionRefs(kb: KbStore.KbRecord) {
        kb.caseAllocVersionId?.let { put("case_alloc_version_id", it) }
        kb.prefVersionId?.let { put("pref_version_id", it) }
        kb.ordVersionId?.let { put("demand_order_version_id", it) }
        kb.purchMatVersionId?.let { put("purchasable_material_version_id", it) }
        kb.constrVersionId?.let { put("constraint_version_id", it) }
    }

    /** JSON shape for one KB record, for the KB-seeding dialog's "already run" list.
     *  Synthesizes preset-shaped fields from the kb_record itself (source plan_run's name for
     *  bootstrap-tagged rows, or a truncated signature suffix as a deterministic fallback) so the
     *  frontend can render every KB record uniformly regardless of how it was produced (a random
     *  seeding batch, a manual run, or the agent's own `suggest_next_batch`-driven run). */
    private fun toJsonKbRecord(kb: KbStore.KbRecord): JsonObject = buildJsonObject {
        val displayLabel = kb.presetLabel
            ?: ("kb-" + kb.signature.takeLast(6))
        put("preset_id", kb.presetId ?: kb.signature)
        put("preset_label", displayLabel)
        put("preset_index", -1)
        put("primary_axis", kb.primaryAxis ?: "user-driven")
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(kb.configJson).jsonObject }
            .getOrNull()?.let { put("config", it) }
        put("kb_record_id", kb.id)
        put("soundness_status", kb.soundnessStatus)
        kb.sourcePlanRunId?.let { put("plan_run_id", it) }
        put("source_plan_run_deleted", kb.sourcePlanRunDeleted)
        val kpis = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(kb.kpisSnapshotJson).jsonObject }
            .getOrNull() ?: buildJsonObject { }
        kpis.entries.forEach { (k, v) -> put(k, v) }
        // Legacy snapshot without timing fields — read them off the source plan_run while it lives.
        if ("run_created_at" !in kpis && kb.sourcePlanRunId != null && !kb.sourcePlanRunDeleted) {
            KbStore.timingFromPlanRun(kb.sourcePlanRunId)?.entries?.forEach { (k, v) -> put(k, v) }
        }
        putVersionRefs(kb)
    }

    // ── KB seeding (human-facing "Knowledge Base" dialog) ─────────────────────
    // Deliberately independent of the LIBRARY/AXIS_CATALOG round-robin system above (still
    // used by the planning agent's own suggest_next_batch tool and KbStore's novelOnly branch).
    // See this class's own module doc for the two systems' relationship.

    /** One form filled in by the user, describing a single fixed config to seed a batch of KB
     *  runs from — `max_methods` sweeps every integer in [maxMethodsMin, maxMethodsMax] (one run
     *  per value; running the same max_methods twice would just collide on the same signature,
     *  so there is no separate "how many runs" knob), everything else held fixed across the
     *  whole batch. The 5 version ids are nullable — null means "no override" for that external
     *  config object, same convention as [CaseConfigVersioning.resolveVersionId]. */
    data class SeedForm(
        val maxMethodsMin: Int,
        val maxMethodsMax: Int,
        val purchaseAllowed: Boolean,
        val makeBatchScale: String,
        val moveBatchScale: String,
        val purchaseBatchScale: String,
        val analyzeCriticality: Boolean,
        val checkSoundness: Boolean,
        val caseAllocVersionId: Int?,
        val prefVersionId: Int?,
        val ordVersionId: Int?,
        val purchMatVersionId: Int?,
        val constrVersionId: Int?,
        val rootWaterfall: Boolean = true,
        val equalSplitRawMaterials: Boolean = false,
        val horizonStart: String? = null,
        val horizonEnd: String? = null,
        /** Per-lot ASC override for the whole seeding batch: supply_id -> date — see
         *  [resolveWipSupplyDates]' own doc for the per-lot "auto" -> horizon-start default (each
         *  "wip" lot is its own independent readiness schedule). Lots absent from this map leave
         *  that lot unresolved (falls back to horizon start at run time, same as a manual
         *  submission that never touched the ASC section). */
        val wipSupplyDates: Map<String, String> = emptyMap(),
    )

    /** Overlay [form]'s external-config version picks onto [config] — only non-null picks are
     *  written, so an unset pick leaves that object with no override (see
     *  [CaseConfigVersioning.resolveVersionId]'s own doc), exactly like a manual Plan Run
     *  submission that never touched that picker would. */
    private fun withVersionPicks(config: JsonObject, form: SeedForm): JsonObject = buildJsonObject {
        config.entries.forEach { (k, v) -> put(k, v) }
        form.caseAllocVersionId?.let { put("case_alloc_version_id", it) }
        form.prefVersionId?.let { put("pref_version_id", it) }
        form.ordVersionId?.let { put("demand_order_version_id", it) }
        form.purchMatVersionId?.let { put("purchasable_material_version_id", it) }
        form.constrVersionId?.let { put("constraint_version_id", it) }
    }

    /** One entry per integer in [SeedForm.maxMethodsMin, SeedForm.maxMethodsMax] whose resulting
     *  config signature is NOT in [kbSigs] — the net-new subset actually worth running.
     *
     *  Deliberately checks ONLY the KB record set, not [coveredSignaturesFor]'s broader union
     *  with raw plan_run history — the seeding dialog's own KB/plan_run relationship is:
     *  (1) replicate on creation — a successful+sound plan_run always gets mirrored into KB
     *      ([KbStore.backfillForCase], called by [statusFor] right before this), so any run
     *      worth counting as "covered" already has a KB row; (2) independent on delete —
     *      deleting a KB record does NOT touch its source plan_run, and by the same token should
     *      NOT permanently block that signature from being seeded again either. Using the
     *      broader plan_run-inclusive set here would make a deleted KB row's signature stay
     *      "covered" forever (since its source plan_run is still around), silently undoing the
     *      point of letting the user delete it. */
    fun generateNetNewBatch(caseId: Int, form: SeedForm, kbSigs: Set<String>): List<BootstrapPreset> {
        val lo = minOf(form.maxMethodsMin, form.maxMethodsMax)
        val hi = maxOf(form.maxMethodsMin, form.maxMethodsMax)
        // Resolve the 5 external-config version ids ONCE — they depend only on caseId + form,
        // not on max_methods, so they're invariant across the whole sweep. Each resolution is a
        // handful of DB round-trips; doing this per-value (as an earlier version did, via
        // signatureForBootstrapCandidate) turned a sweep over N values into an O(N) multiple of
        // that cost for no reason — the dominant cost behind the dialog's multi-second latency.
        val fp = KbFingerprint.buildFingerprint(
            caseAllocVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.CASEALLOC, form.caseAllocVersionId),
            prefVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.PREF, form.prefVersionId),
            ordVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.ORD, form.ordVersionId),
            purchMatVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.PURCHMAT, form.purchMatVersionId),
            constrVersionId = CaseConfigVersioning.resolveVersionId(caseId, ConfigVersionKind.CONSTR, form.constrVersionId),
        )
        var index = 1
        return (lo..hi).mapNotNull { maxMethods ->
            val config = embedFingerprint(
                withVersionPicks(
                    seedCfg(
                        maxMethods = maxMethods,
                        purchaseAllowed = form.purchaseAllowed,
                        makeBatchScale = form.makeBatchScale,
                        moveBatchScale = form.moveBatchScale,
                        purchaseBatchScale = form.purchaseBatchScale,
                        analyzeCriticality = form.analyzeCriticality,
                        checkSoundness = form.checkSoundness,
                        rootWaterfall = form.rootWaterfall,
                        equalSplitRawMaterials = form.equalSplitRawMaterials,
                        horizonStart = form.horizonStart,
                        horizonEnd = form.horizonEnd,
                        wipSupplyDates = form.wipSupplyDates,
                    ),
                    form,
                ),
                fp,
            )
            if (signatureFor(config) in kbSigs) return@mapNotNull null
            BootstrapPreset(
                presetId = "seed-max=$maxMethods",
                label = "Max methods: $maxMethods",
                index = index++,
                primaryAxis = "max_methods",
                config = config,
            )
        }
    }

    /** Whole-KB status snapshot for the seeding dialog: every KB record plus the net-new
     *  max_methods sweep for [form] (see [generateNetNewBatch]). Calls KbStore.backfillForCase
     *  first so historical successful + sound runs surface even if they predate the kb_records
     *  table. */
    fun statusFor(caseId: Int, form: SeedForm): JsonObject {
        KbStore.backfillForCase(caseId)
        val kbRecordsBySig = KbStore.listForCase(caseId)
        val nextBatch = generateNetNewBatch(caseId, form, kbRecordsBySig.keys)
        return buildJsonObject {
            put("kb_record_count", kbRecordsBySig.size)
            putJsonArray("already_run") {
                kbRecordsBySig.values.forEach { add(toJsonKbRecord(it)) }
            }
            putJsonArray("next_batch") {
                nextBatch.forEach { preset -> add(toJson(preset)) }
            }
        }
    }
}
