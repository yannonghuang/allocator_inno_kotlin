package com.allocator.services

/**
 * Preferences KB build engine — a precomputed, persisted ranking of alternative
 * fulfillment methods (make/move/purchase) and BOM alt_group variants, replacing the
 * old "elaborate mode"'s on-the-fly per-run simulation with a one-time, memoized,
 * static BOM-structure walk.
 *
 * For every (product_id, location_id) node, every alternative (one per method-type,
 * further split one-per-BOM-alt_group for "make" methods with multiple alt_groups —
 * the exact same enumeration [expandWaterfallCandidates] already does for planning)
 * is scored on three axes, walking its own children down to a configurable
 * `max_bom_depth`:
 *
 *  - **Inventory coverage** — how many units of the parent this alternative could
 *    support purely from currently on-hand stock reachable in its own subtree
 *    (AND-min across required children, mirroring [computeRawAchievable]'s logic
 *    but over static totals rather than live per-demand quantities). `purchase`
 *    scores 0 (no children, no stock draw by definition).
 *  - **Delivery performance** — cumulative lead time along the critical (slowest)
 *    path down the same subtree, via [leadDaysForMethod].
 *  - **Critical-material usage** — total count of BOM edges anywhere in the subtree
 *    whose child is a critical material ([isRawCriticalPosition]), summed across the
 *    whole subtree and NOT deduplicated by material identity (the same physical
 *    material reused by 3 different components counts 3). Higher usage is worse —
 *    an alternative that leans on more scarce, non-elastic materials should rank
 *    lower, all else equal.
 *
 * The first two axes are deliberately NOT coupled to `purchase_allowed`/
 * `purchasable_materials` — those admission gates are already applied upstream of
 * [expandWaterfallCandidates] during planning, so a KB entry only ever matters for
 * candidates that already passed them. The third axis is a deliberate, precedented
 * exception: [isRawCriticalPosition] itself depends on `config` (criterion 2, "bought
 * but excluded from purchasable_materials"), so a case's `config` is now threaded
 * through the whole build — the exact same tradeoff `Allocation.kt`'s
 * `generateAndSeedCaseAllocation` already makes for the identical reason. As with
 * that precedent, this means critical-material scores can go stale if
 * `purchase_allowed`/`purchasable_materials` changes after a KB is generated, until
 * the KB is regenerated — the first two axes remain a pure function of case data.
 *
 * This file is intentionally DB-free (pure functions over `data`); persistence lives
 * in `api/Preferences.kt`, mirroring how [buildSupplyAllocation] (pure) is split from
 * `Allocation.kt`'s `generateAndSeedCaseAllocation` (DB read/write).
 */

/**
 * A node's structural best-case, per axis, INDEPENDENTLY (the alternative that
 * maximizes coverage need not be the same alternative that minimizes lead time, nor
 * the one using the fewest critical materials). This keeps the metric weight-independent
 * and cheaply reusable by parents, regardless of what delivery/inventory/critical-material
 * weights a later scoring pass applies.
 */
internal data class NodeMetrics(
    val bestCoverageUnits: Double,
    val bestCumulativeLeadDays: Double,
    val bestCriticalMaterialUsage: Double,
)

internal data class PreferenceCandidateRow(
    val productId: String,
    val locationId: String,
    val methodType: String,
    val methodKey: String,
    val preference: Int,
    val inventoryScore: Double?,
    val deliveryScore: Double?,
    val criticalMaterialScore: Double?,
)

/** One persisted alternative's KB row, as consulted at planning time: the canonical ordinal
 *  [preference] (used everywhere today) plus the raw, pre-normalization axis values that fed
 *  it — carried through so [reconstructNodeScores] can rebuild the continuous combined score
 *  a node's alternatives were originally ranked by (needed for proportional splitting; the
 *  ordinal alone carries no magnitude information).
 *
 *  Not `internal`: threaded as a parameter type through the public [plan] function, so Kotlin
 *  requires it to be at least as visible as [plan] itself. */
