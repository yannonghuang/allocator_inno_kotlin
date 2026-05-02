package com.allocator.services

import com.allocator.PlanRuns
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 *  listed below gets the system default. The defaults define the baseline. */
private fun cfg(
    mode: String = "preference",
    maxMethods: Int = 1,
    depth: Int = 1,
    weights: Triple<Double, Double, Double>? = null,   // (commit, inventory, purchase)
    engine: String = "leaf-legacy",                    // "leaf-legacy" | "supply"
    allocationMode: String = "fair",                   // "fair" | "proportional" | "priority_first"
    consolidationEnabled: Boolean = true,
    periodDays: Int = 0,
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
        put("period_days", periodDays)
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
    private const val AXIS_DEPTH      = "depth"
    private const val AXIS_SCOPE      = "regulation_scope"
    private const val AXIS_ALLOC      = "allocation_mode"
    private const val AXIS_CONSOLID   = "consolidation"
    private const val AXIS_PURCHASE   = "purchase"
    private const val AXIS_ELABORATE  = "elaborate"

    /**
     * The library — every entry is a **single-axis variation off the
     * baseline** (cfg() defaults: mode=preference, max_methods=1, depth=1,
     * leaf-legacy, fair, consolidation=on, period=0, purchase=off, balanced
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

        // Engine axis: regulation scope.
        add("engine=supply", AXIS_SCOPE, cfg(engine = "supply"))

        // Allocation mode axis.
        add("alloc=proportional",   AXIS_ALLOC, cfg(allocationMode = "proportional"))
        add("alloc=priority_first", AXIS_ALLOC, cfg(allocationMode = "priority_first"))

        // Consolidation axis.
        add("consolidation=off", AXIS_CONSOLID, cfg(consolidationEnabled = false))
        for (p in listOf(7, 14, 30, 60)) add("period=$p", AXIS_CONSOLID, cfg(periodDays = p))

        // Purchase axis.
        add("purchase=on", AXIS_PURCHASE, cfg(purchaseAllowed = true))

        // Mode axis: elaborate scoring (with all other knobs at default).
        // Note: weight variations are deliberately NOT included here as
        // standalone presets — weights only have effect in elaborate mode,
        // so a "weight" preset would need to also flip mode → multi-axis.
        // The elaborate baseline (default weights) IS included as a clean
        // single-axis test of mode change.
        add("mode=elaborate", AXIS_ELABORATE, cfg(mode = "elaborate"))

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
                "engine" -> putJsonObject("consolidation") {
                    cs.entries.forEach { (k, v) -> if (k != "engine") put(k, v) }
                    put("engine", JsonPrimitive((parsedValue as? String) ?: "leaf-legacy"))
                }
                "allocation_mode" -> putJsonObject("consolidation") {
                    cs.entries.forEach { (k, v) -> if (k != "allocation_mode") put(k, v) }
                    put("allocation_mode", JsonPrimitive((parsedValue as? String) ?: "fair"))
                }
                "consolidation_enabled" -> putJsonObject("consolidation") {
                    cs.entries.forEach { (k, v) -> if (k != "enabled") put(k, v) }
                    put("enabled", JsonPrimitive((parsedValue as? Boolean) ?: true))
                }
                "period_days" -> putJsonObject("consolidation") {
                    cs.entries.forEach { (k, v) -> if (k != "period_days") put(k, v) }
                    put("period_days", JsonPrimitive((parsedValue as? Number)?.toInt() ?: 0))
                }
                "purchase_allowed" -> put("purchase_allowed", JsonPrimitive((parsedValue as? Boolean) ?: false))
                "mode" -> putJsonObject("method_selection") {
                    ms.entries.forEach { (k, v) -> if (k != "mode" && k != "elaborate") put(k, v) }
                    val modeStr = (parsedValue as? String) ?: "preference"
                    put("mode", JsonPrimitive(modeStr))
                    put("elaborate", JsonPrimitive(modeStr == "elaborate"))
                }
                "score_weights" -> putJsonObject("method_selection") {
                    ms.entries.forEach { (k, v) -> if (k != "mode" && k != "elaborate" && k != "score_weights") put(k, v) }
                    put("mode", JsonPrimitive("elaborate"))
                    put("elaborate", JsonPrimitive(true))
                    val profile = (parsedValue as? String) ?: "balanced"
                    putJsonObject("score_weights") {
                        when (profile) {
                            "commit"    -> { put("commit_time", JsonPrimitive(1.0)); put("inventory_consumed", JsonPrimitive(0.0)); put("purchase", JsonPrimitive(0.0)) }
                            "inventory" -> { put("commit_time", JsonPrimitive(0.0)); put("inventory_consumed", JsonPrimitive(1.0)); put("purchase", JsonPrimitive(0.0)) }
                            "purchase"  -> { put("commit_time", JsonPrimitive(0.0)); put("inventory_consumed", JsonPrimitive(0.0)); put("purchase", JsonPrimitive(1.0)) }
                            else        -> { put("commit_time", JsonPrimitive(0.4)); put("inventory_consumed", JsonPrimitive(0.35)); put("purchase", JsonPrimitive(0.25)) }
                        }
                    }
                }
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
         *  logical dependencies: e.g. period_days and allocation_mode only
         *  matter when consolidation is enabled, so they share the
         *  "consolidation" group with consolidation_enabled. */
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
        AxisSpec(
            name = "mode", label = "Mode",
            description = "preference (lookup) vs elaborate (multi-objective scoring with default weights).",
            valueType = "enum", enumValues = listOf("preference", "elaborate"),
            baselineValue = JsonPrimitive("preference"),
            defaultSeed = JsonPrimitive("elaborate"),
            variations = listOf("elaborate").map { JsonPrimitive(it) },
            group = GROUP_METHOD,
        ),
        // Compound axis: each value implies mode=elaborate AND a specific
        // score-weight triple. Lets users explore different elaborate-mode
        // scoring profiles without rolling their own JSON.
        AxisSpec(
            name = "score_weights", label = "Elaborate score weights",
            description = "Multi-objective scoring profile (sets mode=elaborate plus the named weights).",
            valueType = "enum",
            enumValues = listOf("balanced", "commit", "inventory", "purchase"),
            baselineValue = JsonPrimitive("balanced"),
            defaultSeed = JsonPrimitive("commit"),
            variations = listOf("commit", "inventory", "purchase").map { JsonPrimitive(it) },
            group = GROUP_METHOD,
        ),
        // ── Consolidation cluster ────────────────────────────────────────
        // consolidation_enabled is the cluster's "primary" knob;
        // engine, period_days, and allocation_mode are sub-knobs that live
        // nested under consolidation in the planner config (see cfg()).
        AxisSpec(
            name = "consolidation_enabled", label = "Consolidation",
            description = "Group demands by date window before allocation.",
            valueType = "bool", enumValues = emptyList(),
            baselineValue = JsonPrimitive(true),
            defaultSeed = JsonPrimitive(false),
            variations = listOf(JsonPrimitive(false)),
            group = GROUP_CONSOLID,
        ),
        AxisSpec(
            name = "engine", label = "Engine",
            description = "Regulation scope: leaf-only vs whole-tree supply allocation.",
            valueType = "enum", enumValues = listOf("leaf-legacy", "supply"),
            baselineValue = JsonPrimitive("leaf-legacy"),
            defaultSeed = JsonPrimitive("supply"),
            variations = listOf("supply").map { JsonPrimitive(it) },
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
        AxisSpec(
            name = "allocation_mode", label = "Allocation mode",
            description = "How competing demands split a constrained supply.",
            valueType = "enum", enumValues = listOf("fair", "proportional", "priority_first"),
            baselineValue = JsonPrimitive("fair"),
            defaultSeed = JsonPrimitive("proportional"),
            variations = listOf("proportional", "priority_first").map { JsonPrimitive(it) },
            group = GROUP_CONSOLID,
        ),
        // Purchase is a method-selection knob (it permits purchase orders
        // as a fulfillment method) — supply-side strategy, same as
        // max_methods / mode / score_weights.
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
            "engine" -> cfg(engine = (parsedValue as? String) ?: "leaf-legacy")
            "allocation_mode" -> cfg(allocationMode = (parsedValue as? String) ?: "fair")
            "consolidation_enabled" -> cfg(consolidationEnabled = (parsedValue as? Boolean) ?: true)
            "period_days" -> cfg(periodDays = (parsedValue as? Number)?.toInt() ?: 0)
            "purchase_allowed" -> cfg(purchaseAllowed = (parsedValue as? Boolean) ?: false)
            "mode" -> cfg(mode = (parsedValue as? String) ?: "preference")
            // Compound axis: profile name implies mode=elaborate plus the
            // named weight triple. "balanced" uses default weights so the
            // resulting config equals cfg(mode="elaborate") — same shape
            // as the simple mode axis with value "elaborate".
            "score_weights" -> when ((parsedValue as? String) ?: "balanced") {
                "commit"    -> cfg(mode = "elaborate", weights = Triple(1.0, 0.0, 0.0))
                "inventory" -> cfg(mode = "elaborate", weights = Triple(0.0, 1.0, 0.0))
                "purchase"  -> cfg(mode = "elaborate", weights = Triple(0.0, 0.0, 1.0))
                else        -> cfg(mode = "elaborate")  // balanced (default weights)
            }
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
                signatureFor(buildConfigForAxisValue(spec.name, value)) !in coveredSigs
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
     */
    fun selectNextBatch(caseId: Int, batchSize: Int = 5, criterion: String = CRITERION_FILL_RATE): List<BootstrapPreset> {
        // Single-knob variations off the case's CURRENT BEST config (per
        // [criterion]) or BASELINE on cold-start. For each (axis, value) in
        // AXIS_CATALOG we apply that knob to the seed and check the
        // resulting signature against KB; any duplicate is skipped at
        // suggestion time so the user only ever sees fresh runs.
        // Round-robin across axes for dimensional breadth.
        val seed = bestConfigFor(caseId, criterion)
        val seedSignature = signatureFor(seed)
        val coveredSignatures = listCoveredSignatures(caseId)

        val candidatesByAxis: MutableMap<String, MutableList<BootstrapPreset>> = linkedMapOf()
        var presetIdx = 1
        for (axis in AXIS_CATALOG) {
            for (value in axis.variations) {
                val config = applyAxisToSeed(seed, axis.name, value)
                val sig = signatureFor(config)
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

    /** Signatures considered "covered" for next-batch dedup. Two sources:
     *
     *   1. kb_records — the dissociated KB store. A row here means we have a
     *      KPI snapshot regardless of whether the source plan_run still exists.
     *   2. Non-failed plan_runs — user-driven runs that match a preset
     *      signature should also dedup (don't re-queue what the user already
     *      tried manually) even if they haven't been promoted to a kb_record.
     *
     *  Failed plan_runs do NOT count: bootstrap should retry them next click.
     *
     *  Public so the start-bootstrap handler can re-check at submission time
     *  (preview is computed on dialog open and may be stale by the time the
     *  user clicks Start, especially when configs were edited or a parallel
     *  job ran).
     */
    fun coveredSignaturesFor(caseId: Int): Set<String> = listCoveredSignatures(caseId)

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
        val depthOpt = ms.bool("depth_optimal", false)
        val wC = fmtDbl(sw.dbl("commit_time", 0.4))
        val wI = fmtDbl(sw.dbl("inventory_consumed", 0.35))
        val wP = fmtDbl(sw.dbl("purchase", 0.25))
        val engine = cs.str("engine", "leaf-legacy")
        val alloc = cs.str("allocation_mode", "fair")
        val consEnabled = cs.bool("enabled", true)
        val period = cs.int("period_days", 0)
        val purch = config.bool("purchase_allowed", false)
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

    /** Public-shape JSON for a preset (frontend confirm dialog).
     *  When [alreadyCovered] is true the dialog renders an "in KB" hint so
     *  the user knows clicking Start without editing this entry will skip
     *  it via the submit-time dedup. */
    fun toJson(preset: BootstrapPreset, alreadyCovered: Boolean = false): JsonObject = buildJsonObject {
        put("preset_id", preset.presetId)
        put("preset_label", preset.label)
        put("preset_index", preset.index)
        put("primary_axis", preset.primaryAxis)
        put("config", preset.config)
        if (alreadyCovered) put("already_covered", true)
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

    /** JSON shape for a KB record whose config doesn't match any LIBRARY
     *  preset. Synthesizes preset-shaped fields from the kb_record itself
     *  so the frontend can render it in the same table — uniformly with
     *  library-aligned rows. KB rows are KB rows; the UI no longer
     *  distinguishes "library" vs "custom". */
    private fun toJsonOffLibrary(kb: KbStore.KbRecord): JsonObject = buildJsonObject {
        // No library preset to anchor to; use the source plan_run's name
        // (carried in kb.presetLabel for bootstrap-tagged rows, null otherwise)
        // or a truncated signature suffix as a deterministic fallback.
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
    fun statusFor(caseId: Int, batchSize: Int = 5, criterion: String = CRITERION_FILL_RATE): JsonObject {
        KbStore.backfillForCase(caseId)
        val kbRecordsBySig = KbStore.listForCase(caseId)
        val librarySigs = LIBRARY.associateBy { signatureFor(it.config) }
        val coveredPresets = LIBRARY.filter { signatureFor(it.config) in kbRecordsBySig.keys }
        val offLibraryRecords = kbRecordsBySig.filterKeys { sig -> sig !in librarySigs.keys }.values
        val nextBatch = selectNextBatch(caseId, batchSize, criterion)
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
                // selectNextBatch already filters duplicates; we still pass
                // alreadyCovered=false explicitly so the response shape is
                // stable for clients that read the flag.
                nextBatch.forEach { preset -> add(toJson(preset, alreadyCovered = false)) }
            }
        }
    }
}
