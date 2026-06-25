package com.allocator.services

import org.slf4j.LoggerFactory
import java.time.LocalDate

private val log = LoggerFactory.getLogger("com.allocator.ResolutionEngine")

// ── BomAncestry ──────────────────────────────────────────────────────────────
//
// Index of BOM rows that supports cumulative-rate lookups between any
// (ancestor, descendant) pair. Used by the merge step in Phase 2 to
// translate user-demand quantities into per-component quantities at the
// shared component (the "deepest leaf" across the resolved paths).

/**
 * Indexes BOM rows so [cumulativeRate] can answer "if I need 1 unit of
 * `descendant`, how many units does that imply 1 unit of `ancestor` consume?"
 *
 * The traversal is purely structural — it ignores alt_groups (treats every
 * child as reachable). Since [walkResolution] now enumerates every alt
 * branch into its own [ResolutionPath], each path records exactly one
 * alternative chain; ancestry-level rate is just the arithmetic of
 * multiplying rates along that chain.
 */
class BomAncestry(bom: List<Map<String, Any?>>) {

    /** parent_id → list of (child_id, rate). Aggregated across all bom rows. */
    private val outgoing: Map<String, List<Pair<String, Double>>>

    init {
        val map = mutableMapOf<String, MutableList<Pair<String, Double>>>()
        for (row in bom) {
            val parent = (row["parent_id"] as? String)?.trim() ?: continue
            val child  = (row["child_id"]  as? String)?.trim() ?: continue
            val rate   = (row["rate"]      as? Number)?.toDouble() ?: 1.0
            if (parent.isBlank() || child.isBlank() || rate <= 0) continue
            map.getOrPut(parent) { mutableListOf() }.add(child to rate)
        }
        outgoing = map
    }

    /**
     * Return the cumulative rate from [ancestor] down to [descendant]
     * along the shortest BOM path, or null if no path exists. When the
     * same descendant is reachable via multiple paths the first one
     * encountered (BFS, deterministic by insertion order of bom rows)
     * wins — consolidation paths produced by [ResolutionEngine] always
     * record the unambiguous chain so this fallback only matters when a
     * caller asks ancestry-level questions outside a resolved chain.
     */
    fun cumulativeRate(ancestor: String, descendant: String): Double? {
        if (ancestor == descendant) return 1.0
        // BFS — visited holds the cumulative rate at which each pid was first reached.
        val visited = mutableMapOf(ancestor to 1.0)
        val queue = ArrayDeque<String>()
        queue.add(ancestor)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val curRate = visited[cur] ?: continue
            val children = outgoing[cur] ?: continue
            for ((childPid, rate) in children) {
                if (childPid in visited) continue
                val cumulative = curRate * rate
                visited[childPid] = cumulative
                if (childPid == descendant) return cumulative
                queue.add(childPid)
            }
        }
        return null
    }

    /** True iff [ancestor] is a structural BOM ancestor of [descendant] (and they differ). */
    fun isAncestor(ancestor: String, descendant: String): Boolean =
        ancestor != descendant && cumulativeRate(ancestor, descendant) != null
}

// ── ResolutionGraph ───────────────────────────────────────────────────────────
//
// For every demand, the resolution engine walks BOM/methods inventory-blind
// and records one path per "leaf" — the first inventory-bearing node it
// reaches along a chosen alternative. The graph is shape-only; quantities
// are recorded as cumulative rates so the merge step can scale them by the
// user demand's quantity.

/** A node in a resolved chain. Always part of a [ResolutionPath]. */
data class ResolutionNode(
    val productId: String,
    val locationId: String,
    /** Cumulative rate from the root demand's product to this node. */
    val cumulativeRate: Double,
    /** 0 = root demand. */
    val depth: Int,
    /** Method chosen at this node (null at the leaf when the leaf is inventory-bearing). */
    val method: Map<String, Any?>?,
    /** True when traversal stopped here (inventory-bearing or terminal purchase). */
    val isLeaf: Boolean,
    /** Supply lot identifier — set on inventory-bearing leaves, null elsewhere. */
    val supplyId: String? = null,
    /** Arrival date of this supply lot — set on inventory-bearing leaves, null elsewhere. */
    val supplyDate: LocalDate? = null,
    /** Available quantity of this specific supply lot. */
    val supplyQty: Double? = null,
)

/**
 * One resolved chain from a user demand to a leaf component. Nodes are ordered
 * root → leaf. Multiple paths per demand exist when a make BOM splits across
 * alt_groups (each alt_group contributes one chosen child).
 */