data class PreferenceKbEntry(
    val preference: Int,
    val inventoryScore: Double?,
    val deliveryScore: Double?,
    val criticalMaterialScore: Double?,
)

/** Runtime view of a case's Preferences KB: per-alternative entries plus the delivery/
 *  inventory/critical-material weights used to build them (needed to recombine
 *  [PreferenceKbEntry]'s raw axis values back into a comparable score — see
 *  [reconstructNodeScores]). Not `internal`, for the same reason as [PreferenceKbEntry]. */
data class PreferenceKb(
    val entries: Map<Triple<String, String, String>, PreferenceKbEntry>,
    val deliveryWeight: Double,
    val inventoryWeight: Double,
    val criticalMaterialWeight: Double,
)

/** Sentinel for "unreachable" (a cycle, or no method and no stock) — always ranks last,
 *  never divides by zero, never lets an infeasible branch look artificially good. */
private const val INFEASIBLE_LEAD_DAYS = Double.MAX_VALUE

/**
 * Shared by build-time scoring AND runtime lookup ([kbPreference] in PlanningEngine.kt,
 * called identically from the live commit, the sketch phase, and the diamond-allocation
 * gather pass) — the single source of truth for how an alternative's identity is derived,
 * so none of them drift out of sync.
 */
internal fun preferenceMethodKey(method: Map<String, Any?>, altKey: String?): String = when (method["type"]) {
    "make" -> "${(method["bom_id"] as? String)?.trim() ?: ""}:${altKey ?: ""}"
    "move" -> (method["from_location_id"] as? String)?.trim() ?: ""
    "purchase" -> (method["vendor_id"] as? String)?.trim() ?: ""
    else -> ""
}

private fun supplyOnHand(productId: String, locationId: String, supplyByNode: Map<Pair<String, String>, Double>): Double =
    supplyByNode[productId to locationId] ?: 0.0

/** One raw (coverageUnits, cumulativeLeadDays, criticalMaterialUsage) computation for a single
 *  alternative at (productId, locationId) — shared by [computeNodeMetrics]'s per-candidate loop
 *  and [scoreNodeCandidates], so the two can never disagree on how an alternative is scored.
 *  [config] is only consulted for the critical-material axis (via [isRawCriticalPosition]) —
 *  the other two axes remain config-independent, see this file's own header doc. */
private fun candidateRawMetrics(
    method: Map<String, Any?>,
    altKey: String?,
    productId: String,
    locationId: String,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    maxBomDepth: Int,
    supplyByNode: Map<Pair<String, String>, Double>,
    cache: MutableMap<Pair<Pair<String, String>, Int>, NodeMetrics>,
    inProgress: MutableSet<Pair<String, String>>,
    remainingDepth: Int,
): Triple<Double, Double, Double> = when (method["type"]) {
    "purchase" -> Triple(0.0, leadDaysForMethod(method), 0.0)
    "move" -> {
        val fromLid = (method["from_location_id"] as? String)?.trim()
        if (fromLid.isNullOrBlank()) Triple(0.0, INFEASIBLE_LEAD_DAYS, 0.0)
        else {
            val src = computeNodeMetrics(productId, fromLid, data, config, maxBomDepth, supplyByNode, cache, inProgress, remainingDepth - 1)
            // Relocating the same product isn't a new material-usage edge — pass the source's
            // own critical-material count through unchanged, same as coverage.
            Triple(src.bestCoverageUnits, leadDaysForMethod(method) + src.bestCumulativeLeadDays, src.bestCriticalMaterialUsage)
        }
    }
    "make" -> {
        val productionLocation = (method["location_id"] as? String)?.trim() ?: locationId
        val variants = variantsForMake(productId, productionLocation, 1.0, method, data)
        val childList = if (altKey != null) variants.firstOrNull { it.first == altKey }?.second ?: emptyList()
                        else variants.flatMap { it.second }
        if (childList.isEmpty()) Triple(0.0, leadDaysForMethod(method), 0.0)
        else {
            var minCoverage = Double.MAX_VALUE
            var maxChildLead = 0.0
            var feasible = true
            // Cost tally, not an achievability bottleneck — summed across every required
            // child's own edge (this child, if critical) plus whatever critical materials its
            // own subtree already uses.
            var sumCriticalUsage = 0.0
            for (child in childList) {
                val cPid = (child["product_id"] as? String)?.trim() ?: continue
                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                val rate = (child["quantity"] as? Number)?.toDouble() ?: 1.0
                if (rate <= 0) continue
                val cm = computeNodeMetrics(cPid, cLid, data, config, maxBomDepth, supplyByNode, cache, inProgress, remainingDepth - 1)
                val coverageInParentUnits = cm.bestCoverageUnits / rate
                if (coverageInParentUnits < minCoverage) minCoverage = coverageInParentUnits
                if (cm.bestCumulativeLeadDays >= INFEASIBLE_LEAD_DAYS) feasible = false
                else if (cm.bestCumulativeLeadDays > maxChildLead) maxChildLead = cm.bestCumulativeLeadDays
                val childOwnEdge = if (isRawCriticalPosition(cPid, cLid, data, config)) 1.0 else 0.0
                sumCriticalUsage += childOwnEdge + cm.bestCriticalMaterialUsage
            }
            if (minCoverage == Double.MAX_VALUE) minCoverage = 0.0
            Triple(minCoverage, if (feasible) leadDaysForMethod(method) + maxChildLead else INFEASIBLE_LEAD_DAYS, sumCriticalUsage)
        }
    }
    else -> Triple(0.0, INFEASIBLE_LEAD_DAYS, 0.0)
}

