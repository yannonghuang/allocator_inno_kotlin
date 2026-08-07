package com.allocator.services

import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.math.min

private val log = LoggerFactory.getLogger("com.allocator.SupplyGuidedPlanning")

// ── Config ─────────────────────────────────────────────────────────────────────

/**
 * Parsed from `config["supply_guided"]`.
 *
 * Supply-guided planning implements the two-loop model:
 *   Loop 1  — top-down request decomposition (inventory-prioritized BOM walk)
 *   Step 2  — supply allocation across all demands simultaneously
 *   Loop 2  — bottom-up commitment with per-supply budget caps
 *   Step 3c — GC of unused budgets via compensation passes
 *
 * Step 2's allocation policy is fixed, not configurable: critical raw materials
 * are pre-allocated (proportional to raw demand quantity — see [ALLOCATION_MODE]),
 * everything else is plain FIFO (no allocation concept — see consumeFromInventory's
 * own doc). A configurable `allocation_mode`/`enabled` pair used to live here; both
 * were dead (no UI ever set them, `enabled` never gated anything) and removed.
 *
 * Step 3c's compensation-pass count used to be configurable too (`max_compensation_passes`)
 * — removed: WO-consolidation-by-wave converges naturally in a single pass, so there was
 * never a real need for more than one.
 */
data class SupplyGuidedConfig(
    /** Enable post-planning lot-draw trace + compensation telemetry. Off by default — can OOM on large runs. */
    val traceLots: Boolean = false,
)

/**
 * Step 2's fixed allocation policy for critical raw materials — proportional to
 * raw demand quantity: allocation(d, s) = qty(s) × (qty(d) / Σ_competing qty(dd)).
 * Gives each demand a fair share of every supply regardless of BOM rates. See
 * [allocate]'s own doc for the other (dead, no longer reachable) policy names.
 */
const val ALLOCATION_MODE = "demand_qty"

fun parseSupplyGuidedConfig(config: Map<String, Any?>?): SupplyGuidedConfig {
    val sub = (config?.get("supply_guided") as? Map<*, *>) ?: return SupplyGuidedConfig()
    @Suppress("UNCHECKED_CAST")
    val m = sub as? Map<String, Any?> ?: return SupplyGuidedConfig()
    val traceLots = m["trace_lots"] as? Boolean ?: false
    return SupplyGuidedConfig(traceLots)
}

// ── Targeted Supply Allocation (TSA) overrides ──────────────────────────────────

/**
 * One critical-material lot's user-editable input, keyed by `supply_id` — the "Targeted Supply
 * Allocation" UI's entire editable surface. Only quantity and target are overridable: lead time
 * is always derived live from BOM structure ([aggregatePathToLeaf] inside
 * [allocateCriticalSuppliesPerLot]), never stored or overridden here.
 *
 * [qtyCap] replaces the lot's physical `qty` for allocation purposes (defaults to the lot's own
 * `qty` when a TSA row is first generated — see the route layer's "generate default" action, not
 * this file). [target] replaces the lot's `target` (customer id); pass `""` (not null) to
 * explicitly clear a lot's own TARGET via the UI — a null [target] here means "leave the row's
 * existing target as-is", matching [qtyCap]'s null-means-unset convention.
 */
data class TsaOverride(
    val qtyCap: Double? = null,
    val target: String? = null,
)

/**
 * Applies [tsaOverrides] to [data]'s `supply` rows, replacing `qty`/`target` per matching
 * `supply_id`. Returns [data] unchanged (same reference) when there's nothing to apply, so every
 * case with no TSA edits — i.e. everything before this feature existed — stays byte-for-byte
 * identical. Applied once, at the very top of [buildSupplyAllocation], BEFORE critical-material
 * classification, critical-stock detection, and the [hasAnyTargetedSupply] gate — so a target
 * added purely via a TSA override correctly flips a case into the TARGET-aware allocation path,
 * exactly as if it had been in the uploaded supply.csv all along.
 */
internal fun applyTsaOverrides(
    data: Map<String, List<Map<String, Any?>>>,
    tsaOverrides: Map<String, TsaOverride>?,
): Map<String, List<Map<String, Any?>>> {
    if (tsaOverrides.isNullOrEmpty()) return data
    val supplies = data["supply"] ?: return data
    val overridden = supplies.map { row ->
        val sid = (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@map row
        val override = tsaOverrides[sid] ?: return@map row
        val next = row.toMutableMap()
        if (override.qtyCap != null) next["qty"] = override.qtyCap
        if (override.target != null) next["target"] = override.target.takeIf { it.isNotBlank() }
        next
    }
    return data + ("supply" to overridden)
}

/**
 * Parses `config["targeted_supply_allocation"]` — a JSON-array-shaped list of
 * `{supply_id, qty_cap?, target?}` rows — into the same [TsaOverride] map [applyTsaOverrides]
 * expects. This is how TSA edits reach [buildSupplyAllocation] on every real call site (plan
 * submission, Critical Material Allocation preview/generate) without those call sites needing
 * their own dedicated parameter: TSA rides along as part of the ordinary planning config, exactly
 * like `purchasable_materials`/`constraints` already do (see `Allocate.kt`'s `planningConfig`).
 * Rows with a blank/missing `supply_id` are skipped; a present `target` key (even `""`) clears the
 * lot's own target, matching [TsaOverride.target]'s convention — only an ABSENT key means "leave
 * as-is".
 */
internal fun parseTsaOverridesFromConfig(config: Map<String, Any?>?): Map<String, TsaOverride> {
    val rows = (config?.get("targeted_supply_allocation") as? List<*>) ?: return emptyMap()
    val result = mutableMapOf<String, TsaOverride>()
    for (entry in rows) {
        val row = entry as? Map<*, *> ?: continue
        val sid = (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
        val qtyCap = (row["qty_cap"] as? Number)?.toDouble()
        val target = if (row.containsKey("target")) (row["target"] as? String ?: "") else null
        result[sid] = TsaOverride(qtyCap = qtyCap, target = target)
    }
    return result
}

// ── Allocation result ──────────────────────────────────────────────────────────

/**
 * Output of [buildSupplyAllocation] — the pre-computed per-lot budget caps to
 * hand to [legacyCommit], plus diagnostic context for post-planning tracing.
 */
internal data class SupplyAllocationResult(
    /** Per-demand, per-lot budget caps: demandId → lotKey → qty.  Passed directly to legacyCommit. */
    val perLotBudgets: Map<Any?, MutableMap<String, Double>>,
    // ── BOM graph (reused by computeAchievableQtyMaps to avoid rebuilding) ────
    val graph: BomGraph,
    // ── diagnostic context (needed by logSupplyGuidedTrace) ──────────────────
    val criticalMatrix: NeedsMatrix,
    val allocations: SupplyAllocations,
    val supplyTotals: Map<SupplyKey, Double>,
    val lotAllocByLot: Map<String, Map<Any?, Double>>,
    val demandPriorities: Map<Any?, Int>,
    val sgConfig: SupplyGuidedConfig,
)

// ── Allocation ─────────────────────────────────────────────────────────────────

/**
 * The set of product ids that are critical (raw-material-constrained) for this case's current
 * data/config: a product qualifies only if [isRawCriticalPosition] is true at EVERY one of its
 * known locations — i.e. no location anywhere offers an elastic path (make, or an admitted buy).
 * A product with a make method at just one location (e.g. a sub-assembly built at a specific
 * plant and moved elsewhere) is system-wide elastic, even though its move-destination locations
 * individually have no method of their own — so a single method-less location must NOT drag the
 * whole product into criticality when another location can produce more of it. Uses the SAME
 * canonical per-location test used for live dominator labeling (PlanningEngine.kt), so the two
 * systems can never disagree about which materials are critical.
 *
 * Recomputed fresh from live [data]/[config] on every call — this is what makes it usable as a
 * staleness check for a previously-generated Critical Material Allocation table (see
 * `caseAllocMaterialSet` in Allocation.kt and its call sites in Allocate.kt): the table's own
 * material set is a snapshot from whenever it was generated, while this always reflects "right
 * now."
 */
internal fun computeCriticalPids(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): Set<String> {
    val locationsByProduct: Map<String, List<String>> = (data["productlocation"] ?: emptyList())
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            pid to lid
        }
        .groupBy({ it.first }, { it.second })
    return locationsByProduct.entries
        .filter { (pid, locs) -> locs.all { lid -> isRawCriticalPosition(pid, lid, data, config) } }
        .map { it.key }
        .toSet()
}

/** True when ANY supply row in this case has a non-blank TARGET — the single case-wide flag that
 *  scopes every TARGET-aware behavior (critical-material per-lot budgeting via
 *  [allocateCriticalSuppliesPerLot], latest-lot-first draw order, and critical-stock detection) to
 *  cases that actually use the feature, so every case that doesn't stays byte-for-byte identical to
 *  behavior before any of it existed. Single source of truth — reused by
 *  [computeCriticalStockPositions] and [PlanningEngine]'s `DataIndex.hasTargetedSupply` so the two
 *  can never disagree about which cases are in scope. */
internal fun hasAnyTargetedSupply(data: Map<String, List<Map<String, Any?>>>): Boolean =
    (data["supply"] ?: emptyList()).any { (it["target"] as? String)?.trim()?.isNotBlank() == true }

/**
 * Critical stock: a [SupplyKey] with on-hand supply (qty > 0), not itself raw-critical
 * ([isRawCriticalPosition]), whose full set of fulfillment alternatives is structurally guaranteed
 * to consume the SAME single critical, TARGETed raw material — see
 * [computeMandatoryCriticalRawMaterials]'s own doc for what "structurally guaranteed" means. Maps
 * each qualifying stock [SupplyKey] to that one mandatory raw-material [SupplyKey], so callers can
 * look up which material's targeting a stock inherits (see [expandCriticalStockSupplies]).
 *
 * Ambiguous cases are deliberately excluded, not approximated: a position whose alternatives
 * disagree on which raw material is mandatory (or where any alternative bypasses raw material
 * entirely, e.g. an admitted `buy`) has an EMPTY mandatory set, not a singleton — see
 * [computeMandatoryCriticalRawMaterials]. Only a true singleton qualifies.
 *
 * Hard, case-level, first line: a case with no TARGET usage anywhere (e.g. case 173) takes ZERO of
 * this function's real work — not merely "happens to return empty" after running the walk. Mirrors
 * the exact same [hasAnyTargetedSupply] gate [buildSupplyAllocation] already uses to decide whether
 * TARGET-aware allocation applies at all.
 */
internal fun computeCriticalStockPositions(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): Map<SupplyKey, SupplyKey> {
    if (!hasAnyTargetedSupply(data)) return emptyMap()

    val supplies = data["supply"] ?: emptyList()

    val stockPositions: Set<SupplyKey> = supplies
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            val qty = (row["qty"] as? Number)?.toDouble() ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank() || qty <= 0) null else SupplyKey(pid, lid)
        }
        .toSet()
        .filterNot { sk -> isRawCriticalPosition(sk.productId, sk.locationId, data, config) }
        .toSet()
    if (stockPositions.isEmpty()) return emptyMap()

    val targetedRawKeys: Set<SupplyKey> = supplies
        .filter { (it["target"] as? String)?.trim()?.isNotBlank() == true }
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank()) null else SupplyKey(pid, lid)
        }
        .toSet()

    val memo = mutableMapOf<Pair<String, String>, Set<SupplyKey>>()
    val result = mutableMapOf<SupplyKey, SupplyKey>()
    for (sk in stockPositions) {
        val mandatory = computeMandatoryCriticalRawMaterials(sk.productId, sk.locationId, data, config, memo)
        val r = mandatory.singleOrNull() ?: continue
        if (r in targetedRawKeys) result[sk] = r
    }
    return result
}

/**
 * Splits each real critical-stock supply row into one virtual row per lot of its mandatory raw
 * material ([criticalStocks], from [computeCriticalStockPositions]) — Ramification 1's proportional
 * split, "stock_i(product, location, quantity × weight_i/Σweight, target = supply_i.target)".
 *
 * [rawLotAllocatedTotals] (real raw-material `supply_id` → its current effective total) would let
 * the split track the raw material's CURRENT allocation rather than its static physical `qty` —
 * kept as a parameter for that purpose, but [buildSupplyAllocation] (this function's one caller)
 * always passes an empty map now: `case_allocation` no longer persists a critical-stock-aware
 * allocation total to read (it stores TSA INPUT rows — qty_cap/target per RAW lot only, never
 * critical stock — see `Allocation.kt`'s `CaseAllocRow`/`generateDefaultTsaRows` doc), so every
 * call falls back to each raw lot's own physical `qty` — the plain Ramification-1 formula.
 *
 * Every virtual row is synthetic, product/location/date-identical to the real row it splits, with
 * `supply_id = "$realSupplyId#$i"` and `target` inherited from the raw material lot it corresponds
 * to. Non-critical-stock rows, and critical-stock rows whose mandatory material has zero weight to
 * split against, pass through completely unchanged. The returned `virtualSid -> realSid` map lets
 * the caller collapse budgets keyed by a virtual id back to the one real, physical supply_id every
 * downstream consumer (`consumeFromInventory`, `case_allocation`) actually knows about — see
 * `buildSupplyAllocation`'s own collapse step, right after `allocateCriticalSuppliesPerLot`.
 */