data class ResolutionPath(
    val demandId: Any?,
    val priority: Int,
    val dueDate: LocalDate?,
    val requestedQty: Double,
    val nodes: List<ResolutionNode>,
) {
    /** The terminal node this path resolves to. */
    val leaf: ResolutionNode get() = nodes.last()
    /** The user demand's product (root). */
    val root: ResolutionNode get() = nodes.first()
    /** Quantity at the leaf, scaled by the demand's requested qty. */
    fun leafQuantity(): Double = requestedQty * leaf.cumulativeRate
}

/** All resolved paths for a planning run. */
data class ResolutionGraph(
    val paths: List<ResolutionPath>,
)

// ── Resolution walk ───────────────────────────────────────────────────────────

/**
 * Build a [ResolutionGraph] for the given demands. Walks BOM/methods
 * inventory-blind: every method is selected via simple preference, with no
 * cascade probe. **Each alt_group enumerates every child** — the walk emits
 * one [ResolutionPath] per alternative branch, not one per chosen alternative.
 * Stops at the first inventory-bearing (pid, lid).
 *
 * Inventory-blindness is intentional. Phase 1's job is to declare *what
 * candidate leaves merged-group consolidation could land at*; Phase 3 (commit)
 * is the one that actually picks the alternative against current supply +
 * preference scoring. Letting inventory steer Phase 1 produces the cascade
 * thrash we are trying to avoid (different demands picking different
 * alternatives for the same component, breaking the merge-structure
 * invariant the cap loop relies on).
 *
 * Union-alt rationale: with pick-one, when Phase 3 routes through alt B'
 * but Phase 1 picked B, the consolidation benefit is lost — D's C' WO is
 * built standalone instead of merged with peers. Worst when many demands
 * diverge together to the same actually-used alt. Union-alt provisions
 * merged groups at every alt's leaf; the cap loop drives unpicked alts
 * to zero in 1-2 iters; the picked alt retains full consolidation.
 */
fun buildResolutionGraph(
    demands: List<Map<String, Any?>>,
    data: Map<String, List<Map<String, Any?>>>,
): ResolutionGraph {
    val supplyIndex = buildSupplyIndexForResolution(data)
    val paths = mutableListOf<ResolutionPath>()
    for (d in demands) {
        val productId  = (d["product_id"]  as? String)?.trim() ?: continue
        val locationId = (d["location_id"] as? String)?.trim() ?: continue
        val qty        = (d["quantity"]    as? Number)?.toDouble() ?: continue
        if (qty <= 0) continue
        val demandId = d["demand_id"]
        val priority = (d["priority"] as? Number)?.toInt() ?: 0
        val reqStr   = d["request_due_time"] as? String ?: d["request_time"] as? String
        val reqDt    = parseDateOrNull(reqStr)

        val collected = mutableListOf<ResolutionPath>()
        walkResolution(
            productId = productId,
            locationId = locationId,
            cumulativeRate = 1.0,
            depth = 0,
            data = data,
            supplyIndex = supplyIndex,
            visited = emptySet(),
            chain = emptyList(),
            demandId = demandId,
            priority = priority,
            dueDate = reqDt,
            requestedQty = qty,
            out = collected,
        )
        if (collected.isEmpty()) {
            log.debug("[map][request] demand={} product={}@{} produced 0 paths (no method/supply reachable)",
                demandId, productId, locationId)
        } else {
            log.debug("[map][request] demand={} product={}@{} paths={} supply-columns={}",
                demandId, productId, locationId, collected.size,
                collected.map { "${it.leaf.productId}@${it.leaf.locationId}" }.toSet().size)
        }
        paths.addAll(collected)
    }
    return ResolutionGraph(paths)
}

/** Convert a [ResolutionGraph] to legacy [ComponentNeed] rows so it can drop into the existing consolidation pipeline. */
fun ResolutionGraph.toComponentNeeds(): List<ComponentNeed> =
    paths.map { p ->
        ComponentNeed(
            productId        = p.leaf.productId,
            locationId       = p.leaf.locationId,
            dueDate          = p.dueDate,
            qty              = p.leafQuantity(),
            demandId         = p.demandId,
            priority         = p.priority,
            parentProductId  = if (p.nodes.size >= 2) p.nodes[p.nodes.size - 2].productId else p.root.productId,
            // v2 enumerates every alt_group child as its own path, so no individual
            // path is "via OR" — each path records exactly one alternative chain.
            viaOrAlternative = false,
        )
    }