/**
 * Bottom-up, memoized (pid, lid, remainingDepth) walk — same cache+inProgress shape as
 * [maxMakeDepth], extended with a depth-qualified cache key so a node reached at different
 * remaining-depth budgets from different callers is never incorrectly shared (a node
 * closer to the [maxBomDepth] cutoff along one path must not silently reuse a deeper,
 * more-explored result computed for a shallower path).
 *
 * `remainingDepth` counts DOWN from [maxBomDepth]; once negative, recursion stops and the
 * node is treated as a leaf — its own on-hand stock is the only visible signal beyond that
 * horizon (lead time 0, "already exists, nothing more assumed").
 *
 * Cycle guard: hitting a node already on the call stack returns a sentinel
 * (`coverage = 0.0`, `leadDays = INFEASIBLE`) WITHOUT caching it — a cycle's apparent
 * reachability depends on the path that found it, so it must not poison the shared cache
 * for other, non-cyclic callers reaching the same node.
 */
internal fun computeNodeMetrics(
    productId: String,
    locationId: String,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    maxBomDepth: Int,
    supplyByNode: Map<Pair<String, String>, Double>,
    cache: MutableMap<Pair<Pair<String, String>, Int>, NodeMetrics>,
    inProgress: MutableSet<Pair<String, String>> = mutableSetOf(),
    remainingDepth: Int = maxBomDepth,
): NodeMetrics {
    val node = productId to locationId
    val cacheKey = node to remainingDepth
    cache[cacheKey]?.let { return it }
    // Direct on-hand supply at this node is always visible without recursing into any
    // method — mirrors the real planner's consumeFromInventory, which draws existing stock
    // BEFORE invoking make/move/buy regardless of whether methods are also defined. Checked
    // unconditionally (even mid-cycle below) since it never recurses and is always cheap.
    val directSupply = supplyOnHand(productId, locationId, supplyByNode)
    if (!inProgress.add(node)) {
        // Cycle: this path can't resolve further via methods, but direct stock still counts.
        return if (directSupply > 0) NodeMetrics(directSupply, 0.0, 0.0) else NodeMetrics(0.0, INFEASIBLE_LEAD_DAYS, 0.0)
    }
    try {
        if (remainingDepth < 0) {
            return NodeMetrics(directSupply, 0.0, 0.0).also { cache[cacheKey] = it }
        }
        val methods = getMethods(productId, locationId, data)
        // Dedupe by natural key: identical (type, methodKey) candidates are structurally
        // identical (see scoreNodeCandidates) and would otherwise be rescored redundantly —
        // matters here because some products have dozens of method_make rows sharing one
        // bom_id, only their `preference` differing.
        val candidates = expandWaterfallCandidates(methods, productId, emptyMap(), null, data)
            .distinctBy { c -> c.method["type"] to preferenceMethodKey(c.method, c.altKey) }
        var bestCoverage = directSupply
        var bestLead = if (directSupply > 0) 0.0 else INFEASIBLE_LEAD_DAYS
        // On-hand stock needs 0 further critical materials — a "free" option, same rationale
        // as bestLead's 0.0 init. Collapsed back from the sentinel below if no candidate beats it.
        var bestCriticalUsage = if (directSupply > 0) 0.0 else Double.MAX_VALUE
        for (c in candidates) {
            val (cov, lead, criticalUsage) = candidateRawMetrics(
                c.method, c.altKey, productId, locationId, data, config, maxBomDepth,
                supplyByNode, cache, inProgress, remainingDepth,
            )
            if (cov > bestCoverage) bestCoverage = cov
            if (lead < bestLead) bestLead = lead
            if (criticalUsage < bestCriticalUsage) bestCriticalUsage = criticalUsage
        }
        if (bestCriticalUsage == Double.MAX_VALUE) bestCriticalUsage = 0.0
        val nm = NodeMetrics(bestCoverage, bestLead, bestCriticalUsage)
        cache[cacheKey] = nm
        return nm
    } finally {
        inProgress.remove(node)
    }
}

