package com.allocator.services

import org.slf4j.LoggerFactory
import kotlin.math.min

private val gcLog = LoggerFactory.getLogger("com.allocator.services.GCEngine")

/**
 * Trim a pegging subtree to [effectiveCap], returning excess inventory and
 * budget in-place. Replaces the snapshot-restore + second-pass re-plan loop
 * in [planMethodSlot] for the AND-min partial-fulfillment case.
 *
 * Returns a new (immutable) pegging node with corrected quantities.
 * [inventory] and [budget] are mutated in-place as excess is returned.
 *
 * Node dispatch:
 *   "demand"     — update committed_qty; trim WO children then supply children
 *   "work_order" — trim quantity; recurse into AND/OR children with bom_rate scaling
 *   "supply"     — return excess qty to inventory bucket + restore budget entries
 *   "purchase"   — leaf (new procurement); cap quantity but no inventory return
 *   "operation"  — capacity metadata only; skip
 *   "resource"   — capacity metadata only; skip
 *   unknown      — return unchanged
 */
internal fun garbageCollectPegging(
    node: Map<String, Any?>,
    effectiveCap: Double,
    inventory: MutableList<MutableMap<String, Any?>>,
    budget: MutableMap<String, Double>?,
): Map<String, Any?> = when (node["type"] as? String) {
    "supply"     -> gcSupplyNode(node, effectiveCap, inventory, budget)
    "work_order" -> gcWoNode(node, effectiveCap, inventory, budget)
    "demand"     -> gcDemandNode(node, effectiveCap, inventory, budget)
    "purchase"   -> {
        val q = (node["quantity"] as? Number)?.toDouble() ?: 0.0
        if (q <= effectiveCap + 1e-9) node else node + ("quantity" to roundQty(effectiveCap))
    }
    "operation", "resource" -> node   // capacity metadata — no inventory to return
    else -> node
}

// ── Supply leaf ──────────────────────────────────────────────────────────────

private fun gcSupplyNode(
    node: Map<String, Any?>,
    effectiveCap: Double,
    inventory: MutableList<MutableMap<String, Any?>>,
    budget: MutableMap<String, Double>?,
): Map<String, Any?> {
    val nodeQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
    if (nodeQty <= effectiveCap + 1e-9) return node

    val excess    = nodeQty - effectiveCap
    val pid       = node["product_id"]?.toString() ?: ""
    val lid       = node["location_id"]?.toString() ?: ""
    val supplyId  = node["supply_id"]?.toString()

    // Return excess to the originating inventory bucket
    val bucket = findInventoryBucket(inventory, pid, lid, supplyId)
    if (bucket != null) {
        val cur = (bucket["qty"] as? Number)?.toDouble() ?: 0.0
        bucket["qty"] = cur + excess
        gcLog.debug("[GC-supply] returned {} of {}@{} lot={}", excess, pid, lid, supplyId ?: "(agg)")
    } else {
        gcLog.warn("[GC-supply] bucket not found for {}@{} supply_id={} — {} units lost", pid, lid, supplyId, excess)
    }

    // Restore budget: aggregate component key + per-lot key (if present)
    if (budget != null) {
        val componentKey = "$pid|$lid"
        budget[componentKey] = (budget[componentKey] ?: 0.0) + excess
        if (supplyId != null) {
            val lotKey = "$componentKey|$supplyId"
            if (budget.containsKey(lotKey)) budget[lotKey] = budget[lotKey]!! + excess
        }
    }

    // Keep exact double — mirrors the "do not round supply leaves" policy from
    // consumeFromInventory (PlanningEngine.kt:2249). GC returns exact excess to
    // inventory; rounding the leaf to an integer breaks the conservation identity
    // (leftover + pegged ≠ initial by up to 0.5 × number_of_gc_trimmed_leaves).
    return node + ("quantity" to effectiveCap)
}

// ── Work-order node ──────────────────────────────────────────────────────────

private fun gcWoNode(
    node: Map<String, Any?>,
    effectiveCap: Double,
    inventory: MutableList<MutableMap<String, Any?>>,
    budget: MutableMap<String, Double>?,
): Map<String, Any?> {
    val woQty = (node["quantity"] as? Number)?.toDouble() ?: 0.0
    if (woQty <= effectiveCap + 1e-9) return node

    val childrenRelation = node["children_relation"] as? String
    @Suppress("UNCHECKED_CAST")
    val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()

    val newChildren: List<Map<String, Any?>> = when (childrenRelation) {
        "or" ->
            // OR-split (variant alternatives under a make WO): trim last child first
            gcTrimOrChildren(children, effectiveCap, inventory, budget)

        else -> {
            // AND-group (standard BOM children, or null for single child).
            // bom_rate: prefer explicit "gc_bom_rate" tag written during first pass;
            // fall back to committed_qty/woQty derived from the tree (same by construction).
            children.map { child ->
                when (child["type"] as? String) {
                    "operation", "resource" -> child   // capacity metadata — skip
                    else -> {
                        val bomRate = (child["gc_bom_rate"] as? Number)?.toDouble()
                            ?: run {
                                val cq = committedQtyOf(child)
                                if (woQty > 1e-9) cq / woQty else 0.0
                            }
                        garbageCollectPegging(child, effectiveCap * bomRate, inventory, budget)
                    }
                }
            }
        }
    }

    return node + ("quantity" to roundQty(effectiveCap)) + ("children" to newChildren)
}