// ── Merge step (Phase 2 core) ─────────────────────────────────────────────────
//
// Collapses multi-level groups into a single group at the deepest shared
// component. When two groups share a time bucket and one group's leaf is a BOM
// ancestor of another's, the shallower group's members are promoted into the
// deeper one(s) with rate-converted quantities. This implements the user's
// "Interpretation A": members are user demands; merged-group quantity is the
// sum of rate-converted user-demand quantities at the leaf.
//
// Promotion is structural (inventory-blind). When a shallow leaf has supply
// AND its members do not actually need to make-from-deeper, Phase 3 will
// commit them against shallow supply naturally; the speculative
// pre-allocation at the deep group may over-produce. This artifact is
// resolved iteratively in Stage 4 (the iteration controller converges
// production qty to actual need).

/** A user-demand contribution to a merged group, with quantity expressed at the merged leaf. */
data class MergedMember(
    val demandId: Any?,
    /** Quantity at the merged-group leaf (origin-leaf qty × promotion rate). */
    val qty: Double,
    val priority: Int,
    val dueDate: java.time.LocalDate?,
    /** The leaf where the demand's resolution path actually stopped (before any promotion). */
    val originalLeafPid: String,
    val originalLeafLid: String,
    /** Cumulative BOM rate from [originalLeafPid] down to the merged group's leaf (1.0 if not promoted). */
    val promotionRate: Double,
)

/** A consolidation group for one specific supply lot competing across demands. */
data class MergedGroup(
    val leafPid: String,
    val leafLid: String,
    /** Supply lot identifier — null means aggregated (all lots at this (pid, lid)). */
    val supplyId: String?,
    /** Arrival date of this supply lot — null if not known. */
    val supplyDate: java.time.LocalDate?,
    /** Available quantity of this supply lot. */
    val supplyQty: Double?,
    val timeBucket: java.time.LocalDate,
    val members: List<MergedMember>,
) {
    val totalQty: Double get() = members.sumOf { it.qty }
    /** Budget key used in plan() — "$pid|$lid|$supplyId" when supplyId is known, else "$pid|$lid". */
    val budgetKey: String get() = if (supplyId != null) "$leafPid|$leafLid|$supplyId" else "$leafPid|$leafLid"
}

/**
 * Stage 4b: cap each member's [MergedMember.qty] at the value this demand
 * actually consumed at the merged leaf during the previous iteration.
 *
 * [caps] is keyed by `demand_id`. A demand may contribute multiple members
 * to the same group when split-promotion split it across maximal descendants
 * — caps are applied to the per-demand sum and individual members are scaled
 * proportionally so their relative contribution is preserved. A cap of 0
 * drops the demand entirely; a cap >= the current sum is a no-op.
 *
 * The fixed-point iteration uses this to drive merged-leaf production down
 * to "what the chain actually needs" — phases 2+3 then re-run with
 * correctly-sized WOs at the merged leaf and (transitively) every BOM child
 * of the merged leaf.
 */
fun MergedGroup.withMemberCaps(caps: Map<Any?, Double>): MergedGroup {
    if (caps.isEmpty()) return this
    val byDemand: Map<Any?, List<MergedMember>> = members.groupBy { it.demandId }
    val newMembers = mutableListOf<MergedMember>()
    for ((demandId, demandMembers) in byDemand) {
        val cap = caps[demandId]
        if (cap == null) {
            newMembers.addAll(demandMembers)
            continue
        }
        if (cap <= 1e-9) continue  // drop entirely
        val currentTotal = demandMembers.sumOf { it.qty }
        if (cap >= currentTotal - 1e-9) {
            newMembers.addAll(demandMembers)
            continue
        }
        val scale = cap / currentTotal
        for (m in demandMembers) {
            newMembers.add(m.copy(qty = m.qty * scale))
        }
    }
    return copy(members = newMembers)
}

/**
 * Bucket the graph's paths by (leafPid, leafLid, timeBucket) and promote
 * shallower groups into their deepest BOM descendant(s) — the "merge step".
 *
 * Branching (a shallow group has multiple non-comparable descendants) is
 * handled by split-promotion: each member is duplicated into every maximal
 * descendant with the appropriate rate. This corresponds to the case where
 * the shallow product's BOM has parallel sub-trees that each touch a
 * different deeper group.
 */