/** Normalize three weights to sum to 1; falls back to an even 1/3 each split when all three are
 *  non-positive (mirrors the deleted normalizeScoreWeights precedent). Internal (not private):
 *  reused by [reconstructNodeScores] to recombine axes with the exact same formula
 *  [scoreNodeCandidates] used at build time. */
internal fun normalizeWeights(
    deliveryWeight: Double,
    inventoryWeight: Double,
    criticalMaterialWeight: Double,
): Triple<Double, Double, Double> {
    val total = deliveryWeight + inventoryWeight + criticalMaterialWeight
    return if (total <= 0.0) Triple(1.0 / 3, 1.0 / 3, 1.0 / 3)
           else Triple(deliveryWeight / total, inventoryWeight / total, criticalMaterialWeight / total)
}

/**
 * Ranks every alternative at (productId, locationId): computes each candidate's own raw
 * (coverage, leadDays, criticalMaterialUsage) via [candidateRawMetrics], min-max normalizes all
 * three axes across this node's sibling set, combines via the given weights, and assigns
 * canonical preference 10, 20, 30, ... in descending-score order. Ties keep the input candidate
 * order (Kotlin's `sortedWith` is stable), which itself follows BOM/method declaration order via
 * [expandWaterfallCandidates] / [getMethods] — deterministic for a fixed data snapshot.
 *
 * Returns an empty list when the node has no methods (nothing to rank/persist).
 */