internal fun expandCriticalStockSupplies(
    supplies: List<Map<String, Any?>>,
    criticalStocks: Map<SupplyKey, SupplyKey>,
    rawLotAllocatedTotals: Map<String, Double>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): Pair<List<Map<String, Any?>>, Map<String, String>> {
    if (criticalStocks.isEmpty()) return supplies to emptyMap()

    val rawMaterialKeys = criticalStocks.values.toSet()
    val rawLotsByKey: Map<SupplyKey, List<Map<String, Any?>>> = supplies
        .filter { row ->
            val pid = (row["product_id"] as? String)?.trim()
            val lid = (row["location_id"] as? String)?.trim()
            pid != null && lid != null && SupplyKey(pid, lid) in rawMaterialKeys
        }
        .groupBy { row -> SupplyKey((row["product_id"] as String).trim(), (row["location_id"] as String).trim()) }

    val expanded = mutableListOf<Map<String, Any?>>()
    val virtualToReal = mutableMapOf<String, String>()

    for (row in supplies) {
        val pid = (row["product_id"] as? String)?.trim()
        val lid = (row["location_id"] as? String)?.trim()
        val qty = (row["qty"] as? Number)?.toDouble() ?: 0.0
        val realSid = (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val sk = if (pid != null && lid != null) SupplyKey(pid, lid) else null
        val rawKey = sk?.let { criticalStocks[it] }

        // A stock row that already carries its OWN explicit target must never be fractured
        // against the raw material's split — that would fabricate a phantom sub-lot tagged for a
        // DIFFERENT target than the one this physical batch is actually known to belong to.
        // Concretely: wip_280-0001 (target=Q6J, cases/inno2026_2) sits on the Q6J-only BOM branch
        // — no Q6K demand can ever reach it — but splitting it 93/7 by 283-0504-31's OWN targets
        // would tag ~7% of it "Q6K", which then sits permanently unreachable/stranded (confirmed
        // live: exactly matches an R10 "inventory not consumed" violation, to the decimal). Only a
        // stock with NO target of its own (e.g. wip_280-1001, shared between Q6J/Q6K paths) is
        // genuinely ambiguous and needs the raw material's own distribution to infer one.
        val ownTarget = (row["target"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        if (rawKey == null || pid == null || lid == null || qty <= 0 || realSid == null || ownTarget != null) {
            expanded.add(row)
            continue
        }

        val rawLots = selectHistoricalWeightLots(
            stockPid = pid, stockLid = lid, rawPid = rawKey.productId, rawLid = rawKey.locationId,
            rawLots = rawLotsByKey[rawKey] ?: emptyList(), data = data, config = config,
        )
        val weightedLots: List<Pair<Map<String, Any?>, Double>> = rawLots.mapNotNull { lot ->
            val lotSid = (lot["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lotPhysicalQty = (lot["qty"] as? Number)?.toDouble() ?: 0.0
            val weight = rawLotAllocatedTotals[lotSid] ?: lotPhysicalQty
            if (weight <= 0) null else lot to weight
        }
        val totalWeight = weightedLots.sumOf { it.second }
        if (totalWeight <= 0) {
            expanded.add(row)   // nothing to split against: pass through unchanged
            continue
        }

        for ((i, weightedLot) in weightedLots.withIndex()) {
            val (lot, weight) = weightedLot
            val virtualSid = "$realSid#$i"
            val virtualRow = row.toMutableMap()
            virtualRow["supply_id"] = virtualSid
            virtualRow["qty"] = qty * (weight / totalWeight)
            virtualRow["target"] = lot["target"]
            expanded.add(virtualRow)
            virtualToReal[virtualSid] = realSid
        }
    }

    return expanded to virtualToReal
}

/**
 * Picks which of a critical stock's mandatory raw material's own supply rows ([rawLots]) reflect
 * the period the EXISTING stock was actually produced — not the raw material's current/future
 * intake, which is what's arriving now, not what already went into what's already on the shelf.
 * Per the user's explicit requirement: the weight must come from real, dated supply rows, never a
 * blend across whatever the current period happens to contain regardless of relevance.
 *
 * The lookback offset is derived per stock item from its own cumulative BOM lead time to the raw
 * material ([aggregatePathToLeaf]) — not a fixed constant: a stock one make-step removed from the
 * raw material (e.g. `280-1001`, lead 7d) looks back ~7 days from horizon start; a stock two steps
 * removed (e.g. `280-0001`, lead 7d on top of `280-1001`'s own 7d) looks back ~14 days.
 *
 * Selection: raw-material lots dated on/before the computed lookback date are genuine historical
 * data — used if any exist. Otherwise (today's common case — no case in this system currently
 * uploads pre-horizon supply history, so the lookback date always predates the earliest available
 * lot) falls back to the two earliest-dated lots actually present in the file, per the user's
 * explicit interim policy — real rows that exist, just not from the true historical period, NEVER
 * a fabricated ratio conjured from the whole forward-looking window.
 */
internal fun selectHistoricalWeightLots(
    stockPid: String,
    stockLid: String,
    rawPid: String,
    rawLid: String,
    rawLots: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
): List<Map<String, Any?>> {
    if (rawLots.size <= 1) return rawLots
    val datedLots: List<Pair<LocalDate, Map<String, Any?>>> = rawLots.mapNotNull { lot ->
        val d = parseDate(lot["supply_date"] as? String) ?: return@mapNotNull null
        d to lot
    }
    if (datedLots.isEmpty()) return rawLots   // nothing dated: no basis to window against

    val horizonStart = resolveHorizonStart(config, data["demand"] ?: emptyList()) ?: return rawLots
    val leadDays = aggregatePathToLeaf(stockPid, stockLid, rawPid, rawLid, data)?.cumulativeLeadTime ?: return rawLots
    val lookbackDate = horizonStart.minusDays(leadDays.toLong())

    val historical = datedLots.filter { (d, _) -> !d.isAfter(lookbackDate) }
    if (historical.isNotEmpty()) return historical.map { it.second }

    val earliestDates = datedLots.map { it.first }.distinct().sorted().take(2).toSet()
    return datedLots.filter { (d, _) -> d in earliestDates }.map { it.second }
}

/**
 * Rewrites per-lot budget keys ("$pid|$lid|$virtualSid") produced against
 * [expandCriticalStockSupplies]'s virtual sub-lots back to the one real, physical supply_id
 * ("$pid|$lid|$realSid"), summing when multiple virtual sub-lots collapse onto the same real lot —
 * so every consumer downstream of [buildSupplyAllocation] (`case_allocation` persistence,
 * `consumeFromInventory`'s `perLotBudget`) only ever sees real supply_ids, never a virtual one that
 * doesn't exist in the actual physical inventory pool. Aggregate ("$pid|$lid") keys are already
 * correct without remapping — every virtual sub-lot of the same critical stock shares the same
 * `SupplyKey`, so [allocateCriticalSuppliesPerLot] already accumulates them into one aggregate
 * entry as it processes each sub-lot. No-op when [virtualToReal] is empty (mutates and returns
 * [budgets] as-is), i.e. whenever no critical stock was detected.
 */
internal fun collapseCriticalStockBudgets(
    budgets: MutableMap<Any?, MutableMap<String, Double>>,
    virtualToReal: Map<String, String>,
): MutableMap<Any?, MutableMap<String, Double>> {
    if (virtualToReal.isEmpty()) return budgets
    for (budgetMap in budgets.values) {
        val lotKeys = budgetMap.keys.filter { it.count { c -> c == '|' } >= 2 }
        for (lotKey in lotKeys) {
            val sid = lotKey.substringAfterLast('|')
            val realSid = virtualToReal[sid] ?: continue
            val qty = budgetMap.remove(lotKey) ?: continue
            val realKey = lotKey.substringBeforeLast('|') + "|" + realSid
            budgetMap[realKey] = (budgetMap[realKey] ?: 0.0) + qty
        }
    }
    return budgets
}

/**
 * Pure allocation step: BOM reachability walk + proportional supply split.
 * Does NOT touch inventory — no side effects on the supply pool.
 *
 * Produces the per-lot budget caps ([SupplyAllocationResult.perLotBudgets]) that
 * [legacyCommit] uses to enforce each demand's entitlement during planning.
 *
 * @param demands all demands competing for shared supply
 * @param data    BOM, methods, supply tables (read-only)
 * @param config  planning config (supply_guided sub-key, purchasable_materials, …)
 * @param tsaOverrides Targeted Supply Allocation edits (qty cap / target per lot's `supply_id`).
 *   Defaults to null, meaning "read them from `config["targeted_supply_allocation"]` instead" —
 *   see [parseTsaOverridesFromConfig] — which is what every real call site relies on; tests pass
 *   this directly instead of building a config blob. Either way, no TSA rows anywhere reproduces
 *   pre-TSA behavior exactly.
 */
internal fun buildSupplyAllocation(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    tsaOverrides: Map<String, TsaOverride>? = null,
): SupplyAllocationResult {
    val sgConfig = parseSupplyGuidedConfig(config)
    val data = applyTsaOverrides(data, tsaOverrides ?: parseTsaOverridesFromConfig(config))

    // Critical-materials identification (done before BOM walk so the walk can prune early) — see
    // computeCriticalPids's own doc for the per-location rule.
    val criticalPids: Set<String> = computeCriticalPids(data, config)
    // Critical stock: on-hand inventory of an otherwise-elastic product that's nonetheless
    // structurally guaranteed to derive from one specific critical, TARGETed raw material — see
    // computeCriticalStockPositions's own doc. Empty whenever the case has no TARGET usage at all
    // (its own hard early-exit), so this is a no-op for the vast majority of cases.
    val criticalStocks: Map<SupplyKey, SupplyKey> = computeCriticalStockPositions(data, config)

    // Step 1 — BOM reachability: build full graph (for unmapped-demand check), then build
    // the critical-only matrix in one pass by pruning non-critical supply leaves during walk.
    val graph         = buildBomGraph(demands, data)
    val requestMatrix = buildReachabilityMatrix(demands, graph)   // full — used only for unmapped check
    val unmappedDemands = demands.filter { it["demand_id"] !in requestMatrix.byRow }
    if (unmappedDemands.isNotEmpty()) {
        log.warn(
            "[supply-guided] {} demand(s) not in request map (no reachable supply path or qty<=0): {}",
            unmappedDemands.size,
            unmappedDemands.joinToString { d -> "${d["demand_id"]}(qty=${d["quantity"]})" },
        )
    }
    val criticalMatrix = buildReachabilityMatrix(demands, graph, criticalPids = criticalPids, criticalStockKeys = criticalStocks.keys)
    log.info(
        "[supply-guided] critical matrix: {} supply columns ({} critical product ids) of {} total",
        criticalMatrix.byColumn.size, criticalPids.size, requestMatrix.byColumn.size,
    )

    // Step 2 — supply allocation: split each lot proportionally among competing demands.
    val supplyTotals     = aggregateSupplies(data["supply"] ?: emptyList())
    val demandPriorities = extractDemandPriorities(demands)
    val demandQuantities = extractDemandQuantities(demands)
    val demandDates: Map<Any?, LocalDate?> = demands.associate { d ->
        d["demand_id"] to parseDate(d["request_due_time"] as? String ?: d["request_time"] as? String)
    }

    // Aggregate allocation (kept for compensation-pass diagnostics).
    val allocations = allocateSupplies(
        matrix           = criticalMatrix,
        supplyTotals     = supplyTotals,
        demandPriorities = demandPriorities,
        mode             = ALLOCATION_MODE,
        demandQuantities = demandQuantities,
    )
    log.info(
        "[supply-guided] initial allocation: {} demand rows, {} cells, mode={}",
        allocations.byRow.size, allocations.cellCount(), ALLOCATION_MODE,
    )

    // Log contested critical supplies.
    criticalMatrix.byColumn.entries
        .filter { it.value.size > 1 }
        .sortedByDescending { e -> e.value.values.sum() }
        .forEach { (sk, demandNeeds) ->
            val total     = demandNeeds.values.sum()
            val lots      = (data["supply"] ?: emptyList()).filter { s ->
                s["product_id"]?.toString()?.trim() == sk.productId &&
                s["location_id"]?.toString()?.trim() == sk.locationId
            }
            val supplyQty = lots.sumOf { s -> (s["qty"] as? Number)?.toDouble() ?: 0.0 }
            val sorted    = demandNeeds.entries.sortedByDescending { it.value }
            val isCritical = supplyQty > 0 && supplyQty < total * 0.01
            log.info("[supply-guided][request] supply={}@{} supplyQty={} totalRequest={} demands={}  {}={}",
                sk.productId, sk.locationId, supplyQty.toLong(), total.toLong(), demandNeeds.size,
                if (isCritical) "all" else "top",
                (if (isCritical) sorted else sorted.take(8)).joinToString { (d, q) -> "$d:${q.toLong()}" })
            if (isCritical) {
                for (lot in lots.sortedByDescending { (it["qty"] as? Number)?.toDouble() ?: 0.0 }) {
                    val sid        = (lot["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                    val lotQty     = (lot["qty"] as? Number)?.toDouble() ?: continue
                    val lotDate    = parseDate(lot["supply_date"] as? String)
                    val lotDateStr = (lot["supply_date"] as? String)?.take(10) ?: "?"
                    val eligible   = demandNeeds.entries
                        .filter { (did, _) ->
                            val dd       = demandDates[did]
                            val eligDate = if (dd != null && dd.dayOfMonth == 1) dd.plusMonths(1).minusDays(1) else dd
                            lotDate == null || eligDate == null || !eligDate.isBefore(lotDate)
                        }
                        .sortedByDescending { it.value }
                    log.info("[supply-guided][request-lot] supply={}@{} lot={} date={} qty={} eligible_demands={}  top={}",
                        sk.productId, sk.locationId, sid, lotDateStr, lotQty.toLong(), eligible.size,
                        eligible.take(8).joinToString { (d, q) -> "$d:${q.toLong()}" })
                }
            }
            // Log aggregate allocation for this supply.
            val demandAllocs  = allocations.byColumn[sk] ?: emptyMap()
            val sortedAllocs  = demandAllocs.entries.sortedByDescending { it.value }
            val allocCritical = sortedAllocs.lastOrNull()?.value?.let { it < 1.0 } ?: false
            log.info("[supply-guided][allocation] supply={}@{} totalAllocated={} demands={}  {}={}",
                sk.productId, sk.locationId, demandAllocs.values.sum().toLong(), demandAllocs.size,
                if (allocCritical) "all" else "top",
                (if (allocCritical) sortedAllocs else sortedAllocs.take(8)).joinToString { (d, q) -> "$d:${q.toLong()}" })
        }

    // Per-lot budgets. `allocateCriticalSuppliesPerLot` (TARGET/tightened-timing/rate-yield-
    // adjusted "legitimate quantity") is scoped to cases that actually USE TARGET — a case with
    // none falls back to the original, unmodified-from-main `allocateSuppliesPerLot`
    // (demand_qty-proportional). Deliberate: the new path recomputes a rate/yield-adjusted
    // cumulative need per (demand, critical material) via an uncached recursive BOM+move walk
    // (aggregatePathToLeaf) — correct, but real cost on a large case, and a materially different
    // allocation formula from what's shipped on main. Gating it behind actual TARGET usage keeps
    // every case that doesn't use this feature byte-for-byte identical to main's own behavior —
    // confirmed necessary live: case 173 (zero TARGET rows) dropped from 69.6% to 30.7% fill rate
    // when the new path ran unconditionally for every critical material in every case.
    val hasTargetedSupply = hasAnyTargetedSupply(data)
    val perLotBudgets = if (hasTargetedSupply) {
        // Critical stock's real physical lots are replaced by virtual per-target sub-lots here
        // (Ramification 1's split — see expandCriticalStockSupplies's own doc), so
        // allocateCriticalSuppliesPerLot competes demands for them exactly like any other
        // TARGETed critical-material lot. rawLotAllocatedTotals is always empty (see
        // expandCriticalStockSupplies's own doc) — case_allocation only ever stores TSA rows for
        // genuinely raw lots now, never critical stock, so there's nothing DB-backed to weight
        // the split against; it always falls back to each raw lot's own physical qty.
        val (expandedSupplies, virtualToReal) = expandCriticalStockSupplies(
            supplies              = data["supply"] ?: emptyList(),
            criticalStocks        = criticalStocks,
            rawLotAllocatedTotals = emptyMap(),
            data                  = data,
            config                = config,
        )
        val rawBudgets = allocateCriticalSuppliesPerLot(
            matrix    = criticalMatrix,
            supplies  = expandedSupplies,
            demands   = demands,
            data      = data,
        )
        // Collapse: every downstream consumer (case_allocation persistence, consumeFromInventory)
        // must only ever see the real, physical supply_id — never a virtual "#i" sub-lot, which
        // doesn't exist in the actual inventory pool. No-op (returns rawBudgets unchanged) when
        // virtualToReal is empty, i.e. whenever criticalStocks is empty.
        collapseCriticalStockBudgets(rawBudgets, virtualToReal)
    } else {
        allocateSuppliesPerLot(
            matrix           = criticalMatrix,
            supplies         = data["supply"] ?: emptyList(),
            demandPriorities = demandPriorities,
            mode             = ALLOCATION_MODE,
            demandDates      = demandDates,
            demandQuantities = demandQuantities,
        )
    }

    // Build lotKey → demand → qty map for post-planning trace.
    val aggKeySet     = criticalMatrix.byColumn.keys.map { it.toString() }.toSet()
    val lotAllocByLot = mutableMapOf<String, MutableMap<Any?, Double>>()
    for ((demandId, budgetMap) in perLotBudgets) {
        for ((key, qty) in budgetMap) {
            if (key in aggKeySet) continue
            lotAllocByLot.getOrPut(key) { mutableMapOf() }[demandId] = qty
        }
    }
    for ((lotKey, demandAllocs) in lotAllocByLot.entries.sortedByDescending { it.value.values.sum() }) {
        val pipeIdx  = lotKey.indexOf('|')
        val pipeIdx2 = if (pipeIdx >= 0) lotKey.indexOf('|', pipeIdx + 1) else -1
        if (pipeIdx < 0 || pipeIdx2 < 0) continue
        val pid          = lotKey.substring(0, pipeIdx)
        val lid          = lotKey.substring(pipeIdx + 1, pipeIdx2)
        val sid          = lotKey.substring(pipeIdx2 + 1)
        val sk           = SupplyKey(pid, lid)
        val totalRequest = criticalMatrix.byColumn[sk]?.values?.sum() ?: continue
        val totalSupply  = supplyTotals[sk] ?: continue
        if (totalSupply <= 0 || totalSupply >= totalRequest * 0.01) continue
        val allCompeting = criticalMatrix.byColumn[sk]?.entries?.sortedByDescending { it.value } ?: emptyList()
        log.info("[supply-guided][allocation-lot] supply={}@{} lot={} allocated={} eligible={} of {} demands  all={}",
            pid, lid, sid, demandAllocs.values.sum().toLong(), demandAllocs.size, allCompeting.size,
            allCompeting.joinToString { (d, _) -> "$d:${(demandAllocs[d] ?: 0.0).toLong()}" })
    }

    return SupplyAllocationResult(
        perLotBudgets    = perLotBudgets,
        graph            = graph,
        criticalMatrix   = criticalMatrix,
        allocations      = allocations,
        supplyTotals     = supplyTotals,
        lotAllocByLot    = lotAllocByLot,
        demandPriorities = demandPriorities,
        sgConfig         = sgConfig,
    )
}

/**
 * Flatten perLotBudgets to (supplyId, demandId?, qty) triples for DB persistence.
 * Only emits lot-level keys ("pid|lid|sid"); aggregate ("pid|lid") keys are skipped.
 * perLotBudgets is produced from criticalMatrix (non-purchasable materials only),
 * so the result is already restricted to critical materials by construction.
 */
internal fun buildAllocationBudgetRows(perLotBudgets: Map<Any?, MutableMap<String, Double>>): List<Triple<String, String?, Double>> {
    val rows = mutableListOf<Triple<String, String?, Double>>()
    for ((demandId, budgetMap) in perLotBudgets) {
        for ((key, qty) in budgetMap) {
            if (key.count { it == '|' } < 2) continue
            val supplyId = key.substringAfterLast('|')
            if (supplyId.isBlank() || qty <= 1e-12) continue
            rows.add(Triple(supplyId, demandId?.toString(), qty))
        }
    }
    return rows
}

/**
 * Same output shape as [buildAllocationBudgetRows], but zero-filled: emits a row for EVERY
 * (physical lot, demand) pair where the demand's BOM can reach that critical material at all
 * ([criticalMatrix]'s reachability — TARGET/timing-agnostic), not just pairs that actually
 * received budget.
 *
 * Without this, a demand whose deadline (or TARGET mismatch) excludes it from every lot of a
 * critical material it structurally needs simply has no row anywhere — indistinguishable in the
 * UI/API from "this demand doesn't need this material," when the true story is "needs it, but no
 * lot can legally supply it in time." Same reasoning for a lot no demand's deadline reaches: it
 * silently vanishes from the table rather than showing as a legitimately-unclaimed 0. See
 * allocateCriticalSuppliesPerLot's own doc for why a (demand, lot) pair can be reachable in
 * aggregate yet still get 0 budget.
 */
internal fun buildAllocationBudgetRowsFull(
    perLotBudgets: Map<Any?, MutableMap<String, Double>>,
    criticalMatrix: NeedsMatrix,
    supplies: List<Map<String, Any?>>,
): List<Triple<String, String?, Double>> {
    val lotsByKey: Map<SupplyKey, List<String>> = supplies.mapNotNull { row ->
        val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
        val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
        val sid = (row["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val qty = (row["qty"] as? Number)?.toDouble() ?: return@mapNotNull null
        if (qty <= 0) null else SupplyKey(pid, lid) to sid
    }.groupBy({ it.first }, { it.second })

    val rows = mutableListOf<Triple<String, String?, Double>>()
    for ((sk, demandNeeds) in criticalMatrix.byColumn) {
        val lots = lotsByKey[sk] ?: continue
        for (supplyId in lots) {
            val lotKey = "${sk}|$supplyId"
            for (demandId in demandNeeds.keys) {
                val qty = perLotBudgets[demandId]?.get(lotKey) ?: 0.0
                rows.add(Triple(supplyId, demandId?.toString(), qty))
            }
        }
    }
    return rows
}

// ── Achievable-quantity pre-computation ────────────────────────────────────────

/**
 * For each demand, compute the maximum fillable quantity at EVERY BOM node given
 * the per-lot budget caps from [allocation].  Returns a per-demand map of
 * (pid to lid) → achievable qty so that [plan] can cap each node before drawing
 * supply — eliminating the WO cascade that fires for quantities the budget can
 * never cover.
 *
 * Traversal rules (per-demand BOM walk):
 *   - Supply leaf: budget-capped if the supply is critical (has lot entries in
 *     perLotBudgets for this demand); uncapped otherwise.
 *   - Make method (variants = OR split, equal share): each variant handles
 *     needed/nVariants; its components are AND-constrained (min over children).
 *     Child qty = (needed/nVariants) * rate (per-variant, not the full-qty
 *     value that variantsForMake returns for the aggregate demand).
 *   - Move method: delegate to the source (pid, from_lid).
 *   - Buy method: unlimited.
 *   - Multiple methods at a node: OR — take the best.
 *
 * Returns demandId → Map<(pid to lid), achievable qty>.
 */
internal fun computeAchievableQtyMaps(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
): Map<Any?, Map<Pair<String, String>, Double>> {
    val result = mutableMapOf<Any?, Map<Pair<String, String>, Double>>()
    for (demand in demands) {
        val demandId = demand["demand_id"] ?: continue
        val pid  = (demand["product_id"] as? String)?.trim() ?: continue
        val lid  = (demand["location_id"] as? String)?.trim() ?: continue
        val qty  = (demand["quantity"]    as? Number)?.toDouble() ?: continue
        if (qty <= 0.0) { result[demandId] = emptyMap(); continue }
        val nodeMap = mutableMapOf<Pair<String, String>, Double>()
        nodeAchievableInto(pid, lid, qty, demandId, allocation, data, mutableSetOf(), nodeMap)
        result[demandId] = nodeMap
        val rootAq = nodeMap[pid to lid] ?: qty
        if (rootAq < qty - 1e-9) {
            log.info("[supply-guided][cap] demand={} qty={} achievable={} ({}) nodes={}",
                demandId, qty.toLong(), rootAq.toLong(), "${"%.1f".format(rootAq * 100.0 / qty)}%", nodeMap.size)
        }
    }
    return result
}

private fun nodeAchievableInto(
    pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, Double>,
): Double {
    if (needed <= 1e-9) return 0.0
    val key = pid to lid
    if (!visited.add(key)) return needed  // cycle guard: assume uncapped

    try {
        val sk            = SupplyKey(pid, lid)
        val demandBudgets = allocation.perLotBudgets[demandId] ?: emptyMap<String, Double>()
        val budgetPrefix  = "$pid|$lid|"

        // Supply at this node: sum lot budgets if critical; treat as uncapped otherwise.
        val supplyContrib: Double = when {
            sk !in allocation.graph.supplyIndex -> 0.0
            demandBudgets.keys.any { it.startsWith(budgetPrefix) } ->
                demandBudgets.entries.filter { (k, _) -> k.startsWith(budgetPrefix) }.sumOf { (_, q) -> q }
            else -> needed  // supply exists but not in critical matrix for this demand → uncapped
        }
        if (supplyContrib >= needed) {
            into[key] = needed
            return needed
        }

        // Methods at this node: OR group — best method wins.
        var methodContrib = 0.0
        for (method in getMethods(pid, lid, data)) {
            val ma = methodAchievableInto(method, pid, lid, needed, demandId, allocation, data, visited, into)
            if (ma > methodContrib) methodContrib = ma
            if (methodContrib >= needed) break
        }

        val aq = minOf(needed, supplyContrib + methodContrib)
        into[key] = aq
        return aq
    } finally {
        visited.remove(key)
    }
}

private fun methodAchievableInto(
    method: Map<String, Any?>, pid: String, lid: String, needed: Double,
    demandId: Any?,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, Double>,
): Double {
    return when (method["type"] as? String) {
        "purchase" -> needed  // unlimited
        "move" -> {
            val fromLid = (method["from_location_id"] as? String)?.trim() ?: return 0.0
            nodeAchievableInto(pid, fromLid, needed, demandId, allocation, data, visited, into)
        }
        "make" -> {
            // Variants are OR alternatives with equal-split: each handles needed/nVariants.
            // variantsForMake returns children with qty = needed * rate (full-demand scale).
            // Per-variant child qty = (needed/nVariants) * rate = cNeededFull / nVariants.
            val variants = variantsForMake(pid, lid, needed, method, data)
            if (variants.isEmpty()) return 0.0
            val nVariants = variants.size.toDouble()
            var total = 0.0
            for ((_, children) in variants) {
                val perVariant = needed / nVariants
                if (children.isEmpty()) { total += perVariant; continue }
                // AND: achievable limited by the most-constrained component.
                var variantAchievable = perVariant
                for (child in children) {
                    val cPid        = (child["product_id"] as? String)?.trim() ?: continue
                    val cLid        = (child["location_id"] as? String)?.trim() ?: continue
                    val cNeededFull = (child["quantity"]    as? Number)?.toDouble() ?: continue
                    // Divide by nVariants: variantsForMake scales qty by full `needed`, but
                    // each variant only handles needed/nVariants of the parent demand.
                    val cNeeded = cNeededFull / nVariants  // = perVariant * rate
                    if (cNeeded <= 1e-9) continue
                    val cAch      = nodeAchievableInto(cPid, cLid, cNeeded, demandId, allocation, data, visited, into)
                    // Convert child achievable back to parent units: cAch / rate = cAch / (cNeeded/perVariant)
                    val fromChild = cAch / (cNeeded / perVariant)
                    if (fromChild < variantAchievable) variantAchievable = fromChild
                }
                total += variantAchievable
            }
            total
        }
        else -> 0.0
    }
}

// ── Plan blueprint (sketch-based two-phase planning) ───────────────────────────

/**
 * Per-node result of the sketch phase.
 * Captures the achievable quantity AND the pre-selected BOM method so the commit
 * phase can bypass the unified waterfall's candidate expansion/ranking entirely —
 * reducing per-node overhead to O(1) at every BOM depth during planning.
 */
data class NodeBlueprint(
    /** Maximum qty this node can satisfy given its per-lot budget caps. */
    val achievable: Double,
    /** Supply portion of achievable (drawn from this node's inventory budget). */
    val supplyQty: Double = 0.0,
    /**
     * First feasible BOM method (by ascending preference int) for the residual.
     * null = supply fully covers demand (no method needed).
     */
    val method: Map<String, Any?>? = null,
    /**
     * Root-cause pointer(s) for why [achievable] < what was asked of this node, computed
     * INLINE alongside the same min (AND)/waterfall (OR) arithmetic that produces [achievable]
     * itself — not a separate reconstruction pass. Empty when this node was fully satisfied.
     * See [nodeSketchInto] / [methodAchievableForSketch] for exactly where each entry comes from.
     */
    val quantityDominator: List<DominatorRef> = emptyList(),
)

typealias DemandBlueprint = Map<Pair<String, String>, NodeBlueprint>
typealias PlanBlueprint   = Map<Any?, DemandBlueprint>

/**
 * Sketch phase: one read-only BOM walk per demand that:
 *  1. Computes achievable quantity at every node (same logic as [computeAchievableQtyMaps]).
 *  2. Records the FIRST feasible BOM method by preference so the commit phase can
 *     skip the unified waterfall's candidate expansion/ranking entirely.
 *
 * Supersedes [computeAchievableQtyMaps] in the supply-guided pipeline.
 */
internal fun computePlanBlueprint(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    /** Optional Preferences KB override, see [plan]'s `preferenceKb` param. `null` preserves
     *  today's exact raw-preference behavior. */
    preferenceKb: PreferenceKb? = null,
    /** Needed to build the SAME unified waterfall candidate list [nodeSketchInto] now shares
     *  with the live commit (expandWaterfallCandidates respects customer BOM-alternative
     *  constraints, config-scoped) and to read `max_methods` via [resolveMethodSelection]. */
    config: Map<String, Any?>? = null,
): PlanBlueprint {
    val result = mutableMapOf<Any?, DemandBlueprint>()
    for (demand in demands) {
        val demandId = demand["demand_id"] ?: continue
        val pid  = (demand["product_id"] as? String)?.trim() ?: continue
        val lid  = (demand["location_id"] as? String)?.trim() ?: continue
        val qty  = (demand["quantity"]    as? Number)?.toDouble() ?: continue
        if (qty <= 0.0) { result[demandId] = emptyMap(); continue }
        val nodeMap = mutableMapOf<Pair<String, String>, NodeBlueprint>()
        nodeSketchInto(pid, lid, qty, demandId, demand, allocation, data, config, mutableSetOf(), nodeMap, preferenceKb)
        result[demandId] = nodeMap
        val rootAq = nodeMap[pid to lid]?.achievable ?: qty
        if (rootAq < qty - 1e-9) {
            log.info("[supply-guided][blueprint-cap] demand={} qty={} achievable={} ({}) nodes={}",
                demandId, qty.toLong(), rootAq.toLong(),
                "${"%.1f".format(rootAq * 100.0 / qty)}%", nodeMap.size)
        }
    }
    return result
}

/** Every physical lot THIS DEMAND actually has entitlement to at (pid, lid), per its own
 *  [demandBudgets] slice of perLotBudgets — NOT every physical lot that exists for the
 *  product@location, which perLotBudgets deliberately fragments across ALL demands sharing a
 *  critical material (a demand's own entitlement is only ever a subset). Used only as the
 *  terminal cause when this exact (pid, lid) has no method able to relieve it — a genuine
 *  raw-supply bottleneck, not a (product, location) proxy, and not a listing of lots this demand
 *  was never entitled to draw from in the first place. When this demand has no entitlement here
 *  at all, self-identifies as the sole terminal cause with no supply_id — mirrors
 *  `rawDominatorRefs`'s "no supply method" terminal case in PlanningEngine.kt for consistency
 *  across the sketch-phase / live-commit boundary. */
private fun rawSupplyLotRefs(pid: String, lid: String, demandBudgets: Map<String, Double>): List<DominatorRef> {
    val prefix = "$pid|$lid|"
    val ownLots = demandBudgets.keys.filter { it.startsWith(prefix) }
    if (ownLots.isEmpty()) {
        return listOf(DominatorRef(kind = "bom_child", productId = pid, locationId = lid, label = "$pid@$lid (no supply)"))
    }
    return ownLots.mapNotNull { k ->
        val sid = k.removePrefix(prefix).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        DominatorRef(kind = "bom_child", productId = pid, locationId = lid, supplyId = sid, label = "$pid@$lid ($sid)")
    }
}

private fun nodeSketchInto(
    pid: String, lid: String, needed: Double,
    demandId: Any?,
    /** The demand this whole sketch pass belongs to — threaded through every recursive call
     *  unchanged (its own product/location/quantity aren't reread below this point; only
     *  customer_id, via expandWaterfallCandidates's constraint filter, still applies at every
     *  depth). Needed to call the SAME expandWaterfallCandidates the live commit and the
     *  diamond-allocation gather pass already use. */
    demand: Map<String, Any?>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, NodeBlueprint>,
    preferenceKb: PreferenceKb? = null,
): Double {
    if (needed <= 1e-9) return 0.0
    val key = pid to lid
    if (!visited.add(key)) return needed  // cycle guard: assume uncapped
    try {
        val sk            = SupplyKey(pid, lid)
        val demandBudgets = allocation.perLotBudgets[demandId] ?: emptyMap<String, Double>()
        val budgetPrefix  = "$pid|$lid|"

        val supplyQty: Double = when {
            sk !in allocation.graph.supplyIndex -> 0.0
            demandBudgets.keys.any { it.startsWith(budgetPrefix) } ->
                demandBudgets.entries.filter { (k, _) -> k.startsWith(budgetPrefix) }.sumOf { (_, q) -> q }
            else -> needed
        }
        if (supplyQty >= needed) {
            into[key] = NodeBlueprint(achievable = needed, supplyQty = needed)
            return needed
        }

        val residual = needed - supplyQty

        // Unified waterfall candidate list — the SAME functions (expandWaterfallCandidates +
        // kbPreference) the live commit's own waterfall (plan()) and the diamond-allocation
        // gather pass (gatherAndSiblingRequests's rankedCandidates) already use, rather than a
        // third, independently-evolving notion of "which methods/alt_groups, in what order."
        // Bounded by max_methods (resolveMethodSelection) the SAME way plan()'s own candidate
        // loop is — "root-level proportional split, waterfall elsewhere": this position isn't
        // proportionally split here (that's rootSplitWeights, live-commit-only), but the
        // CANDIDATE POOL itself is capped identically everywhere, not just at the root.
        val methodCfg = resolveMethodSelection(config)
        val ranked = expandWaterfallCandidates(getMethods(pid, lid, data), pid, demand, config, data)
            .sortedBy { kbPreference(pid, lid, it.method, it.altKey, preferenceKb) }
        val candidates = ranked.take(methodCfg.maxMethods.coerceAtMost(ranked.size))

        // Waterfall across candidates: the best-preference one gets the full residual; only
        // spill to the next if it can't fully cover. Replaces the old "first method with ANY
        // positive achievable wins, stop" rule, which under-explored relative to what the live
        // commit's OWN waterfall actually does — silently leaving NodeBlueprint.achievable (and
        // everything downstream that reads it: nodeQtyCaps, computeAndSiblingCaps's fair-split
        // discount) with no prediction at all for whatever the live commit spills into once the
        // sketch's first pick falls short.
        var waterfallResidual = residual
        var selectedAchievable = 0.0
        var firstContributingCandidate: WaterfallCandidate? = null
        var contributingCandidateCount = 0
        var dominatorUnion: List<DominatorRef> = emptyList()
        for (candidate in candidates) {
            if (waterfallResidual <= 1e-9) break
            val askedOfThisCandidate = waterfallResidual
            val cs = candidateAchievableForSketch(candidate, pid, lid, waterfallResidual, demandId, demand, allocation, data, config, visited, into, preferenceKb)
            if (cs.achievable <= 1e-9) continue
            if (firstContributingCandidate == null) firstContributingCandidate = candidate
            contributingCandidateCount++
            selectedAchievable += cs.achievable
            waterfallResidual -= cs.achievable
            if (cs.achievable < askedOfThisCandidate - 1e-9 && cs.dominator.isNotEmpty()) {
                dominatorUnion = dominatorUnion + cs.dominator
            }
        }
        // The "blueprint shortcut" downstream (PlanningEngine.kt's plan()) collapses straight
        // to NodeBlueprint.method, skipping full candidate expansion — only valid when trying
        // that ONE method alone in the live commit would reproduce this same achievable qty.
        // A waterfall spill (more than one candidate actually contributed) means the live
        // commit must ALSO be free to explore beyond the first, so this is deliberately left
        // null in that case — same as today's "no blueprint entry at all" fallback, which
        // already correctly falls through to full candidate expansion.
        val selectedMethod = if (contributingCandidateCount <= 1) firstContributingCandidate?.method else null

        val aq = supplyQty + selectedAchievable
        // Genuine shortfall at THIS node: union the dominators of whichever candidates fell
        // short of what THEY were individually asked — or, if nothing contributed at all, the
        // terminal cause IS this (pid, lid)'s own raw supply — point at its actual physical
        // lot(s) directly.
        val quantityDominator = if (aq < needed - 1e-9) {
            dominatorUnion.dedupBySupply().takeIf { it.isNotEmpty() } ?: rawSupplyLotRefs(pid, lid, demandBudgets)
        } else emptyList()
        into[key] = NodeBlueprint(achievable = aq, supplyQty = supplyQty, method = selectedMethod, quantityDominator = quantityDominator)
        return aq
    } finally {
        visited.remove(key)
    }
}

/** Paired with [NodeBlueprint.quantityDominator]: the achievable qty AND, computed in the
 *  same breath, who's to blame if it fell short of what was asked of this candidate. */
private data class MethodSketchResult(val achievable: Double, val dominator: List<DominatorRef> = emptyList())

/**
 * Achievable qty (and dominator) for ONE unified-waterfall candidate — a specific method, or
 * for "make" a specific alt_group variant of one (see [WaterfallCandidate]). Same arithmetic
 * as the live commit's own per-candidate resolution, but recurses via [nodeSketchInto] to
 * populate blueprint entries instead of actually consuming inventory/emitting work orders.
 * Alt_group waterfalling itself happens one level up now, in [nodeSketchInto]'s own unified
 * candidate loop — this function only ever evaluates the ONE variant [candidate] names.
 */
private fun candidateAchievableForSketch(
    candidate: WaterfallCandidate, pid: String, lid: String, needed: Double,
    demandId: Any?,
    demand: Map<String, Any?>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    visited: MutableSet<Pair<String, String>>,
    into: MutableMap<Pair<String, String>, NodeBlueprint>,
    preferenceKb: PreferenceKb? = null,
): MethodSketchResult {
    val method = candidate.method
    return when (method["type"] as? String) {
        // Elastic on both quantity and time (can always order more / sooner) — never itself
        // a dominator, matching the "any purchase is a consequence, never a cause" rule.
        "purchase" -> MethodSketchResult(needed)
        "move" -> {
            val fromLid = (method["from_location_id"] as? String)?.trim() ?: return MethodSketchResult(0.0)
            val ach = nodeSketchInto(pid, fromLid, needed, demandId, demand, allocation, data, config, visited, into, preferenceKb)
            // Transparent 1:1 pass-through — move adds no constraint of its own, so it inherits
            // the source location's own already-computed dominator verbatim (whatever that is).
            MethodSketchResult(ach, into[pid to fromLid]?.quantityDominator ?: emptyList())
        }
        "make" -> {
            val variants = variantsForMake(pid, lid, needed, method, data)
            // expandWaterfallCandidates emits one candidate per alt_group, so exactly one
            // variant should match here — a null altKey (single-alt_group method) simply
            // means there's exactly one variant to begin with.
            val chosen = if (candidate.altKey != null) variants.filter { it.first == candidate.altKey } else variants
            val (_, children) = chosen.firstOrNull() ?: return MethodSketchResult(0.0)
            if (children.isEmpty()) return MethodSketchResult(needed)
            // AND: exactly one child — whichever pulls achievable down the most — dominates
            // this variant. Ties keep whichever was found first (min tracking itself only ever
            // holds one winner at a time).
            var achievable = needed
            var dominator: List<DominatorRef> = emptyList()
            for (child in children) {
                val cPid = (child["product_id"] as? String)?.trim() ?: continue
                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                val cNeeded = (child["quantity"] as? Number)?.toDouble() ?: continue
                if (cNeeded <= 1e-9) continue
                val cAch = nodeSketchInto(cPid, cLid, cNeeded, demandId, demand, allocation, data, config, visited, into, preferenceKb)
                val fromChild = cAch / (cNeeded / needed)
                if (fromChild < achievable) {
                    achievable = fromChild
                    // Inherit the child's own already-computed dominator (recursively resolved
                    // — may itself be an OR-group from further below) rather than re-deriving
                    // it; fall back to a fresh self-reference only if the child (unexpectedly)
                    // didn't carry one despite falling short.
                    dominator = into[cPid to cLid]?.quantityDominator?.takeIf { it.isNotEmpty() }
                        ?: listOf(DominatorRef(kind = "bom_child", productId = cPid, locationId = cLid, label = "$cPid@$cLid"))
                }
            }
            MethodSketchResult(achievable, dominator)
        }
        else -> MethodSketchResult(0.0)
    }
}

// ── Intra-demand sibling contention ("diamond problem") ─────────────────────────
//
// A demand's own tree can fan out into several SIMULTANEOUSLY-active branches that
// independently reach the same scarce, critical material: AND-required BOM siblings
// under one make method, and root-level candidates.take(cap) (rootSplitWeights,
// PlanningEngine.kt) proportionally splitting the demand's own quantity up front.
// Both share the property that makes gather-then-allocate tractable: the participant
// set is fixed and known BEFORE any of them runs (unlike the ordinary sequential
// waterfall, where whether a second candidate is even tried depends on the first
// one's outcome). The live commit phase (planMethodSlot/plan) explores these
// branches sequentially and greedily, so an early branch can exhaust a shared lot
// before a later, equally-entitled sibling ever gets a look — even when the lot
// would comfortably cover a FAIR split across all of them. This section computes
// that fair split up front, in a separate top-down gather pass over the same
// (already-deterministic, KB-ranked) topology the live commit phase itself walks,
// so the live phase can enforce it as an additional per-branch cap.

/** Identifies one contending branch. AND-siblings are identified by their own
 *  (productId, locationId) alone (distinct BOM children of one AND-parent always have
 *  distinct (pid, lid) in practice). Root-split candidates all share the demand's own
 *  (productId, locationId) — [slot] disambiguates which top-level candidate/method a
 *  branch represents. */
data class BranchKey(
    val productId: String,
    val locationId: String,
    val slot: String? = null,
)

/** One (contended node, branch, critical supply) request discovered by [gatherAndSiblingRequests]. */
internal data class AndSiblingRequest(
    /** The AND-parent's (or root demand's) own (productId, locationId) — groups branches
     *  that are simultaneously active competitors for the same fanout. */
    val cohort: Pair<String, String>,
    val branch: BranchKey,
    val supplyKey: SupplyKey,
    val requestedQty: Double,
)

internal fun slotIdFor(candidate: WaterfallCandidate): String =
    "${candidate.method["type"]}:${candidate.altKey
        ?: (candidate.method["location_id"] ?: candidate.method["to_location_id"] ?: "").toString()}"

/**
 * Lineage: the chain of enclosing branch identities ("pid@lid" segments joined by ">")
 * leading to a fanout. Shared verbatim between [gatherAndSiblingRequests] (which builds
 * it while discovering branches) and PlanningEngine.kt's `plan()`/`planMethodSlot` (which
 * must reconstruct the identical value while walking the live commit, so a branch created
 * mid-tree by two DIFFERENT outer AND-siblings that happen to route through the same
 * shared sub-assembly doesn't collapse to one indistinguishable BranchKey — see
 * [gatherAndSiblingRequests]'s own doc for the full rationale. Both sides MUST use these
 * exact same two functions, or a lookup mismatch silently falls back to "uncapped".
 */
internal fun extendLineage(lineage: String, pid: String, lid: String): String =
    if (lineage.isEmpty()) "$pid@$lid" else "$lineage>$pid@$lid"

internal fun combineSlot(lineage: String, localSlot: String?): String? =
    listOfNotNull(lineage.ifEmpty { null }, localSlot).joinToString("|").ifEmpty { null }

/**
 * Phase 1 — pure top-down request gathering for one demand: assumes infinite upstream
 * supply and deterministic top-choice (KB-ranked) routing at every non-fanout point (the
 * same simplification [nodeSketchInto] and [computeAchievableQtyMaps] already lean on —
 * with a precomputed Preferences KB and a preference-ordered-waterfall/root-split
 * paradigm everywhere else, a pegging tree's shape is already close to deterministic).
 * No availability checks, no capping — just discovers who would ask for what, IF every
 * fanout branch got everything it asked for.
 *
 * Returns every (cohort, branch, criticalSupply, requestedQty) tuple this demand's tree
 * would generate. Cheap to call for demands with no critical reach at all — bails via
 * [SupplyAllocationResult.criticalMatrix]'s already-computed byRow index.
 */
internal fun gatherAndSiblingRequests(
    demand: Map<String, Any?>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    preferenceKb: PreferenceKb?,
    /** This demand's own slice of [computePlanBlueprint]'s sketch-phase output — reused here
     *  (not re-derived) to discount a branch's requested share of a shared critical material by
     *  what the SAME sketch pass already knows that branch can achieve, independent of the
     *  shared-material fight (nodeSketchInto doesn't track cross-branch consumption of a
     *  contended material, so its achievable prediction for any OTHER, non-shared constraint
     *  along the path is exactly the "ignoring this fight" ceiling step (b)'s fair-split is
     *  missing). Already accounts for max_methods/preferences via the same config/preferenceKb
     *  this function's own routing (rankedCandidates/kbPreference) already uses — reusing the
     *  sketch's own achievable value here means this doesn't need its own separate notion of
     *  achievability. `null` (the default) applies no discount, matching prior behavior. */
    demandBlueprint: DemandBlueprint? = null,
): List<AndSiblingRequest> {
    val demandId = demand["demand_id"]
    if (allocation.criticalMatrix.byRow[demandId].isNullOrEmpty()) return emptyList()
    val rootPid = (demand["product_id"] as? String)?.trim() ?: return emptyList()
    val rootLid = (demand["location_id"] as? String)?.trim() ?: return emptyList()
    val rootQty = (demand["quantity"] as? Number)?.toDouble() ?: return emptyList()
    if (rootQty <= 1e-9) return emptyList()

    val methodCfg = resolveMethodSelection(config)
    val out = mutableListOf<AndSiblingRequest>()

    fun rankedCandidates(pid: String, lid: String): List<WaterfallCandidate> {
        val methods = getMethods(pid, lid, data)
        if (methods.isEmpty()) return emptyList()
        return expandWaterfallCandidates(methods, pid, demand, config, data)
            .sortedBy { kbPreference(pid, lid, it.method, it.altKey, preferenceKb) }
    }

    // The top-ranked candidate is sometimes a structural dead end regardless of supply — most
    // commonly a move whose source is the very (pid, lid) frame currently open one level up
    // (e.g. 260-0141-02@2000 move-from-1000 vs 260-0141-02@1000 move-from-2000, a two-location
    // cycle). Under "infinite supply" that candidate would still never terminate, so picking it
    // and stopping (relying on the cycle guard) makes this whole branch silently vanish from the
    // gather pass instead of falling through to the next candidate — exactly what the live
    // commit phase's own cycle-aware waterfall does (a blocked candidate never ends the search).
    // Only a one-hop lookahead: deeper/indirect cycles still terminate via the recursive
    // visited-guard inside accumulate/discover, same as before.
    fun firstFeasibleCandidate(pid: String, lid: String, visited: Set<Pair<String, String>>): WaterfallCandidate? =
        rankedCandidates(pid, lid).firstOrNull { cand ->
            if (cand.method["type"] != "move") return@firstOrNull true
            val fromLid = (cand.method["from_location_id"] as? String)?.trim() ?: return@firstOrNull true
            (pid to fromLid) !in visited
        }

    // Full recursive tally of everything ONE branch's own subtree needs — AND children
    // summed (they're all mandatory, no alternative to choose among), the single
    // deterministic top-choice OR path elsewhere. Terminates at critical leaves (a
    // critical material's existing supply is its only source — nothing to recurse into).
    fun accumulate(pid: String, lid: String, needed: Double, branch: BranchKey, cohort: Pair<String, String>, visited: MutableSet<Pair<String, String>>) {
        if (needed <= 1e-9) return
        val key = pid to lid
        if (!visited.add(key)) return
        try {
            val sk = SupplyKey(pid, lid)
            if (sk in allocation.criticalMatrix.byColumn) {
                out.add(AndSiblingRequest(cohort, branch, sk, needed))
                return
            }
            // Discount by this position's own sketch-computed achievable ceiling — everything
            // else already known to constrain this path, independent of the shared-material
            // fight (see this function's own demandBlueprint param doc). Applied AFTER the
            // critical-leaf check above: capping the leaf's OWN registration by its OWN
            // sketch-achievable would be circular (that achievable is itself derived from this
            // demand's fair share of the very material step (b) is about to compute).
            val cappedNeeded = demandBlueprint?.get(key)?.achievable?.let { min(needed, it) } ?: needed
            if (cappedNeeded <= 1e-9) return
            val best = firstFeasibleCandidate(pid, lid, visited) ?: return
            when (best.method["type"]) {
                "move" -> {
                    val fromLid = (best.method["from_location_id"] as? String)?.trim() ?: return
                    accumulate(pid, fromLid, cappedNeeded, branch, cohort, visited)
                }
                "make" -> {
                    val mLoc = (best.method["location_id"] as? String)?.trim() ?: lid
                    val variants = variantsForMake(pid, mLoc, cappedNeeded, best.method, data)
                    val chosen = if (best.altKey != null) variants.filter { it.first == best.altKey } else variants
                    for ((_, children) in chosen) {
                        // A nested AND-fanout (>1 child) partway down this branch's own
                        // subtree is exactly what `discover` independently finds and handles
                        // at its own, finer-grained cohort (it's invoked on this same (pid,
                        // lid) alongside every accumulate call — see both call sites below).
                        // Flattening through it here too would register a SECOND, coarser
                        // request for the same underlying critical-material touch — under
                        // this outer branch's identity rather than the nested branch's own —
                        // diluting the fair split with a phantom contender that never
                        // actually draws anything in the live commit (nothing looks up this
                        // outer branch's cap for a leaf that's really reached through the
                        // nested fanout's own, more specific branch key). Stop here; the
                        // nested discover call covers this subtree completely on its own.
                        if (children.size > 1) return
                        for (child in children) {
                            val cPid = (child["product_id"] as? String)?.trim() ?: continue
                            val cLid = (child["location_id"] as? String)?.trim() ?: continue
                            val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                            if (cQty <= 1e-9) continue
                            accumulate(cPid, cLid, cQty, branch, cohort, visited)
                        }
                    }
                }
                // "purchase": elastic (can always order more/sooner) — never itself a
                // contention source, matches the sketch phase's own treatment.
            }
        } finally {
            visited.remove(key)
        }
    }

    // Walks the same topology looking for fanout points (AND-parent with >1 child, or the
    // root's own rootSplitWeights split). At each one found: computes each branch's own
    // target qty (rate-based for AND, flat 1/cap for root-split — mirroring
    // PlanningEngine.kt's rootSplitWeights formula exactly), fires one fresh [accumulate]
    // per branch, and keeps discovering deeper, independent fanouts nested within each
    // branch's own subtree — each nested fanout gets its own, separate cohort. Two
    // "cousin" branches from unrelated cohorts that both happen to reach the same scarce
    // critical material ARE fairly pooled together — [computeAndSiblingCaps] groups by
    // supplyKey alone, not (cohort, supplyKey), specifically so cousin contention isn't
    // invisible to the split. What's still left uncomposed: whether an OUTER branch's own
    // (possibly partial) achievability should further discount an INNER nested fanout's
    // share — e.g. a branch capped to 30% elsewhere in its own subtree still competes for
    // an unrelated inner-fanout material as if it will fully succeed. A real but separate,
    // lower-severity refinement (efficiency, not fair-share correctness) left for later.
    //
    // Lineage disambiguates two structurally identical subtrees reached via different
    // outer AND-siblings — see [extendLineage]/[combineSlot]'s own doc for the full
    // rationale (shared verbatim with PlanningEngine.kt's live-commit lookup).
    //
    // `discover` and `routeChildrenForDiscovery` mutually recurse, so both are declared as
    // lateinit lambdas (plain local `fun`s only see declarations lexically before them —
    // no forward reference — so genuine two-way local-function recursion needs this).
    lateinit var discover: (String, String, Double, Boolean, MutableSet<Pair<String, String>>, String) -> Unit
    lateinit var routeChildrenForDiscovery: (WaterfallCandidate, String, String, Double, String) -> Unit

    discover = discover@{ pid: String, lid: String, needed: Double, isRoot: Boolean, visited: MutableSet<Pair<String, String>>, lineage: String ->
        if (needed <= 1e-9) return@discover
        val key = pid to lid
        if (!visited.add(key)) return@discover
        try {
            val sk = SupplyKey(pid, lid)
            if (sk in allocation.criticalMatrix.byColumn) return@discover  // terminal — no fanout beneath a raw critical leaf

            val candidates = rankedCandidates(pid, lid)
            if (candidates.isEmpty()) return@discover
            val cap = methodCfg.maxMethods.coerceAtMost(candidates.size)

            if (isRoot && cap > 1) {
                val cohort = key
                val top = candidates.take(cap)
                // Flat split — see PlanningEngine.kt's rootSplitWeights for why this is no
                // longer KB-proportional.
                val weights = List(top.size) { 1.0 / top.size }
                for ((idx, cand) in top.withIndex()) {
                    val target = weights[idx] * needed
                    if (target <= 1e-9) continue
                    val branch = BranchKey(pid, lid, combineSlot(lineage, slotIdFor(cand)))
                    accumulate(pid, lid, target, branch, cohort, mutableSetOf())
                    // Step directly into this candidate's own children for nested-fanout
                    // discovery — cannot re-enter discover(pid, lid, ...) here, that would
                    // just re-trigger this same root-split fanout again.
                    //
                    // Root-split candidates all share the SAME (pid, lid) — they're OR
                    // alternatives for building the identical product, not distinct BOM
                    // children — so extending lineage with plain "pid@lid" would produce the
                    // SAME segment for every candidate, collapsing a nested fanout shared by
                    // two DIFFERENT root-split candidates exactly like an unqualified
                    // BranchKey would. Fold the candidate's own slot in too, matching
                    // `branch`'s own identity, so each candidate's descent stays distinct.
                    routeChildrenForDiscovery(cand, pid, lid, target, extendLineage(lineage, pid, "$lid#${slotIdFor(cand)}"))
                }
                return@discover
            }

            // Not a fanout here: follow the single deterministic top choice, but keep
            // looking for fanouts further below. Skips a top choice that would immediately
            // cycle back to an already-open frame (see firstFeasibleCandidate) — otherwise
            // a cyclic top preference silently truncates discovery right here, same bug as
            // in accumulate.
            val best = firstFeasibleCandidate(pid, lid, visited) ?: return@discover
            when (best.method["type"]) {
                "move" -> {
                    val fromLid = (best.method["from_location_id"] as? String)?.trim() ?: return@discover
                    discover(pid, fromLid, needed, false, visited, lineage)
                }
                "make" -> {
                    val mLoc = (best.method["location_id"] as? String)?.trim() ?: lid
                    val variants = variantsForMake(pid, mLoc, needed, best.method, data)
                    val chosen = if (best.altKey != null) variants.filter { it.first == best.altKey } else variants
                    for ((_, children) in chosen) {
                        val isAndGroup = children.size > 1
                        if (isAndGroup) {
                            val cohort = key
                            for (child in children) {
                                val cPid = (child["product_id"] as? String)?.trim() ?: continue
                                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                                val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                                if (cQty <= 1e-9) continue
                                val branch = BranchKey(cPid, cLid, combineSlot(lineage, null))
                                accumulate(cPid, cLid, cQty, branch, cohort, mutableSetOf())
                                discover(cPid, cLid, cQty, false, mutableSetOf(), extendLineage(lineage, cPid, cLid))
                            }
                        } else {
                            for (child in children) {
                                val cPid = (child["product_id"] as? String)?.trim() ?: continue
                                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                                val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                                if (cQty <= 1e-9) continue
                                discover(cPid, cLid, cQty, false, visited, lineage)
                            }
                        }
                    }
                }
            }
        } finally {
            visited.remove(key)
        }
    }

    routeChildrenForDiscovery = routeChildrenForDiscovery@{ candidate: WaterfallCandidate, pid: String, lid: String, needed: Double, lineage: String ->
        when (candidate.method["type"]) {
            "move" -> {
                val fromLid = (candidate.method["from_location_id"] as? String)?.trim() ?: return@routeChildrenForDiscovery
                discover(pid, fromLid, needed, false, mutableSetOf(), lineage)
            }
            "make" -> {
                val mLoc = (candidate.method["location_id"] as? String)?.trim() ?: lid
                val variants = variantsForMake(pid, mLoc, needed, candidate.method, data)
                val chosen = if (candidate.altKey != null) variants.filter { it.first == candidate.altKey } else variants
                for ((_, children) in chosen) {
                    for (child in children) {
                        val cPid = (child["product_id"] as? String)?.trim() ?: continue
                        val cLid = (child["location_id"] as? String)?.trim() ?: continue
                        val cQty = (child["quantity"] as? Number)?.toDouble() ?: continue
                        if (cQty <= 1e-9) continue
                        discover(cPid, cLid, cQty, false, mutableSetOf(), lineage)
                    }
                }
            }
        }
    }

    discover(rootPid, rootLid, rootQty, true, mutableSetOf(), "")
    return out
}

/** Bundles [computeAndSiblingCaps]'s two outputs: per-branch numeric caps (step b) and, for
 *  branches whose group was genuinely constrained, the dominator each should be tagged with
 *  (step c). Kept as one return type (rather than two separately-invoked top-level functions)
 *  because both are computed from the exact same per-group fair-split call — see
 *  [allocateSiblingGroup]. */
internal data class AndSiblingCapsResult(
    val caps: Map<Any?, Map<BranchKey, Map<String, Double>>>,
    val dominators: Map<Any?, Map<BranchKey, List<DominatorRef>>>,
)

private fun BranchKey.label(): String = "$productId@$locationId" + (slot?.let { " [$it]" } ?: "")

/** One contended-group's fair-split result: per-branch [shares] of [availableAgg] (step b), and
 *  — when the group's combined need exceeded what was available (the common case: proportional
 *  scaling across every contending branch, not one clear "worst" one) — the dominator(s) each
 *  constrained branch should be tagged with (step c).
 *
 *  Every branch in the group is drawing on the exact same material `sk` — so, mirroring the
 *  AND-min rule ("the wo with the least quantity dominates; copy its dominator to every
 *  sibling") one level up: rather than each branch computing its own reduced-quantity dominator
 *  independently, [dominatorByBranch] is ONE already-resolved [rawSupplyLotRefs] result for `sk`
 *  — the same bottom-up resolution every branch would already get from the sketch phase for
 *  this shared material — copied onto every constrained branch, not recomputed per branch. A
 *  dominator is always a real, specific supply lot (`kind = "bom_child"`, `supplyId` set), never
 *  something composed on-the-fly at the propagation site. Which sibling branches were also
 *  drawing on it rides along on `competingDemandIds` purely for UI-tooltip use — attached to the
 *  copy, never baked into its identity or label — and the copy still has to survive
 *  `isLotExhausted` at reconcile() time like any other `bom_child` ref: this is a candidate, not
 *  a final verdict on which lot actually bound. Empty [dominatorByBranch] when the group wasn't
 *  actually constrained (available >= total ask). */
private data class SiblingGroupAllocation(
    val shares: Map<BranchKey, Double>,
    val dominatorByBranch: Map<BranchKey, List<DominatorRef>>,
)

/**
 * Step (b): fair-split [availableAgg] of [sk] across [byBranch]'s contending branches — reuses
 * [allocate], the same fair-split primitive already used cross-demand, one level deeper.
 *
 * Step (c): when the group's total ask exceeds [availableAgg] — the common case, proportional
 * scaling across ALL branches rather than a single identifiable "worst" one — every branch that
 * GENUINELY NEEDS some of `sk` (its own alternative capacity, [achievableExcludingMaterial], falls
 * short of its own request) is tagged with the SAME [rawSupplyLotRefs] resolution for `sk`, copied
 * rather than independently recomputed (see [SiblingGroupAllocation]'s own doc for why copy, not
 * compose). A branch with a perfectly good alternative that never actually touches `sk` — e.g. a
 * VirtualProduct OR-group with several make-method candidates, only one of which reaches `sk` —
 * is NOT blamed just because it structurally COULD reach `sk` and the group overall is scarce;
 * confirmed live on case 173's 20018963_20/688_F35_2024_07_VIRTUAL, where
 * VirtualProduct_280-1159_A1 (fully satisfiable via 504-1532, unrelated to the contended
 * 160-1153) was tagged with sibling A3's 160-1153 dominator this way — [branchDominator] carries
 * this tag with priority over a branch's own, correctly-resolved sketch-phase dominator (see
 * PlanningEngine.kt's `plan()`), so a wrong guess here silently overrides a right answer downstream.
 */
private fun allocateSiblingGroup(
    sk: SupplyKey,
    byBranch: Map<BranchKey, List<AndSiblingRequest>>,
    availableAgg: Double,
    allocationMode: String,
    demandBudgets: Map<String, Double>,
    demand: Map<String, Any?>,
    config: Map<String, Any?>?,
    data: Map<String, List<Map<String, Any?>>>,
    preferenceKb: PreferenceKb?,
): SiblingGroupAllocation {
    val candidates = byBranch.map { (branch, reqs) ->
        AllocationCandidate(demandId = branch, neededQty = reqs.sumOf { it.requestedQty }, priority = 0)
    }
    // Mode "demand_qty" (the default) gracefully falls back to proportional-by-neededQty here —
    // AllocationCandidate.demandQty is deliberately left unset (0.0) since these candidates are
    // branches of ONE demand, not competing demands; splitting by each branch's own need is
    // exactly the right semantics.
    // allocate() returns Map<Any?, Double> (AllocationCandidate.demandId is Any? so it can hold
    // a BranchKey here) — safe per-entry cast, same as the pre-split code's own
    // `branch as? BranchKey ?: continue`, not a blanket cast.
    val shares = allocate(candidates, availableAgg, allocationMode)
        .mapNotNull { (k, v) -> (k as? BranchKey)?.let { it to v } }.toMap()

    val totalRequested = candidates.sumOf { it.neededQty }
    val dominatorByBranch = if (totalRequested > availableAgg + 1e-9) {
        val sharedDominator = rawSupplyLotRefs(sk.productId, sk.locationId, demandBudgets)
        // ONE shared, mutable snapshot across all N candidates in iteration order — each
        // candidate's probe destructively consumes from it (via the real
        // consumeFromInventory), so a later candidate's probe never double-credits the same
        // physical lot an earlier one already claimed — see
        // computeDiamondCapsForAttempt's identical two-recipient version for the full
        // rationale.
        val stockSnapshot = copyInventory(data["supply"] ?: emptyList())
        val genuinelyConstrained = candidates.mapNotNull { c ->
            val branch = c.demandId as? BranchKey ?: return@mapNotNull null
            val stock = achievableExcludingMaterial(
                branch.productId, branch.locationId, c.neededQty, sk.productId,
                demand, config, data, preferenceKb, stockSnapshot,
            )
            if (stock < c.neededQty - 1e-6) branch else null
        }.toSet()
        genuinelyConstrained.associateWith { branch ->
            val siblingLabels = byBranch.keys.filter { it != branch }.map { it.label() }
            sharedDominator.map { it.copy(competingDemandIds = siblingLabels) }
        }
    } else emptyMap()
    return SiblingGroupAllocation(shares, dominatorByBranch)
}

/**
 * Structural (BOM-topology-only) discovery of OR-group "grand-parent" recipients for each
 * critical material — generalizes the former hardcoded `DIAMOND_RECIPIENTS`
 * (`["VirtualProduct_280-1159_A1", "VirtualProduct_280-1159_A3"]`, the one known 160-1153 shape)
 * into an automatic algorithm covering any critical material reached through an OR-group,
 * anywhere in the BOM.
 *
 * For a critical material `c`, walks every BOM parent chain upward from `c` (`data["bom"]`:
 * `child_id -> parent_id`, one row per `(parent_id, elem_ix, child_id)` triple, `alt_group`
 * marking OR-alternative membership). Sibling OR-alternatives for the SAME logical slot are
 * grouped by `(parent_id, elem_ix)`, NOT `(parent_id, bom_id)`: in this dataset, each
 * alternative typically gets its OWN, per-child `bom_id` (e.g. `BOM_..._A3_504-1319` vs
 * `..._504-1532`, one row apiece, each its own single-row `method_make`) — `bom_id` never
 * groups true siblings together. `elem_ix` is the actual "same slot" signal (confirmed against
 * the live dataset: 160-1153's A3 recipient has 4 alternative children — 504-1319/1532/1548/
 * 1817 — each its own `bom_id`, but all sharing `elem_ix=1`). Falls back to `bom_id` when
 * `elem_ix` is absent (older/test fixtures that predate this field, or genuinely don't set it —
 * those already share one `bom_id` across true siblings, so the fallback preserves them).
 *
 * At each step from child `x` to parent `p`: if `x`'s own BOM row has a non-null `alt_group`,
 * AND a sibling row under the same `(parent_id, elem_ix)` has a *different* non-null
 * `alt_group` (confirming a genuine >=2-member OR-group, not a singleton), `p` is recorded as a
 * recipient for `c` and that chain stops there — the nearest enclosing OR-group's parent is the
 * collapsing point (e.g. `VirtualProduct_280-1159_A1`/`_A3`, not `280-1159` itself, for
 * 160-1153 — matches the original hardcode exactly). Plain AND-mandatory links (`alt_group =
 * NULL`, or a lone alt_group with no sibling) are walked through without recording, continuing
 * the search further up.
 *
 * Pure structural fact: computed once for the whole dataset, no demand or quantity involved —
 * a critical material can have multiple recipients (generalizing the A1/A3 pair to N — the live
 * dataset has ~29 for 160-1153 alone, most far less consequential than A1/A3 since they're
 * rarely on a currently-reachable demand path), and one recipient can serve multiple critical
 * materials. The quantity split among recipients found here happens live, per
 * waterfall-candidate attempt, in `PlanningEngine.kt`'s `computeDiamondCapsForAttempt` — see
 * that function's own doc for why a static, upfront split (this function's predecessor) is
 * wrong for recipients that are mutually exclusive with each other (gated behind a shared
 * ancestor OR-choice), as opposed to always-simultaneously-visited AND-mandatory siblings like
 * A1/A3.
 */
internal fun findOrGroupRecipients(
    criticalPids: Set<String>,
    data: Map<String, List<Map<String, Any?>>>,
): Map<String, Set<String>> {
    val bomRows = data["bom"] ?: emptyList()
    // child_id -> list of (parent_id, slotKey, alt_group)
    val parentsByChild = mutableMapOf<String, MutableList<Triple<String, String, String?>>>()
    // (parent_id, slotKey) -> set of distinct non-null alt_group values among its children
    val altGroupsByParentSlot = mutableMapOf<Pair<String, String>, MutableSet<String>>()
    // parent_id -> list of child_id, the downward mirror of parentsByChild — only needed for
    // the ancestor-pruning pass below (isDescendantOf).
    val childrenByParent = mutableMapOf<String, MutableList<String>>()
    for (row in bomRows) {
        val parentId = (row["parent_id"] as? String)?.trim() ?: continue
        val childId = (row["child_id"] as? String)?.trim() ?: continue
        val bomId = (row["bom_id"] as? String)?.trim() ?: continue
        if (parentId.isBlank() || childId.isBlank() || bomId.isBlank()) continue
        val altGroup = (row["alt_group"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        // "Same slot" grouping key: elem_ix when present, else fall back to bom_id.
        val slotKey = (row["elem_ix"] as? Number)?.toString() ?: bomId
        parentsByChild.getOrPut(childId) { mutableListOf() }.add(Triple(parentId, slotKey, altGroup))
        if (altGroup != null) altGroupsByParentSlot.getOrPut(parentId to slotKey) { mutableSetOf() }.add(altGroup)
        childrenByParent.getOrPut(parentId) { mutableListOf() }.add(childId)
    }

    // Is `descendant` reachable downward from `ancestor` via the BOM? Used only to prune
    // recipients below — cheap enough as a one-time, whole-dataset pre-pass (not on the live
    // commit path).
    fun isDescendantOf(ancestor: String, descendant: String): Boolean {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        stack.add(ancestor)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!seen.add(cur)) continue
            for (child in childrenByParent[cur] ?: emptyList()) {
                if (child == descendant) return true
                stack.add(child)
            }
        }
        return false
    }

    val result = mutableMapOf<String, MutableSet<String>>()
    for (c in criticalPids) {
        val recipients = mutableSetOf<String>()
        fun walk(childId: String, visited: MutableSet<String>) {
            if (!visited.add(childId)) return
            for ((parentId, slotKey, altGroup) in parentsByChild[childId] ?: emptyList()) {
                val siblingAltGroups = altGroupsByParentSlot[parentId to slotKey] ?: emptySet()
                val isRealOrGroup = altGroup != null && siblingAltGroups.size >= 2
                if (isRealOrGroup) {
                    recipients.add(parentId)
                } else {
                    walk(parentId, visited)
                }
            }
        }
        walk(c, mutableSetOf())
        // A recipient whose own subtree structurally contains ANOTHER, more specific recipient
        // for the same critical material is a coarser, outer OR-group junction — one of its
        // alternatives (e.g. 280-1210-02) may never itself reach a real inner OR-group, while a
        // SIBLING alternative (e.g. 280-1159) does, deeper down (its own A1/A3 split). Treating
        // the outer junction as an independent recipient wrongly grants it a share of the SAME
        // pool the inner recipients already split correctly between themselves — a candidate
        // reaching the critical material via the non-diamond sibling then inherits that
        // un-narrowed outer share whole, on top of what the inner split already allows (observed
        // live on case 173's 858_F35_2024_08_VIRTUAL: VirtualProduct_280-1314_A2 registered
        // alongside its own descendants VirtualProduct_280-1159_A1/_A3, letting a demand's
        // 280-1210-02 branch draw a full second, un-split share of 160-1153 after A1+A3 already
        // consumed their correctly-split one). Keep only the innermost (most specific)
        // recipients — drop any recipient that is a BOM ancestor of another recipient in the
        // same set.
        val innermost = recipients.filterNotTo(mutableSetOf()) { outer ->
            recipients.any { inner -> inner != outer && isDescendantOf(outer, inner) }
        }
        if (innermost.isNotEmpty()) result[c] = innermost
    }
    return result
}

/**
 * Per-demand slice of the raw (unsplit) entitlement for every critical material that has at
 * least one structurally-discovered OR-group recipient ([findOrGroupRecipients]) — exactly the
 * materials `PlanningEngine.kt`'s `computeDiamondCapsForAttempt` needs to split live, per
 * waterfall-candidate attempt, as the live commit proceeds. Unlike the former
 * `computeDiamondRecipientCaps`, this does NOT split the entitlement at all: recipients that are
 * mutually exclusive (gated behind a shared ancestor OR-choice) must not be pre-split — only the
 * live, per-attempt computation can tell which one is actually being visited right now. Critical
 * materials with no known recipients are simply absent here (nothing for the live commit to do
 * beyond the existing andSiblingCaps mechanism).
 */
internal fun buildDiamondCriticalEntitlement(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    diamondRecipients: Map<String, Set<String>>,
): Map<Any?, Map<String, Map<String, Double>>> {
    if (diamondRecipients.isEmpty()) return emptyMap()
    val result = mutableMapOf<Any?, Map<String, Map<String, Double>>>()
    for (demand in demands) {
        val demandId = demand["demand_id"] ?: continue
        val demandBudgets = allocation.perLotBudgets[demandId] ?: continue
        val perMaterial = mutableMapOf<String, Map<String, Double>>()
        for (criticalPid in diamondRecipients.keys) {
            // allocateSuppliesPerLot writes BOTH a per-lot key ("pid|lid|supplyId") AND an
            // aggregate key ("pid|lid", the sum of all lots) into the SAME demandBudgets map —
            // the aggregate key also starts with "$criticalPid|", so a plain prefix filter
            // matches both and summing every match double-counts (7 real lots + 1 aggregate
            // holding their sum = exactly 2x). Require >= 2 pipes (the supply_id component) to
            // keep only genuine per-lot entries — same disambiguation PlanningEngine.kt's own
            // criticalSupplyIds computation already uses for the identical key-shape ambiguity.
            val lotEntries = demandBudgets.entries.filter {
                it.key.startsWith("$criticalPid|") && it.key.count { c -> c == '|' } >= 2
            }
            if (lotEntries.isNotEmpty()) perMaterial[criticalPid] = lotEntries.associate { it.key to it.value }
        }
        if (perMaterial.isNotEmpty()) result[demandId] = perMaterial
    }
    return result
}

/**
 * Phase 2 — per-(demand, critical supply) fair allocation among contending branches
 * discovered by [gatherAndSiblingRequests] (step a), split into fair-share allocation (step
 * b, [allocateSiblingGroup]) and horizontal dominator propagation (step c, same function) —
 * see [allocateSiblingGroup]'s own doc for why b and c are one call, not two.
 *
 * Grouped by supplyKey alone, deliberately NOT by (cohort, supplyKey): branches from
 * different AND-parents ("cousins") competing for the same scarce material draw from the
 * exact same demand-wide budget and must be split together, not one cohort at a time —
 * see the grouping loop below for the full rationale.
 *
 * Returns, per demand: demandId → branch → lotKey → capped qty (`caps`, ready to slice
 * per-demand and thread into [legacyCommit] as `andSiblingCaps`), and demandId → branch →
 * dominator refs (`dominators`, threaded the same way as `andSiblingDominators`).
 */
internal fun computeAndSiblingCaps(
    demands: List<Map<String, Any?>>,
    allocation: SupplyAllocationResult,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    preferenceKb: PreferenceKb?,
    /** Optional per-demand progress callback (index 1-based, total demand count) — this is the
     *  single most expensive step in a large plan's pre-processing (a full BOM gather walk per
     *  demand), so it gets its own sub-progress rather than reporting only once at entry/exit. */
    onDemandProcessed: ((Int, Int) -> Unit)? = null,
    /** [computePlanBlueprint]'s sketch-phase output — sliced per-demand and passed to
     *  [gatherAndSiblingRequests] as its own `demandBlueprint` param. See that param's own doc
     *  for why step (a)'s gather reuses it instead of assuming infinite achievability. */
    planBlueprint: PlanBlueprint? = null,
): AndSiblingCapsResult {
    val capsResult = mutableMapOf<Any?, Map<BranchKey, Map<String, Double>>>()
    val dominatorResult = mutableMapOf<Any?, Map<BranchKey, List<DominatorRef>>>()
    for ((idx, demand) in demands.withIndex()) {
        val demandId = demand["demand_id"] ?: continue
        val requests = gatherAndSiblingRequests(demand, allocation, data, config, preferenceKb, planBlueprint?.get(demandId))  // (a)
        onDemandProcessed?.invoke(idx + 1, demands.size)
        if (requests.isEmpty()) continue
        val demandBudgets = allocation.perLotBudgets[demandId] ?: emptyMap()
        val branchCaps = mutableMapOf<BranchKey, MutableMap<String, Double>>()
        val branchDominators = mutableMapOf<BranchKey, MutableList<DominatorRef>>()

        // Group by supplyKey ALONE — not (cohort, supplyKey). Two branches hanging off
        // different AND-parents ("cousins") that both reach the same scarce critical
        // material are just as much in contention as two direct AND-siblings: they draw
        // from the exact same demand-wide budget. Grouping per-cohort would let each
        // cohort's fair-split run in isolation, blind to the other's claim on the same
        // pool — worse, a branch with no peer *within its own cohort* would never trip
        // the size<2 check below and would go uncapped, free to drain the whole
        // demand-wide budget if evaluated first, starving its cousin(s) down to zero.
        // For demands where every critical material is reached from a single cohort
        // (the common case), this produces the exact same groups as before.
        for ((sk, group) in requests.groupBy { it.supplyKey }) {
            val byBranch = group.groupBy { it.branch }
            if (byBranch.size < 2) continue  // no contention: only one branch reaches this supply

            val prefix = "${sk.productId}|${sk.locationId}|"
            val lotEntries = demandBudgets.entries.filter { it.key.startsWith(prefix) }.map { it.key to it.value }
            val availableAgg = lotEntries.sumOf { it.second }
            if (availableAgg <= 1e-9) continue

            val (shares, dominatorByBranch) =
                allocateSiblingGroup(sk, byBranch, availableAgg, ALLOCATION_MODE, demandBudgets, demand, config, data, preferenceKb)  // (b) + (c)

            // Known v1 limitation: projects one flat ratio onto every one of the demand's
            // existing per-lot entries for this supply key — doesn't re-check per-lot date
            // eligibility per branch.
            for ((branch, share) in shares) {
                if (share <= 1e-9) continue
                val ratio = share / availableAgg
                val m = branchCaps.getOrPut(branch) { mutableMapOf() }
                for ((lotKey, lotQty) in lotEntries) {
                    val cap = lotQty * ratio
                    // A branch key can legitimately recur across independent cohorts (e.g. a
                    // shared component appearing under two different AND-parents); take the
                    // tighter of the two rather than summing — summing could let a branch's
                    // effective cap exceed what any single cohort's fair split actually granted.
                    m[lotKey] = m[lotKey]?.let { min(it, cap) } ?: cap
                }
            }
            for ((branch, refs) in dominatorByBranch) {
                branchDominators.getOrPut(branch) { mutableListOf() }.addAll(refs)
            }
        }
        if (branchCaps.isNotEmpty()) capsResult[demandId] = branchCaps
        if (branchDominators.isNotEmpty()) dominatorResult[demandId] = branchDominators
    }
    return AndSiblingCapsResult(capsResult, dominatorResult)
}

// ── Post-planning trace ─────────────────────────────────────────────────────────

/**
 * Logs the request → allocation → pegging pipeline for critically scarce supplies,
 * and runs the compensation-pass telemetry (budget GC diagnostics).
 * Called after [legacyCommit] completes, with the committed pegging trees.
 */
internal fun logSupplyGuidedTrace(
    allocation: SupplyAllocationResult,
    pegging: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
) {
    val lotPeggedByLot = extractLotDrawsFromPegging(pegging)
    for ((lotKey, allocDemands) in allocation.lotAllocByLot.entries.sortedByDescending { it.value.values.sum() }) {
        val pipeIdx  = lotKey.indexOf('|')
        val pipeIdx2 = if (pipeIdx >= 0) lotKey.indexOf('|', pipeIdx + 1) else -1
        if (pipeIdx < 0 || pipeIdx2 < 0) continue
        val pid          = lotKey.substring(0, pipeIdx)
        val lid          = lotKey.substring(pipeIdx + 1, pipeIdx2)
        val sid          = lotKey.substring(pipeIdx2 + 1)
        val sk           = SupplyKey(pid, lid)
        val totalRequest = allocation.criticalMatrix.byColumn[sk]?.values?.sum() ?: continue
        val totalSupply  = allocation.supplyTotals[sk] ?: continue
        if (totalSupply <= 0 || totalSupply >= totalRequest * 0.01) continue
        val lotInfo        = (data["supply"] ?: emptyList()).firstOrNull {
            it["supply_id"]?.toString()?.trim() == sid && it["product_id"]?.toString()?.trim() == pid
        }
        val lotQty         = (lotInfo?.get("qty") as? Number)?.toDouble() ?: 0.0
        val lotDateStr     = (lotInfo?.get("supply_date") as? String)?.take(10) ?: "?"
        val requestNeeds   = allocation.criticalMatrix.byColumn[sk] ?: emptyMap()
        val peggedByDemand = lotPeggedByLot[sk]?.get(sid) ?: emptyMap()
        val lines = requestNeeds.entries.sortedByDescending { it.value }.mapNotNull { (did, _) ->
            val req    = requestNeeds[did]      ?: 0.0
            val alloc  = allocDemands[did]      ?: 0.0
            val pegged = peggedByDemand[did]    ?: 0.0
            if (req < 1e-9 && alloc < 1e-9 && pegged < 1e-9) null
            else "$did:${req.toLong()}->${alloc.toLong()}->${pegged.toLong()}"
        }
        log.info("[supply-guided][trace-lot] supply={}@{} lot={} date={} lotQty={}  all={}",
            pid, lid, sid, lotDateStr, lotQty.toLong(), lines.joinToString(", "))
    }

    // Compensation pass telemetry (re-planning with updated caps is deferred; this is diagnostics
    // only). A single pass — WO-consolidation-by-wave converges naturally, so a configurable
    // multi-pass count (removed) was never actually needed.
    val actualDraws = extractActualDrawsFromPegging(pegging)
    val cr = compensate(allocation.allocations, actualDraws, allocation.criticalMatrix,
                        allocation.demandPriorities, ALLOCATION_MODE)
    if (cr.redistributed) {
        log.info("[supply-guided] compensation pass: supplies={} redistributed={:.2f} absorbed={:.2f}",
            cr.supplyCount, cr.qtyRedistributed, cr.qtyAbsorbed)
        log.info("[supply-guided] compensation complete; re-plan with updated caps is TODO (convergence loop)")
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

/**
 * Aggregate what each demand actually drew from each supply-bearing (pid, lid)
 * node during Loop 2, by walking the committed pegging trees.
 *
 * Returns demandId → SupplyKey → total qty consumed, suitable for passing
 * to [compensate] as the `actualDraws` argument.
 *
 * Only "supply" type pegging nodes are counted (not "purchase" — those are
 * external procurement events, not draws on existing inventory supply rows).
 * Failed work_order subtrees are skipped (they reflect first-pass exploration
 * that was rolled back and didn't actually consume inventory).
 */
private fun extractActualDrawsFromPegging(
    pegging: List<Map<String, Any?>>,
): Map<Any?, Map<SupplyKey, Double>> {
    val result = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()

    @Suppress("UNCHECKED_CAST")
    fun walk(node: Map<String, Any?>, demandId: Any?) {
        val type = node["type"] as? String
        if (type == "work_order" && node["failed"] == true) return

        if (type == "supply") {
            val pid = (node["product_id"] as? String)?.trim() ?: return
            val lid = (node["location_id"] as? String)?.trim() ?: return
            val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 1e-12) {
                val m = result.getOrPut(demandId) { mutableMapOf() }
                val sk = SupplyKey(pid, lid)
                m[sk] = (m[sk] ?: 0.0) + qty
            }
        }

        val nextId = if (type == "demand") node["demand_id"] else demandId
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it, nextId) }
    }

    for (entry in pegging) {
        val demandId = entry["demand_id"]
        @Suppress("UNCHECKED_CAST")
        val tree     = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, demandId)
    }
    return result
}

/**
 * Walk committed pegging trees and collect per-lot (supply_id) actual draws per demand.
 *
 * Returns SupplyKey → supplyId → demandId → qty consumed.
 * Failed work_order subtrees are skipped (they were rolled back, no inventory consumed).
 */
private fun extractLotDrawsFromPegging(
    pegging: List<Map<String, Any?>>,
): Map<SupplyKey, Map<String, Map<Any?, Double>>> {
    val result = mutableMapOf<SupplyKey, MutableMap<String, MutableMap<Any?, Double>>>()

    @Suppress("UNCHECKED_CAST")
    fun walk(node: Map<String, Any?>, demandId: Any?) {
        val type = node["type"] as? String
        if (type == "work_order" && node["failed"] == true) return

        if (type == "supply") {
            val pid = (node["product_id"] as? String)?.trim() ?: return
            val lid = (node["location_id"] as? String)?.trim() ?: return
            val sid = (node["supply_id"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return
            val qty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
            if (qty > 1e-12) {
                val sk = SupplyKey(pid, lid)
                result.getOrPut(sk) { mutableMapOf() }
                    .getOrPut(sid) { mutableMapOf() }
                    .merge(demandId, qty) { a, b -> a + b }
            }
        }

        val nextId = if (type == "demand") node["demand_id"] else demandId
        (node["children"] as? List<Map<String, Any?>>)?.forEach { walk(it, nextId) }
    }

    for (entry in pegging) {
        val demandId = entry["demand_id"]
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as? Map<String, Any?> ?: continue
        walk(tree, demandId)
    }
    return result
}

private fun filterToCritical(matrix: NeedsMatrix, purchasable: Set<String>): NeedsMatrix {
    val newByColumn = matrix.byColumn.filterKeys { it.productId !in purchasable }
    if (newByColumn.isEmpty()) return NeedsMatrix(emptyMap(), emptyMap())
    val newByRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()
    for ((sk, demandNeeds) in newByColumn) {
        for ((demandId, qty) in demandNeeds) {
            newByRow.getOrPut(demandId) { mutableMapOf() }[sk] = qty
        }
    }
    return NeedsMatrix(newByRow, newByColumn)
}

/** Keeps only supply columns whose productId is in [productIds]; rebuilds byRow accordingly. */
private fun filterToProductIds(matrix: NeedsMatrix, productIds: Set<String>): NeedsMatrix {
    val newByColumn = matrix.byColumn.filterKeys { it.productId in productIds }
    if (newByColumn.isEmpty()) return NeedsMatrix(emptyMap(), emptyMap())
    val newByRow = mutableMapOf<Any?, MutableMap<SupplyKey, Double>>()
    for ((sk, demandNeeds) in newByColumn) {
        for ((demandId, qty) in demandNeeds) {
            newByRow.getOrPut(demandId) { mutableMapOf() }[sk] = qty
        }
    }
    return NeedsMatrix(newByRow, newByColumn)
}