fun mergeGroups(
    graph: ResolutionGraph,
    periodDays: Int,
): List<MergedGroup> {
    data class Key(
        val pid: String, val lid: String,
        val supplyId: String?,
        val bucket: java.time.LocalDate,
    )
    data class LotMeta(val supplyDate: java.time.LocalDate?, val supplyQty: Double?)

    // Bucket paths by (leaf supply lot, bucket). Each path becomes one member.
    val raw  = mutableMapOf<Key, MutableList<MergedMember>>()
    val meta = mutableMapOf<Key, LotMeta>()
    for (p in graph.paths) {
        val leaf = p.leaf
        val key  = Key(leaf.productId, leaf.locationId, leaf.supplyId, timeBucket(p.dueDate, periodDays))
        val member = MergedMember(
            demandId        = p.demandId,
            qty             = p.leafQuantity(),
            priority        = p.priority,
            dueDate         = p.dueDate,
            originalLeafPid = leaf.productId,
            originalLeafLid = leaf.locationId,
            promotionRate   = 1.0,
        )
        raw.getOrPut(key) { mutableListOf() }.add(member)
        if (key !in meta) meta[key] = LotMeta(leaf.supplyDate, leaf.supplyQty)
    }

    // Demand map = request map: one group per supply lot per time bucket.
    // No promotion — each lot allocates only the demands that directly requested it.
    return raw.entries
        .map { (k, ms) ->
            val m = meta[k]
            MergedGroup(k.pid, k.lid, k.supplyId, m?.supplyDate, m?.supplyQty, k.bucket, ms.toList())
        }
        .sortedWith(compareBy({ it.timeBucket }, { it.leafPid }, { it.leafLid }, { it.supplyId }))
}

/**
 * Convert a [MergedGroup] to a [ConsolidationGroup] so it can plug into the
 * existing [runConsolidation] pipeline unchanged. Each [MergedMember] becomes
 * a [ComponentNeed]; the parent product proxy is set to the original-leaf
 * pid (so consolidation logging/explanation can still show where the demand
 * came from before promotion).
 */
fun MergedGroup.toConsolidationGroup(): ConsolidationGroup {
    val needs = members.map { m ->
        ComponentNeed(
            productId        = leafPid,
            locationId       = leafLid,
            dueDate          = m.dueDate,
            qty              = m.qty,
            demandId         = m.demandId,
            priority         = m.priority,
            parentProductId  = m.originalLeafPid,
            viaOrAlternative = false,
        )
    }
    return ConsolidationGroup(leafPid, leafLid, timeBucket, needs, totalQty, supplyId)
}

// ── Internals ─────────────────────────────────────────────────────────────────

/** Maps (productId, locationId) → list of supply rows (one per lot) with qty > 0. */
private fun buildSupplyIndexForResolution(
    data: Map<String, List<Map<String, Any?>>>,
): Map<Pair<String, String>, List<Map<String, Any?>>> =
    (data["supply"] ?: emptyList())
        .filter { s -> ((s["qty"] as? Number)?.toDouble() ?: 0.0) > 0 }
        .groupBy { s ->
            val pid = s["product_id"]?.toString()?.trim() ?: return@groupBy ("" to "")
            val lid = s["location_id"]?.toString()?.trim() ?: return@groupBy ("" to "")
            pid to lid
        }
        .filterKeys { (pid, lid) -> pid.isNotBlank() && lid.isNotBlank() }

private fun parseDateOrNull(s: String?): LocalDate? {
    if (s.isNullOrBlank()) return null
    return try { LocalDate.parse(s.trim().take(10)) } catch (_: Exception) { null }
}

/**
 * Recursive walker. Emits one [ResolutionPath] per leaf reached.
 *
 * Termination:
 *   - (productId, locationId) has supply → emit a leaf path, stop.
 *   - method is `purchase` → emit a leaf path, stop (terminal — inventory will be created at commit).
 *   - cycle detected via [visited] → drop the branch silently.
 *   - no methods available → drop the branch.
 *
 * Recursion:
 *   - `make`: each alt_group enumerates **every** child (union-alts). Independent
 *     BOM lines (different alt_groups) are AND-combined; alternatives within an
 *     alt_group are OR-combined and become parallel paths in the resolution graph.
 *     Phase 3's plan() picks which alt to use at runtime; the cap loop drives
 *     unpicked alts to zero within a couple of iterations.
 *   - `move`: one synthetic child = the source (pid, fromLocationId).
 */