internal fun scoreNodeCandidates(
    productId: String,
    locationId: String,
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    maxBomDepth: Int,
    deliveryWeight: Double,
    inventoryWeight: Double,
    criticalMaterialWeight: Double,
    supplyByNode: Map<Pair<String, String>, Double>,
    cache: MutableMap<Pair<Pair<String, String>, Int>, NodeMetrics>,
): List<PreferenceCandidateRow> {
    val methods = getMethods(productId, locationId, data)
    if (methods.isEmpty()) return emptyList()
    // Dedupe by natural key: it's common for multiple method_make rows to share the same
    // bom_id (and therefore alt_group) with only their `preference` differing — the
    // dominant real-world way "N tiers of the same recipe" is modeled. Such rows are
    // structurally IDENTICAL alternatives (same children, same coverage/lead score) and
    // collapse to the same case_preference natural key, so persisting more than one would
    // violate the unique constraint. Keeping the first is safe: at runtime every one of
    // those method rows shares this methodKey, so all of them receive the same KB
    // override via kbPreference's lookup regardless of which specific row planning picks.
    val candidates = expandWaterfallCandidates(methods, productId, emptyMap(), null, data)
        .distinctBy { c -> c.method["type"] to preferenceMethodKey(c.method, c.altKey) }
    if (candidates.isEmpty()) return emptyList()

    data class Raw(val c: WaterfallCandidate, val cov: Double, val lead: Double, val critical: Double)
    val raw = candidates.map { c ->
        val (cov, lead, critical) = candidateRawMetrics(
            c.method, c.altKey, productId, locationId, data, config, maxBomDepth,
            supplyByNode, cache, mutableSetOf(productId to locationId), maxBomDepth,
        )
        Raw(c, cov, lead, critical)
    }

    val covMax = raw.maxOf { it.cov }
    val covMin = raw.minOf { it.cov }
    val covSpan = (covMax - covMin).let { if (it <= 0.0) 1.0 else it }
    val finiteLeads = raw.map { it.lead }.filter { it < INFEASIBLE_LEAD_DAYS }
    val leadMax = finiteLeads.maxOrNull() ?: 0.0
    val leadMin = finiteLeads.minOrNull() ?: 0.0
    val leadSpan = (leadMax - leadMin).let { if (it <= 0.0) 1.0 else it }
    val critMax = raw.maxOf { it.critical }
    val critMin = raw.minOf { it.critical }
    val critSpan = (critMax - critMin).let { if (it <= 0.0) 1.0 else it }
    val (dw, iw, cw) = normalizeWeights(deliveryWeight, inventoryWeight, criticalMaterialWeight)

    data class Scored(val c: WaterfallCandidate, val cov: Double, val lead: Double, val critical: Double, val score: Double)
    val scored = raw.map { r ->
        val normCov = ((r.cov - covMin) / covSpan).coerceIn(0.0, 1.0)
        val normDelivery = if (r.lead >= INFEASIBLE_LEAD_DAYS) 0.0 else (1.0 - (r.lead - leadMin) / leadSpan).coerceIn(0.0, 1.0)
        // Inverted, same convention as delivery — lower critical-material usage is better.
        val normCritical = (1.0 - (r.critical - critMin) / critSpan).coerceIn(0.0, 1.0)
        Scored(r.c, r.cov, r.lead, r.critical, dw * normDelivery + iw * normCov + cw * normCritical)
    }
    val ranked = scored.sortedWith(compareByDescending { it.score })

    return ranked.mapIndexed { i, s ->
        PreferenceCandidateRow(
            productId = productId,
            locationId = locationId,
            methodType = s.c.method["type"] as? String ?: "",
            methodKey = preferenceMethodKey(s.c.method, s.c.altKey),
            preference = (i + 1) * 10,
            inventoryScore = s.cov.takeIf { it.isFinite() },
            deliveryScore = s.lead.takeIf { it < INFEASIBLE_LEAD_DAYS },
            // Always well-defined at build time (no infeasible state for this axis) — unlike
            // the other two, no takeIf filter; stays nullable at the type/DB level purely to
            // represent legacy rows persisted before this axis existed.
            criticalMaterialScore = s.critical,
        )
    }
}

/**
 * Top-level entry: enumerate every (product_id, location_id) reachable from
 * `data["demand"]` via [buildBomGraph]'s existing reachability walk (do not re-derive
 * reachability a second way), score each, and flatten into the full row set to persist.
 */
