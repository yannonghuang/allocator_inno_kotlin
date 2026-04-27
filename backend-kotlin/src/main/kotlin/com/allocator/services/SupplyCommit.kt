package com.allocator.services

/**
 * Phase 3a of supply-level consolidation — per-demand initial commit.
 *
 * Each demand walks its BOM via the existing [plan] recursive walker, with
 * per-demand budget caps fed from [SupplyAllocations]. The walker draws from
 * each supply up to its allocated cap, emits committed rows, work orders,
 * and a per-demand pegging tree.
 *
 * This is the "initial commit" half of Phase 3. The compensation sub-phase
 * (3b) runs afterward to redistribute any unused allocation; see
 * docs/supply-level-consolidation.md §3 (mental-model diagram) and §5
 * (iteration / compensation).
 *
 * ## Why we can reuse [plan] unchanged
 *
 * `plan()` already accepts a `budget: MutableMap<String, Double>?` keyed
 * by `"$pid|$lid"` that caps per-component consumption and is decremented
 * in-place. The leaf-engine uses this for its merged-leaf budget; we feed
 * it from the supply-level allocation map. Same mechanism, different scope.
 *
 * ## What we track post-commit
 *
 * Comparing the budget snapshot before vs after `plan()` returns gives us
 * `actualDraws[D][S]` — what each demand actually consumed at each supply.
 * Compensation (Phase 3b) subtracts these from `allocation` to find unused
 * capacity that can be redistributed.
 */

/** Output of Phase 3a's initial commit. */
data class InitialCommitResult(
    /** One row per (demand, commit_reason) tuple — input to plan-run persistence. */
    val committedDemands: List<Map<String, Any?>>,
    /** Work orders emitted by the per-demand BOM walks. */
    val workOrders: List<Map<String, Any?>>,
    /** Per-demand pegging tree, one entry per demand with a non-null tree. */
    val planningPegging: List<Map<String, Any?>>,
    /**
     * What each demand actually drew at each supply during 3a.
     * `actualDraws[D][S] = allocation[D][S] − remainingBudget[D][S]`,
     * coerced to ≥ 0. Demands or supplies with zero draw are omitted.
     *
     * Phase 3b (compensation) compares this against the input allocations
     * to identify unused capacity and redistribute it.
     */
    val actualDraws: Map<Any?, Map<SupplyKey, Double>>,
)

/**
 * Run Phase 3a. Iterates demands in input order; each demand's `plan()` call
 * mutates [inventory] (consuming real supplies) and the per-demand budget
 * map (in-place). [allocations] is read-only.
 *
 * Demand iteration order matters under shortage: when allocations are
 * exhausted, a later demand sees less inventory than an earlier one. Today's
 * leaf-engine sorts demands by priority then demandId for determinism;
 * supply-level consolidation gets this ordering from the [SupplyAllocations]
 * already (the policy split distributed shares before commit), so the order
 * here is mostly cosmetic — the budget cap is the real gatekeeper.
 *
 * Even so, we iterate in caller-supplied order so the caller can choose to
 * sort by priority/dueDate/etc. for deterministic logs and pegging trees.
 */
fun runInitialCommit(
    demands: List<Map<String, Any?>>,
    inventory: MutableList<MutableMap<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    overrideIndex: Map<String, Map<String, Any?>>,
    allocations: SupplyAllocations,
    progressCallback: ((Map<String, Any?>) -> Unit)? = null,
): InitialCommitResult {
    val committedDemands = mutableListOf<Map<String, Any?>>()
    val workOrders = mutableListOf<Map<String, Any?>>()
    val planningPegging = mutableListOf<Map<String, Any?>>()
    val actualDraws = mutableMapOf<Any?, Map<SupplyKey, Double>>()
    val total = demands.size

    // Build the supply key set once. Used to filter inventory deltas down to
    // supply-level keys (intermediate inventory consumption isn't tracked here).
    val supplyKeys: Set<SupplyKey> = (data["supply"] ?: emptyList())
        .filter { ((it["qty"] as? Number)?.toDouble() ?: 0.0) > 0 }
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank()) null else SupplyKey(pid, lid)
        }
        .toSet()

    demands.forEachIndexed { i, d ->
        val demandId = d["demand_id"]
        val reqStr = d["request_due_time"] as? String ?: d["request_time"] as? String
        val reqDt = parseDate(reqStr)

        // Build the per-demand budget map keyed by "$pid|$lid" (matches plan()'s
        // expectation). plan() decrements in place as it draws.
        val initialAllocation: Map<SupplyKey, Double> = allocations.byRow[demandId] ?: emptyMap()
        val budget: MutableMap<String, Double> = initialAllocation
            .mapKeys { (sk, _) -> sk.toString() }
            .toMutableMap()

        // Snapshot inventory totals per supply key BEFORE plan() so we can
        // compute this demand's actual consumption regardless of whether the
        // matrix knew about each supply. Matrix misses (demand draws from S
        // without an allocation) are caught here as inventory deltas, not as
        // budget deltas — without this, plan() runs unconstrained for the
        // missed (D, S) pair and the orchestrator never learns about it,
        // re-instating the FIFO-style competition the supply engine is meant
        // to eliminate.
        val invBefore = computeInventoryByKey(inventory, supplyKeys)

        val (solvedList, wos, peggingNode) = plan(
            d, inventory, data, reqDt,
            config = config,
            preferDemandId = null,    // supply-level consolidation has no tagged synthetic buckets
            overrideIndex = overrideIndex,
            budget = budget,
        )

        committedDemands.addAll(solvedList)
        workOrders.addAll(wos)
        if (peggingNode != null && demandId != null) {
            planningPegging.add(mapOf("demand_id" to demandId, "tree" to peggingNode))
        }

        // Compute draws via inventory delta — captures both matrix-known
        // supplies (where alloc - remaining matches) and matrix-miss supplies
        // (where the budget map had no entry, so plan() drew unconstrained).
        val invAfter = computeInventoryByKey(inventory, supplyKeys)
        val draws = mutableMapOf<SupplyKey, Double>()
        for (sk in supplyKeys) {
            val drew = ((invBefore[sk] ?: 0.0) - (invAfter[sk] ?: 0.0)).coerceAtLeast(0.0)
            if (drew > 1e-12) draws[sk] = drew
        }
        if (draws.isNotEmpty()) actualDraws[demandId] = draws

        progressCallback?.invoke(mapOf("current" to i + 1, "total" to total, "demand_id" to demandId))
    }

    return InitialCommitResult(committedDemands, workOrders, planningPegging, actualDraws)
}

/** Sum inventory qty per (pid, lid), restricted to the given supply key set. */
private fun computeInventoryByKey(
    inventory: List<Map<String, Any?>>,
    supplyKeys: Set<SupplyKey>,
): Map<SupplyKey, Double> {
    val result = mutableMapOf<SupplyKey, Double>()
    for (b in inventory) {
        val pid = (b["product_id"] as? String)?.trim() ?: continue
        val lid = (b["location_id"] as? String)?.trim() ?: continue
        val sk = SupplyKey(pid, lid)
        if (sk !in supplyKeys) continue
        val qty = (b["qty"] as? Number)?.toDouble() ?: continue
        result.merge(sk, qty, Double::plus)
    }
    return result
}