private fun walkResolution(
    productId: String,
    locationId: String,
    cumulativeRate: Double,
    depth: Int,
    data: Map<String, List<Map<String, Any?>>>,
    supplyIndex: Map<Pair<String, String>, List<Map<String, Any?>>>,
    visited: Set<Pair<String, String>>,
    chain: List<ResolutionNode>,
    demandId: Any?,
    priority: Int,
    dueDate: LocalDate?,
    requestedQty: Double,
    out: MutableList<ResolutionPath>,
) {
    val key = productId to locationId
    if (key in visited) return

    // Inventory-bearing node → emit one path per supply lot, stop.
    val lots = supplyIndex[key]
    if (lots != null) {
        for (lot in lots) {
            val sid  = lot["supply_id"]?.toString()
            val sQty = (lot["qty"] as? Number)?.toDouble()
            val sDate = parseDateOrNull(
                lot["supply_date"]?.toString() ?: lot["available_date"]?.toString()
            )
            val leaf = ResolutionNode(
                productId, locationId, cumulativeRate, depth,
                method = null, isLeaf = true,
                supplyId = sid, supplyDate = sDate, supplyQty = sQty,
            )
            out.add(ResolutionPath(demandId, priority, dueDate, requestedQty, chain + leaf))
        }
        return
    }

    val methods = getMethods(productId, locationId, data)
    val (method, _) = getPreferredMethod(methods)
    if (method == null) return  // no way to make/buy/move — drop branch

    when (method["type"]) {
        "purchase" -> {
            val leaf = ResolutionNode(productId, locationId, cumulativeRate, depth, method = method, isLeaf = true)
            out.add(ResolutionPath(demandId, priority, dueDate, requestedQty, chain + leaf))
        }
        "make" -> {
            val node = ResolutionNode(productId, locationId, cumulativeRate, depth, method = method, isLeaf = false)
            val nextChain = chain + node
            val nextVisited = visited + key
            val productionLocation = (method["location_id"] as? String)?.trim() ?: locationId
            val variants = variantsForMake(productId, productionLocation, 1.0, method, data) // unit-rate walk
            // Each alt_group emits a path through EVERY child so consolidation can form
            // merged groups at every candidate leaf (Phase 3's plan() picks the actual
            // alt at runtime). But the OR-alternatives within an alt_group SHARE the
            // demand — the planner equal-splits it across them (or picks one) — so
            // provision requestedQty / N per alternative, not full qty each. Provisioning
            // every alt at full over-allocates by N× at each shared leaf (the giant
            // first-pass over-production) and forces the fixed-point iteration to claw it
            // back; splitting right-sizes the total so a single allocation pass suffices.
            // (Independent alt_groups are AND-combined and each carry the full rate.)
            for ((_, childList) in variants) {
                val altCount = childList.size.coerceAtLeast(1)
                val altQty = requestedQty / altCount
                for (alt in childList) {
                    val cPid = (alt["product_id"]  as? String)?.trim() ?: continue
                    val cLid = (alt["location_id"] as? String)?.trim() ?: continue
                    val cRate = (alt["quantity"]    as? Number)?.toDouble() ?: continue
                    if (cRate <= 0) continue
                    walkResolution(
                        productId = cPid,
                        locationId = cLid,
                        cumulativeRate = cumulativeRate * cRate,
                        depth = depth + 1,
                        data = data,
                        supplyIndex = supplyIndex,
                        visited = nextVisited,
                        chain = nextChain,
                        demandId = demandId,
                        priority = priority,
                        dueDate = dueDate,
                        requestedQty = altQty,
                        out = out,
                    )
                }
            }
        }
        "move" -> {
            val node = ResolutionNode(productId, locationId, cumulativeRate, depth, method = method, isLeaf = false)
            val nextChain = chain + node
            val nextVisited = visited + key
            for (child in childMaterialsForMove(method, 1.0)) {
                val cPid = (child["product_id"]  as? String)?.trim() ?: continue
                val cLid = (child["location_id"] as? String)?.trim() ?: continue
                val cRate = (child["quantity"]    as? Number)?.toDouble() ?: continue
                if (cRate <= 0) continue
                walkResolution(
                    productId = cPid,
                    locationId = cLid,
                    cumulativeRate = cumulativeRate * cRate,
                    depth = depth + 1,
                    data = data,
                    supplyIndex = supplyIndex,
                    visited = nextVisited,
                    chain = nextChain,
                    demandId = demandId,
                    priority = priority,
                    dueDate = dueDate,
                    requestedQty = requestedQty,
                    out = out,
                )
            }
        }
    }
}