internal fun buildPreferenceKb(
    data: Map<String, List<Map<String, Any?>>>,
    config: Map<String, Any?>?,
    maxBomDepth: Int,
    deliveryWeight: Double,
    inventoryWeight: Double,
    criticalMaterialWeight: Double,
): List<PreferenceCandidateRow> {
    val demands = data["demand"] ?: emptyList()
    val nodes = buildBomGraph(demands, data).topoOrder
    val supplyByNode: Map<Pair<String, String>, Double> = (data["supply"] ?: emptyList())
        .mapNotNull { row ->
            val pid = (row["product_id"] as? String)?.trim() ?: return@mapNotNull null
            val lid = (row["location_id"] as? String)?.trim() ?: return@mapNotNull null
            if (pid.isBlank() || lid.isBlank()) null else Triple(pid, lid, (row["qty"] as? Number)?.toDouble() ?: 0.0)
        }
        .groupBy({ (pid, lid, _) -> pid to lid }, { (_, _, qty) -> qty })
        .mapValues { (_, qtys) -> qtys.sum() }

    val cache = mutableMapOf<Pair<Pair<String, String>, Int>, NodeMetrics>()
    return nodes.flatMap { (pid, lid) ->
        scoreNodeCandidates(pid, lid, data, config, maxBomDepth, deliveryWeight, inventoryWeight, criticalMaterialWeight, supplyByNode, cache)
    }
}

/**
 * Reconstructs each of [candidates]'s continuous combined score (the same 0..1 value
 * [scoreNodeCandidates] computed at build time to rank them into the persisted ordinal
 * `preference`) from [preferenceKb]'s persisted raw axis values — needed because the
 * continuous score itself is never persisted, only the ordinal. Normalizes across the FULL
 * `candidates` set passed in (mirrors [scoreNodeCandidates]'s normalization scope: this
 * node's entire sibling set, not just a top slice a caller might go on to use), so the
 * reconstructed scores stay consistent with the ranking already visible in `preference`.
 *
 * Returns `null` — defer to the caller's own fallback — unless every one of [candidates] has
 * a KB entry with a non-null [PreferenceKbEntry.inventoryScore] AND non-null
 * [PreferenceKbEntry.criticalMaterialScore] (the "per-alternative fallback" principle used
 * elsewhere: partial coverage at this node isn't enough to trust a reconstructed magnitude
 * comparison across candidates). Critical-material score gets the same strict, all-or-nothing
 * treatment as inventory score (not the lenient per-candidate handling used for delivery/lead,
 * which is allowed to be individually null for a genuinely infeasible candidate) — a null
 * critical-material score can only mean the KB predates this axis, never "infeasible," so a
 * mix of real and missing values here would make span-normalization meaningless.
 */
internal fun reconstructNodeScores(
    productId: String,
    locationId: String,
    candidates: List<WaterfallCandidate>,
    preferenceKb: PreferenceKb,
): List<Double>? {
    val entries = candidates.map { c ->
        preferenceKb.entries[Triple(productId, locationId, preferenceMethodKey(c.method, c.altKey))] ?: return null
    }
    if (entries.any { it.inventoryScore == null }) return null
    if (entries.any { it.criticalMaterialScore == null }) return null

    val covs = entries.map { it.inventoryScore!! }
    val covMax = covs.max()
    val covMin = covs.min()
    val covSpan = (covMax - covMin).let { if (it <= 0.0) 1.0 else it }
    val leads = entries.map { it.deliveryScore }
    val finiteLeads = leads.filterNotNull()
    val leadMax = finiteLeads.maxOrNull() ?: 0.0
    val leadMin = finiteLeads.minOrNull() ?: 0.0
    val leadSpan = (leadMax - leadMin).let { if (it <= 0.0) 1.0 else it }
    val crits = entries.map { it.criticalMaterialScore!! }
    val critMax = crits.max()
    val critMin = crits.min()
    val critSpan = (critMax - critMin).let { if (it <= 0.0) 1.0 else it }
    val (dw, iw, cw) = normalizeWeights(preferenceKb.deliveryWeight, preferenceKb.inventoryWeight, preferenceKb.criticalMaterialWeight)

    return entries.indices.map { i ->
        val normCov = ((covs[i] - covMin) / covSpan).coerceIn(0.0, 1.0)
        val normDelivery = leads[i]?.let { (1.0 - (it - leadMin) / leadSpan).coerceIn(0.0, 1.0) } ?: 0.0
        val normCritical = (1.0 - (crits[i] - critMin) / critSpan).coerceIn(0.0, 1.0)
        dw * normDelivery + iw * normCov + cw * normCritical
    }
}