// ── Demand node ──────────────────────────────────────────────────────────────

private fun gcDemandNode(
    node: Map<String, Any?>,
    effectiveCap: Double,
    inventory: MutableList<MutableMap<String, Any?>>,
    budget: MutableMap<String, Double>?,
): Map<String, Any?> {
    val committed = (node["committed_qty"] as? Number)?.toDouble() ?: 0.0
    if (committed <= effectiveCap + 1e-9) return node

    @Suppress("UNCHECKED_CAST")
    val children = (node["children"] as? List<Map<String, Any?>>) ?: emptyList()

    // Separate supply draws from WO/method nodes; keep other types untouched
    val supplyChildren = children.filter { it["type"] == "supply" }
    val woChildren     = children.filter { it["type"] == "work_order" }
    val otherChildren  = children.filter { it["type"] != "supply" && it["type"] != "work_order" }

    // Trim WO children first (last method slot = least preferred, drain first)
    var excess = committed - effectiveCap
    val newWoChildren = woChildren.reversed().map { wo ->
        if (excess <= 1e-9) return@map wo
        val woQty = (wo["quantity"] as? Number)?.toDouble() ?: 0.0
        val trim  = min(woQty, excess)
        excess -= trim
        garbageCollectPegging(wo, woQty - trim, inventory, budget)
    }.reversed()

    // Trim supply children (LIFO — return the latest inventory buckets first)
    val newSupplyChildren = supplyChildren.reversed().map { supply ->
        if (excess <= 1e-9) return@map supply
        val sq   = (supply["quantity"] as? Number)?.toDouble() ?: 0.0
        val trim = min(sq, excess)
        excess -= trim
        garbageCollectPegging(supply, sq - trim, inventory, budget)
    }.reversed()

    val newChildren = newSupplyChildren + newWoChildren + otherChildren
    return node + ("committed_qty" to roundQty(effectiveCap)) + ("children" to newChildren)
}

// ── OR-split trimmer (variant-level children inside a WO) ───────────────────

private fun gcTrimOrChildren(
    children: List<Map<String, Any?>>,
    targetTotal: Double,
    inventory: MutableList<MutableMap<String, Any?>>,
    budget: MutableMap<String, Double>?,
): List<Map<String, Any?>> {
    // Walk in reverse (last = least-preferred), reduce until sum == targetTotal
    var remaining = targetTotal
    return children.reversed().map { child ->
        val childQty = committedQtyOf(child)
        when {
            remaining >= childQty - 1e-9 -> {
                remaining -= childQty
                child                    // keep fully — no trimming needed
            }
            remaining > 1e-9 -> {
                val cap = remaining
                remaining = 0.0
                garbageCollectPegging(child, cap, inventory, budget)
            }
            else -> garbageCollectPegging(child, 0.0, inventory, budget)
        }
    }.reversed()
}

// ── WO flat-list scaler ──────────────────────────────────────────────────────

/**
 * Scale a flat work-order list by [scale], dropping lots that round to zero.
 *
 * Used after GC trims a child's committed qty: the first-pass WOs were built
 * at the full (pre-AND-min) quantity. Scaling gives proportional quantities
 * without requiring a full re-plan of the WO schedule.
 *
 * Tradeoff: lot count is preserved (no re-splitting at lot boundaries).
 * For planning / resource-loading purposes this is acceptable; exact lot
 * boundaries can be corrected in a follow-up by calling buildWorkOrders again.
 */
internal fun scaleWos(wos: List<Map<String, Any?>>, scale: Double): List<Map<String, Any?>> {
    if (scale >= 1.0 - 1e-9) return wos
    if (scale <= 1e-9) return emptyList()
    return wos.mapNotNull { wo ->
        val q = (wo["quantity"] as? Number)?.toDouble() ?: return@mapNotNull null
        val scaled = roundQty(q * scale)
        if (scaled < 1e-9) null else wo + ("quantity" to scaled)
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Reads committed quantity from any node type (demand → "committed_qty", others → "quantity"). */
internal fun committedQtyOf(node: Map<String, Any?>): Double =
    (node["committed_qty"] as? Number)?.toDouble()
        ?: (node["quantity"] as? Number)?.toDouble()
        ?: 0.0

/** Finds the mutable inventory bucket for (pid, lid, supplyId). */
private fun findInventoryBucket(
    inventory: MutableList<MutableMap<String, Any?>>,
    pid: String,
    lid: String,
    supplyId: String?,
): MutableMap<String, Any?>? {
    val candidates: List<MutableMap<String, Any?>> =
        (inventory as? IndexedInventory)?.idx?.get(pid to lid)
            ?: inventory.filter {
                it["product_id"]?.toString() == pid && it["location_id"]?.toString() == lid
            }
    return if (supplyId != null)
        candidates.find { it["supply_id"]?.toString() == supplyId }
    else
        candidates.firstOrNull()
}
